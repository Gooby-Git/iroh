package dev.elemore.iroh.api;

/** api 4: a vertex-pulling draw pipeline for {@link IrohDraw#multiDrawIndirectCount} ({@link IrohDraw#indirectPipeline}). */
public interface IndirectPipelineHandle extends GpuResource {
    int storageBuffers();

    int sampledTextures();

    int pushBytes();

    IrohTargets.Format colorFormat();

    IrohTargets.Format depthFormat();
}
