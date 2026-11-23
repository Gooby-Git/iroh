package dev.elemore.iroh.rttest;

import dev.elemore.iroh.gpu.VkGpuDevice.VkBuf;
import dev.elemore.iroh.rt.RtFrameData;
import dev.elemore.iroh.rt.RtLightGrid;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.*;

import static dev.elemore.iroh.rt.RtFrameData.*;
import static dev.elemore.iroh.rttest.RtReplay.check;
import static dev.elemore.iroh.rttest.RtReplay.f;
import static dev.elemore.iroh.rttest.RtReplay.metric;

/**
 * P5-3 step 2 ({@code ./gradlew rtLights}, lavapipe only): block light from many emissive quads. Plain RIS (P5-0, emit.comp)
 * against the per-section light grid and ReSTIR DI (restir.comp) on the village cave, the torch-lit village night and a
 * stress hall (225 emissive blocks of five colours, ~1100 emissive quads, moving crates), each against the CPU brute-force
 * reference (cosine rays that must hit emitters: an independent estimator). Measures per-frame error vs rays per pixel,
 * bias (static, 48 frames), the grid-only range cut, light switch lag and grid rebuild cost, moving occluders.
 */
public final class RtLightReplay {
    static final int W = 160, H = 90, WARM = 16, FRAMES = 48;

    record Variant(String name, int flags, int M, int taps, float share, int rays) {}

    static final List<Variant> VARIANTS = List.of(
            new Variant("RIS M=8 (P5-0), 1 ray", 0, 8, 0, 0f, 1),
            new Variant("RIS M=8, 2 rays (2 samples averaged)", 0, 8, 0, 0f, 2),
            new Variant("RIS M=8, 4 rays", 0, 8, 0, 0f, 4),
            new Variant("RIS M=8, 8 rays", 0, 8, 0, 0f, 8),
            new Variant("grid RIS M=8 (share 0.5), 1 ray", F_LIGHT_GRID, 8, 0, 0.5f, 1),
            new Variant("grid + ReSTIR temporal, 1 ray", F_LIGHT_GRID | F_RESTIR, 8, 0, 0.5f, 1),
            new Variant("grid + ReSTIR temporal + spatial(2), 1 ray", F_LIGHT_GRID | F_RESTIR, 8, 2, 0.5f, 1),
            new Variant("  same + visibility reuse", F_LIGHT_GRID | F_RESTIR | F_RESTIR_VISREUSE, 8, 2, 0.5f, 1),
            new Variant("grid ONLY (share 1.0: a range cut), ReSTIR t+s", F_LIGHT_GRID | F_RESTIR, 8, 2, 1.0f, 1));
    /** The variant built as the default: grid + ReSTIR TEMPORAL (spatial reuse measured biased here: not enabled). */
    static final int MAIN = 5;

    record Shot(String name, RtScene scene, RtCamera cam, int refSpp) {}

