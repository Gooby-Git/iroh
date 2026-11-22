package net.vulkanmod.vulkan.framebuffer;

import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;

import java.nio.LongBuffer;

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
 *   <li>{@link #RENDER_SCALE}: superseded by the P4-1a WORLD IMAGE SET below; stays 1.0 (the display images are
 *       always window-sized).</li>
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
    /** Superseded by the P4-1a world image set ({@link #activateWorld}); must stay 1.0. */
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
                .setUsage(displayColorUsage())
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
        activateDisplay(); // P4-1a: sizes below are the display images'
        if (newWidth <= 0 || newHeight <= 0)
            return false;
        if (newWidth == this.width && newHeight == this.height)
            return false;

        this.resize(newWidth, newHeight);
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // P4-1a WORLD IMAGE SET. With iroh.upscale.mode set, the world (renderLevel .. just before the GUI) renders into a
    // second colour + depth pair at RENDER size. While it is active, this object's colour/depth/width/height ARE the
    // world images, so everything that means "the main target" (Minecraft's MainTarget fields, the stable GL texture
    // ids, the main render passes, screenshots taken in the world phase, bindAsTexture) follows without a second code
    // path. The render passes are shared (same formats): no extra pipelines are compiled. Each set has its own
    // VkFramebuffers. The display set is restored before the GUI and before anything outside the frame (resize,
    // cleanup) touches this object. Mode off: none of this is ever allocated (UpscaleDefaultOffTest).

    /**
     * STORAGE only when the upscale device features were enabled (debug views write the world colour from compute):
     * storage usage can turn off colour compression (DCC on AMD), a cost the plain render-scale path should not pay.
     */
    /** P4-1b: FSR writes the upscaled picture into the display colour by storage: STORAGE only when FSR can run. */
    static int displayColorUsage() {
        return dev.elemore.iroh.temporal.DeviceCaps.fsrCapable() ? COLOR_USAGE | VK_IMAGE_USAGE_STORAGE_BIT : COLOR_USAGE;
    }

    static int worldColorUsage() {
        return dev.elemore.iroh.temporal.DeviceCaps.velocityCapable() ? COLOR_USAGE | VK_IMAGE_USAGE_STORAGE_BIT : COLOR_USAGE;
    }

    private VulkanImage worldColor, worldDepth;
    private int worldWidth, worldHeight;
    private VulkanImage displayColor, displayDepth;
    private int displayWidth, displayHeight;
    private boolean worldActive;
    private final Reference2LongArrayMap<RenderPass> worldFramebuffers = new Reference2LongArrayMap<>();

    public boolean isWorldActive() {
        return this.worldActive;
    }

    public boolean hasWorldImages() {
        return this.worldColor != null;
    }

    /** (Re)creates the render-size world images when missing or of another size. Only with the display set active. */
    public void ensureWorldImages(int w, int h) {
        if (this.worldActive)
            throw new IllegalStateException("ensureWorldImages while the world set is active");
        if (this.worldColor != null && this.worldWidth == w && this.worldHeight == h)
            return;
        releaseWorldImages();
        this.worldWidth = w;
        this.worldHeight = h;
        this.worldColor = VulkanImage.builder(w, h)
                .setName("Iroh world colour (render scale)")
                .setFormat(this.format)
                .setUsage(worldColorUsage())
                .setLinearFiltering(true)
                .setClamp(true)
                .createVulkanImage();
        this.worldDepth = VulkanImage.builder(w, h)
                .setName("Iroh world depth (render scale)")
                .setFormat(this.depthFormat)
                .setUsage(DEPTH_USAGE)
                .setLinearFiltering(false)
                .setClamp(false)
                .createVulkanImage();
    }

    /** Frees the world images and their framebuffers (deferred frees). Only with the display set active. */
    public void releaseWorldImages() {
        if (this.worldActive)
            throw new IllegalStateException("releaseWorldImages while the world set is active");
        if (this.worldColor != null)
            this.worldColor.free();
        if (this.worldDepth != null)
            this.worldDepth.free();
        this.worldColor = this.worldDepth = null;
        final VkDevice device = Vulkan.getVkDevice();
        final long[] ids = this.worldFramebuffers.values().toLongArray();
        if (ids.length > 0)
            MemoryManager.getInstance().addFrameOp(() -> { for (long id : ids) vkDestroyFramebuffer(device, id, null); });
        this.worldFramebuffers.clear();
    }

    /** The world images become this target's attachments (size = render size). */
    public void activateWorld() {
        if (this.worldActive || this.worldColor == null)
            return;
        this.displayColor = this.colorAttachment;
        this.displayDepth = this.depthAttachment;
        this.displayWidth = this.width;
        this.displayHeight = this.height;
        this.colorAttachment = this.worldColor;
        this.depthAttachment = this.worldDepth;
        this.width = this.worldWidth;
        this.height = this.worldHeight;
        this.worldActive = true;
    }

    /** Back to the window-size display images. */
    public void activateDisplay() {
        if (!this.worldActive)
            return;
        this.colorAttachment = this.displayColor;
        this.depthAttachment = this.displayDepth;
        this.width = this.displayWidth;
        this.height = this.displayHeight;
        this.worldActive = false;
    }

    public VulkanImage getWorldColor() {
        return this.worldColor;
    }

    public VulkanImage getWorldDepth() {
        return this.worldDepth;
    }

    /** the display colour whichever set is active */
    public VulkanImage getDisplayColor() {
        return this.worldActive ? this.displayColor : this.colorAttachment;
    }

    @Override
    protected long getFramebufferId(RenderPass renderPass) {
        if (!this.worldActive)
            return super.getFramebufferId(renderPass);
        return this.worldFramebuffers.computeIfAbsent(renderPass, rp -> createWorldFramebuffer((RenderPass) rp));
    }

    private long createWorldFramebuffer(RenderPass renderPass) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer attachments = stack.longs(this.worldColor.getImageView(), this.worldDepth.getImageView());
            LongBuffer p = stack.mallocLong(1);
            VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack);
            info.sType$Default();
            info.renderPass(renderPass.getId());
            info.width(this.worldWidth);
            info.height(this.worldHeight);
            info.layers(1);
            info.pAttachments(attachments);
            if (dev.elemore.iroh.memory.MemoryGuard.check(vkCreateFramebuffer(Vulkan.getVkDevice(), info, null, p), "vkCreateFramebuffer world") != VK_SUCCESS)
                throw new RuntimeException("Failed to create the world framebuffer");
            return p.get(0);
        }
    }

    @Override
    public void cleanUp(boolean cleanImages) {
        activateDisplay();
        if (cleanImages)
            releaseWorldImages();
        super.cleanUp(cleanImages);
    }
}
