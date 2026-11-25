package dev.elemore.iroh.diag;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaBudget;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

/**
 * Iroh (rc7): {@code -Diroh.memStats=true} logs one {@code [iroh-mem]} block every 30 s, so a long soak shows a plateau or
 * a leak and WHERE: Java heap / non-heap / direct buffers, VMA budget and usage per memory heap (blocks, allocations,
 * bytes), Iroh's registered buffers and images, chunk-area vertex/index buffers (used / allocated / fragmentation), and
 * the size of every Iroh map that is keyed by chunk, section, class or texture. Off: one static-final test per frame.
 */
public final class MemStats {
    public static final boolean ON = Boolean.getBoolean("iroh.memStats");
    private static final Logger LOGGER = LogManager.getLogger("Iroh");
    private static long last;

    private MemStats() {
    }

    /** Render thread, once per frame. */
    public static void frame() {
        long now = System.nanoTime();
        if (last != 0 && now - last < 30_000_000_000L) return;
        last = now;
        try {
            LOGGER.info(report());
        } catch (Throwable t) {
            LOGGER.warn("[iroh-mem] report failed", t);
        }
    }

    static String report() {
        StringBuilder sb = new StringBuilder("[iroh-mem] ");
        var mx = ManagementFactory.getMemoryMXBean();
        MemoryUsage h = mx.getHeapMemoryUsage(), nh = mx.getNonHeapMemoryUsage();
        sb.append("heap used ").append(h.getUsed() >> 20).append(" / committed ").append(h.getCommitted() >> 20).append(" MB, non-heap ")
          .append(nh.getUsed() >> 20).append(" MB");
        for (BufferPoolMXBean p : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))
            sb.append(", ").append(p.getName()).append(" ").append(p.getMemoryUsed() >> 20).append(" MB (").append(p.getCount()).append(")");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var props = net.vulkanmod.vulkan.device.DeviceManager.memoryProperties;
            int heaps = props == null ? 0 : props.memoryHeapCount();
            if (heaps > 0) {
                VmaBudget.Buffer budgets = VmaBudget.calloc(16, stack);
                Vma.vmaGetHeapBudgets(net.vulkanmod.vulkan.Vulkan.getAllocator(), budgets);
                for (int i = 0; i < heaps; i++) {
                    VmaBudget b = budgets.get(i);
                    sb.append("\n  vma heap ").append(i).append(": usage ").append(b.usage() >> 20).append(" / budget ").append(b.budget() >> 20)
                      .append(" MB, blocks ").append(b.statistics().blockCount()).append(" (").append(b.statistics().blockBytes() >> 20)
                      .append(" MB), allocations ").append(b.statistics().allocationCount()).append(" (").append(b.statistics().allocationBytes() >> 20).append(" MB)");
                }
            }
        }
        sb.append("\n  memory guard: ").append(dev.elemore.iroh.memory.MemoryGuard.statsLine());
        sb.append("\n  ").append(dev.elemore.iroh.perf.AreaArena.statsLine()); // P2-4
        sb.append("\n  ").append(dev.elemore.iroh.perf.PipelineStats.line()); // P2-11
        sb.append("\n  iroh objects: ").append(net.vulkanmod.vulkan.memory.MemoryManager.liveSummary());
        if (ImageTrace.ON) sb.append("\n  ").append(ImageTrace.report()); // rc13
        var gpuCore = dev.elemore.iroh.gpu.GpuCore.orNull(); // td-033cca: api 4 upload routing / pass breaks / hazards (same 30 s limit)
        if (gpuCore != null) sb.append("\n  ").append(gpuCore.summary());
        var wr = net.vulkanmod.render.chunk.WorldRenderer.getInstance();
        if (wr != null && wr.getChunkAreaManager() != null)
            sb.append("\n  chunk areas: ").append(String.join(", ", wr.getChunkAreaManager().getStats()));
        var cs = net.vulkanmod.render.chunk.ChunkStatusMap.INSTANCE;
        var waiting = net.vulkanmod.render.chunk.WorldRenderer.rebuildWaiting();
        sb.append("\n  maps: ChunkStatusMap ").append(cs == null ? 0 : cs.size())
          .append(", rebuild parked chunks ").append(waiting == null ? 0 : waiting.parkedChunks())
          .append(", ModAttribution ").append(ModAttribution.cacheSize())
          .append(", upload helper: ").append(net.vulkanmod.render.texture.ImageUploadHelper.INSTANCE.memSummary());
        return sb.toString();
    }
}
