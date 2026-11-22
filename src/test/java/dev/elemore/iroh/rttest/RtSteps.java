package dev.elemore.iroh.rttest;

import dev.elemore.iroh.gpu.VkGpuDevice.VkBuf;
import dev.elemore.iroh.rt.RtFrameData;
import org.joml.Vector3f;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import static dev.elemore.iroh.rt.RtFrameData.*;
import static dev.elemore.iroh.rttest.RtReplay.check;
import static dev.elemore.iroh.rttest.RtReplay.f;
import static dev.elemore.iroh.rttest.RtReplay.metric;

/** The rtReplay steps (see {@link RtReplay}). */
final class RtSteps {
    final RtGpu gpu;
    final CpuRef ref;
    final CpuRef.Surf[] prim;
    final RtCamera cam;
    final RtReplay.Light light;
    final int frames, W, H, N;
    final boolean quick;
    boolean[] agree;          // raster G-buffer and CPU primary see the same surface
    float[] sunOn;            // converged soft sun (alpha test on), for (f)
    final int[] rayTotals = new int[16];
    int rayFrames;
    static final int CPU_VERSION = 4;

    RtSteps(RtGpu gpu, CpuRef ref, CpuRef.Surf[] prim, RtCamera cam, RtReplay.Light light, int frames, boolean quick) {
        this.gpu = gpu;
        this.ref = ref;
        this.prim = prim;
        this.cam = cam;
        this.light = light;
        this.frames = frames;
        this.quick = quick;
        this.W = gpu.W;
        this.H = gpu.H;
        this.N = gpu.N;
        computeAgree();
    }

