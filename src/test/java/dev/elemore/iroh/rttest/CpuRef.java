package dev.elemore.iroh.rttest;

import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Brute-force CPU reference for the P5-0 RT passes: a double-precision BVH over the SAME decoded triangles the GPU
 * traces (quantised 16-byte vertices), the same alpha rule and the same light transport definitions, but INDEPENDENT
 * estimators wherever possible so a shared bug cannot cancel out:
 * block light = cosine (Shirley-Chiu concentric) hemisphere rays that must hit emitters (GPU: power-CDF light list);
 * light samples at secondary vertices = uniform light choice (GPU: power CDF); glossy = GGX NDF sampling (Walter 2007;
 * GPU: VNDF, Heitz 2018); sun cone = cap sampling written separately.
 */
final class CpuRef {
    final RtScene scene;
    final RtScene.Tris t;
    final RtReplay.Light light;
    final RtCamera cam;
    final int W, H, N;
    static final double EPS = 1e-3, TMAX = 1000;

    // BVH
    double[] bmin, bmax;
    int[] left, start, count, order;
    int nodes;

    CpuRef(RtScene scene, RtReplay.Light light, RtCamera cam) {
        this.scene = scene;
        this.t = scene.triangles();
        this.light = light;
        this.cam = cam;
        this.W = cam.w;
        this.H = cam.h;
        this.N = W * H;
        build();
    }

    // ------------------------------------------------------------------------------------------------ P5-3 dynamic triangles
    /** Static triangle count (BVH); posed entity triangles follow it, tested brute force per group (one AABB per part). */
    int ns = -1, dynGroups;
    double[] dmin, dmax;
    int per;

    /** Replaces the entity triangles (world space, {@code trisPerGroup} per posed part) for the next estimates. */
    void setDynamic(RtScene.Tris d, int trisPerGroup) {
        if (ns < 0) ns = t.n;
        int n = ns + d.n;
        t.p = java.util.Arrays.copyOf(t.p, n * 9);
        t.uv = java.util.Arrays.copyOf(t.uv, n * 6);
        t.tile = java.util.Arrays.copyOf(t.tile, n);
        t.tint = java.util.Arrays.copyOf(t.tint, n);
        t.translucent = java.util.Arrays.copyOf(t.translucent, n);
        System.arraycopy(d.p, 0, t.p, ns * 9, d.n * 9);
        System.arraycopy(d.uv, 0, t.uv, ns * 6, d.n * 6);
        System.arraycopy(d.tile, 0, t.tile, ns, d.n);
        System.arraycopy(d.tint, 0, t.tint, ns, d.n);
        java.util.Arrays.fill(t.translucent, ns, n, false);
        t.n = n;
        per = trisPerGroup;
        dynGroups = d.n / trisPerGroup;
        dmin = new double[dynGroups * 3];
        dmax = new double[dynGroups * 3];
        java.util.Arrays.fill(dmin, 1e30);
        java.util.Arrays.fill(dmax, -1e30);
        for (int k = 0; k < d.n; k++)
            for (int v = 0; v < 3; v++)
                for (int a = 0; a < 3; a++) {
                    int g = k / per;
                    dmin[g * 3 + a] = Math.min(dmin[g * 3 + a], d.p[k * 9 + v * 3 + a] - 1e-6);
                    dmax[g * 3 + a] = Math.max(dmax[g * 3 + a], d.p[k * 9 + v * 3 + a] + 1e-6);
                }
    }

