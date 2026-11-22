package dev.elemore.iroh.diag;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Iroh (hitches2): {@code -Diroh.slowFrames=<ms> -Diroh.slowFrames.stacks=true} names what the render thread does in a
 * slow frame. A daemon sampler watches the render thread; once the current frame is older than
 * {@code iroh.slowFrames.stackAfterMs} (8 ms) it takes the render thread's stack every 2 ms (ThreadMXBean.getThreadInfo
 * with locked monitors and synchronizers: state, the lock it waits for and that lock's OWNER thread). At the end of a
 * slow frame one line: samples by phase, top 5 leaf frames, top 5 first non-JDK frames, thread states, and for
 * BLOCKED / WAITING samples the lock and its owner. Cost: nothing while frames are fast (the sampler only reads two
 * volatiles every 2 ms); the stack captures happen only inside frames that are already slow.
 */
public final class SlowStacks {
    public static final boolean ON = SlowFrames.ON && Boolean.getBoolean("iroh.slowFrames.stacks");
    static final long AFTER_NS = (long) (Double.parseDouble(System.getProperty("iroh.slowFrames.stackAfterMs", "8")) * 1e6);
    private static final int MAX_SAMPLES = 1000;

    private static volatile long frameStartNs;   // 0 = not in a frame
    private static volatile int phase = -1;       // innermost phase ordinal (render thread writes)
    private static volatile long frameSerial;
    private static volatile Thread render;

    private record Sample(long frame, int phase, StackTraceElement[] stack, Thread.State state, String lock, String owner) {}

    private static final List<Sample> SAMPLES = new ArrayList<>();
    private static Thread sampler;

    private SlowStacks() {}

    /** Render thread, frame start. */
    static void frameStart(long nowNs) {
        if (render == null) {
            render = Thread.currentThread();
            start();
        }
        frameSerial++;
        phase = -1;
        frameStartNs = nowNs == 0 ? 1 : nowNs;
    }

    static void phase(int p) {
        phase = p;
    }

    /** Render thread, frame end: stops sampling. */
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
                Thread.sleep(2);
            } catch (InterruptedException e) {
                return;
            }
            long fs = frameStartNs;
            Thread r = render;
            if (fs == 0 || r == null || System.nanoTime() - fs < AFTER_NS) continue;
            long serial = frameSerial;
            int ph = phase;
            try {
                ThreadInfo ti = tmx.getThreadInfo(new long[]{r.threadId()}, true, true)[0];
                if (ti == null) continue;
                Sample s = new Sample(serial, ph, ti.getStackTrace(), ti.getThreadState(), ti.getLockName(), ti.getLockOwnerName());
                synchronized (SAMPLES) {
                    if (SAMPLES.size() >= MAX_SAMPLES) SAMPLES.remove(0);
                    SAMPLES.add(s);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Render thread, slow frame: the histogram of this frame's samples ("" when none). Clears the buffer. */
    static String report() {
        long serial = frameSerial;
        List<Sample> mine = new ArrayList<>();
        synchronized (SAMPLES) {
            for (Sample s : SAMPLES) if (s.frame == serial) mine.add(s);
            SAMPLES.clear();
        }
        if (mine.isEmpty()) return "";
        Map<String, Integer> leaf = new HashMap<>(), first = new HashMap<>(), states = new HashMap<>(), locks = new HashMap<>(), phases = new HashMap<>();
        for (Sample s : mine) {
            StackTraceElement[] st = s.stack;
            if (st.length > 0) leaf.merge(frame(st[0]), 1, Integer::sum);
            for (StackTraceElement e : st) {
                String c = e.getClassName();
                if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.") || c.startsWith("com.sun.")) continue;
                first.merge(frame(e), 1, Integer::sum);
                break;
            }
            states.merge(String.valueOf(s.state), 1, Integer::sum);
            if ((s.state == Thread.State.BLOCKED || s.state == Thread.State.WAITING || s.state == Thread.State.TIMED_WAITING) && s.lock != null)
                locks.merge(shortLock(s.lock) + " owned by " + (s.owner == null ? "?" : s.owner), 1, Integer::sum);
            phases.merge(s.phase < 0 ? "-" : SlowFrames.PHASES[s.phase].label, 1, Integer::sum);
        }
        StringBuilder b = new StringBuilder(" | stacks ").append(mine.size()).append(" samples");
        top(b, "; by phase", phases, 4);
        top(b, "; states", states, 4);
        top(b, "; leaf", leaf, 5);
        top(b, "; first non-JDK", first, 5);
        if (!locks.isEmpty()) top(b, "; waits on", locks, 3);
        return b.toString();
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
}
