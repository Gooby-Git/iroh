package dev.elemore.iroh.rttest;

import java.util.stream.IntStream;

import static dev.elemore.iroh.rttest.NrdScene.*;

/**
 * Minecraft-like offline scene for the denoiser comparison: a closed 6x3x8 room made of blocks with 16x16 pixel-art albedo
 * (high-frequency texels), a window in the right wall through which the sun and the sky enter (high-variance interior light),
 * a torch-like point emitter on the left wall with flicker, a moving entity box, a camera that strafes and turns. Radiance is
 * demodulated (no albedo): final colour = albedo * radiance. Same conventions as {@link NrdScene} (LH, UV top-left, MV = prev - cur).
 */
public final class McScene {
    static final float[] SUN2 = norm(new float[]{0.8f, 0.55f, 0.25f});
    static final float SUN_TAN2 = 0.05f, SUN_I = 3.0f, TORCH_I = 7.0f;
    /** static camera / entity / flicker (light-switch test) */
    public static boolean STATIC;
    static int cf(int f) { return STATIC ? 0 : f; }
    static final float[] TORCH = {-2.7f, 1.9f, 0.4f};

    /** boxes: cx,cy,cz,hx,hy,hz, material (0 stone, 1 planks, 2 floor dirt, 3 wood, 4 entity cloth) */
    static float[][] boxes(int f) {
        return new float[][]{
                {0, -0.25f, 0, 3.5f, 0.25f, 4.5f, 2},                       // floor
                {0, 3.25f, 0, 3.5f, 0.25f, 4.5f, 0},                        // ceiling
                {-3.25f, 1.5f, 0, 0.25f, 1.5f, 4.5f, 0},                    // left wall
                {0, 1.5f, 4.25f, 3.5f, 1.5f, 0.25f, 0},                     // back wall
                {0, 1.5f, -4.25f, 3.5f, 1.5f, 0.25f, 0},                    // front wall
                {3.25f, 0.5f, 0, 0.25f, 0.5f, 4.5f, 0},                     // right wall below the window
                {3.25f, 2.5f, 0, 0.25f, 0.5f, 4.5f, 0},                     // right wall above the window
                {3.25f, 1.5f, -2.25f, 0.25f, 0.5f, 1.75f, 0},               // right wall beside the window (z -4..-0.5)
                {3.25f, 1.5f, 3.25f, 0.25f, 0.5f, 0.75f, 0},                // right wall beside the window (z 2.5..4)
                {0.8f, 1.5f, 1.2f, 0.4f, 1.5f, 0.4f, 1},                    // pillar
                {-1.6f, 0.4f, 2.0f, 0.9f, 0.4f, 0.5f, 3},                   // table
                {-1.5f + 0.06f * cf(f), 0.9f, -1.0f, 0.3f, 0.9f, 0.3f, 4}};    // entity
    }
    // the window is the gap in the right wall between z -0.5..2.5 at y 1..2 (the two "beside" boxes leave it open; "below/above" boxes above are full length, see below)

    static float[][] boxesFixed(int f) {
        float[][] b = boxes(f);
        // the full-length below/above pieces must not cover the window: shrink their z extent to the two sides (replace with 4 side pieces already present)
        b[5] = new float[]{3.25f, 0.5f, 0, 0.25f, 0.5f, 4.5f, 0};
        b[6] = new float[]{3.25f, 2.5f, 0, 0.25f, 0.5f, 4.5f, 0};
        return b;
    }

    static float flicker(int f) { return STATIC ? 1f : 1.0f + 0.3f * (float) Math.sin(0.9 * f) + 0.15f * (float) Math.sin(2.3 * f + 1); }

    public static float[] eye(int f) { return new float[]{-1.2f + 0.05f * cf(f), 1.6f, -3.0f + 0.03f * cf(f)}; }

    static float yaw(int f) { return 0.35f + 0.012f * cf(f); }

    static float[][] basis(int f) {
        float cy = (float) Math.cos(yaw(f)), sy = (float) Math.sin(yaw(f)), cp = (float) Math.cos(-0.05), sp = (float) Math.sin(-0.05);
        float[] fwd = {sy * cp, sp, cy * cp};
        float[] right = norm(cross(new float[]{0, 1, 0}, fwd));
        float[] up = cross(fwd, right);
        return new float[][]{right, up, fwd};
    }

    public static float[] worldToView(int f) {
        float[][] b = basis(f);
        float[] e = eye(f);
        float[] m = new float[16];
        for (int r = 0; r < 3; r++) {
            m[r] = b[r][0]; m[4 + r] = b[r][1]; m[8 + r] = b[r][2];
            m[12 + r] = -(b[r][0] * e[0] + b[r][1] * e[1] + b[r][2] * e[2]);
        }
        m[15] = 1;
        return m;
    }

