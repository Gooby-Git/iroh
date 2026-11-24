package dev.elemore.iroh.rt;

/**
 * Device-memory accounting for the RT core as owner {@value #OWNER} (in game: a share of MemoryGuard's cap, like an
 * api-4 owner; offline: a counter). {@link #reserve} returning false is a normal outcome: the build waits (or the
 * residency radius shrinks), it never forces the driver.
 */
public interface RtMemory {
    String OWNER = "iroh-rt";

    long capBytes();

    long usedBytes();

    boolean reserve(long bytes);

    void release(long bytes);

    /**
     * R1-REV: counts {@code bytes} that already exist (a built BLAS larger than its estimate) without asking: never
     * refused, may pass the cap; later {@link #reserve} calls are refused until enough is released.
     */
    void charge(long bytes);

    /** A plain counter with a fixed cap (offline tests, and the fallback when no guard exists). */
    final class Counter implements RtMemory {
        private final long cap;
        private long used;
        public int reserveCalls;

        public Counter(long cap) {
            this.cap = cap;
        }

        @Override public long capBytes() { return cap; }
        @Override public synchronized long usedBytes() { return used; }

        @Override
        public synchronized boolean reserve(long bytes) {
            reserveCalls++;
            if (used + bytes > cap) return false;
            used += bytes;
            return true;
        }

        @Override
        public synchronized void release(long bytes) {
            used = Math.max(0, used - bytes);
        }

        @Override
        public synchronized void charge(long bytes) {
            used += Math.max(0, bytes);
        }
    }
}