    public static void main(String[] args) throws Exception {
        Files.createDirectories(RtReplay.OUT);
        RtReplay.metrics = new java.io.PrintStream(Files.newOutputStream(RtReplay.OUT.resolve("light_metrics.txt")), true);
        long t0 = System.nanoTime();
        RtReplay.Light light = new RtReplay.Light(new RtFrameData().waterExtinction);
        RtScene hall = RtScene.hall();
        hall.entities = new RtEntities();
        RtScene village = new RtScene(true);
        int q = 0;
        for (RtScene.SectionMesh m : hall.sections) q += m.totalQuads();
        metric(String.format(Locale.ROOT, "P5-3 many lights: hall %d emissive blocks, %d emissive quads, %d quads; village %d emissive quads; %dx%d, %d frames after %d warm-up",
                hall.hallLamps, hall.lights.size(), q, village.lights.size(), W, H, FRAMES, WARM));
        List<Shot> shots = List.of(
                new Shot("hall", hall, new RtCamera(new Vector3f(5f, 9f, 42f), new Vector3f(30f, 6f, 12f), 70, W, H), 4096),
                new Shot("village cave", village, new RtCamera(RtScene.v(30.6f, 10.2f, 35.1f), RtScene.v(46f, 9.0f, 35.4f), 70, W, H), 4096),
                new Shot("village night, torches", village, new RtCamera(RtScene.v(12.5f, 12.8f, 27.5f), RtScene.v(10.6f, 9.6f, 12f), 70, W, H), 4096));
        Map<String, List<BufferedImage>> tiles = new LinkedHashMap<>();
        Map<String, double[][]> table = new LinkedHashMap<>();
        RtGpu hg = new RtGpu(hall, W, H), vg = new RtGpu(village, W, H);
        try {
            for (Shot s : shots) {
                RtGpu gpu = s.scene == hall ? hg : vg;
                metric(String.format(Locale.ROOT, "--- %s: light grid %d cells, %d entries (%d B on the GPU), CPU build %.2f ms",
                        s.name, gpu.grid.cellCount(), gpu.grid.entLight.length, gpu.grid.gpuBytes(), gpu.gridBuildMs));
                CpuRef ref = new CpuRef(s.scene, light, s.cam);
                CpuRef.Surf[] prim = ref.primary();
                boolean[] mask = new boolean[W * H];
                for (int i = 0; i < mask.length; i++) mask[i] = prim[i].valid && !s.scene.materials[prim[i].tile].water() && !s.scene.materials[prim[i].tile].emissive();
                String key = "lights-" + s.name.replaceAll("[^a-z]+", "_") + "-v1-" + W + "x" + H;
                float[] cpu = RtChain.cachedRef(key + "-" + s.refSpp, () -> ref.emit(prim, s.refSpp));
                // The brute-force CPU estimator (cosine rays that must hit an emitter) is hopeless for torch-sized emitters
                // (CPU-vs-CPU floor relMAE 0.70 at 4096 spp in the night scene, measured), so the per-pixel reference is a
                // 1024-sample GPU RIS run (M=16, 8 rays x 128 frames; RIS was checked against Lambert's exact polygon
                // irradiance in P5-0), and the CPU brute force cross-checks it on the image mean.
                float[] refA = meanOf(run(gpu, s.cam, light, new Variant("reference RIS M=16 x 8 rays", 0, 16, 0, 0f, 8), WARM + 128, null));
                double[] cx = RtImg.err(refA, cpu, mask);
                metric(String.format(Locale.ROOT, "  reference: GPU RIS 1024 samples/px; vs CPU brute force %d spp: image-mean bias %+.4f, relMAE %.4f (CPU noise-dominated)", s.refSpp, cx[1], cx[0]));
                float[] cpuPow = RtChain.cachedRef(key + "-pow256", () -> ref.estimate(prim, 256, 98, (sf, i, r) -> s.scene.materials[sf.tile].water() ? new double[3] : ref.emitPower(sf.pos, sf.n, r)));
                metric(String.format(Locale.ROOT, "  diagnostic: CPU light-list estimator (power CDF + area sampling, 256 spp) vs CPU brute force: bias %+.4f; vs GPU reference: bias %+.4f",
                        RtImg.err(cpuPow, cpu, mask)[1], RtImg.err(cpuPow, refA, mask)[1]));
                // Gate only where the CPU light-list estimator is usable (cave): the night's torches make its area-sampling tail
                // explode (+93%); the hall is gated per surface orientation below (RQ-P53-b)
                if (s.name.contains("cave"))
                    check("(" + s.name + ") GPU RIS reference agrees with the CPU light-list estimator on the image mean (" + String.format(Locale.ROOT, "%+.4f", -RtImg.err(cpuPow, refA, mask)[1]) + " within 2%)", Math.abs(RtImg.err(cpuPow, refA, mask)[1]) <= 0.02);
                if (s.name.equals("hall")) {   // RQ-P53-b gate: per surface orientation against the CPU light-list estimator
                    String[] nm = {"floor(+y)", "ceiling(-y)", "wall x", "wall z"};
                    double[] rat = new double[4];
                    for (int g = 0; g < 4; g++) {
                        double a = 0, b = 0, c2 = 0; int n = 0;
                        for (int i = 0; i < mask.length; i++) {
                            if (!mask[i]) continue;
                            double[] nn = prim[i].n;
                            int grp = nn[1] > 0.5 ? 0 : nn[1] < -0.5 ? 1 : Math.abs(nn[0]) > 0.5 ? 2 : 3;
                            if (grp != g) continue;
                            a += RtImg.lum(refA, i); b += RtImg.lum(cpuPow, i); c2 += RtImg.lum(cpu, i); n++;
                        }
                        rat[g] = a / b;
                        metric(String.format(Locale.ROOT, "  per orientation %-11s n=%5d GPU/CPU-lightlist %.3f GPU/CPU-brute %.3f", nm[g], n, a / b, a / c2));
                    }
                    check("(hall) GPU block light matches the CPU light-list estimator on floor and walls within 3% (RQ-P53-b: " + String.format(Locale.ROOT, "%.3f %.3f %.3f", rat[0], rat[2], rat[3])
                            + "; ceiling pixels next to lamp faces are judged by the brute force: the light list's area-sampling tail spikes there)", Math.abs(rat[0] - 1) <= 0.03 && Math.abs(rat[2] - 1) <= 0.03 && Math.abs(rat[3] - 1) <= 0.03);
                }
                double[][] rows = new double[VARIANTS.size()][];
                float[] lastRis = null, lastRestir = null;
                for (int v = 0; v < VARIANTS.size(); v++) {
                    Variant var = VARIANTS.get(v);
                    float[][] seq = run(gpu, s.cam, light, var, WARM + FRAMES, null);
                    rows[v] = score(seq, refA, mask, gpu);
                    metric(String.format(Locale.ROOT, "  %-48s per-frame relMAE %.4f | bias %+.4f | %d-frame mean relMAE %.4f | rays/px %.2f",
                            var.name, rows[v][0], rows[v][1], FRAMES, rows[v][2], rows[v][3]));
                    if (v == 0) lastRis = seq[seq.length - 1];
                    if (v == MAIN) lastRestir = seq[seq.length - 1];
                }
                table.put(s.name, rows);
                // equal-error ray count: RIS rays needed to match ReSTIR's per-frame error (log interpolation 1,2,4,8)
                double target = rows[MAIN][0];
                double eq = 8;
                int[] rr = {1, 2, 4, 8};
                for (int k = 0; k + 1 < 4; k++)
                    if (rows[k][0] >= target && rows[k + 1][0] <= target) {
                        double a = Math.log(rows[k][0]), b = Math.log(rows[k + 1][0]);
                        eq = rr[k] * Math.pow(2, (a - Math.log(target)) / (a - b));
                    }
                if (rows[0][0] <= target) eq = 1;
                metric(String.format(Locale.ROOT, "  => ReSTIR (grid, temporal) at 1 ray/px has the per-frame error of RIS at %s rays/px", rows[3][0] > target ? "> 8" : String.format(Locale.ROOT, "%.1f", eq)));
                check("(" + s.name + ") ReSTIR beats 1-ray RIS per frame (" + f(rows[MAIN][0]) + " < " + f(rows[0][0]) + ")", rows[MAIN][0] < rows[0][0]);
                check("(" + s.name + ") ReSTIR temporal bias no worse than 1-ray RIS's noise-limited 48-frame bias + 2% (" + String.format(Locale.ROOT, "%+.4f", rows[MAIN][1]) + " vs RIS "
                        + String.format(Locale.ROOT, "%+.4f", rows[0][1]) + ")", Math.abs(rows[MAIN][1]) <= Math.abs(rows[0][1]) + 0.02);
                List<BufferedImage> t = new ArrayList<>();
                t.add(shade(gpu, lastRis));
                t.add(shade(gpu, lastRestir));
                t.add(shade(gpu, refA));
                tiles.put(s.name, t);
            }
            double[][] hr = table.get("hall");
            check("(hall) grid-only sampling (share 1) is a range cut: measurably biased (" + String.format(Locale.ROOT, "%+.4f", hr[8][1]) + ") while the mixture is not", hr[8][1] < -0.01 && Math.abs(hr[MAIN][1]) < Math.abs(hr[8][1]));
            lagAndRebuild(hg, hall, light);
            movingOccluders(hg, hall, light, shots.get(0).cam);
        } finally {
            hg.close();
            vg.close();
        }
        sheet(tiles, table, light);
        metric(String.format(Locale.ROOT, "wall time %.1f s", (System.nanoTime() - t0) / 1e9));
        String last = "# rtlights " + (RtReplay.fail == 0 ? "PASS" : "FAIL") + " " + RtReplay.pass + "/" + (RtReplay.pass + RtReplay.fail);
        System.out.println(last);
        RtReplay.metrics.println(last);
        for (String r : RtReplay.report) RtReplay.metrics.println(r);
        RtReplay.metrics.close();
        System.exit(RtReplay.fail == 0 ? 0 : 1);
    }

