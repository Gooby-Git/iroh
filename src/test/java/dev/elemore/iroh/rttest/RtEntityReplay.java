package dev.elemore.iroh.rttest;

import dev.elemore.iroh.gpu.VkGpuDevice.VkBuf;
import dev.elemore.iroh.rt.RtEntityBudget;
import dev.elemore.iroh.rt.RtFrameData;
import dev.elemore.iroh.rt.vk.RtAccel;
import dev.elemore.iroh.upscale.fsr.NativeLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.*;

import static dev.elemore.iroh.rt.RtFrameData.*;
import static dev.elemore.iroh.rttest.RtReplay.check;
import static dev.elemore.iroh.rttest.RtReplay.f;
import static dev.elemore.iroh.rttest.RtReplay.metric;

/**
 * P5-3 step 1 ({@code ./gradlew rtEntities}, lavapipe only): entity shadows and reflections with one rigid BLAS per model
 * part posed by full 3x4 TLAS instances. (A) per-frame correctness of a walking six-part mob + a sliding, turning crate
 * against the CPU reference (primary visibility, hard sun shadow, motion vectors of the mob's own pixels); (B) entities in
 * GI and reflection rays; (C) the moving shadow through the denoiser + FSR: lag, ghosting, the mechanisms that fix it;
 * (D) cost per entity (BLAS bytes, TLAS instances, TLAS rebuild and refit times, relative); (E) the entities.png sheet.
 */
public final class RtEntityReplay {
    static final int W = 160, H = 90, RW = 320, RH = 180, DW = 640, DH = 360, WARM = 10, WALK = 30;
    /** Test-scene walk: across the grass in front of the camera, 3 blocks in 30 frames (0.1 block/frame = 6 m/s at 60 fps,
     *  a sprint: the hardest common case for shadow lag), the crate slides 2.5 blocks and turns. */
    static final RtEntities.Walk TEST_WALK = new RtEntities.Walk(new float[]{5.5f, 8f, 22.5f}, new float[]{8.5f, 8f, 22.5f}, WALK,
            new float[]{12.6f, 8f, 25.2f}, new float[]{14.6f, 8f, 24.0f});
    /** Mob standing on the far bank of the pond, its reflection on the water seen from the camera (GI/reflection check). */
    static final RtEntities.Walk POND = new RtEntities.Walk(new float[]{19f, 8f, 7.6f}, new float[]{19f, 8f, 7.6f}, 1, null, null);

    public static void main(String[] args) throws Exception {
        Files.createDirectories(RtReplay.OUT);
        RtReplay.metrics = new java.io.PrintStream(Files.newOutputStream(RtReplay.OUT.resolve("entity_metrics.txt")), true);
        long t0 = System.nanoTime();
        RtScene scene = new RtScene();
        scene.entities = new RtEntities();
        RtReplay.Light light = new RtReplay.Light(new RtFrameData().waterExtinction);
        RtCamera cam = new RtCamera(new Vector3f(-2.0f, 16.5f, 27.0f), new Vector3f(16.0f, 7.0f, 14.0f), 70, W, H);
        metric("P5-3 entities: test scene + 6-part mob (head, body, 2 arms, 2 legs) + crate; " + W + "x" + H + " correctness, " + RW + "x" + RH + " -> " + DW + "x" + DH + " chain");
        try (RtGpu gpu = new RtGpu(scene, W, H)) {
            stepA(gpu, scene, light, cam);
            stepB(gpu, scene, light, cam);
            stepD(gpu, scene);
        }
        if (!NativeLoader.load()) check("(C) FSR 3.1 library available (" + NativeLoader.unavailableReason() + ")", false);
        else stepC(scene, light);
        metric(String.format(Locale.ROOT, "wall time %.1f s", (System.nanoTime() - t0) / 1e9));
        String last = "# rtentities " + (RtReplay.fail == 0 ? "PASS" : "FAIL") + " " + RtReplay.pass + "/" + (RtReplay.pass + RtReplay.fail);
        System.out.println(last);
        RtReplay.metrics.println(last);
        for (String r : RtReplay.report) RtReplay.metrics.println(r);
        RtReplay.metrics.close();
        System.exit(RtReplay.fail == 0 ? 0 : 1);
    }

