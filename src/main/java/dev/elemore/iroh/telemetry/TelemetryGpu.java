package dev.elemore.iroh.telemetry;

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
 * GPU time of the frame command buffer for the Telemetry overlay (card TEL-1): two timestamps per frame slot, one right
 * after the command buffer begins and one just before the main pass ends it. The result of a slot is read when that slot
 * comes round again (its fence was waited), so the value shown is {@code frames in flight} frames old. Also stamps where the
 * render thread starts working on the frame (after the fence wait), which is the CPU half of the CPU / GPU split.
 *
 * Off ({@link #ON} false, the default): the Renderer call sites are one plain static boolean test and nothing else; no query
 * pool exists. Render thread only. UNVERIFIED in game: validation layer clean on the timestamp reset and write.
 */
public final class TelemetryGpu {
    private TelemetryGpu() {}

    /** Set once per frame by {@link TelemetryOverlay}; plain static (read at two Renderer call sites). */
    public static boolean ON;

    private static long pool;
    private static boolean tried, ok;
    private static int slots;
    private static double periodNs;
    private static boolean[] armed;
    private static int cur = -1;
    private static long cpuStartNs;
    private static long lastGpuNs = -1;

    /** The newest completed GPU frame time in ns, or -1 (no timestamps / nothing read yet). */
    public static long lastGpuNs() { return lastGpuNs; }

    /** Render-thread work start of the current frame (System.nanoTime), 0 if not stamped; read once per frame. */
    public static long consumeCpuStart() {
        long t = cpuStartNs;
        cpuStartNs = 0;
        return t;
    }

    /** Forget the last GPU value (the overlay turned GPU timing off). */
    public static void clear() {
        lastGpuNs = -1;
        cpuStartNs = 0;
    }

    /** Right after vkBeginCommandBuffer, before the main pass (resets are not allowed inside a render pass). */
    public static void frameStart(VkCommandBuffer cb, int slot) {
        cpuStartNs = System.nanoTime();
        if (!tried) init();
        if (!ok) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (armed[slot]) {
                LongBuffer res = stack.mallocLong(4);
                int r = vkGetQueryPoolResults(Vulkan.getVkDevice(), pool, slot * 2, 2, res, 16,
                        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
                if ((r == VK_SUCCESS || r == VK_NOT_READY) && res.get(1) != 0 && res.get(3) != 0 && res.get(2) >= res.get(0))
                    lastGpuNs = (long) ((res.get(2) - res.get(0)) * periodNs);
                armed[slot] = false;
            }
            vkCmdResetQueryPool(cb, pool, slot * 2, 2);
            vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, pool, slot * 2);
            cur = slot;
        }
    }

    /** Just before the main pass ends the command buffer. */
    public static void frameEnd(VkCommandBuffer cb) {
        if (!ok || cur < 0) return;
        vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, cur * 2 + 1);
        armed[cur] = true;
        cur = -1;
    }

    private static void init() {
        tried = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            slots = Renderer.getFramesNum();
            armed = new boolean[slots];
            periodNs = DeviceManager.deviceProperties.limits().timestampPeriod();
            VkQueryPoolCreateInfo ci = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(slots * 2);
            LongBuffer p = stack.mallocLong(1);
            if (periodNs <= 0 || vkCreateQueryPool(Vulkan.getVkDevice(), ci, null, p) != VK_SUCCESS) {
                Initializer.LOGGER.info("Iroh telemetry: no GPU timestamps on this device; CPU times only");
                return;
            }
            pool = p.get(0);
            ok = true;
            Initializer.LOGGER.info("Iroh telemetry: GPU frame time from timestamps (period {} ns)", periodNs);
        } catch (Throwable t) {
            Initializer.LOGGER.info("Iroh telemetry: GPU timestamps unavailable ({})", t.toString());
        }
    }

    /** Vulkan.cleanUp (device idle). */
    public static void shutdown() {
        if (pool != 0L) vkDestroyQueryPool(Vulkan.getVkDevice(), pool, null);
        pool = 0L;
        ok = false;
        tried = false;
        cur = -1;
        lastGpuNs = -1;
    }
}
