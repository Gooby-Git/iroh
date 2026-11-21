package dev.elemore.iroh.api;

import dev.elemore.iroh.apiimpl.DrawImpl;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4fc;

/**
 * Immediate geometry for our mods (api 3, P1-13a): the native replacement for BufferUploader / RenderSystem raw state.
 * Declared pipelines ({@link PipelineDecl}) are created once; inside a {@link FrameHook} (render thread) a mod writes
 * vertices with {@link #begin} and Iroh records them into the frame at that stage, batched per pipeline, then restores the
 * render state. Cost: a memcpy of the vertices into the frame's vertex ring and one draw per pipeline run; no allocation.
 * Transform: the matrices given to {@link #transform} (default: the world pass view / projection at world stages, the GUI
 * matrices at HUD stages). Outside a frame hook the calls are ignored (one log line).
 */
public final class IrohDraw {
    private IrohDraw() {
    }

    /** Create (or fetch, by equal decl) a pipeline. Any thread; the Vulkan pipeline is built on first use per target. */
    public static PipelineHandle pipeline(PipelineDecl decl) {
        return DrawImpl.pipeline(decl);
    }

    /** Texture for sampler unit {@code slot} (0..7) of the following draws: a texture-manager id. Render thread. */
    public static void texture(int slot, ResourceLocation texture) {
        DrawImpl.texture(slot, texture);
    }

    /** Matrices for the following draws (copied). Null = the stage's default. Render thread. */
    public static void transform(Matrix4fc modelView, Matrix4fc projection) {
        DrawImpl.transform(modelView, projection);
    }

    /** Start a batch of vertices for {@code pipeline}. Render thread, inside a frame hook. */
    public static GeometryWriter begin(PipelineHandle pipeline) {
        return DrawImpl.begin(pipeline);
    }

    /** {@code vertexCount} vertices x {@code instanceCount} instances with no vertex buffer (layout NONE): vertex pulling. */
    public static void drawInstanced(PipelineHandle pipeline, int vertexCount, int instanceCount) {
        DrawImpl.drawInstanced(pipeline, vertexCount, instanceCount);
    }

    /** Records what is batched now (otherwise done at the end of the hook stage). Render thread. */
    public static void flush() {
        DrawImpl.flush();
    }

    /**
     * GPU-driven indirect draws with a GPU count (Vista, Phase 9). Not available yet: capability
     * {@link Capability#MULTI_DRAW_INDIRECT_COUNT} is absent and this throws {@link UnsupportedOperationException}.
     */
    public static void multiDrawIndirectCount(PipelineHandle pipeline, Object commandBuffer, long commandOffset,
                                              Object countBuffer, long countOffset, int maxDraws, int stride) {
        throw new UnsupportedOperationException("IrohDraw.multiDrawIndirectCount: Phase 2/9 (capability MULTI_DRAW_INDIRECT_COUNT)");
    }
}
