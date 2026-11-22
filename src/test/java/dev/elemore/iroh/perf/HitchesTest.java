package dev.elemore.iroh.perf;

import net.vulkanmod.render.chunk.graph.SectionGraph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * perf/hitches (v2): offline invariants.
 * <ul>
 *   <li>Task budget v2 against a vanilla full drain on the same streaming workload (fake clock, 11 ms client ticks every
 *       50 ms, packet bursts): order kept, one task per frame minimum, bounded waiting, and FEWER frames over 16.7 ms and
 *       a max frame no worse than vanilla (block 7: v1 was worse than vanilla).</li>
 *   <li>Builder count / topology / adaptive hysteresis / rebuild allowance rules.</li>
 *   <li>Graph cache, as a model (the real SectionGraph needs a game; in game -Diroh.graph.verify checks it): random
 *       worlds with occluders, camera paths and uploads that open occluders between walks. Reproduces block 7's miss
 *       with the old 100 ms refresh throttle, and shows 0 misses with the hitches2 rule (any structural upload forces a
 *       full walk; filter passes only on an unchanged graph).</li>
 * </ul>
 * {@code ./gradlew hitchesTest}; last line "# hitches PASS n/n".
 */
public final class HitchesTest {
    private static int pass, fail;
    private static long t;
    static final long ms = 1_000_000L;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    record Task(int seq, long enqueuedNs, long costNs) {}

    /** Result of one simulated run. */
    record Run(int over16, long maxFrameNs, long maxWaitNs, boolean inOrder, int framesQueuedNoneRun, long sumFrameNs) {}

    /**
     * Streaming workload at a 120 fps cap: 5 ms of other work per frame, an 11 ms client tick every 50 ms, a packet burst
     * (60 tasks of 0.05-1.5 ms) every 150 ms plus a trickle. {@code budget} null = vanilla full drain.
     */
    static Run simulate(TaskBudget budget, long seed) {
        t = 0;
        SplittableRandom rnd = new SplittableRandom(seed);
        ArrayDeque<Task> q = new ArrayDeque<>();
        List<Integer> ran = new ArrayList<>();
        int seq = 0, over = 0, noneRun = 0;
        long maxFrame = 0, maxWait = 0, sum = 0, nextTick = 0, nextBurst = 0;
        long target = TaskBudget.targetNs(120);
        for (int frame = 0; frame < 4000; frame++) {
            long f0 = t;
            if (budget != null) budget.frameStart();
            if (t >= nextBurst) {
                for (int i = 0; i < 60; i++) q.add(new Task(seq++, t, (long) (rnd.nextDouble(0.05, 1.5) * ms)));
                nextBurst += 150 * ms;
            }
            for (int i = rnd.nextInt(3); i > 0; i--) q.add(new Task(seq++, t, (long) (rnd.nextDouble(0.01, 0.2) * ms)));
            int ticks = 0;
            while (nextTick <= t) { ticks++; nextTick += 50 * ms; }
            ticks = Math.min(ticks, 10);
            int before = q.size();
            final long drainStart = t;
            final long[] w = {maxWait};
            java.util.function.BooleanSupplier poll = () -> {
                Task k = q.poll();
                if (k == null) return false;
                w[0] = Math.max(w[0], drainStart - k.enqueuedNs);
                ran.add(k.seq);
                t += k.costNs;
                return true;
            };
            int run;
            if (budget == null) {
                run = 0;
                while (poll.getAsBoolean()) run++;
            } else {
                budget.ticksPlanned(ticks);
                run = budget.drain(poll, q::size, false, target);
            }
            maxWait = w[0];
            if (before > 0 && run == 0 && ticks == 0) noneRun++;
            for (int i = 0; i < ticks; i++) {
                long tk = 11 * ms;
                t += tk;
                if (budget != null) budget.tickDone(tk);
            }
            t += 5 * ms;
            long wall = t - f0;
            if (wall < target) { // fps limiter sleep
                if (budget != null) budget.idle(target - wall);
                t = f0 + target;
                wall = target;
            }
            if (wall > 16_700_000L) over++;
            maxFrame = Math.max(maxFrame, wall);
            sum += wall;
        }
        boolean inOrder = true;
        for (int i = 0; i < ran.size(); i++) if (ran.get(i) != i) { inOrder = false; break; }
        return new Run(over, maxFrame, maxWait, inOrder, noneRun, sum);
    }

