package dev.elemore.iroh.rttest;

import java.util.stream.IntStream;

/**
 * A tiny analytic "path-traced-like" scene for the offline denoiser tests: ground plane + 3 boxes (one moving), a camera that
 * translates and yaws, analytic normals / view depth / motion vectors, a 1 spp noisy diffuse sky-light signal (cosine
 * sampled, hit distance), a noisy sun shadow signal (penumbra), and 1024 spp references. Left-handed view space
 * (+z forward, +y up), UV with (0,0) at the top left, MV = previous UV - current UV.
 */
public final class NrdScene {
    public static final int W = 320, H = 180;
    public static final float FP16_MAX = 65504f;
    static final float FOV_Y = (float) Math.toRadians(60), NEAR = 0.1f, FAR = 1000f;
    static final float[] SUN = norm(new float[]{0.45f, 0.8f, 0.35f});
    static final float SUN_TAN = 0.06f;                       // tan of the sun's angular radius (soft shadows)

    // boxes: center, half size; box 2 moves along +x by 0.1 per frame
    static float[][] boxes(int frame) {
        return new float[][]{
                {0f, 0.5f, 0f, 0.5f, 0.5f, 0.5f},
                {2.4f, 0.8f, 1.6f, 0.5f, 0.8f, 0.5f},
                {-2.5f + 0.1f * frame, 0.4f, 0.6f, 0.4f, 0.4f, 0.4f}};
    }

    public static float[] eye(int f) { return new float[]{-1.5f + 0.1f * f, 1.7f, -7f + 0.06f * f}; }

    static float yaw(int f) { return 0.003f * f; }

    static final float PITCH = -0.10f;

    /** camera basis (right, up, forward) in world space. */
    static float[][] basis(int f) {
        float cy = (float) Math.cos(yaw(f)), sy = (float) Math.sin(yaw(f)), cp = (float) Math.cos(PITCH), sp = (float) Math.sin(PITCH);
        float[] fwd = {sy * cp, sp, cy * cp};
        float[] right = norm(cross(new float[]{0, 1, 0}, fwd));
        float[] up = cross(fwd, right);
        return new float[][]{right, up, fwd};
    }

    /** column-major world-to-view (LH), vectors are columns. */
    public static float[] worldToView(int f) {
        float[][] b = basis(f);
        float[] e = eye(f);
        float[] m = new float[16];
        for (int r = 0; r < 3; r++) {
            m[0 * 4 + r] = b[r][0]; m[1 * 4 + r] = b[r][1]; m[2 * 4 + r] = b[r][2];
            m[3 * 4 + r] = -(b[r][0] * e[0] + b[r][1] * e[1] + b[r][2] * e[2]);
        }
        m[3] = 0; m[7] = 0; m[11] = 0; m[15] = 1;
        return m;
    }

    /** column-major LH perspective, clip z = z/w convention of NRD (D3D depth range). */
    public static float[] viewToClip() {
        float t = (float) Math.tan(FOV_Y / 2), a = (float) W / H;
        float[] m = new float[16];
        m[0] = 1 / (a * t); m[5] = 1 / t; m[10] = FAR / (FAR - NEAR); m[11] = 1; m[14] = -NEAR * FAR / (FAR - NEAR);
        return m;
    }

    static float[] norm(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new float[]{v[0] / l, v[1] / l, v[2] / l};
    }

