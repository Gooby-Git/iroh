package dev.elemore.iroh.upscaletest;

import dev.elemore.iroh.upscale.JitterSequence;
import dev.elemore.iroh.upscale.fsr.FsrNative;
import dev.elemore.iroh.upscale.fsr.FsrTuning;
import dev.elemore.iroh.upscale.fsr.NativeLoader;

import java.io.File;
import java.util.Locale;

import static dev.elemore.iroh.upscaletest.FsrReplay.*;

/**
 * FSR-Q2: the offline metrics every later FSR quality card is judged by (lavapipe, synthetic scenes, no game).
 *
 * <pre>
 * 1 static edge   diagonal block edge, Native AA / 1.5x / 2x: edge RMSE per frame vs an 8x8 supersampled reference,
 *                 frames until stable ("convergence"), final edge RMSE, relative to the aliased native image
 * 2 jitter sign   the four scene-sign combinations vs the shipped one: the shipped must sharpen, the others blur/double
 * 3 ghosting      FsrReplay's moving square over a textured background (Performance 2x): error in the pixels the square
 *                 left 4 frames ago and the trail length at the last frame, with and without motion vectors
 * 4 stability     static scene: frame-to-frame flicker (whole image, and 1 px fence lines)
 * 5 sharpness     gradient energy and peak gradient of the edge band vs the reference; overshoot (ringing) %
 * </pre>
 * Options: {@code --force16}, {@code --dump=dir}, {@code --sweep=KEY:v1,v2;KEY2:..}, {@code --combo=KEY=v,KEY=v|..}, {@code --ratio=1,2} (the Q3 sweep per mode), {@code --rcas=0.2}. Last line {@code # fsrmetrics PASS|FAIL}.
 * Gate thresholds are regression bars set from the baseline table in knowledge/fsr.md, not quality targets.
 */
public final class FsrMetrics {
    static final int DW = 360, DH = 180;
    static final int EDGE_FRAMES = 20;
    static final float LO = 0.12f, HI = 0.88f;
    static boolean force16;
    static float rcasArg;
    static String rcasSweep;
    static boolean reactiveSweep;

    // ---------------------------------------------------------------- scene: a diagonal edge, optional 1 px fence lines
    static float signX = -1, signY = -1;   // scene sampling sign (-1 = the shipped / SDK convention, see FsrReplay.JSX)

    /** signed distance (display px) of a point from the edge line y = 0.5 (x - 180) + 90; positive = below the line. */
    static float dist(float x, float y) { return (y - 0.5f * (x - 180f) - 90f) / 1.1180340f; }

    static float[] edgeRgb(float x, float y) {
        boolean below = dist(x, y) > 0;
        float v = below ? LO : HI;
        return new float[]{v, v * (below ? 1.1f : 0.95f), v * (below ? 1.2f : 0.9f)};
    }

    static float[] fenceRgb(float x, float y) {
        int k = (int) Math.floor(x);
        boolean line = (k & 15) == 7;
        float v = line ? 0.9f : 0.3f;
        return new float[]{v, v, v};
    }

    interface Fn { float[] at(float x, float y); }

    static FrameSource staticScene(Fn fn, float ratio, boolean point) {
        int rw = Math.round(DW / ratio), rh = Math.round(DH / ratio);
        int sup = point ? 1 : 3;
        return (t, jx, jy, color, depth, mv) -> {
            for (int j = 0; j < rh; j++)
                for (int i = 0; i < rw; i++) {
                    float x = (i + 0.5f + signX * jx) * ratio, y = (j + 0.5f + signY * jy) * ratio;
                    float[] c = new float[3];
                    for (int sy = 0; sy < sup; sy++) for (int sx = 0; sx < sup; sx++) {
                        float[] q = fn.at(x + ((sx + 0.5f) / sup - 0.5f) * ratio, y + ((sy + 0.5f) / sup - 0.5f) * ratio);
                        c[0] += q[0]; c[1] += q[1]; c[2] += q[2];
                    }
                    int o = j * rw + i;
                    color[o * 4] = c[0] / (sup * sup); color[o * 4 + 1] = c[1] / (sup * sup); color[o * 4 + 2] = c[2] / (sup * sup); color[o * 4 + 3] = 1f;
                    depth[o] = 0.9f;
                    mv[o * 2] = 0f; mv[o * 2 + 1] = 0f;
                }
        };
    }

