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
}
