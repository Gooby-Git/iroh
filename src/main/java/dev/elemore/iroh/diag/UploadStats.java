package dev.elemore.iroh.diag;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Iroh (rc5, td-1cb256 perf part): {@code -Diroh.uploadStats=true} counts the texture upload path and logs one
 * {@code [iroh-upload]} line every 5 s: async uploads (into the shared upload command buffer), in-frame uploads (private
 * staging buffer + render pass break each), upload-buffer submits, barrier calls at submit and images they cover, staging
 * overflows (each = submit + full fence wait mid-frame), frame-end fence waits. Off: one static-final test per site.
 */
public final class UploadStats {
    public static final boolean ON = Boolean.getBoolean("iroh.uploadStats");
    private static final Logger LOGGER = LogManager.getLogger("Iroh");
    public static long asyncUploads, inFrameUploads, submits, submitBarrierCalls, submitBarrierImages, stagingOverflows, frameFenceWaits, spillBlocks, bytes;
    // perf/uploads: chunk path
    public static long chunkBytes, workerStagedBytes, chunkUploadNs, sectionsUploaded, sectionsDeferred, inFrameFlushes;
    /** Render-thread section-upload time of the current frame (TaskDispatcher.updateSections). */
    public static long frameUploadNs;
    private static long frames, uploadNsSum, uploadNsMax, fenceWaits0, fenceWaitNs0;

    /** Per frame (flushConsumers): closes this frame's render-thread upload time. */
    private static void endFrameTimes() {
        frames++;
        uploadNsSum += frameUploadNs;
        if (frameUploadNs > uploadNsMax) uploadNsMax = frameUploadNs;
        frameUploadNs = 0;
    }
    private static final java.util.HashMap<String, long[]> BY_TEXTURE = new java.util.HashMap<>(), BY_CALLER = new java.util.HashMap<>();
    private static long sampleTick;
    private static final int CALLER_SAMPLE = 16;

    /** rc6: one upload (render thread): bytes and count per texture; caller mod sampled 1 in 16 (stack walk). */
    public static void upload(net.vulkanmod.vulkan.texture.VulkanImage image, long size) {
        bytes += size;
        long[] t = BY_TEXTURE.computeIfAbsent(image.name == null ? "?" : image.name, k -> new long[2]);
        t[0]++;
        t[1] += size;
        if (sampleTick++ % CALLER_SAMPLE == 0) {
            long[] c = BY_CALLER.computeIfAbsent(EmuStats.caller() + " -> " + (image.name == null ? "?" : image.name.replaceAll("[0-9]+", "#")), k -> new long[2]);
            c[0] += CALLER_SAMPLE;
            c[1] += size * CALLER_SAMPLE;
        }
    }

    private static String top(java.util.HashMap<String, long[]> m) {
        StringBuilder sb = new StringBuilder();
        m.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(10)
                .forEach(e -> sb.append("\n    ").append(e.getValue()[0]).append(" x, ").append(e.getValue()[1] >> 10).append(" KB  ").append(e.getKey()));
        return sb.toString();
    }
    private static long last;

    private UploadStats() {
    }

    /** Render thread, once per frame. */
    public static void frame() {
        endFrameTimes();
        long now = System.nanoTime();
        if (last == 0) last = now;
        if (now - last < 5_000_000_000L) return;
        last = now;
        long copies = net.vulkanmod.render.texture.ImageUploadHelper.INSTANCE.copyCommands;
        LOGGER.info("[iroh-upload] 5 s: textures: async {} in-frame {} ({} MB) | copy commands {} | upload submits {}, read-only barrier calls {} covering {} images | staging budget waits {}, spill blocks {} | frame fence waits {}"
                        + "\n  top textures (count, KB):{}\n  top callers (sampled 1/{}):{}",
                asyncUploads, inFrameUploads, bytes >> 20, copies, submits, submitBarrierCalls, submitBarrierImages, stagingOverflows, spillBlocks,
                frameFenceWaits, top(BY_TEXTURE), CALLER_SAMPLE, top(BY_CALLER));
        var sync = net.vulkanmod.vulkan.Synchronization.INSTANCE;
        var ring = net.vulkanmod.vulkan.memory.buffer.StagingBuffer.ring();
        LOGGER.info("[iroh-upload] chunks: {} sections, {} MB ({} MB staged by workers), {} deferred to the next frame | render-thread upload ms/frame mean {} max {} over {} frames"
                        + " | in-frame flushes {} | staging ring {} MB, blocks live {} pooled {}, budget misses {} | render-thread fence waits {} ({} ms)",
                sectionsUploaded, chunkBytes >> 20, workerStagedBytes >> 20, sectionsDeferred,
                frames == 0 ? "0" : String.format("%.3f", uploadNsSum / 1e6 / frames), String.format("%.3f", uploadNsMax / 1e6), frames,
                inFrameFlushes, ring == null ? 0 : ring.totalBytes() >> 20, ring == null ? 0 : ring.liveBlocks(), ring == null ? 0 : ring.pooledBlocks(),
                ring == null ? 0 : ring.statBudgetMisses, sync.statFenceWaits - fenceWaits0, String.format("%.2f", (sync.statFenceWaitNs - fenceWaitNs0) / 1e6));
        fenceWaits0 = sync.statFenceWaits;
        fenceWaitNs0 = sync.statFenceWaitNs;
        chunkBytes = workerStagedBytes = chunkUploadNs = sectionsUploaded = sectionsDeferred = inFrameFlushes = 0;
        frames = uploadNsSum = uploadNsMax = 0;
        net.vulkanmod.render.texture.ImageUploadHelper.INSTANCE.copyCommands = 0;
        asyncUploads = inFrameUploads = submits = submitBarrierCalls = submitBarrierImages = stagingOverflows = frameFenceWaits = spillBlocks = bytes = 0;
        BY_TEXTURE.clear();
        BY_CALLER.clear();
    }
}