    static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static float dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    /** ray (o, d) vs the scene: returns {t, object id (0 sky, 1 ground, 2.. boxes), nx, ny, nz} or t = inf */
    static float[] trace(float[] o, float[] d, float[][] bx, float tmax) {
        float best = tmax;
        int id = 0;
        float[] n = {0, 0, 0};
        if (d[1] < -1e-6f) {
            float t = -o[1] / d[1];
            if (t > 1e-4f && t < best && t < 500f) { best = t; id = 1; n = new float[]{0, 1, 0}; }
        }
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
            best = tn;
            id = 2 + k;
            n = new float[]{0, 0, 0};
            n[axis] = sgn;
        }
        return id == 0 ? new float[]{Float.POSITIVE_INFINITY, 0, 0, 0, 0} : new float[]{best, id, n[0], n[1], n[2]};
    }

    static float[] sky(float[] d) {
        float u = Math.max(0f, d[1]);
        float[] c = {0.55f + (0.15f - 0.55f) * u, 0.75f + (0.3f - 0.75f) * u, 1.0f + (0.9f - 1.0f) * u};
        float k = 1.0f + 1.0f * u;
        float r = c[0] * k, g = c[1] * k, b = c[2] * k;
        if (dot(d, SUN) > 0.997f) { r += 30; g += 25; b += 18; }     // sun disc: fireflies
        return new float[]{r, g, b};
    }

    /** deterministic hash RNG */
    static int hash(int x) {
        x ^= x >>> 16; x *= 0x7feb352d; x ^= x >>> 15; x *= 0x846ca68b; x ^= x >>> 16;
        return x;
    }

    static float rnd(int seed) { return (hash(seed) >>> 8) / 16777216f; }

    static float[] cosineDir(float[] n, float u1, float u2) {
        float r = (float) Math.sqrt(u1), phi = (float) (2 * Math.PI * u2);
        float x = r * (float) Math.cos(phi), y = r * (float) Math.sin(phi), z = (float) Math.sqrt(Math.max(0, 1 - u1));
        float[] t = Math.abs(n[1]) < 0.99f ? cross(n, new float[]{0, 1, 0}) : cross(n, new float[]{1, 0, 0});
        t = norm(t);
        float[] b = cross(n, t);
        return norm(new float[]{t[0] * x + b[0] * y + n[0] * z, t[1] * x + b[1] * y + n[1] * z, t[2] * x + b[2] * y + n[2] * z});
    }

    /** All per-frame buffers (row 0 = top). */
    public static final class Frame {
        public final float[] viewZ = new float[W * H], mv = new float[W * H * 2], normal = new float[W * H * 3];
        public final int[] id = new int[W * H];
        public final float[] hitDist = new float[W * H];               // diffuse sample hit distance (FP16_MAX = miss)
        public final float[] radiance = new float[W * H * 3];          // noisy 1 spp diffuse sky-light radiance (demodulated)
        public final float[] penumbra = new float[W * H];              // noisy SIGMA input
        public final float[] position = new float[W * H * 3];
        public final boolean[] disoccluded = new boolean[W * H];
    }

    public static float[] primaryDir(int f, int i, int j) {
        float[][] b = basis(f);
        float t = (float) Math.tan(FOV_Y / 2), a = (float) W / H;
        float x = (2 * ((i + 0.5f) / W) - 1) * a * t, y = (1 - 2 * ((j + 0.5f) / H)) * t;
        return norm(new float[]{b[0][0] * x + b[1][0] * y + b[2][0], b[0][1] * x + b[1][1] * y + b[2][1], b[0][2] * x + b[1][2] * y + b[2][2]});
    }

    /** uv in the image of camera frame f for a world point; also view z. */
    static float[] project(int f, float[] p) {
        float[][] b = basis(f);
        float[] e = eye(f);
        float[] q = {p[0] - e[0], p[1] - e[1], p[2] - e[2]};
        float x = dot(q, b[0]), y = dot(q, b[1]), z = dot(q, b[2]);
        float t = (float) Math.tan(FOV_Y / 2), a = (float) W / H;
        float ndcX = x / (z * a * t), ndcY = y / (z * t);
        return new float[]{ndcX * 0.5f + 0.5f, 0.5f - ndcY * 0.5f, z};
    }

    public static Frame render(int f, int spp, int seedBase) {
        Frame fr = new Frame();
        float[][] bx = boxes(f), bxPrev = boxes(Math.max(0, f - 1));
        float[] e = eye(f);
        IntStream.range(0, H).parallel().forEach(j -> {
            for (int i = 0; i < W; i++) {
                int px = j * W + i;
                float[] d = primaryDir(f, i, j);
                float[] h = trace(e, d, bx, 1e9f);
                if (h[1] == 0) { fr.viewZ[px] = 100000f; fr.id[px] = 0; fr.penumbra[px] = FP16_MAX; fr.hitDist[px] = FP16_MAX; continue; }
                float t = h[0];
                float[] p = {e[0] + d[0] * t, e[1] + d[1] * t, e[2] + d[2] * t};
                float[] n = {h[2], h[3], h[4]};
                fr.id[px] = (int) h[1];
                fr.position[px * 3] = p[0]; fr.position[px * 3 + 1] = p[1]; fr.position[px * 3 + 2] = p[2];
                fr.normal[px * 3] = n[0]; fr.normal[px * 3 + 1] = n[1]; fr.normal[px * 3 + 2] = n[2];
                fr.viewZ[px] = project(f, p)[2];
                // motion vector: where was this surface point in the previous frame (moving box carries its own motion)
                float[] pp = p.clone();
                if (fr.id[px] == 4) pp[0] -= (bx[2][0] - bxPrev[2][0]);
                float[] cu = project(f, p), pu = project(Math.max(0, f - 1), pp);
                fr.mv[px * 2] = pu[0] - cu[0];
                fr.mv[px * 2 + 1] = pu[1] - cu[1];
                // disocclusion: the previous frame saw a different object (or nothing) at the reprojected position
                int pi = (int) Math.floor(pu[0] * W), pj = (int) Math.floor(pu[1] * H);
                boolean dis = f == 0;
                if (!dis) {
                    if (pi < 0 || pj < 0 || pi >= W || pj >= H) dis = true;
                    else {
                        float[] ph = trace(eye(f - 1), primaryDir(f - 1, pi, pj), bxPrev, 1e9f);
                        dis = (int) ph[1] != fr.id[px] || Math.abs(project(f - 1, pp)[2] - ph[0] * dot(primaryDir(f - 1, pi, pj), basis(f - 1)[2])) > 0.15f;
                    }
                }
                fr.disoccluded[px] = dis;
                // diffuse sky-light signal: spp cosine samples (1 = the noisy input)
                float[] acc = new float[3];
                float hd = FP16_MAX;
                for (int k = 0; k < spp; k++) {
                    int seed = hash(px * 7919 + seedBase * 104729 + k * 31337 + 17);
                    float[] w = cosineDir(n, rnd(seed), rnd(seed ^ 0x5bd1e995));
                    float[] o = {p[0] + n[0] * 1e-3f, p[1] + n[1] * 1e-3f, p[2] + n[2] * 1e-3f};
                    float[] s = trace(o, w, bx, 1e9f);
                    if (s[1] == 0) { float[] c = sky(w); acc[0] += c[0]; acc[1] += c[1]; acc[2] += c[2]; if (k == 0) hd = FP16_MAX; }
                    else if (k == 0) hd = s[0];
                }
                fr.radiance[px * 3] = acc[0] / spp; fr.radiance[px * 3 + 1] = acc[1] / spp; fr.radiance[px * 3 + 2] = acc[2] / spp;
                fr.hitDist[px] = hd;
                // sun shadow signal: one sample inside the sun's cone
                {
                    int seed = hash(px * 6203 + seedBase * 15731 + 99);
                    float[] dir = SUN.clone();
                    float[] tt = norm(cross(dir, new float[]{0, 1, 0})), bb = cross(dir, tt);
                    float rr = SUN_TAN * (float) Math.sqrt(rnd(seed)), ph = (float) (2 * Math.PI * rnd(seed ^ 0x27d4eb2f));
                    float[] l = norm(new float[]{dir[0] + tt[0] * rr * (float) Math.cos(ph) + bb[0] * rr * (float) Math.sin(ph), dir[1] + tt[1] * rr * (float) Math.cos(ph) + bb[1] * rr * (float) Math.sin(ph),
                            dir[2] + tt[2] * rr * (float) Math.cos(ph) + bb[2] * rr * (float) Math.sin(ph)});
                    float[] o = {p[0] + n[0] * 1e-3f, p[1] + n[1] * 1e-3f, p[2] + n[2] * 1e-3f};
                    float[] s = trace(o, l, bx, 1e9f);
                    fr.penumbra[px] = s[1] == 0 ? FP16_MAX : Math.min(s[0] * SUN_TAN * 0.5f, 32768f);
                }
            }
        });
        return fr;
    }

    /** 1024 spp sky-light reference radiance (average of the same estimator) and sun visibility reference. */
    public static float[][] reference(int f, int spp) {
        float[][] bx = {null};
        Frame fr = render(f, spp, 777);
        float[] vis = new float[W * H];
        float[][] boxesNow = boxes(f);
        IntStream.range(0, H).parallel().forEach(j -> {
            for (int i = 0; i < W; i++) {
                int px = j * W + i;
                if (fr.id[px] == 0) { vis[px] = 1; continue; }
                float[] p = {fr.position[px * 3], fr.position[px * 3 + 1], fr.position[px * 3 + 2]}, n = {fr.normal[px * 3], fr.normal[px * 3 + 1], fr.normal[px * 3 + 2]};
                int lit = 0;
                for (int k = 0; k < 1024; k++) {
                    int seed = hash(px * 6203 + k * 7717 + 5);
                    float[] tt = norm(cross(SUN, new float[]{0, 1, 0})), bb = cross(SUN, tt);
                    float rr = SUN_TAN * (float) Math.sqrt(rnd(seed)), ph = (float) (2 * Math.PI * rnd(seed ^ 0x27d4eb2f));
                    float[] l = norm(new float[]{SUN[0] + tt[0] * rr * (float) Math.cos(ph) + bb[0] * rr * (float) Math.sin(ph), SUN[1] + tt[1] * rr * (float) Math.cos(ph) + bb[1] * rr * (float) Math.sin(ph),
                            SUN[2] + tt[2] * rr * (float) Math.cos(ph) + bb[2] * rr * (float) Math.sin(ph)});
                    float[] o = {p[0] + n[0] * 1e-3f, p[1] + n[1] * 1e-3f, p[2] + n[2] * 1e-3f};
                    if (trace(o, l, boxesNow, 1e9f)[1] == 0) lit++;
                }
                vis[px] = lit / 1024f;
            }
        });
        return new float[][]{fr.radiance, vis};
    }

    // ------------------------------------------------------------------ NRD input encodings (own implementations of the documented packings)
    /** R10_G10_B10_A2 oct-style packed normal + roughness as NRD's default NormalEncoding (2) expects (packed into A2B10G10R10: r bits 0-9). */
    public static int packNormalRoughness(float nx, float ny, float nz, float roughness, int material2bit) {
        float inv = 1f / (Math.abs(nx) + Math.abs(ny) + Math.abs(nz));
        nx *= inv; ny *= inv; nz *= inv;
        float ry = ny * 0.5f + 0.5f, rx = nx * 0.5f + ry;
        ry -= nx * 0.5f;
        roughness = Math.max(roughness, 1.5f / 512f);
        float rz = (nz < 0 ? -roughness : roughness) * 0.5f + 0.5f;
        return q10(rx) | (q10(ry) << 10) | (q10(rz) << 20) | ((material2bit & 3) << 30);
    }

    static int q10(float v) { return Math.max(0, Math.min(1023, Math.round(v * 1023f))); }

    /** linear RGB -> YCoCg as used by REBLUR's radiance front-end. */
    public static float[] ycocg(float r, float g, float b) {
        return new float[]{0.25f * r + 0.5f * g + 0.25f * b, 0.5f * r - 0.5f * b, -0.25f * r + 0.5f * g - 0.25f * b};
    }

    public static float[] fromYcocg(float y, float co, float cg) {
        float t = y - cg;
        return new float[]{Math.max(0, t + co), Math.max(0, y + cg), Math.max(0, t - co)};
    }

    /** REBLUR diffuse hit distance normalisation with default parameters (A=3, B=0.1; diffuse roughness 1 => no lobe term). */
    public static float normHitDist(float hitDist, float viewZ) {
        float f = 3.0f + viewZ * 0.1f;
        return Math.max(0f, Math.min(1f, hitDist / f));
    }
}
