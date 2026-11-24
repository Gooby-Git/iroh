package dev.elemore.iroh.packtest;

import dev.elemore.iroh.render.PassResume;
import dev.elemore.iroh.shaderpack.FrontEnd;
import dev.elemore.iroh.shaderpack.PackSource;
import dev.elemore.iroh.shaderpack.StandardMacros;
import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PackPlan;
import dev.elemore.iroh.shaderpack.runtime.FrameExecutor;
import dev.elemore.iroh.shaderpack.runtime.GpuBackend;
import dev.elemore.iroh.shaderpack.runtime.PackFrameInputs;
import dev.elemore.iroh.shaderpack.runtime.RecordingBackend;
import dev.elemore.iroh.shaderpack.runtime.Substitution;
import dev.elemore.iroh.shaderpack.runtime.SyntheticFullScene;
import dev.elemore.iroh.shaderpack.runtime.WorldPass;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P3-11 (S) world pass on the fake device: the whole new frame order with substituted draws going through the
 * renderer's bind decision path ({@link WorldPass#onBind} / {@link WorldPass#beforeDraw}: exactly what
 * Renderer.bindGraphicsPipeline / uploadAndBindUBOs call through SubstitutionHook), interleaved with raster draws and
 * foreign recordings (endPassRunResume). Checks: render pass begin/end balance (Iroh's and the world pass's), the
 * attachment set of every substituted pass = the program's DRAWBUFFERS halves + depthtex0, no transfer/clear/dispatch
 * inside open attachments, barriers (no hazard on the recording backend), Iroh's bound-pipeline cache and dynamic
 * state invalidated at every switch back from a pack pipeline, the frame order (sky, terrain, entities, held depth
 * captures, deferred, translucent programs on the alt halves, composite, final, raster-over, present), degraded mode
 * (a refused permutation fails the world pass with no exception and raster continues), and that the segmented frame
 * records the same executor work as the monolithic frame().
 * {@code ./gradlew worldPassTest}; last line {@code # worldpass PASS n/n}.
 */
public final class WorldPassTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    /** Iroh's renderer as seen by the world pass: one main pass, a bound-pipeline cache, dynamic state validity */
    static final class FakeIroh implements WorldPass.Host {
        final List<String> log = new ArrayList<>();
        boolean bound = true;
        long cachedPipeline;
        boolean dynValid = true;
        int begins = 1, ends;

        public boolean irohPassBound() { return bound; }
        public void endIrohPass() { if (!bound) log.add("BAD end unbound"); bound = false; ends++; log.add("iroh end"); }
        public void resumeIrohPass() { if (bound) log.add("BAD resume bound"); bound = true; begins++; log.add("iroh resume"); }
        public void invalidateBoundState() { cachedPipeline = 0; log.add("invalidate"); }
        public void reapplyDynamicState() { dynValid = true; log.add("reapply"); }

        /** a vanilla bind that was NOT substituted: Iroh binds its own pipeline (skipped when cached) */
        void rasterBind(long handle, RecordingBackend rec) {
            if (!bound)
                log.add("BAD raster bind with no Iroh pass");
            if (!dynValid)
                log.add("BAD raster bind with stale dynamic state");
            if (cachedPipeline != handle) {
                cachedPipeline = handle;
                rec.calls.add("iroh bind " + handle);
            }
        }

        /** VulkanImage.endPassRunResume: closes the world pass first (hook), then PassResume */
        void outside(WorldPass w, Runnable r) {
            w.closeForForeign();
            PassResume.run(new PassResume.Hooks() {
                public boolean passBound() { return bound; }
                public void endPass() { endIrohPass(); }
                public void resumePass() { resumeIrohPass(); }
                public void reapplyViewportAndScissor() { FakeIroh.this.invalidateBoundState(); FakeIroh.this.reapplyDynamicState(); }
            }, new dev.elemore.iroh.render.CmdState(new dev.elemore.iroh.render.CmdState.Backend() {
                public void bindPipeline(long h) {}
                public void depthBias(float c, float s) {}
                public void lineWidth(float w) {}
            }), r); // int-2b: the dynamic-state rules themselves are tested by passResumeTest
        }
    }

    static final class FakeSource implements WorldPass.DrawSource {
        final RecordingBackend rec;
        Object tex = "entity-atlas";
        int imports;

        FakeSource(RecordingBackend rec) { this.rec = rec; }

        public void modelView(double[] out) { for (int i = 0; i < 16; i++) out[i] = i % 5 == 0 ? 1 : 0; }
        public void projection(double[] out) { for (int i = 0; i < 16; i++) out[i] = i % 5 == 0 ? 1 : 0; out[14] = -0.1; }
        public Object texture() { return tex; }
        public long textureKey(Object t) { return t.hashCode(); }
        public int importTexture(Object t) { imports++; return rec.importImage(t, "draw texture " + t); }
        public void ids(Map<String, double[]> out) { out.get("entityId")[0] = 7; }
    }

    /** the game's inputs: no batches at START, the terrain lists from BEFORE_ENTITIES on */
    static final class Phased implements PackFrameInputs {
        final SyntheticFullScene s;
        List<Geometry> geo = List.of();
        long version;

        Phased(SyntheticFullScene s) { this.s = s; }

        public int width() { return s.width(); }
        public int height() { return s.height(); }
        public Map<String, double[]> uniforms() { return s.uniforms(); }
        public Set<String> suppliedImages() { return s.suppliedImages(); }
        public float[] image(String name) { return s.image(name); }
        public int[] extent(String name) { return s.extent(name); }
        public String startStage() { return s.startStage(); }
        public double frameSeconds() { return s.frameSeconds(); }
        public List<Geometry> geometry() { return geo; }
        public long geometryVersion() { return version; }
        public Set<String> geometryTextures() { return s.geometryTextures(); }
        public Map<String, Integer> geometryImages() { return s.geometryImages(); }
        public int depthTo() { return s.depthTo(); }
        public Map<String, Integer> copySources() { return s.copySources(); }
        public Map<String, float[]> clearValues() { return s.clearValues(); }
    }

    static final List<String> NEW_ENTITY = List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal");
    static final List<String> PARTICLE = List.of("Position", "UV0", "Color", "UV2");
    static final List<String> POS = List.of("Position");
    static final List<String> POS_COLOR_NORMAL = List.of("Position", "Color", "Normal");
    static final List<String> BLOCK = List.of("Position", "Color", "UV0", "UV2", "Normal");

    static boolean bind(WorldPass w, Object key, String shader, List<String> el, boolean depthWrite, boolean blend) {
        // translucent blend = SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA (VK 6, 7, 1, 7)
        return w.onBind(key, shader, el, WorldPass.TRIANGLE_LIST, true, depthWrite, false, blend, 6, 7, 1, 7, true);
    }

    static int idx(List<String> calls, String prefix) {
        for (int i = 0; i < calls.size(); i++)
            if (calls.get(i).startsWith(prefix))
                return i;
        return -1;
    }

    static int idxFrom(List<String> calls, String prefix, int from) {
        for (int i = Math.max(0, from); i < calls.size(); i++)
            if (calls.get(i).startsWith(prefix))
                return i;
        return -1;
    }

    static int count(List<String> calls, String prefix) {
        int n = 0;
        for (String c : calls)
            if (c.startsWith(prefix))
                n++;
        return n;
    }

    public static void main(String[] args) throws Exception {
        check("hook off by default (one volatile read per bind and per draw)", Substitution.HOOK == 0);
        Path pack = Paths.get(System.getProperty("user.home"), ".local/share/atlauncher/instances/Vulkan/shaderpacks/Navidson");
        if (!Files.isDirectory(pack)) {
            System.out.println("# worldpass SKIP (no Navidson pack on this machine)");
            return;
        }
        Path opt = pack.resolveSibling("Navidson.txt");
        String saved = Files.isRegularFile(opt) ? Files.readString(opt, StandardCharsets.UTF_8) : null;
        Path dump = Paths.get("build/worldPass/Navidson");
        PackHarness.deleteTree(dump);
        FrontEnd.run(PackSource.open(pack), new FrontEnd.Config(StandardMacros.Env.defaults(), saved, true, dump));
        PackData data = PackData.load(dump);
        PackPlan plan = PackPlan.build(data);
        String folder = "world0";
        SyntheticFullScene scene = new SyntheticFullScene(data, plan.frames.get(folder), plan.resources.get(folder), 160, 90);
        List<PackFrameInputs.Geometry> terrain = scene.geometry();

        // ---------------------------------------------------------------- the whole frame, twice (second = steady state)
        RecordingBackend rec = new RecordingBackend();
        FrameExecutor ex = new FrameExecutor(data, plan, folder, rec, FrameExecutor.cap(1L << 30));
        int sceneColour = rec.external(new GpuBackend.ImageDesc("scene colour", "R8G8B8A8_UNORM", 160, 90, 1, 1, Set.of("COLOR_ATTACHMENT", "TRANSFER_DST", "TRANSFER_SRC")));
        int sceneDepth = rec.external(new GpuBackend.ImageDesc("scene depth", "D32_SFLOAT", 160, 90, 1, 1, Set.of("TRANSFER_DST", "TRANSFER_SRC")));
        ex.presentTo = sceneColour;
        ex.rasterOver = sceneColour;
        FakeIroh iroh = new FakeIroh();
        FakeSource src = new FakeSource(rec);
        WorldPass w = new WorldPass(ex, rec, iroh, src, Set.of("skipmod"), true);
        Phased in = new Phased(scene);
        Object skyKey = new Object(), guiKey = new Object(), entKey = new Object(), entSolidKey = new Object(), crumbKey = new Object(),
                modKey = new Object(), skipKey = new Object(), partKey = new Object(), entTrKey = new Object(), lineKey = new Object(),
                weirdKey = new Object(), weatherKey = new Object();
        int firstFrameStart = 0;
        iroh.outside(w, () -> w.begin(in)); // the game warms after the first begin of an executor
        int warmed = w.warm();
        iroh.outside(w, () -> w.end(in));
        check("warm-up compiled the common permutations (" + warmed + ")", warmed >= 10 && rec.violations.isEmpty());
        int afterWarm = rec.calls.size();
        for (int frame = 0; frame < 2; frame++) {
            int startCall = rec.calls.size();
            if (frame == 1)
                firstFrameStart = startCall;
            in.geo = List.of();
            in.version++;
            iroh.outside(w, () -> w.begin(in));
            check(frame + ": phase OPAQUE after begin", w.phase() == WorldPass.Phase.OPAQUE);
            // sky: position shader -> gbuffers_skybasic into colortex0
            check(frame + ": sky substituted", bind(w, skyKey, "position", POS, false, false));
            check(frame + ": sky draw bound", w.beforeDraw());
            check(frame + ": iroh pass ended for the world pass", !iroh.bound);
            // a raster draw inside the world (GUI-like shader in world) -> back to Iroh's pass, state invalidated
            iroh.log.clear();
            boolean g = bind(w, guiKey, "rendertype_gui", POS, false, false);
            check(frame + ": gui shader stays raster", !g);
            check(frame + ": switch back resumes Iroh + invalidates + reapplies " + iroh.log, iroh.log.equals(List.of("iroh resume", "invalidate", "reapply")));
            iroh.rasterBind(11, rec);
            check(frame + ": uploadAndBindUBOs after a raster bind is vanilla", !w.beforeDraw());
            iroh.outside(w, () -> {
                w.afterSky();
                ex.blitIn(sceneColour, "colortex0"); // (game: only when the sky was raster)
                ex.clearExternal(sceneColour, new float[4]);
            });
            // terrain arrives
            in.geo = terrain;
            in.version++;
            iroh.outside(w, () -> {
                w.opaque(in);
                ex.blitOut("depthtex0", sceneDepth);
            });
            int afterTerrain = rec.calls.size();
            // entities: two draws one bind; a block entity with the same class: no switch
            check(frame + ": entity substituted", bind(w, entKey, "rendertype_entity_cutout_no_cull", NEW_ENTITY, true, false));
            check(frame + ": entity draw 1", w.beforeDraw());
            check(frame + ": entity draw 2 (same bind)", w.beforeDraw());
            int begins = count(rec.calls.subList(afterTerrain, rec.calls.size()), "beginAttachments");
            check(frame + ": entity solid same class", bind(w, entSolidKey, "rendertype_entity_solid", NEW_ENTITY, true, false) && w.beforeDraw());
            check(frame + ": same DRAWBUFFERS set: no attachment switch", count(rec.calls.subList(afterTerrain, rec.calls.size()), "beginAttachments") == begins);
            // a foreign recording between a bind and its draw (in-frame texture upload flush): reopened at the draw
            check(frame + ": entity bind before foreign", bind(w, entKey, "rendertype_entity_cutout_no_cull", NEW_ENTITY, true, false));
            iroh.outside(w, () -> rec.calls.add("foreign recording"));
            check(frame + ": attachments closed during the foreign recording", rec.calls.get(rec.calls.indexOf("foreign recording") - 1).startsWith("endAttachments")
                    || !rec.calls.get(rec.calls.lastIndexOf("foreign recording") - 1).startsWith("bindExternalDraw"));
            check(frame + ": draw after foreign reopens", w.beforeDraw() && w.attachmentsOpen());
            // crumbling: another program (other DRAWBUFFERS) -> one switch, no Iroh pass in between
            iroh.log.clear();
            check(frame + ": crumbling substituted", bind(w, crumbKey, "rendertype_crumbling", BLOCK, false, true) && w.beforeDraw());
            check(frame + ": program switch does not touch Iroh's pass " + iroh.log, iroh.log.isEmpty());
            // unplaceable / non-triangle / deny-listed draws stay raster
            check(frame + ": unplaceable format raster", !bind(w, weirdKey, "mymod:fancy", List.of("Position", "Weird"), true, false));
            iroh.rasterBind(12, rec);
            check(frame + ": line-list topology raster", !w.onBind(lineKey, "rendertype_lines", POS_COLOR_NORMAL, 1, true, true, false, false, 0, 0, 0, 0, true));
            check(frame + ": raster.mods namespace raster", !bind(w, skipKey, "skipmod:glow", NEW_ENTITY, true, false));
            check(frame + ": mod shader mapped by vertex format", bind(w, modKey, "othermod:mob", NEW_ENTITY, true, false) && w.beforeDraw());
            check(frame + ": outside the world pass raster", !w.onBind(entKey, "rendertype_entity_solid", NEW_ENTITY, WorldPass.TRIANGLE_LIST, true, true, false, false, 0, 0, 0, 0, false));
            iroh.rasterBind(13, rec);
            // translucent terrain layer: deferred + water; later draws use translucent programs
            int beforeTr = rec.calls.size();
            iroh.outside(w, () -> w.translucent(in));
            check(frame + ": phase TRANSLUCENT", w.phase() == WorldPass.Phase.TRANSLUCENT);
            check(frame + ": particle substituted after deferred", bind(w, partKey, "particle", PARTICLE, false, true) && w.beforeDraw());
            check(frame + ": translucent entity substituted", bind(w, entTrKey, "rendertype_entity_translucent", NEW_ENTITY, true, true) && w.beforeDraw());
            check(frame + ": opaque-only class after deferred stays raster (outline)", !bind(w, lineKey, "rendertype_lines", POS_COLOR_NORMAL, true, false));
            iroh.rasterBind(14, rec);
            check(frame + ": entity after deferred -> translucent program", bind(w, entKey, "rendertype_entity_cutout_no_cull", NEW_ENTITY, true, false) && w.beforeDraw());
            int wb0 = rec.calls.size();
            check(frame + ": rain (particle shader inside renderSnowAndRain) -> gbuffers_weather", bind(w, weatherKey, "iroh:weather", PARTICLE, false, true) && w.beforeDraw()
                    && idxFrom(rec.calls, "beginAttachments gbuffers_weather:translucent [pack:colortex12", wb0) >= 0);
            iroh.outside(w, () -> w.end(in));
            check(frame + ": phase OFF after end", w.phase() == WorldPass.Phase.OFF);
            check(frame + ": attachments closed at frame end", !w.attachmentsOpen() && rec.openAttachments == null);
            check(frame + ": Iroh's pass bound again at frame end", iroh.bound);
            check(frame + ": no world-pass failure " + w.failure(), w.failure() == null);
            check(frame + ": no bad host transitions " + iroh.log, iroh.log.stream().noneMatch(x -> x.startsWith("BAD")));

            List<String> c = rec.calls.subList(startCall, rec.calls.size());
            check(frame + ": attachment begin/end balanced", count(c, "beginAttachments") == count(c, "endAttachments"));
            int sky = idx(c, "beginAttachments gbuffers_skybasic"), terrainDraw = idx(c, "geometry gbuffers_terrain"),
                    ents = idx(c, "beginAttachments gbuffers_entities "), crumb = idx(c, "beginAttachments gbuffers_damagedblock"),
                    capture = idx(c, "copy pack:depthtex0 -> pack:depthtex1"), deferred = idx(c, "draw deferred"),
                    water = idx(c, "geometry gbuffers_water:translucent"), part = idx(c, "beginAttachments gbuffers_textured:translucent"),
                    entTr = idx(c, "beginAttachments gbuffers_entities_translucent:translucent"), comp = idx(c, "draw composite"),
                    fin = idx(c, "draw final"), over = idx(c, "draw engine:over"), present = c.lastIndexOf("blit pack:output -> scene colour");
            check(frame + ": order sky < terrain < entities < crumbling < depth capture < deferred < water < particles/translucent entities < composite < final < over < present: "
                            + List.of(sky, terrainDraw, ents, crumb, capture, deferred, water, part, entTr, comp, fin, over, present),
                    sky >= 0 && sky < terrainDraw && terrainDraw < ents && ents < crumb && crumb < capture && capture < deferred && deferred < water
                            && water < part && part < entTr && entTr < comp && comp < fin && fin < over && over < present);
            check(frame + ": entity attachments = gbuffers_entities DRAWBUFFERS (main halves) + depthtex0: " + (ents >= 0 ? c.get(ents) : "-"),
                    ents >= 0 && c.get(ents).contains("[pack:colortex0#main,pack:colortex3#main,pack:colortex6,pack:colortex4] + pack:depthtex0"));
            check(frame + ": translucent entity attachments on the alt half of colortex0: " + (entTr >= 0 ? c.get(entTr) : "-"),
                    entTr >= 0 && c.get(entTr).contains("pack:colortex0#alt") && c.get(entTr).contains("+ pack:depthtex0"));
            check(frame + ": sky attachments colortex0 main: " + (sky >= 0 ? c.get(sky) : "-"), sky >= 0 && c.get(sky).contains("[pack:colortex0#main] + pack:depthtex0"));
            check(frame + ": every bindExternalDraw inside its own pass (no violations) " + rec.violations, rec.violations.isEmpty());
            check(frame + ": 11 substituted draws (" + w.substitutedDraws + ")", w.substitutedDraws == 11);
            check(frame + ": scene colour cleared transparent before entities", idx(c, "clear scene colour") >= 0 && idx(c, "clear scene colour") < ents);
            check(frame + ": depth back before entities", idx(c, "blit pack:depthtex0 -> scene depth") >= 0 && idx(c, "blit pack:depthtex0 -> scene depth") < ents);
            check(frame + ": one beginFrame / endFrame", count(c, "beginFrame") == 1 && count(c, "endFrame") == 1 && idx(c, "beginFrame") < idx(c, "endFrame"));
            check(frame + ": executor problems " + ex.problems, ex.problems.isEmpty());
        }
        check("warmed entity/particle/sky types compiled nothing at first sight (only crumbling-with-depth-write variants etc. may): "
                + count(rec.calls.subList(afterWarm, firstFrameStart), "createPipeline"), count(rec.calls.subList(afterWarm, firstFrameStart), "createPipeline") <= 3);
        check("permutations cached across frames (" + ex.substitutedPipelines() + ")",
                count(rec.calls.subList(firstFrameStart, rec.calls.size()), "createPipeline") == 0);
        check("texture imported once", src.imports == 1);
        check("Iroh begin/end balanced overall (" + iroh.begins + "/" + iroh.ends + ")", iroh.begins == iroh.ends + 1 && iroh.bound);
        System.out.println("# " + w.report());

        // ---------------------------------------------------------------- segmented == monolithic (no substitution)
        RecordingBackend recA = new RecordingBackend(), recB = new RecordingBackend();
        FrameExecutor exA = new FrameExecutor(data, plan, folder, recA, FrameExecutor.cap(1L << 30));
        FrameExecutor exB = new FrameExecutor(data, plan, folder, recB, FrameExecutor.cap(1L << 30));
        exA.expectGeometry = exB.expectGeometry = true;
        Phased inA = new Phased(scene), inB = new Phased(scene);
        inA.geo = terrain;
        inB.geo = terrain;
        FakeIroh irohB = new FakeIroh();
        WorldPass wb = new WorldPass(exB, recB, irohB, new FakeSource(recB), Set.of(), false);
        for (int f = 0; f < 2; f++) {
            exA.frame(inA);
            wb.begin(inB);
            check("no substitution: binds stay raster", !bind(wb, entKey, "rendertype_entity_solid", NEW_ENTITY, true, false));
            wb.opaque(inB);
            wb.translucent(inB);
            wb.end(inB);
        }
        List<String> a = work(recA.calls), b = work(recB.calls);
        check("segmented frame records the same passes in the same order as frame() (" + a.size() + " vs " + b.size() + ")", a.equals(b));
        if (!a.equals(b))
            for (int i = 0; i < Math.min(a.size(), b.size()); i++)
                if (!a.get(i).equals(b.get(i))) {
                    System.out.println("  first difference at " + i + ": " + a.get(i) + " | " + b.get(i));
                    break;
                }
        check("segmented: no violations " + recB.violations, recB.violations.isEmpty());

        // ---------------------------------------------------------------- degraded mode: a refused permutation
        RecordingBackend recF = new RecordingBackend();
        FrameExecutor exF = new FrameExecutor(data, plan, folder, recF, FrameExecutor.cap(1L << 30));
        FakeIroh irohF = new FakeIroh();
        WorldPass wf = new WorldPass(exF, recF, irohF, new FakeSource(recF), Set.of(), true);
        Phased inF = new Phased(scene);
        inF.geo = terrain;
        irohF.outside(wf, () -> wf.begin(inF));
        recF.failPipeline = id -> id.contains("[vanilla:");
        boolean threw = false, sub = true;
        try {
            sub = bind(wf, entKey, "rendertype_entity_solid", NEW_ENTITY, true, false);
        } catch (RuntimeException e) {
            threw = true;
        }
        check("refused permutation: no exception", !threw);
        check("refused permutation: draw stays raster", !sub && !wf.beforeDraw());
        check("refused permutation: world pass failed with a reason (" + wf.failure() + ")", wf.failure() != null);
        check("after failure every bind is raster", !bind(wf, skyKey, "position", POS, false, false));
        irohF.outside(wf, () -> wf.end(inF));
        check("failed world pass still ends its frame balanced", recF.openAttachments == null && irohF.bound && recF.violations.isEmpty());

        System.out.println("# worldpass " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }

    /** the executor's own work (passes, captures, copies), without the world-pass extras and frame bookkeeping */
    static List<String> work(List<String> calls) {
        List<String> out = new ArrayList<>();
        for (String c : calls)
            if (c.startsWith("draw ") || c.startsWith("geometry ") || c.startsWith("dispatch ") || c.startsWith("copy ") || c.startsWith("mips "))
                if (!c.startsWith("draw engine:over"))
                    out.add(c);
        return out;
    }
}
