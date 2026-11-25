package dev.elemore.iroh.upscaletest;

import dev.elemore.iroh.upscale.fsr.FsrNative;
import dev.elemore.iroh.upscale.fsr.NativeLoader;

import java.util.Locale;

import static dev.elemore.iroh.upscaletest.FsrReplay.*;

/**
 * FSR-Q5: texture sharpness vs shimmer for texture LOD bias values, offline on lavapipe.
 *
 * <pre>
 * scene   a 16x16 pixel-art texture (cobblestone-like: random 3-level texels + dark grout lines every 4th texel) on a plane at
 *         s texels per DISPLAY pixel (minified), sampled like the GPU does it: lod = log2(r * s) + bias (r = display/render
 *         ratio: one render pixel covers r*s texels), lod <= 0 -> nearest on level 0 (Minecraft's magnification filter),
 *         lod > 0 -> bilinear on the two box-filtered mip levels, blended (trilinear). The render is jittered like the game.
 * ref     the plane integrated over each display pixel (8x8 level-0 samples): what an infinitely supersampled frame shows
 * sharp   gradient energy of the output over the reference's (1.0 = as sharp as the reference; > 1 = sharper than it, i.e. aliasing)
 * rmse    output vs reference (whole frame, frames 12..23)
 * still   flicker of the static scene: mean |frame t - frame t-1| (frames 12..23), the temporal shimmer of a standing camera
 * pan     the same on a slow pan (0.31, 0.17) display px/frame with correct motion vectors: mean |(out - ref)_t - (out - ref)_t-1|,
 *         i.e. the flicker of the ERROR, so the legitimate scroll of the texture does not count
 * </pre>
 * {@code --bias=0,-0.5,..} (default list) {@code --ratio=1,1.5,2} {@code --density=1.5,3} {@code --rcas=0.3}; "formula" adds log2(1/r) - 1.
 * Last line {@code # fsrmip PASS|FAIL}. Nothing here gates a number: the table chooses the defaults (knowledge/fsr.md G.8).
 */
public final class FsrMipMetrics {
    static final int DW = 360, DH = 180, FR = 24, F0 = 12;
    static final int T = 16;
    static final float PX = 0.31f, PY = 0.17f;
    static final float[][] LEVEL = new float[5][]; // level k: (16>>k)^2 luminance texels
    static boolean force16;

    static {
        float[] l0 = new float[T * T];
        for (int y = 0; y < T; y++) for (int x = 0; x < T; x++) {
            int h = (x * 73856093) ^ (y * 19349663);
            h = (h ^ (h >>> 13)) * 0x5bd1e995; h ^= h >>> 15;
            float v = new float[]{0.20f, 0.50f, 0.80f}[Math.floorMod(h, 3)];
            if ((x & 3) == 0 || (y & 3) == 0) v = 0.08f;
            l0[y * T + x] = v;
        }
        LEVEL[0] = l0;
        for (int k = 1; k < 5; k++) {
            int n = T >> k, m = T >> (k - 1);
            float[] a = new float[n * n];
            for (int y = 0; y < n; y++) for (int x = 0; x < n; x++)
                a[y * n + x] = 0.25f * (LEVEL[k - 1][(2 * y) * m + 2 * x] + LEVEL[k - 1][(2 * y) * m + 2 * x + 1] + LEVEL[k - 1][(2 * y + 1) * m + 2 * x] + LEVEL[k - 1][(2 * y + 1) * m + 2 * x + 1]);
            LEVEL[k] = a;
        }
    }

    static float texel(int k, int x, int y) { int n = T >> k; return LEVEL[k][Math.floorMod(y, n) * n + Math.floorMod(x, n)]; }

    /** bilinear sample of level k at texture coordinate (u, v) in level-0 texel units (wraps). */
    static float bilinear(int k, float u, float v) {
        float s = 1f / (1 << k);
        float x = u * s - 0.5f, y = v * s - 0.5f;
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        float fx = x - x0, fy = y - y0;
        return (texel(k, x0, y0) * (1 - fx) + texel(k, x0 + 1, y0) * fx) * (1 - fy) + (texel(k, x0, y0 + 1) * (1 - fx) + texel(k, x0 + 1, y0 + 1) * fx) * fy;
    }

