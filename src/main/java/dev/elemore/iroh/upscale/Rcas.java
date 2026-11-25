package dev.elemore.iroh.upscale;

/**
 * FSR-Q4: RCAS sharpening discipline. The per-mode "auto" strength comes from the offline sweep (knowledge/fsr.md G.8):
 * the strongest 0.1 step whose ringing on a step edge stays at or under 1% of the step and whose edge flicker stays within
 * 16% of RCAS off. The sweep is one edge + fence scene on lavapipe; the owner picks the final values from screenshot pairs.
 * Only FSR applies RCAS (nothing else in Iroh sharpens: the pack path owns its own CAS and the upscaler is off while the
 * pack path is on), so there is no second sharpening pass to disable.
 */
public final class Rcas {
    private Rcas() {}

    /** auto strength 0..1 for a display / render ratio */
    public static float forRatio(float ratio) {
        if (ratio < 1.6f)
            return 0.4f;      // Native AA 1x, Quality 1.5x
        if (ratio < 1.85f)
            return 0.3f;      // Balanced 1.7x: ringing passes 1% above 0.3
        if (ratio < 2.5f)
            return 0.4f;      // Performance 2x
        return 0.1f;          // Ultra Performance 3x: 0.1 already reaches 0.96% ringing
    }
}
