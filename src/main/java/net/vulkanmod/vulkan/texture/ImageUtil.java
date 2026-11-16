package net.vulkanmod.vulkan.texture;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;

public abstract class ImageUtil {

    public static void copyBufferToImageCmd(MemoryStack stack, VkCommandBuffer commandBuffer, long buffer, long image,
                                            int mipLevel, int width, int height, int xOffset, int yOffset,
                                            int bufferOffset, int bufferRowLenght, int bufferImageHeight) {
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.bufferOffset(bufferOffset);
        region.bufferRowLength(bufferRowLenght);
        region.bufferImageHeight(bufferImageHeight);
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        region.imageSubresource().mipLevel(mipLevel);
        region.imageSubresource().baseArrayLayer(0);
        region.imageSubresource().layerCount(1);
        region.imageOffset().set(xOffset, yOffset, 0);
        region.imageExtent(VkExtent3D.calloc(stack).set(width, height, 1));

        vkCmdCopyBufferToImage(commandBuffer, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
    }

    public static void downloadTexture(VulkanImage image, long ptr) {
        try (MemoryStack stack = stackPush()) {
            int prevLayout = image.getCurrentLayout();
            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
            image.transitionImageLayout(stack, commandBuffer.getHandle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            long imageSize = (long) image.width * image.height * image.formatSize;

            LongBuffer pStagingBuffer = stack.mallocLong(1);
            PointerBuffer pStagingAllocation = stack.pointers(0L);
            MemoryManager.getInstance().createBuffer(imageSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                                     VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                                                     pStagingBuffer, pStagingAllocation);

            copyImageToBufferCmd(stack, commandBuffer.getHandle(), pStagingBuffer.get(0), image.getId(), 0, image.width,
                              image.height, 0, 0, 0, 0, 0);
            image.transitionImageLayout(stack, commandBuffer.getHandle(), prevLayout);

            long fence = DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);
            vkWaitForFences(DeviceManager.vkDevice, fence, true, VUtil.UINT64_MAX);

            MemoryManager.MapAndCopy(pStagingAllocation.get(0),
                                     (data) -> VUtil.memcpy(data.getByteBuffer(0, (int) imageSize), ptr));

            MemoryManager.freeBuffer(pStagingBuffer.get(0), pStagingAllocation.get(0));
        }
    }

    public static void copyImageToBuffer(VulkanImage image, Buffer buffer, int mipLevel,
                                         int width, int height, int xOffset, int yOffset,
                                         int bufferOffset, int bufferRowLength, int bufferImageHeight) {
        try (MemoryStack stack = stackPush()) {
            int prevLayout = image.getCurrentLayout();
            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
            image.transitionImageLayout(stack, commandBuffer.getHandle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            copyImageToBufferCmd(stack, commandBuffer.getHandle(), buffer.getId(), image.getId(), mipLevel, width,
                                 height, xOffset, yOffset, bufferOffset, bufferRowLength, bufferImageHeight);
            image.transitionImageLayout(stack, commandBuffer.getHandle(), prevLayout);

            long fence = DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);
            vkWaitForFences(DeviceManager.vkDevice, fence, true, VUtil.UINT64_MAX);
        }
    }

    public static void copyImageToBufferCmd(MemoryStack stack, VkCommandBuffer commandBuffer, long buffer, long image,
                                            int mipLevel, int width, int height, int xOffset, int yOffset, int bufferOffset,
                                            int bufferRowLength, int bufferImageHeight) {
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.bufferOffset(bufferOffset);
        region.bufferRowLength(bufferRowLength);
        region.bufferImageHeight(bufferImageHeight);
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        region.imageSubresource().mipLevel(mipLevel);
        region.imageSubresource().baseArrayLayer(0);
        region.imageSubresource().layerCount(1);
        region.imageOffset().set(xOffset, yOffset, 0);
        region.imageExtent().set(width, height, 1);

        vkCmdCopyImageToBuffer(commandBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);
    }

    /**
     * Legacy entry (0.5.5 signature): full-image colour blit from the main target into {@code dstImage}.
     * Iroh (R-1): the source used to be hard-coded to the current swapchain image; it is now the main scene
     * target. Prefer {@link #blitFramebuffer(VulkanImage, VulkanImage, VulkanImage, VulkanImage, int, int, int, int, int, int, int, int, int, int)}.
     */
    public static void blitFramebuffer(VulkanImage dstImage, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1) {
        var sceneTarget = Renderer.getInstance().getMainPass().getSceneTarget();
        if (sceneTarget == null)
            return;

        VulkanImage src = sceneTarget.getColorAttachment();
        blitFramebuffer(src, null, dstImage, null,
                        0, 0, src.width, src.height, 0, 0, dstImage.width, dstImage.height,
                        GL_COLOR_BUFFER_BIT, GL_LINEAR);
        Renderer.getInstance().getMainPass().rebindMainTarget();
    }

    private static boolean warnedDepthBlit = false;

    /**
     * Iroh (R-1): glBlitFramebuffer on Vulkan images, recorded in the frame command buffer (render pass ended
     * first; the caller re-binds what was bound). Rects are GL window coordinates (origin bottom left); both
     * images are assumed to use the same row order, so Y is mirrored on both sides and full-image copies are
     * exact. Colour: vkCmdBlitImage (scales, filter from GL). Depth: vkCmdCopyImage when formats match and the
     * rects have the same size, else vkCmdBlitImage NEAREST when the format supports it; otherwise skipped with
     * one warning. Stencil is ignored (no stencil attachments exist). Layouts are restored afterwards, except a
     * colour destination, which ends in SHADER_READ_ONLY as in the 0.5.5 base.
     */
    public static void blitFramebuffer(VulkanImage srcColor, VulkanImage srcDepth, VulkanImage dstColor, VulkanImage dstDepth,
                                       int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1,
                                       int mask, int filter) {
        if (!Renderer.isRecording())
            return;

        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

            Renderer.getInstance().endRenderPass(commandBuffer);

            if ((mask & GL_COLOR_BUFFER_BIT) != 0 && srcColor != null && dstColor != null && srcColor != dstColor) {
                int srcPrev = srcColor.getCurrentLayout();

                srcColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                dstColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                setBlitRegion(blit, srcColor, dstColor, VK_IMAGE_ASPECT_COLOR_BIT,
                              srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1);

                vkCmdBlitImage(commandBuffer, srcColor.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               dstColor.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit,
                               filter == GL_LINEAR ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);

                dstColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                restoreLayout(stack, commandBuffer, srcColor, srcPrev);
            }

            if ((mask & GL_DEPTH_BUFFER_BIT) != 0 && srcDepth != null && dstDepth != null && srcDepth != dstDepth) {
                blitDepth(stack, commandBuffer, srcDepth, dstDepth, srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1);
            }
        }
    }

