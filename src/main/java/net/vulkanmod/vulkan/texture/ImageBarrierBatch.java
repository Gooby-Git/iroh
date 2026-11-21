package net.vulkanmod.vulkan.texture;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import java.util.Arrays;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Iroh (rc5, td-1cb256): image layout transitions collected and recorded as ONE vkCmdPipelineBarrier per
 * (srcStageMask, dstStageMask) group, from a heap-allocated barrier array reused across flushes. No MemoryStack use, no
 * allocation once the arrays have grown to the largest batch. Not thread-safe: one instance per recording thread.
 * <p>
 * Replaces the per-image barrier (one call and one stack allocation each) of an upload flush: a pack-world join queues
 * thousands of images in one flush, which overflowed LWJGL's 64 KB thread stack and halted the world.
 */
public final class ImageBarrierBatch {
    /** Records one barrier call; the default is vkCmdPipelineBarrier. Replaceable for the offline test. */
    public interface Recorder {
        void record(VkCommandBuffer commandBuffer, int srcStage, int dstStage, VkImageMemoryBarrier.Buffer barriers);
    }

    public static final Recorder VULKAN = (cb, src, dst, barriers) -> vkCmdPipelineBarrier(cb, src, dst, 0, null, null, barriers);

    private int count;
    private long[] images = new long[64];
    private int[] ints = new int[64 * 8]; // aspect, baseLevel, oldLayout, newLayout, srcStage, srcAccess, dstStage, dstAccess
    private int[] order = new int[64];
    private VkImageMemoryBarrier.Buffer structs = VkImageMemoryBarrier.calloc(64);
    private int calls;

    public int size() {
        return count;
    }

    /** vkCmdPipelineBarrier calls made by the last {@link #flush}. */
    public int lastCalls() {
        return calls;
    }

    public void add(long image, int aspect, int baseLevel, int oldLayout, int newLayout,
                    int srcStage, int srcAccess, int dstStage, int dstAccess) {
        if (count == images.length) {
            int n = count * 2;
            images = Arrays.copyOf(images, n);
            ints = Arrays.copyOf(ints, n * 8);
            order = Arrays.copyOf(order, n);
        }
        images[count] = image;
        int o = count * 8;
        ints[o] = aspect; ints[o + 1] = baseLevel; ints[o + 2] = oldLayout; ints[o + 3] = newLayout;
        ints[o + 4] = srcStage; ints[o + 5] = srcAccess; ints[o + 6] = dstStage; ints[o + 7] = dstAccess;
        count++;
    }

    /** Records everything added, grouped by stage pair (insertion order kept inside a group), and clears the batch. */
    public void flush(VkCommandBuffer commandBuffer, Recorder recorder) {
        calls = 0;
        int n = count;
        if (n == 0) return;
        if (structs.capacity() < n) {
            structs.free();
            structs = VkImageMemoryBarrier.calloc(Integer.highestOneBit(n - 1) << 1);
        }
        // group: stable order by (srcStage, dstStage); n is small per group in practice, but keep it O(n log n)
        Integer[] boxed = null;
        boolean uniform = true;
        for (int i = 1; i < n && uniform; i++)
            uniform = ints[i * 8 + 4] == ints[4] && ints[i * 8 + 6] == ints[6];
        if (uniform) {
            for (int i = 0; i < n; i++) order[i] = i;
        } else {
            boxed = new Integer[n];
            for (int i = 0; i < n; i++) boxed[i] = i;
            final int[] f = ints;
            Arrays.sort(boxed, (a, b) -> {
                int c = Integer.compare(f[a * 8 + 4], f[b * 8 + 4]);
                return c != 0 ? c : Integer.compare(f[a * 8 + 6], f[b * 8 + 6]);
            });
            for (int i = 0; i < n; i++) order[i] = boxed[i];
        }
        for (int k = 0; k < n; k++) {
            int i = order[k], o = i * 8;
            VkImageMemoryBarrier b = structs.get(k);
            b.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER).pNext(0)
                    .oldLayout(ints[o + 2]).newLayout(ints[o + 3])
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(images[i]).srcAccessMask(ints[o + 5]).dstAccessMask(ints[o + 7]);
            b.subresourceRange().aspectMask(ints[o]).baseMipLevel(ints[o + 1]).levelCount(VK_REMAINING_MIP_LEVELS)
                    .baseArrayLayer(0).layerCount(VK_REMAINING_ARRAY_LAYERS);
        }
        int start = 0;
        while (start < n) {
            int s0 = ints[order[start] * 8 + 4], d0 = ints[order[start] * 8 + 6];
            int end = start + 1;
            while (end < n && ints[order[end] * 8 + 4] == s0 && ints[order[end] * 8 + 6] == d0) end++;
            VkImageMemoryBarrier.Buffer slice = VkImageMemoryBarrier.create(structs.address() + (long) start * VkImageMemoryBarrier.SIZEOF, end - start);
            recorder.record(commandBuffer, s0, d0, slice);
            calls++;
            start = end;
        }
        count = 0;
    }

    /** Frees the native array (shutdown). */
    public void free() {
        if (structs != null) {
            structs.free();
            structs = null;
        }
    }
}