    static float[] reference(Fn fn, int n) {
        float[] g = new float[DW * DH * 4];
        for (int y = 0; y < DH; y++) for (int x = 0; x < DW; x++) {
            float r = 0, gg = 0, b = 0;
            for (int sy = 0; sy < n; sy++) for (int sx = 0; sx < n; sx++) {
                float[] c = fn.at(x + (sx + 0.5f) / n, y + (sy + 0.5f) / n);
                r += c[0]; gg += c[1]; b += c[2];
            }
            int o = (y * DW + x) * 4;
            g[o] = r / (n * n); g[o + 1] = gg / (n * n); g[o + 2] = b / (n * n); g[o + 3] = 1;
        }
        return g;
    }

    static boolean[] edgeBand(float halfWidth) {
        boolean[] m = new boolean[DW * DH];
        for (int y = 0; y < DH; y++) for (int x = 0; x < DW; x++) m[y * DW + x] = Math.abs(dist(x + 0.5f, y + 0.5f)) <= halfWidth;
        return m;
    }

    static double rmseMask(float[] a, float[] b, boolean[] m) {
        double s = 0; long n = 0;
        for (int i = 0; i < m.length; i++) if (m[i]) for (int c = 0; c < 3; c++) { double d = a[i * 4 + c] - b[i * 4 + c]; s += d * d; n++; }
        return Math.sqrt(s / n);
    }

    static double gradMask(float[] a, boolean[] m, double[] peak) {
        double s = 0; long n = 0; double pk = 0;
        for (int y = 0; y < DH - 1; y++) for (int x = 0; x < DW - 1; x++) if (m[y * DW + x]) {
            float l = lum(a, DW, x, y);
            double dx = lum(a, DW, x + 1, y) - l, dy = lum(a, DW, x, y + 1) - l, g2 = dx * dx + dy * dy;
            s += g2; n++; pk = Math.max(pk, Math.sqrt(g2));
        }
        if (peak != null) peak[0] = pk;
        return s / n;
    }

    /** ringing: worst excursion beyond the plateau levels within 4 px of the edge (luminance, % of the step), away from the staircase. */
    static double overshootPct(float[] a) {
        float lHi = lum(new float[]{HI, HI * 0.95f, HI * 0.9f, 1}, 1, 0, 0), lLo = lum(new float[]{LO, LO * 1.1f, LO * 1.2f, 1}, 1, 0, 0);
        double over = 0;
        for (int y = 0; y < DH; y++) for (int x = 0; x < DW; x++) {
            float d = dist(x + 0.5f, y + 0.5f);
            if (Math.abs(d) < 2f || Math.abs(d) > 5f) continue;
            float l = lum(a, DW, x, y);
            over = Math.max(over, d < 0 ? l - lHi : lLo - l);
        }
        return 100.0 * over / (lHi - lLo);
    }

    static double flicker(float[][] o, int f0, int f1, boolean[] mask, double[] max) {
        double s = 0; long n = 0; double mx = 0;
        for (int f = f0 + 1; f <= f1; f++)
            for (int i = 0; i < DW * DH; i++) if (mask == null || mask[i]) for (int c = 0; c < 3; c++) {
                double d = Math.abs(o[f][i * 4 + c] - o[f - 1][i * 4 + c]); s += d; n++; mx = Math.max(mx, d);
            }
        if (max != null) max[0] = mx;
        return s / n;
    }

    // ---------------------------------------------------------------- results kept for the baseline table
    record Edge(double[] rm, double[] dl, int conv, int convRmse, double fin, double sharp, double peak, double over, double flick, float[] last) {}

