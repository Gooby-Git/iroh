package dev.elemore.iroh.rt;

/**
 * R1-HANG: the RT work limits in one place (Minecraft-free). Safe mode ({@code -Diroh.rt.safe=true}) is the setting for the
 * first guarded in-game RT runs after the device loss: residency 2 sections, 256 MiB ceiling (MemoryShares reads the same
 * property), at most 2 section BLAS builds and 50,000 triangles per frame. Each value can still be set on its own:
 * {@code iroh.rt.residency}, {@code iroh.rt.capMiB}, {@code iroh.rt.maxBuilds}, {@code iroh.rt.maxTriangles}.
 * The per-frame build budget is the per-SUBMIT bound: every build of a frame goes into that frame's one command buffer.
 */
public final class RtLimits {
    private RtLimits() {}

    public static final int SAFE_RESIDENCY = 2, SAFE_CAP_MIB = 256, SAFE_MAX_BUILDS = 2, SAFE_MAX_TRIANGLES = 50_000;
    /** hard ceilings no property can lift: one frame never records more than this (a GPU ring timeout is ~2-10 s) */
    public static final int HARD_MAX_BUILDS = 32, HARD_MAX_TRIANGLES = 1_000_000, HARD_MAX_RESIDENCY = 16;
    /** TLAS instances per frame: (2r+1)^2 columns x (2*max(2,r/2)+1) layers x 2 BLAS per section at r = 16, rounded up */
    public static final int HARD_MAX_INSTANCES = 40_000;

    public static boolean safe() {
        return Boolean.getBoolean("iroh.rt.safe");
    }

    public static int residency() {
        return clamp(Integer.getInteger("iroh.rt.residency", safe() ? SAFE_RESIDENCY : 8), 1, HARD_MAX_RESIDENCY);
    }

    public static long capMiB() {
        return Long.getLong("iroh.rt.capMiB", safe() ? SAFE_CAP_MIB : 1536);
    }

    public static int maxBuilds(RtPreset p) {
        return clamp(Integer.getInteger("iroh.rt.maxBuilds", safe() ? SAFE_MAX_BUILDS : p == RtPreset.LOW ? 4 : 8), 1, HARD_MAX_BUILDS);
    }

    public static int maxTriangles(RtPreset p) {
        return clamp(Integer.getInteger("iroh.rt.maxTriangles", safe() ? SAFE_MAX_TRIANGLES : p == RtPreset.LOW ? 100_000 : 200_000), 1_000, HARD_MAX_TRIANGLES);
    }

    /** One line for the startup self-check. */
    public static String describe(RtPreset p) {
        return (safe() ? "SAFE " : "") + "residency " + residency() + ",cap " + capMiB() + "MiB,builds/frame " + maxBuilds(p) + ",tris/frame " + maxTriangles(p);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