    static void params(RtReplay.Light light, Variant v) {
        light.look = fd -> {
            fd.lightCandidates = v.M;
            fd.reuse[1] = 20f;
            fd.reuse[2] = v.taps;
            fd.reuse[3] = v.share;
        };
    }

    /** Static camera, {@code n} frames; returns per-frame block-light irradiance (rays averaged for multi-ray RIS). */
    static float[][] run(RtGpu gpu, RtCamera cam, RtReplay.Light light, Variant v, int n, java.util.function.IntConsumer perFrame) {
        params(light, v);
        VkBuf out = gpu.buf(gpu.N * 16L);
        gpu.clearReservoirs();
        gpu.resetCounters();
        float[][] seq = new float[n][];
        boolean restir = (v.flags & (F_LIGHT_GRID | F_RESTIR)) != 0;
        int counted = 0;
        for (int k = 0; k < n; k++) {
            if (perFrame != null) perFrame.accept(k);
            if (k == WARM) gpu.resetCounters();
            float[] acc = new float[gpu.N * 4];
            for (int j = 0; j < v.rays; j++) {
                gpu.setFrame(cam, cam, 7000 + k * 16 + j, F_ALPHA_TEST | v.flags, light);
                if (j == 0) gpu.gbuffer();
                if (restir) gpu.restir(out);
                else gpu.trace("block light (RIS)", gpu.pEmit, out);
                float[] f = gpu.readVec4(out);
                for (int i = 0; i < acc.length; i++) acc[i] += f[i] / v.rays;
            }
            if (k >= WARM) counted++;
            seq[k] = acc;
        }
        gpu.raysPerPixel = gpu.readCounters()[2] / (double) (counted * gpu.N);
        gpu.free(out);
        light.look = null;
        return seq;
    }

