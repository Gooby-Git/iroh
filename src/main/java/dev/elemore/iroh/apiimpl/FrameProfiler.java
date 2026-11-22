package dev.elemore.iroh.apiimpl;

import dev.elemore.iroh.api.FrameStage;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Iroh (P1-13a): {@code -Diroh.profile=true}, first version of the Phase 2 frame profiler. Time is split at the
 * {@link FrameStage} points (the segment "A to B" is charged to B: everything drawn since the previous stage, plus B's
 * hooks). CPU: System.nanoTime at each point. GPU: a timestamp query written at each point into a per-frame-slot query
 * pool (vkCmdWriteTimestamp, bottom of pipe), read back without waiting when that slot's fence has signalled (two frames
 * later), so it never stalls. Every 5 s one line: {@code [iroh-profile] frames=N cpu ms/frame <stage>=x ... | gpu ...}.
 * Off: one static boolean test per stage point.
 */
public final class FrameProfiler {
    public static final boolean ON = Boolean.getBoolean("iroh.profile");
    private static final FrameStage[] STAGES = FrameStage.values();
    private static final int N = STAGES.length;

    private static final long[] cpuNs = new long[N];
    private static final double[] gpuMs = new double[N];
    private static int prevStage = -1;
    private static long prevT, windowStart;
    private static int frames, gpuFrames;

    // GPU
    private static long pool;
    private static boolean gpuOk;
    private static int slots;
    private static double periodNs;
    private static int[] writtenMask;
    private static int currentSlot = -1;

    private FrameProfiler() {
    }

    /** A stage point (render thread). */
    static void mark(FrameStage stage) {
        long now = System.nanoTime();
        int s = stage.ordinal();
        if (prevStage >= 0) cpuNs[s] += now - prevT;
        prevStage = stage == FrameStage.AFTER_PRESENT ? -1 : s;
        if (stage == FrameStage.AFTER_PRESENT) {
            frames++;
            maybeLog(now);
        } else if (gpuOk && currentSlot >= 0) {
            VkCommandBuffer cb = Renderer.recordingCommandBuffer("profiler timestamp");
            if (cb != null) {
                vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, currentSlot * N + s);
                writtenMask[currentSlot] |= 1 << s;
            }
        }
        // rc7: the next segment starts AFTER the profiler's own work (timestamp write, logging), so no stage is charged
        // for the profiler.
        prevT = System.nanoTime();
    }

    /**
     * Frame start, right after vkBeginCommandBuffer and BEFORE the main pass begins (query resets are not allowed inside a
     * render pass). The slot's previous results are complete: its fence was waited in beginFrame. Reads them, then resets.
     */
    public static void frameStart(VkCommandBuffer cb, int frameSlot) {
        if (!ON) return;
        if (pool == 0L && !init()) return;
        if (!gpuOk) return;
        readSlot(frameSlot);
        vkCmdResetQueryPool(cb, pool, frameSlot * N, N);
        writtenMask[frameSlot] = 0;
        currentSlot = frameSlot;
    }

    private static boolean init() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            slots = Renderer.getFramesNum();
            writtenMask = new int[slots];
            periodNs = DeviceManager.deviceProperties.limits().timestampPeriod();
            VkQueryPoolCreateInfo ci = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(slots * N);
            LongBuffer p = stack.mallocLong(1);
            if (periodNs <= 0 || vkCreateQueryPool(Vulkan.getVkDevice(), ci, null, p) != VK_SUCCESS) {
                Initializer.LOGGER.info("Iroh profile: no GPU timestamps on this device; CPU times only");
                pool = -1L;
                return false;
            }
            pool = p.get(0);
            gpuOk = true;
            Initializer.LOGGER.info("Iroh profile: CPU + GPU stage times every 5 s (timestampPeriod {} ns)", periodNs);
            return true;
        } catch (Throwable t) {
            pool = -1L;
            return false;
        }
    }

    private static void readSlot(int slot) {
        int mask = writtenMask[slot];
        if (mask == 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer res = stack.mallocLong(N * 2);
            int r = vkGetQueryPoolResults(Vulkan.getVkDevice(), pool, slot * N, N, res, 16,
                    VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
            if (r != VK_SUCCESS && r != VK_NOT_READY) return;
            long prev = -1;
            for (int s = 0; s < N; s++) {
                if ((mask & (1 << s)) == 0 || res.get(s * 2 + 1) == 0) continue;
                long t = res.get(s * 2);
                if (prev >= 0 && t >= prev) gpuMs[s] += (t - prev) * periodNs / 1e6;
                prev = t;
            }
            gpuFrames++;
        }
    }

    private static void maybeLog(long now) {
        if (windowStart == 0) windowStart = now;
        if (now - windowStart < 5_000_000_000L || frames == 0) return;
        StringBuilder sb = new StringBuilder("[iroh-profile] frames=").append(frames).append(" cpu ms/frame");
        for (int s = 0; s < N; s++) if (cpuNs[s] > 0) sb.append(' ').append(STAGES[s]).append('=').append(String.format("%.3f", cpuNs[s] / 1e6 / frames));
        if (gpuOk && gpuFrames > 0) {
            sb.append(" | gpu ms/frame");
            for (int s = 0; s < N; s++) if (gpuMs[s] > 0) sb.append(' ').append(STAGES[s]).append('=').append(String.format("%.3f", gpuMs[s] / gpuFrames));
        }
        dev.elemore.iroh.gpu.GpuCore core = dev.elemore.iroh.gpu.GpuCore.orNull(); // api 4.1: per-owner GPU passes
        if (core != null) {
            String owners = core.profileLine();
            if (!owners.isEmpty()) sb.append(" | api4 ").append(owners);
        }
        Initializer.LOGGER.info(sb.toString());
        java.util.Arrays.fill(cpuNs, 0);
        java.util.Arrays.fill(gpuMs, 0);
        frames = gpuFrames = 0;
        windowStart = now;
    }
}
