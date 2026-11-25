package dev.elemore.iroh.upscaletest;

import dev.elemore.iroh.upscale.VoxelMask;
import dev.elemore.iroh.upscale.fsr.ReactiveGen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.elemore.iroh.upscaletest.FsrReplay.*;

/**
 * FSR-Q6 / FSR-Q7: ghost-trail scenes for the reactive mask modes (lavapipe, synthetic, no game). Section 6 of fsrMetrics.
 * <pre>
 * water     an opaque checker square moves (6,2 px/frame, with motion vectors) UNDER a static translucent water rectangle
 *           (depth in front, motion vector 0): the square seen through the water leaves a trail          class A
 * particles 40 bright 6x6 px translucent particles fall 9 px/frame over a static textured background (no motion vectors) class A
 * rain      60 thin 2x18 px translucent streaks fall 16 px/frame                                                         class B
 * lava      an opaque animated region (texture frame changes every 2 frames, no depth difference, no motion): error vs
 *           the frame's own ground truth                                                                                 sprite
 * </pre>
 * Modes: none (no reactive, no T&amp;C), iroh (the pre-Q6 own colour-difference pass, CPU twin of velocity.comp), auto (the SDK's
 * GenerateReactiveMask on the GPU, composed like velocity.comp F_SDK), voxel (VoxelMask classes from the depth phases the
 * production path has; lava is class OTHER there = no information), voxel+sprites (lava additionally flagged: needs a per-fragment
 * source that is NOT built). Metric: mean abs RGB error vs the 2x2-supersampled truth of the same frame, in the pixels a
 * moving element left N frames ago (excess over the pixels it never touched); for lava the error over the animated region minus
 * the control.
 */
final class FsrReactiveMetrics {
    static final int RW = 320, RH = 180, DW = 640, DH = 360;
    static final float R = 2f;
    static final int[] AGES = {1, 2, 4};
    static final int[] TS = {12, 20, 28, 39};

    /** one evaluated scene point */
    static final class Px {
        float[] opq = new float[3], fin = new float[3];
        float od, ph, sd, mvx, mvy;
        boolean sprite;
    }

    interface Scene {
        String name();
        void at(float x, float y, int t, Px p);
        /** a moving/animated element covers this display pixel at frame t */
        boolean dyn(int x, int y, int t);
        boolean roi(int x, int y);
        boolean animated();   // lava style (error over region) instead of vacated-by-N
    }

    static float[] bg(float x, float y) {
        float u = x / 640f, v = y / 360f;
        float base = 0.25f + 0.35f * u + 0.15f * v;
        boolean cell = ((int) Math.floor(x / 4f) + (int) Math.floor(y / 4f) & 1) == 0;
        float k = cell ? 0.12f : 0f;
        return new float[]{base + k, base * 0.9f + k, base * 0.7f + k};
    }

    static void mix(float[] dst, float[] a, float[] b, float alpha) {
        for (int i = 0; i < 3; i++) dst[i] = a[i] * (1 - alpha) + b[i] * alpha;
    }

    // ---------------------------------------------------------------- scenes
    static final class Water implements Scene {
        static final int X0 = 60, X1 = 560, Y0 = 40, Y1 = 320;
        static final float ALPHA = 0.45f;
        static final float[] TINT = {0.10f, 0.30f, 0.70f};
        public String name() { return "water over a moving square"; }
        public boolean animated() { return false; }
        public boolean roi(int x, int y) { return x >= X0 && x < X1 && y >= Y0 && y < Y1; }
        public boolean dyn(int x, int y, int t) { return FsrMetrics.inSq(x, y, t); }
        public void at(float x, float y, int t, Px p) {
            boolean[] on = new boolean[1];
            float[] c = sceneRgb(x, y, t, on);
            p.opq = c; p.od = on[0] ? 0.5f : 0.9f;
            p.mvx = on[0] ? -VX / R : 0f; p.mvy = on[0] ? -VY / R : 0f;
            boolean water = x >= X0 && x < X1 && y >= Y0 && y < Y1;
            if (water) { mix(p.fin, c, TINT, ALPHA); p.ph = p.sd = 0.3f; p.mvx = p.mvy = 0f; }
            else { p.fin = c; p.ph = p.sd = p.od; }
        }
    }

