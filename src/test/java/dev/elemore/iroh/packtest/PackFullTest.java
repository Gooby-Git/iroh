package dev.elemore.iroh.packtest;

import dev.elemore.iroh.shaderpack.FrontEnd;
import dev.elemore.iroh.shaderpack.PackSource;
import dev.elemore.iroh.shaderpack.StandardMacros;
import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PackPlan;
import dev.elemore.iroh.shaderpack.runtime.FrameExecutor;
import dev.elemore.iroh.shaderpack.runtime.Mat;
import dev.elemore.iroh.shaderpack.runtime.PackIdMaps;
import dev.elemore.iroh.shaderpack.runtime.RecordingBackend;
import dev.elemore.iroh.shaderpack.runtime.SyntheticFullScene;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * P3-5 FULL mode on the recording backend: Navidson's whole frame from "begin" with real geometry (shadow pass,
 * gbuffers_terrain, gbuffers_water, depth captures, deferred, composites, final). Checks order (shadow before its
 * capture before terrain, depthtex1 captured before the translucent pass, water after deferred), synchronisation (no
 * violation), depth attachments, vertex buffers freed on close, id maps, matrix helpers.
 * {@code ./gradlew packFullTest}; last line {@code # packfull PASS n/n}.
 */
public final class PackFullTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    public static void main(String[] args) throws Exception {
        // matrix helpers
        double[] m = Mat.mul(Mat.translate(1, 2, 3), Mat.ortho(-2, 2, -1, 1, 0.1, 10));
        double[] id = Mat.mul(m, Mat.invert(m));
        boolean ident = true;
        for (int i = 0; i < 16; i++)
            ident &= Math.abs(id[i] - (i % 5 == 0 ? 1 : 0)) < 1e-9;
        check("invert", ident);
        double[][] sh = Mat.shadow(new double[]{-0.45, 0.75, 0.35}, new double[]{100.3, 70, -20.7}, 96, 2);
        double[] centre = {-(100.3 - 100), -(70 - 70), -(-20.7 + 22)};
        double[] p = Mat.mul(sh[0], new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, centre[0], centre[1], centre[2], 1});
        check("shadow camera: snapped centre on the view axis, 128 in front: " + p[12] + "," + p[13] + "," + p[14],
                Math.abs(p[12]) < 1e-6 && Math.abs(p[13]) < 1e-6 && Math.abs(p[14] + 128) < 1e-6);

        Path pack = Paths.get(System.getProperty("user.home"), ".local/share/atlauncher/instances/Vulkan/shaderpacks/Navidson");
        if (!Files.isDirectory(pack)) {
            System.out.println("# packfull SKIP (no Navidson pack on this machine)");
            return;
        }
        Path opt = pack.resolveSibling("Navidson.txt");
        String saved = Files.isRegularFile(opt) ? Files.readString(opt, StandardCharsets.UTF_8) : null;
        Path dump = Paths.get("build/packFull/Navidson");
        PackHarness.deleteTree(dump);
        FrontEnd.run(PackSource.open(pack), new FrontEnd.Config(StandardMacros.Env.defaults(), saved, true, dump));
        PackData data = PackData.load(dump);
        PackPlan plan = PackPlan.build(data);
        PackIdMaps ids = PackIdMaps.of(data);
        check("id maps: water, grass, glowstone listed (" + ids.size("block") + " blocks)",
                ids.block("minecraft:water") > 0 && ids.block("minecraft:grass_block") > 0 && ids.block("minecraft:glowstone") > 0);
        check("id maps: unknown block = -1", ids.block("minecraft:not_a_block") == -1);

        for (String folder : List.of("world0", "world-1")) {
            if (!plan.frames.containsKey(folder))
                continue;
            RecordingBackend rec = new RecordingBackend();
            SyntheticFullScene in = new SyntheticFullScene(data, plan.frames.get(folder), plan.resources.get(folder), 160, 90);
            FrameExecutor ex = new FrameExecutor(data, plan, folder, rec, FrameExecutor.cap(1L << 30));
            ex.frame(in);
            ex.frame(in);
            check(folder + ": no problems " + ex.problems, ex.problems.isEmpty());
            if (!rec.violations.isEmpty() && Boolean.getBoolean("debug")) rec.calls.stream().filter(x -> x.contains("floodfill_img ") || x.contains("floodfill_img\n") || x.endsWith("floodfill_img") || x.startsWith("dispatch") || x.startsWith("geometry")).limit(40).forEach(System.out::println);
            check(folder + ": no violations " + rec.violations, rec.violations.isEmpty());
            List<String> c = rec.calls;
            int terrain = idx(c, "geometry gbuffers_terrain "), water = idx(c, "geometry gbuffers_water:translucent "),
                    deferred1 = idx(c, "draw deferred1"), fin = idx(c, "draw final");
            check(folder + ": terrain drawn", terrain >= 0);
            check(folder + ": water after deferred1, final last", water > deferred1 && deferred1 > terrain && fin > water);
            int shadow = idx(c, "geometry shadow:shadow ");
            if (plan.frames.get(folder).passes.stream().anyMatch(x -> x.id.startsWith("shadow:")))
                check(folder + ": shadow pass before terrain", shadow >= 0 && shadow < terrain);
            check(folder + ": geometry passes " + rec.geoDraws.size() / 2 + " per frame", rec.geoDraws.size() >= 4);
            check(folder + ": every geometry draw has a depth attachment", rec.geoDraws.stream().allMatch(g -> g.depth() >= 0));
            String trace = String.join("\n", ex.trace);
            check(folder + ": depthtex1 captured between terrain and water", trace.indexOf("capture depthtex1") > trace.indexOf("geometry gbuffers_terrain")
                    && trace.indexOf("capture depthtex1") < trace.indexOf("geometry gbuffers_water"));
            check(folder + ": vertex buffers created once (" + rec.liveVertexBuffers() + ")", rec.liveVertexBuffers() == in.geometry().size());
            ex.close();
            check(folder + ": close frees images, pipelines, vertex buffers", rec.live() == 0 && rec.livePipelines() == 0 && rec.liveVertexBuffers() == 0);
        }
        // P3-6 in-game shape: indirect batches over renderer-owned buffers, a separate shadow list, depth back
        if (plan.frames.containsKey("world0")) {
            RecordingBackend rec = new RecordingBackend();
            SyntheticFullScene syn = new SyntheticFullScene(data, plan.frames.get("world0"), plan.resources.get("world0"), 160, 90);
            int sceneDepth = rec.importImage(null, "scene depth");
            int atlas = rec.importImage(null, "block atlas");
            var ind = new dev.elemore.iroh.shaderpack.runtime.PackFrameInputs.Indirect(11, 12, 0, 13, 64, 7, 32);
            java.util.List<dev.elemore.iroh.shaderpack.runtime.PackFrameInputs.Geometry> geo = List.of(
                    new dev.elemore.iroh.shaderpack.runtime.PackFrameInputs.Geometry("terrain_cutout_mipped", "wide-terrain", null, 0, new double[]{-8, -64, 3}, ind),
                    new dev.elemore.iroh.shaderpack.runtime.PackFrameInputs.Geometry("terrain_translucent", "wide-terrain", null, 0, new double[]{-8, -64, 3}, ind),
                    new dev.elemore.iroh.shaderpack.runtime.PackFrameInputs.Geometry("shadow:terrain_cutout_mipped", "wide-terrain", null, 0, new double[]{-8, -64, 3}, ind));
            var in = new dev.elemore.iroh.shaderpack.runtime.PackFrameInputs() {
                public int width() { return syn.width(); }
                public int height() { return syn.height(); }
                public java.util.Map<String, double[]> uniforms() { return syn.uniforms(); }
                public java.util.Set<String> suppliedImages() { return java.util.Set.of(); }
                public float[] image(String n) { return null; }
                public int[] extent(String n) { return null; }
                public String startStage() { return "begin"; }
                public double frameSeconds() { return 1 / 60.0; }
                public List<Geometry> geometry() { return geo; }
                public java.util.Map<String, Integer> geometryImages() { return java.util.Map.of("geo:tex", atlas); }
                public int depthTo() { return sceneDepth; }
            };
            FrameExecutor ex = new FrameExecutor(data, plan, "world0", rec, FrameExecutor.cap(1L << 30));
            ex.frame(in);
            check("indirect: no problems " + ex.problems, ex.problems.isEmpty());
            check("indirect: no violations " + rec.violations, rec.violations.isEmpty());
            var shadowDraw = rec.geoDraws.stream().filter(g -> g.pass().equals("shadow:shadow")).findFirst();
            var terrainDraw = rec.geoDraws.stream().filter(g -> g.pass().equals("gbuffers_terrain")).findFirst();
            check("shadow pass draws only the shadow list (1 batch)", shadowDraw.isPresent() && shadowDraw.get().batches().size() == 1);
            check("terrain pass draws the main list (1 batch), indirect kept", terrainDraw.isPresent() && terrainDraw.get().batches().size() == 1
                    && terrainDraw.get().batches().get(0).indirect() == ind);
            check("water pass draws the translucent batch", rec.geoDraws.stream().anyMatch(g -> g.pass().equals("gbuffers_water:translucent")));
            check("the atlas is the imported image", terrainDraw.isPresent() && terrainDraw.get().set0().values().stream().anyMatch(b -> b.image() == atlas));
            int db = idx(rec.calls, "blit pack:depthtex0 -> scene depth");
            check("depth back to the scene depth after the chain", db > idx(rec.calls, "draw final"));
            ex.close();
            check("close never frees the renderer's atlas or scene depth", !rec.freed.contains(atlas) && !rec.freed.contains(sceneDepth));
        }
        System.out.println("# packfull " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }

    static int idx(List<String> c, String prefix) {
        for (int i = 0; i < c.size(); i++)
            if (c.get(i).startsWith(prefix))
                return i;
        return -1;
    }
}
