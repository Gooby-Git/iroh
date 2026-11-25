package dev.elemore.iroh.memory;

/**
 * Iroh (rc8, DECISIONS "SYSTEM SAFETY" rule 9): Iroh's own device-memory cap and the policy around it. Pure Java: the
 * Vulkan side ({@link MemoryGuard}) feeds samples, so the state machine is tested headless (memoryBudgetTest).
 * <p>
 * Cap = configured {@code maxDeviceMemoryMiB}, or by default min(50% of the largest device-local heap, 6 GiB); further
 * bounded by what the driver says this process may still use: Iroh's usage + (heap budget - process heap usage) - a
 * 1.5 GiB reserve for the desktop. States: OK; WARN from 85% of the cap (one WARN line, trim trimmable memory);
 * CAPPED at the cap (non-essential allocations refused, essential ones still tried); OUT_OF_MEMORY after a driver
 * OUT_OF_*_MEMORY (sticky until world unload: the frame is abandoned and the world is closed to the title screen).
 */
public final class MemoryBudget {
    public enum State { OK, WARN, CAPPED, OUT_OF_MEMORY }

    public static final long MiB = 1L << 20, GiB = 1L << 30;
    public static final long DESKTOP_RESERVE = 1536 * MiB;
    public static final double WARN_FRACTION = 0.85;

    private long configuredCapMiB; // 0 = auto
    private long largestHeap, driverBudget, driverUsage, irohUsage;
    private long cap;
    private State state = State.OK;
    private boolean warnedThisEpisode;
    private int trims, refusals;

    public MemoryBudget(long configuredCapMiB) {
        this.configuredCapMiB = configuredCapMiB;
    }

    public synchronized void setConfiguredCapMiB(long mib) {
        this.configuredCapMiB = Math.max(0, mib);
        recompute();
    }

    /**
     * The automatic cap for a heap of {@code heapBytes}: min(50% of VRAM, 6 GiB). rc9: the card is shared with the
     * compositor, browser, Steam and (in testing) a second compositor; at the rc8 default (60%, 8 GiB) an external
     * watchdog killed the game at 7.7 GiB of Iroh memory plus the rest of the desktop before the cap was reached.
     */
    public static long autoCap(long heapBytes) {
        return Math.min(heapBytes / 2, 6 * GiB);
    }

    /**
     * One sample (about once a second): largest device-local heap size, the driver's budget and usage for that heap
     * (VK_EXT_memory_budget; pass budget 0 when unavailable), and Iroh's own device-local bytes (VMA statistics).
     * Returns the transition, or null when the state did not change.
     */
    public synchronized State sample(long largestHeap, long driverBudget, long driverUsage, long irohUsage) {
        this.largestHeap = largestHeap;
        this.driverBudget = driverBudget;
        this.driverUsage = driverUsage;
        this.irohUsage = irohUsage;
        State before = this.state;
        recompute();
        return before != this.state ? this.state : null;
    }

    private void recompute() {
        // P2-12 (RQ-RD2a-a): the automatic cap is a share of what the driver lets THIS process use (VK_EXT_memory_budget: 14.22 GiB of a
        // 15.92 GiB heap on RADV), not of the heap size; without the extension the budget is VMA's estimate (80% of the heap)
        long basis = driverBudget > 0 ? Math.min(largestHeap, driverBudget) : largestHeap;
        long c = configuredCapMiB > 0 ? configuredCapMiB * MiB : autoCap(basis);
        if (driverBudget > 0) {
            long headroom = driverBudget - driverUsage - DESKTOP_RESERVE; // what the process may still add safely
            c = Math.min(c, Math.max(0, irohUsage + headroom));
        }
        this.cap = c;
        if (state == State.OUT_OF_MEMORY) return; // sticky until reset()
        if (c <= 0) { state = State.CAPPED; return; }
        if (irohUsage >= c) state = State.CAPPED;
        else if (irohUsage >= (long) (c * WARN_FRACTION)) state = State.WARN;
        else { state = State.OK; warnedThisEpisode = false; }
    }

    /** True once per WARN episode: the caller logs the breakdown and trims. */
    public synchronized boolean takeWarn() {
        if ((state == State.WARN || state == State.CAPPED) && !warnedThisEpisode) {
            warnedThisEpisode = true;
            trims++;
            return true;
        }
        return false;
    }

    /**
     * May a new allocation of {@code bytes} proceed? Essential allocations (the frame cannot work without them) always
     * may, unless the driver already reported out of memory; non-essential ones (optional caches, extra targets, dynamic
     * textures nobody samples, staging pool growth beyond need) are refused at the cap or when they would cross it.
     */
    public synchronized boolean allow(long bytes, boolean essential) {
        if (state == State.OUT_OF_MEMORY) { refusals++; return false; }
        if (essential) return true;
        if (state == State.CAPPED || irohUsage + bytes > cap) { refusals++; return false; }
        return true;
    }

    /** The driver returned VK_ERROR_OUT_OF_DEVICE/HOST_MEMORY. Returns true the first time (caller logs + leaves). */
    public synchronized boolean outOfMemory() {
        boolean first = state != State.OUT_OF_MEMORY;
        state = State.OUT_OF_MEMORY;
        return first;
    }

    /** After the world was closed and memory released: back to normal sampling. */
    public synchronized void reset() {
        state = State.OK;
        warnedThisEpisode = false;
        recompute();
    }

    public synchronized State state() { return state; }

    public synchronized long cap() { return cap; }

    public synchronized long irohUsage() { return irohUsage; }

    public synchronized long driverBudget() { return driverBudget; }

    public synchronized long driverUsage() { return driverUsage; }

    public synchronized int refusals() { return refusals; }

    public synchronized int trims() { return trims; }

    public synchronized String summary() {
        return String.format("iroh %d MiB / cap %d MiB (%s), heap %d MiB, driver usage %d / budget %d MiB, refusals %d",
                irohUsage / MiB, cap / MiB, state, largestHeap / MiB, driverUsage / MiB, driverBudget / MiB, refusals);
    }
}
