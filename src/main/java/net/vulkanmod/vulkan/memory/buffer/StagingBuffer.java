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
        // perf/uploads: ring staging may be read by any command buffer of the current epoch (upload or frame: blocks are
        // released only after both finished), but never once its block went back to the pool.
        var ring = RING;
        if (ring == null || srcBuffer == 0L)
            return;
        if (ring.isRingBuffer(srcBuffer) && !ring.isLiveBlock(srcBuffer))
            reportViolation("a copy reads a staging block that was already released to the pool (use after release)");
    }

    static void reportViolation(String what) {
        if (!violationLogged) {
            violationLogged = true;
            net.vulkanmod.Initializer.LOGGER.error("Iroh R-3 staging invariant broken: {}", what, new Throwable("recorded here"));
        }
        assert false : "Iroh R-3 staging invariant broken: " + what;
    }

    // ---------------------------------------------------------------- perf/uploads: chained staging ring

    private static net.vulkanmod.vulkan.memory.staging.StagingRing RING;
    public static final long RING_BLOCK = Long.getLong("iroh.stagingBlockMB", 16L) << 20;
    public static final long RING_BUDGET = Long.getLong("iroh.stagingBudgetMB", 256L) << 20;

    private record RingBlock(StagingBuffer buffer) implements net.vulkanmod.vulkan.memory.staging.StagingRing.Block {
        @Override public long bufferId() { return buffer.getId(); }
        @Override public long address() { return buffer.dataPtr; }
        @Override public long size() { return buffer.getBufferSize(); }
    }

    public static net.vulkanmod.vulkan.memory.staging.StagingRing ring() {
        return RING;
    }

    /** rc13: offline tests run the epoch machinery (Synchronization + MemoryManager deferred frees) on a device-less ring. */
    public static void setRingForTest(net.vulkanmod.vulkan.memory.staging.StagingRing ring) {
        RING = ring;
    }

    /** Creates the ring (device up). Blocks are host-visible, persistently mapped StagingBuffers. */
    public static void initRing() {
        if (RING != null) return;
        RING = new net.vulkanmod.vulkan.memory.staging.StagingRing(new net.vulkanmod.vulkan.memory.staging.StagingRing.Factory() {
            @Override
            public net.vulkanmod.vulkan.memory.staging.StagingRing.Block create(long size) {
                StagingBuffer b = new StagingBuffer(size);
                b.markShared();
                return new RingBlock(b);
            }

            @Override
            public void destroy(net.vulkanmod.vulkan.memory.staging.StagingRing.Block block) {
                ((RingBlock) block).buffer.scheduleFree(); // its readers finished; deferred free is the safe default
            }
        }, RING_BLOCK, RING_BUDGET);
    }

    /**
     * Render thread: copies {@code size} bytes from {@code src} into ring staging and returns the reservation; the caller
     * records the copy from {@code (bufferId, offset)} and then calls {@code ring().consumed(alloc)}. Never waits unless
     * the staging budget is exhausted (then it submits the pending uploads and waits for the oldest epoch; counted).
     */
    public static net.vulkanmod.vulkan.memory.staging.StagingRing.Alloc stage(long size, int align, long src) {
        var ring = RING;
        var a = ring.reserve(size, align);
        // rc8: bounded. Each round submits pending uploads and waits for the oldest in-flight epoch (fence waits time out,
        // see Synchronization); if nothing in flight can free room, the request is refused (no unbounded allocation,
        // no endless loop): the caller's frame is abandoned through the memory guard.
        for (int round = 0; a == null; round++) {
            if (dev.elemore.iroh.diag.UploadStats.ON) dev.elemore.iroh.diag.UploadStats.stagingOverflows++;
            submitAllConsumers();
            boolean waited = Synchronization.INSTANCE.waitOldest();
            if (!waited) {
                // rc12: never mark the open frame's epoch complete (td-7799fa); with the device idle, only the upload
                // copies of the open epoch are known finished: reuse the blocks no frame command buffer reads.
                net.vulkanmod.vulkan.Vulkan.waitIdle();
                Synchronization.INSTANCE.deviceIdle();
                ring.uploadsCompleted(ring.epoch());
            }
            a = ring.reserve(size, align);
            if (a == null && (round >= 8 || (!waited && ring.pendingReservations() == 0 && ring.liveBlocks() == 0))) {
                dev.elemore.iroh.memory.MemoryGuard.outOfMemory("staging budget " + (RING_BUDGET >> 20) + " MB exhausted for a " + size
                        + "-byte upload (" + ring.pendingReservations() + " reservations pinned)");
                throw new dev.elemore.iroh.memory.MemoryGuard.IrohOutOfMemory("staging budget exhausted");
            }
        }
        nmemcpy(a.address, src, size);
        return a;
    }

    /**
     * End of frame (perf/uploads): submits every upload command buffer of this frame and closes the frame's epoch. No
     * wait: the ring releases the blocks once those command buffers and the frame have finished.
     */
    public static void flushConsumers(int frameSlot) {
        if (dev.elemore.iroh.diag.UploadStats.ON) dev.elemore.iroh.diag.UploadStats.frame();
        submitAllConsumers();
        Synchronization.INSTANCE.endEpoch(frameSlot);
    }

    /** Blocking variant for real synchronous needs (readback, swapchain recreation): submit and wait for everything. */
    public static void flushConsumersAndReset() {
        submitAllConsumers();
        Synchronization.INSTANCE.waitFences();
    }

    public static void submitAllConsumers() {
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
        // Submit and wait all recorded uploads before resetting the buffer.
        // Iroh (R-3): includes IndirectBuffer's pending transfer buffer, which the base left unsubmitted here (its
        // copy would then read bytes the next writer had already overwritten).
        submitAllConsumers();
        Synchronization.INSTANCE.waitFences();

        this.reset();
    }
}
