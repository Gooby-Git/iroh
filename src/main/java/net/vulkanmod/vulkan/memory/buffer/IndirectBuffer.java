package net.vulkanmod.vulkan.memory.buffer;

import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.TransferQueue;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;

public class IndirectBuffer extends Buffer {
    /** Iroh (R-3): buffers holding an unsubmitted transfer command buffer that reads the shared staging buffer. */
    private static final java.util.Set<IndirectBuffer> PENDING =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    CommandPool.CommandBuffer commandBuffer;

    /** Iroh (R-3): called before a shared staging buffer is reset. Main thread only, like recordCopyCmd. */
    public static void submitAllPending() {
        if (PENDING.isEmpty())
            return;
        for (IndirectBuffer b : PENDING.toArray(new IndirectBuffer[0]))
            b.submitUploads();
    }

    public IndirectBuffer(int size, MemoryType type) {
        super(VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, type);
        this.createBuffer(size);
    }

    public void recordCopyCmd(ByteBuffer byteBuffer) {
        int size = byteBuffer.remaining();

        if (size > this.bufferSize - this.usedBytes) {
            resizeBuffer((long) (this.bufferSize * 1.5f));
            this.usedBytes = 0;
        }

        if (this.type.mappable()) {
            this.type.copyToBuffer(this, byteBuffer, size, 0, this.usedBytes);
        } else {
            // Iroh (R-3): copy into staging first; an overflow there submits every pending consumer, this one's
            // command buffer included, so the command buffer is started afterwards (UploadManager's order).
            var alloc = StagingBuffer.stage(size, 4, org.lwjgl.system.MemoryUtil.memAddress(byteBuffer)); // perf/uploads

            if (commandBuffer == null) {
                commandBuffer = DeviceManager.getTransferQueue().beginCommands();
                PENDING.add(this);
            }

            TransferQueue.uploadBufferCmd(commandBuffer.getHandle(), alloc.bufferId, alloc.offset, this.getId(), this.getUsedBytes(), size);
            StagingBuffer.ring().consumed(alloc);
        }

        offset = usedBytes;
        usedBytes += size;
    }

    public void submitUploads() {
        if (commandBuffer == null)
            return;

        DeviceManager.getTransferQueue().submitCommandsSignalling(commandBuffer); // perf/uploads
        Synchronization.INSTANCE.addCommandBuffer(commandBuffer);
        commandBuffer = null;
        PENDING.remove(this);
    }

}
