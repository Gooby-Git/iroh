package dev.elemore.iroh.perf;

import net.vulkanmod.render.chunk.graph.SectionGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * perf/hitches: offline invariants. Budgeted task drain on a fake clock (order kept exactly, at least one task per frame,
 * nothing waits past the latency bound, full drains when asked, catch-up when the frame is cheap), builder count and
 * topology rules, adaptive builder hysteresis, rebuild allowance, graph refresh gating.
 * {@code ./gradlew hitchesTest}; last line "# hitches PASS n/n".
 */
public final class HitchesTest {
    private static int pass, fail;
    private static long t;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    record Task(int seq, long enqueuedNs, long costNs) {}

    public static void main(String[] args) {
        long ms = 1_000_000L;

        // ---- 1. budgeted drain: random arrivals and costs, 3000 frames
        TaskBudget b = new TaskBudget(() -> t, 2 * ms, 50 * ms, 3);
        ArrayDeque<Task> q = new ArrayDeque<>();
        List<Integer> ran = new ArrayList<>();
        SplittableRandom rnd = new SplittableRandom(7);
        long maxWait = 0;
        int seq = 0, framesWithTasksButNoneRun = 0, forcedFrames = 0;
        long[] lastRunAt = {0};
        long target = TaskBudget.targetNs(120);
        for (int frame = 0; frame < 3000; frame++) {
            b.frameStart();
            // bursts: every 40th frame 300 chunk-like tasks of 0.1-5 ms, otherwise a few small ones
            int arrivals = frame % 40 == 0 ? 300 : rnd.nextInt(6);
            for (int i = 0; i < arrivals; i++) {
                long cost = frame % 40 == 0 ? (long) (rnd.nextDouble(0.1, 5.0) * ms) : (long) (rnd.nextDouble(0.01, 0.3) * ms);
                q.add(new Task(seq++, t, cost));
            }
            int before = q.size();
            final long[] wait = {maxWait};
            final long drainStart = t;
            b.drain(() -> {
                Task k = q.poll();
                if (k == null) return false;
                wait[0] = Math.max(wait[0], drainStart - k.enqueuedNs); // frames it waited before the drain that ran it
                ran.add(k.seq);
                t += k.costNs;
                return true;
            }, q::size, false, target);
            maxWait = wait[0];
            if (before > 0 && b.lastRun == 0) framesWithTasksButNoneRun++;
            if (b.lastForced) forcedFrames++;
            t += 5 * ms; // the rest of the frame
        }
        boolean inOrder = true;
        for (int i = 0; i < ran.size(); i++) if (ran.get(i) != i) { inOrder = false; break; }
        check("drain keeps the exact task order (" + ran.size() + " tasks)", inOrder);
        check("every frame with queued tasks runs at least one", framesWithTasksButNoneRun == 0);
        // bound: maxDelay + the frame that notices it + that frame's full drain
        // bound: the deferral that started at the frame after arrival + maxDelay, rounded up to the next frame start
        check("no task waits more than ~50 ms + 2 frames before the drain that runs it (max " + maxWait / ms + " ms)", maxWait <= 50 * ms + 2 * 20 * ms);
        check("forced full drains happen (" + forcedFrames + ") but are the exception", forcedFrames > 0 && forcedFrames < 3000 / 10);

        // per-frame time spent in tasks is bounded on non-forced frames
        TaskBudget b2 = new TaskBudget(() -> t, 2 * ms, 50 * ms, 3);
        q.clear();
        for (int i = 0; i < 1000; i++) q.add(new Task(i, t, ms / 2));
        b2.frameStart();
        long s0 = t;
        b2.drain(() -> { Task k = q.poll(); if (k == null) return false; t += k.costNs; return true; }, q::size, false, target);
        long spent = t - s0;
        check("first frame of a 500 ms backlog spends about the budget (" + spent / 100_000 / 10.0 + " ms, target 8.3)", spent <= target + ms);
        // full drain when asked (screen open, paused...)
        b2.drain(() -> { Task k = q.poll(); if (k == null) return false; t += k.costNs; return true; }, q::size, true, target);
        check("full drain empties the queue", q.isEmpty() && b2.lastForced && b2.lastLeft == 0);
        // catch-up: a cheap frame gets a bigger budget than an expensive one
        TaskBudget cheap = new TaskBudget(() -> t, 2 * ms, 50 * ms, 3), dear = new TaskBudget(() -> t, 2 * ms, 50 * ms, 3);
        for (int f = 0; f < 20; f++) { cheap.frameStart(); t += ms; }        // 1 ms of other work per frame
        long[] spentC = new long[1], spentD = new long[1];
        q.clear(); for (int i = 0; i < 1000; i++) q.add(new Task(i, t, ms / 4));
        cheap.frameStart(); long c0 = t;
        cheap.drain(() -> { Task k = q.poll(); if (k == null) return false; t += k.costNs; return true; }, q::size, false, target);
        spentC[0] = t - c0;
        for (int f = 0; f < 20; f++) { dear.frameStart(); t += 12 * ms; }  // 12 ms of other work per frame
        dear.frameStart(); long d0 = t;
        dear.drain(() -> { Task k = q.poll(); if (k == null) return false; t += k.costNs; return true; }, q::size, false, target);
        spentD[0] = t - d0;
        check("cheap frames drain more (" + spentC[0] / ms + " ms) than expensive ones (" + spentD[0] / 100_000 / 10.0 + " ms, >= min 2)",
                spentC[0] > spentD[0] && spentD[0] >= 2 * ms);
        // limiter sleep is not work: a capped cheap frame keeps a big budget
        TaskBudget capped = new TaskBudget(() -> t, 2 * ms, 50 * ms, 3);
        for (int f = 0; f < 20; f++) { capped.frameStart(); t += ms; capped.idle(7 * ms); t += 7 * ms; }
        q.clear(); for (int i = 0; i < 1000; i++) q.add(new Task(i, t, ms / 4));
        capped.frameStart(); long k0 = t;
        capped.drain(() -> { Task k = q.poll(); if (k == null) return false; t += k.costNs; return true; }, q::size, false, target);
        check("fps-limiter sleep does not count as frame work (budget " + (t - k0) / ms + " ms, like the uncapped cheap frame)", t - k0 >= 6 * ms);
        check("frame target from the fps cap: 120 -> 8.3 ms, unlimited -> 6.9 ms", TaskBudget.targetNs(120) == 8_333_333L && TaskBudget.targetNs(260) == 6_944_444L);

        // ---- 2. threads
        check("builders: 6 physical cores -> base 2, adaptive max 4", ThreadPolicy.baseBuilders(0, 6) == 2 && ThreadPolicy.maxBuilders(2, 6, true) == 4);
        check("builders: configured 2 stays the base; not adaptive -> max = base", ThreadPolicy.baseBuilders(2, 16) == 2 && ThreadPolicy.maxBuilders(2, 6, false) == 2);
        check("builders: 2 cores -> 1, 16 cores -> base 7 max 7 (cap 6 never lowers the base)", ThreadPolicy.baseBuilders(0, 2) == 1
                && ThreadPolicy.baseBuilders(0, 16) == 7 && ThreadPolicy.maxBuilders(7, 16, true) == 7);
        Map<Integer, String> ryzen = new TreeMap<>();
        for (int c = 0; c < 12; c++) ryzen.put(c, (c % 6) + "," + (c % 6 + 6));
        String[] r = ThreadPolicy.reservation(ryzen);
        check("7600X topology (siblings n,n+6): reserve CPUs 5,11 like elemore-core; others 0-4,6-10",
                r != null && r[0].equals("5,11") && r[1].equals("0,1,2,3,4,6,7,8,9,10") && ThreadPolicy.physicalCores(ryzen) == 6);
        Map<Integer, String> small = new TreeMap<>();
        for (int c = 0; c < 6; c++) small.put(c, (c / 2 * 2) + "-" + (c / 2 * 2 + 1));
        check("3 physical cores: no reservation", ThreadPolicy.reservation(small) == null && ThreadPolicy.physicalCores(small) == 3);
        ThreadPolicy.Adaptive a = new ThreadPolicy.Adaptive(2, 4);
        long n = 0;
        int l1 = a.update(500, n);
        int l2 = a.update(500, n + 400 * ms);
        int l3 = a.update(500, n + 600 * ms);
        int l4 = a.update(100, n + 700 * ms);
        int l5 = a.update(10, n + 800 * ms);
        int l6 = a.update(10, n + 2_700 * ms);
        int l7 = a.update(10, n + 2_900 * ms);
        check("adaptive: up only after 0.5 s of backlog, down only after 2 s of a small one (" + l1 + l2 + l3 + l4 + l5 + l6 + l7 + ")",
                l1 == 2 && l2 == 2 && l3 == 4 && l4 == 4 && l5 == 4 && l6 == 4 && l7 == 2);

        // ---- 3. rebuild allowance and graph refresh gating
        check("rebuild allowance: empty backlog -> 48 with 2 builders, big backlog -> 8 per update",
                SectionGraph.rebuildAllowance(0, 2) == 48 && SectionGraph.rebuildAllowance(500, 2) == 8 && SectionGraph.rebuildAllowance(40, 4) == 56);
        check("graph refresh: nothing dirty -> no walk", !GraphRefresh.refreshNow(false, 1000 * ms, 0, -1, 100 * ms, 250 * ms));
        check("graph refresh: first time -> walk", GraphRefresh.refreshNow(true, 5 * ms, -1, -1, 100 * ms, 250 * ms));
        check("graph refresh: uploads every frame -> at most one walk per 100 ms",
                !GraphRefresh.refreshNow(true, 150 * ms, 100 * ms, -1, 100 * ms, 250 * ms) && GraphRefresh.refreshNow(true, 200 * ms, 100 * ms, -1, 100 * ms, 250 * ms));
        check("graph refresh: a player edit in the last 250 ms -> walk now",
                GraphRefresh.refreshNow(true, 150 * ms, 140 * ms, 120 * ms, 100 * ms, 250 * ms) && !GraphRefresh.refreshNow(true, 500 * ms, 450 * ms, 100 * ms, 100 * ms, 250 * ms));

        System.out.println("# hitches " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