    int spp(int full) {
        return quick ? Math.max(16, full / 4) : full;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    float[] cached(String name, Supplier<float[]> s) {
        String key = name + "-" + W + "x" + H + (quick ? "-quick" : "") + "-v" + CPU_VERSION + "-" + Integer.toHexString(Arrays.hashCode(ref.t.p) ^ Arrays.hashCode(light.sunDir)
                ^ Float.floatToIntBits(cam.pos.x * 31 + cam.pos.z));
        Path p = RtReplay.OUT.resolve("cpuref").resolve(key + ".bin");
        try {
            if (Files.exists(p)) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(p)))) {
                    float[] v = new float[in.readInt()];
                    for (int i = 0; i < v.length; i++) v[i] = in.readFloat();
                    return v;
                }
            }
            long t0 = System.nanoTime();
            float[] v = s.get();
            metric(String.format(Locale.ROOT, "cpu reference %s: %.1f s", name, (System.nanoTime() - t0) / 1e9));
            Files.createDirectories(p.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)))) {
                out.writeInt(v.length);
                for (float x : v) out.writeFloat(x);
            }
            return v;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void computeAgree() {
        gpu.setFrame(cam, cam, 0, F_ALPHA_TEST | F_SOFT_SUN, light);
        gpu.gbuffer();
        int[] g = gpu.readUvec4(gpu.gbufCur);
        agree = new boolean[N];
        for (int i = 0; i < N; i++) {
            float rz = Float.intBitsToFloat(g[i * 4 + 2]);
            agree[i] = rz > 0 && prim[i].valid && Math.abs(prim[i].viewZ - rz) <= 1e-3 * rz + 1e-4;
        }
    }

    boolean[] mask(java.util.function.IntPredicate p) {
        boolean[] m = new boolean[N];
        for (int i = 0; i < N; i++) m[i] = agree[i] && p.test(i);
        return m;
    }

    boolean water(int i) {
        return ref.scene.materials[prim[i].tile].water();
    }

    boolean spec(int i) {
        RtScene.Material m = ref.scene.materials[prim[i].tile];
        return m.water() || m.spec();
    }

    void addRays(int frames) {
        int[] c = gpu.readCounters();
        for (int k = 0; k < 16; k++) rayTotals[k] += c[k];
        rayFrames += frames;
        gpu.resetCounters();
    }

    // ------------------------------------------------------------------------------------------------ (a)

    void stepA() throws IOException {
        metric("--- (a) BLAS/TLAS + primary-visibility debug view vs raster G-buffer");
        gpu.setFrame(cam, cam, 0, F_ALPHA_TEST | F_SOFT_SUN, light);
        gpu.gbuffer();
        gpu.resetCounters();
        VkBuf out = gpu.buf(N * 16L);
        gpu.trace("primary (debug view)", gpu.pPrimary, out);
        int[] g = gpu.readUvec4(gpu.gbufCur), p = gpu.readUvec4(out);
        int rtMiss = 0, rtExtra = 0, mismatch = 0, both = 0, cpuMismatch = 0;
        double maxRel = 0;
        float[] depth = new float[N * 4], dd = new float[N * 4], albedo = new float[N * 4], normal = new float[N * 4], zr = new float[N * 4], zt = new float[N * 4];
        int[] ids = new int[N];
        for (int i = 0; i < N; i++) {
            float rz = Float.intBitsToFloat(g[i * 4 + 2]), tz = Float.intBitsToFloat(p[i * 4]);
            ids[i] = tz > 0 ? p[i * 4 + 1] : -1;
            if (rz > 0 && tz <= 0) rtMiss++;
            if (rz <= 0 && tz > 0) rtExtra++;
            if (rz > 0 && tz > 0) {
                both++;
                double rel = Math.abs(rz - tz) / rz;
                maxRel = Math.max(maxRel, rel);
                if (rel > 1e-3) mismatch++;
            }
            if ((rz > 0) != prim[i].valid || (rz > 0 && Math.abs(prim[i].viewZ - rz) > 1e-3 * rz + 1e-4)) cpuMismatch++;
            float v = tz > 0 ? (float) Math.exp(-tz / 20.0) : 0;
            depth[i * 4] = depth[i * 4 + 1] = depth[i * 4 + 2] = v;
            float e = rz > 0 && tz > 0 ? (float) Math.min(1, Math.abs(rz - tz) / rz * 1000) : (rz > 0) != (tz > 0) ? 1 : 0;
            dd[i * 4] = e;
            zr[i * 4] = zr[i * 4 + 1] = zr[i * 4 + 2] = rz;
            zt[i * 4] = zt[i * 4 + 1] = zt[i * 4 + 2] = tz;
            int al = g[i * 4 + 1];
            for (int a = 0; a < 3; a++) albedo[i * 4 + a] = ((al >>> (8 * a)) & 0xFF) / 255f;
            int nn = g[i * 4];
            double ex = (nn & 0xFFFF) / 65535.0 * 2 - 1, ey = (nn >>> 16) / 65535.0 * 2 - 1, ez = 1 - Math.abs(ex) - Math.abs(ey);
            if (ez < 0) { double ox = ex; ex = (1 - Math.abs(ey)) * Math.signum(ox); ey = (1 - Math.abs(ox)) * Math.signum(ey); }
            double l = Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (rz > 0) { normal[i * 4] = (float) (ex / l * 0.5 + 0.5); normal[i * 4 + 1] = (float) (ey / l * 0.5 + 0.5); normal[i * 4 + 2] = (float) (ez / l * 0.5 + 0.5); }
        }
        metric(String.format(Locale.ROOT, "raster vs RT primary: both %d px, RT misses %d, RT extra %d, depth mismatch >0.1%% %d, max rel diff %.2e; CPU primary vs raster mismatches %d",
                both, rtMiss, rtExtra, mismatch, maxRel, cpuMismatch));
        check("(a) every raster pixel is hit by the RT primary ray (coverage, " + rtMiss + " misses <= 0.2%)", rtMiss <= N * 0.002);
        check("(a) RT primary hits nothing the raster left empty (" + rtExtra + " <= 0.2%)", rtExtra <= N * 0.002);
        check("(a) RT primary viewZ equals raster viewZ within 0.1% (" + mismatch + " px <= 0.5%)", mismatch <= N * 0.005);
        check("(a) CPU reference sees the raster surfaces (" + cpuMismatch + " px <= 1%)", cpuMismatch <= N * 0.01);
        RtImg.save("a_gbuffer_albedo", RtImg.linear(albedo, W, H, 1));
        RtImg.save("a_gbuffer_normal", RtImg.linear(normal, W, H, 1));
        RtImg.save("a_rt_primary_depth", RtImg.linear(depth, W, H, 1));
        RtImg.save("a_rt_instance_ids", RtImg.ids(ids, W, H));
        RtImg.save("a_depth_diff_x1000", RtImg.linear(dd, W, H, 1));
        addRays(1);
    }

    // ------------------------------------------------------------------------------------------------ (b)

    void stepB() throws IOException {
        metric("--- (b) sun shadows, hard (1 frame, exact) ");
        gpu.setFrame(cam, cam, 0, F_ALPHA_TEST, light);
        gpu.gbuffer();
        VkBuf out = gpu.buf(N * 16L);
        gpu.trace("sun shadow", gpu.pSun, out);
        float[] g = gpu.readVec4(out);
        float[] r = cached("sun-hard", () -> ref.sun(prim, false, 1));
        int lit = 0, mis = 0, n = 0, valMis = 0;
        for (int i = 0; i < N; i++) {
            if (!agree[i] || water(i)) continue;
            n++;
            boolean gl = RtImg.lum(g, i) > 0, cl = RtImg.lum(r, i) > 0;
            if (gl) lit++;
            if (gl != cl) mis++;
            else if (gl && Math.abs(RtImg.lum(g, i) - RtImg.lum(r, i)) > 1e-3 * RtImg.lum(r, i)) valMis++;
        }
        metric("hard sun: " + n + " px compared, " + lit + " lit, visibility mismatches " + mis + ", value mismatches " + valMis);
        check("(b) hard sun visibility equals the CPU reference (" + mis + " px <= 0.5%)", mis <= n * 0.005);
        check("(b) hard sun irradiance equals the CPU reference where lit (" + valMis + " px <= 0.5%)", valMis <= n * 0.005);
        RtImg.save("b_sun_hard", RtImg.linear(g, W, H, 0.3));
        addRays(1);
    }

    // ------------------------------------------------------------------------------------------------ (c) estimator unit check

    /** Lambert's exact irradiance from a convex polygon (radiance 1) at x with normal n; polygon fully above x's horizon. */
    static double lambert(double[][] poly, double[] x, double[] n) {
        double e = 0;
        for (int k = 0; k < poly.length; k++) {
            double[] a = CpuRef.norm(CpuRef.sub(poly[k], x)), b = CpuRef.norm(CpuRef.sub(poly[(k + 1) % poly.length], x));
            double th = Math.acos(Math.max(-1, Math.min(1, CpuRef.dot(a, b))));
            double[] c = CpuRef.norm(CpuRef.cross(a, b));
            e += th * CpuRef.dot(n, c);
        }
        return Math.abs(e) / 2;
    }

    void stepCUnit() {
        metric("--- (c) light estimator unit check vs Lambert's exact polygon irradiance (unoccluded, 4096 samples per point)");
        float[] p0 = {0, 200, 0}, e1 = {1, 0, 0}, e2 = {0, 1, 0}, ln = {0, 0, 1};   // unit quad facing +z at y=200
        double[][] poly = {{0, 200, 0}, {1, 200, 0}, {1, 201, 0}, {0, 201, 0}};
        double[][] pts = {{0.5, 200.5, 0.01}, {0.5, 200.5, 0.1}, {1.02, 200.5, 0.05}, {0.5, 199.6, 0.3}, {0.2, 200.9, 0.5}, {0.5, 200.5, 2}, {3, 202, 4}, {0.5, 200.5, 10},
                {1.3, 200.5, 0.5}, {0.5, 198.9, 0.02}, {0.5, 200.0, 0.007}, {0.25, 200.0, 0.007}, {0.75, 200.0, 0.05}};
        // receivers face the light (-z side normals); every configuration keeps the quad above the receiver's horizon
        double[][] nrm = {{0, 0, -1}, {0, 0, -1}, {-0.3, 0, -0.954}, {0, 0.6, -0.8}, {0, 0, -1}, {0, 0, -1}, {-0.5, -0.3, -0.812}, {0, 0, -1}, {-0.8, 0, -0.6}, {0, 1, 0}, {0, 1, 0}, {0, 1, 0}, {0, 1, 0}};
        java.nio.ByteBuffer lb = RtGpu.le(80);
        lb.putFloat(p0[0]).putFloat(p0[1]).putFloat(p0[2]).putFloat(1).putFloat(e1[0]).putFloat(e1[1]).putFloat(e1[2]).putFloat(1)
                .putFloat(e2[0]).putFloat(e2[1]).putFloat(e2[2]).putFloat(1).putFloat(ln[0]).putFloat(ln[1]).putFloat(ln[2]).putFloat(0)
                .putFloat(1).putFloat(1).putFloat(1).putFloat(0);
        VkBuf lights = gpu.buf(80), ptsB = gpu.buf(pts.length * 32L), out = gpu.buf(pts.length * 16L);
        gpu.up(lights, 0, lb.flip());
        java.nio.ByteBuffer pb = RtGpu.le(pts.length * 32);
        for (int k = 0; k < pts.length; k++) {
            double l = Math.sqrt(CpuRef.dot(nrm[k], nrm[k]));
            pb.putFloat((float) pts[k][0]).putFloat((float) pts[k][1]).putFloat((float) pts[k][2]).putFloat(0)
                    .putFloat((float) (nrm[k][0] / l)).putFloat((float) (nrm[k][1] / l)).putFloat((float) (nrm[k][2] / l)).putFloat(0);
        }
        gpu.up(ptsB, 0, pb.flip());
        gpu.uploadBarrier();
        int saved = gpu.fd.lightCount;
        gpu.fd.lightCount = 1;
        gpu.setFrame(cam, cam, 0, F_ALPHA_TEST | F_SOFT_SUN, light);
        gpu.fd.lightCount = saved;
        java.nio.ByteBuffer push = org.lwjgl.system.MemoryUtil.memAlloc(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        push.putInt(0, pts.length).putInt(4, 4096);
        VkBuf[] b = gpu.scene(ptsB, out);
        b[5] = lights;
        gpu.comp.dispatch(gpu.pEmitUnit, gpu.tlas.handle, b, push, 1, 1, 1);
        gpu.comp.barrier();
        gpu.sync();
        org.lwjgl.system.MemoryUtil.memFree(push);
        java.nio.ByteBuffer r = gpu.dev.readBuffer(out, 0, pts.length * 16);
        gpu.comp.resetSets();
        double worst = 0, worstZ = 0;
        for (int k = 0; k < pts.length; k++) {
            double[] nn = CpuRef.norm(nrm[k]);
            double exact = lambert(poly, pts[k], nn), got = r.getFloat(k * 16 + 4), var = r.getFloat(k * 16 + 12);
            double se = Math.sqrt(Math.max(0, var) / 4096);
            double z = Math.abs(got - exact) / Math.max(se, 1e-6 * exact);
            worst = Math.max(worst, Math.abs(got - exact) / exact);
            if (Math.abs(got - exact) / exact > 0.005) worstZ = Math.max(worstZ, z);   // sub-0.5% offsets are float noise
            metric(String.format(Locale.ROOT, "    point %d: exact %.5f GPU %.5f (rel %+.4f, %.1f std errors, per-sample rel std %.3f)", k, exact, got, (got - exact) / exact,
                    z, Math.sqrt(Math.max(0, var)) / exact));
        }
        check("(c) light estimator is unbiased against Lambert's exact formula (every point within 4 standard errors or 0.5%; worst rel err " + f(worst) + ")", worstZ <= 4);
        gpu.setFrame(cam, cam, 0, F_ALPHA_TEST | F_SOFT_SUN, light);
    }

    /** Diagnostic: GPU estimator at the brightest scene points (real lights + TLAS) vs CPU estimators at 200k samples. */
    double probePoints(boolean[] m, float[] ref2) {
        double worst = 0;
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < N; i++) if (m[i] && RtImg.lum(ref2, i) >= 5) idx.add(i);
        if (idx.isEmpty()) return 0;
        java.nio.ByteBuffer pb = RtGpu.le(idx.size() * 32);
        for (int i : idx)
            pb.putFloat((float) prim[i].pos[0]).putFloat((float) prim[i].pos[1]).putFloat((float) prim[i].pos[2]).putFloat(0)
                    .putFloat((float) prim[i].n[0]).putFloat((float) prim[i].n[1]).putFloat((float) prim[i].n[2]).putFloat(0);
        VkBuf ptsB = gpu.buf(idx.size() * 32L), out = gpu.buf(idx.size() * 16L);
        gpu.up(ptsB, 0, pb.flip());
        gpu.uploadBarrier();
        java.nio.ByteBuffer push = org.lwjgl.system.MemoryUtil.memAlloc(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        push.putInt(0, idx.size()).putInt(4, 65536);
        gpu.comp.dispatch(gpu.pEmitUnit, gpu.tlas.handle, gpu.scene(ptsB, out), push, 1, 1, 1);
        gpu.comp.barrier();
        gpu.sync();
        org.lwjgl.system.MemoryUtil.memFree(push);
        java.nio.ByteBuffer r = gpu.dev.readBuffer(out, 0, idx.size() * 16);
        gpu.comp.resetSets();
        for (int k = 0; k < idx.size(); k++) {
            int i = idx.get(k);
            java.util.SplittableRandom rr = new java.util.SplittableRandom(5);
            double a = 0, b = 0;
            for (int j = 0; j < 200_000; j++) { a += ref.emitPower(prim[i].pos, prim[i].n, rr)[1]; b += ref.emitBrute(prim[i].pos, prim[i].n, rr)[1]; }
            metric(String.format(Locale.ROOT, "    probe px %d,%d: GPU(65536 samples) G %.3f  CPU-power(200k) %.3f  CPU-brute(200k) %.3f", i % W, i / W, r.getFloat(k * 16 + 4), a / 2e5, b / 2e5));
            worst = Math.max(worst, Math.abs(r.getFloat(k * 16 + 4) - b / 2e5) / (b / 2e5));
        }
        return worst;
    }

    // ------------------------------------------------------------------------------------------------ static run: b soft, c, d, e, g, variance

    void staticRun(String step) throws IOException {
        metric("--- static convergence run: " + frames + " frames (soft sun, block light, GI without cache, reflections)");
        int flags = F_ALPHA_TEST | F_SOFT_SUN | F_REJECT;
        VkBuf sun = gpu.buf(N * 16L), emit = gpu.buf(N * 16L), gi = gpu.buf(N * 16L), refl = gpu.buf(N * 16L), diff = gpu.buf(N * 16L);
        RtGpu.Accum aSun = gpu.new Accum(), aEmit = gpu.new Accum(), aGi = gpu.new Accum(), aRefl = gpu.new Accum(), aDiff = gpu.new Accum();
        float[] cpuDiff = null;
        List<double[]> curve = new ArrayList<>();
        float[] rSun = cached("sun-soft", () -> ref.sun(prim, true, spp(1024)));
        float[] rEmit = cached("emit", () -> ref.emit(prim, spp(8192)));
        float[] rInd = cached("indirect", () -> ref.indirect(prim, spp(2048)));
        float[] rSpec = cached("spec", () -> ref.spec(prim, spp(4096)));
        cpuDiff = new float[N * 4];
        for (int i = 0; i < N * 4; i++) cpuDiff[i] = rSun[i] + rEmit[i] + rInd[i];
        boolean[] mDiff = mask(i -> !water(i));
        gpu.resetCounters();
        for (int k = 0; k < frames; k++) {
            gpu.setFrame(cam, cam, k, flags, light);
            gpu.gbuffer();
            gpu.trace("sun shadow", gpu.pSun, sun);
            gpu.trace("block light", gpu.pEmit, emit);
            gpu.trace("GI 1 bounce", gpu.pGi, gpu.cache, gi);
            gpu.trace("reflections", gpu.pRefl, refl);
            gpu.sum(sun, emit, gi, diff);
            boolean reset = k == 0;
            aSun.step(sun, 1e9f, reset);
            aEmit.step(emit, 1e9f, reset);
            aGi.step(gi, 1e9f, reset);
            aRefl.step(refl, 1e9f, reset);
            aDiff.step(diff, 1e9f, reset);
            if (Integer.bitCount(k + 1) == 1) {
                curve.add(new double[]{k + 1, relErr(gpu.readVec4(aDiff.current()), cpuDiff, mDiff)});
            }
        }
        addRays(frames);
        float[] gSun = gpu.readVec4(aSun.current()), gEmit = gpu.readVec4(aEmit.current()), gGi = gpu.readVec4(aGi.current()),
                gRefl = gpu.readVec4(aRefl.current()), gDiff = gpu.readVec4(aDiff.current());
        sunOn = gSun;

        double[] e = RtImg.err(gSun, rSun, mDiff);
        metric("(b) soft sun vs CPU (" + spp(1024) + " spp): relMAE " + f(e[0]) + " bias " + f(e[1]) + " over " + (int) e[3] + " px");
        check("(b) soft sun converges to the CPU reference (relMAE " + f(e[0]) + " <= 0.03, |bias| <= 0.01)", e[0] <= 0.03 && Math.abs(e[1]) <= 0.01);

        float[] rEmit2 = cached("emit-seed2", () -> {
            float[] v = ref.estimate(prim, spp(8192), 99, (s, i, r) -> water(i) ? new double[3] : ref.emitBrute(s.pos, s.n, r));
            return v;
        });
        boolean[] mLit = mask(i -> !water(i));   // no threshold on the noisy reference: that would be a selection bias
        double[] floor = RtImg.err(rEmit2, rEmit, mLit);
        float[] rEmitP = cached("emit-power", () -> ref.estimate(prim, spp(2048), 97, (s, i, r) -> water(i) ? new double[3] : ref.emitPower(s.pos, s.n, r)));
        double probe = probePoints(mLit, rEmitP);
        check("(c) at the brightest scene points (touching emitters; occlusion included) the GPU estimator equals brute force (worst rel diff " + f(probe) + " <= 0.03)", probe <= 0.03);
        e = RtImg.err(gEmit, rEmit, mLit);
        metric("(c) block light vs CPU brute force (cosine rays hitting emitters, " + spp(8192) + " spp): relMAE " + f(e[0]) + " bias " + f(e[1])
                + " over " + (int) e[3] + " px; CPU-vs-CPU noise floor relMAE " + f(floor[0]) + " bias " + f(floor[1]));
        check("(c) block light image (RIS over the light list) agrees with brute force within the noise floor (relMAE " + f(e[0]) + " <= floor " + f(floor[0])
                + " + 0.02, |bias| " + f(Math.abs(e[1])) + " <= 0.05)", e[0] <= floor[0] + 0.02 && Math.abs(e[1]) <= 0.05);

        e = RtImg.err(gGi, rInd, mDiff);
        metric("(d) GI (no cache) vs CPU (" + spp(2048) + " spp, uniform light choice at the bounce): relMAE " + f(e[0]) + " bias " + f(e[1]));
        check("(d) one-bounce GI converges to the CPU reference (relMAE " + f(e[0]) + " <= 0.05, |bias| <= 0.02)", e[0] <= 0.05 && Math.abs(e[1]) <= 0.02);

        boolean[] mSpec = mask(this::spec);
        e = RtImg.err(gRefl, rSpec, mSpec);
        metric("(e) reflections vs CPU (" + spp(4096) + " spp, GGX NDF sampling vs GPU VNDF): relMAE " + f(e[0]) + " bias " + f(e[1]) + " over " + (int) e[3] + " water/glossy px");
        boolean[] mWater = mask(this::water);
        double[] ew = RtImg.err(gRefl, rSpec, mWater);
        boolean[] mGloss = mask(i -> spec(i) && !water(i));
        double[] eg = RtImg.err(gRefl, rSpec, mGloss);
        metric("    water only relMAE " + f(ew[0]) + " (" + (int) ew[3] + " px), glossy only relMAE " + f(eg[0]) + " bias " + f(eg[1]) + " (" + (int) eg[3] + " px)");
        check("(e) reflections converge to the CPU reference (relMAE " + f(e[0]) + " <= 0.05, |bias| <= 0.02)", e[0] <= 0.05 && Math.abs(e[1]) <= 0.02);

        // (g) composition
        VkBuf outB = gpu.buf(N * 16L);
        gpu.compose(aDiff.current(), aRefl.current(), outB);
        float[] gFinal = gpu.readVec4(outB);
        float[] cFinal = ref.compose(prim, cpuDiff, rSpec);
        boolean[] mAll = new boolean[N];
        for (int i = 0; i < N; i++) mAll[i] = agree[i] || (!prim[i].valid);
        e = RtImg.err(gFinal, cFinal, mAll);
        metric("(g) composed HDR (GPU converged) vs CPU composed reference: relMAE " + f(e[0]) + " bias " + f(e[1]));
        check("(g) composed image matches the CPU reference (relMAE " + f(e[0]) + " <= 0.03)", e[0] <= 0.03);
        // exactness of the composition maths: GPU compose on the CPU's signals
        VkBuf cd = gpu.buf(N * 16L), cs = gpu.buf(N * 16L);
        gpu.writeVec4(cd, cpuDiff);
        gpu.writeVec4(cs, rSpec);
        gpu.compose(cd, cs, outB);
        float[] gOnCpu = gpu.readVec4(outB);
        float[] cOnG = ref.compose(gbufSurfaces(gpu.readUvec4(gpu.gbufCur)), cpuDiff, rSpec);
        double maxRel = 0;
        for (int i = 0; i < N; i++) {
            double a = RtImg.lum(gOnCpu, i), b = RtImg.lum(cOnG, i);
            maxRel = Math.max(maxRel, Math.abs(a - b) / Math.max(1e-3, b));
        }
        check("(g) GPU composition + fog equals the CPU formula on identical inputs (G-buffer surfaces, CPU signals) (max rel err " + String.format(Locale.ROOT, "%.1e", maxRel) + " <= 1e-3)", maxRel <= 1e-3);
        light.fog = false;
        gpu.setFrame(cam, cam, 0, flags, light);
        gpu.compose(aDiff.current(), aRefl.current(), outB);
        float[] gNoFog = gpu.readVec4(outB);
        light.fog = true;

        // (h, static half) variance vs frame count
        StringBuilder sb = new StringBuilder("(h) diffuse mean per-pixel relative error vs frame count (static camera, history unbounded):");
        for (double[] c : curve) sb.append(String.format(Locale.ROOT, " n=%d %.4f", (int) c[0], c[1]));
        metric(sb.toString());
        double slope = Math.log(curve.get(Math.min(4, curve.size() - 1))[1] / curve.get(0)[1]) / Math.log(curve.get(Math.min(4, curve.size() - 1))[0]);
        metric(String.format(Locale.ROOT, "    log-log slope over n=1..16: %.3f (pure Monte Carlo noise: -0.5)", slope));
        check("(h) noise falls like 1/sqrt(n) while noise dominates (slope " + f(slope) + " in [-0.65, -0.35])", slope >= -0.65 && slope <= -0.35);

        RtImg.save("b_sun_soft", RtImg.linear(gSun, W, H, 0.3));
        RtImg.save("c_block_light", RtImg.linear(gEmit, W, H, 0.3));
        RtImg.save("c_block_light_cpu", RtImg.linear(rEmit, W, H, 0.3));
        RtImg.save("d_gi", RtImg.linear(gGi, W, H, 0.6));
        RtImg.save("d_gi_cpu", RtImg.linear(rInd, W, H, 0.6));
        RtImg.save("e_reflections", RtImg.hdr(gRefl, W, H, 1.0));
        RtImg.save("g_final", RtImg.hdr(gFinal, W, H, 1.0));
        RtImg.save("g_final_cpu", RtImg.hdr(cFinal, W, H, 1.0));
        RtImg.save("g_final_nofog", RtImg.hdr(gNoFog, W, H, 1.0));
        RtImg.save("g_final_diff", RtImg.diff(gFinal, cFinal, W, H, 0.1));
    }

    /** CPU surfaces decoded from the GPU G-buffer (same albedo bytes, normal, tile, viewZ as the GPU passes see). */
    CpuRef.Surf[] gbufSurfaces(int[] g) {
        CpuRef.Surf[] out = new CpuRef.Surf[N];
        org.joml.Vector3f fw = cam.forward();
        for (int i = 0; i < N; i++) {
            CpuRef.Surf s = new CpuRef.Surf();
            s.dir = cam.rayDir(i % W, i / W);
            float vz = Float.intBitsToFloat(g[i * 4 + 2]);
            out[i] = s;
            if (vz <= 0) continue;
            s.valid = true;
            s.viewZ = vz;
            int nn = g[i * 4];
            double ex = (nn & 0xFFFF) / 65535.0 * 2 - 1, ey = (nn >>> 16) / 65535.0 * 2 - 1, ez = 1 - Math.abs(ex) - Math.abs(ey);
            if (ez < 0) { double ox = ex; ex = (1 - Math.abs(ey)) * (ox >= 0 ? 1 : -1); ey = (1 - Math.abs(ox)) * (ey >= 0 ? 1 : -1); }
            double l = Math.sqrt(ex * ex + ey * ey + ez * ez);
            s.n = new double[]{ex / l, ey / l, ez / l};
            int al = g[i * 4 + 1];
            for (int a = 0; a < 3; a++) s.albedo[a] = ((al >>> (8 * a)) & 0xFF) / 255.0;
            s.tile = al >>> 24;
            double t = vz / (s.dir[0] * fw.x + s.dir[1] * fw.y + s.dir[2] * fw.z);
            for (int a = 0; a < 3; a++) s.pos[a] = new double[]{cam.pos.x, cam.pos.y, cam.pos.z}[a] + s.dir[a] * t;
        }
        return out;
    }

    /** Mean per-pixel relative error |g - r| / (r + 0.05) (luminance): a noise measure not dominated by bright pixels. */
    static double relErr(float[] g, float[] r, boolean[] m) {
        double s = 0;
        int n = 0;
        for (int i = 0; i < m.length; i++)
            if (m[i]) {
                double b = RtImg.lum(r, i);
                s += Math.abs(RtImg.lum(g, i) - b) / (b + 0.05);
                n++;
            }
        return n == 0 ? 0 : s / n;
    }

    // ------------------------------------------------------------------------------------------------ (d) cache

    void stepDCache() throws IOException {
        metric("--- (d) GI with the radiance cache");
        float[] rInd = cached("indirect", () -> ref.indirect(prim, spp(2048)));
        boolean[] mDiff = mask(i -> !water(i));
        int flags = F_ALPHA_TEST | F_SOFT_SUN | F_REJECT | F_CACHE;
        gpu.clearCache();
        VkBuf gi = gpu.buf(N * 16L);
        RtGpu.Accum aGi = gpu.new Accum();
        // warm-up frames fill the cache (as in game: it is persistent across frames), then measure
        int warm = Math.max(8, frames / 8);
        for (int k = 0; k < warm; k++) {
            gpu.setFrame(cam, cam, 10_000 + k, flags, light);
            gpu.gbuffer();
            gpu.trace("GI 1 bounce + cache", gpu.pGi, gpu.cache, gi);
            gpu.cacheResolve();
        }
        int[] before = gpu.readCounters();
        gpu.resetCounters();
        for (int k = 0; k < frames; k++) {
            gpu.setFrame(cam, cam, 20_000 + k, flags, light);
            gpu.gbuffer();
            gpu.trace("GI 1 bounce + cache", gpu.pGi, gpu.cache, gi);
            gpu.cacheResolve();
            aGi.step(gi, 1e9f, k == 0);
        }
        int[] c = gpu.readCounters();
        gpu.resetCounters();
        float[] g = gpu.readVec4(aGi.current());
        double[] e = RtImg.err(g, rInd, mDiff);
        double raysPerPx = c[RtGpu.PASS_GI] / (double) frames / N;
        double hitRate = c[RtGpu.COUNTER_CACHE_HITS] / (double) Math.max(1, c[9]);
        metric(String.format(Locale.ROOT, "(d) GI + cache vs CPU: relMAE %s bias %s; cache answered %.1f%% of secondary hits (%.2f hits/px/frame); GI rays/pixel %.2f (no cache: %.2f)",
                f(e[0]), f(e[1]), 100 * hitRate, c[9] / (double) frames / N, raysPerPx, rayFrames > 0 ? rayTotals[RtGpu.PASS_GI] / (double) rayFrames / N : Double.NaN));
        check("(d) cached GI bias stays within 10% of the reference (bias " + f(e[1]) + ")", Math.abs(e[1]) <= 0.10);
        check("(d) cached GI relMAE <= 0.10 (" + f(e[0]) + ")", e[0] <= 0.10);
        check("(d) the cache answers most secondary hits (" + String.format(Locale.ROOT, "%.0f%%", 100 * hitRate) + " >= 50%)", hitRate >= 0.5);
        metric("    cache entries in use: " + cacheEntries() + " of " + RtGpu.CACHE_ENTRIES);
        RtImg.save("d_gi_cache", RtImg.linear(g, W, H, 0.6));
        RtImg.save("d_gi_cache_diff", RtImg.diff(g, rInd, W, H, 0.3));
    }

    // ------------------------------------------------------------------------------------------------ (f)

    void stepF() throws IOException {
        metric("--- (f) alpha-tested geometry (leaves, glass pane) in shadow rays");
        if (sunOn == null) {
            metric("(f) needs the static run (step b soft); skipped");
            return;
        }
        float[] rOn = cached("sun-soft", () -> ref.sun(prim, true, spp(1024)));
        ref.alphaTest = 0;
        float[] rOff = cached("sun-soft-noalpha", () -> ref.sun(prim, true, spp(1024)));
        ref.alphaTest = 1;
        int flags = F_SOFT_SUN | F_REJECT;   // alpha test OFF on the GPU: cutouts block like opaque geometry
        VkBuf sun = gpu.buf(N * 16L);
        RtGpu.Accum a = gpu.new Accum();
        int fr = Math.max(32, frames / 2);
        for (int k = 0; k < fr; k++) {
            gpu.setFrame(cam, cam, k, flags, light);
            gpu.gbuffer();
            gpu.trace("sun shadow (alpha off)", gpu.pSun, sun);
            a.step(sun, 1e9f, k == 0);
        }
        float[] gOff = gpu.readVec4(a.current());
        boolean[] affected = mask(i -> !water(i) && Math.abs(RtImg.lum(rOn, i) - RtImg.lum(rOff, i)) > 0.05 * Math.max(0.05, RtImg.lum(rOn, i)));
        int n = 0;
        for (boolean b : affected) if (b) n++;
        double[] eOn = RtImg.err(sunOn, rOn, affected), eOffVsOn = RtImg.err(gOff, rOn, affected), eOffVsOff = RtImg.err(gOff, rOff, affected);
        float[] rOn2 = cached("sun-soft-seed2", () -> ref.estimate(prim, spp(1024), 77, (s, i, r) -> water(i) ? new double[3] : ref.sunIrr(s.pos, s.n, true, r)));
        double floor = RtImg.err(rOn2, rOn, affected)[0];
        // two unbiased estimates with S (CPU) and F (GPU) samples differ by ~ sigma*sqrt(1/S + 1/F); the CPU-vs-CPU floor
        // is sigma*sqrt(2/S), so the expected GPU-vs-CPU relMAE is floor/sqrt(2)*sqrt(1 + S/F); 25% margin + 0.01
        double cpuS = spp(1024);
        double limOn = 1.25 * floor / Math.sqrt(2) * Math.sqrt(1 + cpuS / frames) + 0.01, limOff = 1.25 * floor / Math.sqrt(2) * Math.sqrt(1 + cpuS / fr) + 0.01;
        metric("    CPU-vs-CPU noise floor on these pixels: relMAE " + f(floor) + " -> expected limits ON " + f(limOn) + " (GPU " + frames + " frames), OFF " + f(limOff) + " (" + fr + " frames)");
        metric(String.format(Locale.ROOT, "(f) %d px see light through leaves/pane holes: alpha ON vs ref relMAE %s; alpha OFF vs ref %s; alpha OFF vs ref-without-alpha %s",
                n, f(eOn[0]), f(eOffVsOn[0]), f(eOffVsOff[0])));
        check("(f) the scene has light passing cutout holes (" + n + " px > 20)", n > 20);
        check("(f) alpha test in shadow rays matches the reference (relMAE " + f(eOn[0]) + " <= noise limit " + f(limOn) + ")", eOn[0] <= limOn);
        check("(f) without the alpha test the error is >= 3x larger (" + f(eOffVsOn[0]) + ")", eOffVsOn[0] >= 3 * eOn[0]);
        check("(f) GPU with alpha off matches the CPU with alpha off (relMAE " + f(eOffVsOff[0]) + " <= noise limit " + f(limOff) + "): the switch does exactly that", eOffVsOff[0] <= limOff);
        RtImg.save("f_sun_alpha_off", RtImg.linear(gOff, W, H, 0.3));
    }

    // ------------------------------------------------------------------------------------------------ (h)

    RtCamera pathCam(int k, int n) {
        float t = k / (float) (n - 1);
        Vector3f d = new Vector3f(7f * t, 0.5f * t, -3f * t);
        return new RtCamera(new Vector3f(cam.pos).add(d), new Vector3f(cam.target).add(d.x * 0.7f, d.y, d.z * 0.7f), cam.fovYDeg, W, H);
    }

    float[][] motion(boolean reject, int n) {
        int flags = F_ALPHA_TEST | F_SOFT_SUN | F_CACHE | (reject ? F_REJECT : 0);
        VkBuf sun = gpu.buf(N * 16L), emit = gpu.buf(N * 16L), gi = gpu.buf(N * 16L), refl = gpu.buf(N * 16L), diff = gpu.buf(N * 16L), out = gpu.buf(N * 16L);
        RtGpu.Accum aD = gpu.new Accum(), aS = gpu.new Accum();
        gpu.clearCache();
        float[] first = null;
        for (int k = 0; k < n; k++) {
            RtCamera c = pathCam(k, n), p = pathCam(Math.max(0, k - 1), n);
            gpu.setFrame(c, p, 30_000 + k, flags, light);
            gpu.gbuffer();
            gpu.trace("sun shadow", gpu.pSun, sun);
            gpu.trace("block light", gpu.pEmit, emit);
            gpu.trace("GI 1 bounce + cache", gpu.pGi, gpu.cache, gi);
            gpu.cacheResolve();
            gpu.trace("reflections", gpu.pRefl, refl);
            gpu.sum(sun, emit, gi, diff);
            aD.step(diff, 32, k == 0);
            aS.step(refl, 32, k == 0);
            gpu.compose(aD.current(), aS.current(), out);
            if (k == 0) first = gpu.readVec4(out);
        }
        return new float[][]{gpu.readVec4(out), gpu.readVec4(aD.current()), first};
    }

    void stepH() throws IOException {
        metric("--- (h) temporal accumulation under a scripted 30-frame camera move (history cap 32)");
        int n = 30;
        float[][] on = motion(true, n), off = motion(false, n);
        RtCamera last = pathCam(n - 1, n);
        CpuRef r2 = new CpuRef(ref.scene, light, last);
        CpuRef.Surf[] p2 = r2.primary();
        String tag = "-cam29";
        float[] s = cachedFor(r2, "sun-soft" + tag, () -> r2.sun(p2, true, spp(512)));
        float[] em = cachedFor(r2, "emit" + tag, () -> r2.emit(p2, spp(4096)));
        float[] in = cachedFor(r2, "indirect" + tag, () -> r2.indirect(p2, spp(1024)));
        float[] sp = cachedFor(r2, "spec" + tag, () -> r2.spec(p2, spp(2048)));
        float[] d = new float[N * 4];
        for (int i = 0; i < N * 4; i++) d[i] = s[i] + em[i] + in[i];
        float[] refImg = r2.compose(p2, d, sp);
        float[] hist = on[1];
        boolean[] dis = new boolean[N], stable = new boolean[N];
        int nd = 0, ns = 0;
        for (int i = 0; i < N; i++) {
            if (!p2[i].valid) continue;
            float cnt = hist[i * 4 + 3];
            if (cnt > 0 && cnt <= 6) { dis[i] = true; nd++; }
            if (cnt >= 24) { stable[i] = true; ns++; }
        }
        double[] eDisOn = RtImg.err(on[0], refImg, dis), eDisOff = RtImg.err(off[0], refImg, dis);
        double[] eStOn = RtImg.err(on[0], refImg, stable), eStOff = RtImg.err(off[0], refImg, stable);
        metric(String.format(Locale.ROOT, "(h) frame 29: %d px disoccluded within 6 frames, %d px with full history", nd, ns));
        metric("    disoccluded: rejection ON relMAE " + f(eDisOn[0]) + " bias " + f(eDisOn[1]) + "; rejection OFF relMAE " + f(eDisOff[0]) + " bias " + f(eDisOff[1]));
        metric("    full history: rejection ON relMAE " + f(eStOn[0]) + "; OFF " + f(eStOff[0]));
        check("(h) the move disoccludes pixels and their history resets (" + nd + " px > 20)", nd > 20);
        check("(h) no ghosting: on disoccluded pixels the bias with rejection is <= half of without (" + f(eDisOn[1]) + " vs " + f(eDisOff[1]) + ") and the error is lower ("
                + f(eDisOn[0]) + " vs " + f(eDisOff[0]) + ")", Math.abs(eDisOn[1]) <= 0.5 * Math.abs(eDisOff[1]) && eDisOn[0] < eDisOff[0]);
        check("(h) converged areas stay close to the reference while moving (relMAE " + f(eStOn[0]) + " <= 0.10)", eStOn[0] <= 0.10);
        float[] cnt = new float[N * 4];
        for (int i = 0; i < N; i++) cnt[i * 4] = cnt[i * 4 + 1] = cnt[i * 4 + 2] = hist[i * 4 + 3] / 32f;
        RtImg.save("h_frame00", RtImg.hdr(on[2], W, H, 1.0));
        RtImg.save("h_frame29", RtImg.hdr(on[0], W, H, 1.0));
        RtImg.save("h_frame29_norejection", RtImg.hdr(off[0], W, H, 1.0));
        RtImg.save("h_frame29_cpu", RtImg.hdr(refImg, W, H, 1.0));
        RtImg.save("h_history_length", RtImg.linear(cnt, W, H, 1));
    }

    int cacheEntries() {
        java.nio.ByteBuffer b = gpu.dev.readBuffer(gpu.cache, 0, RtGpu.CACHE_ENTRIES * 48);
        gpu.comp.resetSets();
        int n = 0;
        for (int i = 0; i < RtGpu.CACHE_ENTRIES; i++) if (b.getInt(i * 48) != 0) n++;
        return n;
    }

    float[] cachedFor(CpuRef r, String name, Supplier<float[]> s) {
        return cached(name, s);
    }

    // ------------------------------------------------------------------------------------------------ costs

    void costTable() {
        metric("--- per-pass cost on lavapipe (RELATIVE only) and rays per pixel");
        long total = 0;
        for (long[] t : gpu.timing.values()) total += t[0];
        for (Map.Entry<String, long[]> e : gpu.timing.entrySet())
            metric(String.format(Locale.ROOT, "  %-26s %8.2f ms/call  %5.1f%% of recorded time  (%d calls)", e.getKey(), e.getValue()[0] / 1e6 / e.getValue()[1],
                    100.0 * e.getValue()[0] / total, e.getValue()[1]));
        if (rayFrames > 0) {
            String[] names = {"primary debug", "sun", "block light", "GI (no cache)", "reflections"};
            double sum = 0;
            for (int k = 1; k <= 4; k++) {
                double rpp = rayTotals[k] / (double) rayFrames / N;
                sum += rpp;
                metric(String.format(Locale.ROOT, "  rays/pixel %-14s %.3f", names[k], rpp));
            }
            metric(String.format(Locale.ROOT, "  rays/pixel total (sun+light+GI+refl) %.3f at full rate", sum));
            double radianceRate = 2_073_600 * 7.0 / 20e-3; // ASSUMPTION: Radiance's 20 ms 1080p pass ~ 7 rays/pixel (UNVERIFIED)
            double ms = 1707 * 960 * sum / radianceRate * 1e3;
            metric(String.format(Locale.ROOT, "  GPU estimate (UNVERIFIED): Radiance datum 20 ms @1080p native, assumed ~7 rays/px -> %.2f Grays/s;"
                    + " Iroh %.2f rays/px @1707x960 -> ~%.1f ms full rate, ~%.1f ms half rate", radianceRate / 1e9, sum, ms, ms / 2));
        }
    }
}
