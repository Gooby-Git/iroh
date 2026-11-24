package dev.elemore.iroh.shaderpack.graph;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.elemore.iroh.shaderpack.Formats;
import dev.elemore.iroh.shaderpack.Json;
import dev.elemore.iroh.shaderpack.ProgramSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P3-2 pass graph compiler: one ordered, validated frame plan per program folder (root and each dimension folder)
 * from the P3-1 dump ({@link PackData}).
 * <p>
 * Order (Iris order as digested in iris-loader.md 3.1 and Vitrail docs/frame.md; geometry order follows vanilla
 * 1.21.1 LevelRenderer.renderLevel): begin* -> shadow (geometry, shadowtex1 captured between its opaque and
 * translucent casters) -> shadowcomp* -> prepare* -> solid gbuffers (sky, terrain, LOD, entities, block entities,
 * outline, opaque particles, depthtex2 captured, opaque hand, depthtex1 captured) -> deferred_pre flips -> deferred* ->
 * translucent gbuffers (water, translucent entities/blocks/particles, clouds, weather, world border, translucent hand)
 * -> composite_pre flips -> composite* -> final. Each fullscreen family runs base name then 1..99; each program's
 * compute files are dispatched right before its raster pass (letter-less, then _a.._z).
 * <p>
 * Buffer halves (OptiFine shaders.txt "flip" rules, Vitrail render-targets.md): one set of flipped targets per chain.
 * A read lands on the half produced by the last write (MAIN when none). A fullscreen pass reads the current half and
 * writes the other one, then flips what it wrote ({@code flip.<program>.<buffer>=false} keeps it, {@code =true} flips
 * a target it did not write). Geometry passes write the half they read and flip nothing. {@code deferred_pre} /
 * {@code composite_pre} flip directives play at stage opening. Shadowcomp has its own flip set over shadowcolor.
 * A target is doubled when a fullscreen pass writes it or a flip directive names it.
 */
public final class PassGraph {

    public enum Severity { ERROR, WARN, INFO }

    public record Issue(Severity severity, String pass, String message) {}

    public record GeometryClass(String name, String program, String phase, String vanilla) {}

    /** Geometry classes in draw order (vanilla 1.21.1 + Iris program names). */
    public static final List<GeometryClass> GEOMETRY = List.of(
            new GeometryClass("sky_basic", "gbuffers_skybasic", "solid", "sky, horizon, stars, void (renderSky)"),
            new GeometryClass("sky_textured", "gbuffers_skytextured", "solid", "sun, moon (renderSky)"),
            new GeometryClass("terrain_solid", "gbuffers_terrain_solid", "solid", "RenderType.solid sections"),
            new GeometryClass("terrain_cutout_mipped", "gbuffers_terrain_cutout", "solid", "RenderType.cutoutMipped sections"),
            new GeometryClass("terrain_cutout", "gbuffers_terrain_cutout", "solid", "RenderType.cutout sections"),
            new GeometryClass("lod_vista", "vista_terrain", "solid", "Vista far terrain (Phase 9 contract)"),
            new GeometryClass("entities", "gbuffers_entities", "solid", "entity render types"),
            new GeometryClass("entities_glowing", "gbuffers_entities", "solid", "outline/glowing entities (Iris ignores gbuffers_entities_glowing: drawn by gbuffers_entities)"),
            new GeometryClass("armor_glint", "gbuffers_armor_glint", "solid", "glint render types"),
            new GeometryClass("spider_eyes", "gbuffers_spidereyes", "solid", "RenderType.eyes"),
            new GeometryClass("lightning", "gbuffers_lightning", "solid", "RenderType.lightning"),
            new GeometryClass("block_entities", "gbuffers_block", "solid", "block entity renderers"),
            new GeometryClass("beacon_beam", "gbuffers_beaconbeam", "solid", "RenderType.beaconBeam"),
            new GeometryClass("damaged_block", "gbuffers_damagedblock", "solid", "ModelBakery.DESTROY_TYPES"),
            new GeometryClass("outline", "gbuffers_line", "solid", "block outline, fishing line, leashes"),
            new GeometryClass("particles_opaque", "gbuffers_particles", "solid", "opaque particles (particles.ordering mixed/before)"),
            new GeometryClass("hand_solid", "gbuffers_hand", "solid", "first-person hand, opaque"),
            new GeometryClass("terrain_translucent", "gbuffers_water", "translucent", "RenderType.translucent sections"),
            new GeometryClass("lod_vista_water", "vista_water", "translucent", "Vista far water (Phase 9 contract)"),
            new GeometryClass("block_entities_translucent", "gbuffers_block_translucent", "translucent", "translucent block entities"),
            new GeometryClass("entities_translucent", "gbuffers_entities_translucent", "translucent", "translucent entity render types"),
            new GeometryClass("particles_translucent", "gbuffers_particles_translucent", "translucent", "translucent particles"),
            new GeometryClass("clouds", "gbuffers_clouds", "translucent", "renderClouds (skipped when clouds=off)"),
            new GeometryClass("weather", "gbuffers_weather", "translucent", "renderSnowAndRain"),
            new GeometryClass("world_border", "gbuffers_textured_lit", "translucent", "renderWorldBorder"),
            new GeometryClass("hand_translucent", "gbuffers_hand_water", "translucent", "first-person hand, translucent"));

    public static final List<GeometryClass> SHADOW_GEOMETRY = List.of(
            new GeometryClass("shadow_terrain_solid", "shadow_solid", "shadow-opaque", "solid sections (shadowTerrain)"),
            new GeometryClass("shadow_terrain_cutout", "shadow_cutout", "shadow-opaque", "cutout sections (shadowTerrain)"),
            new GeometryClass("shadow_entities", "shadow_entities", "shadow-opaque", "entities (shadowEntities)"),
            new GeometryClass("shadow_block_entities", "shadow_block", "shadow-opaque", "block entities (shadowBlockEntities)"),
            new GeometryClass("shadow_lightning", "shadow_lightning", "shadow-opaque", "lightning"),
            new GeometryClass("shadow_translucent", "shadow_water", "shadow-translucent", "translucent sections (shadowTranslucent)"));

