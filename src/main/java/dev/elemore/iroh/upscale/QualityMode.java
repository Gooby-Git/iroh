package dev.elemore.iroh.upscale;

/** Upscaling quality presets (per-dimension ratios as AMD names them; DLSS maps to the same ratios). */
public enum QualityMode {
    NATIVE_AA("Native AA", 1.0f), QUALITY("Quality", 1.5f), BALANCED("Balanced", 1.7f),
    PERFORMANCE("Performance", 2.0f), ULTRA_PERFORMANCE("Ultra Performance", 3.0f);

    public final String label;
    public final float ratio;

    QualityMode(String label, float ratio) {
        this.label = label;
        this.ratio = ratio;
    }

    /** Render size for a display size: {@code (uint32)((float)display / ratio)} exactly as ffxFsr3UpscalerGetRenderResolutionFromQualityMode. */
    public int[] renderSize(int displayW, int displayH) {
        return renderSize(displayW, displayH, ratio);
    }

    /** Custom scale (ratio >= 1). */
    public static int[] renderSize(int displayW, int displayH, float ratio) {
        if (!(ratio >= 1f)) throw new IllegalArgumentException("ratio must be >= 1: " + ratio);
        return new int[]{(int) ((float) displayW / ratio), (int) ((float) displayH / ratio)};
    }

    /** The FSR enum value (FfxFsr3UpscalerQualityMode order equals this enum's ordinal). */
    public int fsrValue() {
        return ordinal();
    }
}
