package dev.elemore.iroh.upscale.fsr;

/**
 * The five FfxFsr3UpscalerConfigureUpscaleKey tuning constants (SDK v2.4.0 ffx_upscale.h / 1.1.4 ffx_fsr3upscaler.h), set
 * with ffxFsr3UpscalerSetConstant. Defaults and ranges are the header's; the SDK clamps on its own, {@link #clamp} is the
 * same rule for UI code that wants to show the stored value without a native call.
 */
public final class FsrTuning {
    public enum Key {
        /** 0 can improve temporal stability of bright pixels */
        VELOCITY_FACTOR(0, 1.0f, 0.0f, 1.0f),
        /** development knob: scales what is written to the reactive mask */
        REACTIVENESS_SCALE(1, 1.0f, 0.0f, Float.POSITIVE_INFINITY),
        /** scales the computed shading-change value (higher = more reactive) */
        SHADING_CHANGE_SCALE(2, 1.0f, 0.0f, Float.POSITIVE_INFINITY),
        /** accumulation added per frame at disoccluded pixels / reactive mask > 0; lower can reduce ghosting */
        ACCUMULATION_ADDED_PER_FRAME(3, 1.0f / 3.0f, 0.0f, 1.0f),
        /** higher can reduce flicker around swaying thin objects; too high increases ghosting (1.1.4+ default is -1/3) */
        MIN_DISOCCLUSION_ACCUMULATION(4, -1.0f / 3.0f, -1.0f, 1.0f);

        public final int id;
        public final float def, min, max;

        Key(int id, float def, float min, float max) {
            this.id = id;
            this.def = def;
            this.min = min;
            this.max = max;
        }
    }

    private final float[] v = new float[Key.values().length];

    public FsrTuning() {
        for (Key k : Key.values()) v[k.id] = k.def;
    }

    public static float clamp(Key k, float value) {
        if (Float.isNaN(value)) return k.def;
        return Math.max(k.min, Math.min(k.max, value));
    }

    public float get(Key k) {
        return v[k.id];
    }

    public FsrTuning set(Key k, float value) {
        v[k.id] = clamp(k, value);
        return this;
    }

    public boolean isDefault() {
        for (Key k : Key.values()) if (v[k.id] != k.def) return false;
        return true;
    }

    /** Property prefix for overrides: {@code -Diroh.upscale.fsr.velocityFactor=0.5} (camelCase key name; wins over the mode default). */
    public static final String PROPERTY_PREFIX = "iroh.upscale.fsr.";

    public static String propertyName(Key k) {
        StringBuilder b = new StringBuilder(PROPERTY_PREFIX);
        boolean up = false;
        for (char c : k.name().toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (c == '_') up = true;
            else { b.append(up ? Character.toUpperCase(c) : c); up = false; }
        }
        return b.toString();
    }

    /** Measured per-mode defaults (FSR-Q3, knowledge/fsr.md G.7) for a display/render ratio; SDK defaults where no win was measured. */
    public static FsrTuning modeDefaults(float ratio) {
        FsrTuning t = new FsrTuning();
        // Q3 sweep (fsr.md G.7): only SHADING_CHANGE_SCALE showed a win (flat-depth age-1 ghost 0.122 -> 0.068, trail 7 -> 5 px,
        // static edge / fence / sharpness untouched at every mode). ACCUMULATION_ADDED_PER_FRAME trades RMSE for speed
        // everywhere (no clean win), VELOCITY_FACTOR / REACTIVENESS_SCALE / MIN_DISOCCLUSION_ACCUMULATION: no measurable
        // effect on these scenes, so they stay at the SDK defaults. Same value for every ratio (the sweep showed no mode split).
        t.set(Key.SHADING_CHANGE_SCALE, 4.0f);
        return t;
    }

    /** Mode defaults for the ratio, then any -D override (clamped). This is what the adapter applies. */
    public static FsrTuning forRatio(float ratio) {
        FsrTuning t = modeDefaults(ratio);
        for (Key k : Key.values()) {
            String v = System.getProperty(propertyName(k));
            if (v == null || v.isBlank()) continue;
            try { t.set(k, Float.parseFloat(v.trim())); } catch (NumberFormatException ignored) { /* keep the default */ }
        }
        return t;
    }
}