    public static void main(String[] args) {
        // ---- 1. task budget v2 vs vanilla
        int betterOver = 0, notWorseMax = 0;
        long worstWait = 0;
        boolean allOrdered = true, alwaysOne = true;
        for (long seed = 1; seed <= 8; seed++) {
            Run v = simulate(null, seed);
            Run b = simulate(new TaskBudget(() -> t, ms, 50 * ms, 250 * ms, 4), seed);
            if (seed == 1)
                System.out.println("info seed 1: vanilla over16.7=" + v.over16 + " max=" + v.maxFrameNs / 100_000 / 10.0 + " ms; v2 over16.7=" + b.over16
                        + " max=" + b.maxFrameNs / 100_000 / 10.0 + " ms, max wait " + b.maxWaitNs / ms + " ms");
            if (b.over16 < v.over16) betterOver++;
            if (b.maxFrameNs <= v.maxFrameNs) notWorseMax++;
            worstWait = Math.max(worstWait, b.maxWaitNs);
            allOrdered &= b.inOrder;
            alwaysOne &= b.framesQueuedNoneRun == 0;
        }
        check("v2 keeps the exact task order (8 workloads)", allOrdered);
        check("v2 runs at least one task in every frame with queued tasks, except tick frames without room", alwaysOne);
        check("v2: fewer frames over 16.7 ms than vanilla in all 8 workloads (" + betterOver + "/8)", betterOver == 8);
        check("v2: max frame no worse than vanilla in all 8 workloads (" + notWorseMax + "/8)", notWorseMax == 8);
        check("v2: no task waits more than 150 ms before the drain that runs it (worst " + worstWait / ms + " ms; hard bound 250)", worstWait <= 150 * ms);

        // the v1 failure mode: a deep backlog is spread, never drained in one frame (hard bound only after 250 ms)
        TaskBudget deep = new TaskBudget(() -> t, ms, 50 * ms, 250 * ms, 4);
        t = 0;
        ArrayDeque<Task> q = new ArrayDeque<>();
        for (int i = 0; i < 400; i++) q.add(new Task(i, 0, ms / 2)); // 200 ms of packets
        long maxDrain = 0;
        int frames = 0;
        while (!q.isEmpty() && frames < 200) {
            deep.frameStart();
            long d0 = t;
            deep.drain(() -> { Task k = q.poll(); if (k == null) return false; t += k.costNs; return true; }, q::size, false, TaskBudget.targetNs(120));
            maxDrain = Math.max(maxDrain, t - d0);
            t += 5 * ms;
            frames++;
        }
        check("400 deferred packets (200 ms of work) are spread: largest slice " + maxDrain / ms + " ms over " + frames + " frames (v1: one 200 ms frame)",
                q.isEmpty() && maxDrain < 100 * ms && frames > 2);
        check("frame target from the fps cap: 120 -> 8.3 ms, unlimited -> 6.9 ms", TaskBudget.targetNs(120) == 8_333_333L && TaskBudget.targetNs(260) == 6_944_444L);

        // ---- 2. threads
        check("builders (physical rule): 6 cores -> base 2, adaptive max 4", ThreadPolicy.baseBuilders(0, 6) == 2 && ThreadPolicy.maxBuilders(2, 6, true) == 4);
        check("builders (rc14 rule, the default): 12 logical -> 5, configured 2 -> 2", ThreadPolicy.legacyBuilders(0, 12) == 5 && ThreadPolicy.legacyBuilders(2, 12) == 2);
        check("builders: not adaptive -> max = base", ThreadPolicy.maxBuilders(2, 6, false) == 2);
        Map<Integer, String> ryzen = new TreeMap<>();
        for (int c = 0; c < 12; c++) ryzen.put(c, (c % 6) + "," + (c % 6 + 6));
        String[] r = ThreadPolicy.reservation(ryzen);
        check("7600X topology (siblings n,n+6): reserve CPUs 5,11 like elemore-core; others 0-4,6-10",
                r != null && r[0].equals("5,11") && r[1].equals("0,1,2,3,4,6,7,8,9,10") && ThreadPolicy.physicalCores(ryzen) == 6);
        Map<Integer, String> small = new TreeMap<>();
        for (int c = 0; c < 6; c++) small.put(c, (c / 2 * 2) + "-" + (c / 2 * 2 + 1));
        check("3 physical cores: no reservation", ThreadPolicy.reservation(small) == null && ThreadPolicy.physicalCores(small) == 3);
        ThreadPolicy.Adaptive a = new ThreadPolicy.Adaptive(2, 4);
        int l1 = a.update(500, 0), l2 = a.update(500, 400 * ms), l3 = a.update(500, 600 * ms), l4 = a.update(100, 700 * ms);
        int l5 = a.update(10, 800 * ms), l6 = a.update(10, 2_700 * ms), l7 = a.update(10, 2_900 * ms);
        check("adaptive: up after 0.5 s of backlog, down after 2 s of a small one (" + l1 + l2 + l3 + l4 + l5 + l6 + l7 + ")",
                l1 == 2 && l2 == 2 && l3 == 4 && l4 == 4 && l5 == 4 && l6 == 4 && l7 == 2);
        check("rebuild allowance: empty backlog -> 48 with 2 builders, big backlog -> 8",
                SectionGraph.rebuildAllowance(0, 2) == 48 && SectionGraph.rebuildAllowance(500, 2) == 8);

        // ---- 3. graph cache model
        int missThrottle = 0, missRule = 0, extraRule = 0, framesChecked = 0;
        for (long seed = 1; seed <= 20; seed++) {
            int[] m = GraphModel.run(seed, true);
            missThrottle += m[0];
            int[] n = GraphModel.run(seed, false);
            missRule += n[0];
            extraRule += n[1];
            framesChecked += n[2];
        }
        check("model reproduces block 7: a refresh throttle misses sections opened by uploads (" + missThrottle + " missing)", missThrottle > 0);
        check("model, hitches2 rule (structural upload -> full walk): 0 missing and 0 extra over " + framesChecked + " frames",
                missRule == 0 && extraRule == 0 && framesChecked > 0);

        System.out.println("# hitches " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * Model of the cached visibility walk: a 24x8x24 grid of sections, some opaque (block passage), the walk from the
     * camera section passes open sections (all directions, no frustum), the frustum is a view-direction half-space test.
     * Uploads open opaque sections over time. Compares the visible set each frame with a fresh walk + filter.
     */
    static final class GraphModel {
        static final int W = 24, H = 8;

        static int[] run(long seed, boolean throttle) {
            SplittableRandom rnd = new SplittableRandom(seed);
            boolean[] opaque = new boolean[W * H * W];
            for (int i = 0; i < opaque.length; i++) opaque[i] = rnd.nextInt(100) < 35;
            int cx = W / 2, cy = H / 2, cz = W / 2;
            opaque[idx(cx, cy, cz)] = false;
            boolean[] cached = walk(opaque, cx, cy, cz);
            int cacheX = cx, cacheY = cy, cacheZ = cz;
            boolean structureDirty = false;
            long lastFull = 0;
            int missing = 0, extra = 0, frames = 0;
            for (int frame = 1; frame <= 600; frame++) {
                long now = frame * 8L; // ms
                // camera wanders slowly, turns fast
                if (frame % 60 == 0) {
                    cx = clamp(cx + rnd.nextInt(3) - 1, W);
                    cz = clamp(cz + rnd.nextInt(3) - 1, W);
                    opaque[idx(cx, cy, cz)] = false;
                }
                double yaw = frame * 0.21 + rnd.nextDouble(-0.5, 0.5);
                // an upload this frame opens an occluder (structural)
                if (rnd.nextInt(4) == 0) {
                    int i = rnd.nextInt(opaque.length);
                    if (opaque[i]) {
                        opaque[i] = false;
                        structureDirty = true;
                    }
                }
                boolean sectionChanged = cx != cacheX || cy != cacheY || cz != cacheZ;
                boolean full = sectionChanged || (structureDirty && (!throttle || now - lastFull >= 100));
                if (full) {
                    cached = walk(opaque, cx, cy, cz);
                    cacheX = cx; cacheY = cy; cacheZ = cz;
                    structureDirty = false;
                    lastFull = now;
                }
                boolean[] fresh = walk(opaque, cx, cy, cz);
                for (int i = 0; i < fresh.length; i++) {
                    boolean inView = inFrustum(i, cx, cy, cz, yaw);
                    boolean c = cached[i] && inView, f = fresh[i] && inView;
                    if (f && !c) missing++;
                    if (c && !f) extra++;
                }
                frames++;
            }
            return new int[]{missing, extra, frames};
        }

        static int clamp(int v, int w) {
            return Math.max(1, Math.min(w - 2, v));
        }

        static int idx(int x, int y, int z) {
            return (y * W + z) * W + x;
        }

        /** Visited set of a walk from the camera section through open sections (opaque ones are reached but block). */
        static boolean[] walk(boolean[] opaque, int cx, int cy, int cz) {
            boolean[] seen = new boolean[opaque.length];
            ArrayDeque<int[]> q = new ArrayDeque<>();
            seen[idx(cx, cy, cz)] = true;
            q.add(new int[]{cx, cy, cz});
            int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            while (!q.isEmpty()) {
                int[] p = q.poll();
                if (opaque[idx(p[0], p[1], p[2])] && !(p[0] == cx && p[1] == cy && p[2] == cz)) continue;
                for (int[] v : d) {
                    int x = p[0] + v[0], y = p[1] + v[1], z = p[2] + v[2];
                    if (x < 0 || y < 0 || z < 0 || x >= W || y >= H || z >= W) continue;
                    int i = idx(x, y, z);
                    if (seen[i]) continue;
                    seen[i] = true;
                    q.add(new int[]{x, y, z});
                }
            }
            return seen;
        }

        static boolean inFrustum(int i, int cx, int cy, int cz, double yaw) {
            int x = i % W, z = (i / W) % W;
            double dx = x - cx, dz = z - cz;
            return dx * Math.cos(yaw) + dz * Math.sin(yaw) > -0.5; // half-space ahead of the camera
        }
    }
}