    /** falling translucent elements over the static background: w x h display px, speed px/frame, count, alpha, depth */
    static final class Falling implements Scene {
        final String name; final int n, w, h, speed; final float alpha, depth; final float[] col; final boolean bandB;
        final int[] px, py;
        Falling(String name, int n, int w, int h, int speed, float alpha, float depth, float[] col, boolean bandB) {
            this.name = name; this.n = n; this.w = w; this.h = h; this.speed = speed; this.alpha = alpha; this.depth = depth; this.col = col; this.bandB = bandB;
            px = new int[n]; py = new int[n];
            long s = 12345 + n;
            for (int i = 0; i < n; i++) { s = s * 6364136223846793005L + 1442695040888963407L; px[i] = 20 + (int) ((s >>> 33) % 600); s = s * 6364136223846793005L + 1442695040888963407L; py[i] = (int) ((s >>> 33) % 360); }
        }
        public String name() { return name; }
        public boolean animated() { return false; }
        public boolean roi(int x, int y) { return true; }
        boolean cover(int x, int y, int t) {
            for (int i = 0; i < n; i++) {
                int yy = Math.floorMod(py[i] + speed * t, 360);
                if (x >= px[i] && x < px[i] + w && ((y - yy + 360) % 360) < h) return true;
            }
            return false;
        }
        public boolean dyn(int x, int y, int t) { return cover(x, y, t); }
        public void at(float x, float y, int t, Px p) {
            float[] c = bg(x, y);
            p.opq = c; p.od = 0.9f; p.mvx = p.mvy = 0f;
            if (cover((int) Math.floor(x), (int) Math.floor(y), t)) { mix(p.fin, c, col, alpha); p.sd = depth; p.ph = bandB ? 0.9f : depth; }
            else { p.fin = c; p.sd = p.ph = 0.9f; }
        }
    }

    static final class Lava implements Scene {
        static final int X0 = 200, X1 = 440, Y0 = 120, Y1 = 260;
        public String name() { return "lava (animated sprite)"; }
        public boolean animated() { return true; }
        public boolean roi(int x, int y) { return x >= X0 && x < X1 && y >= Y0 && y < Y1; }
        public boolean dyn(int x, int y, int t) { return roi(x, y); }
        public void at(float x, float y, int t, Px p) {
            float[] c = bg(x, y);
            p.od = p.ph = p.sd = 0.9f; p.mvx = p.mvy = 0f;
            if (x >= X0 && x < X1 && y >= Y0 && y < Y1) {
                int cx = (int) Math.floor((x - X0) / 12f), cy = (int) Math.floor((y - Y0) / 12f), k = t / 2;
                int h = (cx * 73856093) ^ (cy * 19349663) ^ (k * 83492791);
                h ^= h >>> 13; h *= 0x5bd1e995; h ^= h >>> 15;
                float v = (h & 255) / 255f;
                c = new float[]{0.75f + 0.25f * v, 0.20f + 0.50f * v, 0.04f + 0.10f * v};
                p.sprite = true;
            }
            p.opq = c; p.fin = c;
        }
    }

    // ---------------------------------------------------------------- modes
    enum Mode { NONE("none"), IROH("iroh (own diff)"), AUTO("auto (SDK pass)"), VOXEL("voxel"), VOXEL_SPRITES("voxel+sprites*");
        final String label; Mode(String l) { label = l; } }

    /** every per-frame array of a scene, computed once (jitter sequence is the same for every mode) */
    record Frames(float[][] color, float[][] opq, float[][] depth, float[][] mv, float[][] od, float[][] ph, boolean[][] sprite) {}

