package net.vulkanmod.vulkan.pass;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SceneTarget;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Main pass. Iroh (R-1): the main render target is Iroh's own {@link SceneTarget} (colour + depth), not the
 * swapchain. The frame ends with one copy (same format and size) or blit (otherwise) from the scene colour
 * into the acquired swapchain image, after the GUI. Nothing else in a frame touches a swapchain image, so
 * screenshots, flushCmds submits and readbacks between frames never see a presentable image.
 * <p>
 * The MainPass interface and the public methods the 0.5.5 base had (create, rebindMainTarget, bindAsTexture,
 * getColorAttachment, begin, end, onResize, cleanUp) keep their signatures. (The AsyncParticles freeze on these
 * members ended 2026-11-21: the owner removed AsyncParticles from the Iroh pack.)
 */
public class DefaultMainPass implements MainPass {

    public static DefaultMainPass create() {
        return new DefaultMainPass();
    }

    private final SceneTarget sceneTarget;

    /** First pass of a frame: clears colour + depth (vanilla runTick does RenderSystem.clear(16640) there). */
    private RenderPass mainRenderPass;
    /** Re-binding the main target mid-frame (after an off-screen pass, a flush, a bindRead): loads contents. */
    private RenderPass auxRenderPass;

    /** GL texture ids of the scene images. Stable for the whole session: resize rebinds the same ids. */
    private final VkGlTexture colorTexture;
    private final VkGlTexture depthTexture;

    /** True between begin() at frame start and end(); a begin() inside a frame (Renderer.flushCmds) resumes. */
    private boolean frameOpen = false;
    private boolean loggedCopyMode = false;

    /** Swapchain image index acquired for the open frame (begin() at frame start runs right after the acquire). */
    private int acquiredImage = -1;
    /** Frames whose scene colour was copied to an acquired swapchain image (for tests: must grow every frame). */
    private static long framesCopied = 0;
    private boolean loggedInvariant = false;

    DefaultMainPass() {
        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        int width = swapChain.getWidth() > 0 ? SceneTarget.sceneSize(swapChain.getWidth()) : 854;
        int height = swapChain.getHeight() > 0 ? SceneTarget.sceneSize(swapChain.getHeight()) : 480;

        this.sceneTarget = new SceneTarget(width, height);

        createRenderPasses();

        this.colorTexture = VkGlTexture.getTexture(VkGlTexture.genTextureId());
        this.depthTexture = VkGlTexture.getTexture(VkGlTexture.genTextureId());
        bindTextureIds();

        Initializer.LOGGER.info("Iroh: scene target {}x{} colour format {} depth format {} (colour tex {}, depth tex {})",
                width, height, this.sceneTarget.getFormat(), this.sceneTarget.getDepthFormat(),
                this.colorTexture.id, this.depthTexture.id);
    }

    private void createRenderPasses() {
        RenderPass.Builder builder = RenderPass.builder(this.sceneTarget);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);

        this.mainRenderPass = builder.build();

