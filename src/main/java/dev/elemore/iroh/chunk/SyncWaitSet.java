package dev.elemore.iroh.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The rebuilds one frame decided to wait for (P1-11 item 1). The scheduler adds one latch per important build task
 * (counted down by the dispatcher when the task has run, been cancelled or been dropped); {@link #awaitAll} blocks the
 * render thread until all are done or the budget is spent. Render thread only; the latches are what cross threads.
 */
public final class SyncWaitSet {
    private final List<CountDownLatch> latches = new ArrayList<>();
    private int cap = ChunkDeferPolicy.MAX_SYNC_SECTIONS;

    // counters for the debug line (render thread)
    public long waits, timeouts, overCap;
    public long waitedNs, worstWaitNs;

    public void setCap(int cap) {
        this.cap = cap;
    }

    /** True while another section may still be added this frame. */
    public boolean hasRoom() {
        return this.latches.size() < this.cap;
    }

    /** Called for an important section that did not fit under the cap. */
    public void noteOverCap() {
        this.overCap++;
    }

    public void add(CountDownLatch latch) {
        this.latches.add(latch);
    }

    public int pending() {
        this.latches.removeIf(l -> l.getCount() == 0);
        return this.latches.size();
    }

    public void clear() {
        this.latches.clear();
    }

    /**
     * Waits for every latch, at most {@code budgetNs} in total. Whatever is still running afterwards stays in the
     * background and is forgotten here.
     *
     * @return true when everything finished inside the budget
     */
    public boolean awaitAll(long budgetNs) {
        if (this.pending() == 0)
            return true;
        long start = System.nanoTime();
        long deadline = start + budgetNs;
        boolean all = true;
        try {
            for (CountDownLatch l : this.latches) {
                long left = deadline - System.nanoTime();
                if (left <= 0 && l.getCount() > 0 || !l.await(Math.max(left, 0), TimeUnit.NANOSECONDS)) {
                    all = false;
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            all = false;
        }
        long took = System.nanoTime() - start;
        this.waits++;
        this.waitedNs += took;
        this.worstWaitNs = Math.max(this.worstWaitNs, took);
        if (!all)
            this.timeouts++;
        this.latches.clear();
        return all;
    }

    public String stats() {
        return String.format("defer waits %d, timeouts %d, over cap %d, waited %.1f ms total, worst %.2f ms",
                this.waits, this.timeouts, this.overCap, this.waitedNs / 1e6, this.worstWaitNs / 1e6);
    }
}
