package dev.elemore.iroh.perf;

import org.lwjgl.glfw.GLFW;

/**
 * P2-10 (L7): precise frame limiter, replaces vanilla {@code RenderSystem.limitDisplayFPS} (a glfwGetTime loop that
 * re-enters glfwWaitEventsTimeout on every early wake and ends late by the wake latency, never repaid). Entry point stays
 * {@code RenderSystem.limitDisplayFPS(I)V} (see LimiterM) because Proctor's busy-time hook and TaskBudget time the call.
 *
 * <p>Deadline math ({@link #plan}) is pure longs for the offline test: the deadline advances by exactly one period per
 * frame, so wake latency is repaid by the next frame (drift correction); a frame late by more than one period resets
 * the deadline to now (no catch-up burst after a stall). Waiting ({@link #await}): glfwWaitEventsTimeout (keeps event
 * processing exactly like vanilla) until deadline - {@link #SPIN_NS}, then Thread.onSpinWait on nanoTime.
 * {@code -Diroh.limiter.vanilla=true} restores the vanilla loop.
 */
public final class FrameLimiter {
    public static final long SPIN_NS = 1_000_000L;
    public static final int UNLIMITED_FPS = 260;
    public static final boolean VANILLA = Boolean.getBoolean("iroh.limiter.vanilla");
    private static final FrameLimiter GAME = new FrameLimiter();

    private long deadline; // 0 = unset (first frame, or after an unlimited cap)

    /** end time this frame must not precede, given the frame's work finished at {@code now}; returns {@code now} for no wait */
    public long plan(long now, int fps) {
        if (fps <= 0 || fps >= UNLIMITED_FPS) {
            deadline = 0;
            return now;
        }
        return plan(now, 1_000_000_000L / fps);
    }

    public long plan(long now, long period) {
        if (deadline == 0) {
            deadline = now;
            return now;
        }
        long target = deadline + period;
        if (now - target > period) { // late by more than one period: stall, do not burst to catch up
            deadline = now;
            return now;
        }
        if (target > now + period) target = now + period; // cap lowered / clock oddity: never wait more than one period
        deadline = target;
        return Math.max(target, now);
    }

    /** the RenderSystem.limitDisplayFPS replacement, render thread */
    public static void limit(int fps) {
        long now = System.nanoTime();
        long target = GAME.plan(now, fps);
        if (target > now) await(target);
    }

    static void await(long target) {
        long coarse = target - SPIN_NS;
        long now;
        while ((now = System.nanoTime()) < coarse) GLFW.glfwWaitEventsTimeout((coarse - now) / 1e9);
        while (System.nanoTime() < target) Thread.onSpinWait();
    }

    FrameLimiter() {}

}
