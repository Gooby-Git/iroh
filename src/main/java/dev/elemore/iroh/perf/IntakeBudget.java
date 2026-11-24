package dev.elemore.iroh.perf;

/**
 * Iroh (P2-4, perf/streaming): the per-section check of the render-thread section intake (TaskDispatcher.updateSections).
 * The old rule checks the 1 ms budget only AFTER a section: a frame that has spent 0.9 ms then takes a 3 ms section and
 * ends at 3.9 ms. With {@code iroh.intake.budget} on, the next section is predicted from its byte size (learned cost
 * per KB plus a fixed cost per section) and the intake stops BEFORE a section that would cross the budget. The first
 * section of a frame always runs (progress). Sections during which an area buffer grew are not learned from (a grow is
 * a rare, much larger cost that would inflate every later estimate). Pure logic; tests drive it with a fake clock.
 */
public final class IntakeBudget {
    /** Runtime switch (PerfSwitches), one volatile read. */
    public static boolean enabled() {
        return PerfSwitches.intakeBudgetOn;
    }

    /** Sections at most this big teach the fixed cost; larger ones the cost per byte. */
    static final long SMALL = 4 << 10;
    /** ns per byte before anything was measured: 0.5 ns/B (0.5 ms per MiB), on the safe side of block 6 logs */
    static final double DEFAULT_PER_BYTE = 0.5;
    static final long DEFAULT_FIXED = 20_000;
    static final double MAX_PER_BYTE = 8;

    private double perByteNs = DEFAULT_PER_BYTE;
    private long fixedNs = DEFAULT_FIXED;
    public int deferredSections;

    /** Predicted render-thread cost of a section of {@code bytes}. */
    public long estimate(long bytes) {
        return this.fixedNs + (long) (this.perByteNs * bytes);
    }

    /**
     * May the next section ({@code nextBytes}) run? {@code elapsedNs} = intake time spent this frame, {@code done} =
     * sections already taken this frame.
     */
    public boolean admit(long elapsedNs, long budgetNs, long bytesSoFar, long budgetBytes, long nextBytes, int done) {
        if (done == 0) return true;
        if (bytesSoFar + nextBytes > budgetBytes) return false;
        return elapsedNs + estimate(nextBytes) <= budgetNs;
    }

    /** One section took {@code ns} for {@code bytes}; {@code grew}: an area buffer grew meanwhile (not learned). */
    public void observe(long bytes, long ns, boolean grew) {
        if (grew || ns <= 0) return;
        if (bytes <= SMALL) {
            this.fixedNs = (this.fixedNs * 7 + Math.min(ns, 2_000_000L)) / 8;
        } else {
            // clamped: one descheduled section (wall time) must not make every later frame take a single section
            double per = Math.min(MAX_PER_BYTE, Math.max(0, ns - this.fixedNs) / (double) bytes);
            this.perByteNs = (this.perByteNs * 7 + per) / 8;
        }
    }

    public double perByteNs() {
        return this.perByteNs;
    }

    public long fixedNs() {
        return this.fixedNs;
    }
}
