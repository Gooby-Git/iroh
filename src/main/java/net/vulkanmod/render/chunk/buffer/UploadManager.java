package net.vulkanmod.render.chunk.buffer;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.StagingBuffer;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.queue.TransferQueue;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class UploadManager {
    public static UploadManager INSTANCE;

    public static void createInstance() {
        INSTANCE = new UploadManager();
    }

    Queue queue = DeviceManager.getTransferQueue();
    CommandPool.CommandBuffer commandBuffer;

    LongOpenHashSet dstBuffers = new LongOpenHashSet();

    public void submitUploads() {
        if (this.commandBuffer == null)
            return;

        this.queue.submitCommandsSignalling(this.commandBuffer); // perf/uploads: the frame waits on the GPU

        Synchronization.INSTANCE.addCommandBuffer(this.commandBuffer);

        this.commandBuffer = null;
        this.dstBuffers.clear();
    }

    public void recordUpload(Buffer buffer, long dstOffset, long bufferSize, ByteBuffer src) {
        // perf/uploads: mesh data a worker already wrote into ring staging is copied from there (no render-thread memcpy);
        // anything else is staged now. Never a mid-frame fence wait (unless the staging budget is exhausted).
        long t0 = dev.elemore.iroh.diag.UploadStats.ON ? System.nanoTime() : 0;
        var ring = StagingBuffer.ring();
        long addr = org.lwjgl.system.MemoryUtil.memAddress(src);
        var staged = ring.tracked(addr);
        if (staged != null && staged.size < bufferSize)
            staged = null;
        var alloc = staged != null ? staged : StagingBuffer.stage(bufferSize, 4, addr);
        if (dev.elemore.iroh.diag.UploadStats.ON) {
            dev.elemore.iroh.diag.UploadStats.chunkBytes += bufferSize;
            if (staged != null) dev.elemore.iroh.diag.UploadStats.workerStagedBytes += bufferSize;
        }

        beginCommands();
        VkCommandBuffer commandBuffer = this.commandBuffer.getHandle();

        if (!this.dstBuffers.add(buffer.getId())) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
                barrier.sType$Default();
                barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);

                vkCmdPipelineBarrier(commandBuffer,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0,
                        barrier,
                        null,
                        null);
            }

            this.dstBuffers.clear();
        }

        TransferQueue.uploadBufferCmd(commandBuffer, alloc.bufferId, alloc.offset, buffer.getId(), dstOffset, bufferSize);
        ring.consumed(alloc);
        if (t0 != 0) dev.elemore.iroh.diag.UploadStats.chunkUploadNs += System.nanoTime() - t0;
    }

    public void copyBuffer(Buffer src, Buffer dst) {
        copyBuffer(src, 0, dst, 0, src.getBufferSize());
    }

    public void copyBuffer(Buffer src, long srcOffset, Buffer dst, long dstOffset, long size) {
        beginCommands();

        VkCommandBuffer commandBuffer = this.commandBuffer.getHandle();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.sType$Default();

            VkBufferMemoryBarrier.Buffer bufferMemoryBarriers = VkBufferMemoryBarrier.calloc(1, stack);
            VkBufferMemoryBarrier bufferMemoryBarrier = bufferMemoryBarriers.get(0);
            bufferMemoryBarrier.sType$Default();
            // calloc leaves both family indices at 0; the buffers are CONCURRENT (MemoryManager.createBuffer), where
            // barriers must name VK_QUEUE_FAMILY_IGNORED (P1-8b, td-ed94f3).
            bufferMemoryBarrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            bufferMemoryBarrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            bufferMemoryBarrier.buffer(src.getId());
            bufferMemoryBarrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            bufferMemoryBarrier.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            bufferMemoryBarrier.size(VK_WHOLE_SIZE);

            vkCmdPipelineBarrier(commandBuffer,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    barrier,
                    bufferMemoryBarriers,
                    null);
        }

        this.dstBuffers.add(dst.getId());

        TransferQueue.uploadBufferCmd(commandBuffer, src.getId(), srcOffset, dst.getId(), dstOffset, size);
    }

    public void syncUploads() {
        submitUploads();

        Synchronization.INSTANCE.waitFences();
    }

    private void beginCommands() {
        if (this.commandBuffer == null)
            this.commandBuffer = queue.beginCommands();
    }

}
