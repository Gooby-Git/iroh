package dev.elemore.iroh.rt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The RT core's per-frame constant block (std430, 400 bytes), mirror of {@code iroh/rt/rt_frame.glsl}. Minecraft-free:
 * the offline harness and the game fill the same fields. Matrices are column-major float[16], unjittered.
 */
public final class RtFrameData {
    public static final int BYTES = 400;
    public static final int F_ALPHA_TEST = 1, F_CACHE = 2, F_SOFT_SUN = 4, F_REJECT = 8, F_CACHE_UPDATE_ALL = 16;

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
        if (b.position() - p != BYTES) throw new IllegalStateException("RtFrameData layout drift: " + (b.position() - p));
        return b;
    }

    private static void vec(ByteBuffer b, float[] v, float w) {
        b.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]).putFloat(w);
    }
}
