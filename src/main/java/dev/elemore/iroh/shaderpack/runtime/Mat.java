package dev.elemore.iroh.shaderpack.runtime;

/** column-major 4x4 matrices as double[16] (GL layout), for the pack's matrix uniforms */
public final class Mat {
    private Mat() {}

    public static double[] identity() {
        return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    public static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int c = 0; c < 4; c++)
            for (int row = 0; row < 4; row++) {
                double v = 0;
                for (int k = 0; k < 4; k++)
                    v += a[k * 4 + row] * b[c * 4 + k];
                r[c * 4 + row] = v;
            }
        return r;
    }

    public static double[] translate(double x, double y, double z) {
        double[] m = identity();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return m;
    }

    /** GL glOrtho */
    public static double[] ortho(double l, double r, double b, double t, double n, double f) {
        double[] m = new double[16];
        m[0] = 2 / (r - l);
        m[5] = 2 / (t - b);
        m[10] = -2 / (f - n);
        m[12] = -(r + l) / (r - l);
        m[13] = -(t + b) / (t - b);
        m[14] = -(f + n) / (f - n);
        m[15] = 1;
        return m;
    }

    /** view matrix looking from eye to centre (GL lookAt, right-handed) */
    public static double[] lookAt(double[] eye, double[] centre, double[] up) {
        double fx = centre[0] - eye[0], fy = centre[1] - eye[1], fz = centre[2] - eye[2];
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl;
        fy /= fl;
        fz /= fl;
        double sx = fy * up[2] - fz * up[1], sy = fz * up[0] - fx * up[2], sz = fx * up[1] - fy * up[0];
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        sx /= sl;
        sy /= sl;
        sz /= sl;
        double ux = sy * fz - sz * fy, uy = sz * fx - sx * fz, uz = sx * fy - sy * fx;
        double[] m = {sx, ux, -fx, 0, sy, uy, -fy, 0, sz, uz, -fz, 0, 0, 0, 0, 1};
        m[12] = -(sx * eye[0] + sy * eye[1] + sz * eye[2]);
        m[13] = -(ux * eye[0] + uy * eye[1] + uz * eye[2]);
        m[14] = fx * eye[0] + fy * eye[1] + fz * eye[2];
        return m;
    }

    /** P3-8 allocation-free variants (out must not alias the inputs) */
    public static double[] mul(double[] a, double[] b, double[] r) {
        for (int c = 0; c < 4; c++)
            for (int row = 0; row < 4; row++) {
                double v = 0;
                for (int k = 0; k < 4; k++)
                    v += a[k * 4 + row] * b[c * 4 + k];
                r[c * 4 + row] = v;
            }
        return r;
    }

    public static double[] normal(double[] m, double[] inv, double[] n) {
        invert(m, inv);
        for (int c = 0; c < 3; c++)
            for (int r = 0; r < 3; r++)
                n[c * 3 + r] = inv[r * 4 + c];
        return n;
    }

    public static double[] invert(double[] m) {
        return invert(m, new double[16]);
    }

    public static double[] invert(double[] m, double[] inv) {
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
        double det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
        if (det == 0) {
            for (int i = 0; i < 16; i++)
                inv[i] = i % 5 == 0 ? 1 : 0;
            return inv;
        }
        for (int i = 0; i < 16; i++)
            inv[i] /= det;
        return inv;
    }

    /** upper-left 3x3 of the inverse transpose, column-major mat3 (9 values) */
    public static double[] normal(double[] m) {
        double[] it = invert(m);
        // transpose of the inverse: element (row r, col c) = inv(c, r)
        double[] n = new double[9];
        for (int c = 0; c < 3; c++)
            for (int r = 0; r < 3; r++)
                n[c * 3 + r] = it[r * 4 + c];
        return n;
    }

    /**
     * Shadow camera (OptiFine semantics, own derivation): looks along -shadowLightDir (world, camera-relative) at the
     * camera, orthographic half extent {@code distance}, near 0.05, far 256, eye 128 blocks up the light; the camera
     * position is snapped to {@code interval} blocks so the shadow map texels stay put while the camera moves
     * (fract(camera / interval) * interval is subtracted from the eye and centre).
     */
    public static double[][] shadow(double[] lightDirWorld, double[] cameraPos, double distance, double interval) {
        double l = Math.sqrt(lightDirWorld[0] * lightDirWorld[0] + lightDirWorld[1] * lightDirWorld[1] + lightDirWorld[2] * lightDirWorld[2]);
        double[] d = {lightDirWorld[0] / l, lightDirWorld[1] / l, lightDirWorld[2] / l};
        double[] snap = new double[3];
        if (interval > 0)
            for (int i = 0; i < 3; i++)
                snap[i] = -(cameraPos[i] - Math.floor(cameraPos[i] / interval) * interval);
        double[] centre = snap;
        double[] eye = {centre[0] + d[0] * 128, centre[1] + d[1] * 128, centre[2] + d[2] * 128};
        double[] up = Math.abs(d[1]) > 0.99 ? new double[]{0, 0, 1} : new double[]{0, 1, 0};
        double[] view = lookAt(eye, centre, up);
        double[] proj = ortho(-distance, distance, -distance, distance, 0.05, 256);
        return new double[][]{view, proj};
    }
}
