package dev.elemore.iroh.diag;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Iroh (hitches3): {@code -Diroh.slowFrames=<ms> -Diroh.slowFrames.stacks=true} names what the render thread does in a
 * slow frame.
 * <p>
 * hitches2 stalled startup for 3 minutes (block 8): it sampled with
 * {@code ThreadMXBean.getThreadInfo(ids, lockedMonitors=true, lockedSynchronizers=true)} every 2 ms, and
 * lockedSynchronizers makes the VM walk the HEAP for owned synchronizers inside a stop-the-world operation; frame 1
 * (startup, resource reload, class loading) lasts minutes, so it ran thousands of times while everything else waited.
 * Now:
 * <ul>
 *   <li>inactive until a world is active ({@link #setWorldActive}: level + player present, no screen, no overlay /
 *       resource reload) and {@code iroh.slowFrames.stacksWarmup} (300) world frames have rendered; off again on
 *       unload, screens and reloads;</li>
 *   <li>a sample is {@code Thread.getState()} + {@code Thread.getStackTrace()} of the render thread (a per-thread
 *       handshake, no heap walk), depth-limited to {@link #MAX_DEPTH} when aggregated;</li>
 *   <li>lock and owner only for BLOCKED / WAITING / TIMED_WAITING samples, via {@code getThreadInfo(id, 0)} (no
 *       locked-monitor / synchronizer search);</li>
 *   <li>at most {@code iroh.slowFrames.stacksPerFrame} (64) samples per frame and {@code stacksPerSecond} (250) per
 *       second; interval {@code iroh.slowFrames.stacksIntervalMs} (2, min 1);</li>
 *   <li>the time the sampler itself spends is counted and printed with the summary.</li>
 * </ul>
 * The render thread only writes volatiles (frame start, phase, active): no lock is shared with the sampler on the phase
 * path. The per-frame buffer is a fixed array owned by the sampler and read by the render thread only at the end of a
 * slow frame, after the frame-serial handover.
 */
public final class SlowStacks {
    public static final boolean ON = SlowFrames.ON && Boolean.getBoolean("iroh.slowFrames.stacks");
    static final long AFTER_NS = (long) (Double.parseDouble(System.getProperty("iroh.slowFrames.stackAfterMs", "8")) * 1e6);
    static final long INTERVAL_MS = Math.max(1, Long.getLong("iroh.slowFrames.stacksIntervalMs", 2L));
    static final int PER_FRAME = Math.max(1, Integer.getInteger("iroh.slowFrames.stacksPerFrame", 64));
    static final int PER_SECOND = Math.max(1, Integer.getInteger("iroh.slowFrames.stacksPerSecond", 250));
    static final int WARMUP = Integer.getInteger("iroh.slowFrames.stacksWarmup", 300);
    static final int MAX_DEPTH = 24;

    // ---- written by the render thread (volatiles only)
    private static volatile long frameStartNs;   // 0 = not sampling this frame
    private static volatile int phase = -1;
    private static volatile long frameSerial;
    private static volatile Thread render;
    private static int activeFrames;               // render thread only

    // ---- sampler-owned buffer for the current frame (fixed size; read by the render thread after the frame)
    private static final Object[] stacks = new Object[256];
    private static final Thread.State[] states = new Thread.State[256];
    private static final int[] phases = new int[256];
    private static final String[] locks = new String[256];
    private static volatile long bufferSerial = -1;
    private static volatile int count;
    private static long secondStart, secondCount;

    // ---- overhead (sampler thread)
    static volatile long sampleNs, samplesTaken, samplesSkippedCap;

    static LongSupplier CLOCK = System::nanoTime;
    private static Thread sampler;

    private SlowStacks() {}

    /** Render thread, every frame before frameStart: whether a world is active (see class comment). */
    public static void setWorldActive(boolean active) {
        if (!active) activeFrames = 0;
        else if (activeFrames < Integer.MAX_VALUE) activeFrames++;
    }

    static boolean armed() {
        return activeFrames > WARMUP;
    }

    /** Render thread, frame start. */
    static void frameStart(long nowNs) {
        frameSerial++;
        phase = -1;
        if (!armed()) {
            frameStartNs = 0;
            return;
        }
        if (render == null) {
            render = Thread.currentThread();
            start();
        }
        frameStartNs = nowNs == 0 ? 1 : nowNs;
    }

    static void phase(int p) {
        phase = p;
    }

    /** Render thread, frame end. */
    static void frameEnd() {
        frameStartNs = 0;
    }

    private static synchronized void start() {
        if (sampler != null) return;
        sampler = new Thread(SlowStacks::loop, "iroh-stack-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    private static void loop() {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        while (true) {
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            sampleOnce(tmx);
        }
    }

    /** One sampler step (also driven directly by the test). */
    static void sampleOnce(ThreadMXBean tmx) {
        long fs = frameStartNs;
        Thread r = render;
        if (fs == 0 || r == null) return;
        long now = CLOCK.getAsLong();
        if (now - fs < AFTER_NS) return;
        long serial = frameSerial;
        if (bufferSerial != serial) { // a new slow frame: reset the buffer
            count = 0;
            bufferSerial = serial;
        }
        if (now - secondStart >= 1_000_000_000L) {
            secondStart = now;
            secondCount = 0;
        }
        if (count >= Math.min(PER_FRAME, stacks.length) || secondCount >= PER_SECOND) {
            samplesSkippedCap++;
            return;
        }
        long t0 = System.nanoTime();
        try {
            Thread.State st = r.getState();
            StackTraceElement[] stack = r.getStackTrace();
            String lock = null;
            if (st == Thread.State.BLOCKED || st == Thread.State.WAITING || st == Thread.State.TIMED_WAITING) {
                ThreadInfo ti = tmx == null ? null : tmx.getThreadInfo(r.threadId(), 0); // no monitor / synchronizer search
                if (ti != null && ti.getLockName() != null)
                    lock = shortLock(ti.getLockName()) + " owned by " + (ti.getLockOwnerName() == null ? "?" : ti.getLockOwnerName());
            }
            int i = count;
            stacks[i] = stack;
            states[i] = st;
            phases[i] = phase;
            locks[i] = lock;
            count = i + 1;
            secondCount++;
            samplesTaken++;
        } catch (Throwable ignored) {
        } finally {
            sampleNs += System.nanoTime() - t0;
        }
    }

    /** Render thread, slow frame: the histogram of this frame's samples ("" when none). */
    static String report() {
        if (bufferSerial != frameSerial) return "";
        int n = count;
        if (n == 0) return "";
        Map<String, Integer> leaf = new HashMap<>(), first = new HashMap<>(), st = new HashMap<>(), lk = new HashMap<>(), ph = new HashMap<>();
        for (int i = 0; i < n; i++) {
            StackTraceElement[] s = (StackTraceElement[]) stacks[i];
            if (s == null) continue;
            if (s.length > 0) leaf.merge(frame(s[0]), 1, Integer::sum);
            for (int k = 0; k < s.length && k < MAX_DEPTH; k++) {
                String c = s[k].getClassName();
                if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.") || c.startsWith("com.sun.")) continue;
                first.merge(frame(s[k]), 1, Integer::sum);
                break;
            }
            st.merge(String.valueOf(states[i]), 1, Integer::sum);
            if (locks[i] != null) lk.merge(locks[i], 1, Integer::sum);
            ph.merge(phases[i] < 0 ? "-" : SlowFrames.PHASES[phases[i]].label, 1, Integer::sum);
        }
        StringBuilder b = new StringBuilder(" | stacks ").append(n).append(" samples");
        top(b, "; by phase", ph, 4);
        top(b, "; states", st, 4);
        top(b, "; leaf", leaf, 5);
        top(b, "; first non-JDK", first, 5);
        if (!lk.isEmpty()) top(b, "; waits on", lk, 3);
        return b.toString();
    }

    /** Summary part: what the sampler cost. */
    static String overhead() {
        return " | stack sampler: " + samplesTaken + " samples, " + (sampleNs / 1_000_000L) + " ms spent, " + samplesSkippedCap + " skipped by the caps";
    }

    private static String frame(StackTraceElement e) {
        String c = e.getClassName();
        int dot = c.lastIndexOf('.');
        return (dot >= 0 ? c.substring(dot + 1) : c) + "." + e.getMethodName();
    }

    private static String shortLock(String lock) {
        int at = lock.indexOf('@');
        String c = at > 0 ? lock.substring(0, at) : lock;
        int dot = c.lastIndexOf('.');
        return dot >= 0 ? c.substring(dot + 1) : c;
    }

    private static void top(StringBuilder b, String label, Map<String, Integer> m, int n) {
        b.append(label).append(':');
        m.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(n)
                .forEach(e -> b.append(' ').append(e.getKey()).append(" x").append(e.getValue()));
    }

    /** Tests. */
    static void resetForTest(Thread renderThread) {
        render = renderThread;
        activeFrames = 0;
        frameStartNs = 0;
        bufferSerial = -1;
        count = 0;
        secondStart = 0;
        secondCount = 0;
        samplesTaken = samplesSkippedCap = sampleNs = 0;
    }
}
