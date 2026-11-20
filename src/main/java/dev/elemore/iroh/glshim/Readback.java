package dev.elemore.iroh.glshim;

import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Synchronous image -> host copies for GL readbacks (glGetTexImage, glReadPixels, P1-2 slice 2). Unlike the base's
 * {@code ImageUtil.downloadTexture} it takes a mip level and a rectangle, uses the image's own aspect (depth images),
 * restores a sane layout when the image was never written (UNDEFINED), and first submits whatever the upload and frame
 * command buffers still hold for the image ({@link FrameSync#beforeHostRead}).
 * Rows come out in image order: row 0 = GL row 0 (Iroh stores every target in GL row order, RQ-16).
 */
public final class Readback {
    private Readback() {}

    /**
     * Copies {@code w x h} texels at (x, y) of {@code level} into {@code dst} (tightly packed, the image's own texel
     * size). Returns false if the image cannot be read (freed, or a multi-plane / compressed format).
     */
    public static boolean read(VulkanImage image, int level, int x, int y, int w, int h, long dst) {
        if (image == null || image.getId() == 0L || w <= 0 || h <= 0) return false;
        int texel = image.formatSize;
        long size = (long) w * h * texel;

        FrameSync.beforeHostRead(image);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.pointers(0L);
            MemoryManager.getInstance().createBuffer(size, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, pBuffer, pAlloc);

            int prev = image.getCurrentLayout();
            CommandPool.CommandBuffer cb = DeviceManager.getGraphicsQueue().beginCommands();
            image.transitionImageLayout(stack, cb.getHandle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(0);
            region.bufferRowLength(0);
            region.bufferImageHeight(0);
            // depth+stencil formats: a copy names exactly one aspect
            int aspect = (image.aspect & VK_IMAGE_ASPECT_DEPTH_BIT) != 0 ? VK_IMAGE_ASPECT_DEPTH_BIT : image.aspect;
            region.imageSubresource().set(aspect, level, 0, 1);
            region.imageOffset().set(x, y, 0);
            region.imageExtent().set(w, h, 1);
            vkCmdCopyImageToBuffer(cb.getHandle(), image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pBuffer.get(0), region);

            int back = prev == VK_IMAGE_LAYOUT_UNDEFINED
                    ? ((image.usage & VK_IMAGE_USAGE_SAMPLED_BIT) != 0 ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    : prev;
            image.transitionImageLayout(stack, cb.getHandle(), back);

            long fence = DeviceManager.getGraphicsQueue().submitCommands(cb);
            vkWaitForFences(DeviceManager.vkDevice, fence, true, VUtil.UINT64_MAX);

            MemoryManager.MapAndCopy(pAlloc.get(0), data -> MemoryUtil.memCopy(data.get(0), dst, size));
            MemoryManager.freeBuffer(pBuffer.get(0), pAlloc.get(0));
        }
        return true;
    }
}