    static float[] project(int f, float[] p) {
        float[][] b = basis(f);
        float[] e = eye(f);
        float[] q = {p[0] - e[0], p[1] - e[1], p[2] - e[2]};
        float x = dot(q, b[0]), y = dot(q, b[1]), z = dot(q, b[2]);
        float t = (float) Math.tan(Math.toRadians(60) / 2), a = (float) W / H;
        return new float[]{x / (z * a * t) * 0.5f + 0.5f, 0.5f - y / (z * t) * 0.5f, z};
    }

    static float[] primaryDir(int f, float x, float y, int w, int h) {          // pixel coordinates (continuous, x right, y down)
        float[][] b = basis(f);
        float t = (float) Math.tan(Math.toRadians(60) / 2), a = (float) W / H;
        float sx = (2 * (x / w) - 1) * a * t, sy = (1 - 2 * (y / h)) * t;
        return norm(new float[]{b[0][0] * sx + b[1][0] * sy + b[2][0], b[0][1] * sx + b[1][1] * sy + b[2][1], b[0][2] * sx + b[1][2] * sy + b[2][2]});
    }

    /** trace against the boxes: {t, id (0 none, k+1), nx, ny, nz} */
    static float[] trace(float[] o, float[] d, float[][] bx, float tmax) {
        float best = tmax;
        int id = 0;
        float[] n = {0, 0, 0};
        for (int k = 0; k < bx.length; k++) {
            float[] b = bx[k];
            float tn = -Float.MAX_VALUE, tf = Float.MAX_VALUE;
            int axis = -1;
            float sgn = 0;
            boolean miss = false;
            for (int a = 0; a < 3; a++) {
                float lo = b[a] - b[3 + a], hi = b[a] + b[3 + a];
                if (Math.abs(d[a]) < 1e-9f) { if (o[a] < lo || o[a] > hi) miss = true; continue; }
                float t0 = (lo - o[a]) / d[a], t1 = (hi - o[a]) / d[a];
                float s = -1;
                if (t0 > t1) { float x = t0; t0 = t1; t1 = x; s = 1; }
                if (t0 > tn) { tn = t0; axis = a; sgn = s; }
                if (t1 < tf) tf = t1;
            }
            if (miss || tn > tf || tn < 1e-4f || tn >= best) continue;
            best = tn; id = k + 1; n = new float[]{0, 0, 0}; n[axis] = sgn;
        }
        return id == 0 ? new float[]{Float.POSITIVE_INFINITY, 0, 0, 0, 0} : new float[]{best, id, n[0], n[1], n[2]};
    }

    /** 16x16 pixel-art albedo of a box face at world point p (texel = 1/16 block). */
    static float[] albedo(float[] box, int face, float[] p) {
        int mat = (int) box[6];
        float u, v;
        switch (face) { case 0 -> { u = p[2]; v = p[1]; } case 1 -> { u = p[0]; v = p[2]; } default -> { u = p[0]; v = p[1]; } }
        int tx = (int) Math.floor(u * 16), ty = (int) Math.floor(v * 16);
        int hsh = hash(tx * 73856093 ^ ty * 19349663 ^ (mat + 1) * 83492791 ^ face * 2654435);
        float n1 = ((hsh >>> 8) & 255) / 255f;
        float[] base;
        float k;
        switch (mat) {
            case 0 -> { base = new float[]{0.55f, 0.55f, 0.56f}; k = 0.55f * (n1 - 0.5f) * 2; if (((tx >> 3) + (ty >> 3)) % 2 == 0 && (tx & 7) == 0) k -= 0.25f; }   // stone with seams
            case 1 -> { base = new float[]{0.62f, 0.45f, 0.24f}; k = 0.25f * (n1 - 0.5f) * 2; if ((ty & 3) == 3) k -= 0.30f; }                                       // planks, dark seams every 4 texels
            case 2 -> { base = new float[]{0.45f, 0.33f, 0.22f}; k = 0.6f * (n1 - 0.5f) * 2; }                                                                        // dirt
            case 3 -> { base = new float[]{0.50f, 0.32f, 0.16f}; k = 0.35f * (n1 - 0.5f) * 2; if ((tx & 7) == 0) k -= 0.2f; }
            default -> { base = new float[]{0.2f, 0.45f, 0.75f}; k = 0.5f * (n1 - 0.5f) * 2; if (((tx + ty) & 1) == 0) k += 0.15f; }
        }
        return new float[]{Math.max(0.03f, base[0] * (1 + k)), Math.max(0.03f, base[1] * (1 + k)), Math.max(0.03f, base[2] * (1 + k))};
    }

    public static final class Frame {
        public final int w, h;
        public final float[] viewZ, normal, albedo, radiance;
        public float[] mv;
        public final int[] id;

        Frame(int w, int h) {
            this.w = w; this.h = h;
            viewZ = new float[w * h]; normal = new float[w * h * 3]; albedo = new float[w * h * 3]; radiance = new float[w * h * 3]; mv = new float[w * h * 2]; id = new int[w * h];
        }
    }