    static Edge staticEdge(float ratio, FsrTuning t) {
        FsrReplay.tuning = t;
        int rw = Math.round(DW / ratio), rh = Math.round(DH / ratio);
        RunResult r = runFsr(rw, rh, DW, DH, EDGE_FRAMES, staticScene(FsrMetrics::edgeRgb, ratio, true), false, force16, 0.1f, 1000f, false);
        FsrReplay.tuning = null;
        float[] ref = reference(FsrMetrics::edgeRgb, 8);
        boolean[] band = edgeBand(3f);
        double[] rm = new double[EDGE_FRAMES];
        for (int f = 0; f < EDGE_FRAMES; f++) rm[f] = rmseMask(r.outputs()[f], ref, band);
        double fin = 0, maxTail = 0;
        for (int f = 12; f < EDGE_FRAMES; f++) { fin += rm[f] / (EDGE_FRAMES - 12); maxTail = Math.max(maxTail, rm[f]); }
        // convergence: the error ripples with the jitter phase, so judge the average over one jitter cycle (trailing window,
        // partial at the start): converged = first frame after which that average stays within 1.10x of the steady state (frames 12..19)
        int cyc = JitterSequence.phaseCount(rw, DW);
        double[] cm = new double[EDGE_FRAMES];
        for (int f = 0; f < EDGE_FRAMES; f++) { int a0 = Math.max(0, f - cyc + 1); double s2 = 0; for (int g = a0; g <= f; g++) s2 += rm[g]; cm[f] = s2 / (f - a0 + 1); }
        double steady = 0;
        for (int f = 12; f < EDGE_FRAMES; f++) steady += cm[f] / (EDGE_FRAMES - 12);
        int conv = EDGE_FRAMES;
        for (int f = EDGE_FRAMES - 1; f >= 0 && cm[f] <= steady * 1.10; f--) conv = f;
        double[] dl = cm;
        int convR = 0;
        double[] pk = new double[1], pr = new double[1];
        double gOut = gradMask(r.outputs()[EDGE_FRAMES - 1], band, pk), gRef = gradMask(ref, band, pr);
        double fl = flicker(r.outputs(), 12, EDGE_FRAMES - 1, band, null);
        return new Edge(rm, dl, conv + 1, convR + 1, fin, gOut / gRef, pk[0] / pr[0], overshootPct(r.outputs()[EDGE_FRAMES - 1]), fl, r.outputs()[EDGE_FRAMES - 1]);
    }

    // ---------------------------------------------------------------- ghosting on FsrReplay's moving square scene
    static final int[] AGES = {1, 2, 4, 6, 10};

    record Ghost(double vacated, double control, double trailPx, double frac, double[] byAge) {
        double excess() { return vacated - control; }
        double excessAt(int i) { return byAge[i] - control; }
    }

    static Ghost ghost(boolean withMv, boolean flatDepth, FsrTuning t) {
        FsrReplay.tuning = t;
        FrameSource src = (tt, jx, jy, c, d, m) -> { SYNTH.render(tt, jx, jy, c, d, m); if (!withMv) java.util.Arrays.fill(m, 0f); if (flatDepth) java.util.Arrays.fill(d, 0.9f); };
        RunResult r = runFsr(320, 180, 640, 360, NF, src, false, force16, 0.1f, 1000f, false);
        FsrReplay.tuning = null;
        int[] ts = {12, 20, 28, LT};
        int[] ages = AGES;
        double[] byAge = new double[ages.length];
        double ctl = 0, frac = 0;
        for (int t0 : ts) {
            float[] gt = groundTruth(t0);
            for (int ai = 0; ai < ages.length; ai++) {
                int age = ages[ai];
                long n = 0, nb = 0; double s = 0;
                for (int y = 0; y < 360; y++) for (int x = 0; x < 640; x++) {
                    if (!inSq(x, y, t0 - age)) continue;
                    boolean again = false;
                    for (int q = t0 - age + 1; q <= t0 && !again; q++) again = inSq(x, y, q);
                    if (again) continue;
                    double e = absErr(r.outputs()[t0], gt, y * 640 + x); s += e; n++; if (e > 0.08) nb++;
                    }
                byAge[ai] += (s / Math.max(1, n)) / ts.length;
                if (age == 4) frac += (nb / (double) Math.max(1, n)) / ts.length;
            }
            double sc = 0; long nc = 0;
            for (int y = 0; y < 360; y++) for (int x = 480; x < 640; x++) { sc += absErr(r.outputs()[t0], gt, y * 640 + x); nc++; }
            ctl += (sc / nc) / ts.length;
        }
        double vac = byAge[2];
        // trail length at the last frame: columns behind the square (rows it covered through the whole run)
        float[] gt = groundTruth(LT);
        int sxl = (int) (100 + VX * LT), y0 = (int) (80 + VY * LT), y1 = (int) (80 + SQ);
        int trail = 0;
        for (int k = 1; k <= 120 && sxl - k >= 0; k++) {
            double s = 0; int n = 0;
            for (int y = y0; y < y1; y++) { s += absErr(r.outputs()[LT], gt, y * 640 + (sxl - k)); n++; }
            if (s / n > ctl + 0.03) trail = k;
        }
        return new Ghost(vac, ctl, trail, frac, byAge);
    }

