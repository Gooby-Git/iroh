package dev.elemore.iroh.perf;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Iroh (perf/hitches): time-budgeted drain of the render thread's task queue (Minecraft.runAllTasks in runTick).
 * <p>
 * Block 6: packet handlers (hundreds per frame while flying, 2-5 ms for the large ones) made 25-90 ms frames. Vanilla
 * drains the whole queue every frame. Here the queue is drained IN ORDER (the same pollTask vanilla uses; nothing is
 * reordered or skipped; the drain only stops early and continues next frame) until the frame's task budget is spent:
 * <ul>
 *   <li>budget = target frame time minus the time the rest of the frame needs (moving average of recent frames), at least
 *       {@code iroh.tasks.minBudgetMs} (2 ms); at least one task always runs;</li>
 *   <li>a hard latency bound: once tasks have been left over for {@code iroh.tasks.maxDelayMs} (50 ms) or
 *       {@code iroh.tasks.maxFrames} (3) frames in a row, the next drain is complete, so nothing waits longer than that;</li>
 *   <li>the caller drains fully whenever the budget must not apply (no level, a screen is open, paused, loading);</li>
 *   <li>a cheap frame drains more (catch-up): the budget is what is left of the target.</li>
 * </ul>
 * Ordering and single-player logic are unchanged: the same tasks run in the same order, at most a few frames later.
 * Pure logic (clock and queue are parameters) so the invariants are tested offline.
 */
public final class TaskBudget {
    public static final boolean ENABLED = !"false".equals(System.getProperty("iroh.tasks.budget"));
    static final long MIN_BUDGET_NS = (long) (Double.parseDouble(System.getProperty("iroh.tasks.minBudgetMs", "2")) * 1e6);
    static final long MAX_DELAY_NS = (long) (Double.parseDouble(System.getProperty("iroh.tasks.maxDelayMs", "50")) * 1e6);
    static final int MAX_FRAMES = Integer.getInteger("iroh.tasks.maxFrames", 3);

    private final LongSupplier clock;
    private final long minBudgetNs, maxDelayNs;
    private final int maxFrames;

    /** Moving average of the frame time spent outside the task drain (ns). */
    private long otherEstNs = -1;
    private long frameStartNs = -1, drainNs;
    /** When tasks were first left over (-1: the queue was emptied by the last drain), and for how many frames. */
    private long leftSinceNs = -1;
    private int leftFrames;

    // stats (tests, slow-frame counters)
    public int lastRun, lastLeft;
    public boolean lastForced;

    public TaskBudget(LongSupplier clock, long minBudgetNs, long maxDelayNs, int maxFrames) {
        this.clock = clock;
        this.minBudgetNs = minBudgetNs;
        this.maxDelayNs = maxDelayNs;
        this.maxFrames = maxFrames;
    }

    public static TaskBudget forGame() {
        return new TaskBudget(System::nanoTime, MIN_BUDGET_NS, MAX_DELAY_NS, MAX_FRAMES);
    }

    /** runTick HEAD: closes the previous frame's measurement. */
    public void frameStart() {
        long now = clock.getAsLong();
        if (frameStartNs >= 0) {
            long other = Math.max(0, (now - frameStartNs) - drainNs);
            otherEstNs = otherEstNs < 0 ? other : (otherEstNs * 7 + other) / 8;
        }
        frameStartNs = now;
        drainNs = 0;
    }

    /** Time the frame spent idle (fps limiter sleep): not "work the frame needs", so it does not shrink the budget. */
    public void idle(long ns) {
        drainNs += Math.max(0, ns);
    }

    /**
     * Drains the queue. {@code pollTask} runs the next task in order and returns false when the queue is empty;
     * {@code pending} = tasks still queued. {@code full}: drain everything (budget must not apply). {@code targetNs}: the
     * frame-time target. Returns the number of tasks run.
     */
    public int drain(BooleanSupplier pollTask, java.util.function.IntSupplier pending, boolean full, long targetNs) {
        long start = clock.getAsLong();
        boolean forced = full
                || (leftSinceNs >= 0 && (start - leftSinceNs >= maxDelayNs || leftFrames >= maxFrames));
        long budget = Math.max(minBudgetNs, targetNs - Math.max(0, otherEstNs));
        int run = 0;
        while (true) {
            if (!forced && run > 0 && clock.getAsLong() - start >= budget) break;
            if (!pollTask.getAsBoolean()) break;
            run++;
        }
        long end = clock.getAsLong();
        drainNs += end - start;
        int left = pending.getAsInt();
        if (left > 0) {
            if (leftSinceNs < 0) leftSinceNs = start;
            leftFrames++;
        } else {
            leftSinceNs = -1;
            leftFrames = 0;
        }
        lastRun = run;
        lastLeft = left;
        lastForced = forced;
        return run;
    }

    /** Frame-time target from the frame limit: 1 / maxFps, or 1/144 s when unlimited / vsync. */
    public static long targetNs(int maxFps) {
        int fps = maxFps <= 0 || maxFps >= 260 ? 144 : maxFps;
        return 1_000_000_000L / fps;
    }
}
