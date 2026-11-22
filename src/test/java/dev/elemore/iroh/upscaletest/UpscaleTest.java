package dev.elemore.iroh.upscaletest;

import dev.elemore.iroh.upscale.*;
import dev.elemore.iroh.upscale.fsr.FsrNative;
import dev.elemore.iroh.upscale.fsr.NativeLoader;

/** Pure upscaler parts, plus cross-checks against the SDK through the JNI shim when libiroh_fsr is present. Last line "# upscale PASS n/n". */
public final class UpscaleTest {
    static int pass, total;

    static void check(boolean ok, String what) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    public static void main(String[] a) {
        int[][] sizes = {{1920, 1080}, {2560, 1440}, {3840, 2160}};
        // quality modes -> render sizes (expected from the SDK formula and the documented ratios)
        int[][][] expect = {
                /* 1080p */ {{1920, 1080}, {1280, 720}, {1129, 635}, {960, 540}, {640, 360}},
                /* 1440p */ {{2560, 1440}, {1706, 960}, {1505, 847}, {1280, 720}, {853, 480}},
                /* 4K    */ {{3840, 2160}, {2560, 1440}, {2258, 1270}, {1920, 1080}, {1280, 720}}};
        for (int s = 0; s < sizes.length; s++)
            for (QualityMode m : QualityMode.values()) {
                int[] r = m.renderSize(sizes[s][0], sizes[s][1]);
                int[] e = expect[s][m.ordinal()];
                check(r[0] == e[0] && r[1] == e[1], "renderSize " + m + " " + sizes[s][0] + "x" + sizes[s][1] + " got " + r[0] + "x" + r[1] + " want " + e[0] + "x" + e[1]);
            }
        // documented phase counts (SDK doc table: 18 / 23 / 32 / 72 at any size, e.g. 1440p)
        int[] want = {8, 18, 23, 32, 72};
        for (QualityMode m : QualityMode.values()) {
            int[] r = m.renderSize(2560, 1440);
            int pc = JitterSequence.phaseCount(r[0], 2560);
            check(Math.abs(pc - want[m.ordinal()]) <= 1, "phase count " + m + " got " + pc + " doc " + want[m.ordinal()]);
        }
        // mip bias: log2(render/display) - 1
        float[] bias = {-1.0f, -1.585f, -1.766f, -2.0f, -2.585f};
        for (QualityMode m : QualityMode.values()) {
            check(Math.abs(MipBias.forMode(m) - bias[m.ordinal()]) < 0.002f, "mip bias " + m + " " + MipBias.forMode(m));
            int[] r = m.renderSize(2560, 1440);
            check(Math.abs(MipBias.forSizes(r[0], 2560) - bias[m.ordinal()]) < 0.01f, "mip bias sizes " + m);
        }
        // sequence properties: in [-0.5,0.5], wraps, first values are Halton(2,3) 1/2-0.5 = 0 and 1/3-0.5
        float[] j0 = JitterSequence.offset(0, 18);
        check(j0[0] == 0.0f && Math.abs(j0[1] - (1f / 3f - 0.5f)) < 1e-6f, "first jitter " + j0[0] + "," + j0[1]);
        boolean inRange = true;
        for (int i = 0; i < 500; i++) {
            float[] j = JitterSequence.offset(i, 72);
            inRange &= j[0] >= -0.5f && j[0] <= 0.5f && j[1] >= -0.5f && j[1] <= 0.5f;
        }
        check(inRange, "jitter in range");
        check(java.util.Arrays.equals(JitterSequence.offset(5, 18), JitterSequence.offset(23, 18)), "jitter wraps");
        float[] pn = JitterSequence.projectionOffsetNdc(new float[]{0.5f, -0.25f}, 1000, 500);
        check(pn[0] == 0.001f && pn[1] == 0.001f, "projection offset " + pn[0] + "," + pn[1]);
        check(FsrMemoryEstimateOk(), "memory estimate within 25% of the SDK table at 1440p Quality (106 MB)");

        if (NativeLoader.load()) {
            System.out.println("native: " + FsrNative.version());
            // the SDK's own functions, bit for bit
            int bad = 0, n = 0;
            for (int pc : new int[]{8, 18, 23, 32, 72, 128})
                for (int i = 0; i < 300; i++) {
                    float[] nat = FsrNative.jitter(i, pc), jav = JitterSequence.offset(i, pc);
                    n++;
                    if (Float.floatToIntBits(nat[0]) != Float.floatToIntBits(jav[0]) || Float.floatToIntBits(nat[1]) != Float.floatToIntBits(jav[1])) bad++;
                }
            check(bad == 0, "jitter vs SDK: " + bad + "/" + n + " differ");
            bad = 0; n = 0;
            for (int dw = 640; dw <= 7680; dw += 37)
                for (QualityMode m : QualityMode.values()) {
                    int rw = m.renderSize(dw, dw * 9 / 16)[0];
                    n++;
                    if (rw > 0 && FsrNative.phaseCount(rw, dw) != JitterSequence.phaseCount(rw, dw)) bad++;
                }
            check(bad == 0, "phase count vs SDK: " + bad + "/" + n + " differ");
            bad = 0; n = 0;
            for (int[] s : sizes)
                for (QualityMode m : QualityMode.values()) {
                    int[] nat = FsrNative.renderSize(m.fsrValue(), s[0], s[1]), jav = m.renderSize(s[0], s[1]);
                    n++;
                    if (nat[0] != jav[0] || nat[1] != jav[1]) bad++;
                    if (FsrNative.upscaleRatio(m.fsrValue()) != m.ratio) bad++;
                }
            check(bad == 0, "render size / ratio vs SDK: " + bad + " differ");
            check(FsrNative.structSize() == 296, "struct size");
        } else {
            System.out.println("native library not loaded (" + NativeLoader.unavailableReason() + "): SDK cross-checks skipped");
        }
        System.out.println("# upscale " + (pass == total ? "PASS " : "FAIL ") + pass + "/" + total);
        if (pass != total) System.exit(1);
    }

    static boolean FsrMemoryEstimateOk() {
        long e = dev.elemore.iroh.upscale.fsr.FsrUpscaler.estimate(1706, 960, 2560, 1440);
        return Math.abs(e - 106_000_000L) < 0.25 * 106_000_000L;
    }
}
