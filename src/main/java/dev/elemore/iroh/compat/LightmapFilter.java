package dev.elemore.iroh.compat;

/**
 * Iroh (rc14, td-5fd20c): the lightmap texture is sampled with linear filtering after every upload, as the OpenGL pack
 * shows it (full sky light, uv 240/256 = the boundary between lightmap texels 14 and 15: linear gives their blend).
 * NativeImage uploads reset the sampler to the upload's blur flag (false for DynamicTexture.upload), so the filter is
 * re-applied in MNativeImage._upload for this one texture id, independent of who calls the upload (vanilla
 * LightTexture.updateLightTexture, or a mod that replaces it, such as Alex's Caves).
 */
public final class LightmapFilter {
    /** GL id of LightTexture's DynamicTexture; -1 until the LightTexture exists. */
    public static volatile int lightmapTextureId = -1;

    private LightmapFilter() {}

    public static boolean isLightmap(int glId) {
        return glId > 0 && glId == lightmapTextureId;
    }
}
