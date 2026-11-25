package net.vulkanmod.vulkan;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;
import java.util.ArrayDeque;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Upload command buffers and their completion.
 * <p>
 * Iroh (perf/uploads): no per-frame CPU wait any more. Upload command buffers are grouped into EPOCHS (one per frame);
 * {@link #endEpoch} closes the frame's epoch after they are submitted, {@link #poll} (frame start, no wait) completes the
 * oldest epochs whose fences have signalled and whose frame command buffer has finished, recycling the command buffers and
 * releasing staging blocks (StagingRing). Transfer-queue command buffers signal their semaphore; the next frame submit
 * waits on it ({@link #takeWaitSemaphores}), so the GPU orders transfer -> frame without the CPU. A command buffer whose
 * semaphore signal has not been waited yet is never reset (a binary semaphore must be waited before it is signalled
 * again). {@link #waitFences} still exists for real synchronous needs (readback, swapchain recreation, staging budget
 * exhausted) and is counted in the stats.
 */
public class Synchronization {
    public static final Synchronization INSTANCE = new Synchronization();
    private static final long FENCE_TIMEOUT_NS = Long.getLong("iroh.fenceTimeoutS", 10L) * 1_000_000_000L;

    private static final class Epoch {
        final ObjectArrayList<CommandPool.CommandBuffer> cbs = new ObjectArrayList<>();
        long id = -1;
        boolean frameDone;
    }

    private Epoch current = new Epoch();
    private final ArrayDeque<Epoch> inFlight = new ArrayDeque<>();
    private final LongArrayList pendingWaits = new LongArrayList();
    private final ReferenceOpenHashSet<CommandPool.CommandBuffer> signalUnconsumed = new ReferenceOpenHashSet<>();
    private final ObjectArrayList<CommandPool.CommandBuffer> resetAfterSubmit = new ObjectArrayList<>();
    private final long[] slotEpoch = new long[16];

    // stats: render-thread blocking fence waits (goal: zero outside loading screens)
    public long statFenceWaits, statFenceWaitNs;

    Synchronization() {
        java.util.Arrays.fill(slotEpoch, -1);
    }

    public synchronized void addCommandBuffer(CommandPool.CommandBuffer commandBuffer) {
        this.current.cbs.add(commandBuffer);
    }

    /** A transfer-queue submission signalled {@code cb.semaphore}: the next frame submit must wait on it. */
    public synchronized void addWaitSemaphore(CommandPool.CommandBuffer cb) {
        this.pendingWaits.add(cb.semaphore);
        this.signalUnconsumed.add(cb);
    }

    /** Frame submit: the binary semaphores to wait on (consumed by this submit). */
    public synchronized long[] takeWaitSemaphores() {
        long[] s = this.pendingWaits.toLongArray();
        this.pendingWaits.clear();
        this.signalUnconsumed.clear();
        return s;
    }

    /**
     * P2-12 (L9): the stages of the frame submit that wait for uploads: every stage that can read what the transfer queue wrote (buffers
     * for vertex/index fetch, indirect commands, uniform/storage reads in any shader stage incl. compute, transfer copies from them).
     * The base waited at ALL_COMMANDS, which also held back the frame's attachment clears / early depth work for no reason. Attachment
     * stages are excluded on purpose: nothing uploaded through the transfer queue is read there. (A branch with acceleration-structure
     * builds reading uploaded buffers adds VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR here.)
     */
    public static final int UPLOAD_WAIT_STAGES = VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT
            | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
            | VK_PIPELINE_STAGE_TRANSFER_BIT;

    private final LongArrayList timelineSems = new LongArrayList();
    private final LongArrayList timelineValues = new LongArrayList();

    /** A signalling upload submit raised {@code semaphore} (a timeline) to {@code value}: the next frame submit waits for it. */
    public synchronized void addTimelineWait(long semaphore, long value) {
        for (int i = 0; i < timelineSems.size(); i++)
            if (timelineSems.getLong(i) == semaphore) {
                if (value > timelineValues.getLong(i)) timelineValues.set(i, value);
                return;
            }
        timelineSems.add(semaphore);
        timelineValues.add(value);
    }

    /** Frame submit: {semaphores, values} to wait on for uploads; binary ones first (value 0), timelines after (consumed by this submit). */
    public synchronized long[][] takeUploadWaits() {
        long[] bin = takeWaitSemaphores();
        int n = bin.length + timelineSems.size();
        long[] sems = new long[n], vals = new long[n];
        System.arraycopy(bin, 0, sems, 0, bin.length);
        for (int i = 0; i < timelineSems.size(); i++) {
            sems[bin.length + i] = timelineSems.getLong(i);
            vals[bin.length + i] = timelineValues.getLong(i);
        }
        timelineSems.clear();
        timelineValues.clear();
        return new long[][]{sems, vals};
    }

    /** After the frame submit that consumed the waits: command buffers held back only for their signal can be recycled. */
    public synchronized void afterFrameSubmit() {
        for (CommandPool.CommandBuffer cb : this.resetAfterSubmit)
            if (!this.signalUnconsumed.contains(cb)) cb.reset();
        this.resetAfterSubmit.removeIf(cb -> !this.signalUnconsumed.contains(cb));
    }

    /** End of frame: the upload command buffers of this frame are submitted. Records the epoch for {@code frameSlot}. */
    public synchronized void endEpoch(int frameSlot) {
        var ring = net.vulkanmod.vulkan.memory.buffer.StagingBuffer.ring();
        long id = ring != null ? ring.endEpoch() : -1;
        this.current.id = id;
        this.inFlight.addLast(this.current);
        this.current = new Epoch();
        if (frameSlot >= 0 && frameSlot < this.slotEpoch.length) this.slotEpoch[frameSlot] = id;
    }

    /**
     * Frame start, after the frame slot's fence was waited (the frame that last used the slot has finished): marks its
     * epoch's frame as done, then completes every finished epoch in order without waiting. Returns the epoch id that
     * MUST be complete before the slot's deferred frees run; {@link #ensureComplete} waits for it if it is not (counted).
     */
    public synchronized void frameSlotDone(int frameSlot) {
        if (frameSlot < 0 || frameSlot >= this.slotEpoch.length) return;
        long id = this.slotEpoch[frameSlot];
        if (id < 0) return;
        for (Epoch e : this.inFlight)
            if (e.id <= id) e.frameDone = true;
        poll();
        // everything submitted before that frame must have finished before its slot's resources are reused
        ensureComplete(id);
    }

    /** Completes finished epochs, oldest first. No wait. */
    public synchronized void poll() {
        VkDevice device = Vulkan.getVkDevice();
        while (!this.inFlight.isEmpty()) {
            Epoch e = this.inFlight.peekFirst();
            if (!e.frameDone) break;
            boolean done = true;
            for (CommandPool.CommandBuffer cb : e.cbs)
                if (vkGetFenceStatus(device, cb.getFence()) != VK_SUCCESS) { done = false; break; }
            if (!done) break;
            complete(this.inFlight.pollFirst());
        }
    }

    private void complete(Epoch e) {
        for (CommandPool.CommandBuffer cb : e.cbs) {
            if (this.signalUnconsumed.contains(cb)) this.resetAfterSubmit.add(cb);
            else cb.reset();
        }
        var ring = net.vulkanmod.vulkan.memory.buffer.StagingBuffer.ring();
        if (ring != null && e.id >= 0) ring.completed(e.id);
    }

    /** Blocks until every epoch up to {@code id} has finished. Expected to return without waiting. */
    public synchronized void ensureComplete(long id) {
        while (!this.inFlight.isEmpty() && this.inFlight.peekFirst().id <= id) {
            Epoch e = this.inFlight.pollFirst();
            waitAll(e.cbs);
            complete(e);
        }
    }

    /**
     * Blocks until the oldest in-flight epoch's UPLOAD command buffers finished (staging budget exhausted). rc12: the
     * epoch is completed (its staging blocks and deferred frees released) only if its FRAME has finished too; otherwise
     * its upload buffers are recycled and the epoch waits for {@link #frameSlotDone}. Returns false if nothing waited.
     */
    public synchronized boolean waitOldest() {
        Epoch e = this.inFlight.peekFirst();
        if (e == null) return false;
        boolean waited = !e.cbs.isEmpty();
        waitAll(e.cbs);
        recycle(e.cbs);
        if (e.frameDone) {
            this.inFlight.pollFirst();
            complete(e);
            return true;
        }
        return waited;
    }

    /**
     * Blocking: waits for every upload command buffer submitted so far (current and in-flight epochs). Counted.
     * rc12 (td-7799fa): this does NOT complete epochs whose frame command buffer may still be executing. The base
     * version called ring.completeAll() here, so a readback (flushCmds), a swapchain recreation or the preInitFrame
     * recursion guard released staging and deferred image/buffer frees that the in-flight FRAME still used:
     * vkDestroyImage / vkDestroyImageView "in use by VkCommandBuffer" at world load (GPUBooster's window-sized
     * targets recreated on resize). Epochs complete only once their frame is known finished (frameSlotDone) or the
     * device is idle ({@link #deviceIdle}).
     */
    public synchronized void waitFences() {
        ObjectArrayList<CommandPool.CommandBuffer> all = new ObjectArrayList<>(this.current.cbs);
        for (Epoch e : this.inFlight) all.addAll(e.cbs);
        if (all.isEmpty()) return;
        waitAll(all);
        recycle(this.current.cbs);
        for (Epoch e : this.inFlight) recycle(e.cbs);
        // complete only the leading epochs whose frames are done
        while (!this.inFlight.isEmpty() && this.inFlight.peekFirst().frameDone)
            complete(this.inFlight.pollFirst());
    }

    /**
     * rc12: the device is idle (vkDeviceWaitIdle returned): every SUBMITTED frame has finished, so every ended epoch is
     * complete. The current (open) epoch is not: its frame command buffer may still be recording.
     */
    public synchronized void deviceIdle() {
        waitFences();
        for (Epoch e : this.inFlight) e.frameDone = true;
        while (!this.inFlight.isEmpty())
            complete(this.inFlight.pollFirst());
    }

    /** Resets finished upload command buffers (keeping those whose semaphore signal is still unwaited) and empties the list. */
    private void recycle(ObjectArrayList<CommandPool.CommandBuffer> cbs) {
        for (CommandPool.CommandBuffer cb : cbs) {
            if (this.signalUnconsumed.contains(cb)) this.resetAfterSubmit.add(cb);
            else cb.reset();
        }
        cbs.clear();
    }

    private void waitAll(ObjectArrayList<CommandPool.CommandBuffer> cbs) {
        if (cbs.isEmpty()) return;
        long t0 = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer fences = stack.mallocLong(cbs.size());
            for (CommandPool.CommandBuffer cb : cbs) fences.put(cb.getFence());
            fences.flip();
            // rc8: never wait forever. A fence that does not signal in 10 s means a hung or lost device: fail cleanly
            // instead of freezing the render thread (and the desktop compositor behind a stuck GPU queue).
            int r = vkWaitForFences(Vulkan.getVkDevice(), fences, true, FENCE_TIMEOUT_NS);
            if (r == VK_TIMEOUT)
                throw new IllegalStateException("Iroh: upload fences did not signal within " + FENCE_TIMEOUT_NS / 1_000_000_000L
                        + " s (GPU hung or device lost); stopping instead of waiting forever");
            if (r != VK_SUCCESS)
                throw new IllegalStateException("Iroh: vkWaitForFences failed: " + r + " (device lost?)");
        }
        this.statFenceWaits++;
        this.statFenceWaitNs += System.nanoTime() - t0;
    }

    public static void waitFence(long fence) {
        VkDevice device = Vulkan.getVkDevice();

        vkWaitForFences(device, fence, true, VUtil.UINT64_MAX);
    }

    public static boolean checkFenceStatus(long fence) {
        VkDevice device = Vulkan.getVkDevice();
        return vkGetFenceStatus(device, fence) == VK_SUCCESS;
    }
}
