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
 * <p>
 * <b>MAIN TARGET CONTRACT (INT-2, 2026-11-22).</b> Two features change "what the main target is" inside a frame:
 * render scale (P4-1a, the world image set) and, next, the shader pack's MRT world pass (card P3-11). Both follow
 * these rules so they compose:
 * <ol>
 *   <li><b>One owner, one accessor.</b> This class owns which images are the main target. {@link #mainTarget()} is the
 *       ONLY way to ask: its colour / depth / width / height at the moment of the call are the images draws land in
 *       now (render-size world images inside the render-scale world phase, window-size display images otherwise).
 *       Nobody caches those images across a phase boundary; resolve them at bind time, every time.</li>
 *   <li><b>Phases are opened and closed only here:</b> {@link #beginWorld} / {@link #endWorld} (render scale) and the
 *       copy points {@link #copyWorld} (AFTER_ENTITIES opaque copy, pre-hand depth). A feature that needs a different
 *       set of attachments for part of the world (P3-11's gbuffer MRT pass) adds its own begin/end pair HERE, nested
 *       inside the world phase, never around it: it ends the bound pass, binds its framebuffer built from
 *       {@code mainTarget().getColorAttachment()/getDepthAttachment()} plus its own extra attachments AT BIND TIME (same
 *       size as mainTarget(): render size when render scale is on), and on exit resumes the main pass through
 *       {@link #rebindMainTarget()} / {@code VulkanImage.endPassRunResume} (both resume on the ACTIVE set).</li>
 *   <li><b>No resize or cleanup inside a phase.</b> {@code SceneTarget.resizeIfNeeded}, {@link #onResize},
 *       {@link #cleanUp} force the display set first; they run between frames only.</li>
 *   <li><b>{@link #end} closes whatever is open</b> (world phase, and the P3-11 pass if it adds one) before the
 *       swapchain copy, so a phase left open by an exception never reaches present.</li>
 *   <li><b>INT-3:</b> P3-11's world pass did NOT become a nested pair here: it binds pack attachments (gbuffers), never
 *       the main target, and yields to every foreign recording through the Substitution.HOOK reads in Renderer.beginRendering /
 *       endRenderPass and VulkanImage.endPassRunResume. Who may be active together and who wins (render scale, world
 *       pass, api 4.1 main join, runOutsideMainPass, PassResume): {@code dev.elemore.iroh.render.MainPassArbiter}.</li>
 *   <li><b>Every phase change notifies api 4</b> ({@code GpuLifecycle.worldPhase}): scaled mod targets follow the
 *       phase they are drawn in (knowledge/api.md).</li>
 * </ol>
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
            // Frame start: contents are cleared by the render pass, so the old layout does not matter (UNDEFINED as the
            // old layout is always valid). V-3: frames are never dropped any more (Renderer.submitFrame); this stays
            // because the clear makes the previous contents dead anyway.
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
        // INT-3 (contract rule 4, MainPassArbiter rule 7): the pack world pass still live here means WorldRenderEvents.END
        // did not run (renderLevel threw): close it and turn it off for the session, never carry it into the next frame
        if ((dev.elemore.iroh.shaderpack.runtime.Substitution.HOOK & dev.elemore.iroh.shaderpack.runtime.Substitution.WORLD) != 0)
            dev.elemore.iroh.shaderpack.runtime.SubstitutionHook.frameEnded();
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

        // INT-2: allocate BEFORE ending the bound pass: an allocation failure (memory refusal, forced by
        // -Diroh.test.failInit=renderScale) throws with the frame untouched, and the caller turns render scale off
        dev.elemore.iroh.diag.FeatureFailures.inject("renderScale");
        this.sceneTarget.ensureWorldImages(renderWidth, renderHeight);
        Renderer.getInstance().endRenderPass(commandBuffer);
        this.sceneTarget.activateWorld();
        bindTextureIds();
        syncMainTarget();
        dev.elemore.iroh.gpu.game.GpuLifecycle.worldPhase(true, renderWidth, renderHeight); // contract rule 5

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
            dev.elemore.iroh.gpu.game.GpuLifecycle.worldPhase(false, 0, 0);
            dev.elemore.iroh.temporal.TemporalRuntime.worldClosed();
            return;
        }
        Renderer.getInstance().endRenderPass(commandBuffer);
        VulkanImage worldColor = this.sceneTarget.getColorAttachment();
        VulkanImage worldDepth = this.sceneTarget.getDepthAttachment();
        VulkanImage displayColor = this.sceneTarget.getDisplayColor();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            dev.elemore.iroh.temporal.TemporalRuntime.recordWorldEnd(commandBuffer, stack, worldColor, worldDepth, displayColor);
        } catch (RuntimeException e) {
            // INT-2: never leave the frame with the world set bound: the display set comes back, render scale goes off
            dev.elemore.iroh.temporal.TemporalRuntime.worldEndFailed(e);
        } finally {
            this.sceneTarget.activateDisplay();
            bindTextureIds();
            syncMainTarget();
            dev.elemore.iroh.gpu.game.GpuLifecycle.worldPhase(false, 0, 0);
            dev.elemore.iroh.temporal.TemporalRuntime.worldClosed();
        }
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

    /**
     * INT-2 main-target contract, rule 1: the target draws land in NOW (its colour / depth / size are the active set:
     * render-size world images inside the render-scale world phase, display images otherwise). Resolve at bind time.
     */
    public SceneTarget mainTarget() {
        return this.sceneTarget;
    }

    /** true inside the render-scale world phase (mainTarget() is the render-size world set) */
    public boolean inWorldPhase() {
        return this.sceneTarget.isWorldActive();
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
