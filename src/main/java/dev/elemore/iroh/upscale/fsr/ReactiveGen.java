package dev.elemore.iroh.upscale.fsr;

/**
 * FSR-Q6: parameters of the SDK's reactive-mask generator (ffxFsr3UpscalerContextGenerateReactiveMask). The pass computes
 * v = scale * (USE_COMPONENTS_MAX ? max|d| : length(d)) with d = final - opaque-only colour; with APPLY_THRESHOLD it outputs
 * binaryValue where v >= cutoff, else 0. {@link #DEFAULT} is the data-picked default (knowledge/fsr.md G.9).
 */
public record ReactiveGen(float scale, float cutoff, float binaryValue, int flags) {
    public static final int TONEMAP = 1, INVERSE_TONEMAP = 2, THRESHOLD = 4, COMPONENTS_MAX = 8;
    public static final ReactiveGen DEFAULT = new ReactiveGen(4f, 0f, 0.9f, COMPONENTS_MAX);
}
