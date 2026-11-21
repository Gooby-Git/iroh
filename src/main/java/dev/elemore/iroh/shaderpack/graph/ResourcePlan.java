package dev.elemore.iroh.shaderpack.graph;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.elemore.iroh.memory.MemoryBudget;
import dev.elemore.iroh.shaderpack.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Vulkan resources one frame plan needs, still offline: images (format, extent as a function of the render
 * size, mips, usage, pair or single), buffers, the attachment set of every raster pass (dynamic rendering), the
 * pack-wide descriptor set layouts, a pipeline key per raster program, memory at any render size, and the budget
 * check against MemoryGuard's cap with the degradation ladder (data, see {@link #LADDER}).
 * <p>
 * Conventions: targets are stored in GL row order (RQ-16, `Orientation`); a GL_BACK cull on a pack target is
 * VK_CULL_MODE_FRONT_BIT with CCW front faces, exactly as for Iroh's OFFSCREEN targets. Depth is D32_SFLOAT holding GL
 * window depth (the translator's clip epilogue). Every pack image is allocated through MemoryGuard as NON-essential
 * (`MemoryBudget.allow(bytes, false)`): a refusal walks the ladder instead of pushing the driver to ENOMEM.
 */
public final class ResourcePlan {

    /** The card Iroh targets (DECISIONS #4: RX 9070 XT, 16 GiB) when no device is known. */
    public static final long ASSUMED_VRAM = 16L << 30;
    /**
     * Share of MemoryGuard's cap a pack plan may take. The rest is the base renderer (chunk buffers, atlases, mod
     * textures, staging ring 256 MiB, Vista). 35% of the 6 GiB auto cap = 2.1 GiB. Design choice, revisit with the
     * first in-game memory stats of the pack path.
     */
    public static final double PACK_SHARE = 0.35;

    public static final class Image {
        public final String name;
        public final String role;      // colour, depth, shadowdepth, shadowcolour, custom, noise
        public String vkFormat;
        public int bytesPerTexel;
        public String extent;          // "render", "render*0.5", "1024x1024", "128x64x128"
        public double fx = 1, fy = 1;  // fraction of the render size (relative extents)
        public long w, h, depth = 1;   // absolute extents (relative == false)
        public boolean relative = true;
        public int layers = 1;         // 2 = ping-pong pair
        public boolean mips;
        public boolean optional;       // may be refused by the ladder (custom images under an optional feature)
        public final Set<String> usage = new LinkedHashSet<>();
        public String note = "";

        Image(String name, String role) {
            this.name = name;
            this.role = role;
        }

        long bytes(int rw, int rh) {
            long ww = relative ? Math.max(1, Math.round(rw * fx)) : w;
            long hh = relative ? Math.max(1, Math.round(rh * fy)) : h;
            long b = ww * hh * depth * bytesPerTexel * layers;
            if (mips)
                b = b * 4 / 3;
            return b;
        }
    }

    public record Buffer(String name, long bytes, String rule) {}

    public final List<Image> images = new ArrayList<>();
    public final List<Buffer> buffers = new ArrayList<>();
    public final Map<String, Object> attachmentSets = new LinkedHashMap<>();
    public final Map<String, Object> descriptorLayout = new LinkedHashMap<>();
    public final Map<String, Object> pipelines = new LinkedHashMap<>();
    public final List<String> notes = new ArrayList<>();
    public int shadowRes = 1024;
    private long naivePairBytes1440;
    private Map<String, Object> budget;

    public static ResourcePlan build(PackData d, PassGraph.Frame f) {
        ResourcePlan rp = new ResourcePlan();
        try {
            rp.shadowRes = (int) Math.round(Double.parseDouble(f.directives.getOrDefault("shadowMapResolution", "1024").replaceAll("[fF]$", "")));
        } catch (NumberFormatException e) {
            rp.notes.add("shadowMapResolution not a number: " + f.directives.get("shadowMapResolution") + ", 1024 assumed");
        }
        JsonObject props = d.properties();
        // ---- colour targets
        for (PassGraph.Target t : f.targets.values()) {
            if (t.writers.isEmpty() && t.readers.isEmpty())
                continue;
            FormatCaps.Row row = FormatCaps.of(t.format);
            boolean shadow = t.name.startsWith("shadowcolor");
            Image im = new Image(t.name, shadow ? "shadowcolour" : "colour");
            im.vkFormat = row == null ? "?" : row.vk();
            im.bytesPerTexel = row == null ? 4 : row.bytes();
            if (shadow) {
                im.relative = false;
                im.w = im.h = rp.shadowRes;
                im.extent = "shadowMapResolution (" + rp.shadowRes + ")";
            } else if (t.size != null) {
                String a = t.size.get(0), b = t.size.get(1);
                if (a.contains(".") || b.contains(".")) {
                    im.fx = Double.parseDouble(a);
                    im.fy = Double.parseDouble(b);
                    im.extent = "render*" + a + " x render*" + b;
                } else {
                    im.relative = false;
                    im.w = Long.parseLong(a);
                    im.h = Long.parseLong(b);
                    im.extent = a + "x" + b;
                }
            } else {
                im.extent = "render";
            }
            im.layers = t.pairNeeded() ? 2 : 1;
            im.mips = t.mipmapped;
            if (t.writers.stream().anyMatch(w -> !w.endsWith(".csh")))
                im.usage.add("COLOR_ATTACHMENT");
            if (!t.readers.isEmpty() || t.mipmapped)
                im.usage.add("SAMPLED");
            if (t.storage)
                im.usage.add("STORAGE");
            im.usage.add("TRANSFER_DST"); // clears outside a pass, copies
            if (t.mipmapped || (t.endOfFrameCopy && t.pairNeeded()))
                im.usage.add("TRANSFER_SRC");
            if (t.doubled && !t.pairNeeded())
                im.note = "pair elided: no pass samples and writes it, no flip directive";
            if (t.endOfFrameCopy && t.pairNeeded())
                im.note = "flipped at frame end and never cleared: alt->main copy at frame end";
            rp.images.add(im);
            if (t.doubled)
                rp.naivePairBytes1440 += im.bytes(2560, 1440) / im.layers;
        }
        // ---- depth
        boolean readsD1 = false, readsD2 = false, readsS1 = false, shadowPass = false;
        for (PassGraph.Pass p : f.passes) {
            if (p.stage.equals("shadow") && p.kind.equals("geometry"))
                shadowPass = true;
            for (Map<String, Object> r : p.reads) {
                Object res = r.get("resource");
                if ("depthtex1 capture".equals(res))
                    readsD1 = true;
                if ("depthtex2 capture".equals(res))
                    readsD2 = true;
                if ("shadowtex1".equals(res))
                    readsS1 = true;
            }
        }
        Image depth = new Image("depthtex0 (main depth)", "depth");
        depth.vkFormat = "D32_SFLOAT";
        depth.bytesPerTexel = 4;
        depth.extent = "render";
        depth.usage.addAll(List.of("DEPTH_STENCIL_ATTACHMENT", "SAMPLED", "TRANSFER_SRC"));
        rp.images.add(depth);
        if (readsD1)
            rp.images.add(depthCopy("depthtex1", "render"));
        if (readsD2)
            rp.images.add(depthCopy("depthtex2", "render"));
        if (shadowPass) {
            Image sd = new Image("shadowtex0", "shadowdepth");
            sd.vkFormat = "D32_SFLOAT";
            sd.bytesPerTexel = 4;
            sd.relative = false;
            sd.w = sd.h = rp.shadowRes;
            sd.extent = "shadowMapResolution (" + rp.shadowRes + ")";
            sd.usage.addAll(List.of("DEPTH_STENCIL_ATTACHMENT", "SAMPLED", "TRANSFER_SRC"));
            if ("true".equals(f.directives.get("shadowHardwareFiltering")))
                sd.note = "shadowHardwareFiltering: sampled through a compare sampler (sampler2DShadow) and a plain one";
            rp.images.add(sd);
            if (readsS1) {
                Image s1 = depthCopy("shadowtex1", "shadowMapResolution");
                s1.relative = false;
                s1.w = s1.h = rp.shadowRes;
                rp.images.add(s1);
            }
        }
        // ---- noise
        if (!props.getAsJsonObject("textures").has("noise")) {
            int res = 256;
            try {
                res = Integer.parseInt(f.directives.getOrDefault("noiseTextureResolution", "256").trim());
            } catch (NumberFormatException ignored) {
            }
            Image n = new Image("noisetex", "noise");
            n.vkFormat = "R8G8B8A8_UNORM";
            n.bytesPerTexel = 4;
            n.relative = false;
            n.w = n.h = res;
            n.extent = res + "x" + res;
            n.usage.addAll(List.of("SAMPLED", "TRANSFER_DST"));
            n.note = "generated (OptiFine white noise)";
            rp.images.add(n);
        }
        // ---- custom images
        boolean customOptional = false;
        for (JsonElement e : props.getAsJsonObject("features").getAsJsonArray("optional"))
            if (e.getAsString().equals("CUSTOM_IMAGES"))
                customOptional = true;
        for (Map.Entry<String, JsonElement> e : props.getAsJsonObject("images").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Image im = new Image(e.getKey(), "custom");
            FormatCaps.Row row = FormatCaps.of(o.get("internalFormat").getAsString());
            im.vkFormat = row == null ? "?" : row.vk();
            im.bytesPerTexel = row == null ? 4 : row.bytes();
            List<Double> size = new ArrayList<>();
            for (JsonElement s : o.getAsJsonArray("size"))
                size.add(s.getAsDouble());
            if (o.get("relative").getAsBoolean()) {
                im.fx = size.get(0);
                im.fy = size.get(1);
                im.extent = "render*" + size.get(0) + " x render*" + size.get(1);
            } else {
                im.relative = false;
                im.w = Math.round(size.get(0));
                im.h = size.size() > 1 ? Math.round(size.get(1)) : 1;
                im.depth = size.size() > 2 ? Math.round(size.get(2)) : 1;
                im.extent = im.w + "x" + im.h + (size.size() > 2 ? "x" + im.depth : "");
            }
            im.usage.addAll(List.of("STORAGE", "SAMPLED", "TRANSFER_DST"));
            im.optional = customOptional;
            if (o.get("clearEachFrame").getAsBoolean())
                im.note = "cleared every frame";
            if (row == null)
                rp.notes.add("image " + e.getKey() + ": internal format " + o.get("internalFormat") + " unknown");
            rp.images.add(im);
        }
        // ---- buffers
        for (Map.Entry<String, JsonElement> e : props.getAsJsonObject("bufferObjects").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            long bytes = o.get("bytes").getAsLong();
            boolean rel = o.has("relative") && o.get("relative").getAsBoolean();
            rp.buffers.add(new Buffer("bufferObject." + e.getKey() + " (set 1 binding " + e.getKey() + ")", bytes,
                    rel ? "bytes per pixel x scaled render size" : "absolute"));
        }
        // ---- attachment sets, pipelines, descriptor layout
        Map<String, String> fmt = new TreeMap<>();
        for (Image im : rp.images)
            fmt.put(im.name, im.vkFormat);
        Set<String> sampledNames = new java.util.TreeSet<>();
        String culling = d.flag("shadow.culling");
        for (PassGraph.Pass p : f.passes) {
            if (!p.kind.equals("geometry") && !p.kind.equals("fullscreen"))
                continue;
            List<String> colour = new ArrayList<>();
            for (Map<String, Object> w : p.writes)
                colour.add(w.get("target") + ":" + fmt.getOrDefault((String) w.get("target"), "?"));
            String depthFmt = p.kind.equals("geometry") ? (p.stage.equals("shadow") ? "shadowtex0:D32_SFLOAT" : "depthtex0:D32_SFLOAT") : null;
            Map<String, Object> as = Json.obj("colour", colour, "depth", depthFmt, "renderArea",
                    p.scale != null ? "scaled viewport " + p.scale : "full target");
            rp.attachmentSets.put(p.id, as);
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("program", p.program);
            key.put("colourFormats", colour);
            key.put("blend", p.blend);
            if (p.kind.equals("fullscreen")) {
                key.put("depth", "none");
                key.put("cull", "none");
                key.put("vertexInput", "none (full-screen triangle from gl_VertexIndex; pack vertex stage reads iris_Vertex/MultiTexCoord0 from a 3-vertex buffer)");
            } else {
                key.put("depth", p.stage.equals("gbuffers-translucent") ? "test LEQUAL, write per render type" : "test LEQUAL, write");
                boolean reversed = p.stage.equals("shadow") && "reversed".equals(culling);
                key.put("cull", p.stage.equals("shadow")
                        ? (reversed ? "GL front -> VK BACK (shadow.culling=reversed)" : "per render type, GL_BACK -> VK FRONT")
                        : "per render type: GL_BACK -> VK FRONT, CCW front (Orientation OFFSCREEN)");
                key.put("vertexInput", GeometryNeeds.vertexFormatOf(p.geometry));
                key.put("alphaTest", p.alphaTest);
            }
            rp.pipelines.put(p.id, key);
            PackData.Unit u = d.units.get(p.unit);
            if (u != null)
                sampledNames.addAll(u.sampled().keySet());
        }
        for (PassGraph.Pass p : f.passes)
            if (p.kind.equals("compute")) {
                PackData.Unit u = d.units.get(p.unit);
                if (u != null)
                    sampledNames.addAll(u.sampled().keySet());
            }
        JsonObject table = d.pack.getAsJsonObject("bindings");
        Map<String, Object> set0 = new LinkedHashMap<>();
        set0.put("0", "UNIFORM_BUFFER IrohPackUniforms (per program size, dynamic offset)");
        Map<Integer, String> byBinding = new TreeMap<>();
        for (String n : sampledNames)
            if (table.has(n))
                byBinding.put(table.get(n).getAsInt(), n);
        for (Map.Entry<Integer, String> e : byBinding.entrySet()) {
            boolean image = props.getAsJsonObject("images").has(e.getValue()) || e.getValue().matches("(shadow)?colorimg\\d+");
            set0.put(String.valueOf(e.getKey()), (image ? "STORAGE_IMAGE " : "COMBINED_IMAGE_SAMPLER ") + e.getValue());
        }
        rp.descriptorLayout.put("set0", set0);
        Map<String, Object> set1 = new LinkedHashMap<>();
        for (Buffer b : rp.buffers)
            set1.put(b.name(), "STORAGE_BUFFER " + b.bytes() + " B");
        rp.descriptorLayout.put("set1", set1);
        rp.descriptorLayout.put("note", "one pack-wide layout (P3-1 binding table) shared by every program of the folder; a program binds "
                + "only what it references; a name sampled as sampler2DShadow in one program and sampler2D in another keeps one "
                + "binding (same descriptor type) with a compare sampler or a plain one per program");
        rp.budget = rp.budgetCheck();
        return rp;
    }

    private static Image depthCopy(String name, String extent) {
        Image c = new Image(name, "depth");
        c.vkFormat = "D32_SFLOAT";
        c.bytesPerTexel = 4;
        c.extent = extent;
        c.usage.addAll(List.of("SAMPLED", "TRANSFER_DST"));
        c.note = "copy of the main depth at its capture point";
        return c;
    }

    public long bytes(int w, int h) {
        long b = 0;
        for (Image im : images)
            b += im.bytes(w, h);
        for (Buffer bu : buffers)
            b += bu.bytes();
        return b;
    }

    public double mib(int w, int h) {
        return bytes(w, h) / 1048576.0;
    }

    // ------------------------------------------------------------------------------------------------ budget

    /** One rung: name, what it changes, how the plan is re-measured. Applied in order until the plan fits. */
    public record Rung(String name, String action, String cost) {}

    public static final List<Rung> LADDER = List.of(
            new Rung("ELIDE_PAIRS", "allocate one image instead of a ping-pong pair wherever no pass samples and writes the target (always on)", "none"),
            new Rung("SHADOW_HALF", "rewrite shadowMapResolution to half before translation (Vitrail's shadow scale; the pack recomputes its filter radius), down to 512", "softer, shorter-reaching shadows"),
            new Rung("RENDER_SCALE_75", "render size 0.75 x window for every render-sized image, bilinear (later FSR/DLSS) to the window at present", "blur until P4's upscaler"),
            new Rung("DROP_OPTIONAL_IMAGES", "refuse custom images when the pack lists CUSTOM_IMAGES in iris.features.optional: recompile without IRIS_FEATURE_CUSTOM_IMAGES", "pack features off (Navidson: coloured lighting volume)"),
            new Rung("RENDER_SCALE_50", "render size 0.5 x window", "heavy blur"),
            new Rung("REFUSE_PACK", "do not load the pack: RASTER path, message on screen and in the log", "no shaders"));

    private Map<String, Object> budgetCheck() {
        long cap = MemoryBudget.autoCap(ASSUMED_VRAM);
        long allowance = (long) (cap * PACK_SHARE);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("memoryGuardCapMiB", cap >> 20);
        m.put("assumedVramGiB", ASSUMED_VRAM >> 30);
        m.put("packShare", PACK_SHARE);
        m.put("packAllowanceMiB", allowance >> 20);
        Map<String, Object> per = new LinkedHashMap<>();
        int[][] res = {{1920, 1080}, {2560, 1440}, {3840, 2160}};
        for (int[] r : res) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("planMiB", Math.round(mib(r[0], r[1])));
            List<String> steps = new ArrayList<>();
            steps.add("ELIDE_PAIRS");
            int sres = shadowRes;
            double scale = 1.0;
            boolean dropped = false;
            long b = bytesWith(r[0], r[1], sres, scale, dropped);
            while (b > allowance && sres > 512) {
                sres /= 2;
                steps.add("SHADOW_HALF->" + sres);
                b = bytesWith(r[0], r[1], sres, scale, dropped);
            }
            if (b > allowance) {
                scale = 0.75;
                steps.add("RENDER_SCALE_75");
                b = bytesWith(r[0], r[1], sres, scale, dropped);
            }
            if (b > allowance && images.stream().anyMatch(i -> i.optional)) {
                dropped = true;
                steps.add("DROP_OPTIONAL_IMAGES");
                b = bytesWith(r[0], r[1], sres, scale, dropped);
            }
            if (b > allowance) {
                scale = 0.5;
                steps.add("RENDER_SCALE_50");
                b = bytesWith(r[0], r[1], sres, scale, dropped);
            }
            if (b > allowance)
                steps.add("REFUSE_PACK");
            x.put("ladder", steps);
            x.put("afterLadderMiB", Math.round(b / 1048576.0));
            x.put("fits", !steps.contains("REFUSE_PACK"));
            per.put(r[0] + "x" + r[1], x);
        }
        m.put("resolutions", per);
        m.put("pairElisionSavesMiB@1440p", Math.round((naivePairBytes1440) / 1048576.0
                - images.stream().filter(i -> i.layers == 2).mapToLong(i -> i.bytes(2560, 1440) / 2).sum() / 1048576.0));
        m.put("allocation", "every pack image through MemoryGuard.allow(bytes, essential=false); a refusal at load walks the ladder, "
                + "a refusal later (resize) drops to the next rung and rebuilds the plan");
        return m;
    }

    private long bytesWith(int w, int h, int sres, double scale, boolean dropOptional) {
        int rw = (int) Math.round(w * scale), rh = (int) Math.round(h * scale);
        long b = 0;
        for (Image im : images) {
            if (dropOptional && im.optional)
                continue;
            if (im.role.startsWith("shadow")) {
                long one = (long) sres * sres * im.bytesPerTexel * im.layers;
                b += im.mips ? one * 4 / 3 : one;
            } else {
                b += im.bytes(rw, rh);
            }
        }
        for (Buffer bu : buffers)
            b += bu.bytes();
        return b;
    }

    public String budgetVerdict() {
        @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) ((Map<String, Object>) budget.get("resolutions")).get("2560x1440");
        @SuppressWarnings("unchecked") List<String> steps = (List<String>) r.get("ladder");
        return steps.size() == 1 ? "yes" : String.join(",", steps.subList(1, steps.size()));
    }

    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> ims = new ArrayList<>();
        for (Image im : images) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", im.name);
            x.put("role", im.role);
            x.put("vkFormat", im.vkFormat);
            x.put("extent", im.extent);
            x.put("layers", im.layers);
            x.put("mips", im.mips ? "full chain" : "1");
            x.put("usage", new ArrayList<>(im.usage));
            if (im.optional)
                x.put("optional", true);
            x.put("MiB@1080p", round1(im.bytes(1920, 1080)));
            x.put("MiB@1440p", round1(im.bytes(2560, 1440)));
            x.put("MiB@2160p", round1(im.bytes(3840, 2160)));
            if (!im.note.isEmpty())
                x.put("note", im.note);
            ims.add(x);
        }
        m.put("images", ims);
        List<Object> bs = new ArrayList<>();
        for (Buffer b : buffers)
            bs.add(Json.obj("name", b.name(), "bytes", b.bytes(), "rule", b.rule()));
        m.put("buffers", bs);
        m.put("totalMiB", Json.obj("1920x1080", round1(bytes(1920, 1080)), "2560x1440", round1(bytes(2560, 1440)), "3840x2160", round1(bytes(3840, 2160))));
        m.put("budget", budget);
        m.put("attachmentSets", attachmentSets);
        m.put("descriptorLayout", descriptorLayout);
        m.put("pipelines", pipelines);
        List<Object> ladder = new ArrayList<>();
        for (Rung r : LADDER)
            ladder.add(Json.obj("rung", r.name(), "action", r.action(), "cost", r.cost()));
        m.put("ladder", ladder);
        m.put("notes", notes);
        return m;
    }

    static double round1(long b) {
        return Math.round(b / 1048576.0 * 10) / 10.0;
    }

    public String toText() {
        StringBuilder sb = new StringBuilder("-- resources (MiB at 1080p / 1440p / 2160p)\n");
        for (Image im : images)
            sb.append(String.format("  %-24s %-26s %-28s x%d %s %7.1f %7.1f %7.1f  %s%n", im.name, im.vkFormat, im.extent, im.layers,
                    im.mips ? "mips" : "    ", round1(im.bytes(1920, 1080)), round1(im.bytes(2560, 1440)), round1(im.bytes(3840, 2160)),
                    String.join("|", im.usage)));
        for (Buffer b : buffers)
            sb.append(String.format("  %-24s %d B (%s)%n", b.name(), b.bytes(), b.rule()));
        sb.append(String.format("  total %.1f / %.1f / %.1f MiB; budget %s%n", mib(1920, 1080), mib(2560, 1440), mib(3840, 2160), budget));
        return sb.toString();
    }
}
