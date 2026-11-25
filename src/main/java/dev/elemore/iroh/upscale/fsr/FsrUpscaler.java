package dev.elemore.iroh.upscale.fsr;

import dev.elemore.iroh.upscale.*;
import dev.elemore.iroh.upscale.UpscalerCapabilities.Input;

import java.nio.ByteBuffer;
import java.util.EnumSet;

/**
 * FSR 3.1 temporal upscaler on AMD's open Vulkan backend (FidelityFX SDK 1.1.4 base, MIT, patched to 3.1.6 parity: SDK v2.4.0 shaders + debug-check host deltas, see check-shader-delta.sh).
 * Create it with {@link #create}, which returns null-safe capabilities via {@link #probe()} when the native library is absent.
 * Thread rule: render thread only (see {@link FsrNative}).
 */
public final class FsrUpscaler implements Upscaler {
    /** FfxFsr3UpscalerInitializationFlagBits. */
    public static final int HDR = 1, DISPLAY_RES_MVS = 2, MV_JITTER_CANCEL = 4, DEPTH_INVERTED = 8, DEPTH_INFINITE = 16,
            AUTO_EXPOSURE = 32, DYNAMIC_RESOLUTION = 64, DEBUG_CHECKING = 256;
    /** shim flags */
    public static final int NO_FP16 = 1, NO_WAVE64 = 2;

    private long ctx;
    private final ByteBuffer params = FsrDispatchStruct.allocate();
    private final long[] mem;
    private final int flags;

    private FsrUpscaler(long ctx, int flags) {
        this.ctx = ctx;
        this.flags = flags;
        long[] m = FsrNative.memoryBytes(ctx);
        this.mem = new long[]{m[0], m[1]};
    }

    /** Capabilities without creating anything: available iff the native library loads. */
    public static UpscalerCapabilities probe() {
        if (!NativeLoader.load()) return UpscalerCapabilities.unavailable("FSR 3.1", NativeLoader.unavailableReason());
        return new UpscalerCapabilities("FSR 3.1", FsrNative.version(), true, "",
                EnumSet.of(Input.COLOR, Input.DEPTH, Input.MOTION_VECTORS), EnumSet.of(Input.EXPOSURE, Input.REACTIVE_MASK, Input.TRANSPARENCY_MASK),
                true, true, true, true);
    }

    /**
     * @param device raw VkInstance/VkPhysicalDevice/VkDevice handles (LWJGL {@code .address()}); the device must have been
     *               created with shaderStorageImageExtendedFormats + shaderStorageImageWriteWithoutFormat + subgroup basic/quad,
     *               and shaderFloat16/shaderInt16 + 16-bit storage if fp16 should be used.
     * @throws FsrException with the FFX error name when the context cannot be created
     */
    public static FsrUpscaler create(long instance, long physicalDevice, long device, long getDeviceProcAddr, int fsrFlags,
                                     int shimFlags, int maxRenderW, int maxRenderH, int maxDisplayW, int maxDisplayH) {
        if (!NativeLoader.load()) throw new FsrException("FSR unavailable: " + NativeLoader.unavailableReason());
        long c = FsrNative.create(instance, physicalDevice, device, getDeviceProcAddr, fsrFlags, shimFlags, maxRenderW, maxRenderH, maxDisplayW, maxDisplayH);
        return new FsrUpscaler(c, fsrFlags);
    }

    @Override
    public UpscalerCapabilities capabilities() {
        return probe();
    }

    @Override
    public long estimateMemoryBytes(int maxRenderW, int maxRenderH, int maxDisplayW, int maxDisplayH) {
        return estimate(maxRenderW, maxRenderH, maxDisplayW, maxDisplayH);
    }

    /** Per-pixel sums from the SDK's internal resource list (ffx_fsr3upscaler.cpp:553-614) plus the three shared images (12 B per render pixel). */
    public static long estimate(int rw, int rh, int dw, int dh) {
        double r = (double) rw * rh, u = (double) dw * dh;
        return (long) (r * (28.0 + 2.1 + 12.0) + u * 17.0);
    }

    @Override
    public long[] memoryUsageBytes() {
        return mem.clone();
    }

    @Override
    public void dispatch(UpscaleInputs in) {
        if (ctx == 0) throw new FsrException("dispatch on a closed FsrUpscaler");
        FsrDispatchStruct.write(params, in);
        FsrNative.dispatch(ctx, params);
    }

    /** Sets one tuning constant (clamped by the SDK); returns the stored value. */
    public float setConstant(FsrTuning.Key key, float value) {
        if (ctx == 0) throw new FsrException("setConstant on a closed FsrUpscaler");
        return FsrNative.setConstant(ctx, key.id, value);
    }

    /** The five tuning constants as stored, in {@link FsrTuning.Key} order. */
    public float[] constants() {
        if (ctx == 0) throw new FsrException("constants on a closed FsrUpscaler");
        return FsrNative.getConstants(ctx);
    }

    /** Applies all five values of a tuning set. */
    public void apply(FsrTuning t) {
        for (FsrTuning.Key k : FsrTuning.Key.values()) setConstant(k, t.get(k));
    }

    /** FFX runtime warnings/errors since the last call (empty string when none; only filled when DEBUG_CHECKING is set). */
    public String drainMessages() {
        return FsrNative.drainMessages();
    }

    public int flags() {
        return flags;
    }

    @Override
    public void close() {
        if (ctx != 0) {
            FsrNative.destroy(ctx);
            ctx = 0;
        }
    }
}