    /** torchOn: intensity multiplier of the torch (before flicker); jitter in pixels (content shifts by +jitter). */
    public static Frame render(int f, int spp, int seedBase, float torchOn, float jx, float jy) {
        return render(W, H, f, spp, seedBase, torchOn, jx, jy);
    }

    public static Frame render(int rw, int rh, int f, int spp, int seedBase, float torchOn, float jx, float jy) {
        Frame fr = new Frame(rw, rh);
        float[][] bx = boxesFixed(f), bp = boxesFixed(Math.max(0, f - 1));
        float[] e = eye(f);
        IntStream.range(0, rh).parallel().forEach(j -> {
            for (int i = 0; i < rw; i++) {
                int px = j * rw + i;
                float[] d = primaryDir(f, i + 0.5f - jx, j + 0.5f - jy, rw, rh);
                float[] h = trace(e, d, bx, 1e9f);
                if (h[1] == 0) { fr.viewZ[px] = 100000f; continue; }
                float[] p = {e[0] + d[0] * h[0], e[1] + d[1] * h[0], e[2] + d[2] * h[0]};
                float[] n = {h[2], h[3], h[4]};
                int k = (int) h[1] - 1;
                fr.id[px] = k + 1;
                fr.normal[px * 3] = n[0]; fr.normal[px * 3 + 1] = n[1]; fr.normal[px * 3 + 2] = n[2];
                int face = Math.abs(n[0]) > 0.5f ? 0 : Math.abs(n[1]) > 0.5f ? 1 : 2;
                float[] pa = {p[0] + n[0] * 1e-3f * -1, p[1] + n[1] * 1e-3f * -1, p[2] + n[2] * 1e-3f * -1};
                float[] al = albedo(bx[k], face, new float[]{p[0] - n[0] * 1e-3f, p[1] - n[1] * 1e-3f, p[2] - n[2] * 1e-3f});
                fr.albedo[px * 3] = al[0]; fr.albedo[px * 3 + 1] = al[1]; fr.albedo[px * 3 + 2] = al[2];
                fr.viewZ[px] = project(f, p)[2];
                float[] pp = p.clone();
                if (k == 11) pp[0] -= (bx[11][0] - bp[11][0]);
                float[] cu = project(f, p), pu = project(Math.max(0, f - 1), pp);
                fr.mv[px * 2] = pu[0] - cu[0]; fr.mv[px * 2 + 1] = pu[1] - cu[1];
                float[] o = {p[0] + n[0] * 2e-3f, p[1] + n[1] * 2e-3f, p[2] + n[2] * 2e-3f};
                float[] acc = new float[3];
                for (int s = 0; s < spp; s++) {
                    int seed = hash(px * 7919 + seedBase * 104729 + s * 31337 + 17);
                    // sky-light (cosine sample)
                    float[] w = cosineDir(n, rnd(seed), rnd(seed ^ 0x5bd1e995));
                    if (trace(o, w, bx, 1e9f)[1] == 0) { float[] c = sky(w); acc[0] += c[0]; acc[1] += c[1]; acc[2] += c[2]; }
                    // sun through the window (cone sample)
                    float[] tt = norm(cross(SUN2, new float[]{0, 1, 0})), bb = cross(SUN2, tt);
                    float rr = SUN_TAN2 * (float) Math.sqrt(rnd(seed ^ 0x1234567)), ph = (float) (2 * Math.PI * rnd(seed ^ 0x27d4eb2f));
                    float[] l = norm(new float[]{SUN2[0] + tt[0] * rr * (float) Math.cos(ph) + bb[0] * rr * (float) Math.sin(ph), SUN2[1] + tt[1] * rr * (float) Math.cos(ph) + bb[1] * rr * (float) Math.sin(ph),
                            SUN2[2] + tt[2] * rr * (float) Math.cos(ph) + bb[2] * rr * (float) Math.sin(ph)});
                    float nl = dot(n, l);
                    if (nl > 0 && trace(o, l, bx, 1e9f)[1] == 0) { acc[0] += SUN_I * nl * 1.0f; acc[1] += SUN_I * nl * 0.95f; acc[2] += SUN_I * nl * 0.8f; }
                    // torch point light with flicker
                    float[] tl = {TORCH[0] - o[0], TORCH[1] - o[1], TORCH[2] - o[2]};
                    float dist = (float) Math.sqrt(dot(tl, tl));
                    float[] tn = {tl[0] / dist, tl[1] / dist, tl[2] / dist};
                    float tnl = dot(n, tn);
                    if (torchOn > 0 && tnl > 0 && trace(o, tn, bx, dist - 0.01f)[1] == 0) {
                        float li = TORCH_I * torchOn * flicker(f) * tnl / (dist * dist + 0.2f);
                        acc[0] += li * 1.0f; acc[1] += li * 0.7f; acc[2] += li * 0.35f;
                    }
                }
                fr.radiance[px * 3] = acc[0] / spp; fr.radiance[px * 3 + 1] = acc[1] / spp; fr.radiance[px * 3 + 2] = acc[2] / spp;
            }
        });
        return fr;
    }
}
