package net.vulkanmod.vulkan.framebuffer;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.VulkanImage;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Iroh (R-1): the scene colour + depth images that back {@code Minecraft.getMainRenderTarget()}.
 * <p>
 * Everything the game draws in a frame (world, post chains, GUI) lands here. The swapchain image is
 * touched only once per frame, at the tail of {@code DefaultMainPass.end}, by a copy/blit from this
 * target. Screenshots and readbacks read this target, so they never touch a presentable image.
 * <p>
 * Default configuration reproduces the 0.5.5 base exactly: colour {@code R8G8B8A8_UNORM} (the format the
 * base picked for the swapchain on this hardware; a UNORM -> UNORM copy changes no bits, and a UNORM ->
 * sRGB swapchain fallback is encoded by the blit exactly as rendering into an sRGB attachment would be),
 * depth {@link Vulkan#getDefaultDepthFormat()} (the base's swapchain depth format), size = swapchain extent,
 * colour sampler linear + clamp, depth sampler nearest + repeat (the base's swapchain image samplers).
 * <p>
 * EXTENSION POINTS for later phases. None of these is read or implemented yet; they exist so later
 * cards change one place. Changing any of them away from the default is NOT supported until the
 * listed work lands.
 * <ul>
 *   <li>{@link #RENDER_SCALE}: scene size different from the window (P4 upscalers). Apply in
 *       {@link #sceneSize(int)}; the final copy in DefaultMainPass already blits when sizes differ, but
 *       viewport/scissor helpers in Renderer (resetViewport) and GUI projection still assume window size.</li>
 *   <li>{@link #COLOR_FORMAT}: float scene colour (e.g. R16G16B16A16_SFLOAT) for HDR / RT (P5). Needs
 *       VulkanImage.Builder.formatSize support, a tonemap/convert pass instead of the plain blit, and
 *       screenshot conversion (MNativeImage assumes 4-byte RGBA). Render passes and their pipelines are
 *       keyed by RenderPass identity, so a format change must rebuild both.</li>
 *   <li>{@link #EXTRA_COLOR_ATTACHMENTS}: G-buffer / MRT attachments. Framebuffer and RenderPass support
 *       one colour attachment (RQ-7).</li>
 *   <li>{@link #KEEP_PREVIOUS_FRAME}: previous-frame colour/depth copy for temporal passes (P4-1). The
 *       hook point is {@code DefaultMainPass.copyToSwapChain}, right before the swapchain copy.</li>
 * </ul>
 */
public class SceneTarget extends Framebuffer {
    /** EXTENSION POINT (P4): not applied yet; must stay 1.0. */
    public static final float RENDER_SCALE = 1.0f;
    /** EXTENSION POINT (P5): only R8G8B8A8_UNORM is supported today. */
    public static final int COLOR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    /** EXTENSION POINT (RQ-7): only 0 is supported today. */
    public static final int EXTRA_COLOR_ATTACHMENTS = 0;
    /** EXTENSION POINT (P4-1): not implemented; must stay false. */
    public static final boolean KEEP_PREVIOUS_FRAME = false;

    static final int COLOR_USAGE = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    static final int DEPTH_USAGE = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    public SceneTarget(int width, int height) {
        super();
        this.format = COLOR_FORMAT;
        this.depthFormat = Vulkan.getDefaultDepthFormat();
        this.width = width;
        this.height = height;
        this.hasColorAttachment = true;
        this.hasDepthAttachment = true;
        this.attachmentCount = 2;
        this.linearFiltering = true;
        this.depthLinearFiltering = false;

        this.createImages();
    }

    /** Scene size for a window/swapchain dimension. EXTENSION POINT: apply {@link #RENDER_SCALE} here (P4). */
    public static int sceneSize(int windowSize) {
        return windowSize;
    }

    @Override
    public void createImages() {
        this.colorAttachment = VulkanImage.builder(this.width, this.height)
                .setName("Iroh scene colour")
                .setFormat(this.format)
                .setUsage(COLOR_USAGE)
                .setLinearFiltering(true)
                .setClamp(true)
                .createVulkanImage();

        this.depthAttachment = VulkanImage.builder(this.width, this.height)
                .setName("Iroh scene depth")
                .setFormat(this.depthFormat)
                .setUsage(DEPTH_USAGE)
                .setLinearFiltering(false)
                .setClamp(false)
                .createVulkanImage();
    }

    /**
     * Recreates the images when the size changed. Images are freed through the deferred free list
     * (callers run after a device wait idle). The render passes stay valid: they depend on formats only.
     * @return true when new images were created (texture ids must be rebound)
     */
    public boolean resizeIfNeeded(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0)
            return false;
        if (newWidth == this.width && newHeight == this.height)
            return false;

        this.resize(newWidth, newHeight);
        return true;
    }
}
