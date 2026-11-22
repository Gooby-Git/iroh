package dev.elemore.iroh.temporal;

import dev.elemore.iroh.debug.DebugSwitches;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Per-frame state of the temporal-upscaler inputs (card P4-1a). Pure: no game, no Vulkan, render thread only.
 * With {@code iroh.upscale.mode} off every flag stays false and every query below is the identity (UpscaleDefaultOffTest).
 * <p>Frame protocol (driven by TemporalRuntime from the GameRenderer hooks):
 * {@link #beginFrame} at GameRenderer.render HEAD -> world phase (renderLevel HEAD .. just before the GUI) during which
 * {@link #IN_WORLD} is true and perspective projections are jittered -> {@link #onWorldCamera} at the world start
 * (Fabric WorldRenderEvents.START: camera position, rotation-only model-view, UNJITTERED projection) ->
 * {@link #endWorld} builds the reprojection matrix for the velocity pass and rolls the camera history.
 */
public final class TemporalFrame {
    private TemporalFrame() {}

    /** a non-off mode is in effect this frame */
    public static boolean ACTIVE;
    /** the world phase is open: the main target is the render-size world image set */
    public static boolean IN_WORLD;
    /** perspective projections uploaded to the GPU get the jitter (world phase only) */
    public static boolean JITTER_ON;
    /** world-phase texture LOD bias in effect */
    public static boolean MIP_ON;

    public static ScaleMode mode = ScaleMode.OFF;
    public static int displayW, displayH, renderW, renderH;
    public static long frameIndex;
    public static int phaseCount = 1;
    public static float jitterPxX, jitterPxY, jitterNdcX, jitterNdcY;
    public static float mipBias;
    public static boolean velocityOn;
    public static boolean viewportFix;
    /** 0 none, 1 velocity, 2 reactive, 3 depth, 4 jitter (velocity.comp modes) */
    public static int debugMode;
    /** the upscaler must drop its history this frame (first frame, mode/size change, teleport, dimension change) */
    public static boolean reset = true;

    // camera history (doubles: world coordinates are large)
    private static final Matrix4d curPR = new Matrix4d(), prevPR = new Matrix4d();
    private static double curX, curY, curZ, prevX, prevY, prevZ;
    private static boolean haveCur, havePrev;
    public static float cameraNear = 0.05f, fovY;
    /** reprojection matrix for this frame's velocity pass (identity when there is no history) */
    public static final Matrix4d reproject = new Matrix4d();
    /** inverse of this frame's PR (camera-relative unprojection for object boxes) */
    public static final Matrix4d invCurPR = new Matrix4d();
    /** P4-1b moving objects; fed by TemporalRuntime, packed into {@link #params} at world end */
    public static final ObjectMotion objects = new ObjectMotion();
    /** P4-1b velocity pass parameters of this frame (objects, hand) */
    public static final VelocityPass.Frame params = new VelocityPass.Frame();
    // hand: P_hand * W (view bob / hurt bob) of this and the previous frame
    private static final Matrix4d handCur = new Matrix4d(), handPrev = new Matrix4d(), HAND_SCRATCH = new Matrix4d();
    private static boolean haveHandCur, haveHandPrev;
    /** hand reprojection of this frame, valid when {@link #handValid} */
    public static final Matrix4d handReproject = new Matrix4d();
    public static boolean handValid;

    /** teleport threshold (blocks per frame) beyond which history is dropped */
    public static final double TELEPORT = 64.0;

    private static final float[] JIT = new float[2];
    private static final Matrix4f SCRATCH = new Matrix4f();
    private static int lastRenderW, lastRenderH;
    private static ScaleMode lastMode = ScaleMode.OFF;

    /**
     * Reads the switches and sets this frame's sizes, jitter, bias. {@code temporalUpscaler} / {@code upscalerWantsVelocity}
     * come from the attached upscaler (bilinear: false / false).
     * @return true when the render size or on/off state changed (world post chains must be resized)
     */
    public static boolean beginFrame(int dispW, int dispH, boolean temporalUpscaler, boolean upscalerWantsVelocity) {
        ScaleMode m = UpscaleSwitches.mode();
        boolean on = m.on() && dispW > 0 && dispH > 0;
        mode = on ? m : ScaleMode.OFF;
        if (!on) {
            boolean changed = ACTIVE;
            ACTIVE = IN_WORLD = JITTER_ON = MIP_ON = false;
            velocityOn = false;
            debugMode = 0;
            haveCur = havePrev = false;
            reset = true;
            lastMode = ScaleMode.OFF;
            return changed;
        }
        float custom;
        try {
            custom = Float.parseFloat(DebugSwitches.getOr(UpscaleSwitches.SCALE, "1.5"));
        } catch (NumberFormatException e) {
            custom = 1.5f;
        }
        float ratio = m.ratio(custom);
        displayW = dispW;
        displayH = dispH;
        renderW = TemporalMath.renderSize(dispW, ratio);
        renderH = TemporalMath.renderSize(dispH, ratio);
        boolean changed = !ACTIVE || renderW != lastRenderW || renderH != lastRenderH;
        if (changed || m != lastMode) {
            reset = true;
            havePrev = false;
        }
        lastRenderW = renderW;
        lastRenderH = renderH;
        lastMode = m;
        ACTIVE = true;
        frameIndex++;
        objects.beginFrame(frameIndex);

        String j = DebugSwitches.getOr(UpscaleSwitches.JITTER, "auto");
        boolean jitter = j.equals("on") || (j.equals("auto") && temporalUpscaler);
        phaseCount = TemporalMath.jitterPhaseCount(renderW, displayW);
        if (jitter) {
            TemporalMath.jitterOffset((int) (frameIndex % Integer.MAX_VALUE), phaseCount, JIT);
            jitterPxX = JIT[0];
            jitterPxY = JIT[1];
        } else {
            jitterPxX = jitterPxY = 0f;
        }
        jitterNdcX = TemporalMath.jitterNdcX(jitterPxX, renderW);
        jitterNdcY = TemporalMath.jitterNdcY(jitterPxY, renderH);
        JITTER_ON = jitter;

        String mb = DebugSwitches.getOr(UpscaleSwitches.MIP_BIAS, "auto");
        if (mb.equals("off")) {
            mipBias = 0f;
        } else if (mb.equals("auto")) {
            mipBias = jitter ? TemporalMath.mipBias(renderW, displayW) : 0f;
        } else {
            try {
                mipBias = Float.parseFloat(mb);
            } catch (NumberFormatException e) {
                mipBias = 0f;
            }
        }
        MIP_ON = mipBias != 0f;

        debugMode = switch (DebugSwitches.getOr(UpscaleSwitches.DEBUG_VIEW, "off")) {
            case "velocity" -> 1;
            case "reactive" -> 2;
            case "depth" -> 3;
            case "jitter" -> 4;
            default -> 0;
        };
        String v = DebugSwitches.getOr(UpscaleSwitches.VELOCITY, "auto");
        velocityOn = v.equals("on") || (v.equals("auto") && (upscalerWantsVelocity || debugMode != 0));
        String vf = DebugSwitches.getOr(UpscaleSwitches.VIEWPORT_FIX, "on");
        viewportFix = vf.equals("on") || vf.equals("true") || vf.equals("1");
        return changed;
    }

    /**
     * The projection the GPU gets: jittered when the world phase is open and the matrix is a perspective one (world,
     * hand, sky); otherwise the argument itself. Mods reading RenderSystem.getProjectionMatrix and the matrices Fabric
     * events hand out are never touched (they keep the unjittered matrix). Returns a scratch matrix: use it at once.
     */
    public static Matrix4fc gpuProjection(Matrix4fc m) {
        if (!JITTER_ON || !IN_WORLD || !TemporalMath.isPerspective(m))
            return m;
        return TemporalMath.jitter(m, jitterNdcX, jitterNdcY, SCRATCH);
    }

    /** The same for a column-major float[16] (converted shaders' ProjMat). In place on {@code m}. */
    public static void gpuProjection(float[] m) {
        if (!JITTER_ON || !IN_WORLD)
            return;
        if (Math.abs(m[15]) >= 0.5f || Math.abs(m[11]) <= 0.5f)
            return;
        for (int c = 0; c < 4; c++) {
            m[c * 4] += jitterNdcX * m[c * 4 + 3];
            m[c * 4 + 1] += jitterNdcY * m[c * 4 + 3];
        }
    }

    /** World start: camera position, rotation-only model-view, unjittered world projection. */
    public static void onWorldCamera(double x, double y, double z, Matrix4fc rotation, Matrix4fc projection) {
        if (!ACTIVE)
            return;
        curPR.set(projection).mul(new Matrix4d().set(rotation));
        curX = x;
        curY = y;
        curZ = z;
        haveCur = true;
        if (Math.abs(projection.m11()) > 1e-6f)
            fovY = (float) (2.0 * Math.atan(1.0 / projection.m11()));
    }

    /** The hand's projection and model-view (bob) at renderHandsWithItems; unjittered. */
    public static void onHand(Matrix4fc projection, Matrix4fc modelView) {
        if (!ACTIVE)
            return;
        handCur.set(projection).mul(HAND_SCRATCH.set(modelView)); // INT-2: no per-frame Matrix4d
        haveHandCur = true;
    }

    /** Drops the history (dimension change, respawn, world change). */
    public static void invalidateHistory() {
        havePrev = false;
        reset = true;
    }

    /** World end: builds {@link #reproject} from the history, then rolls it. */
    public static void endWorld() {
        if (!ACTIVE)
            return;
        boolean history = false;
        if (haveCur && havePrev) {
            double dx = curX - prevX, dy = curY - prevY, dz = curZ - prevZ;
            if (dx * dx + dy * dy + dz * dz > TELEPORT * TELEPORT) {
                reproject.identity();
                reset = true;
            } else {
                TemporalMath.reprojection(prevPR, curPR, dx, dy, dz, reproject);
                history = true;
            }
        } else {
            reproject.identity();
        }
        if (haveCur)
            invCurPR.set(curPR).invert();
        objects.build(new double[]{curX, curY, curZ}, new double[]{prevX, prevY, prevZ}, history ? prevPR : null, params);
        handValid = haveHandCur && haveHandPrev && history;
        if (handValid)
            handReproject.set(handPrev).mul(new Matrix4d(handCur).invert());
        else
            handReproject.identity();
        if (haveHandCur)
            handPrev.set(handCur);
        haveHandPrev = haveHandCur;
        haveHandCur = false;
        if (haveCur) {
            prevPR.set(curPR);
            prevX = curX;
            prevY = curY;
            prevZ = curZ;
            havePrev = true;
        }
        haveCur = false;
    }

    /** after the upscaler consumed this frame */
    public static void frameDone() {
        reset = false;
    }

    /**
     * Viewport/scissor rects given in DISPLAY pixels by mods (Window size) while the world phase draws into the
     * render-size main target: a rect reaching beyond the render target is scaled by render/display. Rects inside the
     * render target (vanilla's own, from mainTarget.viewWidth) are left alone. In place on {x, y, w, h}.
     */
    public static boolean fixRect(int[] r) {
        if (!IN_WORLD || !viewportFix || displayW <= 0)
            return false;
        if (r[0] + r[2] <= renderW && r[1] + r[3] <= renderH)
            return false;
        double sx = (double) renderW / displayW, sy = (double) renderH / displayH;
        int x0 = (int) Math.round(r[0] * sx), y0 = (int) Math.round(r[1] * sy);
        int x1 = (int) Math.round((r[0] + r[2]) * sx), y1 = (int) Math.round((r[1] + r[3]) * sy);
        r[0] = x0;
        r[1] = y0;
        r[2] = x1 - x0;
        r[3] = y1 - y0;
        return true;
    }

    /** For tests: back to the state of a fresh JVM. */
    public static void resetForTest() {
        ACTIVE = IN_WORLD = JITTER_ON = MIP_ON = false;
        mode = lastMode = ScaleMode.OFF;
        frameIndex = 0;
        haveCur = havePrev = false;
        haveHandCur = haveHandPrev = handValid = false;
        reset = true;
        lastRenderW = lastRenderH = 0;
        reproject.identity();
    }
}
