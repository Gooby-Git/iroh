package net.vulkanmod.mixin.texture;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Unique;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;

@Mixin(TextureUtil.class)
public class MTextureUtil {

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int generateTextureId() {
        RenderSystem.assertOnRenderThreadOrInit();
        return VkGlTexture.genTextureId();
    }

    // P1-1b: HEAD-cancel instead of @Overwrite so the vanilla body (with its bind(int) call) stays in the class:
    // owo braid.TextureUtilMixin injects at INVOKE TextureUtil.bind(I)V there (require 1). Retargeting a @Shadow call
    // did not help (launch 2 crashed anyway). owo's handler would now sit in dead code, so its one effect, recording
    // the texture size for Braid, is forwarded below.
    @Inject(method = "prepareImage(Lcom/mojang/blaze3d/platform/NativeImage$InternalGlFormat;IIII)V", at = @At("HEAD"), cancellable = true)
    private static void irohPrepareImage(NativeImage.InternalGlFormat internalGlFormat, int id, int mipLevels, int width, int height, CallbackInfo ci) {
        ci.cancel();
        dev.elemore.iroh.glshim.OwoCompat.registerTextureSize(id, width, height);
        RenderSystem.assertOnRenderThreadOrInit();
        GlStateManager._bindTexture(id);
        VkGlTexture glTexture = VkGlTexture.getBoundTexture();
        VulkanImage image = glTexture.getVulkanImage();

        if (mipLevels > 0) {
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, mipLevels);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_MIN_LOD, 0);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LOD, mipLevels);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_LOD_BIAS, 0.0F);
        }

        if (image == null || image.mipLevels != mipLevels || image.width != width || image.height != height) {
            VulkanImage old = image;
            if (image != null)
                image.free();

            image = new VulkanImage.Builder(width, height)
                    .setName(String.format("Texture %d %dx%d by %s", id, width, height, preparer()))
                    .setOwner("Texture by " + preparer())
                    .setMipLevels(mipLevels + 1)
                    .setFormat(convertFormat(internalGlFormat))
                    .setLinearFiltering(false)
                    .setClamp(false)
                    .createVulkanImage();

            glTexture.setVulkanImage(image);
            VTextureSelector.bindTexture(image);
            if (dev.elemore.iroh.diag.ImageTrace.ON) // rc13
                dev.elemore.iroh.diag.ImageTrace.created(id, image, old, width, height, image.format);
        }
    }

    /** rc10: the class that prepared the texture (DynamicTexture, SimpleTexture, FontTexture, a mod class...). */
    @Unique
    private static String preparer() {
        return StackWalker.getInstance().walk(st -> st
                .map(f -> f.getClassName())
                .filter(c -> !c.startsWith("com.mojang.blaze3d.") && !c.startsWith("net.vulkanmod.") && !c.startsWith("java.")
                        && !c.contains("TextureUtil") && !c.startsWith("dev.elemore.iroh."))
                .findFirst()
                .map(c -> c.substring(c.lastIndexOf('.') + 1))
                .orElse("?"));
    }

    @Unique
    private static int convertFormat(NativeImage.InternalGlFormat format) {
        return switch (format) {
            case RGBA -> VK_FORMAT_R8G8B8A8_UNORM;
            case RED -> VK_FORMAT_R8_UNORM;
            default -> throw new IllegalArgumentException(String.format("Unxepcted format: %s", format));
        };
    }
}
