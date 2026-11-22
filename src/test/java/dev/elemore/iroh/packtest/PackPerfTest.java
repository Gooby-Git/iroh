package dev.elemore.iroh.packtest;

import dev.elemore.iroh.shaderpack.FrontEnd;
import dev.elemore.iroh.shaderpack.PackSource;
import dev.elemore.iroh.shaderpack.StandardMacros;
import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PackPlan;
import dev.elemore.iroh.shaderpack.runtime.FrameExecutor;
import dev.elemore.iroh.shaderpack.runtime.Mat;
import dev.elemore.iroh.shaderpack.runtime.RecordingBackend;
import dev.elemore.iroh.shaderpack.runtime.ShadowPlanner;
import dev.elemore.iroh.shaderpack.runtime.SyntheticFullScene;
import net.vulkanmod.render.chunk.build.task.TaskDispatcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * P3-7 C: shadow pass reuse and light-facing buckets, the derived upload budget, the bounded trace, and the executor's
 * allocation per frame (measured, bounded; NOT zero yet: see iris-loader.md section 10). {@code ./gradlew packPerfTest}.
 */
public final class PackPerfTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    public static void main(String[] args) throws Exception {
        // light-facing buckets (X_POS, X_NEG, Y_POS, Y_NEG, Z_POS, Z_NEG, UNDEFINED)
        check("sun (+x,+y,+z): X_POS|Y_POS|Z_POS|UNDEFINED", ShadowPlanner.lightFacingMask(new double[]{0.5, 0.8, 0.3}) == (1 | 4 | 16 | 64));
        check("sun (-x,+y,-z): X_NEG|Y_POS|Z_NEG|UNDEFINED", ShadowPlanner.lightFacingMask(new double[]{-0.5, 0.8, -0.3}) == (2 | 4 | 32 | 64));
        check("edge-on axis keeps both sides", (ShadowPlanner.lightFacingMask(new double[]{0, 1, 0}) & 3) == 3);
        double[][] sh = Mat.shadow(new double[]{0.5, 0.8, 0.3}, new double[]{10, 70, 10}, 96, 2);
        double[] l = ShadowPlanner.lightDir(sh[0]);
        check("light direction from the shadow view", Math.abs(l[0] - 0.5 / Math.sqrt(0.98)) < 1e-6 && Math.abs(l[1] - 0.8 / Math.sqrt(0.98)) < 1e-6);
        // reuse with bounded staleness
        ShadowPlanner pl = new ShadowPlanner();
        int reused = 0;
        for (int f = 0; f < 10; f++) {
            double[] v = sh[0].clone();
            v[12] += f * 1e-4; // tiny drift (sun step)
            if (pl.decide(v, sh[1], 3, 2e-3, false)[2][0] == 1)
                reused++;
        }
        check("3 reused per 4 frames under tiny drift (" + reused + "/10)", reused == 7 || reused == 8);
        double[] moved = sh[0].clone();
        moved[12] += 1;
        check("a moved shadow camera renders", pl.decide(moved, sh[1], 3, 2e-3, false)[2][0] == 0);
        check("forced render", pl.decide(moved, sh[1], 3, 2e-3, true)[2][0] == 0);
        check("maxStale 0 never reuses", new ShadowPlanner().decide(sh[0], sh[1], 0, 1, false)[2][0] == 0);
        // upload budget derived from the section vertex size
        check("budget 48 MiB at 16 B", TaskDispatcher.derivedUploadBudget(null, 16) == 48L << 20);
        check("budget 108 MiB at 36 B (same sections per frame)", TaskDispatcher.derivedUploadBudget(null, 36) == 108L << 20);
        check("explicit -Diroh.uploadBudgetMB wins", TaskDispatcher.derivedUploadBudget("20", 36) == 20L << 20);

