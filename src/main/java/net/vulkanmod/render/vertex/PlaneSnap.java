package net.vulkanmod.render.vertex;

/**
 * Iroh (rc10, td-242adb): quantisation of terrain vertex positions to the compressed format's grid (1/2048 block)
 * without collapsing a sub-step plane onto a block boundary.
 * <p>
 * Vanilla's powder snow model is six 0.000125-block slabs, each with an outward face on the block boundary and an
 * inward face 0.000125 inside. Rounded to the 1/2048 grid, the inward face landed exactly ON the boundary, i.e. on the
 * neighbour's face plane, and lost the depth tie: OpenGL (float positions) shows the powder face, Iroh showed the wall.
 * Rule: round to the nearest grid step, except that a coordinate which is NOT on an integer block plane must not be
 * rounded ONTO one; it is moved one step to its own side instead. On-grid coordinates (every full-block face, every
 * 1/16 model coordinate: 1/16 = 128 steps) are unchanged, so section seams cannot appear. Geometry moves by at most
 * one step (0.0005 block) and only for coordinates within half a step of a block plane. Only the compressed vertex
 * format needs this; float formats (RQ-5) keep positions as they are.
 */
public final class PlaneSnap {
    public static final float STEPS = 2048.0f;
    private static final float EPS = 1.0e-6f;

    private PlaneSnap() {
    }

    /** {@code v} (section-local block units) on the 1/2048 grid, never collapsing onto an integer plane it is not on. */
    public static float snap(float v) {
        float q = Math.round(v * STEPS) / STEPS;
        float b = Math.round(v);
        if (q == b && Math.abs(v - b) > EPS)
            q = b + Math.signum(v - b) / STEPS;
        return q;
    }
}