    boolean dynBox(int g, double[] o, double[] inv, double tmax) {
        double t0 = 0, t1 = tmax;
        for (int a = 0; a < 3; a++) {
            double ta = (dmin[g * 3 + a] - o[a]) * inv[a], tb = (dmax[g * 3 + a] - o[a]) * inv[a];
            if (ta > tb) { double x = ta; ta = tb; tb = x; }
            t0 = Math.max(t0, ta);
            t1 = Math.min(t1, tb);
            if (t0 > t1) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------ BVH

    void build() {
        int n = t.n;
        order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        int cap = 2 * n + 1;
        bmin = new double[cap * 3];
        bmax = new double[cap * 3];
        left = new int[cap];
        start = new int[cap];
        count = new int[cap];
        double[] cen = new double[n * 3];
        for (int i = 0; i < n; i++)
            for (int a = 0; a < 3; a++) cen[i * 3 + a] = (t.p[i * 9 + a] + t.p[i * 9 + 3 + a] + t.p[i * 9 + 6 + a]) / 3;
        nodes = 0;
        node(0, n, cen);
    }

    int node(int lo, int hi, double[] cen) {
        int id = nodes++;
        double[] mn = {1e30, 1e30, 1e30}, mx = {-1e30, -1e30, -1e30}, cmn = {1e30, 1e30, 1e30}, cmx = {-1e30, -1e30, -1e30};
        for (int i = lo; i < hi; i++) {
            int tr = order[i];
            for (int v = 0; v < 3; v++)
                for (int a = 0; a < 3; a++) {
                    double x = t.p[tr * 9 + v * 3 + a];
                    mn[a] = Math.min(mn[a], x);
                    mx[a] = Math.max(mx[a], x);
                }
            for (int a = 0; a < 3; a++) {
                cmn[a] = Math.min(cmn[a], cen[tr * 3 + a]);
                cmx[a] = Math.max(cmx[a], cen[tr * 3 + a]);
            }
        }
        for (int a = 0; a < 3; a++) {
            bmin[id * 3 + a] = mn[a] - 1e-6;
            bmax[id * 3 + a] = mx[a] + 1e-6;
        }
        if (hi - lo <= 4) {
            left[id] = -1;
            start[id] = lo;
            count[id] = hi - lo;
            return id;
        }
        int axis = 0;
        for (int a = 1; a < 3; a++) if (cmx[a] - cmn[a] > cmx[axis] - cmn[axis]) axis = a;
        final int ax = axis;
        Integer[] sub = new Integer[hi - lo];
        for (int i = lo; i < hi; i++) sub[i - lo] = order[i];
        java.util.Arrays.sort(sub, (x, y) -> Double.compare(cen[x * 3 + ax], cen[y * 3 + ax]));
        for (int i = lo; i < hi; i++) order[i] = sub[i - lo];
        int mid = (lo + hi) / 2;
        int l = node(lo, mid, cen);
        int r = node(mid, hi, cen);
        left[id] = l;
        start[id] = r;
        count[id] = 0;
        return id;
    }

    boolean box(int id, double[] o, double[] inv, double tmax) {
        double t0 = 0, t1 = tmax;
        for (int a = 0; a < 3; a++) {
            double ta = (bmin[id * 3 + a] - o[a]) * inv[a], tb = (bmax[id * 3 + a] - o[a]) * inv[a];
            if (ta > tb) { double s = ta; ta = tb; tb = s; }
            t0 = Math.max(t0, ta);
            t1 = Math.min(t1, tb);
            if (t0 > t1) return false;
        }
        return true;
    }

    /** Ray/triangle (Moller-Trumbore, double); returns t or -1; bary in out[0..1]. */
    double tri(int k, double[] o, double[] d, double tmax, double[] out) {
        double ax = t.p[k * 9], ay = t.p[k * 9 + 1], az = t.p[k * 9 + 2];
        double e1x = t.p[k * 9 + 3] - ax, e1y = t.p[k * 9 + 4] - ay, e1z = t.p[k * 9 + 5] - az;
        double e2x = t.p[k * 9 + 6] - ax, e2y = t.p[k * 9 + 7] - ay, e2z = t.p[k * 9 + 8] - az;
        double px = d[1] * e2z - d[2] * e2y, py = d[2] * e2x - d[0] * e2z, pz = d[0] * e2y - d[1] * e2x;
        double det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1e-14) return -1;
        double inv = 1 / det;
        double sx = o[0] - ax, sy = o[1] - ay, sz = o[2] - az;
        double u = (sx * px + sy * py + sz * pz) * inv;
        if (u < -1e-9 || u > 1 + 1e-9) return -1;
        double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
        double v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv;
        if (v < -1e-9 || u + v > 1 + 1e-9) return -1;
        double tt = (e2x * qx + e2y * qy + e2z * qz) * inv;
        if (tt <= 1e-9 || tt >= tmax) return -1;
        out[0] = u;
        out[1] = v;
        return tt;
    }

    int alphaTest = 1; // 0 = cutouts opaque

    boolean passesAlpha(int k, double u, double v) {
        if (alphaTest == 0 || scene.materials[t.tile[k]].alphaMode() != 1) return true;
        double uu = t.uv[k * 6] * (1 - u - v) + t.uv[k * 6 + 2] * u + t.uv[k * 6 + 4] * v;
        double vv = t.uv[k * 6 + 1] * (1 - u - v) + t.uv[k * 6 + 3] * u + t.uv[k * 6 + 5] * v;
        return ((texel(t.tile[k], uu, vv) >>> 24) & 0xFF) / 255.0 >= 0.5;
    }

    int texel(int tile, double u, double v) {
        int g = RtScene.GRID, a = RtScene.ATLAS, tx0 = (tile % g) * 16, ty0 = (tile / g) * 16;
        int x = Math.max(tx0, Math.min(tx0 + 15, (int) Math.floor(u * a))), y = Math.max(ty0, Math.min(ty0 + 15, (int) Math.floor(v * a)));
        return scene.atlas[y * a + x];
    }

    static final class Hit {
        int tri = -1;
        double t, u, v;
    }

    /** Closest hit; {@code solidOnly} skips translucent (water) triangles (cull mask SOLID). */
    Hit closest(double[] o, double[] d, double tmax, boolean solidOnly) {
        Hit h = new Hit();
        h.t = tmax;
        double[] inv = {1 / d[0], 1 / d[1], 1 / d[2]}, bc = new double[2];
        int[] stack = new int[128];
        int sp = 0;
        stack[sp++] = 0;
        while (sp > 0) {
            int id = stack[--sp];
            if (!box(id, o, inv, h.t)) continue;
            if (left[id] < 0) {
                for (int i = start[id]; i < start[id] + count[id]; i++) {
                    int k = order[i];
                    if (solidOnly && t.translucent[k]) continue;
                    double tt = tri(k, o, d, h.t, bc);
                    if (tt > 0 && passesAlpha(k, bc[0], bc[1])) {
                        h.t = tt;
                        h.tri = k;
                        h.u = bc[0];
                        h.v = bc[1];
                    }
                }
            } else {
                stack[sp++] = left[id];
                stack[sp++] = start[id];
            }
        }
        for (int g = 0; g < dynGroups; g++)
            if (dynBox(g, o, inv, h.t))
                for (int k = ns + g * per; k < ns + (g + 1) * per; k++) {
                    double tt = tri(k, o, d, h.t, bc);
                    if (tt > 0) { h.t = tt; h.tri = k; h.u = bc[0]; h.v = bc[1]; }
                }
        return h;
    }

    boolean occluded(double[] o, double[] d, double tmax) {
        double[] inv = {1 / d[0], 1 / d[1], 1 / d[2]}, bc = new double[2];
        int[] stack = new int[128];
        int sp = 0;
        stack[sp++] = 0;
        while (sp > 0) {
            int id = stack[--sp];
            if (!box(id, o, inv, tmax)) continue;
            if (left[id] < 0) {
                for (int i = start[id]; i < start[id] + count[id]; i++) {
                    int k = order[i];
                    if (t.translucent[k]) continue;
                    double tt = tri(k, o, d, tmax, bc);
                    if (tt > 0 && passesAlpha(k, bc[0], bc[1])) return true;
                }
            } else {
                stack[sp++] = left[id];
                stack[sp++] = start[id];
            }
        }
        for (int g = 0; g < dynGroups; g++)
            if (dynBox(g, o, inv, tmax))
                for (int k = ns + g * per; k < ns + (g + 1) * per; k++) if (tri(k, o, d, tmax, bc) > 0) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------ surfaces

    static final class Surf {
        boolean valid;
        double[] pos = new double[3], n = new double[3], albedo = new double[3], dir;
        int tile, tri;
        double viewZ, dist;
    }

    double[] normal(int k, double[] d) {
        double e1x = t.p[k * 9 + 3] - t.p[k * 9], e1y = t.p[k * 9 + 4] - t.p[k * 9 + 1], e1z = t.p[k * 9 + 5] - t.p[k * 9 + 2];
        double e2x = t.p[k * 9 + 6] - t.p[k * 9], e2y = t.p[k * 9 + 7] - t.p[k * 9 + 1], e2z = t.p[k * 9 + 8] - t.p[k * 9 + 2];
        double nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= l; ny /= l; nz /= l;
        if (nx * d[0] + ny * d[1] + nz * d[2] > 0) { nx = -nx; ny = -ny; nz = -nz; }
        return new double[]{nx, ny, nz};
    }

    Surf surf(double[] o, double[] d, Hit h, boolean quantise) {
        Surf s = new Surf();
        s.dir = d;
        if (h.tri < 0) return s;
        s.valid = true;
        s.tri = h.tri;
        for (int a = 0; a < 3; a++) s.pos[a] = o[a] + d[a] * h.t;
        s.n = normal(h.tri, d);
        s.tile = t.tile[h.tri];
        s.dist = h.t;
        int k = h.tri;
        double uu = t.uv[k * 6] * (1 - h.u - h.v) + t.uv[k * 6 + 2] * h.u + t.uv[k * 6 + 4] * h.v;
        double vv = t.uv[k * 6 + 1] * (1 - h.u - h.v) + t.uv[k * 6 + 3] * h.u + t.uv[k * 6 + 5] * h.v;
        int tx = texel(s.tile, uu, vv), ti = t.tint[k];
        for (int a = 0; a < 3; a++) {
            double al = ((tx >>> (8 * a)) & 0xFF) / 255.0 * (((ti >>> (8 * a)) & 0xFF) / 255.0);
            s.albedo[a] = quantise ? Math.round((float) al * 255f) / 255.0 : al;
        }
        return s;
    }

    Surf[] primary() {
        Surf[] out = new Surf[N];
        org.joml.Vector3f f = cam.forward();
        double[] o = {cam.pos.x, cam.pos.y, cam.pos.z};
        IntStream.range(0, N).parallel().forEach(i -> {
            double[] d = cam.rayDir(i % W, i / W);
            Surf s = surf(o, d, closest(o, d, TMAX, false), true);
            if (s.valid) s.viewZ = (s.pos[0] - o[0]) * f.x + (s.pos[1] - o[1]) * f.y + (s.pos[2] - o[2]) * f.z;
            out[i] = s;
        });
        return out;
    }

    // ------------------------------------------------------------------------------------------------ sampling

    static double[] basisT(double[] n) {
        double[] a = Math.abs(n[0]) > 0.5 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
        double dot = a[0] * n[0] + a[1] * n[1] + a[2] * n[2];
        double[] tt = {a[0] - n[0] * dot, a[1] - n[1] * dot, a[2] - n[2] * dot};
        double l = Math.sqrt(tt[0] * tt[0] + tt[1] * tt[1] + tt[2] * tt[2]);
        return new double[]{tt[0] / l, tt[1] / l, tt[2] / l};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] local(double[] n, double x, double y, double z) {
        double[] tt = basisT(n), b = cross(n, tt);
        return new double[]{tt[0] * x + b[0] * y + n[0] * z, tt[1] * x + b[1] * y + n[1] * z, tt[2] * x + b[2] * y + n[2] * z};
    }

    /** Cosine-weighted direction via Shirley-Chiu concentric disk mapping. */
    static double[] cosine(double[] n, SplittableRandom r) {
        double a = 2 * r.nextDouble() - 1, b = 2 * r.nextDouble() - 1, rad, phi;
        if (a == 0 && b == 0) { rad = 0; phi = 0; }
        else if (Math.abs(a) > Math.abs(b)) { rad = a; phi = Math.PI / 4 * (b / a); }
        else { rad = b; phi = Math.PI / 2 - Math.PI / 4 * (a / b); }
        double x = rad * Math.cos(phi), y = rad * Math.sin(phi), z = Math.sqrt(Math.max(0, 1 - x * x - y * y));
        return local(n, x, y, z);
    }

    double[] sunDir(boolean soft, SplittableRandom r) {
        double[] s = {light.sunDir[0], light.sunDir[1], light.sunDir[2]};
        if (!soft || light.sunRadius <= 0) return s;
        double cosMax = Math.cos(light.sunRadius);
        double z = cosMax + (1 - cosMax) * r.nextDouble(), phi = 2 * Math.PI * r.nextDouble(), st = Math.sqrt(Math.max(0, 1 - z * z));
        return local(s, st * Math.cos(phi), st * Math.sin(phi), z);
    }

    static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static double[] norm(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    static double[] off(double[] p, double[] n, double e) {
        return new double[]{p[0] + n[0] * e, p[1] + n[1] * e, p[2] + n[2] * e};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    double[] sunIrr(double[] x, double[] n, boolean soft, SplittableRandom r) {
        double[] l = sunDir(soft, r);
        double c = dot(n, l);
        if (c <= 0 || occluded(off(x, n, EPS), l, TMAX)) return new double[3];
        return new double[]{light.sunE[0] * c, light.sunE[1] * c, light.sunE[2] * c};
    }

    /** Block-light irradiance by brute force: cosine rays that hit emissive surfaces (no light list). */
    double[] emitBrute(double[] x, double[] n, SplittableRandom r) {
        double[] d = cosine(n, r);
        Hit h = closest(off(x, n, EPS), d, TMAX, true);
        if (h.tri < 0) return new double[3];
        RtScene.Material m = scene.materials[t.tile[h.tri]];
        return new double[]{Math.PI * m.er(), Math.PI * m.eg(), Math.PI * m.eb()};
    }

    /** One light sample with UNIFORM light choice (the GPU picks by power). */
    double[] emitUniform(double[] x, double[] n, SplittableRandom r) {
        int cnt = scene.lights.size();
        if (cnt == 0) return new double[3];
        RtScene.Light l = scene.lights.get(r.nextInt(cnt));
        double a = r.nextDouble(), b = r.nextDouble();
        double[] p = {l.p0()[0] + l.e1()[0] * a + l.e2()[0] * b, l.p0()[1] + l.e1()[1] * a + l.e2()[1] * b, l.p0()[2] + l.e1()[2] * a + l.e2()[2] * b};
        double[] w = {p[0] - x[0], p[1] - x[1], p[2] - x[2]};
        double d2 = dot(w, w), d = Math.sqrt(d2);
        w[0] /= d; w[1] /= d; w[2] /= d;
        double cx = dot(n, w), cl = -(l.n()[0] * w[0] + l.n()[1] * w[1] + l.n()[2] * w[2]);
        if (cx <= 0 || cl <= 0 || occluded(off(x, n, EPS), w, d - 2 * EPS)) return new double[3];
        double k = cx * cl * l.area() * cnt / d2;
        return new double[]{l.radiance()[0] * k, l.radiance()[1] * k, l.radiance()[2] * k};
    }

    /** The GPU's estimator replicated (power CDF choice), for diagnostics only. */
    double[] emitPower(double[] x, double[] n, SplittableRandom r) {
        int cnt = scene.lights.size();
        double total = 0;
        for (RtScene.Light l : scene.lights) total += l.power();
        double u = r.nextDouble() * total, acc = 0;
        int idx = cnt - 1;
        for (int k = 0; k < cnt; k++) { acc += scene.lights.get(k).power(); if (acc >= u) { idx = k; break; } }
        RtScene.Light l = scene.lights.get(idx);
        double pdf = l.power() / total;
        double a = r.nextDouble(), b = r.nextDouble();
        double[] p = {l.p0()[0] + l.e1()[0] * a + l.e2()[0] * b, l.p0()[1] + l.e1()[1] * a + l.e2()[1] * b, l.p0()[2] + l.e1()[2] * a + l.e2()[2] * b};
        double[] w = {p[0] - x[0], p[1] - x[1], p[2] - x[2]};
        double d2 = dot(w, w), d = Math.sqrt(d2);
        w[0] /= d; w[1] /= d; w[2] /= d;
        double cx = dot(n, w), cl = -(l.n()[0] * w[0] + l.n()[1] * w[1] + l.n()[2] * w[2]);
        if (cx <= 0 || cl <= 0 || occluded(off(x, n, EPS), w, d - 2 * EPS)) return new double[3];
        double k = cx * cl * l.area() / (d2 * pdf);
        return new double[]{l.radiance()[0] * k, l.radiance()[1] * k, l.radiance()[2] * k};
    }

    double[] sky(double[] d) {
        if (d[1] < 0) return new double[]{light.horizon[0] * 0.3, light.horizon[1] * 0.3, light.horizon[2] * 0.3};
        double s = Math.sqrt(d[1]);
        return new double[]{light.horizon[0] + (light.zenith[0] - light.horizon[0]) * s, light.horizon[1] + (light.zenith[1] - light.horizon[1]) * s,
                light.horizon[2] + (light.zenith[2] - light.horizon[2]) * s};
    }

    double[] sunDisc(double[] d) {
        if (light.sunRadius <= 0) return new double[3];
        double cosMax = Math.cos(light.sunRadius);
        if (d[0] * light.sunDir[0] + d[1] * light.sunDir[1] + d[2] * light.sunDir[2] < cosMax) return new double[3];
        double k = 1 / (2 * Math.PI * (1 - cosMax));
        return new double[]{light.sunE[0] * k, light.sunE[1] * k, light.sunE[2] * k};
    }

    /** Direct-only outgoing radiance of a secondary hit (emission + diffuse of sun + one uniform light sample). */
    double[] shadeDirect(Surf h, SplittableRandom r) {
        RtScene.Material m = scene.materials[h.tile];
        double[] es = sunIrr(h.pos, h.n, true, r), ee = emitUniform(h.pos, h.n, r);
        double[] o = new double[3];
        o[0] = m.er() + h.albedo[0] / Math.PI * (es[0] + ee[0]);
        o[1] = m.eg() + h.albedo[1] / Math.PI * (es[1] + ee[1]);
        o[2] = m.eb() + h.albedo[2] / Math.PI * (es[2] + ee[2]);
        return o;
    }

    double[] traceShade(double[] o, double[] d, boolean mirror, SplittableRandom r, double[] distOut) {
        Hit h = closest(o, d, TMAX, true);
        if (h.tri < 0) {
            distOut[0] = TMAX;
            double[] s = sky(d);
            if (mirror) { double[] sd = sunDisc(d); s[0] += sd[0]; s[1] += sd[1]; s[2] += sd[2]; }
            return s;
        }
        distOut[0] = h.t;
        return shadeDirect(surf(o, d, h, false), r);
    }

    static double schlick(double f0, double c) {
        double m = Math.max(0, Math.min(1, 1 - c));
        return f0 + (1 - f0) * m * m * m * m * m;
    }

    // ------------------------------------------------------------------------------------------------ signals

    interface PixelEstimator {
        double[] sample(Surf s, int i, SplittableRandom r);
    }

    /** Mean over {@code spp} samples per valid pixel; null-surface pixels (sky) stay 0. */
    float[] estimate(Surf[] prim, int spp, long seed, PixelEstimator e) {
        float[] out = new float[N * 4];
        IntStream.range(0, N).parallel().forEach(i -> {
            Surf s = prim[i];
            if (!s.valid) return;
            SplittableRandom r = new SplittableRandom(seed * 1_000_003L + i);
            double a = 0, b = 0, c = 0;
            for (int k = 0; k < spp; k++) {
                double[] v = e.sample(s, i, r);
                a += v[0];
                b += v[1];
                c += v[2];
            }
            out[i * 4] = (float) (a / spp);
            out[i * 4 + 1] = (float) (b / spp);
            out[i * 4 + 2] = (float) (c / spp);
            out[i * 4 + 3] = 1;
        });
        return out;
    }

    boolean isWater(Surf s) { return scene.materials[s.tile].water(); }

    float[] sun(Surf[] prim, boolean soft, int spp) {
        return estimate(prim, soft ? spp : 1, 11, (s, i, r) -> isWater(s) ? new double[3] : sunIrr(s.pos, s.n, soft, r));
    }

    /** P5-3: sun visibility fraction (soft cone, {@code spp} rays) at each primary surface; water and back-facing -> 1. */
    float[] sunVis(Surf[] prim, int spp, long seed) {
        return estimate(prim, spp, seed, (s, i, r) -> {
            double[] l = sunDir(true, r);
            if (isWater(s) || dot(s.n, l) <= 0) return new double[]{1, 1, 1};
            double v = occluded(off(s.pos, s.n, EPS), l, TMAX) ? 0 : 1;
            return new double[]{v, v, v};
        });
    }

    float[] emit(Surf[] prim, int spp) {
        return estimate(prim, spp, 12, (s, i, r) -> isWater(s) ? new double[3] : emitBrute(s.pos, s.n, r));
    }

    float[] indirect(Surf[] prim, int spp) {
        return estimate(prim, spp, 13, (s, i, r) -> {
            if (isWater(s)) return new double[3];
            double[] d = cosine(s.n, r);
            double[] o = off(s.pos, s.n, EPS);
            Hit h = closest(o, d, TMAX, true);
            if (h.tri < 0) {
                double[] k = sky(d);
                return new double[]{k[0] * Math.PI, k[1] * Math.PI, k[2] * Math.PI};
            }
            Surf x2 = surf(o, d, h, false);
            double[] es = sunIrr(x2.pos, x2.n, true, r), ee = emitUniform(x2.pos, x2.n, r);
            return new double[]{x2.albedo[0] * (es[0] + ee[0]), x2.albedo[1] * (es[1] + ee[1]), x2.albedo[2] * (es[2] + ee[2])};
        });
    }

    static double lambda(double c, double a2) {
        double c2 = Math.max(c * c, 1e-8);
        return 0.5 * (-1 + Math.sqrt(1 + a2 * (1 - c2) / c2));
    }

    float[] spec(Surf[] prim, int spp) {
        return estimate(prim, spp, 14, (s, i, r) -> {
            RtScene.Material m = scene.materials[s.tile];
            double[] v = {-s.dir[0], -s.dir[1], -s.dir[2]};
            double nv = Math.max(dot(s.n, v), 1e-4);
            double[] dist = new double[1];
            if (m.water()) {
                double F = schlick(m.f0(), nv);
                double dn = dot(s.dir, s.n);
                double[] rd = {s.dir[0] - 2 * dn * s.n[0], s.dir[1] - 2 * dn * s.n[1], s.dir[2] - 2 * dn * s.n[2]};
                double[] lr = traceShade(off(s.pos, s.n, EPS), rd, true, r, dist);
                double[] lt = traceShade(off(s.pos, s.n, -EPS), s.dir, false, r, dist);
                double dt = Math.min(dist[0], 64);
                double[] o = new double[3];
                for (int a = 0; a < 3; a++) o[a] = F * lr[a] + (1 - F) * Math.exp(-light.waterExtinction[a] * dt) * lt[a];
                return o;
            }
            if (!m.spec()) return new double[3];
            double rough = light.polishedRoughness > 0 ? light.polishedRoughness : m.roughness();
            double alpha = rough * rough, a2 = alpha * alpha;
            double xi = r.nextDouble(), phi = 2 * Math.PI * r.nextDouble();
            double tan2 = a2 * xi / (1 - xi), ch = 1 / Math.sqrt(1 + tan2), sh = Math.sqrt(Math.max(0, 1 - ch * ch));
            double[] h = local(s.n, sh * Math.cos(phi), sh * Math.sin(phi), ch);
            double vh = dot(v, h);
            if (vh <= 0) return new double[3];
            double[] l = {2 * vh * h[0] - v[0], 2 * vh * h[1] - v[1], 2 * vh * h[2] - v[2]};
            double nl = dot(s.n, l);
            if (nl <= 0) return new double[3];
            double F = schlick(m.f0(), vh);
            double G2 = 1 / (1 + lambda(nv, a2) + lambda(nl, a2));
            double w = F * G2 * vh / (nv * ch);
            double[] L = traceShade(off(s.pos, s.n, EPS), l, false, r, dist);
            return new double[]{w * L[0], w * L[1], w * L[2]};
        });
    }

    // ------------------------------------------------------------------------------------------------ composition

    double fogAmount(double[] o, double[] d, double dist) {
        double a = light.fogDensity, b = light.fogFalloff;
        double e0 = Math.exp(-b * (o[1] - light.fogBase));
        double k = Math.abs(d[1]) > 1e-4 ? e0 * (1 - Math.exp(-b * d[1] * dist)) / (b * d[1]) : e0 * dist;
        return 1 - Math.exp(-a * k);
    }

    double[] fogColor(double[] d) {
        double s = Math.pow(Math.max(dot(d, new double[]{light.sunDir[0], light.sunDir[1], light.sunDir[2]}), 0), 8) * 0.5;
        double[] c = new double[3];
        for (int a = 0; a < 3; a++) c[a] = light.horizon[a] * 1.1 + (light.sunE[a] * 0.25 - light.horizon[a] * 1.1) * s;
        return c;
    }

    /** Same formula as compose.comp, from given diffuse irradiance and specular signals. */
    float[] compose(Surf[] prim, float[] diff, float[] spec) {
        float[] out = new float[N * 4];
        double[] o = {cam.pos.x, cam.pos.y, cam.pos.z};
        org.joml.Vector3f f = cam.forward();
        for (int i = 0; i < N; i++) {
            Surf s = prim[i];
            double[] d = s.dir != null ? s.dir : cam.rayDir(i % W, i / W);
            double[] c = new double[3];
            double dist;
            if (!s.valid) {
                double[] k = sky(d), sd = sunDisc(d);
                for (int a = 0; a < 3; a++) c[a] = k[a] + sd[a];
                dist = 512;
            } else {
                RtScene.Material m = scene.materials[s.tile];
                dist = s.viewZ / (d[0] * f.x + d[1] * f.y + d[2] * f.z);
                if (m.water()) for (int a = 0; a < 3; a++) c[a] = spec[i * 4 + a];
                else {
                    double kd = m.spec() ? 1 - schlick(m.f0(), Math.max(-dot(s.n, d), 0)) : 1;
                    double[] e = {m.er(), m.eg(), m.eb()};
                    for (int a = 0; a < 3; a++)
                        c[a] = e[a] + s.albedo[a] / Math.PI * diff[i * 4 + a] * kd + (m.spec() ? spec[i * 4 + a] : 0);
                }
            }
            if (light.fog) {
                double fa = fogAmount(o, d, dist);
                double[] fc = fogColor(d);
                for (int a = 0; a < 3; a++) c[a] = c[a] + (fc[a] - c[a]) * fa;
            }
            out[i * 4] = (float) c[0];
            out[i * 4 + 1] = (float) c[1];
            out[i * 4 + 2] = (float) c[2];
            out[i * 4 + 3] = 1;
        }
        return out;
    }
}