        Path pack = Paths.get(System.getProperty("user.home"), ".local/share/atlauncher/instances/Vulkan/shaderpacks/Navidson");
        if (Files.isDirectory(pack)) {
            Path opt = pack.resolveSibling("Navidson.txt");
            String saved = Files.isRegularFile(opt) ? Files.readString(opt, StandardCharsets.UTF_8) : null;
            Path dump = Paths.get("build/packPerf/Navidson");
            PackHarness.deleteTree(dump);
            FrontEnd.run(PackSource.open(pack), new FrontEnd.Config(StandardMacros.Env.defaults(), saved, true, dump));
            PackData data = PackData.load(dump);
            PackPlan plan = PackPlan.build(data);
            RecordingBackend rec = new RecordingBackend();
            SyntheticFullScene in = new SyntheticFullScene(data, plan.frames.get("world0"), plan.resources.get("world0"), 160, 90);
            FrameExecutor ex = new FrameExecutor(data, plan, "world0", rec, FrameExecutor.cap(1L << 30));
            ex.frame(in);
            int callsBefore = rec.calls.size();
            ex.reuseShadow = true;
            ex.frame(in);
            java.util.List<String> second = rec.calls.subList(callsBefore, rec.calls.size());
            check("reuse: no shadow pass draws", second.stream().noneMatch(c -> c.startsWith("geometry shadow:")));
            check("reuse: no shadowcomp dispatch", second.stream().noneMatch(c -> c.startsWith("dispatch shadowcomp")));
            check("reuse: shadow maps not cleared", second.stream().noneMatch(c -> c.startsWith("clear pack:shadow")));
            check("reuse: terrain still drawn", second.stream().anyMatch(c -> c.startsWith("geometry gbuffers_terrain")));
            check("reuse: no violations " + rec.violations, rec.violations.isEmpty());
            ex.reuseShadow = false;
            // bounded trace + allocation per frame (executor only; the recording backend's own lists excluded by clearing)
            for (int i = 0; i < 20; i++) {
                ex.frame(in);
                rec.calls.clear();
                rec.draws.clear();
                rec.geoDraws.clear();
                rec.dispatches.clear();
            }
            check("trace holds one frame (" + ex.trace.size() + " lines)", ex.trace.size() < 200);
            var mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            long t = Thread.currentThread().getId();
            long a0 = mx.getThreadAllocatedBytes(t);
            int frames = 50;
            for (int i = 0; i < frames; i++) {
                ex.frame(in);
                rec.calls.clear();
                rec.draws.clear();
                rec.geoDraws.clear();
                rec.dispatches.clear();
            }
            long perFrame = (mx.getThreadAllocatedBytes(t) - a0) / frames;
            System.out.println("# executor allocation per full frame: " + perFrame / 1024 + " KiB (recording backend, 9 terrain batches, 45 passes)");
            check("allocation per frame bounded (< 4 MiB)", perFrame < (4L << 20));
            ex.close();
            // P3-8: the executor's OWN steady-state allocation (no-op backend, tracing off)
            for (boolean up : new boolean[]{false, true}) {
                NullBackend nb = new NullBackend();
                FrameExecutor ne = new FrameExecutor(data, plan, "world0", nb, FrameExecutor.cap(1L << 30));
                ne.tracing = false;
                if (up)
                    ne.upscale = new FrameExecutor.Upscale("composite5", "colortex3", java.util.Set.of("composite6"), 320, 180,
                            new dev.elemore.iroh.shaderpack.runtime.PackUpscaler.Bilinear());
                for (int i = 0; i < 300; i++)
                    ne.frame(in); // warm-up (JIT, caches)
                long b0 = mx.getThreadAllocatedBytes(t);
                for (int i = 0; i < 200; i++)
                    ne.frame(in);
                long per = (mx.getThreadAllocatedBytes(t) - b0) / 200;
                System.out.println("# executor own allocation per full frame" + (up ? " (upscaler on)" : "") + ": " + per + " bytes");
                check("zero steady-state allocation in the executor" + (up ? " (upscaler)" : "") + " (" + per + " B/frame)", per == 0);
                check("frames drew", nb.draws > 0);
            }
            // P3-11: the world pass's own steady-state allocation: segmented frame + 300 substituted draws per frame
            {
                NullBackend nb = new NullBackend();
                FrameExecutor ne = new FrameExecutor(data, plan, "world0", nb, FrameExecutor.cap(1L << 30));
                ne.tracing = false;
                Object tex = new Object();
                dev.elemore.iroh.shaderpack.runtime.WorldPass.Host host = new dev.elemore.iroh.shaderpack.runtime.WorldPass.Host() {
                    boolean bound = true;
                    public boolean irohPassBound() { return bound; }
                    public void endIrohPass() { bound = false; }
                    public void resumeIrohPass() { bound = true; }
                    public void invalidateBoundState() {}
                    public void reapplyDynamicState() {}
                };
                dev.elemore.iroh.shaderpack.runtime.WorldPass.DrawSource ds = new dev.elemore.iroh.shaderpack.runtime.WorldPass.DrawSource() {
                    public void modelView(double[] o) { o[0] = o[5] = o[10] = o[15] = 1; }
                    public void projection(double[] o) { o[0] = o[5] = o[10] = o[15] = 1; }
                    public Object texture() { return tex; }
                    public long textureKey(Object t) { return 1; }
                    public int importTexture(Object t) { return nb.importImage(t, "tex"); }
                    public void ids(java.util.Map<String, double[]> out) { out.get("entityId")[0] = 3; }
                };
                var wp = new dev.elemore.iroh.shaderpack.runtime.WorldPass(ne, nb, host, ds, java.util.Set.of(), true);
                java.util.List<String> ent = java.util.List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal");
                java.util.List<String> part = java.util.List.of("Position", "UV0", "Color", "UV2");
                Object k1 = new Object(), k2 = new Object(), k3 = new Object(), k4 = new Object();
                Runnable frame = () -> {
                    wp.begin(in);
                    wp.opaque(in);
                    for (int i = 0; i < 100; i++) {
                        wp.onBind(k1, "rendertype_entity_cutout_no_cull", ent, 3, true, true, false, false, 0, 0, 0, 0, true);
                        wp.beforeDraw();
                        wp.beforeDraw();
                        wp.onBind(k2, "rendertype_crumbling", ent, 3, true, false, false, true, 4, 2, 1, 0, true);
                        wp.beforeDraw();
                        wp.onBind(k3, "rendertype_gui", ent, 3, true, true, false, false, 0, 0, 0, 0, true); // raster
                    }
                    wp.translucent(in);
                    for (int i = 0; i < 50; i++) {
                        wp.onBind(k4, "particle", part, 3, true, false, false, true, 6, 7, 1, 7, true);
                        wp.beforeDraw();
                    }
                    wp.end(in);
                };
                for (int i = 0; i < 300; i++)
                    frame.run();
                long b0 = mx.getThreadAllocatedBytes(t);
                for (int i = 0; i < 200; i++)
                    frame.run();
                long per = (mx.getThreadAllocatedBytes(t) - b0) / 200;
                System.out.println("# world pass own allocation per frame (350 substituted draws, 400 binds): " + per + " bytes; " + wp.report());
                // lessons.md: ThreadMXBean has a few hundred bytes of noise; "zero" = under 16 KB over all 200 frames
                check("P3-11: zero steady-state allocation in the world pass + executor (" + per + " B/frame, " + per * 200 + " B total)", per * 200 < 16 * 1024);
                check("P3-11: world pass healthy (" + wp.failure() + ")", wp.failure() == null && wp.substitutedDraws == 350);
            }
        }
        System.out.println("# packperf " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
