package dev.elemore.iroh.api;

import net.minecraft.resources.ResourceLocation;

import java.nio.ByteBuffer;

/** A CPU-updated texture from {@link IrohTextures#createDynamic}. */
public interface DynamicTextureHandle extends AutoCloseable {
    ResourceLocation id();

    int width();

    int height();

    /**
     * True while something samples this texture (a shader pack's customTexture, an RT material, an owner shader that
     * declared it). On the RASTER path nothing does, so mods should skip computing the pixels: check this first.
     * Any thread; a volatile read.
     */
    boolean hasConsumers();

    /**
     * Upload {@code w x h} RGBA8 texels (tightly packed rows) at (x, y). Render thread. Returns immediately without copying
     * when {@link #hasConsumers()} is false. Otherwise the pixels are copied and uploaded asynchronously before the next
     * draw that samples the texture.
     */
    void update(int x, int y, int w, int h, ByteBuffer rgba);

    @Override
    void close();
}
