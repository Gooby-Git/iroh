package dev.elemore.iroh.rttest;

import dev.elemore.iroh.upscale.fsr.NativeLoader;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.*;

import static dev.elemore.iroh.rt.RtFrameData.*;
import static dev.elemore.iroh.rttest.RtReplay.check;
import static dev.elemore.iroh.rttest.RtReplay.f;
import static dev.elemore.iroh.rttest.RtReplay.metric;

/**
 * rtReplay step (i), P5-1: the full chain over P5-0's scripted 30-frame camera move, measured per stage at DISPLAY
 * resolution against the CPU reference path tracer; then a static camera (temporal stability) and a light switched off
 * and on (lag). Render 320x180 -> display 640x360 (FSR Performance, 32-phase jitter).
 */
final class RtChainStep {
    static final int RW = 320, RH = 180, DW = 640, DH = 360, MOVE = 30;

    static RtCamera pathCam(RtCamera cam, int k, int n, int w, int h) {
        float t = k / (float) (n - 1);
        Vector3f d = new Vector3f(7f * t, 0.5f * t, -3f * t);
        return new RtCamera(new Vector3f(cam.pos).add(d), new Vector3f(cam.target).add(d.x * 0.7f, d.y, d.z * 0.7f), cam.fovYDeg, w, h);
    }

    static boolean[] all(int n) {
        boolean[] m = new boolean[n];
        Arrays.fill(m, true);
        return m;
    }

    static String row(String name, double[] s, double detail) {
        return String.format(Locale.ROOT, "  %-46s relMAE %.4f  bias %+.4f  tonemapped RMSE %.4f  detail corr %.3f", name, s[0], s[1], s[2], detail);
    }