    private static void blitDepth(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage src, VulkanImage dst,
                                  int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1) {
        boolean usageOk = (src.usage & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) != 0 && (dst.usage & VK_IMAGE_USAGE_TRANSFER_DST_BIT) != 0;
        boolean sameSize = Math.abs(srcX1 - srcX0) == Math.abs(dstX1 - dstX0) && Math.abs(srcY1 - srcY0) == Math.abs(dstY1 - dstY0)
                && (srcX1 - srcX0) * (dstX1 - dstX0) > 0 && (srcY1 - srcY0) * (dstY1 - dstY0) > 0;
        boolean copy = usageOk && src.format == dst.format && sameSize;
        boolean blit = usageOk && src.format == dst.format && !copy && supportsDepthBlit(src.format);

        if (!copy && !blit) {
            if (!warnedDepthBlit) {
                net.vulkanmod.Initializer.LOGGER.warn("Iroh: depth blit skipped (src fmt {} {}x{} usage 0x{}, dst fmt {} {}x{} usage 0x{}); further ones are silent",
                        src.format, src.width, src.height, Integer.toHexString(src.usage),
                        dst.format, dst.width, dst.height, Integer.toHexString(dst.usage));
                warnedDepthBlit = true;
            }
            return;
        }

        int srcPrev = src.getCurrentLayout();
        int dstPrev = dst.getCurrentLayout();

        src.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        dst.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        if (copy) {
            int sx = clamp(Math.min(srcX0, srcX1), 0, src.width), sy = clamp(src.height - Math.max(srcY0, srcY1), 0, src.height);
            int dx = clamp(Math.min(dstX0, dstX1), 0, dst.width), dy = clamp(dst.height - Math.max(dstY0, dstY1), 0, dst.height);
            int w = Math.min(Math.abs(srcX1 - srcX0), Math.min(src.width - sx, dst.width - dx));
            int h = Math.min(Math.abs(srcY1 - srcY0), Math.min(src.height - sy, dst.height - dy));

            if (w > 0 && h > 0) {
                VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
                region.srcSubresource().set(VK_IMAGE_ASPECT_DEPTH_BIT, 0, 0, 1);
                region.dstSubresource().set(VK_IMAGE_ASPECT_DEPTH_BIT, 0, 0, 1);
                region.srcOffset().set(sx, sy, 0);
                region.dstOffset().set(dx, dy, 0);
                region.extent().set(w, h, 1);

                vkCmdCopyImage(commandBuffer, src.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               dst.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            }
        } else {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            setBlitRegion(region, src, dst, VK_IMAGE_ASPECT_DEPTH_BIT,
                          srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1);
            vkCmdBlitImage(commandBuffer, src.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           dst.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK_FILTER_NEAREST);
        }

        restoreLayout(stack, commandBuffer, src, srcPrev);
        restoreLayout(stack, commandBuffer, dst, dstPrev);
    }

    private static void setBlitRegion(VkImageBlit.Buffer blit, VulkanImage src, VulkanImage dst, int aspect,
                                      int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1) {
        blit.srcSubresource().set(aspect, 0, 0, 1);
        blit.dstSubresource().set(aspect, 0, 0, 1);
        // GL origin is bottom left: mirror Y on both sides (same row order on both images).
        blit.srcOffsets(0).set(clamp(srcX0, 0, src.width), clamp(src.height - srcY0, 0, src.height), 0);
        blit.srcOffsets(1).set(clamp(srcX1, 0, src.width), clamp(src.height - srcY1, 0, src.height), 1);
        blit.dstOffsets(0).set(clamp(dstX0, 0, dst.width), clamp(dst.height - dstY0, 0, dst.height), 0);
        blit.dstOffsets(1).set(clamp(dstX1, 0, dst.width), clamp(dst.height - dstY1, 0, dst.height), 1);
    }

    private static void restoreLayout(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image, int layout) {
        if (layout == VK_IMAGE_LAYOUT_UNDEFINED || layout == image.getCurrentLayout())
            return;
        image.transitionImageLayout(stack, commandBuffer, layout);
    }

    private static boolean supportsDepthBlit(int format) {
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, format, properties);
            int need = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
            return (properties.optimalTilingFeatures() & need) == need;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static void generateMipmaps(VulkanImage image) {
        try (MemoryStack stack = stackPush()) {

            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();

            image.transitionImageLayout(stack, commandBuffer.getHandle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            int level, prevLevel;

            for (level = 1; level < image.mipLevels; level++) {
                prevLevel = level - 1;

                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
                barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                barrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                barrier.image(image.getId());

                barrier.subresourceRange().baseMipLevel(prevLevel);
                barrier.subresourceRange().levelCount(1);
                barrier.subresourceRange().baseArrayLayer(0);
                barrier.subresourceRange().layerCount(VK_REMAINING_ARRAY_LAYERS);

                barrier.subresourceRange().aspectMask(image.aspect);

                barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);

                vkCmdPipelineBarrier(commandBuffer.getHandle(), VK_PIPELINE_STAGE_TRANSFER_BIT,
                                     VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

                prevLevel = level - 1;

                VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
                blit.srcOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
                blit.srcOffsets(1, VkOffset3D.calloc(stack).set(image.width >> prevLevel, image.height >> prevLevel, 1));
                blit.srcSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(prevLevel)
                    .baseArrayLayer(0)
                    .layerCount(1);

                blit.dstOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
                blit.dstOffsets(1, VkOffset3D.calloc(stack).set(image.width >> level, image.height >> level, 1));
                blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).baseArrayLayer(0)
                    .layerCount(1);

                vkCmdBlitImage(commandBuffer.getHandle(), image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR);

            }

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            barrier.newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.image(image.getId());

            barrier.subresourceRange().baseMipLevel(0);
            barrier.subresourceRange().levelCount(image.mipLevels - 1);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(VK_REMAINING_ARRAY_LAYERS);

            barrier.subresourceRange().aspectMask(image.aspect);

            barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            vkCmdPipelineBarrier(commandBuffer.getHandle(),
                                 VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                                 0,
                                 null, null,
                                 barrier);

            barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            barrier.subresourceRange().baseMipLevel(image.mipLevels - 1);
            barrier.subresourceRange().levelCount(1);

            vkCmdPipelineBarrier(commandBuffer.getHandle(),
                                 VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                                 0,
                                 null, null,
                                 barrier);

            image.setCurrentLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            long fence = DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);

            vkWaitForFences(DeviceManager.vkDevice, fence, true, VUtil.UINT64_MAX);
        }
    }
}