    public static final class Pass {
        public String id;
        public String kind;          // compute, fullscreen, geometry, flip, capture, mipgen, copy
        public String stage;         // begin, shadow, shadowcomp, prepare, gbuffers-solid, deferred, gbuffers-translucent, composite, final
        public String program;       // resolved program ("" for engine passes)
        public String unit;          // unit id
        public final List<String> geometry = new ArrayList<>();
        public final List<Map<String, Object>> writes = new ArrayList<>();
        public final List<Map<String, Object>> reads = new ArrayList<>();
        public final List<String> flipsAfter = new ArrayList<>();
        public final List<String> mipmapsBefore = new ArrayList<>();
        public Object scale;         // [s] or [s, ox, oy]
        public Object blend;         // "vanilla" | "off" | [4 factors] | {buffer: ...}
        public Object alphaTest;
        public Map<String, Object> dispatch;
        public String depth;         // geometry: "test+write main depth"; capture: what is copied
        public final List<String> notes = new ArrayList<>();

        Object toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("kind", kind);
            m.put("stage", stage);
            if (program != null && !program.isEmpty())
                m.put("program", program);
            if (unit != null)
                m.put("unit", unit);
            if (!geometry.isEmpty())
                m.put("geometry", geometry);
            if (!writes.isEmpty())
                m.put("writes", writes);
            if (!reads.isEmpty())
                m.put("reads", reads);
            if (!flipsAfter.isEmpty())
                m.put("flipsAfter", flipsAfter);
            if (!mipmapsBefore.isEmpty())
                m.put("mipmapsBefore", mipmapsBefore);
            if (scale != null)
                m.put("scale", scale);
            if (blend != null)
                m.put("blend", blend);
            if (alphaTest != null)
                m.put("alphaTest", alphaTest);
            if (dispatch != null)
                m.put("dispatch", dispatch);
            if (depth != null)
                m.put("depth", depth);
            if (!notes.isEmpty())
                m.put("notes", notes);
            return m;
        }
    }

    /** A colour target of the folder (colortexN or shadowcolorN). */
    public static final class Target {
        public final String name;
        public String format = "RGBA8";
        public String formatSource = "default";
        public boolean clear = true;
        public String clearColor;
        public List<String> size;          // size.buffer value, null = render size (shadowcolor: shadow map size)
        public boolean doubled;
        public boolean readAndWrittenInOnePass;
        public boolean flipDirective;
        public boolean writtenByFlipFalse;
        public boolean mipmapped;
        public boolean storage;
        public final Set<String> writers = new LinkedHashSet<>();
        public final Set<String> readers = new LinkedHashSet<>();
        public boolean endOfFrameCopy;
        public boolean historyRead;        // read before its first write in the frame and not cleared

        Target(String name) {
            this.name = name;
        }

        public boolean pairNeeded() {
            // a ping-pong pair is only needed when one pass both samples and writes the target (temporal history,
            // in-place filters) or a flip directive / flip=false makes a read land on a half other than the latest
            return doubled && (readAndWrittenInOnePass || flipDirective || writtenByFlipFalse);
        }
    }

    public static final class Frame {
        public final String folder;
        public final List<String> dimensionIds = new ArrayList<>();
        public final List<Pass> passes = new ArrayList<>();
        public final Map<String, Target> targets = new TreeMap<>(PassGraph::targetOrder);
        public final Map<String, String> directives = new LinkedHashMap<>();
        public final Map<String, String> drawnBy = new LinkedHashMap<>();
        public final List<Issue> issues = new ArrayList<>();

        Frame(String folder) {
            this.folder = folder;
        }

        /** distinct messages of a severity (one message found in several passes counts once) */
        public long count(Severity s) {
            return grouped(s).size();
        }

        /** message -> passes, in first-seen order */
        public Map<String, List<String>> grouped(Severity s) {
            Map<String, List<String>> m = new LinkedHashMap<>();
            for (Issue i : issues)
                if (i.severity() == s)
                    m.computeIfAbsent(i.message(), k -> new ArrayList<>()).add(i.pass());
            return m;
        }
    }

    static int targetOrder(String a, String b) {
        String pa = a.replaceAll("\\d", "");
        String pb = b.replaceAll("\\d", "");
        int c = pa.compareTo(pb);
        if (c != 0)
            return c;
        return Integer.compare(num(a), num(b));
    }

    static int num(String s) {
        String d = s.replaceAll("\\D", "");
        return d.isEmpty() ? -1 : Integer.parseInt(d);
    }

    // =================================================================================================== build

    private final PackData d;
    private final JsonObject props;

    private PassGraph(PackData d) {
        this.d = d;
        this.props = d.properties();
    }

    public static Map<String, Frame> build(PackData d) {
        PassGraph g = new PassGraph(d);
        Map<String, Frame> out = new LinkedHashMap<>();
        Map<String, List<String>> ids = new LinkedHashMap<>();
        JsonObject progs = d.pack.getAsJsonObject("programs");
        for (JsonElement e : progs.getAsJsonArray("dimensions")) {
            JsonObject dim = e.getAsJsonObject();
            String folder = dim.get("exists").getAsBoolean() ? dim.get("folder").getAsString() : "";
            List<String> l = ids.computeIfAbsent(folder, k -> new ArrayList<>());
            for (JsonElement i : dim.getAsJsonArray("ids"))
                l.add(i.getAsString());
        }
        for (String folder : d.folders().keySet()) {
            String dir = folder.equals("<root>") ? "" : folder;
            if (dir.isEmpty() && d.programs("").isEmpty())
                continue;
            Frame f = g.frame(dir);
            f.dimensionIds.addAll(ids.getOrDefault(dir, dir.isEmpty() ? List.of("(any dimension without a folder)") : List.of()));
            out.put(folder, f);
        }
        return out;
    }

    private static final Pattern NUMBERED = Pattern.compile("(begin|prepare|deferred|composite|shadowcomp)([1-9][0-9]?)?");

    /** served = present, enabled, compiled and not another mod's program */
    private PackData.Unit served(String dir, String program) {
        PackData.Unit u = d.raster(dir, program);
        return u != null && u.enabled && u.passed ? u : null;
    }

    private final Set<String> reportedBroken = new java.util.HashSet<>();
    private Frame current;

    private String resolve(String dir, String name) {
        String n = name;
        int guard = 0;
        while (n != null && guard++ < 32) {
            if (served(dir, n) != null)
                return n;
            PackData.Unit u = d.raster(dir, n);
            if (u != null && u.enabled && !u.passed && reportedBroken.add(dir + "/" + n))
                current.issues.add(new Issue(Severity.ERROR, n, "did not compile (" + u.json.get("failStage").getAsString()
                        + ": " + u.json.get("failMessage").getAsString() + "): the plan falls back past it, which changes the image"));
            n = ProgramSet.FALLBACK.get(n);
        }
        return null;
    }

    private List<String> family(String dir, String base) {
        Map<Integer, String> m = new TreeMap<>();
        for (String p : d.programs(dir).keySet()) {
            Matcher x = NUMBERED.matcher(p);
            if (x.matches() && x.group(1).equals(base))
                m.put(x.group(2) == null ? 0 : Integer.parseInt(x.group(2)), p);
        }
        return new ArrayList<>(m.values());
    }

    private Frame frame(String dir) {
        Frame f = new Frame(dir.isEmpty() ? "<root>" : dir);
        current = f;
        foldDirectives(dir, f);
        Chain scene = new Chain(f, "scene");
        Chain shadowChain = new Chain(f, "shadow");

        for (String p : family(dir, "begin"))
            fullscreen(dir, f, scene, p, "begin");

        // shadow
        String shadowEnabledFlag = d.flag("shadow.enabled");
        PackData.Unit shadow = served(dir, "shadow");
        if (shadow != null && !"false".equals(shadowEnabledFlag)) {
            Pass opaque = null;
            for (GeometryClass gc : SHADOW_GEOMETRY) {
                String prog = resolve(dir, gc.program());
                if (prog == null)
                    continue;
                if (gc.phase().equals("shadow-translucent")) {
                    Pass cap = new Pass();
                    cap.id = "capture:shadowtex1";
                    cap.kind = "capture";
                    cap.stage = "shadow";
                    cap.depth = "shadowtex1 := shadow depth (opaque casters only)";
                    f.passes.add(cap);
                }
                Pass p = geometry(dir, f, scene, prog, gc, "shadow", true);
                if (gc.phase().equals("shadow-opaque"))
                    opaque = p;
            }
            if (opaque == null)
                f.issues.add(new Issue(Severity.INFO, "shadow", "shadow program present but no caster class resolved"));
        } else if (shadow == null && d.raster(dir, "shadow") != null) {
            PackData.Unit su = d.raster(dir, "shadow");
            if (su.enabled && !su.passed)
                f.issues.add(new Issue(Severity.ERROR, "shadow", "did not compile (" + su.json.get("failStage").getAsString() + ": "
                        + su.json.get("failMessage").getAsString() + "): no shadow pass, which changes the image"));
            else
                f.issues.add(new Issue(Severity.INFO, "shadow", "shadow program switched off: no shadow pass"));
        }
        for (String p : family(dir, "shadowcomp"))
            fullscreen(dir, f, shadowChain, p, "shadowcomp");
        for (String p : family(dir, "prepare"))
            fullscreen(dir, f, scene, p, "prepare");

        // solid geometry
        String clouds = d.flag("clouds");
        String ordering = d.flag("particles.ordering");
        for (GeometryClass gc : GEOMETRY) {
            if (!gc.phase().equals("solid"))
                continue;
            if (gc.name().equals("hand_solid")) {
                Pass cap = new Pass();
                cap.id = "capture:depthtex2";
                cap.kind = "capture";
                cap.stage = "gbuffers-solid";
                cap.depth = "depthtex2 := main depth (no translucents, no hand)";
                f.passes.add(cap);
            }
            if (gc.name().equals("particles_opaque") && "after".equals(ordering))
                continue;
            String prog = resolve(dir, gc.program());
            f.drawnBy.put(gc.name(), prog);
            if (prog != null)
                geometry(dir, f, scene, prog, gc, "gbuffers-solid", false);
        }
        Pass cap1 = new Pass();
        cap1.id = "capture:depthtex1";
        cap1.kind = "capture";
        cap1.stage = "gbuffers-solid";
        cap1.depth = "depthtex1 := main depth (no translucents)";
        f.passes.add(cap1);

        preFlips(f, scene, "deferred_pre", "deferred");
        for (String p : family(dir, "deferred"))
            fullscreen(dir, f, scene, p, "deferred");

        for (GeometryClass gc : GEOMETRY) {
            if (!gc.phase().equals("translucent"))
                continue;
            if (gc.name().equals("clouds") && "off".equals(clouds)) {
                f.drawnBy.put(gc.name(), null);
                continue;
            }
            String prog = resolve(dir, gc.program());
            f.drawnBy.put(gc.name(), prog);
            if (prog != null)
                geometry(dir, f, scene, prog, gc, "gbuffers-translucent", false);
        }
        if ("before".equals(ordering))
            f.issues.add(new Issue(Severity.INFO, "particles", "particles.ordering=before: translucent particles drawn with the solid phase (not modelled)"));

        preFlips(f, scene, "composite_pre", "composite");
        for (String p : family(dir, "composite"))
            fullscreen(dir, f, scene, p, "composite");
        if (served(dir, "final") != null || !d.computes(dir, "final").isEmpty())
            fullscreen(dir, f, scene, "final", "final");
        else
            f.issues.add(new Issue(Severity.INFO, "final", "no final program: Iroh presents colortex0 (Iris behaviour UNVERIFIED)"));

        scene.finish();
        shadowChain.finish();
        validate(dir, f);
        // merge consecutive geometry passes drawn by the same program with the same attachments
        return f;
    }

    // ------------------------------------------------------------------------------------- directives / targets

    /** Iris fold order: shadowcomp, begin, prepare, gbuffers (incl. final), deferred, composite; last live wins. */
    private void foldDirectives(String dir, Frame f) {
        List<PackData.Unit> order = new ArrayList<>();
        String[] families = {"shadowcomp", "begin", "prepare", "gbuffers", "deferred", "composite"};
        for (String fam : families)
            for (PackData.Unit u : d.units.values()) {
                if (!u.dir.equals(dir) || !u.enabled || !u.passed)
                    continue;
                String p = u.program;
                boolean match = switch (fam) {
                    case "gbuffers" -> !NUMBERED.matcher(p).matches() && !p.endsWith("_pre");
                    default -> {
                        Matcher m = NUMBERED.matcher(p);
                        yield m.matches() && m.group(1).equals(fam);
                    }
                };
                if (match)
                    order.add(u);
            }
        for (PackData.Unit u : order)
            f.directives.putAll(u.constDirectives());
        Pattern buf = Pattern.compile("(colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4]|shadowcolor\\d*)(Format|Clear|ClearColor)");
        for (Map.Entry<String, String> e : f.directives.entrySet()) {
            Matcher m = buf.matcher(e.getKey());
            if (!m.matches())
                continue;
            String t = canonical(m.group(1));
            Target tg = target(f, t);
            switch (m.group(2)) {
                case "Format" -> {
                    tg.format = e.getValue().trim();
                    tg.formatSource = e.getKey();
                }
                case "Clear" -> tg.clear = !e.getValue().trim().equals("false");
                case "ClearColor" -> tg.clearColor = e.getValue().trim();
                default -> {
                }
            }
        }
        JsonObject sb = props.getAsJsonObject("sizeBuffer");
        for (Map.Entry<String, JsonElement> e : sb.entrySet()) {
            Target t = target(f, canonical(e.getKey()));
            List<String> v = new ArrayList<>();
            for (JsonElement x : e.getValue().getAsJsonArray())
                v.add(x.getAsString());
            t.size = v;
        }
    }

    static String canonical(String n) {
        if (n.equals("shadowcolor"))
            return "shadowcolor0";
        return Formats.canonical(n);
    }

    static Target target(Frame f, String name) {
        return f.targets.computeIfAbsent(name, Target::new);
    }

    // ------------------------------------------------------------------------------------------------ chain

    /** The flip state of one chain (scene colortex or shadowcolor). */
    private final class Chain {
        final Frame f;
        final String kind;
        final Set<String> flipped = new TreeSet<>();
        final Set<String> writtenThisFrame = new TreeSet<>();

        Chain(Frame f, String kind) {
            this.f = f;
            this.kind = kind;
        }

        String readHalf(String t) {
            return flipped.contains(t) ? "alt" : "main";
        }

        void finish() {
            for (String t : flipped) {
                Target tg = f.targets.get(t);
                if (tg != null && !tg.clear) {
                    tg.endOfFrameCopy = true;
                }
            }
        }
    }

    private void preFlips(Frame f, Chain c, String pre, String stage) {
        JsonObject flip = props.getAsJsonObject("flip");
        Pass p = null;
        for (Map.Entry<String, JsonElement> e : flip.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(pre + "."))
                continue;
            String t = canonical(k.substring(pre.length() + 1));
            if (!e.getValue().getAsBoolean())
                continue;
            if (p == null) {
                p = new Pass();
                p.id = pre;
                p.kind = "flip";
                p.stage = stage;
                f.passes.add(p);
            }
            toggle(c, t);
            Target tg = target(f, t);
            tg.doubled = true;
            tg.flipDirective = true;
            p.flipsAfter.add(t);
        }
    }

    private static void toggle(Chain c, String t) {
        if (!c.flipped.remove(t))
            c.flipped.add(t);
    }

    // --------------------------------------------------------------------------------------------- passes

    private String stageOfTexture(String passStage) {
        return switch (passStage) {
            case "shadow", "gbuffers-solid", "gbuffers-translucent" -> "gbuffers";
            case "deferred" -> "deferred";
            case "begin", "prepare" -> "prepare";
            default -> "composite";
        };
    }

    private Pass geometry(String dir, Frame f, Chain scene, String prog, GeometryClass gc, String stage, boolean shadowPass) {
        PackData.Unit u = served(dir, prog);
        // consecutive classes drawn by one program merge into one pass
        Pass last = f.passes.isEmpty() ? null : f.passes.get(f.passes.size() - 1);
        if (last != null && last.kind.equals("geometry") && prog.equals(last.program) && stage.equals(last.stage)) {
            last.geometry.add(gc.name());
            return last;
        }
        Pass p = new Pass();
        p.id = (shadowPass ? "shadow:" : "") + prog + (stage.equals("gbuffers-translucent") && !shadowPass ? ":translucent" : "");
        int dup = 2;
        String base = p.id;
        while (idExists(f, p.id))
            p.id = base + "#" + dup++;
        p.kind = "geometry";
        p.stage = stage;
        p.program = prog;
        p.unit = u.id;
        p.geometry.add(gc.name());
        p.depth = shadowPass ? "shadow depth (test + write)" : "main depth (vanilla test/write state of the render type)";
        String prefix = shadowPass ? "shadowcolor" : "colortex";
        List<Integer> db = attachments(u, p);
        reads(dir, f, scene, u, p, stage);
        for (int i = 0; i < db.size(); i++) {
            String t = prefix + db.get(i);
            Target tg = target(f, t);
            tg.writers.add(p.id);
            Map<String, Object> w = Json.obj("location", i, "target", t, "half", shadowPass ? "main" : scene.readHalf(t));
            p.writes.add(w);
            if (!shadowPass)
                scene.writtenThisFrame.add(t);
        }
        p.blend = blend(prog, db, prefix, "vanilla");
        p.alphaTest = alphaTest(prog);
        for (Map<String, Object> r : p.reads)
            if (r.get("resource") instanceof String res && p.writes.stream().anyMatch(w -> w.get("target").equals(res)))
                f.issues.add(new Issue(Severity.WARN, p.id, "geometry pass samples " + res + ", which it also writes: needs a copy of that half (Vitrail TargetCopies)"));
        f.passes.add(p);
        return p;
    }

    private boolean idExists(Frame f, String id) {
        for (Pass p : f.passes)
            if (p.id.equals(id))
                return true;
        return false;
    }

    private void fullscreen(String dir, Frame f, Chain c, String prog, String stage) {
        for (PackData.Unit cu : d.computes(dir, prog)) {
            if (!cu.enabled || !cu.passed)
                continue;
            Pass p = new Pass();
            p.id = cu.id.substring(cu.id.lastIndexOf('/') + 1);
            p.kind = "compute";
            p.stage = stage;
            p.program = prog;
            p.unit = cu.id;
            reads(dir, f, c, cu, p, stage);
            p.dispatch = dispatch(cu, f);
            for (Map.Entry<String, String> im : cu.images().entrySet()) {
                String t = imageTarget(im.getKey());
                if (t != null) {
                    Target tg = target(f, t);
                    tg.storage = true;
                    tg.writers.add(p.id);
                    p.writes.add(Json.obj("image", im.getKey(), "target", t, "half", c.readHalf(t), "format", im.getValue()));
                    p.notes.add(im.getKey() + " stores into " + t + " in place, on the half later passes read (no flip; Iris rule UNVERIFIED)");
                }
            }
            f.passes.add(p);
        }
        PackData.Unit u = served(dir, prog);
        if (u == null) {
            PackData.Unit ru = d.raster(dir, prog);
            if (ru != null && ru.enabled && !ru.passed)
                f.issues.add(new Issue(Severity.ERROR, prog, "did not compile (" + ru.json.get("failStage").getAsString() + ": "
                        + ru.json.get("failMessage").getAsString() + "): pass missing, parity of later passes shifts"));
            else if (ru != null)
                f.issues.add(new Issue(Severity.INFO, prog, "switched off (" + offReason(dir, prog) + "): pass skipped, parity of later passes follows"));
            return;
        }
        Pass p = new Pass();
        p.id = prog;
        p.kind = "fullscreen";
        p.stage = stage;
        p.program = prog;
        p.unit = u.id;
        JsonObject scale = props.getAsJsonObject("scale");
        if (scale.has(prog))
            p.scale = jsonValue(scale.get(prog));
        // mipmaps requested by this program's own directives, generated on the half it reads
        for (Map.Entry<String, String> e : u.constDirectives().entrySet()) {
            Matcher m = Pattern.compile("(colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4]|shadowcolor\\d*)MipmapEnabled").matcher(e.getKey());
            if (m.matches() && e.getValue().trim().equals("true")) {
                String t = canonical(m.group(1));
                p.mipmapsBefore.add(t + " (" + c.readHalf(t) + ")");
                target(f, t).mipmapped = true;
            }
        }
        reads(dir, f, c, u, p, stage);
        String prefix = c.kind.equals("shadow") ? "shadowcolor" : "colortex";
        List<Integer> db = attachments(u, p);
        if (prog.equals("final")) {
            // final draws the frame's output (the swapchain through RQ-16's flipped present), not a colortex
            p.writes.add(Json.obj("location", 0, "target", "output", "half", "-"));
            if (db.size() > 1)
                p.notes.add("final names " + db.size() + " draw buffers: only location 0 reaches the output");
            p.blend = "off";
            f.passes.add(p);
            return;
        }
        Set<String> written = new LinkedHashSet<>();
        for (int i = 0; i < db.size(); i++) {
            String t = prefix + db.get(i);
            Target tg = target(f, t);
            tg.writers.add(p.id);
            tg.doubled = true;
            String half = c.readHalf(t).equals("main") ? "alt" : "main";
            p.writes.add(Json.obj("location", i, "target", t, "half", half));
            written.add(t);
            c.writtenThisFrame.add(t);
            for (Map<String, Object> r : p.reads)
                if (t.equals(r.get("resource")))
                    tg.readAndWrittenInOnePass = true;
        }
        // flips after the pass
        JsonObject flip = props.getAsJsonObject("flip");
        Set<String> toFlip = new LinkedHashSet<>(written);
        for (Map.Entry<String, JsonElement> e : flip.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(prog + "."))
                continue;
            String t = canonical(k.substring(prog.length() + 1));
            Target tg = target(f, t);
            if (e.getValue().getAsBoolean()) {
                toFlip.add(t);
                tg.doubled = true;
                tg.flipDirective = true;
            } else {
                toFlip.remove(t);
                if (written.contains(t))
                    tg.writtenByFlipFalse = true;
            }
        }
        for (String t : toFlip) {
            toggle(c, t);
            p.flipsAfter.add(t);
        }
        p.blend = blend(prog, db, prefix, "off");
        f.passes.add(p);
    }

    private String offReason(String dir, String prog) {
        PackData.Unit u = d.raster(dir, prog);
        if (u != null && u.json.has("offReason"))
            return u.json.get("offReason").getAsString();
        return "not compiled";
    }

    /**
     * Attachments of a raster program: its draw-buffer directive, truncated to 8 entries; without a directive, one
     * attachment per declared fragment output location (OptiFine's "first 8 attachments" default would attach
     * targets nothing writes; the outputs decide what is defined).
     */
    private List<Integer> attachments(PackData.Unit u, Pass p) {
        List<Integer> db = u.drawBuffers();
        int outs = u.fragmentOutputCount();
        List<Integer> res = new ArrayList<>();
        if (db == null) {
            for (int i = 0; i < Math.min(outs, 8); i++)
                res.add(i);
            p.notes.add("no DRAWBUFFERS/RENDERTARGETS: attachments = declared outputs 0.." + (outs - 1));
            return res;
        }
        for (int i = 0; i < Math.min(db.size(), 8); i++)
            res.add(db.get(i));
        if (db.size() > 8)
            p.notes.add("draw buffer list truncated to 8 entries");
        if (outs < res.size())
            p.notes.add("directive names " + res.size() + " buffers, the stage writes " + outs + ": the rest keep their content (undefined in GL)");
        return res;
    }

    private Object blend(String prog, List<Integer> db, String prefix, String def) {
        JsonObject b = props.getAsJsonObject("blend");
        Map<String, Object> per = new LinkedHashMap<>();
        Object all = b.has(prog) ? jsonValue(b.get(prog)) : def;
        boolean any = false;
        for (Integer i : db) {
            String t = prefix + i;
            Object v = all;
            for (Map.Entry<String, String> a : Formats.ALIASES.entrySet())
                if (a.getValue().equals(t) && b.has(prog + "." + a.getKey()))
                    v = jsonValue(b.get(prog + "." + a.getKey()));
            if (b.has(prog + "." + t)) {
                v = jsonValue(b.get(prog + "." + t));
                any = true;
            }
            per.put(t, v);
        }
        return any ? per : all;
    }

    private Object alphaTest(String prog) {
        JsonObject a = props.getAsJsonObject("alphaTest");
        return a.has(prog) ? jsonValue(a.get(prog)) : "vanilla";
    }

    static Object jsonValue(JsonElement e) {
        if (e.isJsonPrimitive())
            return e.getAsJsonPrimitive().isNumber() ? (Object) e.getAsDouble()
                    : e.getAsJsonPrimitive().isBoolean() ? (Object) e.getAsBoolean() : e.getAsString();
        if (e.isJsonArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonElement x : e.getAsJsonArray())
                l.add(jsonValue(x));
            return l;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet())
            m.put(x.getKey(), jsonValue(x.getValue()));
        return m;
    }

    private static String imageTarget(String image) {
        Matcher m = Pattern.compile("colorimg(\\d+)").matcher(image);
        if (m.matches())
            return "colortex" + m.group(1);
        m = Pattern.compile("shadowcolorimg(\\d+)").matcher(image);
        if (m.matches())
            return "shadowcolor" + m.group(1);
        return null;
    }

    private Map<String, Object> dispatch(PackData.Unit cu, Frame f) {
        int[] ls = cu.localSize();
        Map<String, String> cd = cu.constDirectives();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("localSize", ls == null ? null : List.of(ls[0], ls[1], ls[2]));
        if (cd.containsKey("workGroups")) {
            int[] v = ints(cd.get("workGroups"));
            m.put("groups", v == null ? cd.get("workGroups") : List.of(v[0], v[1], v.length > 2 ? v[2] : 1));
            m.put("rule", "workGroups (absolute)");
        } else {
            String r = cd.getOrDefault("workGroupsRender", "vec2(1.0, 1.0)");
            m.put("groups", "ceil(renderSize * " + r + " / localSize.xy), z = 1");
            m.put("rule", cd.containsKey("workGroupsRender") ? "workGroupsRender" : "default workGroupsRender = vec2(1.0, 1.0) (UNVERIFIED Iris default)");
        }
        if (ls == null)
            f.issues.add(new Issue(Severity.ERROR, cu.id, "compute program without layout(local_size_*) in"));
        return m;
    }

    static int[] ints(String s) {
        Matcher m = Pattern.compile("-?\\d+").matcher(s.replaceAll("^\\s*[iu]?vec\\d\\s*", ""));
        List<Integer> l = new ArrayList<>();
        while (m.find())
            l.add(Integer.parseInt(m.group()));
        if (l.isEmpty())
            return null;
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++)
            r[i] = l.get(i);
        return r;
    }

    // ---------------------------------------------------------------------------------------------- reads

    private void reads(String dir, Frame f, Chain c, PackData.Unit u, Pass p, String stage) {
        JsonObject textures = props.getAsJsonObject("textures");
        JsonObject custom = props.getAsJsonObject("customTextures");
        JsonObject images = props.getAsJsonObject("images");
        String texStage = stageOfTexture(stage);
        for (Map.Entry<String, String> s : u.sampled().entrySet()) {
            String name = s.getKey();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", name);
            r.put("type", s.getValue());
            String override = null;
            for (String key : new String[]{texStage + "." + name, texStage + "." + aliasOf(name)})
                if (textures.has(key))
                    override = textures.get(key).getAsString();
            String canon = canonical(name);
            if (override != null) {
                r.put("kind", "custom");
                r.put("resource", override);
            } else if (canon.matches("colortex\\d+") || canon.matches("shadowcolor\\d+")) {
                Target tg = target(f, canon);
                tg.readers.add(p.id);
                r.put("kind", canon.startsWith("shadow") ? "shadowcolor" : "colortex");
                r.put("resource", canon);
                boolean shadowTarget = canon.startsWith("shadow");
                if (!shadowTarget || c.kind.equals("shadow")) {
                    r.put("half", c.readHalf(canon));
                    if (!c.writtenThisFrame.contains(canon) && !tg.clear && !shadowTarget)
                        tg.historyRead = true;
                } else {
                    r.put("half", "main");
                }
            } else if (name.equals("depthtex0") || name.equals("gdepthtex")) {
                r.put("kind", "depth");
                r.put("resource", stage.startsWith("gbuffers") ? "main depth (attached: feedback, needs the depthtex1 copy or a read-only depth layout)" : "main depth");
                if (stage.equals("gbuffers-translucent") || stage.equals("gbuffers-solid"))
                    f.issues.add(new Issue(Severity.WARN, p.id, "geometry pass samples depthtex0 while main depth is attached: bind a copy (Iris semantics UNVERIFIED)"));
            } else if (name.equals("depthtex1")) {
                r.put("kind", "depth");
                r.put("resource", "depthtex1 capture");
            } else if (name.equals("depthtex2")) {
                r.put("kind", "depth");
                r.put("resource", "depthtex2 capture");
            } else if (name.matches("shadowtex0(HW)?|shadow")) {
                r.put("kind", "shadowdepth");
                r.put("resource", "shadowtex0");
            } else if (name.matches("shadowtex1(HW)?|watershadow")) {
                r.put("kind", "shadowdepth");
                r.put("resource", "shadowtex1");
            } else if (name.equals("noisetex")) {
                r.put("kind", "noise");
                r.put("resource", textures.has("noise") ? textures.get("noise").getAsString()
                        : "generated " + f.directives.getOrDefault("noiseTextureResolution", "256") + "^2");
            } else if (name.matches("gtexture|tex|texture|lightmap|normals|specular")) {
                r.put("kind", "geometry");
                r.put("resource", switch (name) {
                    case "lightmap" -> "lightmap";
                    case "normals" -> "atlas normals (LabPBR _n)";
                    case "specular" -> "atlas specular (LabPBR _s)";
                    default -> "draw texture (atlas / entity texture)";
                });
            } else if (custom.has(name)) {
                String v = custom.get(name).getAsString();
                r.put("kind", v.contains(":dynamic/") ? "dynamic" : "custom");
                r.put("resource", v);
            } else if (images.has(name) || imageSampler(images, name) != null) {
                r.put("kind", "image");
                r.put("resource", images.has(name) ? name : imageSampler(images, name));
            } else if (name.startsWith("vista") || name.startsWith("dh") || name.startsWith("vx")) {
                r.put("kind", "lod");
                r.put("resource", name + " (LOD contract)");
            } else if (imageTarget(name) != null) {
                r.put("kind", "colortex-image");
                r.put("resource", imageTarget(name));
            } else {
                r.put("kind", "unknown");
                JsonObject any = props.has("texturesAnyBranch") ? props.getAsJsonObject("texturesAnyBranch") : new JsonObject();
                if (any.has(name)) {
                    // a pack defect the reference shares: the declaration sits in a properties branch that the
                    // option macros did not take. The plan binds a 1x1 transparent black fallback.
                    r.put("resource", "fallback 1x1 vec4(0) (declared only in an untaken branch at " + any.get(name).getAsString() + ")");
                    f.issues.add(new Issue(Severity.WARN, p.id, "samples " + name + ": customTexture." + name + " is declared only inside a "
                            + "shaders.properties branch not taken with these options (" + any.get(name).getAsString()
                            + "); bound to a 1x1 fallback. Pack defect, same under Iris"));
                } else {
                    f.issues.add(new Issue(Severity.ERROR, p.id, "samples " + name + " (" + s.getValue()
                            + "): no target, custom texture, image or standard texture provides it"));
                }
            }
            p.reads.add(r);
        }
        // images a compute/raster stage uses must be created by the plan
        for (Map.Entry<String, String> im : u.images().entrySet())
            if (!images.has(im.getKey()) && imageTarget(im.getKey()) == null)
                f.issues.add(new Issue(Severity.ERROR, p.id, "uses image " + im.getKey() + " that no image.* directive creates"));
    }

    private static String imageSampler(JsonObject images, String sampler) {
        for (Map.Entry<String, JsonElement> e : images.entrySet())
            if (e.getValue().getAsJsonObject().get("sampler").getAsString().equals(sampler))
                return e.getKey();
        return null;
    }

    private static String aliasOf(String name) {
        for (Map.Entry<String, String> a : Formats.ALIASES.entrySet())
            if (a.getValue().equals(name))
                return a.getKey();
            else if (a.getKey().equals(name))
                return a.getValue();
        return name;
    }

    // ------------------------------------------------------------------------------------------- validation

    private void validate(String dir, Frame f) {
        for (Target t : f.targets.values()) {
            boolean shadow = t.name.startsWith("shadow");
            if (t.writers.isEmpty() && t.readers.isEmpty())
                continue; // declared only (a format for a target no served program uses)
            FormatCaps.Row row = FormatCaps.of(t.format);
            if (row == null) {
                f.issues.add(new Issue(Severity.ERROR, t.name, "format " + t.format + " is not a known pack format"));
                continue;
            }
            if (!t.writers.isEmpty()) {
                if (t.storage && row.storage() == FormatCaps.Support.NO)
                    f.issues.add(new Issue(Severity.ERROR, t.name, "written as a storage image but " + row.vk() + " has no STORAGE_IMAGE support"));
                boolean raster = t.writers.stream().anyMatch(w -> !w.endsWith(".csh"));
                if (raster && row.attach() == FormatCaps.Support.NO)
                    f.issues.add(new Issue(Severity.ERROR, t.name, row.vk() + " cannot be a colour attachment"));
                else if (raster && row.attach() == FormatCaps.Support.EXPECTED)
                    f.issues.add(new Issue(Severity.INFO, t.name, row.vk() + " as a colour attachment is optional in the spec: checked against the device at load (" + row.note() + ")"));
            }
            if (row.promoted())
                f.issues.add(new Issue(Severity.INFO, t.name, t.format + " allocated as " + row.vk() + (t.clearColor == null && !t.name.equals("colortex0") && !t.name.equals("colortex1") ? ", default clear opaque black" : "")));
            if (t.writers.isEmpty() && !t.readers.isEmpty())
                f.issues.add(new Issue(t.clear ? Severity.WARN : Severity.INFO, t.name,
                        "read by " + t.readers + " but written by no pass: reads " + (t.clear ? "its clear value" : "whatever it holds (never cleared, never written)")));
            if (!t.writers.isEmpty() && t.readers.isEmpty() && !t.endOfFrameCopy)
                f.issues.add(new Issue(Severity.INFO, t.name, "written by " + t.writers + " and read by no pass with these options (output unused)"));
            if (t.historyRead && !shadow)
                f.issues.add(new Issue(Severity.INFO, t.name, "read before its first write this frame and never cleared: carries the previous frame (history)"));
            if (t.size != null && !shadow)
                for (Pass p : f.passes)
                    if (p.writes.stream().anyMatch(w -> t.name.equals(w.get("target"))) && p.writes.stream().anyMatch(w -> {
                        Target o = f.targets.get((String) w.get("target"));
                        return o != null && !java.util.Objects.equals(o.size, t.size);
                    }))
                        f.issues.add(new Issue(Severity.WARN, p.id, "writes " + t.name + " (size " + t.size + ") together with a target of another size: render area = the smallest"));
        }
    }

    // ============================================================================================== dump

    public static Object toJson(Frame f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("folder", f.folder);
        m.put("dimensionIds", f.dimensionIds);
        m.put("drawnBy", f.drawnBy);
        List<Object> passes = new ArrayList<>();
        for (Pass p : f.passes)
            passes.add(p.toJson());
        m.put("passes", passes);
        Map<String, Object> targets = new LinkedHashMap<>();
        for (Target t : f.targets.values()) {
            if (t.writers.isEmpty() && t.readers.isEmpty())
                continue;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("format", t.format);
            FormatCaps.Row row = FormatCaps.of(t.format);
            x.put("vkFormat", row == null ? null : row.vk());
            x.put("formatSource", t.formatSource);
            x.put("clear", t.clear);
            x.put("clearColor", t.clearColor != null ? t.clearColor : defaultClear(t, row));
            x.put("size", t.size == null ? (t.name.startsWith("shadow") ? "shadowMapResolution" : "render") : t.size);
            x.put("doubled", t.doubled);
            x.put("pairNeeded", t.pairNeeded());
            x.put("mipmapped", t.mipmapped);
            x.put("storage", t.storage);
            x.put("endOfFrameCopy", t.endOfFrameCopy && t.pairNeeded());
            x.put("history", t.historyRead);
            x.put("writers", new ArrayList<>(t.writers));
            x.put("readers", new ArrayList<>(t.readers));
            targets.put(t.name, x);
        }
        m.put("targets", targets);
        Map<String, Object> v = new LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            List<String> l = new ArrayList<>();
            f.grouped(s).forEach((msg, ps) -> l.add(String.join(", ", ps) + ": " + msg));
            v.put(s.name().toLowerCase(), l);
        }
        m.put("validation", v);
        m.put("directives", f.directives);
        return m;
    }

    static String defaultClear(Target t, FormatCaps.Row row) {
        if (t.name.equals("colortex0"))
            return "fog colour, alpha 1";
        if (t.name.equals("colortex1"))
            return "vec4(1.0) (opaque white)";
        if (t.name.startsWith("shadowcolor"))
            return "vec4(1.0) (UNVERIFIED Iris shadowcolor default)";
        return row != null && row.promoted() ? "vec4(0,0,0,1) (promoted format)" : "vec4(0.0)";
    }

    /** A human-readable plan for review. */
    public static String toText(Frame f) {
        StringBuilder sb = new StringBuilder();
        sb.append("== folder ").append(f.folder).append("  dimensions ").append(f.dimensionIds).append('\n');
        int i = 0;
        for (Pass p : f.passes) {
            sb.append(String.format("%3d %-11s %-22s %-28s", i++, p.kind, p.stage, p.id));
            if (!p.geometry.isEmpty())
                sb.append(" geom=").append(p.geometry);
            if (p.depth != null && p.kind.equals("capture"))
                sb.append(' ').append(p.depth);
            sb.append('\n');
            if (!p.mipmapsBefore.isEmpty())
                sb.append("      mipmaps before: ").append(p.mipmapsBefore).append('\n');
            if (!p.writes.isEmpty()) {
                sb.append("      writes:");
                for (Map<String, Object> w : p.writes)
                    sb.append(' ').append(w.get("target")).append('/').append(w.get("half"));
                sb.append('\n');
            }
            if (!p.reads.isEmpty()) {
                sb.append("      reads: ");
                for (Map<String, Object> r : p.reads)
                    sb.append(' ').append(r.get("name")).append(r.containsKey("half") ? "/" + r.get("half") : "")
                      .append(r.get("kind").equals("colortex") || r.get("kind").equals("shadowcolor") ? "" : "[" + r.get("kind") + "]");
                sb.append('\n');
            }
            if (!p.flipsAfter.isEmpty())
                sb.append("      flips after: ").append(p.flipsAfter).append('\n');
            if (p.scale != null)
                sb.append("      scale: ").append(p.scale).append('\n');
            if (p.blend != null && !"off".equals(p.blend) && !"vanilla".equals(p.blend))
                sb.append("      blend: ").append(p.blend).append('\n');
            if (p.alphaTest != null && !"vanilla".equals(p.alphaTest))
                sb.append("      alphaTest: ").append(p.alphaTest).append('\n');
            if (p.dispatch != null)
                sb.append("      dispatch: ").append(p.dispatch).append('\n');
            for (String n : p.notes)
                sb.append("      note: ").append(n).append('\n');
        }
        sb.append("-- targets\n");
        for (Target t : f.targets.values()) {
            if (t.writers.isEmpty() && t.readers.isEmpty())
                continue;
            FormatCaps.Row row = FormatCaps.of(t.format);
            sb.append(String.format("  %-13s %-16s -> %-26s clear=%-5s size=%-14s %s%s%s%s%n", t.name, t.format,
                    row == null ? "?" : row.vk(), t.clear, t.size == null ? (t.name.startsWith("shadow") ? "shadowMap" : "render") : t.size,
                    t.pairNeeded() ? "PAIR " : t.doubled ? "single(elided pair) " : "single ", t.mipmapped ? "mips " : "",
                    t.storage ? "storage " : "", t.historyRead ? "history" : ""));
        }
        sb.append("-- validation: ").append(f.count(Severity.ERROR)).append(" errors, ").append(f.count(Severity.WARN))
          .append(" warnings, ").append(f.count(Severity.INFO)).append(" info\n");
        for (Severity sev : new Severity[]{Severity.ERROR, Severity.WARN})
            f.grouped(sev).forEach((msg, ps) -> sb.append("  ").append(sev).append(' ').append(String.join(", ", ps)).append(": ").append(msg).append('\n'));
        return sb.toString();
    }
}
