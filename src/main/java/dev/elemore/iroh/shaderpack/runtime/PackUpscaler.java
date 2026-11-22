package dev.elemore.iroh.shaderpack.runtime;

/**
 * The pack path's side of the upscaler seam (P3-7 D). The executor renders the pack at render resolution up to the
 * plan's cut ({@code iroh.upscale.after}), then calls {@link #dispatch} with backend image handles: the cut's input
 * colour (render size), the pack's main depth (render size), the engine motion vectors (R16G16_SFLOAT, render size,
 * NDC delta current -> previous x 0.5, i.e. uv units: scale by the render size for pixels) and the display-size image
 * the next pass reads. Adapter to card P4-2a's {@code dev.elemore.iroh.upscale.Upscaler} (worktree iroh-wt-p40):
 * resolve each handle to its VkImage/format/size/layout (IrohVkBackend.desc + the image's VulkanImage) into
 * {@code ImageRef}s, fill {@code UpscaleInputs} (motionVectorScale = renderW, renderH; jitter from its JitterSequence,
 * which the pack's gbufferProjection must then carry; near/far/fov from the frame uniforms) and call
 * {@code Upscaler.dispatch}. That adapter lands when P4-2a merges; {@link Bilinear} is the offline/null stand-in.
 */
public interface PackUpscaler {

    void dispatch(GpuBackend gpu, int colour, int depth, int motion, int output, int renderW, int renderH, int displayW, int displayH);

    String name();

    /** stand-in: a linear blit render -> display (no temporal accumulation, no use of depth or motion) */
    final class Bilinear implements PackUpscaler {
        @Override
        public void dispatch(GpuBackend gpu, int colour, int depth, int motion, int output, int rw, int rh, int dw, int dh) {
            gpu.blit(colour, output);
        }

        @Override
        public String name() {
            return "bilinear";
        }
    }
}
