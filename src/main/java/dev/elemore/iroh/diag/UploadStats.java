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
    public static long asyncUploads, inFrameUploads, submits, submitBarrierCalls, submitBarrierImages, stagingOverflows, frameFenceWaits;
    private static long last;

    private UploadStats() {
    }

    /** Render thread, once per frame. */
    public static void frame() {
        long now = System.nanoTime();
        if (last == 0) last = now;
        if (now - last < 5_000_000_000L) return;
        last = now;
        LOGGER.info("[iroh-upload] 5 s: async {} in-frame {} | upload submits {}, read-only barrier calls {} covering {} images | staging overflows {} | frame fence waits {}",
                asyncUploads, inFrameUploads, submits, submitBarrierCalls, submitBarrierImages, stagingOverflows, frameFenceWaits);
        asyncUploads = inFrameUploads = submits = submitBarrierCalls = submitBarrierImages = stagingOverflows = frameFenceWaits = 0;
    }
}
