package dev.elemore.iroh.glshim;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Card P2-12 (RQ-P213-a): the stage masks of texture uploads, as plain constants and raw-handle helpers so the offline lavapipe
 * repro ({@code imageSyncRepro}) runs this exact code.
 * <ul>
 * <li>{@link #SAMPLED_STAGES}: a texture made readable by an upload is sampled in the VERTEX stage as well as the FRAGMENT stage
 *     (terrain/entity/particle/cutout vertex shaders sample the lightmap, Sampler2). The base made the TRANSFER write visible
 *     to FRAGMENT only; the frame's ALL_COMMANDS semaphore wait hid it (upstream VulkanMod 3fead4d / 94d137a).</li>
 * <li>{@link #transferWriteToTransferWrite}: a second vkCmdCopyBufferToImage into an image already copied into by the same command
 *     buffer is a write-after-write; copies of one image are ordered by this barrier (94d137a).</li>
 * </ul>
 */
public final class ImageSync {
    private ImageSync() {}

    /** destination stages for "an uploaded image may now be sampled": every graphics shader stage Iroh's pipelines have */
    public static final int SAMPLED_STAGES = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    /** counter for tests: TRANSFER -> TRANSFER barriers recorded through {@link #transferWriteToTransferWrite} */
    public static long transferBarriers;

    /** Global TRANSFER write -> TRANSFER write memory barrier (the earlier copy's texels are available before the next copy writes). */
    public static void transferWriteToTransferWrite(VkCommandBuffer cb) {
        transferBarriers++;
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, s).sType$Default()
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, b, null, null);
        }
    }
}
