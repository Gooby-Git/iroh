package dev.elemore.iroh.apiimpl;

import com.mojang.blaze3d.platform.NativeImage;
import dev.elemore.iroh.api.DynamicTextureHandle;
import dev.elemore.iroh.api.IrohTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Iroh internal (P1-13a): {@link IrohTextures#createDynamic}. Backed by a vanilla DynamicTexture registered in the texture
 * manager (so pack customTexture lookups by id find it), with Iroh's sampler flags (REPEAT possible). {@code update} is a
 * volatile read and return while nothing consumes the texture; consumers (shader packs, RT materials: later phases) call
 * {@link #addConsumer}.
 */
public final class DynamicTextures {
    private static final Map<ResourceLocation, Impl> BY_ID = new ConcurrentHashMap<>();

    private DynamicTextures() {
    }

    public static DynamicTextureHandle create(ResourceLocation id, int w, int h, IrohTextures.Filter filter, IrohTextures.Wrap wrap) {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("size " + w + "x" + h);
        Impl impl = new Impl(id, w, h, filter, wrap);
        Impl old = BY_ID.put(id, impl);
        if (old != null) old.release();
        return impl;
    }

    /** For tests and the later PACK / RT consumers: one more (or, with -1, one fewer) consumer of {@code id}. */
    public static boolean addConsumer(ResourceLocation id, int delta) {
        Impl i = BY_ID.get(id);
        if (i == null) return false;
        synchronized (i) {
            i.consumers = Math.max(0, i.consumers + delta);
        }
        return true;
    }

    /** Uploads (counted, for tests). */
    public static long uploads;

    static final class Impl implements DynamicTextureHandle {
        final ResourceLocation id;
        final int w, h;
        final IrohTextures.Filter filter;
        final IrohTextures.Wrap wrap;
        volatile int consumers;
        DynamicTexture texture; // created lazily on the first real update (no GPU image while nobody consumes it)
        boolean closed;

        Impl(ResourceLocation id, int w, int h, IrohTextures.Filter filter, IrohTextures.Wrap wrap) {
            this.id = id;
            this.w = w;
            this.h = h;
            this.filter = filter;
            this.wrap = wrap;
        }

        @Override public ResourceLocation id() { return id; }
        @Override public int width() { return w; }
        @Override public int height() { return h; }
        @Override public boolean hasConsumers() { return consumers > 0 && !closed; }

        @Override
        public void update(int x, int y, int uw, int uh, ByteBuffer rgba) {
            if (consumers == 0 || closed) return;
            if (x < 0 || y < 0 || uw <= 0 || uh <= 0 || x + uw > w || y + uh > h)
                throw new IllegalArgumentException("region " + x + "," + y + " " + uw + "x" + uh + " outside " + w + "x" + h);
            if (rgba.remaining() < uw * uh * 4) throw new IllegalArgumentException("need " + (uw * uh * 4) + " bytes");
            if (texture == null && !dev.elemore.iroh.memory.MemoryGuard.allowOptional((long) w * h * 4))
                return; // rc8: at the graphics-memory cap an optional texture is not created
            if (texture == null) {
                texture = new DynamicTexture(w, h, true);
                Minecraft.getInstance().getTextureManager().register(id, texture);
                applySampler();
            }
            NativeImage img = texture.getPixels();
            long base = MemoryUtil.memAddress(rgba);
            for (int row = 0; row < uh; row++) {
                long src = base + (long) row * uw * 4;
                for (int col = 0; col < uw; col++) {
                    int c = MemoryUtil.memGetInt(src + col * 4L); // bytes R,G,B,A = NativeImage ABGR int
                    img.setPixelRGBA(x + col, y + row, c);
                }
            }
            texture.upload();
            uploads++;
        }

        private void applySampler() {
            VkGlTexture t = VkGlTexture.getTexture(texture.getId());
            VulkanImage vi = t != null ? t.getVulkanImage() : null;
            if (vi == null) return;
            byte flags = filter == IrohTextures.Filter.LINEAR ? SamplerManager.LINEAR_FILTERING_BIT : 0;
            if (wrap == IrohTextures.Wrap.CLAMP) flags |= SamplerManager.CLAMP_BIT;
            vi.updateTextureSampler(flags);
        }

        void release() {
            closed = true;
            if (texture != null) {
                Minecraft.getInstance().getTextureManager().release(id);
                texture = null;
            }
        }

        @Override
        public void close() {
            BY_ID.remove(id, this);
            release();
        }
    }
}
