package dev.elemore.iroh.upscale;

/**
 * One temporal upscaler (FSR 3.1 now; FSR 4 on Windows and DLSS later implement the same contract). All methods that
 * touch the GPU run on the render thread while the frame's command buffer is recording; {@link #close()} only with the
 * GPU idle for this context.
 */
public interface Upscaler extends AutoCloseable {
    UpscalerCapabilities capabilities();

    /** Internal render size for a display size. */
    default int[] renderSize(QualityMode mode, int displayW, int displayH) {
        return mode.renderSize(displayW, displayH);
    }

    default JitterSequence jitterSequence(int renderW, int displayW) {
        return new JitterSequence(renderW, displayW);
    }

    default float mipBias(int renderW, int displayW) {
        return MipBias.forSizes(renderW, displayW);
    }

    /** Estimated GPU bytes for a context (sdk internals + shared images), within about 10% of the SDK's own report. */
    long estimateMemoryBytes(int maxRenderW, int maxRenderH, int maxDisplayW, int maxDisplayH);

    /** Actual bytes held after creation: {sdkInternal, sharedImages}. For MemoryGuard accounting. */
    long[] memoryUsageBytes();

    /** Record the upscale into {@code in.commandBuffer}. Images must already be in the layouts declared in {@link UpscaleInputs}. */
    void dispatch(UpscaleInputs in);

    @Override
    void close();
}
