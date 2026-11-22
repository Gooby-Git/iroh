package dev.elemore.iroh.temporal;

import dev.elemore.iroh.upscale.ImageRef;
import dev.elemore.iroh.upscale.UpscaleInputs;
import dev.elemore.iroh.upscale.fsr.FsrUpscaler;

/**
 * Card P4-1b: the FSR context lifecycle, free of game classes so the lavapipe test drives the exact code the game runs
 * (FsrAdapter supplies a {@link Backend} over Iroh's device, MemoryGuard and frame ops; the test supplies one over its
 * headless device). Rules: (re)create when render size, display size or flags change; the old context is closed through
 * {@link Backend#destroyLater} and its bytes leave the external count then; memory is admitted from the SDK estimate
 * before create and the real numbers are counted after; any create failure (budget refusal, FFX error) marks the session
 * failed with a reason until {@link #clearFailure} (mode / size change), and the caller falls back to bilinear.
 * The first dispatch after a (re)create is a reset (no history).
 */
public final class FsrSession {
    public interface Backend {
        FsrUpscaler create(int fsrFlags, int shimFlags, int rw, int rh, int dw, int dh);
        boolean allow(long bytes);
        void account(long bytes);
        void destroyLater(Runnable r);
        void log(String msg);
    }

    private final Backend backend;
    private FsrUpscaler up;
    private long upBytes;
    private int flags, shim, rw, rh, dw, dh;
    private boolean fresh;
    private String failure;
    private int creates;
    private final StringBuilder messages = new StringBuilder();

    public FsrSession(Backend backend) {
        this.backend = backend;
    }

    public boolean failed() {
        return failure != null;
    }

    public String failure() {
        return failure == null ? "" : failure;
    }

    public void clearFailure() {
        failure = null;
    }

    /** contexts created so far (tests) */
    public int creates() {
        return creates;
    }

    /** bytes this session counts in the external total (tests) */
    public long countedBytes() {
        return upBytes;
    }

    /** The context for these parameters, (re)created if needed; null after a failure (caller uses bilinear). */
    public FsrUpscaler ensure(int fsrFlags, int shimFlags, int renderW, int renderH, int displayW, int displayH) {
        if (failure != null)
            return null;
        if (up != null && fsrFlags == flags && shimFlags == shim && renderW == rw && renderH == rh && displayW == dw && displayH == dh)
            return up;
        closeLater();
        long estimate = FsrUpscaler.estimate(renderW, renderH, displayW, displayH);
        if (!backend.allow(estimate)) {
            fail("FSR needs about " + (estimate >> 20) + " MiB of graphics memory and Iroh's budget refused it: using bilinear");
            return null;
        }
        try {
            up = backend.create(fsrFlags, shimFlags, renderW, renderH, displayW, displayH);
        } catch (RuntimeException e) {
            fail("FSR context could not be created (" + e.getMessage() + "): using bilinear");
            return null;
        }
        long[] m = up.memoryUsageBytes();
        upBytes = m[0] + m[1];
        backend.account(upBytes);
        flags = fsrFlags;
        shim = shimFlags;
        rw = renderW;
        rh = renderH;
        dw = displayW;
        dh = displayH;
        fresh = true;
        creates++;
        return up;
    }

    /** Records the dispatch; forces {@code reset} on the first frame of a context. */
    public void dispatch(UpscaleInputs in) {
        if (up == null)
            throw new IllegalStateException("dispatch without a context");
        if (fresh) {
            in.reset = true;
            fresh = false;
        }
        up.dispatch(in);
        String m = up.drainMessages();
        if (!m.isEmpty() && messages.length() < 4096)
            messages.append(m);
    }

    public String drainMessages() {
        String s = messages.toString();
        messages.setLength(0);
        return s;
    }

    private void fail(String why) {
        failure = why;
        backend.log(why);
    }

    private void closeLater() {
        if (up == null)
            return;
        FsrUpscaler old = up;
        long bytes = upBytes;
        up = null;
        upBytes = 0;
        backend.destroyLater(() -> {
            old.close();
            backend.account(-bytes);
        });
    }

    /** mode off / upscaler switch: deferred close */
    public void close() {
        closeLater();
    }

    /** GPU idle (shutdown): close at once */
    public void closeNow() {
        if (up == null)
            return;
        up.close();
        backend.account(-upBytes);
        up = null;
        upBytes = 0;
    }

    /** Per-frame numbers of a dispatch (game-free twin of UpscalerAdapter.Inputs' scalars). */
    public record Numbers(float jitterX, float jitterY, float mvScaleX, float mvScaleY, int renderW, int renderH,
                          int displayW, int displayH, float sharpness, float frameTimeMs, float cameraNear, float cameraFar,
                          float fovY, boolean reset) {}

    /** Maps Iroh's inputs onto P4-2a's UpscaleInputs (field for field; fsr.md A.3 step 6). */
    public static UpscaleInputs inputs(long cmd, ImageRef colour, ImageRef depth, ImageRef mv, ImageRef reactive, ImageRef tc,
                                       ImageRef out, Numbers n) {
        UpscaleInputs u = new UpscaleInputs();
        u.commandBuffer = cmd;
        u.color = colour;
        u.depth = depth;
        u.motionVectors = mv;
        u.reactive = reactive;
        u.transparency = tc;
        u.output = out;
        u.jitterX = n.jitterX();
        u.jitterY = n.jitterY();
        u.motionVectorScaleX = n.mvScaleX();
        u.motionVectorScaleY = n.mvScaleY();
        u.renderWidth = n.renderW();
        u.renderHeight = n.renderH();
        u.displayWidth = n.displayW();
        u.displayHeight = n.displayH();
        u.enableSharpening = n.sharpness() > 0f;
        u.sharpness = Math.min(1f, Math.max(0f, n.sharpness()));
        u.frameTimeMs = n.frameTimeMs();
        u.cameraNear = n.cameraNear();
        u.cameraFar = n.cameraFar();
        u.cameraFovYRadians = n.fovY();
        u.reset = n.reset();
        return u;
    }
}
