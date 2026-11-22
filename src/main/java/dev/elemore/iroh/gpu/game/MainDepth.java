package dev.elemore.iroh.gpu.game;

import dev.elemore.iroh.gpu.VkGpuDevice;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

import static org.lwjgl.vulkan.VK10.*;

/**
 * api 4 {@link IrohTargets#mainDepthCopy}: ends the main pass, copies the scene depth (TRANSFER_SRC) into the mod's
 * DEPTH32F target (GENERAL), restores the depth layout and resumes the main pass (its LOAD render pass). COMPILED,
 * NOT RUN IN GAME YET. Iroh's depth: standard Z, clear 1.0, LEQUAL, infinite far plane (GameRenderer.getDepthFar).
 */
public final class MainDepth {
    private MainDepth() {}

    public static void copy(IrohGpuDevice dev, VkGpuDevice.VkImg target) {
        VulkanImage depth = IrohGpuDevice.mainDepth();
        if (depth == null) throw new IllegalStateException("no main depth");
        if (depth.width != target.width || depth.height != target.height)
            throw new IllegalArgumentException("target " + target.width + "x" + target.height + " != main depth " + depth.width + "x" + depth.height
                    + " (use IrohTargets.createScaled(..., 1))");
        if (depth.format != VK_FORMAT_D32_SFLOAT)
            throw new UnsupportedOperationException("main depth format " + depth.format + " is not D32F (copy needs equal formats)");
        VkCommandBuffer cmd = Renderer.recordingCommandBuffer("mainDepthCopy");
        if (cmd == null) throw new IllegalStateException("mainDepthCopy outside a frame");
        boolean wasInMainPass = Renderer.getInstance().getBoundRenderPass() != null;
        Renderer.getInstance().endRenderPass(cmd);
        try (MemoryStack s = MemoryStack.stackPush()) {
            int old = depth.getCurrentLayout();
            depth.transitionImageLayout(s, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            dev.barrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
            VkImageCopy.Buffer r = VkImageCopy.calloc(1, s);
            r.srcSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.dstSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.extent().width(depth.width).height(depth.height).depth(1);
            vkCmdCopyImage(cmd, depth.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, target.image, VK_IMAGE_LAYOUT_GENERAL, r);
            dev.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_ACCESS_MEMORY_READ_BIT);
            if (old != VK_IMAGE_LAYOUT_UNDEFINED) depth.transitionImageLayout(s, cmd, old);
        }
        if (wasInMainPass) Renderer.getInstance().getMainPass().rebindMainTarget();
    }
}
