package net.vulkanmod.vulkan.pass;

import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.framebuffer.SceneTarget;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Main pass interface. Was frozen for AsyncParticles' VulkanMod backend until 2026-11-21, when the owner removed
 * AsyncParticles from the Iroh pack; Iroh internals may change it now (our own mods use dev.elemore.iroh.api).
 */
public interface MainPass {

    void begin(VkCommandBuffer commandBuffer, MemoryStack stack);

    void end(VkCommandBuffer commandBuffer);

    void cleanUp();

    void onResize();

    default void mainTargetBindWrite() {}

    default void mainTargetUnbindWrite() {}

    default void rebindMainTarget() {}

    default void bindAsTexture() {}

    default VkGlTexture getColorAttachment() {
        throw new UnsupportedOperationException();
    }

    /** Iroh (R-1): GL texture wrapping the main target's depth image ({@code RenderTarget.getDepthTextureId()}). */
    default VkGlTexture getDepthAttachment() {
        throw new UnsupportedOperationException();
    }

    /** Iroh (R-1): the scene colour + depth images, or null for a main pass without one. */
    default SceneTarget getSceneTarget() {
        return null;
    }

    /** Iroh (R-1): whether the main target's render pass is the one currently recording. */
    default boolean isMainTargetBound() {
        return false;
    }
}
