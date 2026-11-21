package dev.elemore.iroh.api;

/**
 * Vertices for one {@link IrohDraw#begin} batch, written into Iroh-owned reusable memory (no allocation). Render thread,
 * between {@code begin} and {@link #end}. Use the method that matches the pipeline's {@link PipelineDecl.VertexLayout}.
 * QUADS: 4 vertices per quad, counter-clockwise as seen from the front.
 */
public interface GeometryWriter {
    /** POSITION_COLOR. {@code argb} = 0xAARRGGBB. */
    GeometryWriter vertex(float x, float y, float z, int argb);

    /** POSITION_TEX_COLOR. */
    GeometryWriter vertex(float x, float y, float z, float u, float v, int argb);

    /** Ends the batch; consecutive batches with the same pipeline and textures are drawn as one draw call. */
    void end();
}