    static boolean inSq(int x, int y, int t) {
        float sx = 100f + VX * t, sy = 80f + VY * t;
        return x >= sx && x < sx + SQ && y >= sy && y < sy + SQ;
    }

    static double absErr(float[] a, float[] b, int px) {
        return (Math.abs(a[px * 4] - b[px * 4]) + Math.abs(a[px * 4 + 1] - b[px * 4 + 1]) + Math.abs(a[px * 4 + 2] - b[px * 4 + 2])) / 3.0;
    }

    // ---------------------------------------------------------------- stability on a static scene
    record Stab(double whole, double whole2, double line, double lineMax) {}

    static Stab stability(float ratio, FsrTuning t) {
        FsrReplay.tuning = t;
        int rw = Math.round(DW / ratio), rh = Math.round(DH / ratio);
        RunResult r = runFsr(rw, rh, DW, DH, 32, staticScene(FsrMetrics::fenceRgb, ratio, true), false, force16, 0.1f, 1000f, false);
        FsrReplay.tuning = null;
        boolean[] lines = new boolean[DW * DH];
        for (int y = 0; y < DH; y++) for (int x = 0; x < DW; x++) { int m = x & 15; lines[y * DW + x] = m >= 6 && m <= 8; }
        double[] mx = new double[1];
        double fl = flicker(r.outputs(), 16, 31, lines, mx);
        return new Stab(flicker(r.outputs(), 16, 31, null, null), flicker(r.outputs(), 24, 31, null, null), fl, mx[0]);
    }

    static String f(double v) { return String.format(Locale.ROOT, "%.4f", v); }

    public static void main(String[] args) throws Exception {
        String dump = "build/fsrmetrics", sweep = null, combo = null;
        for (String a : args) {
            if (a.equals("--force16")) force16 = true;
            else if (a.startsWith("--dump=")) dump = a.substring(7);
            else if (a.startsWith("--sweep=")) sweep = a.substring(8);
            else if (a.startsWith("--combo=")) combo = a.substring(8);
            else if (a.startsWith("--ratio=")) {
                String[] rs = a.substring(8).split(",");
                sweepRatios = new float[rs.length]; sweepNames = new String[rs.length];
                for (int q = 0; q < rs.length; q++) { sweepRatios[q] = Float.parseFloat(rs[q]); sweepNames[q] = "ratio " + rs[q]; }
            }
            else if (a.startsWith("--rcas=")) rcasArg = Float.parseFloat(a.substring(7));
            else if (a.startsWith("--rcassweep=")) rcasSweep = a.substring(12);
            else if (a.equals("--reactive=sweep")) reactiveSweep = true;
        }
        StringBuilder rep = new StringBuilder();
        try {
            if (!NativeLoader.load()) throw new IllegalStateException("libiroh_fsr unavailable: " + NativeLoader.unavailableReason());
            init(force16, rep);
        } catch (Throwable t) {
            System.out.println(rep);
            System.out.println("# fsrmetrics FAIL setup: " + t);
            System.exit(1);
        }
        FsrReplay.rcas = rcasArg;
        System.out.println("device: " + deviceName + "  native: " + FsrNative.version() + (rcasArg > 0 ? "  RCAS " + rcasArg : ""));
        try {
            if (reactiveSweep) { FsrReactiveMetrics.sweep(); System.out.println("# fsrmetrics PASS (reactive sweep)"); System.exit(0); }
            if (rcasSweep != null) { runRcasSweep(rcasSweep); System.out.println("# fsrmetrics PASS (rcas sweep)"); System.exit(0); }
            if (sweep != null || combo != null) { runSweep(sweep, combo); System.out.println("# fsrmetrics PASS (sweep)"); System.exit(0); }
            run(dump);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.println("# fsrmetrics FAIL " + t);
            System.exit(1);
        }
        FsrReactiveMetrics.section();   // FSR-Q6/Q7: reactive mask modes on ghost-trail scenes
        System.out.println("# fsrmetrics " + (fails == 0 ? "PASS" : "FAIL " + fails + " checks"));
        System.exit(fails == 0 ? 0 : 1);
    }