    static Frames build(Scene s, JitterSeq js) {
        int n = RW * RH;
        Frames f = new Frames(new float[NF][n * 4], new float[NF][n * 4], new float[NF][n], new float[NF][n * 2], new float[NF][n], new float[NF][n], new boolean[NF][n]);
        Px px = new Px(), q = new Px();
        for (int t = 0; t < NF; t++) {
            float[] j = js.offset(t);
            for (int jj = 0; jj < RH; jj++) for (int ii = 0; ii < RW; ii++) {
                float x = (ii + 0.5f + JSX * j[0]) * R, y = (jj + 0.5f + JSY * j[1]) * R;
                s.at(x, y, t, px);
                float[] fc = new float[3], oc = new float[3];
                int sup = SUPER;
                for (int sy = 0; sy < sup; sy++) for (int sx = 0; sx < sup; sx++) {
                    s.at(x + ((sx + 0.5f) / sup - 0.5f) * R, y + ((sy + 0.5f) / sup - 0.5f) * R, t, q);
                    for (int c = 0; c < 3; c++) { fc[c] += q.fin[c]; oc[c] += q.opq[c]; }
                }
                int o = jj * RW + ii;
                for (int c = 0; c < 3; c++) { f.color()[t][o * 4 + c] = fc[c] / (sup * sup); f.opq()[t][o * 4 + c] = oc[c] / (sup * sup); }
                f.color()[t][o * 4 + 3] = 1f; f.opq()[t][o * 4 + 3] = 1f;
                f.depth()[t][o] = px.sd; f.od()[t][o] = px.od; f.ph()[t][o] = px.ph;
                f.mv()[t][o * 2] = px.mvx; f.mv()[t][o * 2 + 1] = px.mvy;
                f.sprite()[t][o] = px.sprite;
            }
        }
        return f;
    }

    interface JitterSeq { float[] offset(int t); }

    static float[] truth(Scene s, int t) {
        float[] g = new float[DW * DH * 4];
        Px q = new Px();
        for (int y = 0; y < DH; y++) for (int x = 0; x < DW; x++) {
            float[] a = new float[3];
            for (int sy = 0; sy < 2; sy++) for (int sx = 0; sx < 2; sx++) { s.at(x + 0.25f + 0.5f * sx, y + 0.25f + 0.5f * sy, t, q); for (int c = 0; c < 3; c++) a[c] += q.fin[c] / 4f; }
            g[(y * DW + x) * 4] = a[0]; g[(y * DW + x) * 4 + 1] = a[1]; g[(y * DW + x) * 4 + 2] = a[2]; g[(y * DW + x) * 4 + 3] = 1f;
        }
        return g;
    }

    /** the value set of one mode, so sweeps can vary it */
    record Cfg(Mode mode, ReactiveGen gen, VoxelMask.Params vox, float irohScale, float irohMax, float irohFront, float tcFront) {
        static Cfg of(Mode m) { return new Cfg(m, ReactiveGen.DEFAULT, VoxelMask.Params.DEFAULT, 4f, 0.9f, 0.5f, 0.8f); }
        Cfg gen(ReactiveGen g) { return new Cfg(mode, g, vox, irohScale, irohMax, irohFront, tcFront); }
        Cfg vox(VoxelMask.Params v) { return new Cfg(mode, gen, v, irohScale, irohMax, irohFront, tcFront); }
    }

    static byte b8(float v) { return (byte) Math.max(0, Math.min(255, Math.round(v * 255f))); }

    record Result(double[] excess, double control, double fracBad) {}

