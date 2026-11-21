package net.vulkanmod.vulkan.memory.buffer;

import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.render.chunk.util.Util;
import net.vulkanmod.render.texture.ImageUploadHelper;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.system.libc.LibCString.nmemcpy;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Host-visible upload source.
 * <p>
 * Iroh (R-3, RQ-18) invariant for the SHARED per-frame staging buffers ({@code Vulkan.getStagingBuffer()}): every
 * command that reads one is recorded into an UPLOAD command buffer (UploadManager, ImageUploadHelper, the transfer
 * queue, IndirectBuffer) that is registered with {@link Synchronization}, never into the frame command buffer
 * ({@code Renderer.getCommandBuffer()}). A shared staging buffer is reset only after every such command buffer has
 * been submitted and its fence waited ({@link #flushConsumersAndReset()} from Renderer.waitFences, or the overflow
 * path), so the reset can happen at any point of the frame, including before the frame command buffer is submitted.
 * Frame-buffer copies (td-4a9be3 uploadInFrame) must use a private {@code new StagingBuffer(size)} +
 * {@code scheduleFree()}. {@link #checkConsumer} enforces this at every recording site: an assert under -ea, a
 * once-only error log otherwise.
 */
public class StagingBuffer extends Buffer {
    private static final long DEFAULT_SIZE = 64 * 1024 * 1024;

    private static volatile boolean violationLogged;

    /** True for the per-frame buffers owned by {@code Vulkan}; private staging buffers may be read by any buffer. */
    boolean shared;

    public void markShared() {
        this.shared = true;
    }

    public boolean isShared() {
        return this.shared;
    }

    /**
     * Iroh (R-3): call wherever a copy that reads {@code srcBuffer} is recorded into {@code commandBuffer}.
     * Cheap: one array scan of the frame-count shared buffers, only when the target is the frame command buffer.
     */
    public static void checkConsumer(org.lwjgl.vulkan.VkCommandBuffer commandBuffer, long srcBuffer) {
        if (net.vulkanmod.vulkan.Renderer.getInstance() == null)
            return;
        org.lwjgl.vulkan.VkCommandBuffer frameCb = net.vulkanmod.vulkan.Renderer.getCommandBuffer();
        if (frameCb == null || commandBuffer == null || commandBuffer.address() != frameCb.address())
            return;
        if (!net.vulkanmod.vulkan.Vulkan.isSharedStagingBuffer(srcBuffer))
            return;
        reportViolation("a copy from the shared per-frame staging buffer was recorded into the FRAME command buffer; "
                + "the buffer is reset before that buffer executes (use a private StagingBuffer + scheduleFree)");
    }

    static void reportViolation(String what) {
        if (!violationLogged) {
            violationLogged = true;
            net.vulkanmod.Initializer.LOGGER.error("Iroh R-3 staging invariant broken: {}", what, new Throwable("recorded here"));
        }
        assert false : "Iroh R-3 staging invariant broken: " + what;
    }

    /**
     * Iroh (R-3): submits every upload command buffer that may still read the current shared staging buffer, waits
     * for all of them, then resets it. The only legal way to reset a shared staging buffer.
     */
    public static void flushConsumersAndReset() {
        if (dev.elemore.iroh.diag.UploadStats.ON) { dev.elemore.iroh.diag.UploadStats.frameFenceWaits++; dev.elemore.iroh.diag.UploadStats.frame(); }
        submitAllConsumers();
        Synchronization.INSTANCE.waitFences();
        net.vulkanmod.vulkan.Vulkan.getStagingBuffer().reset();
        ImageUploadHelper.INSTANCE.resetSpill(); // rc6: its readers (upload buffers) were just waited
    }

    private static void submitAllConsumers() {
        if (UploadManager.INSTANCE != null)
            UploadManager.INSTANCE.submitUploads();
        ImageUploadHelper.INSTANCE.submitCommands();
        IndirectBuffer.submitAllPending();
    }

    public StagingBuffer() {
        this(DEFAULT_SIZE);
    }

    public StagingBuffer(long size) {
        super(VK_BUFFER_USAGE_TRANSFER_SRC_BIT, MemoryTypes.HOST_MEM);
        this.createBuffer(size);
    }

    public void copyBuffer(int size, ByteBuffer byteBuffer) {
        this.copyBuffer(size, MemoryUtil.memAddress(byteBuffer));
    }

    public void copyBuffer(int size, long scrPtr) {
        if (size > this.bufferSize) {
            throw new IllegalArgumentException("Upload size is greater than staging buffer size.");
        }

        if (size > this.bufferSize - this.usedBytes) {
            submitUploads();
        }

        nmemcpy(this.dataPtr + this.usedBytes, scrPtr, size);

        this.offset = this.usedBytes;
        this.usedBytes += size;
    }

    /** rc6: whether {@code size} bytes fit after aligning to {@code alignment}, without flushing. */
    public boolean fits(long size, int alignment) {
        long aligned = Util.align(this.usedBytes, Math.max(1, alignment));
        return aligned + size <= this.bufferSize;
    }

    public void align(int alignment) {
        long alignedOffset = Util.align(usedBytes, alignment);

        if (alignedOffset > this.bufferSize) {
            submitUploads();
            alignedOffset = 0;
        }

        this.usedBytes = alignedOffset;
    }

    private void submitUploads() {
        if (dev.elemore.iroh.diag.UploadStats.ON) dev.elemore.iroh.diag.UploadStats.stagingOverflows++;
        // rc6: the spill block's readers are submitted and waited here too
        if (this.shared) {
            submitAllConsumers();
            Synchronization.INSTANCE.waitFences();
            this.reset();
            ImageUploadHelper.INSTANCE.resetSpill();
            return;
        }
        // Submit and wait all recorded uploads before resetting the buffer.
        // Iroh (R-3): includes IndirectBuffer's pending transfer buffer, which the base left unsubmitted here (its
        // copy would then read bytes the next writer had already overwritten).
        submitAllConsumers();
        Synchronization.INSTANCE.waitFences();

        this.reset();
    }
}
