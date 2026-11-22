package dev.elemore.iroh.api;

import dev.elemore.iroh.apiimpl.DynamicTextures;
import net.minecraft.resources.ResourceLocation;

/**
 * CPU-updated textures (api 3, P1-13a): Tempest's {@code tempest:dynamic/*} maps, elemore-core's
 * {@code elemore_core:dynamic/lights}. Registered in the texture manager under {@code id} (what a pack's
 * {@code customTexture.*} binds), RGBA8, with the declared filter and wrap (REPEAT works, which vanilla's DynamicTexture
 * API cannot set). See {@link DynamicTextureHandle#hasConsumers()}: nearly free when nothing samples it.
 */
public final class IrohTextures {
    private IrohTextures() {
    }

    public enum Filter { NEAREST, LINEAR }

    public enum Wrap { CLAMP, REPEAT }

    /** Render thread (creates the GPU image). */
    public static DynamicTextureHandle createDynamic(ResourceLocation id, int width, int height, Filter filter, Wrap wrap) {
        return DynamicTextures.create(id, width, height, filter, wrap);
    }

    /** api 4: the block atlas id (bind with {@link IrohDraw#texture}). */
    public static ResourceLocation blockAtlas() {
        return net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
    }

    /** api 4: the lightmap texture id (linear filtered, like vanilla's turnOnLightLayer). */
    public static ResourceLocation lightmap() {
        return dev.elemore.iroh.compat.LightmapFilter.lightmapLocation();
    }
}
