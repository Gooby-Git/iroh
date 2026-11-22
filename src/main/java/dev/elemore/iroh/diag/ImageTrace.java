package dev.elemore.iroh.diag;

import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Iroh (rc13, td-598433): {@code -Diroh.imageTrace=<owner substring>} follows every GL texture image whose owner or name
 * contains the substring (case-insensitive; {@code *} = all) through its whole life, so one launch answers "who keeps
 * these images alive":
 * <ul>
 *   <li>creation: GL id, size, Vulkan format, frame number and a 12-frame stack;</li>
 *   <li>every delete REQUEST for a traced GL id: the API it came through (first caller outside the emulation), whether a
 *       texture / image existed, and where the image went (deferred free with its epoch, or nothing);</li>
 *   <li>re-specification (storage replaced, old image freed);</li>
 *   <li>the deferred-free queueing (epoch tag + completed epoch) and the actual Vulkan destroy;</li>
 *   <li>with {@code -Diroh.memStats}: the live traced GL ids with their age in frames.</li>
 * </ul>
 * Off (no property): every hook is behind the static-final {@link #ON}; nothing is allocated.
 */
public final class ImageTrace {
    public static final String FILTER = System.getProperty("iroh.imageTrace", "");
    public static final boolean ON = !FILTER.isEmpty();
    private static final String NEEDLE = FILTER.toLowerCase(java.util.Locale.ROOT);

    /** Output; replaceable for the offline test. */
    public static Consumer<String> SINK = s -> org.apache.logging.log4j.LogManager.getLogger("Iroh").info(s);

    private static long frame;

    private record Rec(String name, String owner, int width, int height, int format, long createdFrame, VulkanImage image) {}

    private static final Map<Integer, Rec> LIVE = new LinkedHashMap<>();
    /** P3-4 G1 fix: images with no GL name (pack render targets {@code pack:<target>}), by identity */
    private static final Map<VulkanImage, Rec> LIVE_RAW = new java.util.IdentityHashMap<>();
    private static long created, deleteRequests, queued, destroyed, respecified;

    private ImageTrace() {}

    /** Renderer: once per frame. */
    public static void frame() {
        frame++;
    }

    public static boolean matches(String owner, String name) {
        if (!ON) return false;
        if (NEEDLE.equals("*")) return true;
        return (owner != null && owner.toLowerCase(java.util.Locale.ROOT).contains(NEEDLE))
                || (name != null && name.toLowerCase(java.util.Locale.ROOT).contains(NEEDLE));
    }

    /** VkGlTexture.allocateImage, after the new image exists. {@code old} = the image it replaces (re-specification). */
    public static synchronized void created(int glId, VulkanImage image, VulkanImage old, int width, int height, int vkFormat) {
        if (image == null) return;
        boolean match = matches(image.owner, image.name);
        if (old != null && old.traced) {
            respecified++;
            SINK.accept("[iroh-trace] respecify GL " + glId + ": old image " + old.name + " freed, now " + width + "x" + height
                    + " fmt " + vkFormat + " (frame " + frame + ")");
        }
        if (!match) {
            if (old != null && old.traced) LIVE.remove(glId);
            return;
        }
        image.traced = true;
        created++;
        LIVE.put(glId, new Rec(image.name, image.owner, width, height, vkFormat, frame, image));
        SINK.accept("[iroh-trace] create GL " + glId + " " + width + "x" + height + " fmt " + vkFormat + " owner '" + image.owner
                + "' frame " + frame + "\n" + stack(12));
    }

    /** An image created outside the GL emulation (pack targets): traced from creation to the Vulkan destroy. */
    public static synchronized void createdImage(VulkanImage image) {
        if (image == null || !matches(image.owner, image.name)) return;
        image.traced = true;
        created++;
        LIVE_RAW.put(image, new Rec(image.name, image.owner, image.width, image.height, image.format, frame, image));
        SINK.accept("[iroh-trace] create " + image.name + " " + image.width + "x" + image.height + " fmt " + image.format
                + " owner '" + image.owner + "' frame " + frame);
    }

    /**
     * VkGlTexture.glDeleteTextures, after the name left the map. Logs only live traced ids (a second delete of the same
     * name is not logged: the name is no longer traced).
     */
    public static synchronized void deleteRequest(int glId, boolean textureExisted, VulkanImage image) {
        Rec r = LIVE.remove(glId);
        boolean traced = r != null || (image != null && image.traced);
        if (!traced) return;
        deleteRequests++;
        SINK.accept("[iroh-trace] delete GL " + glId + " via " + api() + ": texture " + (textureExisted ? "existed" : "MISSING")
                + ", image " + (image == null ? "none" : image.name + (image.getId() == 0L && image.traced ? " (no device image)" : ""))
                + (r != null ? ", age " + (frame - r.createdFrame) + " frames" : "") + " (frame " + frame + ")");
    }

    /** MemoryManager.addToFreeable / the free hook: where a traced image went. */
    public static synchronized void queued(VulkanImage image, String how) {
        if (image == null || !image.traced) return;
        queued++;
        SINK.accept("[iroh-trace] queued " + image.name + " for " + how + " (frame " + frame + ")");
    }

    /** VulkanImage.doFree: the Vulkan image is destroyed now. */
    public static synchronized void destroyed(VulkanImage image) {
        if (image == null || !image.traced) return;
        destroyed++;
        LIVE_RAW.remove(image);
        SINK.accept("[iroh-trace] destroyed " + image.name + " (frame " + frame + ")");
    }

    /** -Diroh.memStats line: counters and the live traced GL ids, oldest first, with their age in frames. */
    public static synchronized String report() {
        StringBuilder sb = new StringBuilder("image trace '").append(FILTER).append("': created ").append(created)
                .append(", delete requests ").append(deleteRequests).append(", respecified ").append(respecified)
                .append(", queued ").append(queued).append(", destroyed ").append(destroyed).append(", live ").append(LIVE.size() + LIVE_RAW.size());
        if (!LIVE_RAW.isEmpty()) {
            sb.append(" (non-GL ").append(LIVE_RAW.size()).append(':');
            int n = 0;
            for (Rec r : LIVE_RAW.values())
                if (n++ < 16) sb.append(' ').append(r.name);
            sb.append(n > 16 ? " ...)" : ")");
        }
        // age buckets, then the oldest and newest 16 (a leak shows as a growing old bucket)
        int young = 0, mid = 0, old = 0;
        for (Rec r : LIVE.values()) {
            long age = frame - r.createdFrame;
            if (age < 600) young++; else if (age < 36000) mid++; else old++;
        }
        sb.append(" (age <600 frames ").append(young).append(", <36000 ").append(mid).append(", older ").append(old).append(')');
        java.util.List<Map.Entry<Integer, Rec>> all = new java.util.ArrayList<>(LIVE.entrySet());
        appendIds(sb, "oldest", all.subList(0, Math.min(16, all.size())));
        if (all.size() > 16) appendIds(sb, "newest", all.subList(Math.max(16, all.size() - 16), all.size()));
        return sb.toString();
    }

    private static void appendIds(StringBuilder sb, String label, java.util.List<Map.Entry<Integer, Rec>> ids) {
        sb.append("; ").append(label).append(':');
        for (Map.Entry<Integer, Rec> e : ids) {
            Rec r = e.getValue();
            sb.append(" GL ").append(e.getKey()).append(' ').append(r.width).append('x').append(r.height)
                    .append(" age ").append(frame - r.createdFrame).append(',');
        }
        sb.setLength(sb.length() - 1);
    }

    /** Live traced GL ids (tests). */
    public static synchronized int liveCount() {
        return LIVE.size() + LIVE_RAW.size();
    }

    private static final StackWalker WALKER = StackWalker.getInstance();

    private static String stack(int max) {
        return WALKER.walk(s -> {
            StringBuilder sb = new StringBuilder();
            s.filter(f -> !f.getClassName().equals(ImageTrace.class.getName())).limit(max)
                    .forEach(f -> sb.append("    at ").append(f.getClassName()).append('.').append(f.getMethodName())
                            .append(':').append(f.getLineNumber()).append('\n'));
            return sb.toString();
        });
    }

    /** First caller outside the texture emulation: the API the delete came through. */
    private static String api() {
        return WALKER.walk(s -> s.filter(f -> {
                    String c = f.getClassName();
                    return !c.equals(ImageTrace.class.getName()) && !c.equals("net.vulkanmod.gl.VkGlTexture");
                }).limit(3).map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
                .reduce((a, b) -> a + " <- " + b).orElse("?"));
    }
}
