package dev.elemore.iroh.rt.denoise;

/**
 * A denoiser instance over a fixed list of {@link DenoiserType}s (NRD: RELAX / REBLUR / SIGMA; Iroh's own temporal
 * accumulation as the licence-free fallback). GPU work is recorded on the render thread into the frame's command buffer;
 * {@link #close()} only with the GPU idle for this instance.
 */
public interface Denoiser extends AutoCloseable {
    String name();

    DenoiserType[] types();

    /** Bytes of permanent + transient textures for a resource size (from the library, exact), no GPU needed. */
    long estimateMemoryBytes(int width, int height);

    /** Actual GPU bytes held: {permanent, transient}. */
    long[] memoryUsageBytes();

    /** Apply denoiser settings by name (see NrdSettings / the fallback's own keys); unknown names throw. */
    void configure(int identifier, java.util.Map<String, Float> settings);

    void denoise(DenoiseFrame frame);

    @Override
    void close();
}
