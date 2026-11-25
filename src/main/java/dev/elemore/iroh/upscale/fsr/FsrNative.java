package dev.elemore.iroh.upscale.fsr;

import java.nio.ByteBuffer;

/**
 * JNI surface of libiroh_fsr (iroh-native/fsr/src/iroh_fsr_jni.cpp). THREAD RULE: a context handle is used by one thread at
 * a time (the render thread, while recording the frame's command buffer); create/destroy with the GPU idle. The static
 * helpers are pure. Every failure throws {@link FsrException} naming the FFX error.
 * Do not call these directly: use {@link FsrUpscaler}; {@link NativeLoader#load()} must have succeeded.
 */
public final class FsrNative {
    private FsrNative() {}

    public static native String version();
    public static native int structSize();
    public static native int phaseCount(int renderW, int displayW);
    public static native float[] jitter(int index, int phaseCount);
    public static native float upscaleRatio(int mode);
    public static native int[] renderSize(int mode, int displayW, int displayH);
    public static native String drainMessages();
    /** shimFlags: 1 = pretend no fp16 (use 32-bit permutations), 2 = pretend no wave64. getDeviceProcAddr 0 = libvulkan's. */
    public static native long create(long instance, long physicalDevice, long device, long getDeviceProcAddr, int fsrFlags,
                                     int shimFlags, int maxRenderW, int maxRenderH, int maxUpscaleW, int maxUpscaleH);
    /** {sdkInternalGpuBytes, shimSharedImageBytes, hostScratchBytes}. */
    public static native long[] memoryBytes(long ctx);
    public static native void destroy(long ctx);
    public static native void dispatch(long ctx, ByteBuffer params);
/** FfxFsr3UpscalerConfigureUpscaleKey (0..4, see {@link FsrTuning}); returns the value the SDK stored after its own clamp. */
public static native float setConstant(long ctx, int key, float value);
/** The five tuning constants as currently stored, in key order. */
public static native float[] getConstants(long ctx);

    // ---- frame generation components (optical flow + frame interpolation, no swapchain proxy)
    public static native int fgStructSize();
    /** flags bit0: HDR colour input; backBufferVkFormat: VkFormat of the presentation colour image. */
    public static native long fgCreate(long instance, long physicalDevice, long device, long getDeviceProcAddr, int flags, int shimFlags, int renderW, int renderH, int displayW, int displayH, int backBufferVkFormat);
    public static native String fgInfo(long ctx);
    /** {sdkInternalGpuBytes, shimSharedImageBytes, hostScratchBytes}. */
    public static native long[] fgMemoryBytes(long ctx);
    /** inspection: {image, width, height, vkFormat} x5: optical flow vector, SCD, dilated depth, dilated MV, reconstructed previous depth. */
    public static native long[] fgSharedImages(long ctx);
    public static native void fgDestroy(long ctx);
    public static native void fgDispatch(long ctx, ByteBuffer params);
}