    /** the GPU sample: u, v level-0 texel coords; lod already includes the bias. */
    static float sample(float u, float v, float lod) {
        if (lod <= 0f) return texel(0, (int) Math.floor(u), (int) Math.floor(v)); // nearest magnification
        float c = Math.min(lod, 4f);
        int k0 = (int) Math.floor(c), k1 = Math.min(4, k0 + 1);
        float f = c - k0;
        return bilinear(k0, u, v) * (1 - f) + bilinear(k1, u, v) * f;
    }

    static float[] ref(float s, float ox, float oy) {
        float[] g = new float[DW * DH * 4];
        for (int y = 0; y < DH; y++) for (int x = 0; x < DW; x++) {
            float a = 0;
            for (int sy = 0; sy < 8; sy++) for (int sx = 0; sx < 8; sx++)
                a += texel(0, (int) Math.floor((x + (sx + 0.5f) / 8f - ox) * s), (int) Math.floor((y + (sy + 0.5f) / 8f - oy) * s));
            a /= 64f;
            int o = (y * DW + x) * 4; g[o] = g[o + 1] = g[o + 2] = a; g[o + 3] = 1;
        }
        return g;
    }

    static FrameSource scene(float ratio, float s, float bias, boolean pan) {
        int rw = Math.round(DW / ratio), rh = Math.round(DH / ratio);
        float lod = (float) (Math.log(ratio * s) / Math.log(2)) + bias;
        return (t, jx, jy, color, depth, mv) -> {
            float ox = pan ? PX * t : 0, oy = pan ? PY * t : 0;
            for (int j = 0; j < rh; j++) for (int i = 0; i < rw; i++) {
                float x = (i + 0.5f - jx) * ratio, y = (j + 0.5f - jy) * ratio; // shipped sign convention, see FsrMetrics.signX
                float v = sample((x - ox) * s, (y - oy) * s, lod);
                int o = j * rw + i;
                color[o * 4] = color[o * 4 + 1] = color[o * 4 + 2] = v; color[o * 4 + 3] = 1f;
                depth[o] = 0.9f;
                mv[o * 2] = pan ? -PX / ratio : 0f; mv[o * 2 + 1] = pan ? -PY / ratio : 0f; // current -> previous, render px
            }
        };
    }

    static double grad(float[] a) {
        double s = 0; long n = 0;
        for (int y = 0; y < DH - 1; y++) for (int x = 0; x < DW - 1; x++) {
            float l = a[(y * DW + x) * 4];
            double dx = a[(y * DW + x + 1) * 4] - l, dy = a[((y + 1) * DW + x) * 4] - l; s += dx * dx + dy * dy; n++;
        }
        return s / n;
    }

    record Res(double sharp, double rmse, double still, double pan) {}

    static final java.util.Map<String, Res> CACHE = new java.util.HashMap<>();

    static Res run(float ratio, float s, float bias) {
        return CACHE.computeIfAbsent(ratio + "/" + s + "/" + bias, k -> run0(ratio, s, bias));
    }

    static Res run0(float ratio, float s, float bias) {
        int rw = Math.round(DW / ratio), rh = Math.round(DH / ratio);
        RunResult st = runFsr(rw, rh, DW, DH, FR, scene(ratio, s, bias, false), false, force16, 0.1f, 1000f, false);
        RunResult pn = runFsr(rw, rh, DW, DH, FR, scene(ratio, s, bias, true), false, force16, 0.1f, 1000f, false);
        float[] r0 = ref(s, 0, 0);
        double sh = 0, rm = 0, fl = 0, pf = 0; int n = 0, nf = 0;
        for (int f = F0; f < FR; f++) {
            sh += grad(st.outputs()[f]) / grad(r0); n++;
            double e = 0; for (int i = 0; i < DW * DH; i++) { double d = st.outputs()[f][i * 4] - r0[i * 4]; e += d * d; }
            rm += Math.sqrt(e / (DW * DH));
            if (f > F0) {
                double d = 0; for (int i = 0; i < DW * DH; i++) d += Math.abs(st.outputs()[f][i * 4] - st.outputs()[f - 1][i * 4]);
                fl += d / (DW * DH);
                float[] ra = ref(s, PX * f, PY * f), rb = ref(s, PX * (f - 1), PY * (f - 1));
                double p = 0; for (int i = 0; i < DW * DH; i++) p += Math.abs((pn.outputs()[f][i * 4] - ra[i * 4]) - (pn.outputs()[f - 1][i * 4] - rb[i * 4]));
                pf += p / (DW * DH); nf++;
            }
        }
        return new Res(sh / n, rm / n, fl / nf, pf / nf);
    }