    static void run(String dump) {
        // aliased native baseline (what a native render without any upscaler gives), same band
        float[] ref = reference(FsrMetrics::edgeRgb, 8);
        boolean[] band = edgeBand(3f);
        float[] alias = new float[DW * DH * 4];
        staticScene(FsrMetrics::edgeRgb, 1f, true).render(0, 0, 0, alias, new float[DW * DH], new float[DW * DH * 2]);
        double aliasRm = rmseMask(alias, ref, band);
        float[] ssaa4 = reference(FsrMetrics::edgeRgb, 4);
        System.out.println("\n== 1+5. static edge (diagonal block edge, 360x180 display, edge band +-3 px), " + EDGE_FRAMES + " frames");
        System.out.println("edge RMSE vs 8x8 SSAA reference: aliased native (no upscaler) " + f(aliasRm) + " | 4x4 SSAA " + f(rmseMask(ssaa4, ref, band)) + " | edge step " + f(HI - LO));
        System.out.println("mode          ratio  conv@   edgeRMSE(final)  rel.err%  vsAliased  sharpness(E)  peakGrad  overshoot%  edgeFlicker");
        float[] ratios = {1f, 1.5f, 2f};
        String[] names = {"Native AA", "Quality 1.5x", "Performance 2x"};
        Edge[] ed = new Edge[3];
        for (int i = 0; i < 3; i++) {
            Edge e = staticEdge(ratios[i], null);
            ed[i] = e;
            System.out.printf(Locale.ROOT, "%-13s %.1f   %2d     %s          %5.1f     %.2f       %.3f         %.3f     %5.1f       %s%n",
                    names[i], ratios[i], e.conv, f(e.fin), 100 * e.fin / (HI - LO), e.fin / aliasRm, e.sharp, e.peak, e.over, f(e.flick));
            System.out.println("   per-frame edge RMSE: " + perFrame(e.rm));
            System.out.println("   cycle-averaged edge RMSE: " + perFrame(e.dl));
        }
        ck(finite(ed[0].last), "native AA output finite");
        // plan quality target (a): converge within 8 frames at Native AA. Reported, not gated: the baseline misses it (see knowledge/fsr.md)
        System.out.println((ed[0].conv <= 8 ? "  TARGET MET  " : "  TARGET MISSED ") + "(plan a) Native AA cycle-averaged edge error within 10% of steady state after " + ed[0].conv + " frames (target <= 8)");
        ck(ed[0].fin < aliasRm, "FSR Native AA edge is closer to the SSAA reference than the aliased native image (" + f(ed[0].fin) + " < " + f(aliasRm) + ")");
        ck(ed[0].conv <= 16 && ed[1].conv <= 10 && ed[2].conv <= 9, "regression bar: convergence frames Native/1.5x/2x <= 16/10/9 (got " + ed[0].conv + "/" + ed[1].conv + "/" + ed[2].conv + ")");
        ck(ed[0].fin <= 0.035 && ed[1].fin <= 0.070 && ed[2].fin <= 0.095, "regression bar: final edge RMSE Native/1.5x/2x <= 0.035/0.070/0.095 (got " + f(ed[0].fin) + "/" + f(ed[1].fin) + "/" + f(ed[2].fin) + ")");
        ck(ed[0].sharp >= 0.70 && ed[1].sharp >= 0.68 && ed[2].sharp >= 0.45, "regression bar: edge sharpness (gradient energy vs reference) Native/1.5x/2x >= 0.70/0.68/0.45 (got " + String.format(Locale.ROOT, "%.3f/%.3f/%.3f", ed[0].sharp, ed[1].sharp, ed[2].sharp) + ")");
        File dd = new File(dump); dd.mkdirs();
        png(ed[0].last, DW, DH, new File(dd, "edge_native_last.png")); png(ref, DW, DH, new File(dd, "edge_reference.png")); png(alias, DW, DH, new File(dd, "edge_aliased.png"));

        System.out.println("\n== 2. jitter polarity (scene sign vs the dispatch jitter; shipped = -1,-1), final-frame edge RMSE and sharpness");
        System.out.println("ratio  scene(x,y)   edgeRMSE  vsShipped  sharpness(E)  verdict");
        for (float ratio : new float[]{1f, 2f}) {
            double[] rms = new double[4]; double[] sh = new double[4];
            float[][] sg = {{-1, -1}, {1, 1}, {1, -1}, {-1, 1}};
            for (int k = 0; k < 4; k++) {
                signX = sg[k][0]; signY = sg[k][1];
                Edge e = k == 0 ? (ratio == 1f ? ed[0] : ed[2]) : staticEdge(ratio, null);
                rms[k] = e.fin; sh[k] = e.sharp;
            }
            signX = -1; signY = -1;
            for (int k = 0; k < 4; k++) {
                String v = k == 0 ? "shipped (sharpens)" : (rms[k] > rms[0] * 1.15 ? (sh[k] < sh[0] * 0.95 ? "blurs/doubles" : "wrong: worse RMSE") : "NOT WORSE");
                System.out.printf(Locale.ROOT, "%.0fx   (%+.0f,%+.0f)      %s    %.2f      %.3f        %s%n", ratio, sg[k][0], sg[k][1], f(rms[k]), rms[k] / rms[0], sh[k], v);
                if (k > 0) ck(rms[k] > rms[0], "ratio " + ratio + " scene sign (" + sg[k][0] + "," + sg[k][1] + ") is worse than the shipped convention (" + f(rms[k]) + " vs " + f(rms[0]) + ")");
                if (k == 1 || ratio == 1f && k > 0) ck(rms[k] > rms[0] * 1.15, "ratio " + ratio + " scene sign (" + sg[k][0] + "," + sg[k][1] + ") is worse by > 15% (" + f(rms[k]) + " vs " + f(rms[0]) + ")");
            }
        }

        System.out.println("\n== 3. ghosting (FsrReplay moving square 6,2 px/frame over textured background, Performance 2x, 40 frames)");
        Ghost[] gs = {ghost(true, false, null), ghost(false, false, null), ghost(true, true, null), ghost(false, true, null)};
        String[] gn = {"depth + MVs", "depth, zero MVs", "flat depth + MVs", "flat depth, zero MVs"};
        System.out.println("excess error (vs the static control region) in pixels the square left N frames ago; control = mean abs error of the untouched background");
        System.out.println("scenario              control   age1     age2     age4     age6     age10    px>0.08@4  trail px (err > control+0.03, last frame)");
        for (int i = 0; i < 4; i++) {
            System.out.printf(Locale.ROOT, "%-21s %s", gn[i], f(gs[i].control));
            for (int k = 0; k < AGES.length; k++) System.out.printf(Locale.ROOT, "   %s", f(gs[i].excessAt(k)));
            System.out.printf(Locale.ROOT, "   %5.2f%%     %.0f%n", 100 * gs[i].frac, gs[i].trailPx);
        }
        Ghost gm = gs[0];
        ck(gm.excessAt(0) <= 0.020 && gm.excessAt(2) <= 0.004, "regression bar: ghost excess with depth + MVs, age 1 <= 0.020, age 4 <= 0.004 (got " + f(gm.excessAt(0)) + ", " + f(gm.excessAt(2)) + ")");
        ck(gm.trailPx <= 4, "regression bar: ghost trail with depth + MVs <= 4 px at the last frame (got " + (int) gm.trailPx + ")");
        ck(gm.excessAt(0) < gs[1].excessAt(0), "motion vectors reduce the age-1 ghost where depth already helps (" + f(gm.excessAt(0)) + " < " + f(gs[1].excessAt(0)) + ")");

        System.out.println("\n== 4. temporal stability, static scene with 1 px fence lines every 16 px (mean |frame N - N-1|, frames 16..31)");
        System.out.println("ratio  whole(16-31)  whole(24-31)  fence-lines mean  fence-lines max");
        double[] stabLine = new double[2];
        float[] sr = {1f, 2f};
        for (int i = 0; i < 2; i++) {
            Stab s = stability(sr[i], null);
            stabLine[i] = s.line;
            System.out.printf(Locale.ROOT, "%.0fx     %s        %s        %s            %s%n", sr[i], f(s.whole), f(s.whole2), f(s.line), f(s.lineMax));
            ck(s.whole <= 0.001 && s.line <= 0.004, "regression bar: static fence scene flicker at " + sr[i] + "x whole <= 0.001, fence lines <= 0.004 (got " + f(s.whole) + ", " + f(s.line) + ")");
        }
        System.out.println("\n== 5b. RCAS response at Native AA (UpscaleInputs.sharpness), final-frame edge band");
        System.out.println("rcas   edgeRMSE  sharpness(E)  peakGrad  overshoot%");
        for (float rc : new float[]{0f, 0.1f, 0.25f, 0.5f, 1f}) {
            FsrReplay.rcas = rc;
            Edge e = staticEdge(1f, null);
            System.out.printf(Locale.ROOT, "%.2f   %s    %.3f         %.3f     %5.1f%n", rc, f(e.fin), e.sharp, e.peak, e.over);
        }
        FsrReplay.rcas = rcasArg;
        ck(ed[0].flick <= 0.006 && ed[2].flick <= 0.010, "regression bar: static edge flicker Native AA <= 0.006, 2x <= 0.010 (got " + f(ed[0].flick) + ", " + f(ed[2].flick) + ")");
        ck(ed[0].over <= 2.0, "regression bar: edge overshoot without RCAS <= 2% of the step (got " + String.format(Locale.ROOT, "%.1f", ed[0].over) + ")");
    }

