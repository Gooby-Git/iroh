package dev.elemore.iroh.perf;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Iroh (perf/hitches, v2): time-budgeted drain of the render thread's task queue (Minecraft.runAllTasks in runTick).
 * OFF by default ({@code -Diroh.tasks.budget=true} to enable): block 7 showed v1 worse than vanilla in glide.
 * <p>
 * Tasks always run IN ORDER through vanilla's pollTask; the drain only stops early and continues next frame.
 * <p>
 * v1 failure analysis (block 7): (a) the budget filled the frame up to the target even on frames that then run a client
 * tick (about 11 ms in this pack), so tick frames went over 16.7 ms more often; (b) a backlog of 390-406 deferred
 * packets was force-drained in ONE frame after 50 ms / 3 frames: a bigger spike than vanilla's.
 * v2:
 * <ul>
 *   <li>tick aware: the ticks this frame will run (known before the drain: DeltaTracker.advanceTime) times their
 *       measured cost are taken out of the budget;</li>
 *   <li>proportional, no cliff: the budget grows with the backlog (estimated work of the leftover tasks spread over
 *       {@code iroh.tasks.clearFrames}, 4) and with its age (doubling every {@code iroh.tasks.maxDelayMs}/2), so the
 *       queue keeps up on average instead of piling up;</li>
 *   <li>never past the smoothness line: a catch-up slice fills the frame up to {@code iroh.tasks.frameCapMs} (16 ms)
 *       at most, so a backlog becomes several frames under 16 ms instead of one long one;</li>
 *   <li>a hard safety bound only: leftovers older than {@code iroh.tasks.hardMs} (250 ms) drain fully;</li>
 *   <li>at least {@code iroh.tasks.minBudgetMs} (1 ms) and at least one task per frame, except a frame that runs a
 *       client tick with no room left while the backlog is younger than maxDelayMs (it would only push that frame over
 *       the line; the next frame catches up);</li>
 *   <li>a slice stops before a task that would likely cross the budget (recent mean task time).</li>
 * </ul>
 * Pure logic (clock and queue are parameters): invariants tested offline against a vanilla full drain.
 */
public final class TaskBudget {
    /** Runtime switch (PerfSwitches), read every frame. */
    public static boolean enabled() {
        return PerfSwitches.on(PerfSwitches.TASKS_BUDGET);
    }
    static final long MIN_BUDGET_NS = (long) (Double.parseDouble(System.getProperty("iroh.tasks.minBudgetMs", "1")) * 1e6);
    static final long MAX_DELAY_NS = (long) (Double.parseDouble(System.getProperty("iroh.tasks.maxDelayMs", "50")) * 1e6);
    static final long HARD_NS = (long) (Double.parseDouble(System.getProperty("iroh.tasks.hardMs", "250")) * 1e6);
    static final int CLEAR_FRAMES = Integer.getInteger("iroh.tasks.clearFrames", 4);
    /** The frame time a catch-up slice may fill up to (the slow-frame line). */
    static final long CAP_NS = (long) (Double.parseDouble(System.getProperty("iroh.tasks.frameCapMs", "16")) * 1e6);

    private final LongSupplier clock;
    private final long minBudgetNs, maxDelayNs, hardNs;
    private long capNs = CAP_NS;
    private final int clearFrames;

    /** Moving averages (ns): frame time outside drain and ticks; one client tick; one task. */
    private long otherEstNs = -1, tickEstNs = -1, taskEstNs = -1;
    private long frameStartNs = -1, drainNs, tickNs;
    private int ticksThisFrame;
    /**
     * Age tracking of the OLDEST leftover task: {@code leftSinceNs} is (about) when the tasks still owed from the
     * marker were already queued, {@code markerRemaining} how many of them are still queued (in order, they run first).
     * -1: nothing left over.
     */
    private long leftSinceNs = -1;
    private int markerRemaining;

    // stats (tests, slow-frame counters)
    public int lastRun, lastLeft;
    public boolean lastForced;
    public long lastBudgetNs;

    public TaskBudget(LongSupplier clock, long minBudgetNs, long maxDelayNs, long hardNs, int clearFrames) {
        this.clock = clock;
        this.minBudgetNs = minBudgetNs;
        this.maxDelayNs = maxDelayNs;
        this.hardNs = hardNs;
        this.clearFrames = Math.max(1, clearFrames);
    }

    /** Tests: the smoothness line. */
    TaskBudget cap(long ns) {
        this.capNs = ns;
        return this;
    }

    public static TaskBudget forGame() {
        return new TaskBudget(System::nanoTime, MIN_BUDGET_NS, MAX_DELAY_NS, HARD_NS, CLEAR_FRAMES);
    }

