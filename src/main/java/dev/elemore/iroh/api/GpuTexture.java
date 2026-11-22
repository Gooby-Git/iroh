package dev.elemore.iroh.api;

/**
 * api 4.1 (card V-M3): something a pipeline can sample (combined image sampler): a {@link RenderTargetHandle}, a texture
 * made with {@link IrohTextures#create}, or one of Iroh's own textures ({@link IrohTextures#blockAtlasTexture},
 * {@link IrohTextures#lightmapTexture}: resolved when bound, so a resource reload never leaves a stale view).
 */
public interface GpuTexture extends GpuResource {
    int width();

    int height();
}