    static Result run(Scene s, Frames f, Cfg c) {
        FsrReplay.tuning = null;
        final boolean[] first = {true};
        FrameSource src = (t, jx, jy, color, depth, mv) -> {
            System.arraycopy(f.color()[t], 0, color, 0, color.length);
            System.arraycopy(f.depth()[t], 0, depth, 0, depth.length);
            System.arraycopy(f.mv()[t], 0, mv, 0, mv.length);
        };
        if (c.mode() == Mode.NONE) { maskHook = null; genParams = null; compose = null; }
        else {
            maskHook = (t, opaque, rb, tb) -> {
                System.arraycopy(f.opq()[t], 0, opaque, 0, opaque.length);
                float[] col = f.color()[t];
                for (int i = 0; i < RW * RH; i++) {
                    float od = f.od()[t][i], sd = f.depth()[t][i], ph = f.ph()[t][i];
                    boolean front = sd < od - 1e-7f;
                    float dm = Math.max(Math.abs(col[i * 4] - opaque[i * 4]), Math.max(Math.abs(col[i * 4 + 1] - opaque[i * 4 + 1]), Math.abs(col[i * 4 + 2] - opaque[i * 4 + 2])));
                    float r, tc = 0f;
                    switch (c.mode()) {
                        case IROH, AUTO -> {   // AUTO's reactive is replaced by the SDK output in compose; T&C is the iroh rule for both
                            r = Math.min(c.irohMax(), c.irohScale() * dm) * (front ? c.irohFront() : 1f);
                            if (front) tc = c.tcFront();
                        }
                        default -> {
                            VoxelMask.Cls cl = VoxelMask.classify(od, ph, sd);
                            r = VoxelMask.reactive(c.vox(), cl, dm); tc = VoxelMask.tc(c.vox(), cl);
                            if (c.mode() == Mode.VOXEL_SPRITES && f.sprite()[t][i]) { r = 0f; tc = Math.max(tc, SPRITE_TC); }
                        }
                    }
                    rb[i] = b8(r); tb[i] = b8(tc);
                }
            };
            if (c.mode() == Mode.AUTO) {
                genParams = c.gen();
                compose = (t, sdk, rb) -> {
                    for (int i = 0; i < sdk.length; i++) {
                        boolean front = f.depth()[t][i] < f.od()[t][i] - 1e-7f;
                        float v = Float.isFinite(sdk[i]) ? Math.min(c.irohMax(), Math.max(0f, sdk[i])) : 0f;
                        rb[i] = b8(front ? v * c.irohFront() : v);
                    }
                };
            } else { genParams = null; compose = null; }
        }
        releaseRuns = true;
        RunResult r;
        try { r = runFsr(RW, RH, DW, DH, NF, src, false, FsrMetrics.force16, 0.1f, 1000f, false); } finally { releaseRuns = false; }
        maskHook = null; genParams = null; compose = null;
        boolean[] ever = new boolean[DW * DH];
        for (int y = 0; y < DH; y++) for (int x = 0; x < DW; x++)
            for (int t = 0; t < NF && !ever[y * DW + x]; t++) ever[y * DW + x] = s.dyn(x, y, t);
        double[] by = new double[AGES.length];
        double ctl = 0, bad = 0;
        for (int t0 : TS) {
            float[] gt = truth(s, t0);
            double sc = 0; long nc = 0;
            for (int y = 0; y < DH; y++) for (int x = 0; x < DW; x++)
                if (s.roi(x, y) && !ever[y * DW + x]) { sc += FsrMetrics.absErr(r.outputs()[t0], gt, y * DW + x); nc++; }
            ctl += sc / Math.max(1, nc) / TS.length;
            if (s.animated()) {
                double se = 0; long ne = 0;
                for (int y = 0; y < DH; y++) for (int x = 0; x < DW; x++) if (s.dyn(x, y, t0)) { se += FsrMetrics.absErr(r.outputs()[t0], gt, y * DW + x); ne++; }
                by[0] += se / Math.max(1, ne) / TS.length;
                continue;
            }
            for (int ai = 0; ai < AGES.length; ai++) {
                int age = AGES[ai];
                double sv = 0; long n = 0, nb = 0;
                for (int y = 0; y < DH; y++) for (int x = 0; x < DW; x++) {
                    if (!s.roi(x, y) || !s.dyn(x, y, t0 - age)) continue;
                    boolean again = false;
                    for (int q = t0 - age + 1; q <= t0 && !again; q++) again = s.dyn(x, y, q);
                    if (again) continue;
                    double e = FsrMetrics.absErr(r.outputs()[t0], gt, y * DW + x); sv += e; n++; if (e > 0.08) nb++;
                }
                by[ai] += sv / Math.max(1, n) / TS.length;
                if (age == 4) bad += nb / (double) Math.max(1, n) / TS.length;
            }
        }
        double[] ex = new double[AGES.length];
        for (int i = 0; i < ex.length; i++) ex[i] = by[i] - ctl;
        if (s.animated()) ex[1] = ex[2] = Double.NaN;
        return new Result(ex, ctl, bad);
    }

    static final float SPRITE_TC = 0.8f;

    static String f(double v) { return Double.isNaN(v) ? "   -  " : String.format(Locale.ROOT, "%+.4f", v); }