    /** runTick HEAD: closes the previous frame's measurement. */
    public void frameStart() {
        long now = clock.getAsLong();
        if (frameStartNs >= 0) {
            long other = Math.max(0, (now - frameStartNs) - drainNs - tickNs);
            otherEstNs = otherEstNs < 0 ? other : (otherEstNs * 7 + other) / 8;
        }
        frameStartNs = now;
        drainNs = 0;
        tickNs = 0;
        ticksThisFrame = 0;
    }

    /** Client ticks this frame will run (DeltaTracker.advanceTime, before the drain). */
    public void ticksPlanned(int ticks) {
        ticksThisFrame = Math.max(0, ticks);
    }

    /** One client tick took {@code ns}. */
    public void tickDone(long ns) {
        tickNs += ns;
        tickEstNs = tickEstNs < 0 ? ns : (tickEstNs * 7 + ns) / 8;
    }

    /** Time the frame spent idle (fps limiter sleep): not work, so it does not shrink the budget. */
    public void idle(long ns) {
        drainNs += Math.max(0, ns);
    }

    /** The budget for this frame's drain (pure; also used by the test). */
    long budget(long start, int pendingBefore, long targetNs) {
        long fixed = Math.max(0, otherEstNs) + (long) ticksThisFrame * Math.max(0, tickEstNs);
        long base = targetNs - fixed;
        // never push the frame past the smoothness line (16 ms): catching up happens below it, over more frames
        long room = Math.max(minBudgetNs, capNs - fixed);
        long b = Math.max(minBudgetNs, base);
        if (leftSinceNs >= 0 && pendingBefore > 0) {
            // keep up on average: the backlog's estimated work over clearFrames frames ...
            long backlog = (long) pendingBefore * Math.max(0, taskEstNs) / clearFrames;
            // ... and more the older it gets (x2 every maxDelay/2)
            long age = start - leftSinceNs;
            double ageFactor = Math.pow(2.0, age / (double) Math.max(1, maxDelayNs / 2));
            b = Math.max(b, (long) Math.min(Long.MAX_VALUE / 4, Math.max(backlog, minBudgetNs * ageFactor)));
        }
        return Math.min(b, Math.max(room, Math.max(minBudgetNs, base)));
    }

    /**
     * Drains the queue in order. {@code pollTask} runs the next task and returns false when the queue is empty;
     * {@code pending} = tasks still queued. {@code full}: drain everything. Returns the number of tasks run.
     */
    public int drain(BooleanSupplier pollTask, IntSupplier pending, boolean full, long targetNs) {
        long start = clock.getAsLong();
        int before = pending.getAsInt();
        boolean forced = full || (leftSinceNs >= 0 && start - leftSinceNs >= hardNs);
        long budget = forced ? Long.MAX_VALUE : budget(start, before, targetNs);
        long est = Math.max(0, taskEstNs);
        // a frame that runs a client tick and has no room left may skip the queue while the backlog is young
        boolean skip = !forced && ticksThisFrame > 0 && budget <= minBudgetNs && est > 0
                && (leftSinceNs < 0 || start - leftSinceNs < maxDelayNs)
                && Math.max(0, otherEstNs) + (long) ticksThisFrame * Math.max(0, tickEstNs) + est > capNs;
        int run = 0;
        long t = start;
        while (!skip) {
            // stop BEFORE a task that would likely cross the budget (estimate = recent mean task time)
            if (!forced && run > 0 && (t - start) + est > budget) break;
            if (!pollTask.getAsBoolean()) break;
            run++;
            t = clock.getAsLong();
        }
        long spent = t - start;
        drainNs += spent;
        if (run > 0) {
            long per = spent / run;
            taskEstNs = taskEstNs < 0 ? per : (taskEstNs * 7 + per) / 8;
        }
        int left = pending.getAsInt();
        if (left == 0) {
            leftSinceNs = -1;
            markerRemaining = 0;
        } else if (leftSinceNs < 0) {
            leftSinceNs = start;          // these tasks were queued by the time this drain started (or soon after)
            markerRemaining = left;
        } else {
            markerRemaining -= run;
            if (markerRemaining <= 0) {   // the tasks owed at the marker are done: the oldest left is younger
                leftSinceNs = start;
                markerRemaining = left;
            }
        }
        lastRun = run;
        lastLeft = left;
        lastForced = forced;
        lastBudgetNs = budget;
        return run;
    }

    /** Frame-time target from the frame limit: 1 / maxFps, or 1/144 s when unlimited / vsync. */
    public static long targetNs(int maxFps) {
        int fps = maxFps <= 0 || maxFps >= 260 ? 144 : maxFps;
        return 1_000_000_000L / fps;
    }
}
