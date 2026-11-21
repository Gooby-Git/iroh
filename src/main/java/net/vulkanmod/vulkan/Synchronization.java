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

    /** Frame submit: the semaphores to wait on (consumed by this submit). */
    public synchronized long[] takeWaitSemaphores() {
        long[] s = this.pendingWaits.toLongArray();
        this.pendingWaits.clear();
        this.signalUnconsumed.clear();
        return s;
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

    /** Blocks until the oldest in-flight epoch finished (staging budget exhausted). Returns false if nothing was in flight. */
    public synchronized boolean waitOldest() {
        Epoch e = this.inFlight.pollFirst();
        if (e == null) return false;
        waitAll(e.cbs);
        complete(e);
        return true;
    }

    /** Blocking: waits for every upload command buffer submitted so far (current and in-flight epochs). Counted. */
    public synchronized void waitFences() {
        ObjectArrayList<CommandPool.CommandBuffer> all = new ObjectArrayList<>(this.current.cbs);
        for (Epoch e : this.inFlight) all.addAll(e.cbs);
        if (all.isEmpty()) return;
        waitAll(all);
        for (CommandPool.CommandBuffer cb : all) {
            if (this.signalUnconsumed.contains(cb)) this.resetAfterSubmit.add(cb);
            else cb.reset();
        }
        this.current.cbs.clear();
        this.inFlight.clear();
        var ring = net.vulkanmod.vulkan.memory.buffer.StagingBuffer.ring();
        if (ring != null) ring.completeAll();
    }

    private void waitAll(ObjectArrayList<CommandPool.CommandBuffer> cbs) {
        if (cbs.isEmpty()) return;
        long t0 = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer fences = stack.mallocLong(cbs.size());
            for (CommandPool.CommandBuffer cb : cbs) fences.put(cb.getFence());
            fences.flip();
            vkWaitForFences(Vulkan.getVkDevice(), fences, true, VUtil.UINT64_MAX);
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
