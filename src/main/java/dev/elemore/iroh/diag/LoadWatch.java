package dev.elemore.iroh.diag;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;

/**
 * Iroh (rc5, td-30c049): world-load watch. If LevelLoadingScreen stays up longer than {@code -Diroh.loadWatchS} seconds
 * (default 60; 0 = off) the render thread logs ONE report: every thread's state and stack (server thread, C2ME workers,
 * storage and OpenCL threads, light executors) plus Iroh's client chunk state, so a reopen stall leaves its evidence in
 * latest.log without a manual kill -3. Cost when not loading: one instanceof per frame.
 */
public final class LoadWatch {
    private static final Logger LOGGER = LogManager.getLogger("Iroh");
    private static final long LIMIT_NS = Long.getLong("iroh.loadWatchS", 60L) * 1_000_000_000L;
    private static long since;
    private static boolean reported;

    private LoadWatch() {
    }

    /** Render thread, once per frame. */
    public static void tick() {
        if (LIMIT_NS <= 0) return;
        if (!(Minecraft.getInstance().screen instanceof LevelLoadingScreen)) {
            since = 0;
            reported = false;
            return;
        }
        long now = System.nanoTime();
        if (since == 0) since = now;
        if (!reported && now - since > LIMIT_NS) {
            reported = true;
            report((now - since) / 1_000_000_000L);
        }
    }

    private static void report(long seconds) {
        StringBuilder sb = new StringBuilder();
        sb.append("Iroh: world loading screen up for ").append(seconds).append(" s (td-30c049 load watch). Iroh client state: ")
          .append(clientState()).append('\n');
        for (ThreadInfo ti : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            sb.append('"').append(ti.getThreadName()).append("\" ").append(ti.getThreadState());
            if (ti.getLockName() != null) sb.append(" on ").append(ti.getLockName());
            if (ti.getLockOwnerName() != null) sb.append(" held by \"").append(ti.getLockOwnerName()).append('"');
            sb.append('\n');
            StackTraceElement[] st = ti.getStackTrace();
            for (int i = 0; i < Math.min(st.length, 40); i++) sb.append("\tat ").append(st[i]).append('\n');
        }
        LOGGER.error(sb.toString());
    }

    private static String clientState() {
        try {
            var cs = net.vulkanmod.render.chunk.ChunkStatusMap.INSTANCE;
            var waiting = net.vulkanmod.render.chunk.WorldRenderer.rebuildWaiting();
            return "ChunkStatusMap " + (cs == null ? "null" : "present") + ", rebuild parked "
                    + (waiting == null ? "n/a" : waiting.refused + " refused / " + waiting.wakeups + " wakeups")
                    + ", " + net.vulkanmod.vulkan.memory.MemoryManager.liveSummary();
        } catch (Throwable t) {
            return "unavailable (" + t + ")";
        }
    }
}