    static void run(RtScene scene, RtReplay.Light light, RtCamera base) throws IOException {
        metric("--- (i) full chain: jittered G-buffer -> RT @" + RW + "x" + RH + " -> own denoiser (diffuse+IN_ALBEDO, specular, shadow) -> compose -> FSR 3.1 -> "
                + DW + "x" + DH + " -> tone map; vs CPU reference @" + DW + "x" + DH);
        if (!NativeLoader.load()) {
            check("(i) FSR 3.1 library available (" + NativeLoader.unavailableReason() + ")", false);
            return;
        }
        int flags = F_ALPHA_TEST | F_SOFT_SUN | F_CACHE | F_REJECT;
        RtCamera lastD = pathCam(base, MOVE - 1, MOVE, DW, DH), lastR = pathCam(base, MOVE - 1, MOVE, RW, RH);
        float[] refD = RtChain.reference(scene, light, lastD, "cam29", 256, 2048, 512, 1024);
        float[] refR = RtChain.reference(scene, light, lastR, "cam29", 512, 4096, 1024, 2048);
        long t0 = System.nanoTime();
        try (RtChain ch = new RtChain(scene, light, RW, RH, DW, DH, base.fovYDeg)) {
            metric("  devices: RT " + ch.gpu.dev.deviceName + "; denoise/FSR " + ch.lvp.deviceName + " (host copies between them: harness only)");
            metric("  jitter: FSR Halton(2,3), " + ch.js.phaseCount + " phases; motion vectors: G-buffer pixels (prev - cur, unjittered) -> UV once in RtChain.pack");
            // ================================================================ MOVE
            ch.gpu.clearCache();
            float[] noisyR = null, denR = null, noisyF = null, denF = null;
            float[][] dout = null;
            try (RtChain.Den den = ch.new Den(); RtChain.Fsr fd = ch.new Fsr(); RtChain.Fsr fn = ch.new Fsr()) {
                for (int k = 0; k < MOVE; k++) {
                    RtCamera c = pathCam(base, k, MOVE, RW, RH), p = pathCam(base, Math.max(0, k - 1), MOVE, RW, RH);
                    RtChain.Rt r = ch.trace(c, p, 50_000 + k, k, flags);
                    RtChain.In in = ch.pack(r);
                    float[] jp = ch.js.offset(Math.max(0, k - 1));
                    final int kk = k;
                    float[][][] box = new float[1][][];
                    ch.time("own denoiser (3 channels)", () -> box[0] = den.run(in, kk, kk == 0, c, p, r.jitter, jp));
                    dout = box[0];
                    denR = ch.compose(dout[0], dout[2], 4, dout[1]);
                    noisyR = ch.compose(in.diff, in.visRaw, 1, in.spec);
                    float[] dr = denR, nr = noisyR;
                    float[][] o = new float[2][];
                    ch.time("FSR 3.1 " + RW + "x" + RH + " -> " + DW + "x" + DH, () -> o[0] = fd.run(dr, in, r.jitter, kk == 0, box[0][0]));
                    o[1] = fn.run(nr, in, r.jitter, k == 0, null);
                    denF = o[0];
                    noisyF = o[1];
                }
            }
            boolean[] mD = all(DW * DH), mR = all(RW * RH);
            Map<String, float[]> disp = new LinkedHashMap<>();
            disp.put("A noisy 1 spp -> bilinear", RtChain.bilinear(noisyR, RW, RH, DW, DH));
            disp.put("B denoised -> bilinear", RtChain.bilinear(denR, RW, RH, DW, DH));
            disp.put("C noisy 1 spp -> FSR 3.1", noisyF);
            disp.put("D denoised -> FSR 3.1 (THE CHAIN)", denF);
            disp.put("R CPU reference @render -> bilinear (ceiling)", RtChain.bilinear(refR, RW, RH, DW, DH));
            metric("(i) frame 29 of the move, per stage at DISPLAY resolution vs the CPU reference (" + DW + "x" + DH + "):");
            Map<String, double[]> sc = new LinkedHashMap<>();
            Map<String, Double> dt = new LinkedHashMap<>();
            boolean finite = true;
            for (Map.Entry<String, float[]> e : disp.entrySet()) {
                double[] s = RtChain.score(e.getValue(), refD, mD);
                double d = RtChain.detail(e.getValue(), refD, mD, DW, DH);
                sc.put(e.getKey(), s);
                dt.put(e.getKey(), d);
                finite &= RtChain.finite(e.getValue());
                metric(row(e.getKey(), s, d));
            }
            double[] rn = RtChain.score(noisyR, refR, mR), rd = RtChain.score(denR, refR, mR);
            metric("  at RENDER resolution vs the CPU reference @" + RW + "x" + RH + ":");
            metric(row("noisy 1 spp composed", rn, RtChain.detail(noisyR, refR, mR, RW, RH)));
            metric(row("denoised composed", rd, RtChain.detail(denR, refR, mR, RW, RH)));
            // disocclusion: render pixels with short diffuse history at frame 29, mapped to display
            boolean[] dis = new boolean[DW * DH], stable = new boolean[DW * DH];
            int nd = 0;
            for (int y = 0; y < DH; y++)
                for (int x = 0; x < DW; x++) {
                    int ri = (y * RH / DH) * RW + x * RW / DW;
                    float hist = dout[0][ri * 4 + 3];
                    boolean surf = denR != null && RtImg.lum(dout[0], ri) >= 0 && hist > 0;
                    dis[y * DW + x] = surf && hist <= 4;
                    stable[y * DW + x] = surf && hist >= 16;
                    if (dis[y * DW + x]) nd++;
                }
            double[] eDis = RtChain.score(denF, refD, dis), eSt = RtChain.score(denF, refD, stable);
            metric(String.format(Locale.ROOT, "  disocclusion: %d display px with diffuse history <= 4 frames: chain relMAE %.4f, tonemapped RMSE %.4f (full-history px: relMAE %.4f, RMSE %.4f)",
                    nd, eDis[0], eDis[2], eSt[0], eSt[2]));
            check("(i) chain output is finite (no NaN/Inf in any stage)", finite);
            double eA = sc.get("A noisy 1 spp -> bilinear")[2], eB = sc.get("B denoised -> bilinear")[2], eC = sc.get("C noisy 1 spp -> FSR 3.1")[2], eD = sc.get("D denoised -> FSR 3.1 (THE CHAIN)")[2];
            check("(i) denoising lowers the display error vs the noisy frame (bilinear " + f(eB) + " < " + f(eA) + ")", eB < eA);
            check("(i) the chain (denoised -> FSR) beats FSR on the noisy frame (" + f(eD) + " < " + f(eC) + ")", eD < eC);
            check("(i) FSR on the denoised frame is not worse than bilinear (" + f(eD) + " <= 1.05 x " + f(eB) + ")", eD <= eB * 1.05);
            check("(i) FSR recovers more texture detail than bilinear (" + String.format(Locale.ROOT, "%.3f > %.3f", dt.get("D denoised -> FSR 3.1 (THE CHAIN)"), dt.get("B denoised -> bilinear")) + ")",
                    dt.get("D denoised -> FSR 3.1 (THE CHAIN)") > dt.get("B denoised -> bilinear"));
            check("(i) the move disoccludes pixels (" + nd + " > 50)", nd > 50);
            check("(i) chain relMAE at display <= 0.15 (" + f(sc.get("D denoised -> FSR 3.1 (THE CHAIN)")[0]) + ")", sc.get("D denoised -> FSR 3.1 (THE CHAIN)")[0] <= 0.15);
            RtImg.save("i_noisy_bilinear", RtImg.ldr(ch.postNeutral(disp.get("A noisy 1 spp -> bilinear"), DW * DH), DW, DH));
            RtImg.save("i_denoised_bilinear", RtImg.ldr(ch.postNeutral(disp.get("B denoised -> bilinear"), DW * DH), DW, DH));
            RtImg.save("i_noisy_fsr", RtImg.ldr(ch.postNeutral(noisyF, DW * DH), DW, DH));
            RtImg.save("i_chain_fsr", RtImg.ldr(ch.postNeutral(denF, DW * DH), DW, DH));
            RtImg.save("i_reference_cpu", RtImg.ldr(ch.postNeutral(refD, DW * DH), DW, DH));
            RtImg.save("i_chain_diff_x10", RtImg.diff(denF, refD, DW, DH, 0.1));

            // ================================================================ STATIC (temporal stability)
            ch.gpu.clearCache();
            int S = 24;
            double[] flick = new double[3];
            float[][] prev = new float[3][];
            float[] lastStatic = null;
            try (RtChain.Den den = ch.new Den(); RtChain.Fsr fd = ch.new Fsr()) {
                for (int k = 0; k < S; k++) {
                    RtChain.Rt r = ch.trace(lastR, lastR, 60_000 + k, k, flags);
                    RtChain.In in = ch.pack(r);
                    float[][] o = den.run(in, k, k == 0, lastR, lastR, r.jitter, ch.js.offset(Math.max(0, k - 1)));
                    float[] dR = ch.compose(o[0], o[2], 4, o[1]), nR = ch.compose(in.diff, in.visRaw, 1, in.spec);
                    float[][] cur = {RtChain.bilinear(nR, RW, RH, DW, DH), RtChain.bilinear(dR, RW, RH, DW, DH), fd.run(dR, in, r.jitter, k == 0, o[0])};
                    if (k >= 12)
                        for (int v = 0; v < 3; v++) {
                            double s = 0;
                            for (int i = 0; i < DW * DH; i++) s += Math.abs(RtChain.tml(cur[v], i) - RtChain.tml(prev[v], i));
                            flick[v] += s / (DW * DH) / (S - 12);
                        }
                    prev = cur;
                    lastStatic = cur[2];
                }
            }
            double[] eStatic = RtChain.score(lastStatic, refD, mD);
            metric(String.format(Locale.ROOT, "(i) static camera, frames 12-23: mean |frame-to-frame change| of tonemapped luminance (flicker): noisy->bilinear %.4f, denoised->bilinear %.4f, chain %.4f (reference 0); converged chain relMAE %.4f RMSE %.4f",
                    flick[0], flick[1], flick[2], eStatic[0], eStatic[2]));
            check("(i) temporal stability: chain flicker <= 1/4 of the noisy frame's (" + f(flick[2]) + " vs " + f(flick[0]) + ")", flick[2] <= flick[0] / 4);

            // ================================================================ LIGHT OFF -> ON (lag)
            RtScene.Material[] on = scene.materials.clone(), off = scene.materials.clone();
            RtScene.Material gm = on[RtScene.T_GLOWSTONE];
            off[RtScene.T_GLOWSTONE] = new RtScene.Material(gm.roughness(), gm.alphaMode(), gm.water(), gm.spec(), 0, 0, 0, gm.f0());
            List<RtScene.Light> lOn = scene.lights, lOff = new ArrayList<>();
            for (RtScene.Light l : lOn) if (l.radiance()[0] != gm.er()) lOff.add(l);
            int OFF = 12, T = OFF + 24;
            float[][][] withCache = lagRun(ch, lastR, flags, on, off, lOn, lOff, OFF, T), noCache = lagRun(ch, lastR, flags & ~F_CACHE, on, off, lOn, lOff, OFF, T);
            boolean[] region = new boolean[DW * DH];
            int nreg = 0;
            for (int i = 0; i < DW * DH; i++) {
                double a = RtImg.lum(lastStatic, i), b = RtImg.lum(withCache[0][OFF - 1], i);
                region[i] = a > 0.05 && a > 1.33 * b;
                if (region[i]) nreg++;
            }
            int f90 = -1;
            String[] names = {"chain (cache on)", "noisy 1 spp (cache on)", "denoised, no FSR (cache on)", "chain (cache off)", "noisy 1 spp (cache off)"};
            float[][][] seqs = {withCache[0], withCache[1], withCache[2], noCache[0], noCache[1]};
            for (int v = 0; v < seqs.length; v++) {
                double lvOff = mean(seqs[v][OFF - 1], region), lvOn = v == 2 ? mean(seqs[v][T - 1], region) : mean(lastStatic, region);
                int a50 = -1, a90 = -1;
                StringBuilder sb = new StringBuilder();
                for (int k = OFF; k < T; k++) {
                    double fr = (mean(seqs[v][k], region) - lvOff) / (lvOn - lvOff);
                    if (a50 < 0 && fr >= 0.5) a50 = k - OFF + 1;
                    if (a90 < 0 && fr >= 0.9) a90 = k - OFF + 1;
                    if (k < OFF + 12) sb.append(String.format(Locale.ROOT, " %.2f", fr));
                }
                if (v == 0) f90 = a90;
                metric(String.format(Locale.ROOT, "(i) light switch, %-24s %d display px: frames to 50%% %d, to 90%% %d; level per frame:%s", names[v], nreg, a50, a90, sb));
            }
            check("(i) light switch: the chain reaches 90% of the new level within 16 frames (" + f90 + ")", f90 > 0 && f90 <= 16);
            RtImg.save("i_static_chain", RtImg.ldr(ch.postNeutral(lastStatic, DW * DH), DW, DH));

            // ================================================================ levers: error vs rays per pixel (step 3)
            metric("(i) quality levers over the same 30-frame move (chain output at display res vs the CPU reference; rays per RENDER pixel per frame):");
            String[] ln = {"full rate, RIS M=8", "GI + refl checkerboard, M=8", "GI + refl checkerboard, M=4", "full rate, M=16", "full rate, M=1 (plain light sampling)", "GI checkerboard only, M=8"};
            int[] lf = {0, F_HALF_GI | F_HALF_REFL, F_HALF_GI | F_HALF_REFL, 0, 0, F_HALF_GI};
            int[] lm = {8, 8, 4, 16, 1, 8};
            double[][] lev = new double[ln.length][];
            for (int v = 0; v < ln.length; v++) {
                lev[v] = lever(ch, base, light, flags | lf[v], lm[v], refD);
                metric(String.format(Locale.ROOT, "  %-40s relMAE %.4f  RMSE %.4f  detail %.3f | rays/px sun %.3f light %.3f GI %.3f refl %.3f total %.3f",
                        ln[v], lev[v][0], lev[v][1], lev[v][2], lev[v][3], lev[v][4], lev[v][5], lev[v][6], lev[v][3] + lev[v][4] + lev[v][5] + lev[v][6]));
            }
            check("(i) checkerboard GI + reflections halves their rays (" + f(lev[1][5]) + " <= 0.55 x " + f(lev[0][5]) + ")", lev[1][5] <= 0.55 * lev[0][5]);
            check("(i) checkerboard costs at most 15% more display error (" + f(lev[1][1]) + " vs " + f(lev[0][1]) + ")", lev[1][1] <= 1.15 * lev[0][1]);

            // ================================================================ cost
            metric("(i) chain cost per frame on lavapipe (RELATIVE only, harness host copies included):");
            for (Map.Entry<String, double[]> e : ch.cost.entrySet())
                metric(String.format(Locale.ROOT, "  %-56s %8.1f ms/call (%d calls)", e.getKey(), e.getValue()[0] / 1e6 / e.getValue()[1], (int) e.getValue()[1]));
            for (Map.Entry<String, long[]> e : ch.gpu.timing.entrySet())
                metric(String.format(Locale.ROOT, "    pass %-34s %8.2f ms/call (%d calls)", e.getKey(), e.getValue()[0] / 1e6 / e.getValue()[1], e.getValue()[1]));
        }
        metric(String.format(Locale.ROOT, "(i) chain wall time %.1f s", (System.nanoTime() - t0) / 1e9));
    }

