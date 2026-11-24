package dev.elemore.iroh.perf;

import java.util.SplittableRandom;

/**
 * P2-10: the limiter's deadline math, on plain longs (no clock, no GLFW). Cases: on time, late by 0.5 and 3 periods, cap
 * changes, 0 / unlimited cap, plus a jittered-busy simulation against the OLD loop (vanilla: next deadline = the time the
 * wait ended + period, so every late frame's lateness is never repaid).
 * {@code ./gradlew frameLimiterTest}; last line "# frameLimiter PASS n/n".
 */
public final class FrameLimiterTest {
    private static int pass, fail;
    static final long MS = 1_000_000L;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    /** the old behaviour as a model: vanilla RenderSystem.limitDisplayFPS (lastDrawTime = end of the wait) */
    static final class Old {
        long last;
        long plan(long now, long period) { long t = Math.max(now, last + period); last = t; return t; }
    }

    static long period(int fps) { return 1_000_000_000L / fps; }

    public static void main(String[] a) {
        long p = period(100); // 10 ms
        // on time: frames end 4 ms after the previous deadline -> wait to the deadline
        FrameLimiter l = new FrameLimiter();
        long t0 = 1_000_000_000L;
        check("first frame never waits", l.plan(t0, p) == t0);
        long target = l.plan(t0 + 4 * MS, p);
        check("on time: waits to previous deadline + period", target == t0 + p);
        target = l.plan(t0 + p + 3 * MS, p);
        check("on time again: deadline advances by exactly one period (no drift)", target == t0 + 2 * p);

        // late by 0.5 period: no wait, deadline still advances by one period so the NEXT frame repays half
        l = new FrameLimiter(); l.plan(t0, p);
        long now = t0 + p + p / 2;
        check("late by 0.5 period: returns now (no wait)", l.plan(now, p) == now);
        long nxt = l.plan(now + 2 * MS, p);
        check("late by 0.5 period: next deadline = old deadline + 2 periods (repays the lateness, no burst)", nxt == t0 + 2 * p + 0 && nxt > now + 2 * MS - 1);

        // late by 3 periods: reset, no catch-up burst
        l = new FrameLimiter(); l.plan(t0, p);
        now = t0 + 4 * p; // deadline was t0+p, 3 periods late
        check("late by 3 periods: no wait", l.plan(now, p) == now);
        check("late by 3 periods: reset, the next frame ends one period after the stall end", l.plan(now + 2 * MS, p) == now + p);

        // cap change: period shrinks / grows, deadline carries over, wait never exceeds one period
        l = new FrameLimiter(); l.plan(t0, period(50)); // 20 ms
        long w = l.plan(t0 + 5 * MS, period(200));      // 5 ms period now
        check("cap raised: target = deadline + new period (5 ms), already reached -> no wait", w == t0 + 5 * MS);
        l = new FrameLimiter(); l.plan(t0, period(200));
        w = l.plan(t0 + 1 * MS, period(20));            // 50 ms period now
        check("cap lowered: waits at most one new period", w - (t0 + 1 * MS) <= period(20) && w > t0 + 1 * MS);

        // unlimited / zero cap
        l = new FrameLimiter(); l.plan(t0, p);
        check("cap 0: no wait", l.plan(t0 + MS, 0) == t0 + MS);
        check("cap 260 or more (unlimited): no wait", l.plan(t0 + 2 * MS, 260) == t0 + 2 * MS);
        check("after unlimited the state is reset (no stale deadline burst)", l.plan(t0 + 3 * MS, p) == t0 + 3 * MS);

        // simulation: jittered busy time, cap 100, 20000 frames
        SplittableRandom r = new SplittableRandom(7);
        long[] busy = new long[20000];
        for (int i = 0; i < busy.length; i++) busy[i] = (long) ((r.nextInt(100) < 3 ? 14 : 6.5) * MS) + r.nextInt(2 * (int) MS);
        double[] newS = simulate(busy, p, true), oldS = simulate(busy, p, false);
        System.out.printf("# sim cap100: new mean %.4f ms sd %.4f ms; old mean %.4f ms sd %.4f ms%n", newS[0], newS[1], oldS[0], oldS[1]);
        check("sim: new limiter mean interval within 0.1% of the period (old loop is slower than the cap)", Math.abs(newS[0] - 10.0) < 0.01 && oldS[0] > newS[0]);
        check("sim: after a 3-period stall the next interval is a full period (no burst)", burstFree());

        System.out.println("# frameLimiter " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        if (fail != 0) System.exit(1);
    }

    static boolean burstFree() {
        long p = period(100); FrameLimiter l = new FrameLimiter(); long t = 1_000_000_000L;
        l.plan(t, p);
        long end = l.plan(t + 3 * p + p / 2, p);          // a 3.5 period stall ends here
        long next = l.plan(end + MS, p);                  // 1 ms of work, then the limiter
        return next - end >= p - MS - 1;
    }

    /** returns {mean ms, sd ms, min ms} of frame intervals when each frame's work takes busy[i] and the limiter ends it */
    static double[] simulate(long[] busy, long period, boolean neo) {
        FrameLimiter n = new FrameLimiter(); Old o = new Old();
        long t = 1_000_000_000L, prev = t; double sum = 0, sq = 0, min = 1e18; int c = 0;
        n.plan(t, period); o.plan(t, period);
        for (long b : busy) {
            long now = t + b;
            long end = neo ? n.plan(now, period) : o.plan(now, period);
            double iv = (end - prev) / 1e6;
            sum += iv; sq += iv * iv; min = Math.min(min, iv); c++;
            prev = end; t = end;
        }
        double mean = sum / c;
        return new double[]{mean, Math.sqrt(sq / c - mean * mean), min};
    }
}