    /** Diagnostic image into build/rt (never a golden: the chain images are compared by the metrics above, not by pixels). */
    static void diag(String name, BufferedImage img) {
        try { ImageIO.write(img, "png", RtReplay.OUT.resolve(name + ".png").toFile()); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    static int trisPerPart() {
        return 12;
    }

    // ================================================================================================ (A) per-frame correctness

    static void stepA(RtGpu gpu, RtScene scene, RtReplay.Light light, RtCamera cam) {
        float radius = light.sunRadius;
        light.sunRadius = 0;   // hard sun: the shadow is exact, so GPU and CPU must agree pixel for pixel
        CpuRef ref = new CpuRef(scene, light, cam);
        VkBuf vis = gpu.buf(gpu.N * 16L);
        int worstMismatch = 0, totalEntShadow = 0, totalMob = 0, primMismatch = 0, mvPixels = 0;
        double mvErrSum = 0, mvErrMax = 0, mvMean = 0;
        for (int k = 0; k < WALK; k++) {
            List<RtEntities.Inst> insts = RtEntities.frame(TEST_WALK, k, Math.max(0, k - 1), true);
            gpu.poseEntities(insts, true);
            ref.setDynamic(scene.entities.triangles(insts), trisPerPart());
            gpu.setFrame(cam, cam, k, F_ALPHA_TEST, light);
            gpu.gbuffer();
            gpu.trace("sun visibility", gpu.pSunVis, vis);
            float[] gv = gpu.readVec4(vis);
            int[] g = gpu.readUvec4(gpu.gbufCur);
            CpuRef.Surf[] prim = ref.primary();
            float[] cv = ref.sunVis(prim, 1, 5);
            int groups = ref.dynGroups;
            ref.dynGroups = 0;
            float[] cvNo = ref.sunVis(prim, 1, 5);
            ref.dynGroups = groups;
            int mism = 0, entShadow = 0;
            for (int i = 0; i < gpu.N; i++) {
                float gz = Float.intBitsToFloat(g[i * 4 + 2]);
                CpuRef.Surf s = prim[i];
                boolean same = s.valid && gz > 0 && Math.abs(gz - s.viewZ) < 0.01 * s.viewZ;
                if (s.valid != gz > 0 || (s.valid && !same)) { primMismatch++; continue; }
                if (!s.valid) continue;
                boolean mob = s.tri >= ref.ns;
                if (mob) totalMob++;
                if (cv[i * 4] != cvNo[i * 4]) entShadow++;
                if ((gv[i * 4 + 1] > 0.5f) != (cv[i * 4] > 0.5f)) mism++;
                if (mob && k > 0) {   // motion vector of an entity pixel: the surface point through the previous pose
                    int inst = (s.tri - ref.ns) / trisPerPart();
                    RtEntities.Inst e = insts.get(inst);
                    double[] mv = expectedMv(cam, e, s.pos);
                    double gx = RtChain.half(g[i * 4 + 3] & 0xFFFF), gy = RtChain.half(g[i * 4 + 3] >>> 16);
                    double err = Math.hypot(gx - mv[0], gy - mv[1]);
                    mvErrSum += err;
                    mvErrMax = Math.max(mvErrMax, err);
                    mvMean += Math.hypot(mv[0], mv[1]);
                    mvPixels++;
                }
            }
            totalEntShadow += entShadow;
            worstMismatch = Math.max(worstMismatch, mism);
            if (k == WALK - 1) metric(String.format(Locale.ROOT, "(A) frame %d: %d entity-shadowed px, %d sun-visibility mismatches GPU vs CPU", k, entShadow, mism));
        }
        metric(String.format(Locale.ROOT, "(A) %d frames: mob/crate px %d, entity-cast shadow px %d (mean %.0f/frame), worst frame %d visibility mismatches, primary mismatches %d of %d px",
                WALK, totalMob, totalEntShadow, totalEntShadow / (double) WALK, worstMismatch, primMismatch, WALK * gpu.N));
        metric(String.format(Locale.ROOT, "(A) entity motion vectors: %d px, mean |mv| %.3f px, error mean %.4f px, max %.4f px (camera static: pure object motion)",
                mvPixels, mvMean / Math.max(1, mvPixels), mvErrSum / Math.max(1, mvPixels), mvErrMax));
        check("(A) entities cast RT sun shadows every frame (" + totalEntShadow / WALK + " px/frame >= 30 at 160x90)", totalEntShadow / WALK >= 30);
        check("(A) hard entity shadows equal the CPU reference (worst frame " + worstMismatch + " px <= 6)", worstMismatch <= 6);
        check("(A) RT G-buffer sees the posed parts where the CPU does (primary mismatches " + primMismatch + " <= 0.2%)", primMismatch <= 0.002 * WALK * gpu.N);
        check("(A) entity motion vectors follow the part poses (mean error " + f(mvErrSum / Math.max(1, mvPixels)) + " px <= 0.02)", mvPixels > 100 && mvErrSum / mvPixels <= 0.02);
        light.sunRadius = radius;
        gpu.poseEntities(List.of(), true);
    }

    /** Expected motion (pixels, previous - current) of world point {@code p} on instance {@code e} under a static camera. */
    static double[] expectedMv(RtCamera cam, RtEntities.Inst e, double[] p) {
        Matrix4f cur = toMat(e.cur()), prev = toMat(e.prev());
        Vector4f local = new Matrix4f(cur).invert().transform(new Vector4f((float) p[0], (float) p[1], (float) p[2], 1));
        Vector4f pw = prev.transform(new Vector4f(local));
        Matrix4f vp = cam.viewProj();
        Vector4f a = vp.transform(new Vector4f(pw)), b = vp.transform(new Vector4f((float) p[0], (float) p[1], (float) p[2], 1));
        return new double[]{(a.x / a.w - b.x / b.w) * 0.5 * cam.w, (a.y / a.w - b.y / b.w) * 0.5 * cam.h};
    }

    static Matrix4f toMat(float[] r) {
        return new Matrix4f(r[0], r[4], r[8], 0, r[1], r[5], r[9], 0, r[2], r[6], r[10], 0, r[3], r[7], r[11], 1);
    }

    // ================================================================================================ (B) GI and reflections

    static void stepB(RtGpu gpu, RtScene scene, RtReplay.Light light, RtCamera cam0) {
        int frames = 192;
        RtCamera cam = new RtCamera(new Vector3f(13.5f, 10.5f, 17.5f), new Vector3f(19.5f, 7.5f, 8f), 70, W, H);   // low over the pond, mob on the far bank
        List<RtEntities.Inst> insts = RtEntities.frame(POND, 0, 0, true);
        CpuRef ref = new CpuRef(scene, light, cam);
        CpuRef.Surf[] prim0 = ref.primary();
        float[] giNo = ref.indirect(prim0, 256), specNo = ref.spec(prim0, 256);
        ref.setDynamic(scene.entities.triangles(insts), trisPerPart());
        CpuRef.Surf[] prim = ref.primary();
        float[] gi = ref.indirect(prim, 256), spec = ref.spec(prim, 256);
        gpu.poseEntities(insts, true);
        gpu.setFrame(cam, cam, 0, F_ALPHA_TEST, light);
        gpu.gbuffer();
        int[] gb = gpu.readUvec4(gpu.gbufCur), tiles = new int[gpu.N];
        for (int i = 0; i < gpu.N; i++) tiles[i] = Float.intBitsToFloat(gb[i * 4 + 2]) > 0 ? (gb[i * 4 + 1] >>> 24) & 63 : -1;
        diag("ent_b_tiles", RtImg.ids(tiles, W, H));
        VkBuf bg = gpu.buf(gpu.N * 16L), br = gpu.buf(gpu.N * 16L);
        float[] accG = new float[gpu.N * 4], accR = new float[gpu.N * 4];
        for (int k = 0; k < frames; k++) {
            gpu.setFrame(cam, cam, 1000 + k, F_ALPHA_TEST | F_SOFT_SUN, light);
            gpu.gbuffer();
            gpu.trace("GI 1 bounce", gpu.pGi, gpu.cache, bg);
            gpu.trace("reflections", gpu.pRefl, br);
            float[] a = gpu.readVec4(bg), b = gpu.readVec4(br);
            for (int i = 0; i < a.length; i++) { accG[i] += a[i] / frames; accR[i] += b[i] / frames; }
        }
        for (String sig : new String[]{"GI", "reflections"}) {
            float[] g = sig.equals("GI") ? accG : accR, with = sig.equals("GI") ? gi : spec, without = sig.equals("GI") ? giNo : specNo;
            boolean[] m = new boolean[gpu.N];
            int n = 0;
            for (int i = 0; i < gpu.N; i++) {
                if (!prim[i].valid || prim[i].tri >= ref.ns) continue;
                double a = RtImg.lum(with, i), b = RtImg.lum(without, i);
                m[i] = Math.abs(a - b) > (sig.equals("GI") ? 0.08 : 0.15) * Math.max(a, 0.02);
                if (m[i]) n++;
            }
            double[] eWith = RtImg.err(g, with, m), eWithout = RtImg.err(g, without, m);
            metric(String.format(Locale.ROOT, "(B) %s: %d px changed by the mob (CPU, >8%% GI / >15%% reflections); GPU (%d frames) vs CPU WITH entity relMAE %.4f, vs CPU WITHOUT %.4f", sig, n, frames, eWith[0], eWithout[0]));
            check("(B) " + sig + " rays see the entity (" + n + " affected px; GPU matches the with-entity reference " + f(eWith[0]) + " < 0.5 x " + f(eWithout[0]) + ")", n >= 8 && eWith[0] < 0.5 * eWithout[0]);
        }
        gpu.poseEntities(List.of(), true);
    }

    // ================================================================================================ (D) cost

    static void stepD(RtGpu gpu, RtScene scene) {
        long perMob = 0;
        for (int p = RtEntities.HEAD; p < RtEntities.PARTS; p++) perMob += gpu.partBlas.get(p).bytes;
        metric(String.format(Locale.ROOT, "(D) part BLAS (lavapipe sizes): %d parts built once = %d B total; the 6-part mob = %d B (%.0f B/part, 12 triangles each); per frame per admitted part: one %d B TLAS instance + %d B motion record",
                RtEntities.PARTS, gpu.partBlasBytes, perMob, perMob / 6.0, RtAccel.INSTANCE_BYTES, RtEntityBudget.BYTES_PER_PART_FRAME - RtAccel.INSTANCE_BYTES));
        int[] mobs = {0, 1, 10, 64, 256};
        double base = 0;
        StringBuilder sb = new StringBuilder();
        double[] ms = new double[mobs.length];
        for (int j = 0; j < mobs.length; j++) {
            List<RtEntities.Inst> l = new ArrayList<>();
            for (int m = 0; m < mobs[j]; m++) {
                RtEntities.Walk w = new RtEntities.Walk(new float[]{2f + (m % 16) * 1.7f, 8f, 2f + (m / 16) * 1.7f}, new float[]{3f + (m % 16) * 1.7f, 8f, 2f + (m / 16) * 1.7f}, 8, null, null);
                l.addAll(RtEntities.frame(w, m % 8, Math.max(0, m % 8 - 1), true));
            }
            gpu.timing.remove("TLAS rebuild (static + entity instances)");
            for (int r = 0; r < 6; r++) gpu.poseEntities(l, true);
            long[] t = gpu.timing.get("TLAS rebuild (static + entity instances)");
            ms[j] = t[0] / 1e6 / t[1];
            if (j == 0) base = ms[j];
            sb.append(String.format(Locale.ROOT, " %d mobs (%d inst) %.2f ms;", mobs[j], gpu.instanceCount, ms[j]));
        }
        metric("(D) TLAS rebuild per frame on lavapipe (RELATIVE), static sections + mobs:" + sb);
        double perInst = (ms[mobs.length - 1] - base) / (mobs[mobs.length - 1] * 6.0);
        metric(String.format(Locale.ROOT, "(D) marginal TLAS cost per part instance %.4f ms (lavapipe), = %.2f%% of the static-scene rebuild per mob", perInst, 100 * perInst * 6 / Math.max(1e-6, base)));
        gpu.poseEntities(List.of(), true);
        // refit fallback: one free-form 36-quad mob stream per entity, refitted per frame vs rebuilt per frame
        RtEntities e = scene.entities;
        int N = 32;
        VkBuf vb = gpu.buf(N * 36 * 4 * 16L);
        List<List<RtAccel.Geometry>> geo = new ArrayList<>();
        for (int m = 0; m < N; m++)
            geo.add(List.of(new RtAccel.Geometry(vb, m * 36 * 64L, 16, 36, RtGpu.geomTransform(), true)));
        List<RtAccel.Accel> upd = new ArrayList<>();
        writeFreeForm(gpu, e, vb, N, 0);
        gpu.accel.barrierInputsToBuild();
        for (int m = 0; m < N; m++) upd.add(gpu.accel.buildBlasUpdatable(geo.get(m)));
        gpu.sync();
        gpu.accel.releaseTransient();
        long tRefit = 0, tRebuild = 0;
        int frames = 8;
        for (int k = 1; k <= frames; k++) {
            writeFreeForm(gpu, e, vb, N, k);
            gpu.accel.barrierInputsToBuild();
            long a = System.nanoTime();
            for (int m = 0; m < N; m++) gpu.accel.refitBlas(upd.get(m), geo.get(m));
            gpu.sync();
            tRefit += System.nanoTime() - a;
            gpu.accel.releaseTransient();
            long b = System.nanoTime();
            List<RtAccel.Accel> tmp = new ArrayList<>();
            for (int m = 0; m < N; m++) tmp.add(gpu.accel.buildBlas(geo.get(m), false));
            gpu.sync();
            tRebuild += System.nanoTime() - b;
            gpu.accel.releaseTransient();
            for (RtAccel.Accel x : tmp) gpu.accel.destroy(x);
        }
        long updBytes = upd.get(0).bytes;
        for (RtAccel.Accel x : upd) gpu.accel.destroy(x);
        gpu.sync();
        gpu.dev.destroyBuffer(vb);
        double refitMs = tRefit / 1e6 / frames, rebuildMs = tRebuild / 1e6 / frames;
        metric(String.format(Locale.ROOT, "(D) refit fallback, %d free-form mobs (72 tris each, ALLOW_UPDATE BLAS %d B): refit %.2f ms/frame vs rebuild %.2f ms/frame (lavapipe, incl. submit); rigid instances need neither",
                N, updBytes, refitMs, rebuildMs));
        check("(D) refit is cheaper than a per-frame rebuild for free-form entities (" + f(refitMs) + " < " + f(rebuildMs) + " ms)", refitMs < rebuildMs);
        // the budget rule (RtEntityBudget.DEFAULT), on a synthetic crowd: 200 entities in a 96-block square
        RtEntityBudget bud = new RtEntityBudget(RtEntityBudget.Limits.DEFAULT);
        List<RtEntityBudget.Candidate> c = new ArrayList<>();
        Random r = new Random(7);
        for (int i = 0; i < 200; i++) c.add(new RtEntityBudget.Candidate(i, r.nextDouble() * 70, 6 + r.nextInt(6), i % 10 != 0));
        RtEntityBudget.Selection s = bud.select(c);
        metric(String.format(Locale.ROOT, "(D) budget rule %s on 200 entities within 70 blocks: %d rigid (%d part instances) + %d refit, %d beyond the radius, %d over a cap; TLAS +%d instances, %d B/frame",
                RtEntityBudget.Limits.DEFAULT, s.rigid.size(), s.parts, s.refit.size(), s.excludedByRadius, s.excludedByCap, s.instances(), s.parts * RtEntityBudget.BYTES_PER_PART_FRAME));
    }

    /** Free-form stream: the six mob parts posed on the CPU into one 36-quad vertex run per entity (mob-local, feet at 4,0,4). */
    static void writeFreeForm(RtGpu gpu, RtEntities e, VkBuf vb, int n, int k) {
        ByteBuffer out = ByteBuffer.allocate(n * 36 * 64).order(ByteOrder.LITTLE_ENDIAN);
        for (int m = 0; m < n; m++) {
            RtEntities.Walk w = new RtEntities.Walk(new float[]{4, 0, 4}, new float[]{4, 0, 5}, 8, null, null);
            float[][] pose = RtEntities.mobPose(w, ((k + m) % 8) / 7f);
            for (int p = RtEntities.HEAD; p < RtEntities.PARTS; p++) {
                ByteBuffer src = e.mesh[p];
                for (int v = 0; v < 24; v++) {
                    double lx = RtScene.decodePos(src.getShort(v * 16)), ly = RtScene.decodePos(src.getShort(v * 16 + 2)), lz = RtScene.decodePos(src.getShort(v * 16 + 4));
                    float[] M = pose[p];
                    for (int a = 0; a < 3; a++)
                        out.putShort((short) Math.round(((M[a * 4] * lx + M[a * 4 + 1] * ly + M[a * 4 + 2] * lz + M[a * 4 + 3]) - 4) * 2048));
                    out.putShort((short) 0xF0F0).putShort(src.getShort(v * 16 + 8)).putShort(src.getShort(v * 16 + 10)).putInt(-1);
                }
            }
        }
        gpu.up(vb, 0, out.flip());
        gpu.uploadBarrier();
    }

    // ================================================================================================ (C) the moving shadow through denoiser + FSR

    record Variant(String name, boolean objectMv, boolean lagGuard, boolean reactive, boolean inTlas) {}

    static void stepC(RtScene scene, RtReplay.Light light) throws Exception {
        boolean fog = light.fog;
        light.fog = false;
        int T = WARM + WALK;
        RtCamera camR = new RtCamera(new Vector3f(-2.0f, 16.5f, 27.0f), new Vector3f(16.0f, 7.0f, 14.0f), 70, RW, RH);
        RtCamera camD = new RtCamera(camR.pos, camR.target, 70, DW, DH);
        // per-frame CPU reference of the sun term at display res (cached): emission + albedo/pi * E * cos * V(soft, 48 spp)
        float[][] ref = new float[T][];
        boolean[][] mobPx = new boolean[T][];
        CpuRef cref = new CpuRef(scene, light, camD);
        for (int k = 0; k < T; k++) {
            List<RtEntities.Inst> insts = RtEntities.frame(TEST_WALK, Math.max(0, k - WARM), 0, true);
            cref.setDynamic(scene.entities.triangles(insts), trisPerPart());
            CpuRef.Surf[] prim = cref.primary();
            boolean[] mp = new boolean[DW * DH];
            for (int i = 0; i < mp.length; i++) mp[i] = prim[i].valid && prim[i].tri >= cref.ns;
            mobPx[k] = mp;
            int kk = Math.max(0, k - WARM);
            ref[k] = RtChain.cachedRef("ent-sunterm-v1-" + DW + "x" + DH + "-f" + kk, () -> sunTerm(cref, prim, light));
        }
        boolean[] region = new boolean[DW * DH];
        int nreg = 0;
        for (int i = 0; i < DW * DH; i++) {
            double mn = 1e9, mx = 0;
            boolean mob = false;
            for (int k = WARM; k < T; k++) { double v = RtImg.lum(ref[k], i); mn = Math.min(mn, v); mx = Math.max(mx, v); mob |= mobPx[k][i]; }
            region[i] = !mob && mx > 0.05 && mx - mn > 0.3 * mx;
            if (region[i]) nreg++;
        }
        metric("(C) moving shadow (svgf shadow lag guard = full reset since P5-3): " + WARM + " still frames, then a " + WALK + "-frame walk (0.1 block/frame), static camera; sun term only (emission + albedo/pi E cos V), render "
                + RW + "x" + RH + " -> FSR " + DW + "x" + DH + "; region = " + nreg + " display px the mob's shadow sweeps (mob pixels excluded)");
        List<Variant> vs = List.of(
                new Variant("after: object MVs + shadow lag guard + FSR reactive", true, true, true, true),
                new Variant("no lag guard, no reactive mask", true, false, false, true),
                new Variant("lag guard only (no reactive mask)", true, true, false, true),
                new Variant("camera-only MVs (prev pose = cur)", false, true, true, true),
                new Variant("before: mob raster-only (no TLAS instances)", true, true, true, false));
        Map<String, double[]> res = new LinkedHashMap<>();
        Map<String, float[]> lastFrames = new LinkedHashMap<>();
        try (RtChain ch = new RtChain(scene, light, RW, RH, DW, DH, 70)) {
            for (Variant v : vs) {
                float[][] outD = new float[T][], outR = new float[T][];
                ch.gpu.clearCache();
                try (RtChain.Den den = ch.new Den(); RtChain.Fsr fs = ch.new Fsr()) {
                    if (!v.lagGuard) den.specShadow.configure(1, Map.of("lagK", 0f));
                    fs.reactive = v.reactive;
                    for (int k = 0; k < T; k++) {
                        int w = Math.max(0, k - WARM);
                        ch.gpu.poseEntities(RtEntities.frame(TEST_WALK, w, Math.max(0, w - 1), v.objectMv), v.inTlas);
                        RtChain.Rt r = ch.trace(camR, camR, 40_000 + k, k, F_ALPHA_TEST | F_SOFT_SUN | F_REJECT);
                        RtChain.In in = ch.pack(r);
                        Arrays.fill(in.diff, 0f);
                        Arrays.fill(in.spec, 0f);
                        float[][] o = den.run(in, k, k == 0, camR, camR, r.jitter, ch.js.offset(Math.max(0, k - 1)));
                        float[] hdr = ch.compose(o[0], o[2], 4, o[1]);
                        outR[k] = RtChain.bilinear(hdr, RW, RH, DW, DH);
                        outD[k] = fs.run(hdr, in, r.jitter, k == 0, o[0]);
                        if (k == WARM + 15 && v == vs.get(0)) {
                            diag("ent_c_ref", RtImg.ldr(ch.postNeutral(ref[k], DW * DH), DW, DH));
                            diag("ent_c_chain", RtImg.ldr(ch.postNeutral(outD[k], DW * DH), DW, DH));
                            diag("ent_c_denoised", RtImg.ldr(ch.postNeutral(outR[k], DW * DH), DW, DH));
                            diag("ent_c_noisy", RtImg.ldr(ch.postNeutral(RtChain.bilinear(ch.compose(in.diff, in.visRaw, 1, in.spec), RW, RH, DW, DH), DW * DH), DW, DH));
                        }
                    }
                }
                double[] m = lagMetrics(outD, ref, region, mobPx, T);
                double[] mr = lagMetrics(outR, ref, region, mobPx, T);
                res.put(v.name, m);
                lastFrames.put(v.name, outD[T - 1]);
                metric(String.format(Locale.ROOT, "(C) %-48s chain: shadow-region relMAE %.4f, lag %.2f frames, ghost px %.1f%%, mob-trail relMAE %.4f, mob px relMAE %.4f | denoised (no FSR): relMAE %.4f, lag %.2f, ghost %.1f%%",
                        v.name, m[0], m[1], 100 * m[2], m[3], m[4], mr[0], mr[1], 100 * mr[2]));
            }
            double[] after = res.get(vs.get(0).name), off = res.get(vs.get(1).name), camOnly = res.get(vs.get(3).name), before = res.get(vs.get(4).name);
            check("(C) the chain's entity shadow trails by <= 1.5 frames (" + f(after[1]) + ")", after[1] <= 1.5);
            check("(C) lag guard + reactive mask cut the shadow-region error (" + f(after[0]) + " < " + f(off[0]) + ")", after[0] < off[0]);
            check("(C) object motion vectors cut the error behind the walking mob (trail " + f(after[3]) + " < " + f(camOnly[3]) + ")", after[3] < camOnly[3]);
            check("(C) entity shadows in the TLAS beat raster-only entities against the reference (" + f(after[0]) + " < " + f(before[0]) + ")", after[0] < before[0]);
            sheet(ch, scene, light, camD, ref[T - 1], lastFrames, vs);
        }
        light.fog = fog;
    }

    /** {shadow-region relMAE (walk frames), lag in frames (best shift, parabolic), ghost fraction, mob-trail relMAE, mob relMAE}. */
    static double[] lagMetrics(float[][] out, float[][] ref, boolean[] region, boolean[][] mobPx, int T) {
        int from = WARM + 4;
        double err = 0, num = 0, den = 0, ghost = 0, gn = 0, trail = 0, tn = 0, mob = 0, mn = 0;
        for (int k = from; k < T; k++)
            for (int i = 0; i < region.length; i++) {
                double c = RtImg.lum(out[k], i), r = RtImg.lum(ref[k], i);
                if (region[i]) {
                    num += Math.abs(c - r);
                    den += r;
                    if (r > 0.05) { gn++; if (Math.abs(c - r) > 0.3 * r) ghost++; }
                }
                boolean wasMob = mobPx[k - 1][i] || mobPx[k - 2][i] || mobPx[k - 3][i];
                if (wasMob && !mobPx[k][i]) { trail += Math.abs(c - r); tn += r; }
                if (mobPx[k][i]) { mob += Math.abs(c - r); mn += r; }
            }
        err = num / Math.max(1e-9, den);
        double[] L = new double[7];
        for (int s = 0; s < 7; s++) {
            double e = 0;
            for (int k = WARM + 8; k < T; k++)
                for (int i = 0; i < region.length; i++) if (region[i]) e += Math.abs(RtImg.lum(out[k], i) - RtImg.lum(ref[k - s], i));
            L[s] = e;
        }
        int b = 0;
        for (int s = 1; s < 7; s++) if (L[s] < L[b]) b = s;
        double lag = b;
        if (b > 0 && b < 6) { double d = L[b - 1] - 2 * L[b] + L[b + 1]; if (d > 0) lag += 0.5 * (L[b - 1] - L[b + 1]) / d; }
        return new double[]{err, lag, ghost / Math.max(1, gn), trail / Math.max(1e-9, tn), mob / Math.max(1e-9, mn)};
    }

    static float[] sunTerm(CpuRef r, CpuRef.Surf[] prim, RtReplay.Light light) {
        float[] v = r.sunVis(prim, 48, 21);
        float[] o = new float[prim.length * 4];
        for (int i = 0; i < prim.length; i++) {
            CpuRef.Surf s = prim[i];
            double[] c;
            if (!s.valid) {
                double[] k = r.sky(s.dir), sd = r.sunDisc(s.dir);
                c = new double[]{k[0] + sd[0], k[1] + sd[1], k[2] + sd[2]};
            } else {
                RtScene.Material m = r.scene.materials[s.tile];
                double cs = Math.max(0, s.n[0] * light.sunDir[0] + s.n[1] * light.sunDir[1] + s.n[2] * light.sunDir[2]) * v[i * 4];
                c = m.water() ? new double[3] : new double[]{m.er() + s.albedo[0] / Math.PI * light.sunE[0] * cs, m.eg() + s.albedo[1] / Math.PI * light.sunE[1] * cs, m.eb() + s.albedo[2] / Math.PI * light.sunE[2] * cs};
            }
            for (int a = 0; a < 3; a++) o[i * 4 + a] = (float) c[a];
            o[i * 4 + 3] = 1;
        }
        return o;
    }

    // ================================================================================================ (E) sheet

    static void sheet(RtChain ch, RtScene scene, RtReplay.Light light, RtCamera camD, float[] ref, Map<String, float[]> last, List<Variant> vs) throws Exception {
        List<BufferedImage> tiles = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        tiles.add(RtImg.ldr(ch.postNeutral(ref, DW * DH), DW, DH));
        labels.add("CPU reference (sun term), last walk frame: the mob's shadow on the grass, crate shadow at right");
        for (Variant v : List.of(vs.get(0), vs.get(4), vs.get(1))) {
            tiles.add(RtImg.ldr(ch.postNeutral(last.get(v.name), DW * DH), DW, DH));
            labels.add("chain | " + v.name);
        }
        // village look scenes (HIGH preset through the full look chain): day with the mob's shadow, night with a torch + the walking mob
        RtScene village = new RtScene(true);
        village.entities = new RtEntities();
        RtLookSheets.Preset high = RtLookSheets.presets().get(2);
        int rw = Math.round(DW / high.scale()), rh = Math.round(DH / high.scale());
        try (RtChain vc = new RtChain(village, light, rw, rh, DW, DH, 70)) {
            vc.srgbAlbedo = true;
            for (RtLookSheets.Scene sc : RtLookSheets.entityScenes()) {
                RtLookSheets.Tile t = RtLookSheets.render(vc, light, sc, high, new float[12]);
                tiles.add(t.img());
                labels.add(sc.name() + " | Iroh RT look, HIGH (FSR Quality), frame 24 of the walk");
            }
        }
        RtLookSheets.write(RtLookSheets.sheet("P5-3 entities: rigid per-part BLAS + posed TLAS instances (lavapipe, offline). Top: test scene vs CPU reference; bottom: village look",
                labels, tiles, 3), "entities.png");
    }
}
