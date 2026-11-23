package dev.elemore.iroh.glshim;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import static org.lwjgl.vulkan.VK10.*;

/**
 * int-2b-v3 (td-5cbe77): GL says a texture whose storage was just allocated (glTexImage2D with no data) is valid at once,
 * to sample or to attach. A Vulkan image is UNDEFINED until something writes it, so a mod that samples or attaches such a
 * texture before any upload (GPUBooster's render target pair) made the frame command buffer expect
 * SHADER_READ_ONLY / attachment layouts the image never had (validation at vkQueueSubmit; garbage on drivers that care).
 * This records, once at allocation, UNDEFINED -> TRANSFER_DST, a clear to zero (GL's practical result) and
 * TRANSFER_DST -> SHADER_READ_ONLY: the resting layout Iroh's tracker and every draw assume. Pure Vulkan on raw handles
 * so the offline lavapipe repro runs this exact code.
 */
public final class FreshImage {
    private FreshImage() {}

    public static void record(MemoryStack stack, VkCommandBuffer cb, long image, int aspect, boolean depth, int levels) {
        try (MemoryStack s = stack.push()) {
            barrier(s, cb, image, aspect, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, s);
            range.get(0).aspectMask(aspect).baseMipLevel(0).levelCount(levels).baseArrayLayer(0).layerCount(1);
            if (depth) {
                vkCmdClearDepthStencilImage(cb, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VkClearDepthStencilValue.calloc(s).depth(0f).stencil(0), range);
            } else {
                vkCmdClearColorImage(cb, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VkClearColorValue.calloc(s), range);
            }
            barrier(s, cb, image, aspect, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
    }

    private static void barrier(MemoryStack s, VkCommandBuffer cb, long image, int aspect, int oldL, int newL, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s);
        b.sType$Default().oldLayout(oldL).newLayout(newL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image).srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        b.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS).baseArrayLayer(0).layerCount(VK_REMAINING_ARRAY_LAYERS);
        vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, null, b);
    }
}
