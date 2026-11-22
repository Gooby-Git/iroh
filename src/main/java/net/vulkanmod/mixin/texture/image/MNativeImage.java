package net.vulkanmod.mixin.texture.image;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.Locale;

@Mixin(NativeImage.class)
public abstract class MNativeImage {

    @Shadow private long pixels;
    @Shadow private long size;

    @Shadow public abstract void close();


    @Shadow @Final private NativeImage.Format format;

    @Shadow public abstract int getWidth();

    @Shadow @Final private int width;
    @Shadow @Final private int height;

    @Shadow public abstract int getHeight();

    @Shadow public abstract void setPixelRGBA(int i, int j, int k);

    @Shadow public abstract int getPixelRGBA(int i, int j);

    @Shadow protected abstract void checkAllocated();

    private ByteBuffer buffer;

    @Inject(method = "<init>(Lcom/mojang/blaze3d/platform/NativeImage$Format;IIZ)V", at = @At("RETURN"))
    private void constr(NativeImage.Format format, int width, int height, boolean useStb, CallbackInfo ci) {
        if(this.pixels != 0) {
            buffer = MemoryUtil.memByteBuffer(this.pixels, (int)this.size);
        }
    }

    @Inject(method = "<init>(Lcom/mojang/blaze3d/platform/NativeImage$Format;IIZJ)V", at = @At("RETURN"))
    private void constr(NativeImage.Format format, int width, int height, boolean useStb, long pixels, CallbackInfo ci) {
        if(this.pixels != 0) {
            buffer = MemoryUtil.memByteBuffer(this.pixels, (int)this.size);
        }
    }

    /**
     * @author
     */
    @Overwrite
    private void _upload(int level, int xOffset, int yOffset, int unpackSkipPixels, int unpackSkipRows, int widthIn, int heightIn, boolean blur, boolean clamp, boolean mipmap, boolean autoClose) {
        try {
            RenderSystem.assertOnRenderThreadOrInit();
            // vanilla: an image already closed throws here; Iroh used to read the stale cached buffer (use after free)
            this.checkAllocated();
            // Iroh (rc6, td-fcd844): upload in the image's OWN format with its OWN row layout, through the GL pixel-transfer
            // path that converts to the texture's format. The base copied width*height*formatSize(texture) bytes from
            // the image: an RGB or LUMINANCE image (Charta's cards) into an RGBA texture read past its allocation
            // (SIGSEGV in nmemcpy). The copy is synchronous: nothing reads the image after this returns.
            net.vulkanmod.gl.VkGlTexture tex = net.vulkanmod.gl.VkGlTexture.getBoundTexture();
            if (tex == null || tex.getVulkanImage() == null) {
                return;
            }
            int comps = this.format.components();
            tex.uploadClient(level, xOffset, yOffset, widthIn, heightIn, this.format.glFormat(), org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                    this.pixels, this.size, this.getWidth(), 1, unpackSkipRows, unpackSkipPixels); // rows are tightly packed
            // rc14 (td-5fd20c): the lightmap stays linear whoever uploads it (see LightmapFilter)
            if (dev.elemore.iroh.compat.LightmapFilter.isLightmap(tex.id)) blur = true;
            VTextureSelector.getBoundTexture().updateTextureSampler(blur, clamp, mipmap);
        } finally {
            if (autoClose) {
                this.close();
            }
        }
    }

    /**
     * @author
     */
    @Overwrite
    public void downloadTexture(int level, boolean removeAlpha) {
        RenderSystem.assertOnRenderThread();

        ImageUtil.downloadTexture(VTextureSelector.getBoundTexture(0), this.pixels);

        if (removeAlpha && this.format.hasAlpha()) {
            if (this.format != NativeImage.Format.RGBA) {
                throw new IllegalArgumentException(String.format(Locale.ROOT, "getPixelRGBA only works on RGBA images; have %s", this.format));
            }

            var colorAttachment = ( Renderer.getInstance()
                                                         .getMainPass()
                                                         .getColorAttachment());
            boolean isBGRAFormat = (colorAttachment.getVulkanImage().format == VK10.VK_FORMAT_B8G8R8A8_UNORM);

            for (long l = 0; l < this.width * this.height * 4L; l+=4) {
                int v =  MemoryUtil.memGetInt(this.pixels + l);

                if (isBGRAFormat)
                    v = ColorUtil.BGRAtoRGBA(v);

                v = v | 255 << this.format.alphaOffset();
                MemoryUtil.memPutInt(this.pixels + l, v);
            }
        }

    }

}
