package dev.elemore.iroh.upscaletest;

import dev.elemore.iroh.temporal.ObjectMotion;
import dev.elemore.iroh.temporal.TemporalMath;
import dev.elemore.iroh.temporal.VelocityPass;
import dev.elemore.iroh.upscale.JitterSequence;
import dev.elemore.iroh.upscale.fsr.NativeLoader;
import org.joml.Matrix4d;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK12.*;

/**
 * Card P4-1b cross-check on LAVAPIPE: Iroh's renderer inputs drive FSR exactly as well as card P4-2a's hand-made ones.
 * Same synthetic scene as fsrReplay (320x180 -> 640x360, a checker square moving (+6, +2) display px per frame over a
 * static textured background, 40 frames, area-averaged render, the same jitter sequence). Run A: P4-2a's hand-made
 * motion vectors (render pixels, scale 1). Run B: motion vectors from Iroh's {@link VelocityPass} computed from the
 * scene's DEPTH image, with the square as a moving object packed by {@link ObjectMotion} (orthographic camera: world x/y
 * = display pixels, z = depth) and the jitter removed by the pass, UV units with motionVectorScale = render size. Run C:
 * B with the object's motion reversed (wrong sign). Expected: B equals A within noise (whole frame and square), both
 * beat bilinear, C is clearly worse on the square.
 * {@code ./gradlew fsrVelocityReplay}; last line {@code # fsrvelocity PASS|FAIL}.
 */
public final class FsrVelocityReplay {
    static int fails;

    static void ck(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fails++;
    }

