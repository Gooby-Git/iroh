package dev.elemore.iroh.shaderpack.runtime;

/**
 * P3-7 C, shadow pass cost (pure logic, tested offline):
 * <ul>
 *   <li>{@link #lightFacingMask}: the QuadFacing buckets (X_POS, X_NEG, Y_POS, Y_NEG, Z_POS, Z_NEG, UNDEFINED) whose
 *       faces can be seen from the light. For closed blocks the light-facing faces are the nearest to the light, so
 *       the shadow depth is unchanged while about half the quads are skipped; UNDEFINED (unculled quads: plants,
 *       panes, translucent) always draws.</li>
 *   <li>{@link #decide}: reuse last frame's shadow maps while the shadow camera has barely moved (sun step and the
 *       interval snap), at most {@code maxStale} frames in a row. On reuse the caller keeps feeding the OLD shadow
 *       matrices, so lookups stay consistent with the reused map. Staleness bound = block changes and moving casters
 *       show up in shadows at most maxStale frames late.</li>
 * </ul>
 */
public final class ShadowPlanner {

    private double[] lastView, lastProj;
    private int age;
    public int reused, rendered;

    /** direction TO the light in world space from a shadow view matrix (column-major): its +z axis */
    public static double[] lightDir(double[] shadowView) {
        return new double[]{shadowView[2], shadowView[6], shadowView[10]};
    }

    public static int lightFacingMask(double[] l) {
        int m = 1 << 6; // UNDEFINED
        double eps = 1e-3;
        if (l[0] > -eps) m |= 1;       // X_POS
        if (l[0] < eps) m |= 1 << 1;   // X_NEG
        if (l[1] > -eps) m |= 1 << 2;  // Y_POS
        if (l[1] < eps) m |= 1 << 3;   // Y_NEG
        if (l[2] > -eps) m |= 1 << 4;  // Z_POS
        if (l[2] < eps) m |= 1 << 5;   // Z_NEG
        return m;
    }

    /**
     * @return the matrices to use this frame: {view, proj, reuse?1:0}. Reuse when maxStale > 0, the last map is
     * younger than maxStale frames, and every matrix element moved by less than {@code tolerance}.
     */
    public double[][] decide(double[] view, double[] proj, int maxStale, double tolerance, boolean forceRender) {
        boolean reuse = !forceRender && maxStale > 0 && lastView != null && age < maxStale
                && close(view, lastView, tolerance) && close(proj, lastProj, tolerance);
        if (reuse) {
            age++;
            reused++;
            return new double[][]{lastView, lastProj, {1}};
        }
        lastView = view.clone();
        lastProj = proj.clone();
        age = 0;
        rendered++;
        return new double[][]{view, proj, {0}};
    }

    public void reset() {
        lastView = lastProj = null;
        age = 0;
    }

    static boolean close(double[] a, double[] b, double tol) {
        for (int i = 0; i < a.length; i++)
            if (Math.abs(a[i] - b[i]) > tol)
                return false;
        return true;
    }
}