    /** {mean per-frame relMAE, bias of the frame mean, relMAE of the FRAMES-frame mean, rays/px}. */
    static double[] score(float[][] seq, float[] ref, boolean[] mask, RtGpu gpu) {
        double e = 0;
        float[] mean = new float[ref.length];
        for (int k = WARM; k < seq.length; k++) {
            e += RtImg.err(seq[k], ref, mask)[0] / (seq.length - WARM);
            for (int i = 0; i < mean.length; i++) mean[i] += seq[k][i] / (seq.length - WARM);
        }
        double[] m = RtImg.err(mean, ref, mask);
        return new double[]{e, m[1], m[0], gpu.raysPerPixel};
    }

    // ------------------------------------------------------------------------------------------------ lag + grid rebuild

    static void lagAndRebuild(RtGpu gpu, RtScene hall, RtReplay.Light light) {
        RtCamera cam = new RtCamera(new Vector3f(5f, 9f, 42f), new Vector3f(30f, 6f, 12f), 70, W, H);
        List<RtScene.Light> on = hall.lights, off = new ArrayList<>();
        RtScene.Material red = hall.materials[RtScene.T_RED_LAMP];
        for (RtScene.Light l : on) if (l.radiance()[0] != red.er() || l.radiance()[1] != red.eg()) off.add(l);
        RtScene.Material[] mOn = hall.materials.clone(), mOff = mOn.clone();
        mOff[RtScene.T_RED_LAMP] = new RtScene.Material(1f, 0, false, false, 0, 0, 0, 0.04f);
        int OFF = 16, T = OFF + 16;
        for (int v : new int[]{0, MAIN, 6}) {
            Variant var = VARIANTS.get(v);
            gpu.writeMaterials(mOff);
            gpu.writeLights(off);
            float[][] seq = run(gpu, cam, light, var, T, k -> { if (k == OFF) { gpu.writeMaterials(mOn); gpu.writeLights(on); } });
            float[][] ref = run(gpu, cam, light, VARIANTS.get(3), 1, null);   // 8-ray RIS of the lit hall: the level to reach
            double lo = 0, hi = 0;
            for (int k = OFF - 6; k < OFF; k++) lo += mean(seq[k]) / 6;
            hi = mean(ref[0]);
            StringBuilder sb = new StringBuilder();
            int f90 = -1;
            for (int k = OFF; k < T; k++) {
                double fr = (mean(seq[k]) - lo) / (hi - lo);
                if (f90 < 0 && fr >= 0.9) f90 = k - OFF + 1;
                if (k < OFF + 8) sb.append(String.format(Locale.ROOT, " %.2f", fr));
            }
            metric(String.format(Locale.ROOT, "(lag) red lamps switched on (%d of %d quads; light list rebuilt, indices shift): %-44s frames to 90%% %d; level per frame:%s",
                    on.size() - off.size(), on.size(), var.name, f90, sb));
            if (v != 0) check("(lag) ReSTIR follows a light switch within 4 frames (" + f90 + ")", f90 > 0 && f90 <= 4);
        }
        gpu.writeMaterials(mOn);
        gpu.writeLights(on);
        // grid rebuild cost: one section remesh (its lamps dimmed) rebuilds the 27 cells around it
        float[] c = new float[on.size() * 3], p = new float[on.size()];
        for (int i = 0; i < on.size(); i++) {
            RtScene.Light l = on.get(i);
            for (int a = 0; a < 3; a++) c[i * 3 + a] = l.p0()[a] + 0.5f * (l.e1()[a] + l.e2()[a]);
            p[i] = l.power();
        }
        RtLightGrid g = new RtLightGrid(hall.SX, hall.SY, hall.SZ, c.clone(), p.clone());
        long t0 = System.nanoTime();
        int reps = 50, cells = 0;
        for (int r = 0; r < reps; r++) cells = g.rebuildAround(1, 1, 1, c, p);
        double perSection = (System.nanoTime() - t0) / 1e6 / reps;
        metric(String.format(Locale.ROOT, "(grid) full build %.2f ms for %d lights x %d cells; one section remesh: %d cells rebuilt + repack in %.3f ms (CPU, Java, this machine)",
                gpu.gridBuildMs, on.size(), g.cellCount(), cells, perSection));
    }

