package net.vulkanmod.render.chunk.build.task;

import dev.elemore.iroh.chunk.ChunkDeferPolicy;
import dev.elemore.iroh.chunk.SyncWaitSet;
import dev.elemore.iroh.config.IrohOptions.DeferMode;
import net.vulkanmod.render.chunk.RenderSection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Headless test of the chunk build defer mode machinery (P1-11 item 1): the importance rule, the wait set with its
 * cap and budget, and the latch contract between ChunkTask / TaskDispatcher and the scheduler. It does not build
 * chunks or touch Vulkan; the in-game half (flicker, frame time) is on the Phase 1 campaign list.
 * {@code ./gradlew chunkDeferTest}; last line "# chunkdefer PASS n/n".
 */
public final class ChunkDeferTest {
    private static final List<String> FAILS = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        policy();
        waitSet();
        latchContract();
        System.out.println(FAILS.isEmpty() ? "# chunkdefer PASS " + checks + "/" + checks
                : "# chunkdefer FAIL " + FAILS.size() + " of " + checks + ": " + FAILS);
        if (!FAILS.isEmpty())
            System.exit(1);
    }

    private static void policy() {
        // ALWAYS: nothing is important, whatever vanilla says
        for (boolean nearby : new boolean[]{false, true})
            for (boolean player : new boolean[]{false, true})
                check("ALWAYS never important (nearby=" + nearby + ", player=" + player + ")",
                        !ChunkDeferPolicy.isImportant(DeferMode.ALWAYS, nearby, player, 0));
        for (DeferMode m : new DeferMode[]{DeferMode.ONE_FRAME, DeferMode.ZERO_FRAMES}) {
            check(m + ": player-caused is important with vanilla NONE/PLAYER_AFFECTED, even far away",
                    ChunkDeferPolicy.isImportant(m, false, true, 1e9));
            check(m + ": a dirty section from the world (not the player) is not important without NEARBY",
                    !ChunkDeferPolicy.isImportant(m, false, false, 1.0));
            check(m + ": NEARBY makes a close world-dirty section important (767.9)",
                    ChunkDeferPolicy.isImportant(m, true, false, 767.9));
            check(m + ": NEARBY radius is exclusive at 768 (vanilla: distSqr < 768)",
                    !ChunkDeferPolicy.isImportant(m, true, false, 768.0));
            check(m + ": NEARBY and player-caused far away is still important",
                    ChunkDeferPolicy.isImportant(m, true, true, 1e9));
        }
        check("ALWAYS budget is 0", ChunkDeferPolicy.budgetNs(DeferMode.ALWAYS) == 0);
        check("ZERO_FRAMES budget > 0 and below 16 ms", ChunkDeferPolicy.budgetNs(DeferMode.ZERO_FRAMES) > 0
                && ChunkDeferPolicy.budgetNs(DeferMode.ZERO_FRAMES) < 16_000_000L);
        check("ONE_FRAME budget > 0 and below 16 ms", ChunkDeferPolicy.budgetNs(DeferMode.ONE_FRAME) > 0
                && ChunkDeferPolicy.budgetNs(DeferMode.ONE_FRAME) < 16_000_000L);
    }

    private static void waitSet() throws Exception {
        // finishes inside the budget
        SyncWaitSet set = new SyncWaitSet();
        CountDownLatch quick = new CountDownLatch(1);
        set.add(quick);
        Thread t = new Thread(() -> {
            sleep(20);
            quick.countDown();
        });
        t.start();
        check("pending counts an unfinished latch", set.pending() == 1);
        check("awaitAll returns true when the task finishes in time", set.awaitAll(2_000_000_000L));
        check("awaitAll emptied the set", set.pending() == 0);

        // never finishes: bounded by the budget, set cleared, timeout counted
        CountDownLatch never = new CountDownLatch(1);
        set.add(never);
        long t0 = System.nanoTime();
        boolean ok = set.awaitAll(30_000_000L);
        long took = System.nanoTime() - t0;
        check("awaitAll gives up after the budget (returned false)", !ok);
        check("the wait stayed near the budget (<= 200 ms for 30 ms)", took < 200_000_000L);
        check("a timeout is counted", set.timeouts == 1);
        check("the abandoned latch is forgotten", set.pending() == 0);

        // several tasks share one budget, not one budget each
        SyncWaitSet shared = new SyncWaitSet();
        for (int i = 0; i < 5; i++)
            shared.add(new CountDownLatch(1));
        t0 = System.nanoTime();
        shared.awaitAll(40_000_000L);
        took = System.nanoTime() - t0;
        check("five stuck tasks wait one budget in total, not five (<= 150 ms for 40 ms)", took < 150_000_000L);

        // cap
        SyncWaitSet capped = new SyncWaitSet();
        capped.setCap(3);
        for (int i = 0; i < 3; i++) {
            check("room before the cap " + i, capped.hasRoom());
            capped.add(new CountDownLatch(1));
        }
        check("no room at the cap", !capped.hasRoom());
        capped.noteOverCap();
        check("over-cap sections are counted", capped.overCap == 1);

        // zero budget with something unfinished must not hang or throw
        SyncWaitSet zero = new SyncWaitSet();
        zero.add(new CountDownLatch(1));
        check("a zero budget returns at once", !zero.awaitAll(0L));
    }

    /** ChunkTask.done is counted down by finish(); the dispatcher calls finish() for dropped tasks too. */
    private static void latchContract() throws Exception {
        ChunkTask task = fake();
        check("finish() without a latch is harmless", noThrow(task::finish));
        CountDownLatch l = new CountDownLatch(1);
        task.done = l;
        task.finish();
        check("finish() counts the latch down", l.getCount() == 0);

        // the dispatcher only needs its (unused here) builder pack; no buffers are allocated
        net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack.setTerrainBuilderConstructor(r -> null);
        TaskDispatcher d = new TaskDispatcher();
        ChunkTask a = fake(), b = fake();
        a.done = new CountDownLatch(1);
        b.done = new CountDownLatch(1);
        b.highPriority = true;
        d.schedule(a);
        d.schedule(b);
        check("two tasks queued, dispatcher not idle", !d.isIdle());
        d.clearBatchQueue();
        check("clearBatchQueue releases the latch of a queued low-priority task", a.done.getCount() == 0);
        check("clearBatchQueue releases the latch of a queued high-priority task", b.done.getCount() == 0);
        check("dispatcher idle again", d.isIdle());
    }

    private static ChunkTask fake() {
        return new ChunkTask((RenderSection) null) {
            @Override public String name() { return "fake"; }
            @Override public Result runTask(net.vulkanmod.render.chunk.build.thread.BuilderResources r) { return Result.SUCCESSFUL; }
        };
    }

    private static boolean noThrow(Runnable r) {
        try {
            r.run();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            FAILS.add(what);
            System.out.println("FAIL " + what);
        }
    }
}
