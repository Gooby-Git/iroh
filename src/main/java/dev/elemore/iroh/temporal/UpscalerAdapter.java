package dev.elemore.iroh.temporal;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * The ONE seam between Iroh's renderer-side inputs (card P4-1a, this package) and the upscaler implementations
 * (card P4-2a/b: {@code dev.elemore.iroh.upscale.Upscaler}, {@code UpscaleInputs}, {@code JitterSequence},
 * {@code MipBias}). Nothing else in the renderer names an upscaler. When the two branches merge, add one adapter class
 * here that wraps {@code dev.elemore.iroh.upscale.Upscaler} and maps {@link Inputs} onto {@code UpscaleInputs}
 * (field for field: fsr.md A.3 step 6 / A.8), and let {@link #select} return it for {@code iroh.upscale.upscaler=auto};
 * jitter and mip bias then come from P4-2's JitterSequence/MipBias, which compute the same values as
 * {@link TemporalMath} (both are the SDK formulas; temporalMathTest pins the numbers).
 */
public interface UpscalerAdapter {
    String name();

    /** a temporal upscaler: the projection is jittered and motion vectors are required */
    boolean temporal();

    boolean wantsVelocity();

    /**
     * Records the upscale into the frame command buffer (no render pass open). On entry: colour in TRANSFER_SRC or
     * COLOR_ATTACHMENT (the adapter transitions what it needs through VulkanImage), depth in SHADER_READ_ONLY,
     * velocity in SHADER_READ_ONLY (or null), output = the display scene colour, any layout. On exit the output may be
     * in any tracked layout (the main pass re-begins on it).
     */
    void dispatch(VkCommandBuffer cmd, MemoryStack stack, Inputs in);

    /** with the GPU idle */
    void destroy();

    /**
     * Everything an upscaler gets per frame (FSR 3.1 dispatch description minus the shared internal images, which are
     * the FSR adapter's own). Depth: standard Z [0..1], near {@link #cameraNear} 0.05, INFINITE far (FSR flags:
     * DEPTH_INFINITE, not inverted; cameraFar = Float.MAX_VALUE; FSR warns below near 0.075, accepted: vanilla's near).
     * Motion vectors: prevUv - curUv in Iroh's texel axes, unjittered, motionVectorScale = (renderW, renderH).
     * Jitter: render pixels, FSR's sign (TemporalMath class doc). frameTimeMs: milliseconds.
     */
    record Inputs(VulkanImage colour, VulkanImage depth, VulkanImage velocity, VulkanImage output,
                  int renderW, int renderH, int displayW, int displayH,
                  float jitterX, float jitterY, float mvScaleX, float mvScaleY,
                  float frameTimeMs, float cameraNear, float cameraFar, float fovY, boolean reset) {}

    /** {@code iroh.upscale.upscaler}: auto | bilinear. Until P4-2 merges, both are bilinear. */
    static UpscalerAdapter select(String key) {
        return Bilinear.INSTANCE;
    }

    /**
     * The null upscaler: one vkCmdBlitImage, LINEAR when the sizes differ, NEAREST at scale 1.0 (an exact copy, so
     * Native AA without a temporal upscaler is pixel-identical to the rc14 path). Not temporal: no jitter, no velocity.
     */
    final class Bilinear implements UpscalerAdapter {
        public static final Bilinear INSTANCE = new Bilinear();

        @Override public String name() { return "bilinear"; }
        @Override public boolean temporal() { return false; }
        @Override public boolean wantsVelocity() { return false; }

        @Override
        public void dispatch(VkCommandBuffer cmd, MemoryStack stack, Inputs in) {
            TemporalRuntime.blit(cmd, stack, in.colour(), in.output());
        }

        @Override public void destroy() {}
    }
}