    static float[] meanOf(float[][] seq) {
        float[] m = new float[seq[0].length];
        for (int k = WARM; k < seq.length; k++) for (int i = 0; i < m.length; i++) m[i] += seq[k][i] / (seq.length - WARM);
        return m;
    }

    static double mean(float[] a) {
        double s = 0;
        for (int i = 0; i < a.length / 4; i++) s += RtImg.lum(a, i);
        return s / (a.length / 4);
    }

    // ------------------------------------------------------------------------------------------------ moving occluders

    static void movingOccluders(RtGpu gpu, RtScene hall, RtReplay.Light light, RtCamera cam) {
        RtEntities.Walk[] crates = {
                new RtEntities.Walk(new float[]{10f, 4f, 30f}, new float[]{14f, 4f, 30f}, 24, new float[]{9f, 4f, 24f}, new float[]{16f, 4f, 20f}),
                new RtEntities.Walk(new float[]{20f, 4f, 26f}, new float[]{20f, 4f, 22f}, 24, new float[]{24f, 4f, 36f}, new float[]{30f, 4f, 30f})};
        int n = 24;
        java.util.function.IntFunction<List<RtEntities.Inst>> pose = k -> {
            List<RtEntities.Inst> l = new ArrayList<>();
            for (RtEntities.Walk w : crates) l.addAll(RtEntities.frame(w, Math.min(k, n - 1), Math.max(0, Math.min(k, n - 1) - 1), true));
            return l;
        };
        float[][] seq = run(gpu, cam, light, VARIANTS.get(MAIN), WARM + n, k -> gpu.poseEntities(pose.apply(Math.max(0, k - WARM)), true));
        CpuRef ref = new CpuRef(hall, light, cam);
        ref.setDynamic(hall.entities.triangles(pose.apply(n - 1)), 12);
        CpuRef.Surf[] prim = ref.primary();
        boolean[] mask = new boolean[W * H];
        for (int i = 0; i < mask.length; i++) mask[i] = prim[i].valid && !hall.materials[prim[i].tile].emissive();
        float[] r = RtChain.cachedRef("lights-hall-moving-v1-" + W + "x" + H + "-2048", () -> ref.emit(prim, 2048));
        double[] e = RtImg.err(seq[seq.length - 1], r, mask);
        float[][] st = run(gpu, cam, light, VARIANTS.get(0), WARM + 1, k -> gpu.poseEntities(pose.apply(n - 1), true));
        double[] es = RtImg.err(st[st.length - 1], r, mask);
        metric(String.format(Locale.ROOT, "(occluders) 2 crates + 2 mobs moving for %d frames: ReSTIR last-frame relMAE %.4f bias %+.4f vs the CPU reference of that pose; 1-ray RIS %.4f bias %+.4f",
                n, e[0], e[1], es[0], es[1]));
        check("(occluders) ReSTIR stays better than RIS with moving occluders (" + f(e[0]) + " < " + f(es[0]) + ")", e[0] < es[0]);
        gpu.poseEntities(List.of(), true);
    }

