package dev.elemore.iroh.temporal;

import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4d;

/**
 * Pure maths of the temporal-upscaler inputs (card P4-1a). No game, no Vulkan; {@code ./gradlew temporalMathTest}.
 * P4-1b: the SDK formulas (render size, phase count, Halton jitter, mip bias) live ONCE in {@code dev.elemore.iroh.upscale}
 * (ScaleMode, JitterSequence, MipBias: bit-exact against the SDK in upscaleTest); the methods here delegate.
 *
 * <h2>Pixel space (Iroh, RQ-16)</h2>
 * Every Iroh render target stores GL row order: image row 0 = the BOTTOM row, and a positive-height Vulkan viewport
 * maps NDC y = -1 to row 0 (Orientation). So in Iroh the texel row index GROWS with NDC y, and the texel column with
 * NDC x: {@code texel = (ndc + 1) / 2 * size}, no sign flip on either axis. FSR reads Iroh's images as they are
 * stored (it never sees the present flip), so FSR's "pixel" axes ARE Iroh's texel axes.
 *
 * <h2>Jitter (derivation)</h2>
 * FSR's {@code jitterOffset} (jx, jy) in render pixels means: the scene content was shifted by +jx texels along the
 * column axis and +jy texels along the row axis (AMD's sample applies {@code (2 jx / W, -2 jy / H)} in D3D, where
 * NDC +y is row -1; the content moves by +jy rows). In Iroh rows grow with NDC y, so the same content shift is the
 * NDC translation {@code (+2 jx / W, +2 jy / H)}: Iroh does NOT negate y. It is applied as {@code P' = T * P}
 * (translation pre-multiplied), which adds {@code t * clip.w} to clip.xy and so shifts every projected point by
 * exactly t in NDC, at any depth, for perspective and orthographic matrices alike. FSR gets (jx, jy) unchanged.
 *
 * <h2>Motion vectors</h2>
 * Iroh stores {@code prevUv - curUv} (UV = texel / size, Iroh axes, unjittered positions on both sides) in an RG16F
 * image at render size and passes {@code motionVectorScale = (renderW, renderH)}: FSR's GLSL multiplies the texel by
 * {@code scale / renderSize} = 1, i.e. it expects UV units, current -> previous (ffx_fsr3upscaler_callbacks_glsl.h
 * LoadInputMotionVector). In Iroh's axes {@code uvDelta = ndcDelta / 2} with no y flip.
 * Camera-only reprojection of a pixel from its depth: terrain is camera-relative, so with {@code PR = P * R}
 * (projection times camera rotation, no translation) and {@code d = camCur - camPrev} (doubles on the CPU),
 * {@code prevClip = PR_prev * (h.xyz + d * h.w)} where {@code h = PR_cur^-1 * (ndc, depth, 1)} (homogeneous, not
 * divided). That is ONE matrix {@code M = PR_prev * T(d) * PR_cur^-1}, built here in double precision; it is also
 * right for the sky at depth 1 with Iroh's infinite far plane (h.w = 0: a direction, rotation only).
 */
public final class TemporalMath {
    private TemporalMath() {}

    /**
     * Depth the sky is reprojected at. Iroh's projection is JOML's infinite perspective (Matrix4fM, zZeroToOne): it maps
     * infinity to {@code -m22 = 1 - 1e-6}, so the CLEARED depth 1.0 (sky, clouds without depth writes) lies beyond
     * infinity and unprojects behind the camera. Clamping to 1 - 2e-6 (about 50,000 blocks at near 0.05) makes the sky
     * follow camera rotation only, to well under a hundredth of a pixel.
     */
    public static final double SKY_DEPTH = 1.0 - 2e-6;

    /** Render size for a display size: {@code (uint)(display / ratio)} like ffxFsr3UpscalerGetRenderResolutionFromQualityMode, at least 1. */
    public static int renderSize(int display, float ratio) {
        if (ratio <= 0f)
            return display;
        return Math.max(1, dev.elemore.iroh.upscale.QualityMode.renderSize(display, display, Math.max(1f, ratio))[0]);
    }

    /** {@code int(8 * (display / render)^2)}, ffxFsr3UpscalerGetJitterPhaseCount; at least 1. */
    public static int jitterPhaseCount(int renderWidth, int displayWidth) {
        return Math.max(1, dev.elemore.iroh.upscale.JitterSequence.phaseCount(renderWidth, displayWidth));
    }

