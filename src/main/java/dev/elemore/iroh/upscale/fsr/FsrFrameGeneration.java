package dev.elemore.iroh.upscale.fsr;

import dev.elemore.iroh.upscale.ImageRef;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FSR frame interpolation + optical flow (AMD FidelityFX SDK 1.1.4, MIT) WITHOUT the SDK's Win32 swapchain proxy: per real frame call
 * {@link #dispatch}, which records Prepare -> OpticalFlow -> FrameInterpolation into the caller's command buffer and produces the
 * image BETWEEN the previous real frame and the current one. Presenting (two images per real frame, pacing) is the caller's job
 * (docs/P4-PLAN.md P8 note). Inputs: HUD-less presentation colour of the CURRENT frame (display size, tonemapped sRGB by default),
 * depth + motion vectors (render size, same conventions as the upscaler), camera data, a frame id that increments by exactly 1.
 * THREAD RULE: render thread only; create/destroy with the GPU idle.
 */
public final class FsrFrameGeneration implements AutoCloseable {
    public static final int SIZE = 256;
    public static final int FLAG_RESET = 1, FLAG_DEBUG_VIEW = 2, FLAG_LINEAR_COLOR = 4, FLAG_SKIP_OPTICAL_FLOW = 8;
    private long ctx;
    private final ByteBuffer params = ByteBuffer.allocateDirect(SIZE).order(ByteOrder.LITTLE_ENDIAN);
    public final long[] memory;
    public final String info;

    /** Per-frame input. Images: colour/depth/motionVectors SHADER_READ_ONLY, output GENERAL. */
    public static final class Frame {
        public long commandBuffer;
        public ImageRef color = ImageRef.NONE, depth = ImageRef.NONE, motionVectors = ImageRef.NONE, output = ImageRef.NONE;
        public float jitterX, jitterY, mvScaleX = 1f, mvScaleY = 1f;
        public int renderWidth, renderHeight, displayWidth, displayHeight;
        public float frameTimeMs = 16.6f, cameraNear, cameraFar, fovYRadians, viewSpaceToMeters = 1f;
        public float[] cameraPosition = new float[3], cameraUp = new float[3], cameraRight = new float[3], cameraForward = new float[3];
        public long frameId;
        public boolean reset, debugView, linearColor, skipOpticalFlow;
    }

    public FsrFrameGeneration(long instance, long physicalDevice, long device, long getDeviceProcAddr, boolean hdr, int shimFlags, int renderW, int renderH, int displayW, int displayH, int backBufferVkFormat) {
        if (!NativeLoader.load()) throw new FsrException("FSR unavailable: " + NativeLoader.unavailableReason());
        if (FsrNative.fgStructSize() != SIZE) throw new FsrException("native frame-generation struct size " + FsrNative.fgStructSize() + " != " + SIZE);
        ctx = FsrNative.fgCreate(instance, physicalDevice, device, getDeviceProcAddr, hdr ? 1 : 0, shimFlags, renderW, renderH, displayW, displayH, backBufferVkFormat);
        long[] m = FsrNative.fgMemoryBytes(ctx);
        memory = new long[]{m[0], m[1]};
        info = FsrNative.fgInfo(ctx);
    }

    public void dispatch(Frame f) {
        if (ctx == 0) throw new FsrException("dispatch on a closed FsrFrameGeneration");
        ByteBuffer b = params;
        b.clear();
        b.putLong(0, f.commandBuffer);
        image(b, 8, f.color); image(b, 40, f.depth); image(b, 72, f.motionVectors); image(b, 104, f.output);
        b.putFloat(136, f.jitterX).putFloat(140, f.jitterY).putFloat(144, f.mvScaleX).putFloat(148, f.mvScaleY);
        b.putInt(152, f.renderWidth).putInt(156, f.renderHeight).putInt(160, f.displayWidth).putInt(164, f.displayHeight);
        b.putFloat(168, f.frameTimeMs).putFloat(172, f.cameraNear).putFloat(176, f.cameraFar).putFloat(180, f.fovYRadians).putFloat(184, f.viewSpaceToMeters);
        for (int i = 0; i < 3; i++) {
            b.putFloat(188 + 4 * i, f.cameraPosition[i]).putFloat(200 + 4 * i, f.cameraUp[i]).putFloat(212 + 4 * i, f.cameraRight[i]).putFloat(224 + 4 * i, f.cameraForward[i]);
        }
        b.putLong(240, f.frameId);
        b.putInt(248, (f.reset ? FLAG_RESET : 0) | (f.debugView ? FLAG_DEBUG_VIEW : 0) | (f.linearColor ? FLAG_LINEAR_COLOR : 0) | (f.skipOpticalFlow ? FLAG_SKIP_OPTICAL_FLOW : 0));
        FsrNative.fgDispatch(ctx, b);
    }

    /** inspection of the shim-owned images (see FsrNative.fgSharedImages). */
    public long[] sharedImages() {
        return FsrNative.fgSharedImages(ctx);
    }

    public String drainMessages() {
        return FsrNative.drainMessages();
    }

    private static void image(ByteBuffer b, int o, ImageRef r) {
        b.putLong(o, r.image()).putInt(o + 8, r.format()).putInt(o + 12, r.width()).putInt(o + 16, r.height()).putInt(o + 20, r.usage()).putInt(o + 24, r.layout()).putInt(o + 28, 0);
    }

    @Override
    public void close() {
        if (ctx != 0) {
            FsrNative.fgDestroy(ctx);
            ctx = 0;
        }
    }
}