    static String perFrame(double[] rm) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 16; i++) b.append(f(rm[i])).append(i < 15 ? " " : "");
        return b.toString();
    }

    static float[] sweepRatios = {1f, 1.5f, 1.7f, 2f};
    static String[] sweepNames = {"Native AA", "Quality 1.5x", "Balanced 1.7x", "Performance 2x"};

    /**
     * --sweep=KEY:v1,v2,..[;KEY2:w1,..] (each key swept alone) and --combo=KEY=v,KEY=v|KEY=v (one row per '|' item): Q3 sweep.
     * Per mode (--ratio=1,1.5 narrows) and row: static edge (RMSE final, convergence frames, sharpness, edge flicker),
     * fence-line + whole-image flicker, and the 2x ghost excess at age 1/2/4 with MVs, flat-depth age 1/2, trail px.
     */
    static void runSweep(String sweepSpec, String comboSpec) {
        java.util.LinkedHashMap<String, FsrTuning> rows = new java.util.LinkedHashMap<>();
        rows.put("default", new FsrTuning());
        if (sweepSpec != null) for (String seg : sweepSpec.split(";")) {
            String[] kv = seg.split(":");
            FsrTuning.Key key = FsrTuning.Key.valueOf(kv[0]);
            for (String v : kv[1].split(","))
                rows.put(key + "=" + v, new FsrTuning().set(key, Float.parseFloat(v)));
        }
        if (comboSpec != null) for (String c : comboSpec.split("\\|")) {
            FsrTuning t = new FsrTuning();
            for (String a : c.split(",")) { String[] kv = a.split("="); t.set(FsrTuning.Key.valueOf(kv[0]), Float.parseFloat(kv[1])); }
            rows.put(c.replace("VELOCITY_FACTOR", "VF").replace("REACTIVENESS_SCALE", "RS").replace("SHADING_CHANGE_SCALE", "SC")
                    .replace("ACCUMULATION_ADDED_PER_FRAME", "AA").replace("MIN_DISOCCLUSION_ACCUMULATION", "MD"), t);
        }
        for (int m = 0; m < sweepRatios.length; m++) {
            System.out.println("\n== sweep, " + sweepNames[m] + " (ratio " + sweepRatios[m] + "): edge/fence at this ratio");
            System.out.println("row                                         edgeRMSE  conv  sharp  edgeFlick  fenceLine  fenceWhole");
            for (var e : rows.entrySet()) {
                Edge ed = staticEdge(sweepRatios[m], e.getValue());
                Stab s = stability(sweepRatios[m], e.getValue());
                System.out.printf(Locale.ROOT, "%-42s  %s    %2d   %.3f   %s     %s     %s%n", e.getKey(), f(ed.fin), ed.conv, ed.sharp, f(ed.flick), f(s.line), f(s.whole));
            }
        }
        System.out.println("\n== sweep, ghost (moving square, 2x, fixed scene size): excess err at age 1 / 2 / 4 with MVs, flat depth age 1 / 2, trail px");
        System.out.println("row                                         age1     age2     age4     flatAge1 flatAge2 trail  flatTrail");
        for (var e : rows.entrySet()) {
            Ghost g = ghost(true, false, e.getValue()), gf = ghost(true, true, e.getValue());
            System.out.printf(Locale.ROOT, "%-42s  %s  %s  %s  %s  %s  %3.0f   %3.0f%n", e.getKey(), f(g.excessAt(0)), f(g.excessAt(1)), f(g.excessAt(2)), f(gf.excessAt(0)), f(gf.excessAt(1)), g.trailPx, gf.trailPx);
        }
    }

    static long nanCount(float[][] outs) {
        long n = 0;
        for (float[] o : outs) for (float v : o) if (Float.isNaN(v) || Float.isInfinite(v) || v < -0.001f) n++;
        return n;
    }

    /**
     * FSR-Q4: --rcassweep=0,0.1,0.2,.. per mode (--ratio narrows): RCAS strength vs edge RMSE, sharpness (gradient energy vs the
     * SSAA reference), overshoot (ringing % of the step, luminance beyond the plateaus within 2..5 px of the edge), edge-band
     * flicker (frames 12..19), fence-line flicker, and the count of NaN / negative output samples (the "clamp negative colours" question).
     */
    static void runRcasSweep(String spec) {
        String[] vs = spec.split(",");
        for (int m = 0; m < sweepRatios.length; m++) {
            System.out.println("\n== RCAS sweep, " + sweepNames[m] + " (ratio " + sweepRatios[m] + ")");
            System.out.println("rcas   edgeRMSE  vsOff   sharp  peakGrad  overshoot%  edgeFlick  fenceLine  fenceWhole  badSamples");
            double base = 0;
            for (String v : vs) {
                float rc = Float.parseFloat(v);
                FsrReplay.rcas = rc;
                Edge ed = staticEdge(sweepRatios[m], null);
                Stab s = stability(sweepRatios[m], null);
                if (rc == 0f) base = ed.fin;
                System.out.printf(Locale.ROOT, "%.2f   %s    %.3f   %.3f   %.3f     %5.2f      %s     %s     %s     %d%n", rc, f(ed.fin), base > 0 ? ed.fin / base : 1.0, ed.sharp, ed.peak, ed.over, f(ed.flick), f(s.line), f(s.whole), nanCount(new float[][]{ed.last}));
            }
        }
        FsrReplay.rcas = rcasArg;
    }
}
