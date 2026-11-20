package dev.elemore.iroh.compat.watermedia;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.elemore.iroh.glshim.GlShim;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

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
    private int width, height;
    private ByteBuffer pending;       // tightly packed BGRA, width*4 per row
    private volatile boolean dirty;
    private boolean scheduled, released;
    private boolean warnedFormat;

    // render thread only
    private int texId;
    private VulkanImage image;
    private int imageW, imageH;
    private boolean uploadedOnce;

    public IrohVideoTexture() {
        GlShim.LOGGER.info("Iroh: WaterMedia GLEngine runs on Iroh's video path (BGRA frames -> Vulkan image)");
    }

    public void format(boolean bgra, int w, int h) {
        synchronized (lock) {
            if (!bgra) {
                if (!warnedFormat) GlShim.LOGGER.warn("Iroh: WaterMedia asked for a non-BGRA video format; frames ignored");
                warnedFormat = true;
                width = height = 0;
                return;
            }
            if (w != width || h != height) {
                width = w;
                height = h;
                if (pending != null) MemoryUtil.memFree(pending);
                pending = w > 0 && h > 0 ? MemoryUtil.memAlloc(w * h * 4) : null;
                dirty = false;
            }
        }
    }

    /** Decode thread: copy plane 0 (BGRA, {@code strides[0]} bytes per row) before WaterMedia reuses its buffer. */
    public void upload(ByteBuffer[] planes, int[] strides) {
        if (planes == null || planes.length == 0 || planes[0] == null) return;
        boolean schedule;
        synchronized (lock) {
            if (released || pending == null || width <= 0 || height <= 0) return;
            ByteBuffer src = planes[0];
            int row = width * 4;
            int stride = strides != null && strides.length > 0 && strides[0] > 0 ? strides[0] : row;
            long srcAddr = MemoryUtil.memAddress(src);
            long dstAddr = MemoryUtil.memAddress(pending);
            long avail = src.remaining();
            if (stride == row) {
                MemoryUtil.memCopy(srcAddr, dstAddr, Math.min(avail, (long) row * height));
            } else {
                for (int y = 0; y < height && (long) y * stride + row <= avail; y++)
                    MemoryUtil.memCopy(srcAddr + (long) y * stride, dstAddr + (long) y * row, row);
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
            if (released || !dirty || pending == null) return;
            if (image == null || imageW != width || imageH != height) allocate(width, height);
            image.uploadSubTextureAsync(0, width, height, 0, 0, 0, 0, width, pending);
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
            if (pending != null) {
                MemoryUtil.memFree(pending);
                pending = null;
            }
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