    public static void main(String[] args) throws Exception {
        float[] ratios = {1f, 1.5f, 2f, 3f}, dens = {1.5f, 3f}, bias = {0f, -0.25f, -0.5f, -0.75f, -1f, -1.5f, -2f};
        float rc = 0f;
        for (String a : args) {
            if (a.equals("--force16")) force16 = true;
            else if (a.startsWith("--bias=")) bias = parse(a.substring(7));
            else if (a.startsWith("--ratio=")) ratios = parse(a.substring(8));
            else if (a.startsWith("--density=")) dens = parse(a.substring(10));
            else if (a.startsWith("--rcas=")) rc = Float.parseFloat(a.substring(7));
        }
        StringBuilder rep = new StringBuilder();
        try {
            if (!NativeLoader.load()) throw new IllegalStateException("libiroh_fsr unavailable: " + NativeLoader.unavailableReason());
            init(force16, rep);
        } catch (Throwable t) {
            System.out.println(rep);
            System.out.println("# fsrmip FAIL setup: " + t);
            System.exit(1);
        }
        FsrReplay.rcas = rc;
        System.out.println("device: " + deviceName + "  native: " + FsrNative.version() + (rc > 0 ? "  RCAS " + rc : ""));
        try {
            for (float r : ratios) for (float s : dens) {
                float formula = (float) (-(Math.log(r) / Math.log(2))) - 1f;
                System.out.printf(Locale.ROOT, "%n== ratio %.1f, %.1f texels per display px (footprint %.1f texels per render px; lod at bias 0 = %.2f; formula bias %.2f)%n", r, s, r * s, Math.log(r * s) / Math.log(2), formula);
                System.out.println("bias    lod    sharp   rmse    still   pan");
                Res base = null;
                java.util.List<Float> bl = new java.util.ArrayList<>(); for (float b : bias) bl.add(b); if (!bl.contains(formula)) bl.add(formula);
                for (float b : bl) {
                    Res q = run(r, s, b);
                    if (b == 0f) base = q;
                    System.out.printf(Locale.ROOT, "%+.2f%s %+.2f   %.3f   %.4f  %.4f  %.4f%n", b, b == formula ? "*" : " ", Math.log(r * s) / Math.log(2) + b, q.sharp, q.rmse, q.still, q.pan);
                }
            }
            ruleTable(ratios, dens);
            System.out.println("(* = the AMD formula log2(render/display) - 1; sharp 1.0 = reference sharpness; still = static flicker; pan = flicker of the error on a slow pan)");
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.println("# fsrmip FAIL " + t);
            System.exit(1);
        }
        System.out.println("# fsrmip PASS");
        System.exit(0);
    }

    /** per ratio and bias RULE (the same rule for both densities): sum of RMSE, mean still / pan flicker, mean sharpness. */
    static void ruleTable(float[] ratios, float[] dens) {
        System.out.println("\n== bias RULES (each rule picks ONE bias per ratio; scores over the densities " + java.util.Arrays.toString(dens) + "; lower rmse / flicker is better)");
        System.out.println("ratio  rule             bias    sumRMSE  meanStill  meanPan  meanSharp");
        for (float r : ratios) {
            int rw = Math.round(DW / r);
            float f = dev.elemore.iroh.upscale.MipBias.forSizes(rw, DW);
            String[] names = {"none", "AMD formula", "formula + 0.5", "formula / 2", "shipped (MipBias)"};
            float[] b = {0f, dev.elemore.iroh.upscale.MipBias.quantize(f), dev.elemore.iroh.upscale.MipBias.quantize(f + 0.5f), dev.elemore.iroh.upscale.MipBias.quantize(f * 0.5f),
                    dev.elemore.iroh.upscale.MipBias.resolve(dev.elemore.iroh.upscale.MipBias.Mode.PIXEL_ART, rw, DW)};
            for (int i = 0; i < names.length; i++) {
                double sr = 0, st = 0, pn = 0, sh = 0;
                for (float s : dens) { Res q = run(r, s, b[i]); sr += q.rmse; st += q.still / dens.length; pn += q.pan / dens.length; sh += q.sharp / dens.length; }
                System.out.printf(Locale.ROOT, "%.1f    %-17s %+.3f  %.4f   %.4f     %.4f   %.3f%n", r, names[i], b[i], sr, st, pn, sh);
            }
        }
    }

    static float[] parse(String s) { String[] p = s.split(","); float[] r = new float[p.length]; for (int i = 0; i < p.length; i++) r[i] = Float.parseFloat(p[i]); return r; }
}
