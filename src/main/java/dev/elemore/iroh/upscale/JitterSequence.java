package dev.elemore.iroh.upscale;

/**
 * Camera jitter for temporal upscalers: Halton(2,3), {@code phaseCount = int(8 * (display/render)^2)}, offsets in pixels
 * in [-0.5, 0.5]. Same arithmetic (float) as the FidelityFX SDK so it matches {@code ffxFsr3UpscalerGetJitterOffset} bit
 * for bit (a test compares them through the native shim). DLSS accepts any sequence, so it reuses this one.
 */
public final class JitterSequence {
    public final int phaseCount;

    public JitterSequence(int renderW, int displayW) {
        this.phaseCount = phaseCount(renderW, displayW);
    }

    public static int phaseCount(int renderW, int displayW) {
        float r = (float) displayW / (float) renderW;
        return (int) (8.0f * (r * r));
    }

    /** Jitter in pixels for a frame index (any non-negative index; wraps over the phase count). */
    public float[] offset(int frameIndex) {
        return offset(frameIndex, phaseCount);
    }

    public static float[] offset(int index, int phaseCount) {
        if (phaseCount <= 0) throw new IllegalArgumentException("phaseCount " + phaseCount);
        int i = (index % phaseCount) + 1;
        return new float[]{halton(i, 2) - 0.5f, halton(i, 3) - 0.5f};
    }

    public static float halton(int index, int base) {
        float f = 1.0f, result = 0.0f;
        for (int cur = index; cur > 0; ) {
            f /= (float) base;
            result = result + f * (float) (cur % base);
            cur = (int) Math.floor((float) cur / (float) base);
        }
        return result;
    }

    /**
     * Projection-matrix translation (NDC) for a pixel jitter in IROH's axes: {+2*jx/renderW, +2*jy/renderH}. Iroh targets
     * keep GL row order with a positive-height viewport, so texel rows grow with NDC y and the SDK's D3D sample sign
     * ({@code -2*jy/H}) does not apply: content shifts by +jitter texels in x and y either way (P4-1a derivation in
     * dev.elemore.iroh.temporal.TemporalMath; proved by temporalMathTest and by fsrReplay's convention check).
     */
    public static float[] projectionOffsetNdc(float[] jitterPx, int renderW, int renderH) {
        return new float[]{2.0f * jitterPx[0] / renderW, 2.0f * jitterPx[1] / renderH};
    }
}