    /** Glowstones off for {@code off} frames, then on; returns {chain display frames, noisy-bilinear display frames}. */
    static float[][][] lagRun(RtChain ch, RtCamera cam, int flags, RtScene.Material[] on, RtScene.Material[] off, List<RtScene.Light> lOn, List<RtScene.Light> lOff, int OFF, int T) {
        ch.gpu.clearCache();
        ch.gpu.writeMaterials(off);
        ch.gpu.writeLights(lOff);
        float[][] seqF = new float[T][], seqN = new float[T][], seqD = new float[T][];
        try (RtChain.Den den = ch.new Den(); RtChain.Fsr fd = ch.new Fsr()) {
            for (int k = 0; k < T; k++) {
                if (k == OFF) { ch.gpu.writeMaterials(on); ch.gpu.writeLights(lOn); }
                RtChain.Rt r = ch.trace(cam, cam, 70_000 + k, k, flags);
                RtChain.In in = ch.pack(r);
                float[][] o = den.run(in, k, k == 0, cam, cam, r.jitter, ch.js.offset(Math.max(0, k - 1)));
                float[] dR = ch.compose(o[0], o[2], 4, o[1]);
                seqD[k] = RtChain.bilinear(dR, RW, RH, DW, DH);
                seqF[k] = fd.run(dR, in, r.jitter, k == 0, o[0]);
                seqN[k] = RtChain.bilinear(ch.compose(in.diff, in.visRaw, 1, in.spec), RW, RH, DW, DH);
            }
        }
        ch.gpu.writeMaterials(on);
        ch.gpu.writeLights(lOn);
        return new float[][][]{seqF, seqN, seqD};
    }

