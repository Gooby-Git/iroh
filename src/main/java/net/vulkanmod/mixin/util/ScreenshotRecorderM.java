package net.vulkanmod.mixin.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Screenshot.class)
public class ScreenshotRecorderM {

    /**
     * Iroh (R-1): for the main target this reads the scene colour image (never a swapchain image), so it is
     * valid between frames (F2 runs from the key callback after present) and mid-frame (after a flush).
     * Iroh (RQ-16): every target holds GL row order, so the rows are flipped like vanilla does. The NativeImage is sized from the image
     * itself: ImageUtil.downloadTexture copies the whole image, and a target whose width/height fields
     * disagreed with its image would overflow the buffer.
     * @author
     */
    @Overwrite
    public static NativeImage takeScreenshot(RenderTarget target) {
        VkGlTexture texture = VkGlTexture.getTexture(target.getColorTextureId());
        VulkanImage image = texture != null ? texture.getVulkanImage() : null;

        int width = image != null ? image.width : target.width;
        int height = image != null ? image.height : target.height;

        NativeImage nativeimage = new NativeImage(width, height, false);
        if (image == null)
            return nativeimage;

        // NativeImage.downloadTexture (MNativeImage) reads texture slot 0, whatever the active unit is.
        VTextureSelector.bindTexture(0, image);

        // Need to submit and wait cmds if screenshot was requested
        // before the end of the frame
        dev.elemore.iroh.glshim.FrameSync.flushFrame();

        nativeimage.downloadTexture(0, true);
        nativeimage.flipY();
        return nativeimage;
    }
}