    /** the mode table of section 6 plus the gate; returns the aggregate ghost score per mode (sum of age-1 and age-2 excess over water, particles, rain; lava error excess separately) */
    static void section() {
        System.out.println("\n== 6. reactive mask modes: ghost-trail scenes (320x180 -> 640x360 Performance 2x, 40 frames; excess abs error vs the untouched control; lower = less ghost)");
        System.out.println("   (* voxel+sprites needs a per-fragment source for animated atlas sprites that is NOT built; shown to size the follow-up)");
        JitterSeq js = jitterSeq();
        Scene[] scenes = {new Water(),
                new Falling("particles (band A)", 40, 6, 6, 9, 0.75f, 0.2f, new float[]{0.95f, 0.95f, 1.0f}, false),
                new Falling("rain streaks (band B)", 60, 2, 18, 16, 0.5f, 0.15f, new float[]{0.70f, 0.75f, 0.90f}, true),
                new Lava()};
        Mode[] modes = Mode.values();
        double[][] score = new double[modes.length][scenes.length];
        for (int si = 0; si < scenes.length; si++) {
            Scene s = scenes[si];
            Frames fr = build(s, js);
            System.out.println("-- " + s.name() + (s.animated() ? "  (column age1 = error excess over the animated region, frames 12/20/28/39)" : ""));
            System.out.println("   mode               control   age1     age2     age4     px>0.08@4");
            for (int mi = 0; mi < modes.length; mi++) {
                Mode m = modes[mi];
                if (m == Mode.VOXEL_SPRITES && !s.animated()) { score[mi][si] = score[Mode.VOXEL.ordinal()][si]; continue; }
                Result r = run(s, fr, Cfg.of(m));
                score[mi][si] = s.animated() ? r.excess()[0] : r.excess()[0] + r.excess()[1];
                System.out.printf(Locale.ROOT, "   %-17s  %s  %s  %s  %s   %5.2f%%%n", m.label, FsrMetrics.f(r.control()), f(r.excess()[0]), f(r.excess()[1]), f(r.excess()[2]), 100 * r.fracBad());
            }
        }
        System.out.println("-- summary: ghost score = age1 + age2 excess (water + particles + rain) | lava error excess");
        for (int mi = 0; mi < modes.length; mi++) {
            double g = score[mi][0] + score[mi][1] + score[mi][2];
            System.out.printf(Locale.ROOT, "   %-17s ghost %s  lava %s%n", modes[mi].label, f(g), f(score[mi][3]));
        }
        SCORES = score;
        ck(allFinite(score), "reactive modes: every metric finite");
        double none = score[0][0] + score[0][1] + score[0][2];
        double iroh = score[1][0] + score[1][1] + score[1][2], auto = score[2][0] + score[2][1] + score[2][2];
        ck(iroh < none, "reactive/T&C masks reduce the translucent-over-motion ghost vs none (" + f(iroh) + " < " + f(none) + ")");
        ck(Math.abs(auto - iroh) < 0.25 * Math.max(Math.abs(none - iroh), 1e-3) + 0.01,
                "SDK auto mask (scale 4, components max) matches the own pass: score " + f(auto) + " vs " + f(iroh));
    }

    static double[][] SCORES;

    static boolean allFinite(double[][] a) {
        for (double[] r : a) for (double v : r) if (!Double.isFinite(v)) return false;
        return true;
    }

    static JitterSeq jitterSeq() {
        dev.elemore.iroh.upscale.JitterSequence js = new dev.elemore.iroh.upscale.JitterSequence(RW, DW);
        return js::offset;
    }