    /** One 30-frame move with the given flags and RIS candidates: {relMAE, RMSE, detail, rays/px sun, light, GI, refl}. */
    static double[] lever(RtChain ch, RtCamera base, RtReplay.Light light, int flags, int M, float[] refD) {
        java.util.function.Consumer<dev.elemore.iroh.rt.RtFrameData> old = light.look;
        light.look = fd -> fd.lightCandidates = M;
        ch.gpu.clearCache();
        ch.gpu.resetCounters();
        float[] out = null;
        try (RtChain.Den den = ch.new Den(); RtChain.Fsr fd = ch.new Fsr()) {
            for (int k = 0; k < MOVE; k++) {
                RtCamera c = pathCam(base, k, MOVE, RW, RH), p = pathCam(base, Math.max(0, k - 1), MOVE, RW, RH);
                RtChain.Rt r = ch.trace(c, p, 50_000 + k, k, flags);
                RtChain.In in = ch.pack(r);
                float[][] o = den.run(in, k, k == 0, c, p, r.jitter, ch.js.offset(Math.max(0, k - 1)));
                out = fd.run(ch.compose(o[0], o[2], 4, o[1]), in, r.jitter, k == 0, o[0]);
            }
        }
        light.look = old;
        int[] cnt = ch.gpu.readCounters();
        double n = (double) MOVE * ch.N;
        double[] sc = RtChain.score(out, refD, all(DW * DH));
        return new double[]{sc[0], sc[2], RtChain.detail(out, refD, all(DW * DH), DW, DH), cnt[1] / n, cnt[2] / n, cnt[3] / n, cnt[4] / n};
    }

    static double mean(float[] a, boolean[] m) {
        double s = 0;
        int n = 0;
        for (int i = 0; i < m.length; i++) if (m[i]) { s += RtImg.lum(a, i); n++; }
        return n == 0 ? 0 : s / n;
    }
}
