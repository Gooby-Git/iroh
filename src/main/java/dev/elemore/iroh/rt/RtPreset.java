package dev.elemore.iroh.rt;

/**
 * RT quality presets (docs/P5-DESIGN.md section 11). Each changes ray counts and rates, never the look's definition.
 * Rays per pixel are at render resolution; "half" = checkerboard (one field per frame; recon.comp fills the other field,
 * the temporal denoiser integrates both). P5-1 MEASURED (lavapipe, full chain; rtLook village scenes min-max / rtReplay
 * step (i) test-scene move): LOW 1.17-1.88 / 1.44, MEDIUM 1.24-1.99 / 1.52, HIGH 1.54-2.54 / 1.89, ULTRA 1.57-2.57 / 1.92
 * rays per render pixel per frame. Frame-cost numbers from {@link #estimateMs} are ESTIMATES from the Radiance datum
 * (knowledge/rt-radiance.md), never measurements. {@link #PATH_TRACED} (multi-bounce, ReSTIR) is designed, not built.
 */
public enum RtPreset {
    //            sunRate giRate lightCandidates cache  reflRoughMax reflRate historyCap renderScale
    LOW(          1.0f,   0.5f,  4,              true,  0.15f,       0.5f,    32,        2.0f),
    MEDIUM(       1.0f,   0.5f,  8,              true,  0.30f,       0.5f,    32,        1.7f),
    HIGH(         1.0f,   1.0f,  8,              true,  0.40f,       1.0f,    32,        1.5f),
    /** P5-1: native-resolution RT, FSR as anti-aliasing only (FSR "Native AA", 8 jitter phases). */
    ULTRA(        1.0f,   1.0f,  16,             true,  0.50f,       1.0f,    32,        1.0f),
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

    /** Worst measured rays per render pixel (P5-1 village scenes: cave / underwater). */
    public float measuredWorstRaysPerPixel() {
        return switch (this) {
            case LOW -> 1.88f;
            case MEDIUM -> 1.99f;
            case HIGH -> 2.54f;
            case ULTRA, PATH_TRACED -> 2.57f;
        };
    }

    /** Radiance datum (UNVERIFIED): ~20 ms for its RT pass at 1080p native on an RX 9070 XT with ~7 rays/px assumed. */
    public static final double ASSUMED_RAYS_PER_SECOND = 2_073_600 * 7.0 / 20e-3;
    /** Non-ray stages at 1707x960 from docs/P5-DESIGN.md section 10 (G-buffer, AS upkeep, denoise, compose, FSR, post): ESTIMATE. */
    public static final double ASSUMED_FIXED_MS_1707x960 = 0.8 + 0.7 + 2.0 + 0.5 + 0.5 + 1.0;

    /** ESTIMATED GPU frame cost (ms) of {@code raysPerPixel} at {@code pixels} render pixels: rays at the assumed rate x 1.6 for shading/candidates + fixed stages scaled by pixels. */
    public static double estimateMs(double raysPerPixel, long pixels) {
        return raysPerPixel * pixels / ASSUMED_RAYS_PER_SECOND * 1e3 * 1.6 + ASSUMED_FIXED_MS_1707x960 * pixels / (1707.0 * 960.0);
    }
}