    /** Radical inverse in float arithmetic, the same steps as the SDK's {@code halton()} (MIT). */
    public static float halton(int index, int base) {
        return dev.elemore.iroh.upscale.JitterSequence.halton(index, base);
    }

    /** Jitter in render pixels, each in [-0.5, 0.5]: Halton(2,3) at {@code (index % phaseCount) + 1} minus 0.5 (ffxFsr3UpscalerGetJitterOffset). */
    public static void jitterOffset(int index, int phaseCount, float[] out) {
        float[] j = dev.elemore.iroh.upscale.JitterSequence.offset(Math.floorMod(index, phaseCount), phaseCount);
        out[0] = j[0];
        out[1] = j[1];
    }

    /** FSR's texture LOD bias for jittered rendering: {@code log2(render / display) - 1}. */
    public static float mipBias(int renderWidth, int displayWidth) {
        return dev.elemore.iroh.upscale.MipBias.forSizes(renderWidth, displayWidth);
    }

    /** NDC translation of a jitter (pixels) on a target of the given size, Iroh's axes (see class doc): +2j/size on both axes. */
    public static float jitterNdcX(float jx, int renderWidth) {
        return 2.0f * jx / renderWidth;
    }

    public static float jitterNdcY(float jy, int renderHeight) {
        return 2.0f * jy / renderHeight;
    }

    /** {@code dst = T(ndcX, ndcY, 0) * src}; dst may be src. */
    public static Matrix4f jitter(Matrix4fc src, float ndcX, float ndcY, Matrix4f dst) {
        return dst.set(src).translateLocal(ndcX, ndcY, 0f);
    }

    /** A perspective projection has a zero (or tiny) m33; an orthographic one has m33 = 1. */
    public static boolean isPerspective(Matrix4fc m) {
        return Math.abs(m.m33()) < 0.5f && Math.abs(m.m23()) > 0.5f;
    }

    /** {@code M = prevPR * T(dx,dy,dz) * curPR^-1} with d = camCur - camPrev (see class doc). */
    public static Matrix4d reprojection(Matrix4dc prevPR, Matrix4dc curPR, double dx, double dy, double dz, Matrix4d dst) {
        Matrix4d inv = new Matrix4d(curPR).invert();
        return dst.set(prevPR).translate(dx, dy, dz).mul(inv);
    }

    /**
     * Camera-only motion of the pixel at unjittered NDC (x, y) with depth {@code depth}: {@code out = prevUv - curUv}.
     * The CPU twin of {@code temporal/velocity.comp}. When the previous position is behind the camera (w <= 0) the
     * motion is 0 (FSR treats it as no history; the pixel disoccludes).
     */
    public static void motionUv(Matrix4dc m, double ndcX, double ndcY, double depth, double[] out) {
        Vector4d p = new Vector4d(ndcX, ndcY, Math.min(depth, SKY_DEPTH), 1.0).mul(m);
        if (p.w <= 1e-9) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        out[0] = (p.x / p.w - ndcX) * 0.5;
        out[1] = (p.y / p.w - ndcY) * 0.5;
    }

    /**
     * Motion of a point that moved from {@code prevPos} (previous frame, previous camera) to {@code curPos}: prevUv -
     * curUv. The per-object reference for card P4-1b (entities: previous model matrix per entity id).
     */
    public static double[] objectMotionUv(Matrix4dc prevPR, double[] prevCam, double[] prevPos,
                                          Matrix4dc curPR, double[] curCam, double[] curPos) {
        double[] a = projectUv(prevPR, prevCam[0], prevCam[1], prevCam[2], prevPos[0], prevPos[1], prevPos[2]);
        double[] b = projectUv(curPR, curCam[0], curCam[1], curCam[2], curPos[0], curPos[1], curPos[2]);
        return new double[]{a[0] - b[0], a[1] - b[1]};
    }

    /** Projects a world point: {@code clip = PR * (p - cam)}; returns {u, v, depth} in Iroh's texel-UV axes. */
    public static double[] projectUv(Matrix4dc pr, double camX, double camY, double camZ, double x, double y, double z) {
        Vector4d c = new Vector4d(x - camX, y - camY, z - camZ, 1.0).mul(pr);
        return new double[]{(c.x / c.w + 1) * 0.5, (c.y / c.w + 1) * 0.5, c.z / c.w};
    }
}