    // ------------------------------------------------------------------------------------------------ sheet

    /** Block-light-only image: emission + albedo / pi * irradiance (G-buffer albedo of the last frame), tone mapped. */
    static BufferedImage shade(RtGpu gpu, float[] irr) {
        int[] g = gpu.readUvec4(gpu.gbufCur);
        float[] c = new float[gpu.N * 4];
        for (int i = 0; i < gpu.N; i++) {
            if (Float.intBitsToFloat(g[i * 4 + 2]) <= 0) continue;
            int al = g[i * 4 + 1], tile = (al >>> 24) & 63;
            RtScene.Material m = gpu.scene.materials[tile];
            float[] e = {m.er(), m.eg(), m.eb()};
            for (int k = 0; k < 3; k++) c[i * 4 + k] = e[k] + ((al >>> (8 * k)) & 0xFF) / 255f / (float) Math.PI * irr[i * 4 + k];
        }
        return RtImg.ldr(c, gpu.W, gpu.H);
    }

    static void sheet(Map<String, List<BufferedImage>> tiles, Map<String, double[][]> table, RtReplay.Light light) throws Exception {
        List<BufferedImage> imgs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, List<BufferedImage>> e : tiles.entrySet()) {
            double[][] r = table.get(e.getKey());
            String[] n = {String.format(Locale.ROOT, "RIS M=8, 1 ray/px (P5-0): per-frame relMAE %.3f", r[0][0]),
                    String.format(Locale.ROOT, "grid + ReSTIR DI temporal, 1 ray/px: per-frame relMAE %.3f", r[MAIN][0]), "reference (GPU RIS, 1024 samples/px)"};
            for (int k = 0; k < 3; k++) {
                BufferedImage b = e.getValue().get(k);
                BufferedImage up = new BufferedImage(b.getWidth() * 4, b.getHeight() * 4, BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D gr = up.createGraphics();
                gr.drawImage(b, 0, 0, up.getWidth(), up.getHeight(), null);
                gr.dispose();
                imgs.add(up);
                labels.add(e.getKey() + " | block light only, one frame | " + n[k]);
            }
        }
        RtLookSheets.write(RtLookSheets.sheet("P5-3 many lights: per-section light grid + ReSTIR DI vs RIS vs CPU reference (one frame each, 160x90 shown x4, lavapipe offline)",
                labels, imgs, 3), "many_lights.png");
    }
}