    /** --reactive=sweep: auto parameter variants and voxel class variants (Q6 / Q7 default picking) */
    static void sweep() {
        JitterSeq js = jitterSeq();
        Scene[] scenes = {new Water(),
                new Falling("particles", 40, 6, 6, 9, 0.75f, 0.2f, new float[]{0.95f, 0.95f, 1.0f}, false),
                new Falling("rain", 60, 2, 18, 16, 0.5f, 0.15f, new float[]{0.70f, 0.75f, 0.90f}, true),
                new Lava()};
        Frames[] frs = new Frames[scenes.length];
        for (int i = 0; i < scenes.length; i++) frs[i] = build(scenes[i], js);
        System.out.println("\n== reactive sweep: score per scene = age1 + age2 excess (lava: error excess); sum over the three ghost scenes");
        System.out.println("   variant                                       water    particles rain     |sum     lava");
        List<String> names = new ArrayList<>(); List<Cfg> cfgs = new ArrayList<>();
        names.add("none"); cfgs.add(Cfg.of(Mode.NONE));
        names.add("iroh scale4 max.9 front.5 tc.8"); cfgs.add(Cfg.of(Mode.IROH));
        for (float fw : new float[]{0f, 0.25f, 1f}) { names.add("iroh front weight " + fw); cfgs.add(new Cfg(Mode.IROH, ReactiveGen.DEFAULT, VoxelMask.Params.DEFAULT, 4f, 0.9f, fw, 0.8f)); }
        for (float tc : new float[]{0f, 0.4f, 1f}) { names.add("iroh tc " + tc); cfgs.add(new Cfg(Mode.IROH, ReactiveGen.DEFAULT, VoxelMask.Params.DEFAULT, 4f, 0.9f, 0.5f, tc)); }
        for (float sc : new float[]{1f, 2f, 8f}) { names.add("iroh scale " + sc); cfgs.add(new Cfg(Mode.IROH, ReactiveGen.DEFAULT, VoxelMask.Params.DEFAULT, sc, 0.9f, 0.5f, 0.8f)); }
        ReactiveGen[] gens = {
                new ReactiveGen(4f, 0f, 0.9f, ReactiveGen.COMPONENTS_MAX), new ReactiveGen(4f, 0f, 0.9f, 0), new ReactiveGen(2f, 0f, 0.9f, 0),
                new ReactiveGen(1f, 0f, 0.9f, 0), new ReactiveGen(0.5f, 0.1f, 0.9f, ReactiveGen.TONEMAP | ReactiveGen.THRESHOLD | ReactiveGen.COMPONENTS_MAX),
                new ReactiveGen(1f, 0.1f, 0.9f, ReactiveGen.THRESHOLD | ReactiveGen.COMPONENTS_MAX), new ReactiveGen(4f, 0.05f, 0.9f, ReactiveGen.THRESHOLD | ReactiveGen.COMPONENTS_MAX),
                new ReactiveGen(8f, 0f, 0.9f, ReactiveGen.COMPONENTS_MAX), new ReactiveGen(4f, 0f, 0.9f, ReactiveGen.TONEMAP | ReactiveGen.COMPONENTS_MAX)};
        for (ReactiveGen g : gens) { names.add(String.format(Locale.ROOT, "auto scale %.1f cutoff %.2f bin %.1f flags %d", g.scale(), g.cutoff(), g.binaryValue(), g.flags())); cfgs.add(Cfg.of(Mode.AUTO).gen(g)); }
        VoxelMask.Params d = VoxelMask.Params.DEFAULT;
        for (float sa : new float[]{1f, 2f, 4f, 8f}) for (float ta : new float[]{0.4f, 0.8f, 1f}) {
            names.add("voxel A scale " + sa + " tc " + ta); cfgs.add(Cfg.of(Mode.VOXEL).vox(new VoxelMask.Params(sa, d.maxA(), ta, d.scaleB(), d.maxB(), d.tcB(), d.scaleOther())));
        }
        for (float sb : new float[]{2f, 4f, 8f}) for (float tb : new float[]{0f, 0.4f, 0.8f}) {
            names.add("voxel B scale " + sb + " tc " + tb); cfgs.add(Cfg.of(Mode.VOXEL).vox(new VoxelMask.Params(d.scaleA(), d.maxA(), d.tcA(), sb, d.maxB(), tb, d.scaleOther())));
        }
        for (float so : new float[]{0f, 1f, 4f}) { names.add("voxel other scale " + so); cfgs.add(Cfg.of(Mode.VOXEL).vox(new VoxelMask.Params(d.scaleA(), d.maxA(), d.tcA(), d.scaleB(), d.maxB(), d.tcB(), so))); }
        names.add("voxel+sprites default"); cfgs.add(Cfg.of(Mode.VOXEL_SPRITES));
        for (int k = 0; k < cfgs.size(); k++) {
            double[] sc = new double[scenes.length];
            for (int si = 0; si < scenes.length; si++) {
                Result r = run(scenes[si], frs[si], cfgs.get(k));
                sc[si] = scenes[si].animated() ? r.excess()[0] : r.excess()[0] + r.excess()[1];
            }
            System.out.printf(Locale.ROOT, "   %-44s  %s  %s  %s  |%s  %s%n", names.get(k), f(sc[0]), f(sc[1]), f(sc[2]), f(sc[0] + sc[1] + sc[2]), f(sc[3]));
        }
    }
}
