package dev.elemore.iroh.temporal;

import java.util.Locale;

/**
 * Upscaler quality modes (card P4-1a). The ratio is display / render per axis, the same numbers as AMD's
 * {@code ffxFsr3UpscalerGetUpscaleRatioFromQualityMode} (FidelityFX SDK 1.1.4, MIT): Native AA 1.0, Quality 1.5,
 * Balanced 1.7, Performance 2.0, Ultra Performance 3.0. {@link #CUSTOM} takes its ratio from {@code iroh.upscale.scale}.
 * {@link #OFF} means the whole temporal path is inert (the default): one scene target at window size, as in rc14.
 */
public enum QualityMode {
    OFF("off", 0f),
    NATIVE_AA("native", 1.0f),
    QUALITY("quality", 1.5f),
    BALANCED("balanced", 1.7f),
    PERFORMANCE("performance", 2.0f),
    ULTRA_PERFORMANCE("ultra", 3.0f),
    CUSTOM("custom", 0f);

    public final String key;
    private final float ratio;

    QualityMode(String key, float ratio) {
        this.key = key;
        this.ratio = ratio;
    }

    /** display / render ratio; for CUSTOM the given custom value clamped to [1, 4]; 0 for OFF */
    public float ratio(float custom) {
        if (this == CUSTOM)
            return Float.isFinite(custom) ? Math.max(1.0f, Math.min(4.0f, custom)) : 1.0f;
        return ratio;
    }

    public boolean on() {
        return this != OFF;
    }

    /** "off" / "native" / "quality" / "balanced" / "performance" / "ultra" / "custom"; null for anything else */
    public static QualityMode parse(String s) {
        if (s == null)
            return OFF;
        String k = s.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty() || k.equals("false"))
            return OFF;
        for (QualityMode m : values())
            if (m.key.equals(k))
                return m;
        return switch (k) {
            case "nativeaa", "native_aa", "dlaa" -> NATIVE_AA;
            case "ultra_performance", "ultraperformance" -> ULTRA_PERFORMANCE;
            default -> null;
        };
    }

    public static String keys() {
        StringBuilder sb = new StringBuilder();
        for (QualityMode m : values())
            sb.append(sb.isEmpty() ? "" : "|").append(m.key);
        return sb.toString();
    }
}
