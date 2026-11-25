package net.vulkanmod.vulkan.shader;

import dev.elemore.iroh.cache.DiskCache;
import dev.elemore.iroh.perf.PerfSwitches;
import dev.elemore.iroh.perf.PrewarmList;
import dev.elemore.iroh.perf.PrewarmList.Entry;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * P2-11 (L8, switch {@code iroh.pipeline.prewarm}, default off). Records the graphics pipeline states first seen in play and, after the
 * next resource reload, creates them on a worker thread before their first bind, so the first sight of a state in flight is a map
 * hit instead of a driver compile inside the frame.
 * <ul>
 *   <li>Record: {@link #record} on every NORMAL pipeline creation (a miss in {@code GraphicsPipeline.getHandle}); list saved to
 *       {@code config/iroh/cache/prewarm.txt} (DiskCache, SHA-256 framed) at most every 30 s and at shutdown.</li>
 *   <li>Use: {@link #kick} when the loading overlay closes (render thread): header check (Iroh build, driver id, mod/pack fingerprint),
 *       then one daemon worker creates each (pipeline name, state, attachment formats) for every live pipeline of that name.</li>
 *   <li>Threading: vkCreateGraphicsPipelines is thread safe and the pipeline cache is internally synchronized (no
 *       EXTERNALLY_SYNCHRONIZED flag). The worker never touches the pipelines' state maps; it parks handles in a per-pipeline table guarded by
 *       a lock that cleanUp also takes. The worker makes its OWN render passes (compatible by attachment formats), so a framebuffer resize
 *       cannot destroy a pass under it.</li>
 * </ul>
 * Off: record / kick return at a volatile read; nothing else changes.
 */
public final class PipelinePrewarm {
    public static final String FILE = "prewarm.txt";
    private static final long SAVE_EVERY_NS = 30_000_000_000L;

    private static final Set<Entry> known = ConcurrentHashMap.newKeySet();
    private static volatile boolean dirty;
    private static volatile long lastSave;
    private static volatile Thread worker;
    private static volatile boolean stop;
    /** the on-disk list has been read (kick ran): until then nothing is saved, or the list written by earlier sessions would be replaced by the few loading-screen states */
    private static volatile boolean listLoaded;
    private static volatile PrewarmList.Header lastHeader;

    private PipelinePrewarm() {}

    static Entry entry(String name, PipelineState s, RenderPass rp) {
        return new Entry(name, s.assemblyRasterState, s.blendState_i, s.depthState_i, s.logicOp_i, s.colorMask_i, rp.colorFormat(), rp.depthFormat(), rp.dependencyKind());
    }

    static PipelineState state(Entry e) {
        return new PipelineState(e.assembly(), e.blend(), e.depth(), e.logicOp(), e.colorMask(), null);
    }

    /** a normal creation happened (render thread, usually): remember the state; saves the list now and then */
    static void record(String name, PipelineState s, RenderPass rp) {
        if (!PerfSwitches.pipelinePrewarmOn) return;
        Entry e = entry(name, s, rp);
        if (!e.valid() || known.size() >= PrewarmList.MAX_ENTRIES) return;
        if (known.add(e)) {
            dirty = true;
            long now = System.nanoTime();
            if (now - lastSave > SAVE_EVERY_NS) save();
        }
    }

    /** the list as it stands (tests, report) */
    public static int knownCount() {
        return known.size();
    }

    // ================================================================ header

    /** Iroh build + driver id + mod/resource-pack fingerprint. Render thread (reads Minecraft.options). */
    static PrewarmList.Header header() {
        String build = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("iroh")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        var p = DeviceManager.deviceProperties;
        StringBuilder uuid = new StringBuilder();
        for (int i = 0; i < VK_UUID_SIZE; i++) uuid.append(String.format("%02x", p.pipelineCacheUUID(i)));
        String driver = PrewarmList.driverId(p.vendorID(), p.deviceID(), p.driverVersion(), p.apiVersion(), uuid.toString());
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (var m : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
            String s = m.getMetadata().getId() + "@" + m.getMetadata().getVersion().getFriendlyString();
            try {
                for (var path : m.getOrigin().getPaths()) {
                    if (java.nio.file.Files.isRegularFile(path))
                        s += ":" + java.nio.file.Files.size(path) + ":" + java.nio.file.Files.getLastModifiedTime(path).toMillis();
                }
            } catch (Exception ignored) {
            }
            parts.add(s);
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.options != null) {
            for (String rp : mc.options.resourcePacks) parts.add("rp:" + rp);
            for (String rp : mc.options.incompatibleResourcePacks) parts.add("irp:" + rp);
        }
        return new PrewarmList.Header(build, driver, PrewarmList.fingerprint(parts));
    }

    // ================================================================ save / load

    /** Writes the list (at most MAX_ENTRIES). Never throws. */
    public static void save() {
        lastSave = System.nanoTime();
        if (!dirty || !listLoaded) return;
        try {
            PrewarmList.Header h = lastHeader != null ? lastHeader : header();
            lastHeader = h;
            DiskCache.write(FILE, PrewarmList.encode(h, known));
            dirty = false;
        } catch (Throwable t) {
            Initializer.LOGGER.warn("Iroh: pipeline pre-warm list not saved: {}", t.toString());
        }
    }

    // ================================================================ pre-warm

    /**
     * Render thread, when the loading overlay closes. Reads the list, then starts the worker. A list written under another build /
     * driver / pack is dropped (and replaced by what this session records).
     */
    public static void kick() {
        if (!PerfSwitches.pipelinePrewarmOn || worker != null) return;
        try {
            PrewarmList.Header h = header();
            lastHeader = h;
            PrewarmList.Loaded loaded = PrewarmList.decode(DiskCache.read(FILE), h);
            if (!loaded.accepted()) {
                Initializer.LOGGER.info("Iroh: pipeline pre-warm list not used: {}", loaded.reason());
                listLoaded = true; // what this session recorded replaces the stale list at the first save
                dirty = true;
                return;
            }
            known.addAll(loaded.entries());
            listLoaded = true;
            dirty = true;
            List<GraphicsPipeline> pipelines = GraphicsPipeline.snapshot();
            if (loaded.entries().isEmpty() || pipelines.isEmpty()) return;
            stop = false;
            Thread t = new Thread(() -> run(loaded.entries(), pipelines), "iroh-pipeline-prewarm");
            t.setDaemon(true);
            worker = t;
            t.start();
        } catch (Throwable t) {
            worker = null;
            Initializer.LOGGER.warn("Iroh: pipeline pre-warm not started: {}", t.toString());
        }
    }

    private static void run(List<Entry> entries, List<GraphicsPipeline> pipelines) {
        long t0 = System.nanoTime();
        int made = 0, tried = 0;
        java.util.Map<String, Long> passes = new java.util.HashMap<>();
        try {
            java.util.Map<String, List<GraphicsPipeline>> byName = new java.util.HashMap<>();
            for (GraphicsPipeline g : pipelines) byName.computeIfAbsent(g.name, k -> new java.util.ArrayList<>()).add(g);
            for (Entry e : entries) {
                if (stop) break;
                List<GraphicsPipeline> gs = byName.get(e.pipeline());
                if (gs == null || e.colorFormat() == 0) continue; // unknown pipeline now; colour-less pass: never used by the renderer
                long rp = passes.computeIfAbsent(passKey(e), k -> compatiblePass(e.colorFormat(), e.depthFormat(), e.passKind()));
                if (rp == 0L) continue;
                for (GraphicsPipeline g : gs) {
                    tried++;
                    if (g.prewarm(e, rp)) made++;
                }
            }
        } catch (Throwable t) {
            Initializer.LOGGER.warn("Iroh: pipeline pre-warm stopped: {}", t.toString());
        } finally {
            passes.values().forEach(rp -> { if (rp != 0L) vkDestroyRenderPass(DeviceManager.vkDevice, rp, null); });
            Initializer.LOGGER.info("Iroh: pipeline pre-warm: {} of {} states created in {} ms (list {})", made, tried, (System.nanoTime() - t0) / 1_000_000, entries.size());
            worker = null;
        }
    }

    private static String passKey(Entry e) {
        return e.colorFormat() + "/" + e.depthFormat() + "/" + e.passKind();
    }

    /**
     * A render pass COMPATIBLE with the renderer's (same attachment formats, 1 sample, colour at 0 and depth after it, the same subpass dependency;
     * only layouts and load / store ops may differ: the pass in lavapipe validation said "dependencyCount is incompatible" for a pass without the
     * dependency, PipelinePrewarmRepro). Owned by the worker, destroyed when the run ends.
     */
    public static long compatiblePass(int colorFormat, int depthFormat, int dependencyKind) {
        try (MemoryStack stack = stackPush()) {
            int n = depthFormat != 0 ? 2 : 1;
            VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(n, stack);
            att.get(0).format(colorFormat).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer sub = VkSubpassDescription.calloc(1, stack);
            sub.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS);
            sub.colorAttachmentCount(1);
            sub.pColorAttachments(VkAttachmentReference.calloc(1, stack).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL));
            if (depthFormat != 0) {
                att.get(1).format(depthFormat).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                sub.pDepthStencilAttachment(VkAttachmentReference.calloc(stack).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL));
            }
            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack);
            info.sType$Default().pAttachments(att).pSubpasses(sub);
            if (dependencyKind == 1) { // as RenderPass.createRenderPass for a PRESENT_SRC colour attachment
                info.pDependencies(VkSubpassDependency.calloc(1, stack).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                        .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstStageMask(VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT).srcAccessMask(0).dstAccessMask(0));
            } else if (dependencyKind == 2) { // SHADER_READ_ONLY
                info.pDependencies(VkSubpassDependency.calloc(1, stack).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                        .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                        .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT));
            }
            java.nio.LongBuffer p = stack.mallocLong(1);
            return vkCreateRenderPass(DeviceManager.vkDevice, info, null, p) == VK_SUCCESS ? p.get(0) : 0L;
        }
    }

    /** shutdown, before the pipeline cache and device go: stop the worker (bounded wait), then save the list */
    public static void shutdown() {
        stop = true;
        Thread t = worker;
        if (t != null) {
            try {
                t.join(20_000);
                if (t.isAlive()) Initializer.LOGGER.error("Iroh: pipeline pre-warm worker still running at shutdown after 20 s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (PerfSwitches.pipelinePrewarmOn) save();
    }
}