        builder = RenderPass.builder(this.sceneTarget);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        this.auxRenderPass = builder.build();
    }

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        RenderPass renderPass;

        if (!this.frameOpen) {
            // Frame start: contents are cleared by the render pass, so the old layout does not matter.
            // Declaring UNDEFINED keeps the barrier valid even when the previous frame was recorded but never
            // submitted (Renderer.submitFrame skips the submit when a swapchain update is pending).
            this.sceneTarget.getColorAttachment().setCurrentLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            this.sceneTarget.getDepthAttachment().setCurrentLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            this.frameOpen = true;
            // Renderer.beginFrame calls this right after a successful vkAcquireNextImageKHR.
            this.acquiredImage = Renderer.getCurrentImage();
            renderPass = this.mainRenderPass;
        } else {
            // Resumed after Renderer.flushCmds: keep what was drawn so far.
            renderPass = this.auxRenderPass;
        }

        this.sceneTarget.beginRenderPass(commandBuffer, renderPass, stack);

        VkViewport.Buffer pViewport = this.sceneTarget.viewport(stack);
        vkCmdSetViewport(commandBuffer, 0, pViewport);

        VkRect2D.Buffer pScissor = this.sceneTarget.scissor(stack);
        vkCmdSetScissor(commandBuffer, 0, pScissor);
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        // P4-1a safety: a world phase left open (renderLevel threw, a mod skipped the GUI) is closed before the copy
        if (this.sceneTarget.isWorldActive())
            endWorld();
        Renderer.getInstance().endRenderPass(commandBuffer);

        // Invariant (R-1): a frame that is submitted targets the image acquired for it, and every such
        // frame copies the scene target to it. Violations are logged once and fail under -ea.
        boolean acquired = this.frameOpen && this.acquiredImage == Renderer.getCurrentImage();
        if (!acquired) {
            if (!this.loggedInvariant) {
                Initializer.LOGGER.error("Iroh: frame end without a matching acquire (frameOpen={}, acquired={}, current={}); swapchain left untouched",
                        this.frameOpen, this.acquiredImage, Renderer.getCurrentImage());
                this.loggedInvariant = true;
            }
            assert false : "frame end without a matching swapchain acquire";
        } else {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                copyToSwapChain(commandBuffer, stack);
            }
            framesCopied++;
        }

        this.frameOpen = false;
        this.acquiredImage = -1;

        int result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer:" + result);
        }
    }

    /**
     * The only place a frame touches the swapchain: scene colour -> acquired image -> PRESENT_SRC.
     * The first barrier's source stage is COLOR_ATTACHMENT_OUTPUT, the stage at which Renderer.submitFrame
     * waits on the image-available semaphore, so the transition is ordered after the acquire.
     */
    private void copyToSwapChain(VkCommandBuffer commandBuffer, MemoryStack stack) {
        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        VulkanImage src = this.sceneTarget.getColorAttachment();
        VulkanImage dst = swapChain.getColorAttachment();

        // EXTENSION POINT (SceneTarget.KEEP_PREVIOUS_FRAME, P4-1): copy scene colour/depth to history images here.

        src.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

        dst.setCurrentLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        VulkanImage.transitionLayout(stack, commandBuffer, dst,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);

        // Iroh (RQ-16): the scene target holds GL row order (row 0 = bottom), the swapchain is top-down: one blit
        // with the destination rows swapped. Copy only if blitting is impossible (picture would be upside down).
        boolean copy = !swapChain.canBlitTo();
        boolean sameSize = src.width == dst.width && src.height == dst.height;

        if (!this.loggedCopyMode) {
            Initializer.LOGGER.info("Iroh: frame end {} scene {}x{} fmt {} -> swapchain {}x{} fmt {}",
                    copy ? "vkCmdCopyImage (NO FLIP: swapchain format has no BLIT_DST)" : "vkCmdBlitImage (vertical flip)",
                    src.width, src.height, src.format, dst.width, dst.height, dst.format);
            this.loggedCopyMode = true;
        }

        if (copy) {
            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            region.srcOffset().set(0, 0, 0);
            region.dstOffset().set(0, 0, 0);
            region.extent().set(Math.min(src.width, dst.width), Math.min(src.height, dst.height), 1);

            vkCmdCopyImage(commandBuffer, src.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           dst.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        } else {
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            blit.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
            blit.srcOffsets(0).set(0, 0, 0);
            blit.srcOffsets(1).set(src.width, src.height, 1);
            blit.dstOffsets(0).set(0, dst.height, 0);
            blit.dstOffsets(1).set(dst.width, 0, 1);

            vkCmdBlitImage(commandBuffer, src.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           dst.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit,
                           sameSize ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
        }

        VulkanImage.transitionLayout(stack, commandBuffer, dst,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // P4-1a render scale: the world phase renders into SceneTarget's render-size world image set (see SceneTarget).
    // Called only by dev.elemore.iroh.temporal.TemporalRuntime, only with iroh.upscale.mode set.

    /**
     * Opens the world phase: ends whatever pass is bound, makes the world images the main target (texture ids and
     * MainTarget fields follow), and begins the CLEARING main pass on them (vanilla clears again in renderLevel).
     * @return false when nothing is recording (the phase is not opened)
     */
    public boolean beginWorld(int renderWidth, int renderHeight) {
        VkCommandBuffer commandBuffer = Renderer.recordingCommandBuffer("beginWorld");
        if (commandBuffer == null || this.sceneTarget.isWorldActive() || !this.frameOpen)
            return false;

        Renderer.getInstance().endRenderPass(commandBuffer);
        this.sceneTarget.ensureWorldImages(renderWidth, renderHeight);
        this.sceneTarget.activateWorld();
        bindTextureIds();
        syncMainTarget();

        // contents are cleared; UNDEFINED -> attachment waits for all earlier commands (BOTTOM_OF_PIPE source)
        this.sceneTarget.getColorAttachment().setCurrentLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        this.sceneTarget.getDepthAttachment().setCurrentLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.sceneTarget.beginRenderPass(commandBuffer, this.mainRenderPass, stack);
            vkCmdSetViewport(commandBuffer, 0, this.sceneTarget.viewport(stack));
            vkCmdSetScissor(commandBuffer, 0, this.sceneTarget.scissor(stack));
        }
        return true;
    }

    /**
     * Closes the world phase: ends the pass, lets TemporalRuntime record velocity / debug view / upscale (world colour
     * -> display colour), restores the display images as the main target and resumes the main pass on them (LOAD).
     */
    public void endWorld() {
        if (!this.sceneTarget.isWorldActive())
            return;
        VkCommandBuffer commandBuffer = Renderer.recordingCommandBuffer("endWorld");
        if (commandBuffer == null) {
            this.sceneTarget.activateDisplay();
            bindTextureIds();
            syncMainTarget();
            dev.elemore.iroh.temporal.TemporalRuntime.worldClosed();
            return;
        }
        Renderer.getInstance().endRenderPass(commandBuffer);
        VulkanImage worldColor = this.sceneTarget.getColorAttachment();
        VulkanImage worldDepth = this.sceneTarget.getDepthAttachment();
        VulkanImage displayColor = this.sceneTarget.getDisplayColor();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            dev.elemore.iroh.temporal.TemporalRuntime.recordWorldEnd(commandBuffer, stack, worldColor, worldDepth, displayColor);
        }
        this.sceneTarget.activateDisplay();
        bindTextureIds();
        syncMainTarget();
        dev.elemore.iroh.temporal.TemporalRuntime.worldClosed();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.sceneTarget.beginRenderPass(commandBuffer, this.auxRenderPass, stack);
            vkCmdSetViewport(commandBuffer, 0, this.sceneTarget.viewport(stack));
            vkCmdSetScissor(commandBuffer, 0, this.sceneTarget.scissor(stack));
        }
    }

    /**
     * Copies the world colour and/or depth (same formats and size; null = skip) and resumes the main pass (LOAD).
     * Called right before vanilla's pre-hand depth clear in renderLevel (depth: motion vectors and the upscaler need
     * the SCENE depth; the depth buffer at world end only holds the hand) and at WorldRenderEvents.AFTER_ENTITIES
     * (opaque colour + depth for the reactive / T&C masks). One pass break + the copies, only when the velocity pass
     * or a temporal upscaler is on.
     */
    public boolean copyWorld(VulkanImage colourDst, VulkanImage depthDst) {
        VkCommandBuffer commandBuffer = Renderer.recordingCommandBuffer("copyWorld");
        if (commandBuffer == null || !this.sceneTarget.isWorldActive() || !isMainTargetBound())
            return false;
        Renderer.getInstance().endRenderPass(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (colourDst != null)
                copyForCompute(stack, commandBuffer, this.sceneTarget.getColorAttachment(), colourDst, VK_IMAGE_ASPECT_COLOR_BIT);
            if (depthDst != null)
                copyForCompute(stack, commandBuffer, this.sceneTarget.getDepthAttachment(), depthDst, VK_IMAGE_ASPECT_DEPTH_BIT);
            this.sceneTarget.beginRenderPass(commandBuffer, this.auxRenderPass, stack);
            vkCmdSetViewport(commandBuffer, 0, this.sceneTarget.viewport(stack));
            vkCmdSetScissor(commandBuffer, 0, this.sceneTarget.scissor(stack));
        }
        dev.elemore.iroh.glshim.GlState.reapplyViewportAndScissor();
        return true;
    }

    /** src -> dst (same format), dst left in SHADER_READ_ONLY for a compute reader. */
    private static void copyForCompute(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage src, VulkanImage dst, int aspect) {
        src.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        dst.setCurrentLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        dst.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.srcSubresource().set(aspect, 0, 0, 1);
        region.dstSubresource().set(aspect, 0, 0, 1);
        region.extent().set(Math.min(src.width, dst.width), Math.min(src.height, dst.height), 1);
        vkCmdCopyImage(commandBuffer, src.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        VulkanImage.transitionLayout(stack, commandBuffer, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    /** Frees the world images when the mode goes off (deferred frees; display set must be active). */
    public void releaseWorld() {
        if (!this.sceneTarget.isWorldActive())
            this.sceneTarget.releaseWorldImages();
    }

    public static long getFramesCopied() {
        return framesCopied;
    }

    @Override
    public void cleanUp() {
        this.mainRenderPass.cleanUp();
        this.auxRenderPass.cleanUp();
        this.sceneTarget.cleanUp();
    }

    /**
     * Called by Renderer.recreateSwapChain after a device wait idle (resize, fullscreen, vsync, out-of-date,
     * frame queue change). Recreates the scene images only when the size changed; keeps them while the
     * window is minimised (0x0 swapchain) so readers never see a zero-sized target.
     */
    @Override
    public void onResize() {
        this.sceneTarget.activateDisplay(); // P4-1a
        this.frameOpen = false;
        this.acquiredImage = -1;

        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        if (swapChain.getWidth() > 0 && swapChain.getHeight() > 0) {
            int width = SceneTarget.sceneSize(swapChain.getWidth());
            int height = SceneTarget.sceneSize(swapChain.getHeight());

            if (this.sceneTarget.resizeIfNeeded(width, height)) {
                bindTextureIds();
                Initializer.LOGGER.info("Iroh: scene target resized to {}x{}", width, height);
            }
        }

        syncMainTarget();
        this.loggedCopyMode = false;
    }

    private void bindTextureIds() {
        VkGlTexture.bindIdToImage(this.colorTexture.id, this.sceneTarget.getColorAttachment());
        VkGlTexture.bindIdToImage(this.depthTexture.id, this.sceneTarget.getDepthAttachment());
    }

    /** Keeps Minecraft's MainTarget size fields equal to the scene images (screenshots size their buffers from them). */
    public void syncMainTarget() {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget mainTarget = minecraft != null ? minecraft.getMainRenderTarget() : null;
        if (mainTarget == null)
            return;

        mainTarget.width = this.sceneTarget.getWidth();
        mainTarget.height = this.sceneTarget.getHeight();
        mainTarget.viewWidth = this.sceneTarget.getWidth();
        mainTarget.viewHeight = this.sceneTarget.getHeight();
    }

    @Override
    public void rebindMainTarget() {
        VkCommandBuffer commandBuffer = Renderer.recordingCommandBuffer("rebindMainTarget"); // rc3r2 (td-7937f0)
        if (commandBuffer == null)
            return;

        // Do not rebind if the framebuffer is already bound
        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        if (boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
            return;

        Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.sceneTarget.beginRenderPass(commandBuffer, this.auxRenderPass, stack);
        }
    }

    @Override
    public void bindAsTexture() {
        VkCommandBuffer commandBuffer = Renderer.recordingCommandBuffer("bindAsTexture");
        if (commandBuffer == null) {
            VTextureSelector.bindTexture(this.sceneTarget.getColorAttachment());
            return;
        }

        // Check if render pass is using the framebuffer
        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        if (boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
            Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.sceneTarget.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(this.sceneTarget.getColorAttachment());
    }

    @Override
    public VkGlTexture getColorAttachment() {
        return this.colorTexture;
    }

    @Override
    public VkGlTexture getDepthAttachment() {
        return this.depthTexture;
    }

    @Override
    public SceneTarget getSceneTarget() {
        return this.sceneTarget;
    }

    @Override
    public boolean isMainTargetBound() {
        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        return boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass;
    }
}
