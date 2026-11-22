package dev.elemore.iroh.rt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The RT core's per-frame constant block (std430, 608 bytes), mirror of {@code iroh/rt/rt_frame.glsl}. Minecraft-free:
 * the offline harness and the game fill the same fields. Matrices are column-major float[16]: {@code viewProj} and
 * {@code prevViewProj} are UNJITTERED (motion vectors come from them); {@code invViewProj} is the inverse of the JITTERED
 * projection actually rasterised (primary rays go through the jittered sample). P5-1 appended the jitter and the look block
 * (Navidson-style sky, mountain fog, water, wetness, cloud-shadow hook; see docs/P5-DESIGN.md section 7).
 */
public final class RtFrameData {
    public static final int BYTES = 608;
    public static final int F_ALPHA_TEST = 1, F_CACHE = 2, F_SOFT_SUN = 4, F_REJECT = 8, F_CACHE_UPDATE_ALL = 16;
    /** P5-1: look sky/fog/water (off = the P5-0 analytic test atmosphere the CPU reference models). */
    public static final int F_LOOK = 32, F_UNDERWATER = 64, F_HALF_GI = 128, F_HALF_REFL = 256, F_LIGHT_GRID = 512,
            F_RESTIR = 1024, F_WAVES = 2048, F_CLOUD_SHADOW = 4096, F_HALF_LIGHT = 8192,
            /** P5-3: ReSTIR stores an occluded final sample with W = 0 for the next frame (visibility reuse). */
            F_RESTIR_VISREUSE = 16384;

    public final float[] invViewProj = new float[16], viewProj = new float[16], prevViewProj = new float[16];
    public final float[] camPos = new float[3], camFwd = new float[3], prevCamPos = new float[3], prevCamFwd = new float[3];
    public final float[] sunDir = new float[3];
    public float sunAngularRadius;            // radians, 0 = hard shadows
    public final float[] sunE = new float[3];  // irradiance at normal incidence
    public final float[] skyZenith = new float[3], skyHorizon = new float[3];
    public float fogDensity, fogFalloff, fogBase;
    public boolean fog;
    public int width, height, frameIndex, flags;
    public int lightCount, cacheMask, cacheMinSamples = 4, cacheMaxSamples = 64;
    public float cacheCellBase = 0.5f, cacheLodDistance = 16f, polishedRoughness;
    /** RIS candidates per light sample (1 = plain light sampling). */
    public int lightCandidates = 8;
    public final float[] waterExtinction = {0.30f, 0.10f, 0.06f};
    // ---- P5-1 additions (appended; order = rt_frame.glsl)
    /** Jitter: NDC offset added to the raster clip position (x, y) and the same in pixels (z, w); content shifts by +px. */
    public final float[] jitter = new float[4];
    /** Look sky: up colour + sunset amount; horizon colour + night factor; sunset band colour + rain; ground colour + time (s). */
    public final float[] skyUp = new float[4], skyMid = new float[4], skySunset = new float[4], skyDown = new float[4];
    /** Mountain fog (owner's NvMountainFog, LGPL grant): base altitude, height H, extinction, opacity cap. */
    public final float[] mfog0 = new float[4];
    /** patches, distance haze amount, haze distance, near-distance cutoff. */
    public final float[] mfog1 = new float[4];
    /** fog / mist colour rgb, glare strength. */
    public final float[] fogColor = new float[4];
    /** water: wave amplitude (normal tilt), wave time (s), index of refraction, underwater view-path density scale. */
    public final float[] water2 = {0.10f, 0f, 1.333f, 1f};
    /** water in-scatter colour rgb (underwater fog colour), unused. */
    public final float[] waterColor = {0.03f, 0.09f, 0.12f, 0f};
    /** wetness (rain 0..1), wet roughness, wet albedo darkening, unused. */
    public final float[] wet = {0f, 0.12f, 0.75f, 0f};
    /** Tempest cloud-shadow hook (stub): cover 0..1, feature size (blocks), drift x, drift z (blocks). */
    public final float[] cloud = new float[4];
    /** half-rate / reuse: checkerboard phase, ReSTIR temporal M cap, spatial taps, light-grid share of candidates. */
    public final float[] reuse = {0f, 20f, 0f, 0.5f};

    public ByteBuffer write(ByteBuffer b) {
        b.order(ByteOrder.LITTLE_ENDIAN);
        int p = b.position();
        for (float f : invViewProj) b.putFloat(f);
        for (float f : viewProj) b.putFloat(f);
        for (float f : prevViewProj) b.putFloat(f);
        vec(b, camPos, 0);
        vec(b, camFwd, 0);
        vec(b, prevCamPos, 0);
        vec(b, prevCamFwd, 0);
        vec(b, sunDir, (float) Math.cos(sunAngularRadius));
        vec(b, sunE, sunAngularRadius);
        vec(b, skyZenith, 0);
        vec(b, skyHorizon, 0);
        b.putFloat(fogDensity).putFloat(fogFalloff).putFloat(fogBase).putFloat(fog ? 1 : 0);
        b.putInt(width).putInt(height).putInt(frameIndex).putInt(flags);
        b.putInt(lightCount).putInt(cacheMask).putInt(cacheMinSamples).putInt(cacheMaxSamples);
        b.putFloat(cacheCellBase).putFloat(cacheLodDistance).putFloat(lightCandidates).putFloat(polishedRoughness);
        vec(b, waterExtinction, 0);
        for (float[] v : new float[][]{jitter, skyUp, skyMid, skySunset, skyDown, mfog0, mfog1, fogColor, water2, waterColor, wet, cloud, reuse})
            for (float f : v) b.putFloat(f);
        if (b.position() - p != BYTES) throw new IllegalStateException("RtFrameData layout drift: " + (b.position() - p));
        return b;
    }

    private static void vec(ByteBuffer b, float[] v, float w) {
        b.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]).putFloat(w);
    }
}
