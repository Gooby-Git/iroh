package dev.elemore.iroh.compat.watermedia;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.elemore.iroh.glshim.GlShim;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;

/**
 * Iroh's replacement for the GL work inside WaterMedia's {@code GLEngine} (P1-2, blocker A).
 *
 * <p>WaterMedia decodes on its own threads (FFmpeg) and hands frames to a GFXEngine. Its GLEngine uploads YUV planes
 * through PBOs and converts them with a GLSL program into an FBO: dozens of GL calls Iroh cannot emulate, and the
 * first one without a context ({@code GLEngine$Env.restore} -> glActiveTexture) aborted the JVM. Under Iroh the engine
 * answers {@code supports(fmt)} with BGRA only, so FFmpeg's swscale converts every frame to BGRA on the decode thread
 * (FFMediaPlayer's own fallback path); this class copies that frame, uploads it on the render thread into a Vulkan
 * image (B8G8R8A8, so no CPU swizzle) registered as a GL texture id, and returns that id from {@code texture()}.
 * FancyMenu binds the id through GlStateManager._bindTexture, which resolves to the image. No GL anywhere.
 *
 * <p>Threads: {@link #format} and {@link #upload} come from WaterMedia's decode thread, {@link #textureId} and the
 * upload from the render thread. A frame arriving while the previous one is still pending replaces it (newest wins).
 */
public final class IrohVideoTexture {
    private final Object lock = new Object();
    private final VideoFrameBuffer buf = new VideoFrameBuffer(); // tightly packed BGRA, width*4 per row
    private volatile boolean dirty;
    private boolean scheduled, released;

    // render thread only
    private int texId;
    private VulkanImage image;
    private int imageW, imageH;
    private boolean uploadedOnce;

    public IrohVideoTexture() {
        GlShim.LOGGER.info("Iroh: WaterMedia GLEngine runs on Iroh's video path (BGRA frames -> Vulkan image)");
    }

    /**
     * Decode thread. A non-BGRA announcement is transient (see {@link VideoFrameBuffer}): the buffer is parked and the
     * next BGRA announcement, which FFMediaPlayer makes before it uploads anything, re-arms it. No warning here.
     */
    public void format(boolean bgra, String name, int w, int h) {
        synchronized (lock) {
            if (buf.announce(bgra, name, w, h)) dirty = false;
        }
    }

    /** Decode thread: copy plane 0 (BGRA, {@code strides[0]} bytes per row) before WaterMedia reuses its buffer. */
    public void upload(ByteBuffer[] planes, int[] strides) {
        boolean schedule;
        synchronized (lock) {
            if (released) return;
            if (!buf.copy(planes, strides)) {
                if (buf.shouldWarnDrop())
                    GlShim.LOGGER.warn("Iroh: WaterMedia delivered frames in non-BGRA format {} (supports() said BGRA only); frames dropped until it announces BGRA", buf.parkedFormat);
                return;
            }
            dirty = true;
            schedule = !scheduled;
            scheduled = true;
        }
        if (schedule) RenderSystem.recordRenderCall(this::flush);
    }

    /** Render thread: newest pending frame -> image. */
    public void flush() {
        synchronized (lock) {
            scheduled = false;
            if (released || !dirty || buf.pending == null) return;
            if (image == null || imageW != buf.width || imageH != buf.height) allocate(buf.width, buf.height);
            image.uploadSubTextureAsync(0, buf.width, buf.height, 0, 0, 0, 0, buf.width, buf.pending);
            dirty = false;
            uploadedOnce = true;
        }
    }

    private void allocate(int w, int h) {
        if (texId == 0) texId = VkGlTexture.genTextureId();
        if (image != null) image.free();
        image = new VulkanImage.Builder(w, h)
                .setName("Iroh WaterMedia video " + texId)
                .setFormat(VK_FORMAT_B8G8R8A8_UNORM)
                .setLinearFiltering(true)
                .setClamp(true)
                .createVulkanImage();
        image.updateTextureSampler((byte) (SamplerManager.LINEAR_FILTERING_BIT | SamplerManager.CLAMP_BIT));
        imageW = w;
        imageH = h;
        VkGlTexture.setVulkanImage(texId, image);
    }

    /** GL texture id to sample, 0 until the first frame is on the GPU (FancyMenu treats 0 as "not ready"). */
    public long textureId() {
        // A frame that already drew the video keeps that picture (td-4a9be3): uploading now would have to end the
        // render pass; the scheduled flush picks the frame up before the next frame's draws.
        if (RenderSystem.isOnRenderThread() && dirty && (image == null || !image.usedThisFrame())) flush();
        return uploadedOnce ? texId : 0L;
    }

    public void release() {
        synchronized (lock) {
            released = true;
            buf.free();
        }
        if (RenderSystem.isOnRenderThread()) freeGpu();
        else RenderSystem.recordRenderCall(this::freeGpu);
    }

    private void freeGpu() {
        if (texId != 0) VkGlTexture.glDeleteTextures(texId); // frees the image via MemoryManager
        texId = 0;
        image = null;
    }
}