    static long view(FsrReplay.Img i) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(s).sType$Default().image(i.image()).viewType(VK_IMAGE_VIEW_TYPE_2D).format(i.format());
            vi.subresourceRange().set(i.aspect(), 0, 1, 0, 1);
            LongBuffer p = s.mallocLong(1);
            FsrReplay.vk(vkCreateImageView(FsrReplay.device, vi, null, p), "view");
            return p.get(0);
        }
    }

    public static void main(String[] args) {
        StringBuilder rep = new StringBuilder();
        if (!NativeLoader.load()) {
            System.out.println("# fsrvelocity FAIL setup: libiroh_fsr unavailable: " + NativeLoader.unavailableReason());
            System.exit(1);
        }
        FsrReplay.init(false, rep);
        System.out.print(rep);
        final int rw = 320, rh = 180, dw = 640, dh = 360, NF = FsrReplay.NF, LT = FsrReplay.LT;
        final float R = FsrReplay.RATIO, VX = FsrReplay.VX, VY = FsrReplay.VY, SQ = FsrReplay.SQ;

        // orthographic "camera": clip.x = x/320 - 1, clip.y = y/180 - 1 (x, y in DISPLAY px, rows grow with y like the
        // synthetic scene's sampling), clip.z = z (world z = the depth the scene writes: 0.5 square, 0.9 background)
        Matrix4d pr = new Matrix4d().m00(2.0 / dw).m30(-1).m11(2.0 / dh).m31(-1).m22(1).m32(0).m33(1);
        Matrix4d inv = new Matrix4d(pr).invert();
        double[] cam = {0, 0, 0};

        // run A: P4-2a's own vectors
        FsrReplay.mvHook = null;
        FsrReplay.RunResult a = FsrReplay.runFsr(rw, rh, dw, dh, NF, FsrReplay.SYNTH, false, false, 0.1f, 1000f, false);

        VelocityPass vp = new VelocityPass(FsrReplay.device, 1);
        int st = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        FsrReplay.Img colourD = FsrReplay.image(VK_FORMAT_R8G8B8A8_UNORM, rw, rh, st, VK_IMAGE_ASPECT_COLOR_BIT);
        FsrReplay.Img mergedD = FsrReplay.image(VK_FORMAT_R32_SFLOAT, rw, rh, st, VK_IMAGE_ASPECT_COLOR_BIT);
        FsrReplay.Img reactD = FsrReplay.image(VK_FORMAT_R8_UNORM, rw, rh, st, VK_IMAGE_ASPECT_COLOR_BIT);
        FsrReplay.Img tcD = FsrReplay.image(VK_FORMAT_R8_UNORM, rw, rh, st, VK_IMAGE_ASPECT_COLOR_BIT);
        long colourV = view(colourD), mergedV = view(mergedD), reactV = view(reactD), tcV = view(tcD);
        int phases = JitterSequence.phaseCount(rw, dw);

        double[] sign = {1};
        FsrReplay.MvHook hook = new FsrReplay.MvHook() {
            final ObjectMotion om = new ObjectMotion();
            long depthView, mvView, depthImg, mvImg;

            @Override
            public void record(int t, float jx, float jy, FsrReplay.Img depth, FsrReplay.Img mv) {
                if (depth.image() != depthImg) { depthImg = depth.image(); depthView = view(depth); }
                if (mv.image() != mvImg) { mvImg = mv.image(); mvView = view(mv); }
                // the same jitter as the scene source used (P4-2a JitterSequence == TemporalMath)
                float[] j = new float[2];
                TemporalMath.jitterOffset(t, phases, j);
                if (j[0] != jx || j[1] != jy) throw new IllegalStateException("jitter mismatch at frame " + t);
                // square = moving object; previous position = current - (VX, VY) (or + with the wrong sign)
                double sx = 100 + VX * t, sy = 80 + VY * t;
                om.beginFrame(t + 1);
                om.submit(1L << 8, sx, sy, 0.5, 0, new double[]{sx, sy, 0.45}, new double[]{sx + SQ, sy + SQ, 0.55});
                VelocityPass.Frame f = new VelocityPass.Frame();
                // previous object position as seen by the pass: correct (sign 1) or reversed (sign -1)
                Matrix4d prevPR = t == 0 ? null : pr;
                om.build(cam, cam, prevPR, f);
                if (sign[0] < 0 && f.objectCount > 0) {
                    Matrix4d m = ObjectMotion.toPrevClip(pr, cam, cam, sx + VX, sy + VY, 0.5, 0, sx, sy, 0.5, 0, new Matrix4d());
                    for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) f.objects[8 + c * 4 + r] = (float) m.get(c, r);
                }
                f.width = rw; f.height = rh; f.mode = 0;
                f.reproject = new Matrix4d();   // static camera
                f.invCurPR = inv;
                f.jitterNdcX = TemporalMath.jitterNdcX(jx, rw);
                f.jitterNdcY = TemporalMath.jitterNdcY(jy, rh);
                f.jitterPxX = jx; f.jitterPxY = jy;
                f.endDepthView = depthView; f.sceneDepthView = 0L; f.velocityView = mvView;
                f.colourView = colourV; f.mergedDepthView = mergedV; f.reactiveView = reactV; f.tcView = tcV;
                FsrReplay.layout(mv, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
                for (FsrReplay.Img o : new FsrReplay.Img[]{colourD, mergedD, reactD, tcD})
                    FsrReplay.layout(o, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
                vp.record(FsrReplay.cmd, 0, f);
                FsrReplay.layout(mv, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        };
        FsrReplay.mvHook = hook;
        FsrReplay.RunResult b = FsrReplay.runFsr(rw, rh, dw, dh, NF, FsrReplay.SYNTH, false, false, 0.1f, 1000f, false);
        sign[0] = -1;
        FsrReplay.mvHook = new FsrReplay.MvHook() {
            final FsrReplay.MvHook inner = hook;
            @Override public void record(int t, float jx, float jy, FsrReplay.Img d, FsrReplay.Img m) { inner.record(t, jx, jy, d, m); }
        };
        FsrReplay.RunResult c = FsrReplay.runFsr(rw, rh, dw, dh, NF, FsrReplay.SYNTH, false, false, 0.1f, 1000f, false);
        FsrReplay.mvHook = null;

        float[] gt = FsrReplay.groundTruth(LT);
        float[] rend = new float[rw * rh * 4];
        float[] jl = JitterSequence.offset(LT, phases);
        FsrReplay.SYNTH.render(LT, jl[0], jl[1], rend, new float[rw * rh], new float[rw * rh * 2]);
        float[] bil = FsrReplay.bilinear(rend, rw, rh, dw, dh);
        int sx0 = (int) (100 + VX * LT), sy0 = (int) (80 + VY * LT), sx1 = sx0 + 120, sy1 = sy0 + 120;
        double eA = FsrReplay.rmse(a.outputs()[LT], gt, dw, dh, 0, 0, dw, dh), eB = FsrReplay.rmse(b.outputs()[LT], gt, dw, dh, 0, 0, dw, dh);
        double eC = FsrReplay.rmse(c.outputs()[LT], gt, dw, dh, 0, 0, dw, dh), eBil = FsrReplay.rmse(bil, gt, dw, dh, 0, 0, dw, dh);
        double sA = FsrReplay.rmse(a.outputs()[LT], gt, dw, dh, sx0 - 6, sy0 - 6, sx1 + 6, sy1 + 6), sB = FsrReplay.rmse(b.outputs()[LT], gt, dw, dh, sx0 - 6, sy0 - 6, sx1 + 6, sy1 + 6);
        double sC = FsrReplay.rmse(c.outputs()[LT], gt, dw, dh, sx0 - 6, sy0 - 6, sx1 + 6, sy1 + 6), sBil = FsrReplay.rmse(bil, gt, dw, dh, sx0 - 6, sy0 - 6, sx1 + 6, sy1 + 6);
        double tB = FsrReplay.rmse(b.outputs()[LT], gt, dw, dh, 160, 140, 325, 200), tA = FsrReplay.rmse(a.outputs()[LT], gt, dw, dh, 160, 140, 325, 200);
        System.out.printf("RMSE whole frame: P4-2a vectors %.4f | Iroh velocity pass %.4f | Iroh, object sign reversed %.4f | bilinear %.4f%n", eA, eB, eC, eBil);
        System.out.printf("RMSE moving square: P4-2a %.4f | Iroh %.4f | reversed %.4f | bilinear %.4f; trail: P4-2a %.4f Iroh %.4f%n", sA, sB, sC, sBil, tA, tB);
        ck(Math.abs(eB - eA) <= 0.002, "Iroh's inputs give the same whole-frame quality as the hand-made ones (|diff| <= 0.002)");
        ck(Math.abs(sB - sA) <= 0.004, "same quality on the moving square (|diff| <= 0.004)");
        ck(Math.abs(tB - tA) <= 0.004, "same ghost trail (|diff| <= 0.004)");
        ck(eB < eBil && sB < sBil, "Iroh's inputs beat bilinear (whole and square)");
        ck(sC > sB * 1.15, "reversed object motion is clearly worse on the square (convention check)");
        new java.io.File("build/p4-1b").mkdirs();
        FsrReplay.png(b.outputs()[LT], dw, dh, new java.io.File("build/p4-1b/fsr_iroh_velocity_last.png"));
        vp.destroy();
        System.out.println("# fsrvelocity " + (fails == 0 ? "PASS" : "FAIL " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }
}
