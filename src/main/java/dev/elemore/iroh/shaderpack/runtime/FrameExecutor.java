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
    /** last access kind per backend image handle (P3-8: an array, not a map: no allocation per frame) */
    private final LastAccess lastAccess = new LastAccess();

    static final class LastAccess {
        String[] kinds = new String[256];

        String get(int i) {
            return i >= 0 && i < kinds.length ? kinds[i] : null;
        }

        void put(int i, String k) {
            if (i < 0)
                return;
            if (i >= kinds.length)
                kinds = java.util.Arrays.copyOf(kinds, Math.max(i + 1, kinds.length * 2));
            kinds[i] = k;
        }

        void clear() {
            java.util.Arrays.fill(kinds, null);
        }
    }
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
    /**
     * P3-7 upscaler seam (null = off; set before the first frame). The inputs are at RENDER size (in.width/height,
     * as the render-scale scene target will be); the pack renders at that size up to and including pass {@code after}
     * and at {@code displayW x displayH} after it; the engine computes motion vectors from depth and the
     * previous-frame matrices; {@code upscaler} turns {@code input} into its display-size image; the passes in
     * {@code replaces} are skipped; every later pass runs on display-size copies ("D:" keys) of what it reads/writes.
     */
    public record Upscale(String after, String input, Set<String> replaces, int displayW, int displayH, PackUpscaler upscaler) {}

    public Upscale upscale;
    /** P3-7 C: keep last frame's shadow maps: shadow + shadowcomp stages and their clears are skipped this frame */
    public boolean reuseShadow;
    /**
     * P3-8 dead-pass removal: a fullscreen pass whose every colour output has no reader anywhere in the frame plan
     * (any pass, any frame: the graph's readers include history reads) and is not the output is skipped. Navidson world0
     * with the shipped options: composite (colortex7, the reflection temporal: nothing reads it). The replay goldens
     * prove the picture is unchanged.
     */
    public boolean pruneDead = true;
    private Set<String> deadPasses;

    /** the passes {@link #pruneDead} removes from this frame plan */
    public Set<String> deadPasses() {
        if (deadPasses == null) {
            deadPasses = new java.util.TreeSet<>();
            for (PassGraph.Pass p : frame.passes) {
                if (!p.kind.equals("fullscreen") || p.writes.isEmpty())
                    continue;
                boolean anyRead = false;
                for (Map<String, Object> w : p.writes) {
                    String t = (String) w.get("target");
                    PassGraph.Target tg = frame.targets.get(t);
                    if ("output".equals(t) || tg == null || !tg.readers.isEmpty() || (tg.endOfFrameCopy && tg.pairNeeded()))
                        anyRead = true;
                }
                if (!anyRead)
                    deadPasses.add(p.id);
            }
        }
        return deadPasses;
    }

    /** keep the trace across frames (offline tools only) */
    public boolean keepTrace;
    /** record the trace at all (P3-8: the game turns it off; strings are not even built then) */
    public boolean tracing = true;
    private boolean postCut;
    private int displayW, displayH;
    private final Set<String> displayKeys = new java.util.TreeSet<>();
    private String upscaleOutKey, upscaleInKey;

    /** P3-6: called when the pass loop enters a stage (full-mode capture before "deferred") */
    public java.util.function.Consumer<String> onStage;

    /**
     * image key a replay starting at {@code startStage} uploads a supplied {@code target} into (the half its first
     * reader sees after the plan's flips before that stage), or the plain key for single targets
     */
    public String replayKey(String target, String startStage) {
        if (!images.containsKey(target + "#main"))
            return target;
        int start = STAGES.indexOf(startStage);
        boolean flipped = false;
        for (PassGraph.Pass p : frame.passes) {
            if (STAGES.indexOf(p.stage) >= start)
                break;
            if (p.flipsAfter.contains(target))
                flipped = !flipped;
        }
        return target + (flipped ? "#alt" : "#main");
    }

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
        displayW = upscale != null ? upscale.displayW() : width;
        displayH = upscale != null ? upscale.displayH() : height;
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
                images.put(key, newImage(d));
            }
        }
        // output of final (display size)
        images.put("output", newImage(new GpuBackend.ImageDesc("pack:output", "R8G8B8A8_UNORM", displayW, displayH, 1, 1,
                Set.of("COLOR_ATTACHMENT", "SAMPLED", "TRANSFER_SRC", "TRANSFER_DST"))));
        if (upscale != null)
            prepareUpscale();
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
                List<String> layouts = new ArrayList<>();
                if (p.kind.equals("geometry"))
                    for (PackFrameInputs.Geometry g : batchesFor(p, in))
                        if (g.layout() != null && !layouts.contains(g.layout()))
                            layouts.add(g.layout());
                if (layouts.isEmpty())
                    layouts.add("wide-terrain");
                passLayouts.put(p.id, layouts);
                // P3-9 (S): one pipeline per (program, vertex layout) over the same SPIR-V; the first under the pass id
                for (int li = 0; li < layouts.size(); li++)
                    pipelines.put(li == 0 ? p.id : p.id + "|" + layouts.get(li), gpu.createPipeline(pipelineDesc(p, u, layouts.get(li))));
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
        if (p.kind.equals("geometry")) {
            // pipelines for every terrain-class pass once geometry is supplied at all (a class absent in the first
            // frame, e.g. no water in view, still draws later); other classes only when their batches exist
            if (in.geometry().isEmpty() && !expectGeometry)
                return false;
            for (String g : p.geometry)
                if (sceneClass(g).startsWith("terrain_"))
                    return true;
            return !batchesFor(p, in).isEmpty();
        }
        return p.kind.equals("fullscreen") || p.kind.equals("compute");
    }

    /** caster class -> the scene class whose batches it draws (the shadow pass reuses the main geometry) */
    static String sceneClass(String geometryClass) {
        return switch (geometryClass) {
            case "shadow_terrain_solid" -> "terrain_solid";
            case "shadow_terrain_cutout" -> "terrain_cutout";
            case "shadow_translucent" -> "terrain_translucent";
            case "shadow_entities" -> "entities";
            case "shadow_block_entities" -> "block_entities";
            case "shadow_lightning" -> "lightning";
            default -> geometryClass;
        };
    }

    List<PackFrameInputs.Geometry> batchesFor(PassGraph.Pass p, PackFrameInputs in) {
        List<PackFrameInputs.Geometry> out = new ArrayList<>();
        if (p.geometry == null || in.geometry().isEmpty())
            return out;
        Set<String> want = new HashSet<>();
        for (String g : p.geometry) {
            String c = sceneClass(g);
            want.add(c);
            if (c.equals("terrain_cutout"))
                want.add("terrain_cutout_mipped");
        }
        boolean shadow = shadowPass(p);
        boolean ownShadowList = false;
        if (shadow)
            for (PackFrameInputs.Geometry g : in.geometry())
                if (g.geometryClass().startsWith("shadow:")) {
                    ownShadowList = true;
                    break;
                }
        for (PackFrameInputs.Geometry g : in.geometry()) {
            String c = g.geometryClass();
            if (c.startsWith("shadow:")) {
                if (shadow && want.contains(c.substring(7)))
                    out.add(g);
            } else if ((!shadow || !ownShadowList) && want.contains(c))
                out.add(g);
        }
        return out;
    }

    /** P3-8: whether a geometry pass has batches, from a per-pass answer cached while the geometry list is the same object */
    private final Map<PassGraph.Pass, Object[]> hasBatchesCache = new java.util.IdentityHashMap<>();

    private boolean hasBatches(PassGraph.Pass p, PackFrameInputs in) {
        List<PackFrameInputs.Geometry> all = in.geometry();
        Object[] c = hasBatchesCache.get(p);
        if (c == null || c[0] != all || (Long) c[2] != in.geometryVersion()) {
            c = new Object[]{all, !batchesFor(p, in).isEmpty(), in.geometryVersion()};
            hasBatchesCache.put(p, c);
        }
        return (Boolean) c[1];
    }

    boolean shadowPass(PassGraph.Pass p) {
        return p.id.startsWith("shadow:");
    }

    /** P3-9: vertex layout of each geometry pass, from its supplied batches at prepare ("wide-terrain" default) */
    private final Map<String, List<String>> passLayouts = new HashMap<>();

    private GpuBackend.PipelineDesc pipelineDesc(PassGraph.Pass p, PackData.Unit u, String layout) throws IOException {
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
        GpuBackend.Geo geo = null;
        if (p.kind.equals("geometry")) {
            boolean shadow = shadowPass(p);
            Integer dh = images.get(shadow ? "shadowtex0" : "depthtex0");
            String df = dh != null ? gpu.desc(dh).vkFormat() : "D32_SFLOAT";
            boolean sky = p.geometry.stream().anyMatch(g -> g.startsWith("sky_"));
            boolean terrain = layout.equals("wide-terrain");
            // P3-9: sky draws test nothing and write no depth (vanilla: depthMask false, drawn first); vanilla-format
            // classes keep culling off (entity models, particles and the hand are drawn without GL_CULL_FACE often)
            geo = new GpuBackend.Geo(layout, df, !sky, !sky, terrain && !shadow, false);
        }
        return new GpuBackend.PipelineDesc(p.id, data.spirv(u, "vertex"), data.spirv(u, "fragment"), null, inputs, formats, blend, slots, geo);
    }

    private String formatOf(String target) {
        for (ResourcePlan.Image im : plan.images)
            if (im.name.equals(target))
                return im.vkFormat;
        return "R8G8B8A8_UNORM";
    }

    // ==================================================================================================== frame

    public void frame(PackFrameInputs in) {
        frameBegin(in);
        runStages(in, in.startStage(), null, false);
        frameEnd(in);
    }

    // P3-11: one frame may be recorded in SEGMENTS around the renderer's own world draws (frameBegin, runStages ...,
    // frameEnd); frame() is the three in a row and records exactly what it recorded before (goldens unchanged)
    private int segStart;
    private String segLastStage;
    private final List<PassGraph.Pass> heldCaptures = new ArrayList<>();
    /** P3-11: terrain-class geometry passes get their pipelines and depth clears even when this frame's inputs carry no batches yet (world pass: batches arrive mid-frame) */
    public boolean expectGeometry;
    /** P3-11: backend image holding raster draws made inside the world pass over transparent black (premultiplied); composited OVER the output before present; -1 = none */
    public int rasterOver = -1;

    /** P3-11 segment 0: plan, clears, inputs, copies (no passes); the backend frame begins here */
    public void frameBegin(PackFrameInputs in) {
        if (!prepared)
            prepare(in);
        // P3-7: the trace holds the LAST frame only (it grew without bound in game: one list entry per pass per frame)
        if (!keepTrace)
            trace.clear();
        gpu.beginFrame(); // V-6/V-7: lastAccess holds images created since the last frame (TRANSFER_WRITE) and the last frame's tail
        postCut = false;
        if (tracing) trace.add("frame start=" + in.startStage() + " size=" + width + "x" + height);
        int start = STAGES.indexOf(in.startStage());

        // halves at the start stage: flips of passes before it (begin/prepare) are replayed from the plan
        Set<String> flippedBefore = flippedCache.get(start);
        if (flippedBefore == null) {
            flippedBefore = new LinkedHashSet<>();
            for (PassGraph.Pass p : frame.passes) {
                if (STAGES.indexOf(p.stage) >= start)
                    break;
                for (String t : p.flipsAfter)
                    if (!flippedBefore.remove(t))
                        flippedBefore.add(t);
            }
            flippedCache.put(start, flippedBefore);
        }
        // clears: targets with clear=true that the inputs do not supply (both halves); planned once per inputs shape
        Set<String> supplied = in.suppliedImages();
        if (clearPlan == null || clearPlanSupplied != supplied || clearPlanReuse != reuseShadow) {
            clearPlan = new ArrayList<>();
            clearPlanSupplied = supplied;
            clearPlanReuse = reuseShadow;
            for (Map.Entry<String, PassGraph.Target> e : frame.targets.entrySet()) {
                PassGraph.Target t = e.getValue();
                if (!t.clear || supplied.contains(t.name) || (t.writers.isEmpty() && t.readers.isEmpty()))
                    continue;
                if (reuseShadow && t.name.startsWith("shadowcolor"))
                    continue;
                for (String key : keysOf(t.name)) {
                    Integer h = images.get(key);
                    if (h != null)
                        clearPlan.add(new Object[]{h, clearColour(t, null, in), t.name.equals("colortex0") && t.clearColor == null});
                }
            }
        }
        for (int ci = 0; ci < clearPlan.size(); ci++) {
            Object[] cp = clearPlan.get(ci);
            float[] cc = (float[]) cp[1];
            if ((Boolean) cp[2]) { // colortex0 without an explicit clear colour: this frame's fog colour
                double[] fog = in.uniforms().get("fogColor");
                if (fog != null) {
                    cc[0] = (float) fog[0];
                    cc[1] = (float) fog[1];
                    cc[2] = (float) fog[2];
                }
            }
            int h = (Integer) cp[0];
            access(h, "TRANSFER_WRITE");
            gpu.clear(h, cc);
            recorded();
            if (tracing) trace.add("clear " + h + " " + java.util.Arrays.toString(cc));
        }
        // supplied images: uploaded into the half the first replayed reader sees
        if (!supplied.isEmpty())
        for (String name : supplied) {
            String key = name;
            if (images.containsKey(name + "#main"))
                key = name + (flippedBefore.contains(name) ? "#alt" : "#main");
            Integer h = images.get(key);
            if (h == null) {
                if (tracing) trace.add("supplied " + name + " has no plan image: ignored");
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
            recorded();
            if (tracing) trace.add("upload " + key);
        }
        // copies from backend images (post mode) and forced clears
        Map<String, Integer> copySrc = in.copySources();
        if (!copySrc.isEmpty())
        for (Map.Entry<String, Integer> e : copySrc.entrySet()) {
            Integer h = inputKeys.get(e.getKey());
            if (h == null) {
                String key = images.containsKey(e.getKey() + "#main") ? e.getKey() + (flippedBefore.contains(e.getKey()) ? "#alt" : "#main") : e.getKey();
                h = images.get(key);
                if (h != null)
                    inputKeys.put(e.getKey(), h);
            }
            if (h == null)
                continue;
            access(e.getValue(), "TRANSFER_READ");
            access(h, "TRANSFER_WRITE");
            gpu.blit(e.getValue(), h);
            recorded();
            if (tracing) trace.add("blit input -> " + e.getKey());
        }
        Map<String, float[]> clearVals = in.clearValues();
        if (!clearVals.isEmpty())
        for (Map.Entry<String, float[]> e : clearVals.entrySet()) {
            int[] hs = clearValueKeys.get(e.getKey());
            if (hs == null) {
                List<String> ks = keysOf(e.getKey());
                int n = 0;
                hs = new int[ks.size()];
                for (String key : ks) {
                    Integer h = images.get(key);
                    if (h != null)
                        hs[n++] = h;
                }
                hs = java.util.Arrays.copyOf(hs, n);
                clearValueKeys.put(e.getKey(), hs);
            }
            for (int h : hs) {
                access(h, "TRANSFER_WRITE");
                gpu.clear(h, e.getValue());
                recorded();
                if (tracing) trace.add("clear " + e.getKey() + " (input value)");
            }
        }
        if ((!in.geometry().isEmpty() || expectGeometry) && start <= STAGES.indexOf("shadow"))
            for (String dk : reuseShadow ? DEPTH_ONLY : DEPTH_AND_SHADOW) {
                Integer h = images.get(dk);
                if (h == null)
                    continue;
                access(h, "TRANSFER_WRITE");
                gpu.clear(h, FAR_DEPTH);
                recorded();
                if (tracing) trace.add("clear " + dk + " 1.0 (geometry frame)");
            }
        if (afterInputs != null)
            afterInputs.run();
        segStart = start;
        segLastStage = null;
        heldCaptures.clear();
    }

    /**
     * P3-11: the passes of stages [from, toExclusive) (null = to the end) of the frame begun by {@link #frameBegin}.
     * {@code holdDepthCaptures}: the scene-depth captures (depthtex1/2 := depthtex0) of these stages are kept for
     * {@link #runHeldCaptures} instead: the world pass draws more opaque geometry into depthtex0 after this segment.
     */
    public void runStages(PackFrameInputs in, String from, String toExclusive, boolean holdDepthCaptures) {
        int start = Math.max(segStart, STAGES.indexOf(from));
        int end = toExclusive == null ? Integer.MAX_VALUE : STAGES.indexOf(toExclusive);
        String lastStage = segLastStage;
        for (int pi = 0; pi < frame.passes.size(); pi++) {
            PassGraph.Pass p = frame.passes.get(pi);
            int st = STAGES.indexOf(p.stage);
            if (st < start)
                continue;
            if (st >= end)
                break;
            if (!p.stage.equals(lastStage)) {
                lastStage = p.stage;
                segLastStage = lastStage;
                if (onStage != null)
                    onStage.accept(p.stage);
            }
            if (holdDepthCaptures && p.kind.equals("capture") && p.id.startsWith("capture:depthtex")) {
                heldCaptures.add(p);
                if (tracing) trace.add("hold " + p.id + " (world pass draws into depthtex0 first)");
                continue;
            }
            if (skip.contains(p.id)) {
                if (tracing) trace.add("skip " + p.id + " (iroh.pack.skip)");
                continue;
            }
            if (pruneDead && deadPasses().contains(p.id)) {
                if (tracing) trace.add("skip " + p.id + " (dead: no reader of its outputs)");
                continue;
            }
            if (reuseShadow && (p.stage.equals("shadow") || p.stage.equals("shadowcomp"))) {
                if (tracing) trace.add("skip " + p.id + " (shadow maps reused)");
                continue;
            }
            if (upscale != null && upscale.replaces().contains(p.id)) {
                if (tracing) trace.add("skip " + p.id + " (replaced by the upscaler)");
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
                case "geometry" -> {
                    if (!hasBatches(p, in)) {
                        if (tracing) trace.add("skip " + p.id + " (no geometry supplied)");
                    } else {
                        gpu.timeBegin(p.id);
                        geometry(p, in);
                        gpu.timeEnd(p.id);
                    }
                }
                case "capture" -> capture(p, in);
                case "flip" -> { if (tracing) trace.add("flip " + p.id + " " + p.flipsAfter + " (frozen in the plan)"); }
                default -> { if (tracing) trace.add("skip " + p.id + " (" + p.kind + ")"); }
            }
            if (upscale != null && p.id.equals(upscale.after()))
                runCut(in);
        }
    }

    /** P3-11: the depth captures {@link #runStages} held, now (after the world pass's opaque draws) */
    public void runHeldCaptures(PackFrameInputs in) {
        for (int i = 0; i < heldCaptures.size(); i++)
            capture(heldCaptures.get(i), in);
        heldCaptures.clear();
    }

    /** P3-11 last segment: end-of-frame copies, depth back, the raster-over composite, present; the backend frame ends */
    public void frameEnd(PackFrameInputs in) {
        postCut = false;
        // end of frame: alt -> main copies for flipped, never-cleared pairs
        if (endCopies == null) {
            List<int[]> l = new ArrayList<>();
            for (PassGraph.Target t : frame.targets.values()) {
                if (!(t.endOfFrameCopy && t.pairNeeded()))
                    continue;
                Integer a = images.get(t.name + "#alt"), m = images.get(t.name + "#main");
                if (a != null && m != null)
                    l.add(new int[]{a, m});
            }
            endCopies = l.toArray(new int[0][]);
        }
        for (int[] am : endCopies) {
            access(am[0], "TRANSFER_READ");
            access(am[1], "TRANSFER_WRITE");
            gpu.copy(am[0], am[1]);
            recorded();
            if (tracing) trace.add("copy " + am[0] + " -> " + am[1] + " (end of frame alt -> main)");
        }
        if (in.depthTo() >= 0 && images.containsKey("depthtex0")) {
            Integer d0 = images.get("depthtex0");
            access(d0, "TRANSFER_READ");
            access(in.depthTo(), "TRANSFER_WRITE");
            gpu.blit(d0, in.depthTo());
            recorded();
            if (tracing) trace.add("depth back depthtex0 -> scene depth");
        }
        if (rasterOver >= 0)
            rasterOverComposite();
        if (presentTo >= 0) {
            Integer out = images.get("output");
            access(out, "TRANSFER_READ");
            access(presentTo, "TRANSFER_WRITE");
            gpu.blit(out, presentTo);
            recorded();
            if (tracing) trace.add("present output");
        }
        gpu.endFrame();
        carryTail();
        if (tracing) trace.add("frame end");
    }

    // P3-8 frame-plan caches (cleared on close)
    private final Map<Integer, Set<String>> flippedCache = new HashMap<>();
    private List<Object[]> clearPlan;
    private Set<String> clearPlanSupplied;
    private boolean clearPlanReuse;
    private final Map<String, Integer> inputKeys = new HashMap<>();
    private final Map<String, int[]> clearValueKeys = new HashMap<>();
    private int[][] endCopies;
    private static final String[] DEPTH_ONLY = {"depthtex0"}, DEPTH_AND_SHADOW = {"depthtex0", "shadowtex0"};
    private static final float[] FAR_DEPTH = {1, 0, 0, 0};

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

    /**
     * one access per image of a program's bindings: an image bound both as a sampler and as storage counts once, as
     * the storage access (a read-modify-write by that program), so the NEXT reader still sees a write and barriers
     */
    /** P3-8: everything about a pass that is stable from frame to frame (rebuilt only when the inputs' identity changes) */
    static final class PassCache {
        GpuBackend.Draw draw;
        GpuBackend.Dispatch dispatch;
        ByteBuffer ubo;
        int[] mips = new int[0];
        // geometry
        Map<Integer, GpuBackend.Bind> set0, set1;
        int[] colour;
        int depth = -1;
        int[] viewport;
        List<PackFrameInputs.Geometry> geoRef;
        long geoVersion;
        List<PackFrameInputs.Geometry> geo;
        final List<ByteBuffer> batchUbos = new ArrayList<>();
        final List<GpuBackend.GeoBatch> batches = new ArrayList<>();
        List<GpuBackend.GeoDraw> geoDraws;
        // capture
        int capSrc = -1, capDst = -1;
    }

    private final Map<PassGraph.Pass, PassCache> passCache = new java.util.IdentityHashMap<>();

    private int viewW() {
        return upscale == null ? 0 : postCut ? displayW : width;
    }

    private int viewH() {
        return upscale == null ? 0 : postCut ? displayH : height;
    }

    private int[] abImg = new int[64];
    private String[] abKind = new String[64];

    private void accessBinds(Map<Integer, GpuBackend.Bind> set0, String storageKind) {
        GpuBackend.Bind[] arr = bindArrays.get(set0);
        if (arr == null) {
            arr = set0.values().toArray(new GpuBackend.Bind[0]);
            bindArrays.put(set0, arr);
        }
        if (abImg.length < arr.length) {
            abImg = new int[arr.length];
            abKind = new String[arr.length];
        }
        int n = 0;
        for (GpuBackend.Bind b : arr) {
            if (b.image() < 0)
                continue;
            String k = "STORAGE".equals(b.sampler()) ? storageKind : "SAMPLED_READ";
            int j = 0;
            while (j < n && abImg[j] != b.image())
                j++;
            if (j == n) {
                abImg[n] = b.image();
                abKind[n++] = k;
            } else {
                String a = abKind[j];
                abKind[j] = a.endsWith("WRITE") ? a : k.endsWith("WRITE") ? k : a.startsWith("STORAGE") ? a : k;
            }
        }
        for (int i = 0; i < n; i++)
            access(abImg[i], abKind[i]);
    }

    /** bind maps -> arrays (identity: the maps are cached per pass) */
    private final Map<Map<Integer, GpuBackend.Bind>, GpuBackend.Bind[]> bindArrays = new java.util.IdentityHashMap<>();

    private void unusedAccessBinds() {
    }

    /**
     * V-6 (RQ-V5-a): every new image is zeroed by the backend (VkPackBackend.createImage records UNDEFINED -> GENERAL and
     * a clear into the current command buffer), so it starts as a TRANSFER_WRITE: the first upload/clear/draw on it then
     * gets its WRITE -> WRITE barrier. Images made by prepare() before the frame stay tracked into it (lastAccess is
     * cleared at the end of a frame, not the start): in game prepare records into the same command buffer as the frame.
     * Only the frame an image is created in pays the barrier.
     */
    private int newImage(GpuBackend.ImageDesc d) {
        int h = gpu.createImage(d);
        owned.set(h);
        if (!untrackedCreateForTest) {
            lastAccess.put(h, "TRANSFER_WRITE");
            noteSinceBarrier(h);
        }
        return h;
    }

    /** negative control for the V-6 repro (packThreadRepro -PoldCreate): the pre-V-6 untracked creation clear */
    static boolean untrackedCreateForTest;

    /** negative control for the V-7 repro (packFlightRepro -Pmode=old): the pre-V-7 end of frame (every access forgotten) */
    static boolean forgetAtFrameEndForTest;

    /*
     * V-7 (review 698ba7c finding 1): frames in flight. In game each frame is its own command buffer and frame N+1 is
     * submitted while frame N may still run on the same queue, so forgetting every access at the end of a frame left the
     * first access of frame N+1 with no barrier against frame N's last one (packFlightRepro: WAW end-of-frame alt -> main
     * copy vs the next frame's scene blit into main, WAR copy read vs the next composite draw). gpu.barrier is ONE FULL
     * memory barrier (VkPackBackend: ALL_COMMANDS -> ALL_COMMANDS, MEMORY_WRITE -> MEMORY_READ|WRITE): it orders every
     * earlier command of every image, earlier frames included, before everything after it. So only commands recorded
     * AFTER a frame's last executor barrier can race the next frame: their accesses of executor-owned images are carried
     * into the next frame and dropped at its first barrier; the first conflicting access before that barrier emits it. Imported images
     * (scene colour / depth, atlas) are not carried: their owner writes them between pack frames and the backend
     * transitions them around every blit. Steady-state cost: a barrier only where a real cross-frame conflict exists
     * (Navidson world0: none, barriers per frame unchanged).
     */
    /** executor-owned images (every one goes through newImage) */
    private final java.util.BitSet owned = new java.util.BitSet();
    /**
     * images whose COMMAND may run after the executor's last barrier, in declaration order. access() declares before
     * the command is recorded and may emit the barrier in the middle of one command's declarations (a draw: one barrier
     * per bound image that needs it), so a barrier drops only the entries of commands already recorded (before
     * {@link #cmdStart}); {@link #recorded} marks the end of a command. A missing mark keeps more (more barriers), never fewer.
     */
    private int[] since = new int[64];
    private int nSince, cmdStart;
    private int[] sincePos = new int[256];
    private final java.util.BitSet inSince = new java.util.BitSet();
    /** lastAccess entries carried over from the previous frame's tail (dropped at this frame's first barrier) */
    private final java.util.BitSet carried = new java.util.BitSet();

    private void noteSinceBarrier(int image) {
        if (image < 0 || (inSince.get(image) && sincePos[image] >= cmdStart))
            return;
        if (nSince == since.length)
            since = java.util.Arrays.copyOf(since, nSince * 2);
        if (image >= sincePos.length)
            sincePos = java.util.Arrays.copyOf(sincePos, Math.max(image + 1, sincePos.length * 2));
        inSince.set(image);
        sincePos[image] = nSince;
        since[nSince++] = image;
    }

    /** the command whose accesses were just declared is recorded (V-7) */
    private void recorded() {
        cmdStart = nSince;
    }

    private void barrierEmitted() {
        for (int h = carried.nextSetBit(0); h >= 0; h = carried.nextSetBit(h + 1))
            lastAccess.put(h, null);
        carried.clear();
        // recorded commands are ordered by this barrier; the declarations of the command still pending stay
        inSince.clear();
        int k = 0;
        for (int i = cmdStart; i < nSince; i++) {
            int h = since[i];
            if (inSince.get(h))
                continue;
            inSince.set(h);
            sincePos[h] = k;
            since[k++] = h;
        }
        nSince = k;
        cmdStart = 0;
    }

    /** end of frame: keep only the owned images whose commands ran after the frame's last barrier (V-7) */
    private void carryTail() {
        if (forgetAtFrameEndForTest) {
            forgetAccesses();
            return;
        }
        int n = 0;
        if (keepH.length < nSince) {
            keepH = new int[since.length];
            keepK = new String[since.length];
        }
        for (int i = 0; i < nSince; i++) {
            int h = since[i];
            String k = lastAccess.get(h);
            if (k != null && owned.get(h)) {
                keepH[n] = h;
                keepK[n++] = k;
            }
        }
        forgetAccesses();
        for (int i = 0; i < n; i++) {
            if (carried.get(keepH[i]))
                continue;
            lastAccess.put(keepH[i], keepK[i]);
            carried.set(keepH[i]);
            noteSinceBarrier(keepH[i]);
        }
        recorded();
    }

    private int[] keepH = new int[64];
    private String[] keepK = new String[64];

    private void forgetAccesses() {
        lastAccess.clear();
        carried.clear();
        inSince.clear();
        nSince = 0;
        cmdStart = 0;
    }

    /** V-7: images whose previous-frame access is still carried (tests) */
    int carriedCount() {
        return carried.cardinality();
    }

    private void access(int image, String kind) {
        String last = lastAccess.get(image);
        boolean write = kind.endsWith("WRITE");
        if (last != null && (last.endsWith("WRITE") || write)) {
            gpu.barrier(image, last, kind);
            barrierEmitted();
        }
        lastAccess.put(image, kind);
        carried.clear(image);
        noteSinceBarrier(image);
    }

    private Integer imageFor(String target, String half) {
        if (target.equals("output"))
            return images.get("output");
        if (postCut) {
            Integer d = images.get("D:" + target + "#" + half);
            if (d == null)
                d = images.get("D:" + target);
            if (d != null)
                return d;
        }
        Integer h = images.get(target + "#" + half);
        return h != null ? h : images.get(target);
    }

    // ------------------------------------------------------------------------------------------------ upscaler seam

    private int motionPipe = -1;

    /** display-size copies of every target half a post-cut pass touches, the motion-vector target and its pipeline */
    private void prepareUpscale() {
        int cutIdx = -1;
        for (int i = 0; i < frame.passes.size(); i++)
            if (frame.passes.get(i).id.equals(upscale.after()))
                cutIdx = i;
        if (cutIdx < 0) {
            problems.add("upscale: no pass " + upscale.after() + " in " + frame.folder + ": upscaler off");
            upscale = null;
            displayW = width;
            displayH = height;
            return;
        }
        for (int i = cutIdx + 1; i < frame.passes.size(); i++) {
            PassGraph.Pass p = frame.passes.get(i);
            if (!(p.kind.equals("fullscreen") || p.kind.equals("compute")))
                continue;
            if (upscale.replaces().contains(p.id)) {
                for (Map<String, Object> w : p.writes)
                    if (upscale.input().equals(w.get("target")))
                        upscaleOutKey = w.get("target") + "#" + w.get("half");
                for (Map<String, Object> r : p.reads)
                    if (upscale.input().equals(r.get("resource")) || upscale.input().equals(r.get("name")))
                        upscaleInKey = upscale.input() + "#" + r.getOrDefault("half", "main");
                continue;
            }
            for (Map<String, Object> w : p.writes)
                if (!"output".equals(w.get("target")))
                    displayKeys.add(w.get("target") + "#" + w.get("half"));
            for (Map<String, Object> r : p.reads)
                if ("colortex".equals(r.get("kind")))
                    displayKeys.add(r.get("resource") + "#" + r.getOrDefault("half", "main"));
                else if ("depth".equals(r.get("kind"))) {
                    String res = String.valueOf(r.get("resource"));
                    displayKeys.add(res.startsWith("depthtex1") ? "depthtex1" : res.startsWith("depthtex2") ? "depthtex2" : "depthtex0");
                }
        }
        if (upscaleOutKey != null)
            displayKeys.add(upscaleOutKey);
        if (upscaleInKey == null)
            upscaleInKey = upscale.input() + "#main";
        for (String k : displayKeys) {
            Integer src = images.get(k);
            if (src == null)
                src = images.get(k.replaceAll("#.*", ""));
            if (src == null)
                continue;
            GpuBackend.ImageDesc d = gpu.desc(src);
            Set<String> usage = new LinkedHashSet<>(d.usage());
            usage.add("TRANSFER_DST");
            String dk = "D:" + (images.containsKey(k) ? k : k.replaceAll("#.*", ""));
            if (!images.containsKey(dk))
                images.put(dk, newImage(new GpuBackend.ImageDesc("pack:" + dk, d.vkFormat(), displayW, displayH, 1, d.mips(), usage)));
        }
        images.put("engine:motion", newImage(new GpuBackend.ImageDesc("pack:engine:motion", "R16G16_SFLOAT", width, height, 1, 1,
                Set.of("COLOR_ATTACHMENT", "SAMPLED", "TRANSFER_SRC", "TRANSFER_DST"))));
        var vs = dev.elemore.iroh.shaderpack.PackCompiler.compile(dev.elemore.iroh.shaderpack.PackGlsl.Stage.VERTEX, MOTION_VS, "iroh-motion.vsh");
        var fs = dev.elemore.iroh.shaderpack.PackCompiler.compile(dev.elemore.iroh.shaderpack.PackGlsl.Stage.FRAGMENT, MOTION_FS, "iroh-motion.fsh");
        if (vs.spirv() == null || fs.spirv() == null) {
            problems.add("upscale: motion shader: " + vs.error() + " " + fs.error());
            return;
        }
        motionPipe = gpu.createPipeline(new GpuBackend.PipelineDesc("engine:motion", vs.spirv(), fs.spirv(), null,
                List.of(new GpuBackend.VertexInput(0, "vec4", "position")), List.of("R16G16_SFLOAT"), List.of("off"),
                List.of(new GpuBackend.Slot(0, 0, "UNIFORM_BUFFER", "IrohMotion", "IrohMotion"),
                        new GpuBackend.Slot(0, 1, "SAMPLER", "sampler2D", "depthtex"))));
        pipelines.put("engine:motion", motionPipe);
        trace.add("upscale prepared: render " + width + "x" + height + " -> display " + displayW + "x" + displayH + ", " + upscale.upscaler().name()
                + ", input " + upscaleInKey + " -> D:" + upscaleOutKey + ", display copies " + displayKeys);
    }

    /** camera-only motion vectors (static world): current pixel -> previous pixel, NDC delta x 0.5 (uv units) */
    static final String MOTION_VS = """
            #version 450
            layout(location = 0) in vec4 pos;
            layout(location = 0) out vec2 uv;
            void main() { uv = pos.xy; gl_Position = vec4(pos.xy * 2.0 - 1.0, 0.0, 1.0); }
            """;
    static final String MOTION_FS = """
            #version 450
            layout(location = 0) in vec2 uv;
            layout(location = 0) out vec4 mv;
            layout(set = 0, binding = 0) uniform IrohMotion { mat4 projInv; mat4 viewInv; mat4 prevView; mat4 prevProj; vec4 camDelta; };
            layout(set = 0, binding = 1) uniform sampler2D depthtex;
            void main() {
                float d = texelFetch(depthtex, ivec2(gl_FragCoord.xy), 0).r;
                vec4 ndc = vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
                vec4 v = projInv * ndc;
                v /= v.w;
                vec3 p = (viewInv * v).xyz + camDelta.xyz;  // player space of this frame -> of the previous frame
                vec4 pc = prevProj * (prevView * vec4(p, 1.0));
                mv = vec4((pc.xy / pc.w - ndc.xy) * 0.5, 0.0, 1.0);
            }
            """;

    private static void putMat(ByteBuffer b, int off, double[] m) {
        for (int i = 0; i < 16; i++)
            b.putFloat(off + i * 4, (float) m[i]);
    }

    private final ByteBuffer cutUbo = ByteBuffer.allocate(4 * 64 + 16).order(ByteOrder.LITTLE_ENDIAN);
    private GpuBackend.Draw cutDraw;
    private static final double[] ZERO3 = new double[3];
    private int[][] cutCopies;
    private Integer cutIn, cutOut;

    /** after the cut pass: motion vectors, the upscale, display copies of everything else the later passes read */
    private void runCut(PackFrameInputs in) {
        Integer depth = images.get("depthtex0"), mv = images.get("engine:motion");
        if (motionPipe >= 0 && depth != null && mv != null) {
            access(depth, "SAMPLED_READ");
            access(mv, "ATTACHMENT_WRITE");
            Map<String, double[]> u = in.uniforms();
            ByteBuffer b = cutUbo;
            putMat(b, 0, u.getOrDefault("gbufferProjectionInverse", IDENTITY));
            putMat(b, 64, u.getOrDefault("gbufferModelViewInverse", IDENTITY));
            putMat(b, 128, u.getOrDefault("gbufferPreviousModelView", IDENTITY));
            putMat(b, 192, u.getOrDefault("gbufferPreviousProjection", IDENTITY));
            double[] c = u.getOrDefault("cameraPosition", ZERO3), pc = u.getOrDefault("previousCameraPosition", c);
            for (int i = 0; i < 3; i++)
                b.putFloat(256 + i * 4, (float) (c[i] - pc[i]));
            if (cutDraw == null)
                cutDraw = new GpuBackend.Draw("engine:motion", motionPipe, new int[]{mv}, new int[]{0, 0, width, height},
                        Map.of(1, GpuBackend.Bind.image(depth, "NEAREST")), Map.of(), b.array());
            gpu.draw(cutDraw);
            recorded();
            if (tracing) trace.add("engine motion vectors " + width + "x" + height);
        }
        if (cutCopies == null) {
            List<int[]> l = new ArrayList<>();
            for (String k : displayKeys) {
                if (k.equals(upscaleOutKey))
                    continue;
                Integer src = images.get(k), dst = images.get("D:" + k);
                if (src != null && dst != null)
                    l.add(new int[]{src, dst});
            }
            cutCopies = l.toArray(new int[0][]);
        }
        for (int[] sd : cutCopies) {
            access(sd[0], "TRANSFER_READ");
            access(sd[1], "TRANSFER_WRITE");
            gpu.blit(sd[0], sd[1]);
            recorded();
            if (tracing) trace.add("display copy " + sd[0] + " -> " + sd[1]);
        }
        if (cutIn == null) {
            cutIn = images.get(upscaleInKey);
            cutOut = upscaleOutKey == null ? null : images.get("D:" + upscaleOutKey);
        }
        Integer inImg = cutIn, outImg = cutOut;
        if (inImg != null && outImg != null) {
            access(inImg, "TRANSFER_READ");
            if (mv != null)
                access(mv, "SAMPLED_READ");
            access(outImg, "TRANSFER_WRITE");
            upscale.upscaler().dispatch(gpu, inImg, depth == null ? -1 : depth, mv == null ? -1 : mv, outImg, width, height, displayW, displayH);
            recorded();
            if (tracing) trace.add("upscale " + upscale.upscaler().name() + " " + upscaleInKey + " " + width + "x" + height + " -> D:" + upscaleOutKey + " " + displayW + "x" + displayH);
        }
        postCut = true;
    }

    private void fullscreen(PassGraph.Pass p, PackFrameInputs in) {
        Integer pipe = pipelines.get(p.id);
        if (pipe == null) {
            if (tracing) trace.add("skip " + p.id + " (no pipeline)");
            return;
        }
        PackData.Unit u = data.units.get(p.unit);
        PassCache c = passCache.get(p);
        if (c != null && c.draw != null && afterPass == null) {
            // P3-8 steady state: no allocation
            for (int h : c.mips) {
                access(h, "TRANSFER_WRITE");
                gpu.generateMips(h);
                recorded();
            }
            accessBinds(c.draw.set0(), "STORAGE_WRITE");
            for (int h : c.draw.colour())
                access(h, "ATTACHMENT_WRITE");
            fillUniforms(u, c.ubo, in.uniforms(), true, viewW(), viewH());
            gpu.draw(c.draw);
            recorded();
            if (tracing) trace.add("draw " + p.id + " viewport " + c.draw.viewport()[2] + "x" + c.draw.viewport()[3] + " reads " + readKeys(p));
            passIndex = frame.passes.indexOf(p);
            drawCount++;
            return;
        }
        int[] mipHandles = new int[p.mipmapsBefore.size()];
        int nMips = 0;
        mipped = new HashSet<>();
        for (String mip : p.mipmapsBefore) {
            String[] tp = mip.replace("(", "").replace(")", "").split(" ");
            mipped.add(tp[0]);
            Integer h = imageFor(tp[0], tp[1]);
            if (h != null) {
                mipHandles[nMips++] = h;
                access(h, "TRANSFER_WRITE");
                gpu.generateMips(h);
                recorded();
                if (tracing) trace.add("mips " + tp[0] + "#" + tp[1]);
            }
        }
        Map<Integer, GpuBackend.Bind> set0 = binds(p, u, 0);
        Map<Integer, GpuBackend.Bind> set1 = binds(p, u, 1);
        accessBinds(set0, "STORAGE_WRITE"); // a fullscreen program may imageStore too
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
        c = new PassCache();
        c.ubo = newBlock(u);
        fillUniforms(u, c.ubo, in.uniforms(), true, viewW(), viewH());
        c.mips = java.util.Arrays.copyOf(mipHandles, nMips);
        c.draw = new GpuBackend.Draw(p.id, pipe, colour, viewport, set0, set1, c.ubo.array());
        passCache.put(p, c);
        gpu.draw(c.draw);
        recorded();
        if (tracing) trace.add("draw " + p.id + " -> " + keys + " viewport " + viewport[2] + "x" + viewport[3] + " reads " + readKeys(p));
        passIndex = frame.passes.indexOf(p);
        drawCount++;
        if (afterPass != null) {
            gpu.endFrame();
            afterPass.accept(p.id, keys);
            gpu.beginFrame();
            forgetAccesses(); // offline debug dumps only: the frame was submitted and waited around afterPass
        }
    }

    /** vertex buffers of the supplied geometry, created once per Geometry object (replay) */
    private final Map<PackFrameInputs.Geometry, Integer> vertexBuffers = new java.util.IdentityHashMap<>();

    /** the geometry textures (atlas, normals, specular) or Iris' defaults */
    private Integer geoTexture(String res, PackFrameInputs in) {
        String k = res.startsWith("atlas normals") ? "normals" : res.startsWith("atlas specular") ? "specular" : "tex";
        String key = "geo:" + k;
        Integer ext = in.geometryImages().get(key);
        if (ext != null)
            return ext; // owned by the renderer: never freed by the executor
        Integer h = images.get(key);
        if (h != null)
            return h;
        int[] ex = in.geometryTextures().contains(key) ? in.extent(key) : null;
        float[] px = ex != null ? in.image(key) : null;
        int w = ex != null ? ex[0] : 1, hh = ex != null ? ex[1] : 1;
        if (px == null)
            px = switch (k) {
                case "normals" -> new float[]{0.5f, 0.5f, 1f, 1f};
                case "specular" -> new float[]{0, 0, 0, 0};
                default -> new float[]{1, 1, 1, 1};
            };
        h = newImage(new GpuBackend.ImageDesc("pack:" + key, "R8G8B8A8_UNORM", w, hh, 1, 1, Set.of("SAMPLED", "TRANSFER_DST")));
        access(h, "TRANSFER_WRITE");
        gpu.upload(h, px);
        recorded();
        images.put(key, h);
        return h;
    }

    /**
     * One geometry pass (P3-5): every supplied batch of its classes in ONE render pass instance with depth. Main:
     * gl_ModelViewMatrix = gbufferModelView, gl_ProjectionMatrix = gbufferProjection, depth = depthtex0; shadow:
     * shadowModelView / shadowProjection, depth = shadowtex0. gl_Vertex = camera-relative position (decode folds the
     * wide vertex's 1/2048 grid, its +4 offset and the section origin, as Iris' vaPosition + chunkOffset).
     */
    // per-batch uniform scratch (P3-8: reused; the backend copies each block when it records the draw)
    private final double[] gDecode = new double[4], gViewInv = new double[16], gProjInv = new double[16], gMvp = new double[16],
            gNormal = new double[9], gTmp = new double[16], gStage = new double[1];
    private static final double[] MINUS_ONE = {-1}, ZERO1 = {0}, NORMAL_IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private final Map<String, double[]> gPer = new HashMap<>();
    private final MutableLayered gLayer = new MutableLayered(gPer);

    /** {@link Layered} with a replaceable base (the frame's uniform map) */
    static final class MutableLayered extends java.util.AbstractMap<String, double[]> {
        final Map<String, double[]> top;
        Map<String, double[]> base = Map.of();

        MutableLayered(Map<String, double[]> top) {
            this.top = top;
        }

        @Override
        public double[] get(Object k) {
            double[] v = top.get(k);
            return v != null ? v : base.get(k);
        }

        @Override
        public Set<Entry<String, double[]>> entrySet() {
            Map<String, double[]> m = new HashMap<>(base);
            m.putAll(top);
            return m.entrySet();
        }
    }

    /**
     * One geometry pass (P3-5): every supplied batch of its classes in ONE render pass instance with depth. Main:
     * gl_ModelViewMatrix = gbufferModelView, gl_ProjectionMatrix = gbufferProjection, depth = depthtex0; shadow:
     * shadowModelView / shadowProjection, depth = shadowtex0. gl_Vertex = camera-relative position (decode folds the
     * wide vertex's 1/2048 grid, its +4 offset and the section origin, as Iris' vaPosition + chunkOffset).
     * P3-8: bindings, attachments, batch objects and uniform blocks are cached per pass while the inputs' geometry
     * list is the same object; the per-batch values are written into reused scratch arrays.
     */
    private void geometry(PassGraph.Pass p, PackFrameInputs in) {
        Integer pipe = pipelines.get(p.id);
        if (pipe == null) {
            if (tracing) trace.add("skip " + p.id + " (no pipeline)");
            return;
        }
        boolean shadow = shadowPass(p);
        PackData.Unit u = data.units.get(p.unit);
        PassCache c = passCache.get(p);
        if (c == null || c.set0 == null) {
            c = new PassCache();
            mipped = Set.of();
            geoIn = in;
            c.set0 = binds(p, u, 0);
            c.set1 = binds(p, u, 1);
            geoIn = null;
            c.colour = new int[p.writes.size()];
            for (int i = 0; i < c.colour.length; i++) {
                Map<String, Object> w = p.writes.get(i);
                Integer h = imageFor((String) w.get("target"), (String) w.get("half"));
                if (h == null) {
                    problems.add(p.id + ": no image for " + w.get("target") + "/" + w.get("half"));
                    return;
                }
                c.colour[i] = h;
            }
            Integer depth = images.get(shadow ? "shadowtex0" : "depthtex0");
            if (depth == null) {
                problems.add(p.id + ": no depth image");
                return;
            }
            c.depth = depth;
            GpuBackend.ImageDesc dd = gpu.desc(depth);
            c.viewport = new int[]{0, 0, dd.width(), dd.height()};
            passCache.put(p, c);
        }
        // storage images in a geometry program are written (shadow voxelisation: imageStore from the shadow pass)
        accessBinds(c.set0, "STORAGE_WRITE");
        for (int h : c.colour)
            access(h, "ATTACHMENT_WRITE");
        access(c.depth, "ATTACHMENT_WRITE");
        Map<String, double[]> base = in.uniforms();
        double[] view = shadow ? base.get("shadowModelView") : base.get("gbufferModelView");
        double[] proj = shadow ? base.get("shadowProjection") : base.get("gbufferProjection");
        if (view == null || proj == null) {
            problems.add(p.id + ": no " + (shadow ? "shadow" : "gbuffer") + " matrices in the inputs");
            return;
        }
        List<PackFrameInputs.Geometry> all = in.geometry();
        boolean sameList = c.geoRef == all && c.geoVersion == in.geometryVersion();
        if (!sameList) {
            c.geoRef = all;
            c.geoVersion = in.geometryVersion();
            c.geo = batchesFor(p, in);
            c.batches.clear();
            c.geoDraws = null;
        }
        Mat.invert(view, gViewInv);
        Mat.invert(proj, gProjInv);
        Mat.mul(proj, view, gMvp);
        Mat.normal(view, gTmp, gNormal);
        gPer.put("iroh_ModelViewMatrix", view);
        gPer.put("iroh_ModelViewMatrixInverse", gViewInv);
        gPer.put("iroh_ProjectionMatrix", proj);
        gPer.put("iroh_ProjectionMatrixInverse", gProjInv);
        // P3-10: the core-profile names (Iris "implicit uniforms": modelViewMatrix, projectionMatrix, normalMatrix) used
        // by core-style programs such as Navidson's gbuffers_line (vaPosition/vaNormal)
        gPer.put("modelViewMatrix", view);
        gPer.put("modelViewMatrixInverse", gViewInv);
        gPer.put("projectionMatrix", proj);
        gPer.put("projectionMatrixInverse", gProjInv);
        gPer.put("normalMatrix", gNormal);
        gPer.put("iroh_ModelViewProjectionMatrix", gMvp);
        gPer.put("iroh_NormalMatrix", gNormal);
        gPer.put("iroh_TextureMatrix", LIGHTMAP_TEXTURE_MATRICES);
        gPer.put("blockEntityId", MINUS_ONE);
        gPer.put("entityId", MINUS_ONE);
        gPer.put("iroh_VertexDecode", gDecode);
        gPer.put("renderStage", gStage);
        gLayer.base = base;
        int vw = viewW(), vh = viewH();
        int quads = 0;
        for (int i = 0; i < c.geo.size(); i++) {
            PackFrameInputs.Geometry g = c.geo.get(i);
            if (c.batchUbos.size() <= i)
                c.batchUbos.add(newBlock(u));
            ByteBuffer ub = c.batchUbos.get(i);
            double[] o = g.origin();
            boolean wideV = "wide-terrain".equals(g.layout());
            gDecode[0] = wideV ? 1.0 / 2048 : 1;
            gDecode[1] = (wideV ? 4 : 0) + o[0];
            gDecode[2] = (wideV ? 4 : 0) + o[1];
            gDecode[3] = (wideV ? 4 : 0) + o[2];
            gPer.put("chunkOffset", o);
            // P3-10 fix: gl_TextureMatrix[0] scales uv0 only for the wide terrain vertex (x32768 grid read as UNORM16);
            // vanilla formats carry float uvs: identity
            gPer.put("iroh_TextureMatrix", wideV ? LIGHTMAP_TEXTURE_MATRICES : VANILLA_TEXTURE_MATRICES);
            gStage[0] = renderStage(g.geometryClass(), shadow);
            boolean viewSpace = g.geometryClass().startsWith("hand");
            if (viewSpace) { // the hand is drawn in view space (vanilla: its own model-view, identity camera rotation)
                gPer.put("modelViewMatrix", IDENTITY);
                gPer.put("modelViewMatrixInverse", IDENTITY);
                gPer.put("normalMatrix", NORMAL_IDENTITY);
                gPer.put("iroh_ModelViewMatrix", IDENTITY);
                gPer.put("iroh_ModelViewMatrixInverse", IDENTITY);
                gPer.put("iroh_ModelViewProjectionMatrix", proj);
                gPer.put("iroh_NormalMatrix", NORMAL_IDENTITY);
            }
            Map<String, double[]> extra = g.uniforms();
            if (extra != null)
                gPer.putAll(extra);
            fillUniforms(u, ub, gLayer, false, vw, vh);
            if (extra != null)
                for (String k : extra.keySet()) { // back to the frame's value (or the per-draw default) for the next batch
                    double[] bv = base.get(k);
                    gPer.put(k, bv != null ? bv : k.equals("entityId") || k.equals("blockEntityId") ? MINUS_ONE : ZERO1);
                }
            if (viewSpace) {
                gPer.put("modelViewMatrix", view);
                gPer.put("modelViewMatrixInverse", gViewInv);
                gPer.put("normalMatrix", gNormal);
                gPer.put("iroh_ModelViewMatrix", view);
                gPer.put("iroh_ModelViewMatrixInverse", gViewInv);
                gPer.put("iroh_ModelViewProjectionMatrix", gMvp);
                gPer.put("iroh_NormalMatrix", gNormal);
            }
            if (c.batches.size() <= i) {
                Integer vb = g.indirect() != null ? Integer.valueOf(-1) : vertexBuffers.get(g);
                if (vb == null) {
                    vb = gpu.createVertexBuffer("pack:geo " + g.geometryClass(), g.vertices().duplicate());
                    vertexBuffers.put(g, vb);
                }
                c.batches.add(new GpuBackend.GeoBatch(ub.array(), vb, g.quads(), 0L, 0L, g.indirect()));
            }
            quads += g.quads();
        }
        gLayer.base = Map.of();
        if (c.geoDraws == null) {
            // one GeoDraw (render pass instance) per vertex layout, each with its pipeline variant
            c.geoDraws = new ArrayList<>();
            List<String> lays = passLayouts.getOrDefault(p.id, List.of("wide-terrain"));
            for (int li = 0; li < lays.size(); li++) {
                List<GpuBackend.GeoBatch> sub = new ArrayList<>();
                for (int i = 0; i < c.geo.size(); i++)
                    if (lays.get(li).equals(c.geo.get(i).layout()) || (li == 0 && c.geo.get(i).layout() == null))
                        sub.add(c.batches.get(i));
                Integer lp = pipelines.get(li == 0 ? p.id : p.id + "|" + lays.get(li));
                if (!sub.isEmpty() && lp != null)
                    c.geoDraws.add(new GpuBackend.GeoDraw(p.id, lp, c.colour, c.depth, c.viewport, c.set0, c.set1, sub));
            }
        }
        for (int di = 0; di < c.geoDraws.size(); di++) {
            if (di > 0) { // the next render pass instance writes the same attachments (and storage images)
                accessBinds(c.set0, "STORAGE_WRITE");
                for (int h : c.colour)
                    access(h, "ATTACHMENT_WRITE");
                access(c.depth, "ATTACHMENT_WRITE");
            }
            gpu.drawGeometry(c.geoDraws.get(di));
            recorded();
        }
        if (tracing) trace.add("geometry " + p.id + " + " + (shadow ? "shadowtex0" : "depthtex0") + " batches " + c.batches.size()
                + " quads " + quads + " reads " + readKeys(p));
        drawCount++;
    }

    /** gl_TextureMatrix[0] identity, [1] the lightmap matrix (0..240 raw light -> texel centres of the 16x16 lightmap) */
    static final double[] LIGHTMAP_TEXTURE_MATRICES = {dev.elemore.iroh.shaderpack.mesh.WideTerrainVertex.UV0_TO_UNIT, 0, 0, 0,
            0, dev.elemore.iroh.shaderpack.mesh.WideTerrainVertex.UV0_TO_UNIT, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1,
            1 / 256.0, 0, 0, 0, 0, 1 / 256.0, 0, 0, 0, 0, 1 / 256.0, 0, 8 / 256.0, 8 / 256.0, 8 / 256.0, 1};

    /** gl_TextureMatrix[0] identity (float uvs), [1] the lightmap matrix */
    static final double[] VANILLA_TEXTURE_MATRICES = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1,
            1 / 256.0, 0, 0, 0, 0, 1 / 256.0, 0, 0, 0, 0, 1 / 256.0, 0, 8 / 256.0, 8 / 256.0, 8 / 256.0, 1};

    /** Iris renderStage constants (MC_RENDER_STAGE_*) for the classes Iroh draws; 0 = none */
    static int renderStage(String geometryClass, boolean shadow) {
        return switch (geometryClass) {
            case "sky_basic" -> 1;
            case "sky_textured" -> 2; // sun/moon share; custom sky 3
            case "terrain_solid" -> 6;
            case "terrain_cutout_mipped" -> 7;
            case "terrain_cutout" -> 8;
            case "entities" -> 9;
            case "block_entities" -> 10;
            case "terrain_translucent" -> 12;
            case "particles_opaque", "particles_translucent" -> 14;
            case "clouds" -> 15;
            case "weather" -> 17;
            case "world_border" -> 18;
            case "hand_solid", "hand_translucent" -> 19;
            default -> 0;
        };
    }

    /** with the upscaler: viewWidth/viewHeight are the render size before the cut and the display size after it */
    private PackFrameInputs sized(PackFrameInputs in) {
        if (upscale == null)
            return in;
        int w = postCut ? displayW : width, h = postCut ? displayH : height;
        return overlay(in, Map.of("viewWidth", new double[]{w}, "viewHeight", new double[]{h}));
    }

    /** read-through view: {@code top} first, then {@code base} (P3-7: no copy of the whole uniform map per batch) */
    static final class Layered extends java.util.AbstractMap<String, double[]> {
        final Map<String, double[]> top, base;

        Layered(Map<String, double[]> top, Map<String, double[]> base) {
            this.top = top;
            this.base = base;
        }

        @Override
        public double[] get(Object k) {
            double[] v = top.get(k);
            return v != null ? v : base.get(k);
        }

        @Override
        public boolean containsKey(Object k) {
            return top.containsKey(k) || base.containsKey(k);
        }

        @Override
        public Set<Entry<String, double[]>> entrySet() {
            Map<String, double[]> m = new HashMap<>(base);
            m.putAll(top);
            return m.entrySet();
        }
    }

    private static PackFrameInputs overlay(PackFrameInputs in, Map<String, double[]> per) {
        Map<String, double[]> m = new Layered(per, in.uniforms());
        return new PackFrameInputs() {
            public int width() { return in.width(); }
            public int height() { return in.height(); }
            public Map<String, double[]> uniforms() { return m; }
            public Set<String> suppliedImages() { return in.suppliedImages(); }
            public float[] image(String n) { return in.image(n); }
            public int[] extent(String n) { return in.extent(n); }
            public String startStage() { return in.startStage(); }
            public double frameSeconds() { return in.frameSeconds(); }
        };
    }

    /** depth captures: depthtex1 / depthtex2 := depthtex0 now, shadowtex1 := shadowtex0 now */
    private void capture(PassGraph.Pass p, PackFrameInputs in) {
        if (in.geometry().isEmpty()) {
            if (tracing) trace.add("skip " + p.id + " (depth capture: supplied)");
            return;
        }
        PassCache c = passCache.get(p);
        if (c == null) {
            c = new PassCache();
            String dst0 = p.id.substring("capture:".length());
            Integer a0 = images.get(dst0.startsWith("shadowtex") ? "shadowtex0" : "depthtex0"), b0 = images.get(dst0);
            c.capSrc = a0 == null ? -1 : a0;
            c.capDst = b0 == null ? -1 : b0;
            passCache.put(p, c);
        }
        int a = c.capSrc, b = c.capDst;
        if (a < 0 || b < 0) {
            if (tracing) trace.add("skip " + p.id + " (no image)");
            return;
        }
        access(a, "TRANSFER_READ");
        access(b, "TRANSFER_WRITE");
        gpu.copy(a, b);
        recorded();
        if (tracing) trace.add("capture " + p.id);
    }

    private void compute(PassGraph.Pass p, PackFrameInputs in) {
        mipped = Set.of();
        Integer pipe = pipelines.get(p.id);
        if (pipe == null) {
            if (tracing) trace.add("skip " + p.id + " (no pipeline)");
            return;
        }
        PackData.Unit u = data.units.get(p.unit);
        PassCache c = passCache.get(p);
        if (c == null || c.dispatch == null) {
            c = new PassCache();
            c.ubo = newBlock(u);
            c.dispatch = new GpuBackend.Dispatch(p.id, pipe, groups(p, u), binds(p, u, 0), binds(p, u, 1), c.ubo.array());
            passCache.put(p, c);
        }
        accessBinds(c.dispatch.set0(), "STORAGE_WRITE");
        int[] groups = c.dispatch.groups();
        fillUniforms(u, c.ubo, in.uniforms(), false, viewW(), viewH());
        gpu.dispatch(c.dispatch);
        recorded();
        if (tracing) trace.add("dispatch " + p.id + " " + java.util.Arrays.toString(groups));
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
    /** the inputs of the geometry pass being bound (draw textures) */
    private PackFrameInputs geoIn;

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
                    String dk = res.startsWith("depthtex1") ? "depthtex1" : res.startsWith("depthtex2") ? "depthtex2" : "depthtex0";
                    h = postCut && images.containsKey("D:" + dk) ? images.get("D:" + dk) : images.get(dk);
                    if (!type.contains("Shadow"))
                        sampler = "NEAREST";
                }
                case "shadowdepth" -> h = images.get(res);
                case "noise" -> h = images.get("noisetex");
                case "image" -> h = images.get(res);
                case "custom", "dynamic" -> h = customTexture(name, res, kind);
                case "geometry" -> {
                    if (geoIn != null)
                        h = geoTexture(res, geoIn);
                    if (sampler.equals("LINEAR"))
                        sampler = "NEAREST"; // the atlas: vanilla blocks sample NEAREST (mipmapped atlas later)
                }
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
                            h = newImage(d);
                            access(h, "TRANSFER_WRITE");
                            gpu.upload(h, px);
                            recorded();
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
        h = newImage(new GpuBackend.ImageDesc("pack:" + key, vk, 1, 1, 1, 1, usage));
        if (depth) {
            access(h, "TRANSFER_WRITE");
            gpu.upload(h, new float[]{1, 0, 0, 0}); // far plane: compare passes (lit)
            recorded();
        }
        images.put(key, h);
        return h;
    }

    // ------------------------------------------------------------------------------------------------ uniforms

    /** P3-8: one std140 member, parsed once per unit (no regex / JSON walk per frame) */
    private record Member(String name, int off, boolean integer, int cols, int rows, int arr, int elemStride, double[] fallback,
                          double[] fullscreenValue) {}

    private final Map<String, Member[]> layouts = new HashMap<>();
    private final Map<String, Integer> blockSizes = new HashMap<>();

    private Member[] layout(PackData.Unit u) {
        Member[] l = layouts.get(u.id);
        if (l != null)
            return l;
        JsonObject r = u.reflection();
        if (!r.has("uniformBlock")) {
            blockSizes.put(u.id, 16);
            layouts.put(u.id, l = new Member[0]);
            return l;
        }
        JsonObject blk = r.getAsJsonObject("uniformBlock");
        blockSizes.put(u.id, Math.max(16, blk.get("size").getAsInt()));
        List<Member> ms = new ArrayList<>();
        for (JsonElement e : blk.getAsJsonArray("members")) {
            JsonObject m = e.getAsJsonObject();
            String name = m.get("name").getAsString();
            String type = m.get("type").getAsString();
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
            double[] fb = m.has("default") ? literal(m.get("default").getAsString()) : null;
            if (fb == null)
                fb = defaults(name, type);
            ms.add(new Member(name, m.get("offset").getAsInt(), integer, cols, rows, arr, elemStride, fb, fullscreenCompat(name)));
        }
        layouts.put(u.id, l = ms.toArray(new Member[0]));
        return l;
    }

    /** fills {@code b} (block size of {@code u}) from the inputs; view size overridden when vw > 0 (upscaler sides) */
    private void fillUniforms(PackData.Unit u, ByteBuffer b, Map<String, double[]> vals, boolean fullscreen, int vw, int vh) {
        for (Member m : layout(u)) {
            double[] v = fullscreen ? m.fullscreenValue() : null;
            if (v == null && vw > 0) {
                if (m.name().equals("viewWidth")) {
                    viewSize[0] = vw;
                    v = viewSize;
                } else if (m.name().equals("viewHeight")) {
                    viewSize2[0] = vh;
                    v = viewSize2;
                }
            }
            if (v == null)
                v = vals.get(m.name());
            if (v == null)
                v = m.fallback();
            writeMember(b, m, v);
        }
    }

    private final double[] viewSize = new double[1], viewSize2 = new double[1];

    static void writeMember(ByteBuffer b, Member mb, double[] v) {
        int k = 0;
        for (int a = 0; a < mb.arr(); a++) {
            int eoff = mb.off() + a * mb.elemStride();
            if (a > 0 && k >= v.length)
                k = 0;
            for (int c = 0; c < mb.cols(); c++)
                for (int r = 0; r < mb.rows(); r++) {
                    double x = k < v.length ? v[k] : 0;
                    k++;
                    int pos = eoff + (mb.cols() > 1 ? c * 16 : 0) + r * 4;
                    if (pos + 4 > b.capacity())
                        continue;
                    if (mb.integer())
                        b.putInt(pos, (int) Math.round(x));
                    else
                        b.putFloat(pos, (float) x);
                }
        }
    }

    private ByteBuffer newBlock(PackData.Unit u) {
        layout(u);
        return ByteBuffer.allocate(blockSizes.get(u.id)).order(ByteOrder.LITTLE_ENDIAN);
    }

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
            case "iroh_VertexDecode" -> new double[]{1, 0, 0, 0};
            default -> null;
        };
    }

    static double[] defaults(String name, String type) {
        if (name.equals("iroh_VertexDecode"))
            return new double[]{1, 0, 0, 0};
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
        owned.clear();
        for (Integer p : pipelines.values())
            gpu.freePipeline(p);
        pipelines.clear();
        for (Integer vb : vertexBuffers.values())
            gpu.freeBuffer(vb);
        vertexBuffers.clear();
        forgetAccesses();
        passCache.clear();
        hasBatchesCache.clear();
        passLayouts.clear();
        bindArrays.clear();
        flippedCache.clear();
        clearPlan = null;
        inputKeys.clear();
        clearValueKeys.clear();
        endCopies = null;
        cutDraw = null;
        for (Integer sp : substPipes.values())
            if (sp >= 0)
                gpu.freePipeline(sp);
        substPipes.clear();
        substPasses.clear();
        passForClass.clear();
        overDraw = null;
        overPipe = -1;
        overFailed = false;
        heldCaptures.clear();
        cutCopies = null;
        cutIn = cutOut = null;
        prepared = false;
        if (tracing) trace.add("closed");
    }

    // ------------------------------------------------------------------------------------------------ P3-11 world pass

    /** premultiplied raster-over: out = raster.rgb + output.rgb * (1 - raster.a) (raster draws were made over transparent black) */
    static final String OVER_FS = """
            #version 450
            layout(location = 0) in vec2 uv;
            layout(location = 0) out vec4 col;
            layout(set = 0, binding = 0) uniform IrohOver { vec4 unused; };
            layout(set = 0, binding = 1) uniform sampler2D raster;
            void main() { col = texelFetch(raster, ivec2(gl_FragCoord.xy), 0); }
            """;
    private int overPipe = -1;
    private GpuBackend.Draw overDraw;
    private boolean overFailed;

    /** blits the raster scene colour into a pack image and draws it over the output (blend ONE, ONE_MINUS_SRC_ALPHA) */
    private void rasterOverComposite() {
        Integer out = images.get("output");
        if (out == null || overFailed)
            return;
        if (overDraw == null) {
            GpuBackend.ImageDesc od = gpu.desc(out), rd = gpu.desc(rasterOver);
            if (od.width() != rd.width() || od.height() != rd.height()) {
                problems.add("raster-over: scene colour " + rd.width() + "x" + rd.height() + " != output " + od.width() + "x" + od.height() + ": raster draws of the world pass not composited");
                overFailed = true;
                return;
            }
            int img = newImage(new GpuBackend.ImageDesc("pack:engine:raster", "R8G8B8A8_UNORM", od.width(), od.height(), 1, 1,
                    Set.of("SAMPLED", "TRANSFER_SRC", "TRANSFER_DST")));
            images.put("engine:raster", img);
            try {
                var vs = dev.elemore.iroh.shaderpack.PackCompiler.compile(dev.elemore.iroh.shaderpack.PackGlsl.Stage.VERTEX, MOTION_VS, "iroh-over.vsh");
                var fs = dev.elemore.iroh.shaderpack.PackCompiler.compile(dev.elemore.iroh.shaderpack.PackGlsl.Stage.FRAGMENT, OVER_FS, "iroh-over.fsh");
                if (vs.spirv() == null || fs.spirv() == null)
                    throw new IllegalStateException(vs.error() + " " + fs.error());
                overPipe = gpu.createPipeline(new GpuBackend.PipelineDesc("engine:over", vs.spirv(), fs.spirv(), null,
                        List.of(new GpuBackend.VertexInput(0, "vec4", "position")), List.of("R8G8B8A8_UNORM"),
                        List.of(List.of("ONE", "ONE_MINUS_SRC_ALPHA", "ONE", "ONE_MINUS_SRC_ALPHA")),
                        List.of(new GpuBackend.Slot(0, 0, "UNIFORM_BUFFER", "IrohOver", "IrohOver"),
                                new GpuBackend.Slot(0, 1, "SAMPLER", "sampler2D", "raster"))));
            } catch (RuntimeException e) {
                problems.add("raster-over: pipeline not created: " + e);
                overFailed = true;
                return;
            }
            pipelines.put("engine:over", overPipe);
            Map<Integer, GpuBackend.Bind> set0 = new TreeMap<>();
            set0.put(1, GpuBackend.Bind.image(img, "NEAREST"));
            overDraw = new GpuBackend.Draw("engine:over", overPipe, new int[]{out}, new int[]{0, 0, od.width(), od.height()}, set0, Map.of(), new byte[16]);
        }
        int img = images.get("engine:raster");
        access(rasterOver, "TRANSFER_READ");
        access(img, "TRANSFER_WRITE");
        gpu.blit(rasterOver, img);
        recorded();
        access(img, "SAMPLED_READ");
        access(out, "ATTACHMENT_WRITE");
        gpu.draw(overDraw);
        recorded();
        if (tracing) trace.add("raster-over composite (world-pass raster draws over the output)");
    }

    /** P3-11: copies a pack image (target key or target name, main half) into a backend image (depth back mid-frame) */
    public void blitOut(String key, int dst) {
        Integer h = images.get(key);
        if (h == null)
            h = imageFor(key, "main");
        if (h == null)
            return;
        access(h, "TRANSFER_READ");
        access(dst, "TRANSFER_WRITE");
        gpu.blit(h, dst);
        recorded();
        if (tracing) trace.add("blit " + key + " -> external");
    }

    /** P3-11: copies a backend image into a pack target's main half (a raster sky into colortex0 mid-frame) */
    public void blitIn(int src, String target) {
        Integer h = imageFor(target, "main");
        if (h == null)
            return;
        access(src, "TRANSFER_READ");
        access(h, "TRANSFER_WRITE");
        gpu.blit(src, h);
        recorded();
        if (tracing) trace.add("blit external -> " + target);
    }

    /** P3-11: clears a backend image the executor does not own (the scene colour to transparent for the raster remainder) */
    public void clearExternal(int image, float[] rgba) {
        access(image, "TRANSFER_WRITE");
        gpu.clear(image, rgba);
        recorded();
        if (tracing) trace.add("clear external");
    }

    /** P3-11: what a substituted draw needs from one pack geometry pass (cached per pass; per-draw fields reused) */
    public static final class SubstPass {
        public final String passId, stage;
        public final int[] colour;
        public final int depth;
        public final int[] viewport;
        public final Map<Integer, GpuBackend.Bind> set0, set1;
        /** set-0 bindings that take the draw's texture (the pack's tex / gtexture / texture sampler) */
        public final int[] texBindings;
        final PackData.Unit unit;
        final PassGraph.Pass pass;
        final ByteBuffer ubo;

        SubstPass(PassGraph.Pass p, PackData.Unit u, int[] colour, int depth, int[] viewport, Map<Integer, GpuBackend.Bind> set0,
                  Map<Integer, GpuBackend.Bind> set1, int[] texBindings, ByteBuffer ubo) {
            this.passId = p.id;
            this.stage = p.stage;
            this.pass = p;
            this.unit = u;
            this.colour = colour;
            this.depth = depth;
            this.viewport = viewport;
            this.set0 = set0;
            this.set1 = set1;
            this.texBindings = texBindings;
            this.ubo = ubo;
        }
    }

    private final Map<String, SubstPass> substPasses = new HashMap<>();
    private final Map<String, String> passForClass = new HashMap<>();
    private final Map<String, Integer> substPipes = new HashMap<>();

    /** P3-11: the geometry pass serving {@code geometryClass} in {@code stage} (gbuffers-solid / gbuffers-translucent), null = none */
    public String passFor(String geometryClass, String stage) {
        String k = geometryClass + "@" + stage;
        String r = passForClass.get(k);
        if (r == null && !passForClass.containsKey(k)) {
            for (PassGraph.Pass p : frame.passes)
                if (p.kind.equals("geometry") && p.stage.equals(stage) && !shadowPass(p) && p.geometry != null && p.geometry.contains(geometryClass)) {
                    r = p.id;
                    break;
                }
            passForClass.put(k, r);
        }
        return r;
    }

    /** P3-11: attachments, bindings and uniform scratch of a pack geometry pass for substituted draws; null when it cannot serve */
    public SubstPass substPass(String passId, PackFrameInputs in) {
        SubstPass sp = substPasses.get(passId);
        if (sp != null)
            return sp;
        PassGraph.Pass p = null;
        for (PassGraph.Pass q : frame.passes)
            if (q.id.equals(passId))
                p = q;
        if (p == null || !prepared)
            return null;
        PackData.Unit u = data.units.get(p.unit);
        if (u == null)
            return null;
        int[] colour = new int[p.writes.size()];
        for (int i = 0; i < colour.length; i++) {
            Integer h = imageFor((String) p.writes.get(i).get("target"), (String) p.writes.get(i).get("half"));
            if (h == null) {
                problems.add(passId + ": no image for " + p.writes.get(i).get("target") + " (substitution)");
                return null;
            }
            colour[i] = h;
        }
        Integer depth = images.get("depthtex0");
        if (depth == null)
            return null;
        GpuBackend.ImageDesc dd = gpu.desc(depth);
        mipped = Set.of();
        geoIn = in;
        Map<Integer, GpuBackend.Bind> set0 = binds(p, u, 0), set1 = binds(p, u, 1);
        geoIn = null;
        List<Integer> tex = new ArrayList<>();
        Map<String, Map<String, Object>> reads = new HashMap<>();
        for (Map<String, Object> rd : p.reads)
            reads.put((String) rd.get("name"), rd);
        JsonObject r = u.reflection();
        if (r.has("samplers"))
            for (JsonElement e : r.getAsJsonArray("samplers")) {
                JsonObject o = e.getAsJsonObject();
                Map<String, Object> rd = reads.get(o.get("name").getAsString());
                if (o.get("set").getAsInt() == 0 && rd != null && "geometry".equals(rd.get("kind"))
                        && !String.valueOf(rd.get("resource")).startsWith("atlas normals") && !String.valueOf(rd.get("resource")).startsWith("atlas specular"))
                    tex.add(o.get("binding").getAsInt());
            }
        int[] tb = new int[tex.size()];
        for (int i = 0; i < tb.length; i++)
            tb[i] = tex.get(i);
        sp = new SubstPass(p, u, colour, depth, new int[]{0, 0, dd.width(), dd.height()}, set0, set1, tb, newBlock(u));
        substPasses.put(passId, sp);
        return sp;
    }

    /**
     * P3-11: the pipeline of {@code passId}'s program for a vanilla vertex layout and render state (cached; created on a
     * miss). {@code blend}: 4 VK blend factor names for attachments whose pack directive is "vanilla", null = no blend.
     * Returns -1 when the pipeline cannot be created (the caller degrades).
     */
    public int substitutePipeline(String passId, String layout, long stateKey, boolean depthTest, boolean depthWrite, boolean cull, List<String> blend) {
        String key = passId + "|" + layout + "|" + Long.toHexString(stateKey);
        Integer h = substPipes.get(key);
        if (h != null)
            return h;
        PassGraph.Pass p = null;
        for (PassGraph.Pass q : frame.passes)
            if (q.id.equals(passId))
                p = q;
        PackData.Unit u = p != null ? data.units.get(p.unit) : null;
        if (u == null) {
            substPipes.put(key, -1);
            return -1;
        }
        try {
            GpuBackend.PipelineDesc d = pipelineDesc(p, u, layout);
            List<Object> bl = new ArrayList<>();
            for (Object b : d.blend())
                bl.add(b instanceof List<?> ? b : "vanilla".equals(b) && blend != null ? blend : b instanceof String sb && !sb.equals("vanilla") ? sb : "off");
            GpuBackend.Geo g = d.geo();
            d = new GpuBackend.PipelineDesc(d.id() + " [" + layout + " " + Long.toHexString(stateKey) + "]", d.vertexSpirv(), d.fragmentSpirv(), null,
                    d.inputs(), d.colourFormats(), bl, d.slots(), new GpuBackend.Geo(layout, g.depthFormat(), depthTest, depthWrite, cull, false));
            h = gpu.createPipeline(d);
        } catch (IOException | RuntimeException e) {
            problems.add(passId + ": substituted pipeline " + layout + " not created: " + e);
            h = -1;
        }
        substPipes.put(key, h);
        return h;
    }

    static final String[] PER_DRAW_KEYS = {"entityId", "blockEntityId", "currentRenderedItemId", "entityColor"};

    /** P3-11: number of substituted pipeline variants created (warm-up / perf report) */
    public int substitutedPipelines() {
        return substPipes.size();
    }

    /**
     * P3-11: the per-draw uniform block of a substituted draw in {@code sp.ubo} (reused: zero allocation): the frame's
     * uniforms, the draw's model-view (vanilla: camera rotation; vertices camera-relative) and projection, gl_Vertex
     * decode identity, vanilla texture matrices, renderStage of the class, plus {@code perDraw} (entityId,
     * blockEntityId, currentRenderedItemId, entityColor). The backend copies the bytes when the draw is bound.
     */
    public byte[] substUniforms(SubstPass sp, PackFrameInputs in, String geometryClass, double[] modelView, double[] proj, Map<String, double[]> perDraw) {
        Mat.invert(modelView, gViewInv);
        Mat.invert(proj, gProjInv);
        Mat.mul(proj, modelView, gMvp);
        Mat.normal(modelView, gTmp, gNormal);
        gPer.put("iroh_ModelViewMatrix", modelView);
        gPer.put("iroh_ModelViewMatrixInverse", gViewInv);
        gPer.put("iroh_ProjectionMatrix", proj);
        gPer.put("iroh_ProjectionMatrixInverse", gProjInv);
        gPer.put("modelViewMatrix", modelView);
        gPer.put("modelViewMatrixInverse", gViewInv);
        gPer.put("projectionMatrix", proj);
        gPer.put("projectionMatrixInverse", gProjInv);
        gPer.put("normalMatrix", gNormal);
        gPer.put("iroh_ModelViewProjectionMatrix", gMvp);
        gPer.put("iroh_NormalMatrix", gNormal);
        gPer.put("iroh_TextureMatrix", VANILLA_TEXTURE_MATRICES);
        gPer.put("blockEntityId", MINUS_ONE);
        gPer.put("entityId", MINUS_ONE);
        gDecode[0] = 1;
        gDecode[1] = gDecode[2] = gDecode[3] = 0;
        gPer.put("iroh_VertexDecode", gDecode);
        gPer.put("chunkOffset", ZERO3);
        gStage[0] = renderStage(geometryClass, false);
        gPer.put("renderStage", gStage);
        if (perDraw != null) // fixed keys, no iterator (zero allocation per draw)
            for (String k : PER_DRAW_KEYS) {
                double[] v = perDraw.get(k);
                if (v != null)
                    gPer.put(k, v);
            }
        gLayer.base = in.uniforms();
        fillUniforms(sp.unit, sp.ubo, gLayer, false, viewW(), viewH());
        gLayer.base = Map.of();
        return sp.ubo.array();
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
