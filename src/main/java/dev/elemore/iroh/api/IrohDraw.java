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

    // ---- api 4 (card V-M1): GPU-driven draws into mod targets

    /**
     * A vertex-pulling pipeline for {@link #multiDrawIndirectCount}: GLSL 450, no vertex inputs, {@code set = 0} storage
     * buffers at bindings 0..n-1 then sampled textures (combined image samplers, nearest) at n.., one push-constant
     * block (vertex + fragment) of up to 128 bytes; drawn into targets of the given formats. {@code depthCompare} is a
     * VkCompareOp (Vista: GREATER for its reversed-Z target).
     */
    public static IndirectPipelineHandle indirectPipeline(String owner, String name, String vertexGlsl450, String fragmentGlsl450, int storageBuffers,
                                                          int sampledTextures, int pushBytes, IrohTargets.Format color, IrohTargets.Format depth,
                                                          int depthCompare, boolean depthWrite, boolean blend) {
        return dev.elemore.iroh.gpu.GpuCore.get().indirectPipeline(owner, name, vertexGlsl450, fragmentGlsl450, storageBuffers, sampledTextures,
                pushBytes, color, depth, depthCompare, depthWrite, blend);
    }

    /**
     * Before {@link IrohTargets#beginRendering}, once per indirect-count draw of the coming pass (same arguments). Where
     * the device lacks drawIndirectCount, Iroh's own compute zeroes instanceCount of the commands at or after the GPU
     * count, so the draw is the same without a CPU readback.
     */
    public static void prepareIndirectCount(GpuBuffer commands, long commandOffset, GpuBuffer count, long countOffset, int maxDraws, int stride) {
        dev.elemore.iroh.gpu.GpuCore.get().prepareIndirectCount(commands, commandOffset, count, countOffset, maxDraws, stride);
    }

    /**
     * vkCmdDrawIndexedIndirectCount inside a {@link IrohTargets#beginRendering} pass: {@code commands} holds
     * VkDrawIndexedIndirectCommand records ({@code stride} >= 20), {@code count} the number to draw (uint at
     * {@code countOffset}), capped at {@code maxDraws}. Every buffer read must have been declared at beginRendering.
     * Capability {@link Capability#MULTI_DRAW_INDIRECT_COUNT} (with a fallback, see {@link #prepareIndirectCount}).
     */
    public static void multiDrawIndirectCount(IndirectPipelineHandle pipeline, IrohCompute.Binding[] storage, RenderTargetHandle[] textures,
                                              java.nio.ByteBuffer push, GpuBuffer index, boolean index32, GpuBuffer commands, long commandOffset,
                                              GpuBuffer count, long countOffset, int maxDraws, int stride) {
        dev.elemore.iroh.gpu.GpuCore.get().multiDrawIndirectCount(pipeline, storage, textures, push, index, index32, commands, commandOffset,
                count, countOffset, maxDraws, stride);
    }
}
