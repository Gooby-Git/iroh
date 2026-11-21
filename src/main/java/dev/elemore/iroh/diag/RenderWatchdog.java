package dev.elemore.iroh.diag;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * Iroh (rc3r2, td-3bc9d5): makes a stalled render thread self-explaining. {@link #heartbeat()} is called at the start of
 * every frame (Renderer.beginFrame, also for frames that are skipped, so a minimised window is not a stall). A daemon
 * thread checks once a second; when no heartbeat came for 10 s, and again at 30 s, 60 s and every 60 s after, it logs
 * {@code Iroh: render thread has not completed a frame for N s} with the render thread's stack, any JVM-detected
 * monitor / ownable-synchronizer deadlock with every involved thread's stack, and the threads the render thread is
 * waiting on (lock owner). A heartbeat after a stall logs how long it lasted. {@code -Diroh.watchdog=false} turns it off.
 */
public final class RenderWatchdog {
    private static final Logger LOGGER = LogManager.getLogger("Iroh");
    private static final boolean ENABLED = !"false".equals(System.getProperty("iroh.watchdog"));

    private static volatile long lastBeat;
    private static volatile Thread renderThread;
    private static volatile boolean started;
    private static volatile int reportsInStall;

    private RenderWatchdog() {
    }

    /** Render thread, start of every frame. Cheap: one volatile write after the first call. */
    public static void heartbeat() {
        if (!ENABLED) return;
        long now = System.nanoTime();
        if (!started) start();
        if (reportsInStall > 0) {
            LOGGER.warn("Iroh: render thread resumed after a {} s stall", (now - lastBeat) / 1_000_000_000L);
            reportsInStall = 0;
        }
        lastBeat = now;
    }

    private static synchronized void start() {
        if (started) return;
        renderThread = Thread.currentThread();
        lastBeat = System.nanoTime();
        Thread t = new Thread(RenderWatchdog::run, "Iroh render watchdog");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        started = true;
    }

    private static void run() {
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            long stalledS = (System.nanoTime() - lastBeat) / 1_000_000_000L;
            long next = reportsInStall == 0 ? 10 : reportsInStall == 1 ? 30 : 60L * (reportsInStall - 1);
            if (stalledS >= next) {
                reportsInStall++;
                try {
                    report(stalledS);
                } catch (Throwable t) {
                    LOGGER.warn("Iroh: watchdog report failed", t);
                }
            }
        }
    }

    private static void report(long stalledS) {
        Thread rt = renderThread;
        StringBuilder sb = new StringBuilder();
        sb.append("Iroh: render thread has not completed a frame for ").append(stalledS).append(" s (state ")
          .append(rt == null ? "?" : rt.getState()).append(")\n");
        if (rt != null)
            for (StackTraceElement e : rt.getStackTrace()) sb.append("\tat ").append(e).append('\n');

        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        if (rt != null) {
            ThreadInfo ri = mx.getThreadInfo(new long[]{rt.threadId()}, true, true)[0];
            if (ri != null && ri.getLockName() != null) {
                sb.append("render thread waits on ").append(ri.getLockName());
                if (ri.getLockOwnerName() != null) {
                    sb.append(" held by \"").append(ri.getLockOwnerName()).append("\"\n");
                    ThreadInfo owner = mx.getThreadInfo(new long[]{ri.getLockOwnerId()}, true, true)[0];
                    if (owner != null) for (StackTraceElement e : owner.getStackTrace()) sb.append("\tat ").append(e).append('\n');
                } else sb.append('\n');
            }
        }
        long[] dead = mx.findDeadlockedThreads();
        if (dead != null) {
            sb.append("JVM-detected deadlock between ").append(dead.length).append(" threads:\n");
            for (ThreadInfo ti : mx.getThreadInfo(dead, true, true)) {
                if (ti == null) continue;
                sb.append('"').append(ti.getThreadName()).append("\" waits on ").append(ti.getLockName())
                  .append(" held by \"").append(ti.getLockOwnerName()).append("\"\n");
                for (StackTraceElement e : ti.getStackTrace()) sb.append("\tat ").append(e).append('\n');
            }
        }
        LOGGER.error(sb.toString());
    }
}
