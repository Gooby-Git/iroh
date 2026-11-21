package net.vulkanmod.vulkan.memory.staging;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Iroh (perf/uploads): chained staging blocks instead of one per-frame staging buffer that, when full, forced a submit
 * and a FULL fence wait on the render thread.
 * <p>
 * Model. Memory is handed out from a current block; a full block is retired and the next one comes from a pool (or is
 * created while the total stays under the budget). Every allocation is a reservation ({@code pending++}) that is
 * {@link #consumed consumed} when the copy reading it is RECORDED (or {@link #cancel cancelled}); consumption stamps the
 * block with the current epoch. An epoch is one frame's worth of consumers: {@link #endEpoch} is called once the frame's
 * upload command buffers are submitted, and {@link #completed} when every consumer of that epoch (upload command buffers
 * AND the frame command buffer) is known to have finished (fences polled, no wait). A block returns to the pool when it
 * is retired, has no pending reservation, and its last consumer epoch is completed. So staging is never reused while
 * the GPU may read it, and nothing waits mid-frame unless the budget is exhausted ({@link #reserve} returns null; the
 * render thread then waits for the oldest epoch, counted in the stats).
 * <p>
 * Thread safety: all methods are synchronized; workers reserve and memcpy into their reservation concurrently (the copy
 * itself happens outside the lock), the render thread consumes and drives epochs. Pure Java (block creation through a
 * {@link Factory}) so the bookkeeping is tested headless (stagingRingTest).
 */
public final class StagingRing {
    public interface Block {
        long bufferId();

        long address();

        long size();
    }

    public interface Factory {
        Block create(long size);

        void destroy(Block block);
    }

    /** One reservation. {@code address} is mapped host memory the caller writes; the copy reads {@code bufferId + offset}. */
    public static final class Alloc {
        public final long bufferId, offset, address, size;
        final Slot slot;
        volatile boolean done;

        Alloc(Slot slot, long offset, long size) {
            this.slot = slot;
            this.bufferId = slot.block.bufferId();
            this.offset = offset;
            this.address = slot.block.address() + offset;
            this.size = size;
        }
    }

    static final class Slot {
        final Block block;
        final boolean dedicated;
        long used;
        int pending;
        long lastEpoch = -1;
        boolean retired;

        Slot(Block block, boolean dedicated) {
            this.block = block;
            this.dedicated = dedicated;
        }
    }

    private final Factory factory;
    private final long blockSize, budget;
    private final ArrayDeque<Slot> pool = new ArrayDeque<>();
    private final ArrayList<Slot> live = new ArrayList<>();
    private Slot current;
    private long totalBytes;
    private long epoch, completedEpoch = -1;
    /** Reservations handed out as ByteBuffers (worker-staged meshes), by address. */
    private final ConcurrentHashMap<Long, Alloc> byAddress = new ConcurrentHashMap<>();

    // stats (read by UploadStats; approximate across threads)
    public long statBudgetMisses, statBlocksCreated, statBytesReserved;

    public StagingRing(Factory factory, long blockSize, long budget) {
        this.factory = factory;
        this.blockSize = blockSize;
        this.budget = Math.max(budget, blockSize);
    }

    /**
     * A reservation of {@code size} bytes aligned to {@code align}, or null when it would exceed the budget (the caller
     * falls back: workers keep heap memory, the render thread waits for an epoch and retries).
     */
    public synchronized Alloc reserve(long size, int align) {
        if (size <= 0) size = 1;
        if (align < 1) align = 1;
        if (size > blockSize) { // dedicated block, released like any other
            if (totalBytes + size > budget && !reclaimFor(size)) { statBudgetMisses++; return null; }
            Slot s = newSlot(size, true);
            s.retired = true;
            s.used = size;
            s.pending++;
            statBytesReserved += size;
            return new Alloc(s, 0, size);
        }
        if (current != null) {
            long off = (current.used + align - 1) / align * align;
            if (off + size <= current.block.size()) {
                current.used = off + size;
                current.pending++;
                statBytesReserved += size;
                return new Alloc(current, off, size);
            }
            current.retired = true;
            current = null;
        }
        Slot s = pool.pollFirst();
        if (s == null) {
            if (totalBytes + blockSize > budget && !reclaimFor(blockSize)) { statBudgetMisses++; return null; }
            s = newSlot(blockSize, false);
        }
        s.retired = false;
        s.used = size;
        s.pending = 1;
        s.lastEpoch = -1;
        live.add(s);
        current = s;
        statBytesReserved += size;
        return new Alloc(s, 0, size);
    }

    private Slot newSlot(long size, boolean dedicated) {
        Slot s = new Slot(factory.create(size), dedicated);
        totalBytes += size;
        statBlocksCreated++;
        if (dedicated) live.add(s);
        return s;
    }

    /** Frees pooled blocks to make room under the budget. */
    private boolean reclaimFor(long size) {
        while (totalBytes + size > budget && !pool.isEmpty()) {
            Slot s = pool.pollLast();
            totalBytes -= s.block.size();
            factory.destroy(s.block);
        }
        return totalBytes + size <= budget;
    }

    /** The copy reading {@code a} was recorded: the block is now in use by the current epoch. */
    public synchronized void consumed(Alloc a) {
        if (a.done) throw new IllegalStateException("staging reservation consumed twice");
        a.done = true;
        a.slot.pending--;
        a.slot.lastEpoch = epoch;
        byAddress.remove(a.address);
    }

    /** The reservation will never be read (task cancelled, result dropped). */
    public synchronized void cancel(Alloc a) {
        if (a.done) return;
        a.done = true;
        a.slot.pending--;
        byAddress.remove(a.address);
        recycle();
    }

    /** All consumers of this epoch are submitted; returns the epoch id to report to {@link #completed} later. */
    public synchronized long endEpoch() {
        if (current != null && current.used > 0) { // next frame starts a fresh block, so blocks free per epoch
            current.retired = true;
            current = null;
        }
        return epoch++;
    }

    public synchronized long epoch() {
        return epoch;
    }

    /** Highest epoch whose consumers (upload command buffers and frame) are known finished; -1 before the first. */
    public synchronized long completedEpoch() {
        return completedEpoch;
    }

    /** Every consumer of epochs up to {@code e} has finished on the GPU. */
    public synchronized void completed(long e) {
        if (e > completedEpoch) completedEpoch = e;
        recycle();
    }

    /** Everything recorded so far has finished (after a full device/fence wait). */
    public synchronized void completeAll() {
        completed(epoch);
    }

    private void recycle() {
        for (int i = live.size() - 1; i >= 0; i--) {
            Slot s = live.get(i);
            if (s.retired && s.pending == 0 && s.lastEpoch <= completedEpoch) {
                live.remove(i);
                if (s.dedicated) {
                    totalBytes -= s.block.size();
                    factory.destroy(s.block);
                } else {
                    s.used = 0;
                    s.lastEpoch = -1;
                    pool.addFirst(s);
                }
            }
        }
    }

    /** Registers a reservation handed out as a raw address (worker-staged data); see {@link #byAddress}. */
    public void track(Alloc a) {
        byAddress.put(a.address, a);
    }

    /** The tracked reservation starting at {@code address}, or null if that memory is not ring staging. */
    public Alloc tracked(long address) {
        return byAddress.get(address);
    }

    /** R-3 checker: true if {@code bufferId} is a ring block that is live (not pooled or destroyed). */
    public synchronized boolean isLiveBlock(long bufferId) {
        for (Slot s : live) if (s.block.bufferId() == bufferId) return true;
        return current != null && current.block.bufferId() == bufferId;
    }

    /** True if {@code bufferId} belongs to the ring at all (live or pooled). */
    public synchronized boolean isRingBuffer(long bufferId) {
        if (isLiveBlock(bufferId)) return true;
        for (Slot s : pool) if (s.block.bufferId() == bufferId) return true;
        return false;
    }

    public synchronized int liveBlocks() { return live.size(); }

    public synchronized int pooledBlocks() { return pool.size(); }

    public synchronized long totalBytes() { return totalBytes; }

    public synchronized int pendingReservations() {
        int n = 0;
        for (Slot s : live) n += s.pending;
        return n;
    }

    /** rc8: destroys every pooled (idle) block; returns the bytes released. Memory guard trim. */
    public synchronized long trimPool() {
        long freed = 0;
        while (!pool.isEmpty()) {
            Slot s = pool.pollLast();
            totalBytes -= s.block.size();
            freed += s.block.size();
            factory.destroy(s.block);
        }
        return freed;
    }

    /** Destroys every block (shutdown, after the device is idle). */
    public synchronized void destroyAll() {
        for (Slot s : live) factory.destroy(s.block);
        for (Slot s : pool) factory.destroy(s.block);
        live.clear();
        pool.clear();
        current = null;
        totalBytes = 0;
        byAddress.clear();
    }
}
