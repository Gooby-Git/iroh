package dev.elemore.iroh.rt;

/**
 * RT quality presets (docs/P5-DESIGN.md section 12). Each changes ray counts and rates, never the look's definition.
 * Rays per pixel are at render resolution; "half" = checkerboard (one field per frame, the denoiser fills the other).
 * {@link #PATH_TRACED} is the DECISIONS #9 top preset (multi-bounce), designed in P5-4, not built in P5-0.
 */
public enum RtPreset {
    //            sunRate giRate lightCandidates cache  reflRoughMax reflRate historyCap renderScale
    LOW(          0.5f,   0.5f,  4,              true,  0.15f,       0.5f,    32,        2.0f),
    MEDIUM(       1.0f,   0.5f,  8,              true,  0.30f,       0.5f,    32,        1.7f),
    HIGH(         1.0f,   1.0f,  8,              true,  0.40f,       1.0f,    32,        1.5f),
    PATH_TRACED(  1.0f,   1.0f,  16,             true,  1.00f,       1.0f,    32,        1.5f);

    /** Fraction of pixels tracing that pass per frame (1 = full rate, 0.5 = checkerboard). */
    public final float sunRate, giRate, reflRate;
    /** RIS candidates per light sample (RtFrameData.lightCandidates). */
    public final int lightCandidates;
    public final boolean radianceCache;
    /** Specular rays only below this roughness (water always). */
    public final float reflRoughnessMax;
    public final int historyCap;
    /** Display / render size ratio handed to the upscaler (FSR Quality 1.5, Balanced 1.7, Performance 2.0). */
    public final float renderScale;

    RtPreset(float sunRate, float giRate, int lightCandidates, boolean radianceCache, float reflRoughnessMax, float reflRate, int historyCap,
             float renderScale) {
        this.sunRate = sunRate;
        this.giRate = giRate;
        this.lightCandidates = lightCandidates;
        this.radianceCache = radianceCache;
        this.reflRoughnessMax = reflRoughnessMax;
        this.reflRate = reflRate;
        this.historyCap = historyCap;
        this.renderScale = renderScale;
    }

    /**
     * Expected rays per render pixel from per-pass rates measured on the test scene (rtReplay: sun 0.52, block light
     * 0.46, GI 0.76 without cache, ~0.60 with, reflections 0.02 per pixel at full rate; scene-dependent: caves and
     * interiors trace more secondary shadow rays than open terrain). Planning number, not a measurement of a frame.
     */
    public float expectedRaysPerPixel(float sun, float light, float gi, float refl) {
        return sun * sunRate + light * sunRate + gi * giRate + refl * reflRate;
    }
}
