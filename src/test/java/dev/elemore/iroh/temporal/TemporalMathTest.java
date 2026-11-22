package dev.elemore.iroh.temporal;

import dev.elemore.iroh.glshim.Orientation;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

/**
 * Card P4-1a offline maths: render-size table, SDK jitter sequence, projection jitter (pixel shift and Y sign under
 * Iroh's Orientation), mip bias, camera and object motion vectors in FSR's convention, TemporalFrame flow.
 * No game, no Vulkan. {@code ./gradlew temporalMathTest}; last line {@code # temporalmath PASS n/n}.
 */
public final class TemporalMathTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    /** Minecraft's world projection under Iroh: JOML perspective, Vulkan depth [0,1], near 0.05, infinite far. */
    static Matrix4f mcProjection(float fovDeg, int w, int h) {
        return new Matrix4f().perspective((float) Math.toRadians(fovDeg), (float) w / h, 0.05f, Float.POSITIVE_INFINITY, true);
    }

    /** texel position (column, row) of a view-space point on a w x h target, via Orientation's SCENE viewport */
    static double[] texel(Matrix4f p, float x, float y, float z, int w, int h) {
        Vector4f c = new Vector4f(x, y, z, 1f).mul(p);
        float[] vp = Orientation.vkViewport(Orientation.Target.SCENE, 0, 0, w, h, h);
        double nx = c.x / c.w, ny = c.y / c.w;
        // Vulkan viewport transform: xf = vp.x + (ndc.x + 1) * vp.w / 2, yf = vp.y + (ndc.y + 1) * vp.h / 2
        return new double[]{vp[0] + (nx + 1) * vp[2] / 2, vp[1] + (ny + 1) * vp[3] / 2};
    }

    public static void main(String[] args) {
        // 1. render-size table (SDK truncation), 2560x1440 and 1920x1080
        int[][] t1440 = {{2560, 1440}, {1706, 960}, {1505, 847}, {1280, 720}, {853, 480}};
        int[][] t1080 = {{1920, 1080}, {1280, 720}, {1129, 635}, {960, 540}, {640, 360}};
        ScaleMode[] modes = {ScaleMode.NATIVE_AA, ScaleMode.QUALITY, ScaleMode.BALANCED, ScaleMode.PERFORMANCE, ScaleMode.ULTRA_PERFORMANCE};
        for (int i = 0; i < modes.length; i++) {
            float r = modes[i].ratio(0);
            check("1440p " + modes[i] + " w", TemporalMath.renderSize(2560, r) == t1440[i][0]);
            check("1440p " + modes[i] + " h", TemporalMath.renderSize(1440, r) == t1440[i][1]);
            check("1080p " + modes[i] + " w", TemporalMath.renderSize(1920, r) == t1080[i][0]);
            check("1080p " + modes[i] + " h", TemporalMath.renderSize(1080, r) == t1080[i][1]);
        }
        check("custom ratio clamped", ScaleMode.CUSTOM.ratio(9f) == 4f && ScaleMode.CUSTOM.ratio(0.5f) == 1f);
        check("parse names", ScaleMode.parse("quality") == ScaleMode.QUALITY && ScaleMode.parse("ultra") == ScaleMode.ULTRA_PERFORMANCE
                && ScaleMode.parse(null) == ScaleMode.OFF && ScaleMode.parse("sideways") == null);

        // 2. jitter phase counts (doc table: 8, 18, 23, 32, 72)
        int[] phases = {8, 18, 23, 32, 72};
        for (int i = 0; i < modes.length; i++)
            check("phase count " + modes[i], TemporalMath.jitterPhaseCount(t1440[i][0], 2560) == phases[i]);

        // 3. Halton / SDK jitter
        check("halton(1,2)=.5", TemporalMath.halton(1, 2) == 0.5f);
        check("halton(2,2)=.25", TemporalMath.halton(2, 2) == 0.25f);
        check("halton(3,2)=.75", TemporalMath.halton(3, 2) == 0.75f);
        check("halton(1,3)=1/3", near(TemporalMath.halton(1, 3), 1 / 3.0, 1e-7));
        check("halton(4,3)=4/9", near(TemporalMath.halton(4, 3), 4 / 9.0, 1e-7));
        float[] j = new float[2];
        TemporalMath.jitterOffset(0, 8, j);
        check("jitter[0] = (0, -1/6)", j[0] == 0f && near(j[1], -1 / 6.0, 1e-6));
        TemporalMath.jitterOffset(8, 8, j);
        check("index wraps at the phase count", j[0] == 0f && near(j[1], -1 / 6.0, 1e-6));
        boolean inRange = true, nullVec = false;
        double sx = 0, sy = 0;
        for (int i = 0; i < 72; i++) {
            TemporalMath.jitterOffset(i, 72, j);
            inRange &= Math.abs(j[0]) <= 0.5f && Math.abs(j[1]) <= 0.5f;
            nullVec |= j[0] == 0f && j[1] == 0f;
            sx += j[0];
            sy += j[1];
        }
        check("jitter in [-0.5,0.5]", inRange);
        check("no null jitter vector", !nullVec);
        check("jitter mean near 0", Math.abs(sx / 72) < 0.02 && Math.abs(sy / 72) < 0.02);

        // 4. mip bias (fsr.md A.10)
        check("mip bias quality", near(TemporalMath.mipBias(1706, 2560), -1.585, 0.002));
        check("mip bias performance", near(TemporalMath.mipBias(1280, 2560), -2.0, 1e-6));
        check("mip bias native", near(TemporalMath.mipBias(2560, 2560), -1.0, 1e-6));

        // 5. projection jitter: a point at ANY depth moves by exactly (jx, jy) render pixels in Iroh texel axes,
        //    rows growing with NDC y (Orientation SCENE: y = 0, height +h)
        int rw = 1706, rh = 960;
        Matrix4f p = mcProjection(70f, 2560, 1440);
        check("isPerspective(world projection)", TemporalMath.isPerspective(p));
        check("ortho is not perspective", !TemporalMath.isPerspective(new Matrix4f().setOrtho(0, 640, 360, 0, 1000, 21000, true)));
        float[][] pts = {{1.3f, -0.7f, -5.2f}, {-3f, 2f, -400f}, {0.01f, 0.02f, -0.06f}};
        float[] jits = {0.3f, -0.41f, -0.5f, 0.5f};
        for (int k = 0; k < jits.length; k += 2) {
            float jx = jits[k], jy = jits[k + 1];
            Matrix4f pj = TemporalMath.jitter(p, TemporalMath.jitterNdcX(jx, rw), TemporalMath.jitterNdcY(jy, rh), new Matrix4f());
            for (float[] q : pts) {
                double[] a = texel(p, q[0], q[1], q[2], rw, rh), b = texel(pj, q[0], q[1], q[2], rw, rh);
                check("jitter x shift " + jx + " at z " + q[2], near(b[0] - a[0], jx, 2e-3));
                check("jitter y shift " + jy + " at z " + q[2] + " (rows grow with NDC y: no sign flip)", near(b[1] - a[1], jy, 2e-3));
                Vector4f c0 = new Vector4f(q[0], q[1], q[2], 1f).mul(p), c1 = new Vector4f(q[0], q[1], q[2], 1f).mul(pj);
                check("jitter keeps depth", near(c0.z / c0.w, c1.z / c1.w, 1e-6));
            }
            // the float[] path (converted shaders' ProjMat) equals the Matrix4f path
            float[] arr = new float[16];
            p.get(arr);
            TemporalFrame.JITTER_ON = TemporalFrame.IN_WORLD = true;
            TemporalFrame.jitterNdcX = TemporalMath.jitterNdcX(jx, rw);
            TemporalFrame.jitterNdcY = TemporalMath.jitterNdcY(jy, rh);
            TemporalFrame.gpuProjection(arr);
            float[] ref = new float[16];
            pj.get(ref);
            boolean same = true;
            for (int i = 0; i < 16; i++)
                same &= near(arr[i], ref[i], 1e-6);
            check("ProjMat float[] jitter == Matrix4f jitter", same);
            check("gpuProjection(Matrix4f) == jittered", TemporalFrame.gpuProjection(p).equals(pj, 1e-6f));
            Matrix4f ortho = new Matrix4f().setOrtho(0, 640, 360, 0, 1000, 21000, true);
            check("GUI ortho never jittered", TemporalFrame.gpuProjection(ortho) == ortho);
            TemporalFrame.IN_WORLD = false;
            check("outside the world phase never jittered", TemporalFrame.gpuProjection(p) == p);
            TemporalFrame.JITTER_ON = false;
        }
        // after the RQ-16 present flip (row r -> swapchain row H-1-r) +jy moves the picture UP on screen; FSR never
        // sees that flip, it reads the stored rows: consistent.

        // 6. camera motion vectors (FSR convention: prevUv - curUv, Iroh axes, unjittered)
        Quaternionf rotCur = new Quaternionf().rotateY((float) Math.toRadians(30)).rotateX((float) Math.toRadians(-10));
        Matrix4d prCur = new Matrix4d().set(p).mul(new Matrix4d().set(new Matrix4f().rotation(rotCur)));
        double[] camCur = {1_000_000.3, 70.62, -2_000_000.8};
        double[] camPrev = {camCur[0] - 0.25, camCur[1] + 0.05, camCur[2] + 0.1};
        Quaternionf rotPrev = new Quaternionf().rotateY((float) Math.toRadians(29.2)).rotateX((float) Math.toRadians(-10.4));
        Matrix4d prPrev = new Matrix4d().set(p).mul(new Matrix4d().set(new Matrix4f().rotation(rotPrev)));
        Matrix4d m = TemporalMath.reprojection(prPrev, prCur, camCur[0] - camPrev[0], camCur[1] - camPrev[1], camCur[2] - camPrev[2], new Matrix4d());
        double[][] world = {{camCur[0] + 3, camCur[1] - 1.5, camCur[2] - 8}, {camCur[0] - 40, camCur[1] + 12, camCur[2] - 90}, {camCur[0] + 0.4, camCur[1] - 0.2, camCur[2] - 0.9}};
        double maxErrPx = 0;
        for (double[] wpt : world) {
            double[] cu = TemporalMath.projectUv(prCur, camCur[0], camCur[1], camCur[2], wpt[0], wpt[1], wpt[2]);
            double[] pu = TemporalMath.projectUv(prPrev, camPrev[0], camPrev[1], camPrev[2], wpt[0], wpt[1], wpt[2]);
            double[] mv = new double[2];
            TemporalMath.motionUv(m, cu[0] * 2 - 1, cu[1] * 2 - 1, cu[2], mv);
            maxErrPx = Math.max(maxErrPx, Math.max(Math.abs((mv[0] - (pu[0] - cu[0])) * rw), Math.abs((mv[1] - (pu[1] - cu[1])) * rh)));
        }
        check("camera reprojection from depth == analytic motion (max err " + maxErrPx + " px)", maxErrPx < 1e-3);

        // sign: strafing RIGHT (+x camera, no rotation) moves a static point LEFT on screen: prev u > cur u -> mv.x > 0
        Matrix4d pr0 = new Matrix4d().set(p);
        Matrix4d strafe = TemporalMath.reprojection(pr0, pr0, 0.5, 0, 0, new Matrix4d());
        double[] wp = {2.0, 0.0, -10.0};
        double[] cuS = TemporalMath.projectUv(pr0, 0.5, 0, 0, wp[0], wp[1], wp[2]);
        double[] mvS = new double[2];
        TemporalMath.motionUv(strafe, cuS[0] * 2 - 1, cuS[1] * 2 - 1, cuS[2], mvS);
        double expectPx = 0.5 * p.m00() / 10.0 * 0.5 * rw; // NDC shift = m00 * dx / dist, UV = NDC / 2
        check("strafe right: mv.x > 0 (" + mvS[0] * rw + " px)", mvS[0] > 0);
        check("strafe right: |mv.x| = m00*dx/dist/2*W px", near(mvS[0] * rw, expectPx, 1e-3));
        check("strafe right: mv.y = 0", near(mvS[1], 0, 1e-9));
        // moving UP (+y camera): static point moves DOWN in the picture = lower NDC y = lower row: prev v > cur v -> mv.y > 0
        Matrix4d up = TemporalMath.reprojection(pr0, pr0, 0, 0.5, 0, new Matrix4d());
        double[] cuU = TemporalMath.projectUv(pr0, 0, 0.5, 0, wp[0], wp[1], wp[2]);
        double[] mvU = new double[2];
        TemporalMath.motionUv(up, cuU[0] * 2 - 1, cuU[1] * 2 - 1, cuU[2], mvU);
        check("camera up: mv.y > 0 in Iroh rows (no D3D flip)", mvU[1] > 0);
        // static camera: zero everywhere
        Matrix4d still = TemporalMath.reprojection(prCur, prCur, 0, 0, 0, new Matrix4d());
        double[] mv0 = new double[2];
        TemporalMath.motionUv(still, 0.3, -0.7, 0.9987, mv0);
        check("static camera: zero motion", Math.abs(mv0[0]) < 1e-7 && Math.abs(mv0[1]) < 1e-7);
        // sky at depth exactly 1 (infinite far): translation gives no motion, rotation does
        double[] mvSky = new double[2];
        TemporalMath.motionUv(strafe, 0.2, 0.1, 1.0, mvSky);
        check("sky ignores translation (< 0.01 px at 0.5 block/frame; " + mvSky[0] * rw + " px)", Math.abs(mvSky[0] * rw) < 0.01 && Math.abs(mvSky[1] * rh) < 0.01);
        Matrix4d yaw = TemporalMath.reprojection(prPrev, prCur, 0, 0, 0, new Matrix4d());
        TemporalMath.motionUv(yaw, 0.0, 0.0, 1.0, mvSky);
        check("sky follows rotation", Math.abs(mvSky[0]) > 1e-3);
        // jittered depth sample: removing the jitter before reprojecting keeps a static scene at zero
        double[] mvJ = new double[2];
        TemporalMath.motionUv(still, 0.1 - 0.0003, 0.2 + 0.0004, 0.999, mvJ);
        check("unjittered reprojection: static scene still zero", Math.abs(mvJ[0]) < 1e-7);

        // 7. object motion (per-object reference for P4-1b): object +0.25 x, static camera: moves right -> mv.x < 0
        double[] cam = {0, 0, 0};
        double[] om = TemporalMath.objectMotionUv(pr0, cam, new double[]{1.0, 0, -6}, pr0, cam, new double[]{1.25, 0, -6});
        check("object moving right: mv.x < 0", om[0] < 0);
        check("object motion = m00*dx/dist/2 UV", near(om[0], -0.25 * p.m00() / 6.0 * 0.5, 1e-9));
        // object and camera moving together (riding a boat): no motion
        double[] om2 = TemporalMath.objectMotionUv(pr0, new double[]{0, 0, 0}, new double[]{1.0, 0, -6}, pr0, new double[]{0.4, 0, 0}, new double[]{1.4, 0, -6});
        check("object moving with the camera: zero", Math.abs(om2[0]) < 1e-12 && Math.abs(om2[1]) < 1e-12);

        // 8. TemporalFrame flow
        TemporalFrame.resetForTest();
        System.setProperty(UpscaleSwitches.MODE, "quality");
        boolean changed = TemporalFrame.beginFrame(2560, 1440, true, true);
        check("first frame reports a size change", changed);
        check("quality 1706x960", TemporalFrame.renderW == 1706 && TemporalFrame.renderH == 960);
        check("temporal upscaler -> jitter on, 18 phases", TemporalFrame.JITTER_ON && TemporalFrame.phaseCount == 18);
        check("auto mip bias -1.585", near(TemporalFrame.mipBias, -1.585, 0.002) && TemporalFrame.MIP_ON);
        check("velocity on for a temporal upscaler", TemporalFrame.velocityOn);
        TemporalFrame.onWorldCamera(10, 64, 10, new Matrix4f(), p);
        TemporalFrame.endWorld();
        check("first world frame: identity reprojection", TemporalFrame.reproject.equals(new Matrix4d(), 0));
        check("same size second frame: no change", !TemporalFrame.beginFrame(2560, 1440, true, true));
        TemporalFrame.onWorldCamera(10.5, 64, 10, new Matrix4f(), p);
        TemporalFrame.endWorld();
        check("second frame: reprojection = strafe", TemporalFrame.reproject.equals(strafe, 1e-9));
        TemporalFrame.beginFrame(2560, 1440, true, true);
        TemporalFrame.onWorldCamera(10.5 + 100, 64, 10, new Matrix4f(), p);
        TemporalFrame.endWorld();
        check("teleport: identity + reset", TemporalFrame.reproject.equals(new Matrix4d(), 0) && TemporalFrame.reset);
        TemporalFrame.beginFrame(2560, 1440, false, false);
        check("bilinear (not temporal): no jitter, no bias, no velocity", !TemporalFrame.JITTER_ON && TemporalFrame.jitterPxX == 0 && !TemporalFrame.MIP_ON && !TemporalFrame.velocityOn);
        TemporalFrame.IN_WORLD = true;
        int[] r = {0, 0, 2560, 1440};
        check("display-size viewport mapped to render size", TemporalFrame.fixRect(r) && r[0] == 0 && r[1] == 0 && r[2] == 1706 && r[3] == 960);
        int[] r2 = {0, 0, 1706, 960};
        check("render-size viewport untouched", !TemporalFrame.fixRect(r2) && r2[2] == 1706);
        TemporalFrame.IN_WORLD = false;
        int[] r3 = {0, 0, 2560, 1440};
        check("no mapping outside the world phase", !TemporalFrame.fixRect(r3));
        System.setProperty(UpscaleSwitches.MODE, "off");
        check("mode off: change reported, everything off", TemporalFrame.beginFrame(2560, 1440, true, true) && !TemporalFrame.ACTIVE && !TemporalFrame.JITTER_ON);
        System.clearProperty(UpscaleSwitches.MODE);

        System.out.println("# temporalmath " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
