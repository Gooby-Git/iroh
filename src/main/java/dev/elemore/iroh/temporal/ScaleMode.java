package dev.elemore.iroh.temporal;

import dev.elemore.iroh.upscale.QualityMode;

import java.util.Locale;

/**
 * The value of the {@code iroh.upscale.mode} switch (card P4-1a; renamed in P4-1b from temporal.QualityMode so there is
 * ONE quality-mode table: {@link QualityMode} in dev.elemore.iroh.upscale, from card P4-2a). Adds what the switch needs
 * beyond the presets: {@link #OFF} (the whole path inert, the default) and {@link #CUSTOM} (ratio from
 * {@code iroh.upscale.scale}). Every preset's ratio comes from {@link QualityMode}.
 */
public enum ScaleMode {
    OFF("off", null),
    NATIVE_AA("native", QualityMode.NATIVE_AA),
    QUALITY("quality", QualityMode.QUALITY),
    BALANCED("balanced", QualityMode.BALANCED),
    PERFORMANCE("performance", QualityMode.PERFORMANCE),
    ULTRA_PERFORMANCE("ultra", QualityMode.ULTRA_PERFORMANCE),
    CUSTOM("custom", null);

    public final String key;
    /** the preset, null for OFF and CUSTOM */
    public final QualityMode preset;

    ScaleMode(String key, QualityMode preset) {
        this.key = key;
        this.preset = preset;
    }

    /** display / render ratio; for CUSTOM the given custom value clamped to [1, 4]; 0 for OFF */
    public float ratio(float custom) {
        if (this == CUSTOM)
            return Float.isFinite(custom) ? Math.max(1.0f, Math.min(4.0f, custom)) : 1.0f;
        return preset == null ? 0f : preset.ratio;
    }

    public boolean on() {
        return this != OFF;
    }

    /** "off" / "native" / "quality" / "balanced" / "performance" / "ultra" / "custom"; null for anything else */
    public static ScaleMode parse(String s) {
        if (s == null)
            return OFF;
        String k = s.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty() || k.equals("false"))
            return OFF;
        for (ScaleMode m : values())
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
        for (ScaleMode m : values())
            sb.append(sb.isEmpty() ? "" : "|").append(m.key);
        return sb.toString();
    }
}
