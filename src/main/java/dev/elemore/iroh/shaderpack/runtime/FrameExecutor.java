package dev.elemore.iroh.shaderpack.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.elemore.iroh.shaderpack.graph.FormatCaps;
import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PackPlan;
import dev.elemore.iroh.shaderpack.graph.PassGraph;
import dev.elemore.iroh.shaderpack.graph.ResourcePlan;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P3-3 frame executor: runs one folder's frame plan ({@link PassGraph.Frame} + {@link ResourcePlan}) on a
 * {@link GpuBackend}. It owns no Vulkan: images, pipelines, barriers, clears, mips, copies, draws and dispatches are
 * backend calls, so the same code is checked by the recording fake device and executed by a headless device offline and
 * by Iroh's renderer in game.
 * <ul>
 *   <li>Images from the resource plan at the input's render size, named {@code pack:<target>} ({@code #main}/{@code #alt}
 *       for pairs), allocated through a {@link MemoryGate} (MemoryGuard non-essential in game), zeroed by the backend.</li>
 *   <li>Halves come from the plan (no flip logic here: the plan froze it). Barriers: any read after a write, any write
 *       after a read or write of the same image.</li>
 *   <li>Passes before {@link PackFrameInputs#startStage()} are supplied by the inputs (captured or synthetic images);
 *       geometry passes after it need the game and are skipped offline (named in the trace).</li>
 *   <li>Fullscreen passes draw a 0..1 quad: iris_Vertex / gl_MultiTexCoord0 = (x, y, 0, 1), compat matrices identity
 *       model-view, ortho(0,1,0,1,-1,1) projection (Iris composite convention), positive-height viewport (RQ-16
 *       Orientation: targets in GL row order), scaled by {@code scale.<program>} from the origin (GL bottom-left).</li>
 * </ul>
 */
public final class FrameExecutor {

    public interface MemoryGate {
        /** may an optional pack image of this size be allocated? (in game: MemoryGuard.allow(bytes, false)) */
        boolean allow(String name, long bytes);
    }

    /** offline gate: a hard cap on everything the executor allocates (default 1 GiB) */
    public static MemoryGate cap(long bytes) {
        long[] used = {0};
        return (name, b) -> {
            if (used[0] + b > bytes)
                return false;
            used[0] += b;
            return true;
        };
    }

    public static final List<String> STAGES = List.of("begin", "shadow", "shadowcomp", "prepare", "gbuffers-solid", "deferred",
            "gbuffers-translucent", "composite", "final");

    private final PackData data;
    private final PassGraph.Frame frame;
    private final ResourcePlan plan;
    private final GpuBackend gpu;
    private final MemoryGate gate;
    public final List<String> trace = new ArrayList<>();
    public final List<String> problems = new ArrayList<>();
    /** image key (target#half, depthtex0, shadowtex1, noisetex, custom name, dummy:...) -> handle */
    public final Map<String, Integer> images = new TreeMap<>();
    private final Map<String, Integer> pipelines = new LinkedHashMap<>();
    private final Map<Integer, String> lastAccess = new HashMap<>();
    private final Map<String, Integer> uniformBuffers = new HashMap<>();
    private int width, height;
    private boolean prepared;
    /** optional: called after each fullscreen pass with the attachment keys (the frame is submitted around it: debug dumps only) */
    public java.util.function.BiConsumer<String, List<String>> afterPass;
    public int passIndex, drawCount;
    /** backend image the final output is blitted into after the frame (post mode: the scene colour), -1 = none */
    public int presentTo = -1;
    /** pass ids not executed (bisecting in game: iroh.pack.skip) */
    public Set<String> skip = Set.of();
    /** called after the inputs were uploaded/copied (capture hook) */
    public Runnable afterInputs;

    public FrameExecutor(PackData data, PackPlan pp, String folder, GpuBackend gpu, MemoryGate gate) {
        this.data = data;
        this.frame = pp.frames.get(folder);
        this.plan = pp.resources.get(folder);
        if (frame == null)
            throw new IllegalArgumentException("no folder " + folder + " in the plan (have " + pp.frames.keySet() + ")");
        this.gpu = gpu;
        this.gate = gate;
    }

    public PassGraph.Frame frame() {
        return frame;
    }

    // ================================================================================================== prepare

    public void prepare(PackFrameInputs in) {
        width = in.width();
        height = in.height();
        for (ResourcePlan.Image im : plan.images) {
            int w, h, dpt = 1;
            if (im.relative) {
                w = (int) Math.max(1, Math.round(width * im.fx));
                h = (int) Math.max(1, Math.round(height * im.fy));
            } else {
                w = (int) im.w;
                h = (int) im.h;
                dpt = (int) im.depth;
            }
            if (!TexelCodec.supported(im.vkFormat)) {
                problems.add("image " + im.name + ": no codec / format " + im.vkFormat);
                continue;
            }
            int mips = im.mips ? 1 + (int) Math.floor(Math.log(Math.max(w, h)) / Math.log(2)) : 1;
            Set<String> usage = new LinkedHashSet<>(im.usage);
            usage.add("SAMPLED");
            usage.add("TRANSFER_SRC");
            usage.add("TRANSFER_DST");
            if (im.role.contains("depth"))
                usage.remove("COLOR_ATTACHMENT");
            String base = switch (im.role) {
                case "depth", "shadowdepth" -> im.name.replace(" (main depth)", "");
                default -> im.name;
            };
            for (int l = 0; l < im.layers; l++) {
                String key = im.layers == 2 ? base + (l == 0 ? "#main" : "#alt") : base;
                GpuBackend.ImageDesc d = new GpuBackend.ImageDesc("pack:" + key, im.vkFormat, w, h, dpt, mips, usage);
                if (!gate.allow(d.name(), d.bytes())) {
                    problems.add("memory gate refused " + d.name() + " (" + (d.bytes() >> 10) + " KiB): the ladder's next rung applies");
                    continue;
                }
                images.put(key, gpu.createImage(d));
            }
        }
        // output of final
        images.put("output", gpu.createImage(new GpuBackend.ImageDesc("pack:output", "R8G8B8A8_UNORM", width, height, 1, 1,
                Set.of("COLOR_ATTACHMENT", "SAMPLED", "TRANSFER_SRC", "TRANSFER_DST"))));
        // pipelines for the passes this frame will run
        for (PassGraph.Pass p : frame.passes) {
            if (!runs(p, in))
                continue;
            PackData.Unit u = data.units.get(p.unit);
            if (u == null) {
                problems.add(p.id + ": unit " + p.unit + " missing from the dump");
                continue;
            }
            try {
                pipelines.put(p.id, gpu.createPipeline(pipelineDesc(p, u)));
            } catch (IOException | RuntimeException e) {
                problems.add(p.id + ": pipeline not created: " + e);
            }
        }
        prepared = true;
    }

    private boolean runs(PassGraph.Pass p, PackFrameInputs in) {
        int start = STAGES.indexOf(in.startStage());
        int st = STAGES.indexOf(p.stage);
        if (st < start)
            return false;
        return p.kind.equals("fullscreen") || p.kind.equals("compute");
    }

    private GpuBackend.PipelineDesc pipelineDesc(PassGraph.Pass p, PackData.Unit u) throws IOException {
        JsonObject r = u.reflection();
        List<GpuBackend.VertexInput> inputs = new ArrayList<>();
        if (r.has("vertexInputs"))
            for (JsonElement e : r.getAsJsonArray("vertexInputs")) {
                JsonObject o = e.getAsJsonObject();
                int loc = o.get("location").getAsInt();
                String feed = switch (loc) {
                    case 0, 12 -> "position";
                    case 2, 14 -> "uv0";
                    case 1, 13 -> "white";
                    default -> "zero";
                };
                inputs.add(new GpuBackend.VertexInput(loc, o.get("type").getAsString(), feed));
            }
        List<String> formats = new ArrayList<>();
        List<Object> blend = new ArrayList<>();
        for (Map<String, Object> w : p.writes) {
            String t = (String) w.get("target");
            formats.add(t.equals("output") ? "R8G8B8A8_UNORM" : formatOf(t));
            Object b = p.blend;
            if (b instanceof Map<?, ?> m)
                b = m.get(t);
            blend.add(b == null ? "off" : b);
        }
        List<GpuBackend.Slot> slots = new ArrayList<>();
        slots.add(new GpuBackend.Slot(0, 0, "UNIFORM_BUFFER", "IrohPackUniforms", "IrohPackUniforms"));
        for (String k : new String[]{"samplers", "images"})
            if (r.has(k))
                for (JsonElement e : r.getAsJsonArray(k)) {
                    JsonObject o = e.getAsJsonObject();
                    slots.add(new GpuBackend.Slot(o.get("set").getAsInt(), o.get("binding").getAsInt(),
                            k.equals("images") ? "STORAGE_IMAGE" : "SAMPLER", o.get("type").getAsString(), o.get("name").getAsString()));
                }
        if (r.has("buffers"))
            for (JsonElement e : r.getAsJsonArray("buffers")) {
                JsonObject o = e.getAsJsonObject();
                slots.add(new GpuBackend.Slot(o.get("set").getAsInt(), o.get("binding").getAsInt(),
                        o.get("type").getAsString().equals("ssbo") ? "STORAGE_BUFFER" : "UNIFORM_BUFFER", o.get("type").getAsString(), o.get("name").getAsString()));
            }
        if (p.kind.equals("compute"))
            return new GpuBackend.PipelineDesc(p.id, null, null, data.spirv(u, "compute"), inputs, formats, blend, slots);
        return new GpuBackend.PipelineDesc(p.id, data.spirv(u, "vertex"), data.spirv(u, "fragment"), null, inputs, formats, blend, slots);
    }

    private String formatOf(String target) {
        for (ResourcePlan.Image im : plan.images)
            if (im.name.equals(target))
                return im.vkFormat;
        return "R8G8B8A8_UNORM";
    }

    // ==================================================================================================== frame

    public void frame(PackFrameInputs in) {
        if (!prepared)
            prepare(in);
        gpu.beginFrame();
        lastAccess.clear();
        trace.add("frame start=" + in.startStage() + " size=" + width + "x" + height);
        int start = STAGES.indexOf(in.startStage());

        // halves at the start stage: flips of passes before it (begin/prepare) are replayed from the plan
        Set<String> flippedBefore = new LinkedHashSet<>();
        for (PassGraph.Pass p : frame.passes) {
            if (STAGES.indexOf(p.stage) >= start)
                break;
            for (String t : p.flipsAfter)
                if (!flippedBefore.remove(t))
                    flippedBefore.add(t);
        }
        // clears: targets with clear=true that the inputs do not supply (both halves)
        JsonObject targets = targetsJson();
        for (Map.Entry<String, PassGraph.Target> e : frame.targets.entrySet()) {
            PassGraph.Target t = e.getValue();
            if (!t.clear || in.suppliedImages().contains(t.name) || (t.writers.isEmpty() && t.readers.isEmpty()))
                continue;
            float[] cc = clearColour(t, targets, in);
            for (String key : keysOf(t.name)) {
                Integer h = images.get(key);
                if (h == null)
                    continue;
                access(h, "TRANSFER_WRITE");
                gpu.clear(h, cc);
                trace.add("clear " + key + " " + java.util.Arrays.toString(cc));
            }
        }
        // supplied images: uploaded into the half the first replayed reader sees
        for (String name : in.suppliedImages()) {
            String key = name;
            if (images.containsKey(name + "#main"))
                key = name + (flippedBefore.contains(name) ? "#alt" : "#main");
            Integer h = images.get(key);
            if (h == null) {
                trace.add("supplied " + name + " has no plan image: ignored");
                continue;
            }
            GpuBackend.ImageDesc d = gpu.desc(h);
            int[] ex = in.extent(name);
            if (ex[0] != d.width() || ex[1] != d.height() || ex[2] != d.depth()) {
                problems.add("supplied " + name + " is " + ex[0] + "x" + ex[1] + "x" + ex[2] + ", plan image " + d.width() + "x" + d.height() + "x" + d.depth());
                continue;
            }
            access(h, "TRANSFER_WRITE");
            gpu.upload(h, in.image(name));
            trace.add("upload " + key);
        }
        // copies from backend images (post mode) and forced clears
        for (Map.Entry<String, Integer> e : in.copySources().entrySet()) {
            String key = images.containsKey(e.getKey() + "#main") ? e.getKey() + (flippedBefore.contains(e.getKey()) ? "#alt" : "#main") : e.getKey();
            Integer h = images.get(key);
            if (h == null)
                continue;
            access(e.getValue(), "TRANSFER_READ");
            access(h, "TRANSFER_WRITE");
            gpu.blit(e.getValue(), h);
            trace.add("blit input -> " + key);
        }
        for (Map.Entry<String, float[]> e : in.clearValues().entrySet())
            for (String key : keysOf(e.getKey())) {
                Integer h = images.get(key);
                if (h == null)
                    continue;
                access(h, "TRANSFER_WRITE");
                gpu.clear(h, e.getValue());
                trace.add("clear " + key + " (input value)");
            }
        if (afterInputs != null)
            afterInputs.run();
        // passes
        for (PassGraph.Pass p : frame.passes) {
            int st = STAGES.indexOf(p.stage);
            if (st < start)
                continue;
            if (skip.contains(p.id)) {
                trace.add("skip " + p.id + " (iroh.pack.skip)");
                continue;
            }
            switch (p.kind) {
                case "fullscreen" -> {
                    gpu.timeBegin(p.id);
                    fullscreen(p, in);
                    gpu.timeEnd(p.id);
                }
                case "compute" -> {
                    gpu.timeBegin(p.id);
                    compute(p, in);
                    gpu.timeEnd(p.id);
                }
                case "geometry" -> trace.add("skip " + p.id + " (geometry: needs the game)");
                case "capture" -> trace.add("skip " + p.id + " (depth capture: supplied)");
                case "flip" -> trace.add("flip " + p.id + " " + p.flipsAfter + " (frozen in the plan)");
                default -> trace.add("skip " + p.id + " (" + p.kind + ")");
            }
        }
        // end of frame: alt -> main copies for flipped, never-cleared pairs
        for (PassGraph.Target t : frame.targets.values()) {
            if (!(t.endOfFrameCopy && t.pairNeeded()))
                continue;
            Integer a = images.get(t.name + "#alt"), m = images.get(t.name + "#main");
            if (a == null || m == null)
                continue;
            access(a, "TRANSFER_READ");
            access(m, "TRANSFER_WRITE");
            gpu.copy(a, m);
            trace.add("copy " + t.name + "#alt -> #main (end of frame)");
        }
        if (presentTo >= 0) {
            Integer out = images.get("output");
            access(out, "TRANSFER_READ");
            access(presentTo, "TRANSFER_WRITE");
            gpu.blit(out, presentTo);
            trace.add("present output");
        }
        gpu.endFrame();
        trace.add("frame end");
    }

    private JsonObject targetsJson() {
        return new JsonObject();
    }

    private List<String> keysOf(String target) {
        if (images.containsKey(target + "#main"))
            return List.of(target + "#main", target + "#alt");
        return List.of(target);
    }

    private float[] clearColour(PassGraph.Target t, JsonObject unused, PackFrameInputs in) {
        if (t.clearColor != null) {
            float[] v = vec4(t.clearColor);
            if (v != null)
                return v;
        }
        if (t.name.equals("colortex0")) {
            double[] fog = in.uniforms().getOrDefault("fogColor", new double[]{0, 0, 0});
            return new float[]{(float) fog[0], (float) fog[1], (float) fog[2], 1};
        }
        if (t.name.equals("colortex1") || t.name.startsWith("shadowcolor"))
            return new float[]{1, 1, 1, 1};
        FormatCaps.Row row = FormatCaps.of(t.format);
        return row != null && row.promoted() ? new float[]{0, 0, 0, 1} : new float[]{0, 0, 0, 0};
    }

    static float[] vec4(String s) {
        Matcher m = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?").matcher(s.replaceAll("vec4", ""));
        List<Float> l = new ArrayList<>();
        while (m.find())
            l.add(Float.parseFloat(m.group()));
        if (l.isEmpty())
            return null;
        if (l.size() == 1)
            return new float[]{l.get(0), l.get(0), l.get(0), l.get(0)};
        float[] r = new float[4];
        for (int i = 0; i < 4; i++)
            r[i] = i < l.size() ? l.get(i) : (i == 3 ? 1 : 0);
        return r;
    }

    private void access(int image, String kind) {
        String last = lastAccess.get(image);
        boolean write = kind.endsWith("WRITE");
        if (last != null && (last.endsWith("WRITE") || write)) {
            gpu.barrier(image, last, kind);
        }
        lastAccess.put(image, kind);
    }

    private Integer imageFor(String target, String half) {
        if (target.equals("output"))
            return images.get("output");
        Integer h = images.get(target + "#" + half);
        return h != null ? h : images.get(target);
    }

    private void fullscreen(PassGraph.Pass p, PackFrameInputs in) {
        Integer pipe = pipelines.get(p.id);
        if (pipe == null) {
            trace.add("skip " + p.id + " (no pipeline)");
            return;
        }
        PackData.Unit u = data.units.get(p.unit);
        mipped = new HashSet<>();
        for (String mip : p.mipmapsBefore) {
            String[] tp = mip.replace("(", "").replace(")", "").split(" ");
            mipped.add(tp[0]);
            Integer h = imageFor(tp[0], tp[1]);
            if (h != null) {
                access(h, "TRANSFER_WRITE");
                gpu.generateMips(h);
                trace.add("mips " + tp[0] + "#" + tp[1]);
            }
        }
        Map<Integer, GpuBackend.Bind> set0 = binds(p, u, 0);
        Map<Integer, GpuBackend.Bind> set1 = binds(p, u, 1);
        for (GpuBackend.Bind b : set0.values())
            if (b.image() >= 0)
                access(b.image(), "STORAGE".equals(b.sampler()) ? "STORAGE_READ" : "SAMPLED_READ");
        int[] colour = new int[p.writes.size()];
        List<String> keys = new ArrayList<>();
        int aw = Integer.MAX_VALUE, ah = Integer.MAX_VALUE;
        for (int i = 0; i < colour.length; i++) {
            Map<String, Object> w = p.writes.get(i);
            Integer h = imageFor((String) w.get("target"), (String) w.get("half"));
            if (h == null) {
                problems.add(p.id + ": no image for " + w.get("target") + "/" + w.get("half"));
                return;
            }
            colour[i] = h;
            keys.add(w.get("target") + "#" + w.get("half"));
            access(h, "ATTACHMENT_WRITE");
            aw = Math.min(aw, gpu.desc(h).width());
            ah = Math.min(ah, gpu.desc(h).height());
        }
        double s = 1;
        if (p.scale instanceof List<?> l && !l.isEmpty())
            s = ((Number) l.get(0)).doubleValue();
        int[] viewport = {0, 0, (int) Math.max(1, Math.round(aw * s)), (int) Math.max(1, Math.round(ah * s))};
        byte[] ubo = uniformBlock(u, in, true);
        gpu.draw(new GpuBackend.Draw(p.id, pipe, colour, viewport, set0, set1, ubo));
        trace.add("draw " + p.id + " -> " + keys + " viewport " + viewport[2] + "x" + viewport[3] + " reads " + readKeys(p));
        passIndex = frame.passes.indexOf(p);
        drawCount++;
        if (afterPass != null) {
            gpu.endFrame();
            afterPass.accept(p.id, keys);
            gpu.beginFrame();
            lastAccess.clear();
        }
    }

    private void compute(PassGraph.Pass p, PackFrameInputs in) {
        mipped = Set.of();
        Integer pipe = pipelines.get(p.id);
        if (pipe == null) {
            trace.add("skip " + p.id + " (no pipeline)");
            return;
        }
        PackData.Unit u = data.units.get(p.unit);
        Map<Integer, GpuBackend.Bind> set0 = binds(p, u, 0);
        for (GpuBackend.Bind b : set0.values())
            if (b.image() >= 0)
                access(b.image(), "STORAGE".equals(b.sampler()) ? "STORAGE_WRITE" : "SAMPLED_READ");
        int[] groups = groups(p, u);
        gpu.dispatch(new GpuBackend.Dispatch(p.id, pipe, groups, set0, binds(p, u, 1), uniformBlock(u, in, false)));
        trace.add("dispatch " + p.id + " " + java.util.Arrays.toString(groups));
    }

    private int[] groups(PassGraph.Pass p, PackData.Unit u) {
        Object g = p.dispatch == null ? null : p.dispatch.get("groups");
        if (g instanceof List<?> l)
            return new int[]{((Number) l.get(0)).intValue(), ((Number) l.get(1)).intValue(), ((Number) l.get(2)).intValue()};
        int[] ls = u.localSize();
        if (ls == null)
            ls = new int[]{1, 1, 1};
        double fx = 1, fy = 1;
        String r = u.constDirectives().get("workGroupsRender");
        if (r != null) {
            float[] v = vec4(r);
            if (v != null) {
                fx = v[0];
                fy = v[1];
            }
        }
        return new int[]{(int) Math.ceil(width * fx / ls[0]), (int) Math.ceil(height * fy / ls[1]), 1};
    }

    private String readKeys(PassGraph.Pass p) {
        List<String> l = new ArrayList<>();
        for (Map<String, Object> r : p.reads)
            if (r.containsKey("half"))
                l.add(r.get("resource") + "#" + r.get("half"));
        return l.toString();
    }

    // ------------------------------------------------------------------------------------------------ bindings

    private Map<Integer, GpuBackend.Bind> binds(PassGraph.Pass p, PackData.Unit u, int set) {
        Map<Integer, GpuBackend.Bind> m = new TreeMap<>();
        JsonObject r = u.reflection();
        Map<String, Map<String, Object>> reads = new HashMap<>();
        for (Map<String, Object> rd : p.reads)
            reads.put((String) rd.get("name"), rd);
        if (set == 0) {
            for (String k : new String[]{"samplers", "images"}) {
                if (!r.has(k))
                    continue;
                for (JsonElement e : r.getAsJsonArray(k)) {
                    JsonObject o = e.getAsJsonObject();
                    if (o.get("set").getAsInt() != 0)
                        continue;
                    String name = o.get("name").getAsString();
                    String type = o.get("type").getAsString();
                    String fmt = o.has("format") ? o.get("format").getAsString() : null;
                    boolean storage = k.equals("images");
                    m.put(o.get("binding").getAsInt(), resolve(name, type, fmt, storage, reads.get(name)));
                }
            }
        } else if (r.has("buffers")) {
            for (JsonElement e : r.getAsJsonArray("buffers")) {
                JsonObject o = e.getAsJsonObject();
                if (o.get("set").getAsInt() != 1)
                    continue;
                String key = "ssbo:" + o.get("binding").getAsInt();
                Integer b = uniformBuffers.computeIfAbsent(key, x -> gpu.createBuffer("pack:" + key, ssboBytes(o.get("binding").getAsInt())));
                m.put(o.get("binding").getAsInt(), GpuBackend.Bind.buffer(b));
            }
        }
        return m;
    }

    private long ssboBytes(int binding) {
        JsonObject bo = data.properties().getAsJsonObject("bufferObjects");
        if (bo.has(String.valueOf(binding)))
            return bo.getAsJsonObject(String.valueOf(binding)).get("bytes").getAsLong();
        return 256;
    }

    /**
     * Targets the current pass samples WITH mipmaps: the ones its mipmap directive generated (Iris: a buffer is
     * min-filtered LINEAR_MIPMAP_LINEAR only in the pass that mipmaps it, plain LINEAR elsewhere). Every other linear
     * read uses LINEAR_NOMIP (maxLod 0): implicit-LOD texture() calls with large derivatives (FXAA's sub-texel taps)
     * otherwise pick a stale or never-written mip (G1 artefact, 2026-11-22: dark texel outlines from composite7 FXAA).
     */
    private Set<String> mipped = Set.of();

    private GpuBackend.Bind resolve(String name, String type, String fmt, boolean storage, Map<String, Object> read) {
        String sampler = storage ? "STORAGE" : type.contains("Shadow") ? "COMPARE"
                : type.startsWith("u") || type.startsWith("i") ? "NEAREST" : "LINEAR";
        Integer h = null;
        if (read != null) {
            String kind = (String) read.get("kind");
            String res = String.valueOf(read.get("resource"));
            switch (kind) {
                case "colortex", "shadowcolor" -> {
                    h = imageFor(res, String.valueOf(read.getOrDefault("half", "main")));
                    if (sampler.equals("LINEAR") && !mipped.contains(res))
                        sampler = "LINEAR_NOMIP";
                }
                case "colortex-image" -> h = imageFor(res, "main");
                case "depth" -> {
                    h = images.get(res.startsWith("depthtex1") ? "depthtex1" : res.startsWith("depthtex2") ? "depthtex2" : "depthtex0");
                    if (!type.contains("Shadow"))
                        sampler = "NEAREST";
                }
                case "shadowdepth" -> h = images.get(res);
                case "noise" -> h = images.get("noisetex");
                case "image" -> h = images.get(res);
                case "custom", "dynamic" -> h = customTexture(name, res, kind);
                default -> {
                }
            }
        }
        if (h == null)
            h = dummy(type, fmt, storage);
        return GpuBackend.Bind.image(h, sampler);
    }

    /** custom textures: pack files decoded once (PNG); dynamic (mod) textures as neutral 1x1 until the game supplies them */
    private Integer customTexture(String sampler, String res, String kind) {
        String key = "custom:" + sampler;
        if (images.containsKey(key))
            return images.get(key);
        Integer h = null;
        if (kind.equals("custom") && !res.contains(":") && !res.startsWith("fallback")) {
            Path pack = Path.of(data.origin());
            Path file = Files.isDirectory(pack) ? pack.resolve("shaders").resolve(res) : null;
            if (file != null && Files.isRegularFile(file)) {
                try {
                    BufferedImage bi = javax.imageio.ImageIO.read(file.toFile());
                    if (bi != null && (long) bi.getWidth() * bi.getHeight() <= 4096L * 4096) {
                        float[] px = new float[bi.getWidth() * bi.getHeight() * 4];
                        for (int y = 0; y < bi.getHeight(); y++)
                            for (int x = 0; x < bi.getWidth(); x++) {
                                int argb = bi.getRGB(x, bi.getHeight() - 1 - y); // GL row order: row 0 = bottom
                                int i = 4 * (y * bi.getWidth() + x);
                                px[i] = ((argb >> 16) & 0xff) / 255f;
                                px[i + 1] = ((argb >> 8) & 0xff) / 255f;
                                px[i + 2] = (argb & 0xff) / 255f;
                                px[i + 3] = ((argb >>> 24) & 0xff) / 255f;
                            }
                        GpuBackend.ImageDesc d = new GpuBackend.ImageDesc("pack:" + key, "R8G8B8A8_UNORM", bi.getWidth(), bi.getHeight(), 1, 1,
                                Set.of("SAMPLED", "TRANSFER_DST", "TRANSFER_SRC"));
                        if (gate.allow(d.name(), d.bytes())) {
                            h = gpu.createImage(d);
                            access(h, "TRANSFER_WRITE");
                            gpu.upload(h, px);
                        }
                    }
                } catch (IOException e) {
                    problems.add("custom texture " + res + ": " + e.getMessage());
                }
            } else {
                problems.add("custom texture " + res + " not readable from " + pack + " (zip packs: P3-4)");
            }
        }
        if (h == null)
            h = dummy("sampler2D", null, false);
        images.put(key, h);
        return h;
    }

    private Integer dummy(String type, String fmt, boolean storage) {
        boolean three = type.contains("3D");
        boolean uint = type.startsWith("u");
        boolean sint = type.startsWith("i");
        boolean depth = type.contains("Shadow");
        String vk = depth ? "D32_SFLOAT" : uint ? "R32_UINT" : sint ? "R32_UINT" : "R8G8B8A8_UNORM";
        if (storage && fmt != null) {
            FormatCaps.Row row = FormatCaps.of(fmt);
            if (row != null)
                vk = row.vk();
        }
        String key = "dummy:" + (three ? "3d:" : "2d:") + vk + (storage ? ":storage" : "");
        Integer h = images.get(key);
        if (h != null)
            return h;
        Set<String> usage = storage ? Set.of("STORAGE", "SAMPLED", "TRANSFER_DST") : Set.of("SAMPLED", "TRANSFER_DST");
        h = gpu.createImage(new GpuBackend.ImageDesc("pack:" + key, vk, 1, 1, 1, 1, usage));
        if (depth) {
            access(h, "TRANSFER_WRITE");
            gpu.upload(h, new float[]{1, 0, 0, 0}); // far plane: compare passes (lit)
        }
        images.put(key, h);
        return h;
    }

    // ------------------------------------------------------------------------------------------------ uniforms

    /** std140 bytes of the program's IrohPackUniforms block from the inputs (fullscreen compat matrices per pass) */
    byte[] uniformBlock(PackData.Unit u, PackFrameInputs in, boolean fullscreen) {
        JsonObject r = u.reflection();
        if (!r.has("uniformBlock"))
            return new byte[16];
        JsonObject blk = r.getAsJsonObject("uniformBlock");
        int size = Math.max(16, blk.get("size").getAsInt());
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        Map<String, double[]> vals = in.uniforms();
        for (JsonElement e : blk.getAsJsonArray("members")) {
            JsonObject m = e.getAsJsonObject();
            String name = m.get("name").getAsString();
            String type = m.get("type").getAsString();
            int off = m.get("offset").getAsInt();
            double[] v = fullscreen ? fullscreenCompat(name) : null;
            if (v == null)
                v = vals.get(name);
            if (v == null && m.has("default"))
                v = literal(m.get("default").getAsString());
            if (v == null)
                v = defaults(name, type);
            write(b, off, type, v);
        }
        return b.array();
    }

    static final double[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    /** GL ortho(0, 1, 0, 1, -1, 1), column-major */
    static final double[] ORTHO01 = {2, 0, 0, 0, 0, 2, 0, 0, 0, 0, -1, 0, -1, -1, 0, 1};

    static double[] fullscreenCompat(String name) {
        return switch (name) {
            case "iroh_ModelViewMatrix", "iroh_ModelViewMatrixInverse", "iroh_TextureMatrix", "iroh_TextureMatrixInverse",
                 "modelViewMatrix", "modelViewMatrixInverse", "textureMatrix" -> IDENTITY;
            case "iroh_ProjectionMatrix", "iroh_ModelViewProjectionMatrix", "projectionMatrix" -> ORTHO01;
            case "iroh_NormalMatrix", "normalMatrix" -> new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
            default -> null;
        };
    }

    static double[] defaults(String name, String type) {
        if (type.startsWith("mat4"))
            return IDENTITY;
        if (type.startsWith("mat3"))
            return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        return new double[16];
    }

    static double[] literal(String s) {
        s = s.trim();
        if (s.equals("true"))
            return new double[]{1};
        if (s.equals("false"))
            return new double[]{0};
        try {
            return new double[]{Double.parseDouble(s.replaceAll("[fFuU]$", ""))};
        } catch (NumberFormatException e) {
            float[] v = vec4(s);
            if (v == null)
                return null;
            return new double[]{v[0], v[1], v[2], v[3]};
        }
    }

    static void write(ByteBuffer b, int off, String type, double[] v) {
        int arr = 1;
        String base = type;
        Matcher am = Pattern.compile("(\\w+)\\[(\\d+)]").matcher(type);
        if (am.matches()) {
            base = am.group(1);
            arr = Integer.parseInt(am.group(2));
        }
        boolean integer = base.startsWith("i") || base.startsWith("u") || base.startsWith("b") || base.equals("int") || base.equals("uint") || base.equals("bool");
        int cols = 1, rows = 1;
        if (base.startsWith("mat")) {
            cols = base.charAt(3) - '0';
            rows = base.length() > 5 ? base.charAt(5) - '0' : cols;
        } else if (base.contains("vec")) {
            rows = base.charAt(base.length() - 1) - '0';
        }
        int elemStride = base.startsWith("mat") ? cols * 16 : (arr > 1 ? 16 : 0);
        int k = 0;
        for (int a = 0; a < arr; a++) {
            int eoff = off + a * elemStride;
            if (a > 0 && k >= v.length)
                k = 0; // one value repeats (iroh_TextureMatrix identity for every unit)
            for (int c = 0; c < cols; c++)
                for (int r = 0; r < rows; r++) {
                    double x = k < v.length ? v[k] : 0;
                    k++;
                    int pos = eoff + (base.startsWith("mat") ? c * 16 : 0) + r * 4;
                    if (pos + 4 > b.capacity())
                        continue;
                    if (integer)
                        b.putInt(pos, (int) Math.round(x));
                    else
                        b.putFloat(pos, (float) x);
                }
        }
    }

    /** frees every image and pipeline this executor created (world unload, resize, dimension change, disable) */
    public void close() {
        for (Integer h : new java.util.LinkedHashSet<>(images.values()))
            gpu.freeImage(h);
        images.clear();
        for (Integer p : pipelines.values())
            gpu.freePipeline(p);
        pipelines.clear();
        lastAccess.clear();
        prepared = false;
        trace.add("closed");
    }

    // ------------------------------------------------------------------------------------------------ results

    /** read back and check every pack colour target and the output: NaN / inf / range / all-zero */
    public Map<String, Map<String, Object>> inspect() {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        for (Map.Entry<String, Integer> e : images.entrySet()) {
            if (e.getKey().startsWith("dummy:") || e.getKey().startsWith("custom:"))
                continue;
            GpuBackend.ImageDesc d = gpu.desc(e.getValue());
            float[] px = gpu.readback(e.getValue());
            if (px == null)
                continue;
            long nan = 0, inf = 0;
            double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            double[] sum = new double[4];
            int n = px.length / 4;
            for (int i = 0; i < n; i++)
                for (int c = 0; c < 4; c++) {
                    float x = px[4 * i + c];
                    if (Float.isNaN(x)) {
                        nan++;
                        continue;
                    }
                    if (Float.isInfinite(x)) {
                        inf++;
                        continue;
                    }
                    min[c] = Math.min(min[c], x);
                    max[c] = Math.max(max[c], x);
                    sum[c] += x;
                }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("format", d.vkFormat());
            s.put("extent", d.width() + "x" + d.height() + (d.depth() > 1 ? "x" + d.depth() : ""));
            s.put("nan", nan);
            s.put("inf", inf);
            List<Double> mean = new ArrayList<>();
            List<Double> mn = new ArrayList<>(), mx = new ArrayList<>();
            for (int c = 0; c < 4; c++) {
                mean.add(round4(sum[c] / Math.max(1, n)));
                mn.add(round4(min[c]));
                mx.add(round4(max[c]));
            }
            s.put("mean", mean);
            s.put("min", mn);
            s.put("max", mx);
            boolean zero = true;
            for (int c = 0; c < 3; c++)
                if (max[c] != 0 || min[c] != 0)
                    zero = false;
            s.put("allZeroRgb", zero);
            out.put(e.getKey(), s);
        }
        return out;
    }

    static double round4(double v) {
        return Math.round(v * 1e4) / 1e4;
    }
}
