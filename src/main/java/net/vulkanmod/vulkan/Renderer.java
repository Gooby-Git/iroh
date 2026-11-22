package net.vulkanmod.vulkan;

import net.vulkanmod.vulkan.memory.buffer.StagingBuffer;

import com.mojang.blaze3d.platform.GlStateManager;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.Minecraft;
import net.vulkanmod.Initializer;
import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.mixin.window.WindowAccessor;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.texture.ImageUploadHelper;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.shader.Uniforms;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.util.VUtil;
import net.vulkanmod.vulkan.util.VkResult;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.mojang.blaze3d.platform.GlConst.GL_COLOR_BUFFER_BIT;
import static com.mojang.blaze3d.platform.GlConst.GL_DEPTH_BUFFER_BIT;
import static net.vulkanmod.vulkan.Vulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class Renderer {
    private static Renderer INSTANCE;

    private static VkDevice device;

    private static boolean swapChainUpdate = false;
    public static boolean skipRendering = false;

    public static void initRenderer() {
        INSTANCE = new Renderer();
        INSTANCE.init();
    }

    public static Renderer getInstance() {
        return INSTANCE;
    }

    public static Drawer getDrawer() {
        return INSTANCE.drawer;
    }

    public static int getCurrentFrame() {
        return currentFrame;
    }

    public static int getCurrentImage() {
        return imageIndex;
    }

    private final Set<Pipeline> usedPipelines = new ObjectOpenHashSet<>();
    private Pipeline boundPipeline;
    private long boundPipelineHandle;

    private Drawer drawer;

    private SwapChain swapChain;

    private int framesNum;
    private List<VkCommandBuffer> commandBuffers;
    private ArrayList<Long> imageAvailableSemaphores;
    private ArrayList<Long> renderFinishedSemaphores;
    private ArrayList<Long> inFlightFences;

    private Framebuffer boundFramebuffer;
    private RenderPass boundRenderPass;

    private static int currentFrame = 0;
    private static int imageIndex;
    private static int lastReset = -1;
    private VkCommandBuffer currentCmdBuffer;
    private boolean recordingCmds = false;

    MainPass mainPass;

    private final List<Runnable> onResizeCallbacks = new ObjectArrayList<>();

    public Renderer() {
        device = Vulkan.getVkDevice();
        framesNum = Initializer.CONFIG.frameQueueSize;
    }

    public static void setLineWidth(float width) {
        if (INSTANCE.boundFramebuffer == null) {
            return;
        }
        VkCommandBuffer cb = recordingCommandBuffer("setLineWidth");
        if (cb == null) return;
        vkCmdSetLineWidth(cb, width);
    }

    private void init() {
        MemoryManager.createInstance(Renderer.getFramesNum());
        Vulkan.createStagingBuffers();

        swapChain = new SwapChain();
        mainPass = DefaultMainPass.create();

        drawer = new Drawer();
        drawer.createResources(framesNum);

        Uniforms.setupDefaultUniforms();
        PipelineManager.init();
        UploadManager.createInstance();

        allocateCommandBuffers();
        createSyncObjects();
    }

    private void allocateCommandBuffers() {
        if (commandBuffers != null) {
            commandBuffers.forEach(commandBuffer -> vkFreeCommandBuffers(device, Vulkan.getCommandPool(), commandBuffer));
        }

        commandBuffers = new ArrayList<>(framesNum);

        try (MemoryStack stack = stackPush()) {

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(getCommandPool());
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(framesNum);

            PointerBuffer pCommandBuffers = stack.mallocPointer(framesNum);

            int vkResult = vkAllocateCommandBuffers(device, allocInfo, pCommandBuffers);
            if (vkResult != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate command buffers: %s".formatted(VkResult.decode(vkResult)));
            }

            for (int i = 0; i < framesNum; i++) {
                commandBuffers.add(new VkCommandBuffer(pCommandBuffers.get(i), device));
            }
        }
    }

    private void createSyncObjects() {
        // Iroh (P1-1): render-finished semaphores are per swapchain IMAGE, not per frame in flight. Presentation
        // holds the semaphore until that image is re-acquired, so reusing it by frame index re-signals a semaphore
        // the swapchain may still wait on (validation: "being signaled by VkQueue ... may still be in use by VkSwapchainKHR").
        int imageCount = swapChain.getImagesNum();
        acquiredPending = false; // rc5: fresh, unsignaled semaphores
        imageAvailableSemaphores = new ArrayList<>(framesNum);
        renderFinishedSemaphores = new ArrayList<>(imageCount);
        inFlightFences = new ArrayList<>(framesNum);

        try (MemoryStack stack = stackPush()) {

            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack);
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pSemaphore = stack.mallocLong(1);
            LongBuffer pFence = stack.mallocLong(1);

            for (int i = 0; i < framesNum; i++) {
                if (vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore) != VK_SUCCESS
                    || vkCreateFence(device, fenceInfo, null, pFence) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create synchronization objects for the frame: " + i);
                }
                imageAvailableSemaphores.add(pSemaphore.get(0));
                inFlightFences.add(pFence.get(0));
            }

            for (int i = 0; i < imageCount; i++) {
                if (vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore) != VK_SUCCESS)
                    throw new RuntimeException("Failed to create render-finished semaphore for swapchain image: " + i);
                renderFinishedSemaphores.add(pSemaphore.get(0));
            }
        }
    }

    public void preInitFrame() {
        Profiler p = Profiler.getMainProfiler();
        p.pop();
        p.round();
        p.push("Frame_ops");

        // runTick might be called recursively,
        // this check forces sync to avoid upload corruption
        if (lastReset == currentFrame) {
            waitFences();
        }
        lastReset = currentFrame;

        drawer.resetBuffers(currentFrame);

        WorldRenderer.getInstance().uploadSections();
        UploadManager.INSTANCE.submitUploads();
    }

    // Iroh (rc5, td-1cb256): under -ea, the render thread's LWJGL MemoryStack must be back where it was at beginFrame
    // when endFrame returns (a missing pop grows it each frame until "Out of stack space").
    private static final boolean STACK_CHECK;
    static {
        boolean ea = false;
        assert ea = true;
        STACK_CHECK = ea;
    }
    private long frameStackPointer = -1;
    private static boolean stackLeakLogged;

    private void checkStackBalance(String where) {
        long p = MemoryStack.stackGet().getPointer();
        if (where == null) {
            frameStackPointer = p;
            return;
        }
        if (frameStackPointer >= 0 && p != frameStackPointer && !stackLeakLogged) {
            stackLeakLogged = true;
            Initializer.LOGGER.error("Iroh: the render thread's MemoryStack moved by {} bytes between beginFrame and {} (a stackPush without pop, "
                    + "or a calloc on a caller's stack in a loop). Run with -Dorg.lwjgl.util.DebugStack=true to get the call site.",
                    frameStackPointer - p, where, new Throwable("checked here"));
            assert false : "MemoryStack leak on the render thread";
        }
    }

    private boolean inBeginFrame;

    public void beginFrame() {
        boolean outer = !inBeginFrame;
        if (STACK_CHECK && outer) checkStackBalance(null);
        inBeginFrame = true;
        try {
            beginFrame0();
        } finally {
            if (outer) inBeginFrame = false;
        }
    }

    private void beginFrame0() {
        dev.elemore.iroh.diag.RenderWatchdog.heartbeat(); // rc3r2 (td-3bc9d5): stall diagnostics
        dev.elemore.iroh.diag.LoadWatch.tick(); // rc5 (td-30c049): evidence if a world load hangs
        dev.elemore.iroh.memory.MemoryGuard.tick(); // rc8: device-memory cap / out-of-memory exit
        if (dev.elemore.iroh.diag.MemStats.ON) dev.elemore.iroh.diag.MemStats.frame(); // rc7
        if (dev.elemore.iroh.diag.ImageTrace.ON) dev.elemore.iroh.diag.ImageTrace.frame(); // rc13
        Profiler p = Profiler.getMainProfiler();
        p.pop();
        p.push("Frame_fence");

        // Iroh (R-1): a 0x0 swapchain sets skipRendering (and Minecraft.noRender) until the next swapchain
        // update, which used to come only from a GLFW framebuffer-size event. A surface that regains its
        // extent without that event left the game frozen on the last presented frame (no acquire, no
        // render, no present). Poll the surface while skipping instead.
        if (skipRendering && !swapChainUpdate && swapChain.getWidth() == 0 && SwapChain.surfaceHasExtent()) {
            Initializer.LOGGER.info("Iroh: surface extent is back while rendering was skipped; recreating the swapchain");
            swapChainUpdate = true;
        }

        if (swapChainUpdate) {
            recreateSwapChain();
            swapChainUpdate = false;

            if (getSwapChain().getWidth() == 0 || getSwapChain().getHeight() == 0) {
                Initializer.LOGGER.info("Iroh: swapchain extent is 0, skipping rendering until the surface has a size");
                skipRendering = true;
                Minecraft.getInstance().noRender = true;
            } else {
                skipRendering = false;
                Minecraft.getInstance().noRender = false;
            }
        }


        if (skipRendering || recordingCmds)
            return;

        waitFrameFence(); // rc8: bounded
        // perf/uploads: the frame that last used this slot finished; complete finished upload epochs (no wait) and make
        // sure everything submitted before that frame is done before the slot's deferred frees run below.
        Synchronization.INSTANCE.frameSlotDone(currentFrame);

        // Iroh (rc5, td-08dc9f): the previous frame acquired a swapchain image but never submitted (an exception between
        // acquire and submit, e.g. the td-1cb256 OutOfMemoryError in endFrame). Its acquire semaphore is still signaled;
        // acquiring with it again is invalid ("semaphore still signaled"). Replace that slot's semaphore.
        if (acquiredPending)
            recoverUnsubmittedAcquire();

        p.pop();
        p.push("Begin_rendering");

        MemoryManager.getInstance().initFrame(currentFrame);
        drawer.setCurrentFrame(currentFrame);

        resetDescriptors();

        currentCmdBuffer = commandBuffers.get(currentFrame);
        vkResetCommandBuffer(currentCmdBuffer, 0);

        try (MemoryStack stack = stackPush()) {

            IntBuffer pImageIndex = stack.mallocInt(1);

            int vkResult = dev.elemore.iroh.memory.MemoryGuard.check(vkAcquireNextImageKHR(device, swapChain.getId(), VUtil.UINT64_MAX,
                                                 imageAvailableSemaphores.get(currentFrame), VK_NULL_HANDLE, pImageIndex), "vkAcquireNextImageKHR");

            if (vkResult == VK_SUBOPTIMAL_KHR || vkResult == VK_ERROR_OUT_OF_DATE_KHR || swapChainUpdate) {
                swapChainUpdate = true;
                skipRendering = true;
                beginFrame();

                return;
            } else if (vkResult != VK_SUCCESS) {
                throw new RuntimeException("Cannot acquire next swap chain image: %s".formatted(VkResult.decode(vkResult)));
            }

            imageIndex = pImageIndex.get(0);
            acquiredPending = true;

            this.beginRenderPass(stack);
        }

        p.pop();
    }

    private void beginRenderPass(MemoryStack stack) {
        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
        beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
        beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

        VkCommandBuffer commandBuffer = currentCmdBuffer;

        int vkResult = vkBeginCommandBuffer(commandBuffer, beginInfo);
        if (vkResult != VK_SUCCESS) {
            throw new RuntimeException("Failed to begin recording command buffer: %s".formatted(VkResult.decode(vkResult)));
        }

        recordingCmds = true;
        // Iroh (P1-13a): GPU timestamps start outside the render pass
        if (dev.elemore.iroh.apiimpl.FrameProfiler.ON)
            dev.elemore.iroh.apiimpl.FrameProfiler.frameStart(commandBuffer, currentFrame);
        // Iroh (td-4a9be3): a new frame command buffer; uses recorded in the previous one have executed or will
        // execute before anything recorded from now on (see VulkanImage.uploadOwned / frameUse).
        net.vulkanmod.vulkan.texture.VulkanImage.nextFrameSerial();
        mainPass.begin(commandBuffer, stack);

        resetDynamicState(commandBuffer);

        // Iroh (P1-13a): frame hooks, FRAME_BEGIN (main target bound, nothing drawn yet)
        dev.elemore.iroh.apiimpl.FrameHooks.beginFrame(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false),
                swapChain.getWidth(), swapChain.getHeight());
        dev.elemore.iroh.apiimpl.FrameHooks.dispatch(dev.elemore.iroh.api.FrameStage.FRAME_BEGIN);
    }

    public void endFrame() {
        if (skipRendering || !recordingCmds)
            return;

        Profiler p = Profiler.getMainProfiler();
        p.push("End_rendering");

        // perf/uploads: queued in-frame uploads are recorded before the frame command buffer ends
        net.vulkanmod.vulkan.texture.VulkanImage.flushInFrame();

        // Iroh (rc3r2, td-7937f0): mainPass.end ends the command buffer; from here on nothing may record into it, even if
        // the submit below throws (device lost / out of date during a world teardown).
        try {
            mainPass.end(currentCmdBuffer);
        } finally {
            recordingCmds = false;
        }

        // perf/uploads: submit this frame's uploads and close its epoch; no CPU wait (was a full fence wait every frame)
        StagingBuffer.flushConsumers(currentFrame);

        submitFrame();

        // Iroh (P1-13a): AFTER_PRESENT (no command buffer is recording; CPU-side work only)
        dev.elemore.iroh.apiimpl.FrameHooks.dispatch(dev.elemore.iroh.api.FrameStage.AFTER_PRESENT);
        if (STACK_CHECK) checkStackBalance("endFrame");

        p.pop();
        p.push("Post_rendering");
    }

    /**
     * rc8 (SYSTEM SAFETY): the frame fence is waited at most 10 s (-Diroh.fenceTimeoutS). A GPU that does not finish a
     * frame in that time is hung or lost; the game stops with a clear error instead of freezing forever.
     */
    private void waitFrameFence() {
        int r = vkWaitForFences(device, inFlightFences.get(currentFrame), true, Long.getLong("iroh.fenceTimeoutS", 10L) * 1_000_000_000L);
        if (r == VK_TIMEOUT)
            throw new IllegalStateException("Iroh: the GPU did not finish a frame within the fence timeout (hung or lost device)");
        if (r != VK_SUCCESS)
            throw new IllegalStateException("Iroh: vkWaitForFences failed: " + r + " (device lost?)");
    }

    /** Iroh (rc5): a swapchain image was acquired with imageAvailableSemaphores[currentFrame] and not yet submitted. */
    private boolean acquiredPending;
    private static boolean loggedAcquireRecovery;

    private void recoverUnsubmittedAcquire() {
        acquiredPending = false;
        if (!loggedAcquireRecovery) {
            loggedAcquireRecovery = true;
            Initializer.LOGGER.warn("Iroh: the last frame acquired a swapchain image but was never submitted (an error in the frame); "
                    + "recreating its acquire semaphore (logged once)");
        }
        vkDeviceWaitIdle(device);
        // the frame that did not submit did not advance currentFrame, so its semaphore is this slot's
        long old = imageAvailableSemaphores.get(currentFrame);
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer p = stack.mallocLong(1);
            if (vkCreateSemaphore(device, info, null, p) == VK_SUCCESS) {
                vkDestroySemaphore(device, old, null);
                imageAvailableSemaphores.set(currentFrame, p.get(0));
            }
        }
    }

    private void submitFrame() {
        if (swapChainUpdate)
            return;

        try (MemoryStack stack = stackPush()) {
            int vkResult;

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);

            // perf/uploads: also wait (on the GPU) for the transfer-queue uploads submitted since the last frame
            long[] uploadWaits = Synchronization.INSTANCE.takeWaitSemaphores();
            java.nio.LongBuffer waits = stack.mallocLong(1 + uploadWaits.length);
            java.nio.IntBuffer stages = stack.mallocInt(1 + uploadWaits.length);
            waits.put(imageAvailableSemaphores.get(currentFrame));
            stages.put(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            for (long w : uploadWaits) {
                waits.put(w);
                stages.put(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
            }
            waits.flip();
            stages.flip();
            submitInfo.waitSemaphoreCount(waits.remaining());
            submitInfo.pWaitSemaphores(waits);
            submitInfo.pWaitDstStageMask(stages);
            submitInfo.pSignalSemaphores(stack.longs(renderFinishedSemaphores.get(imageIndex)));
            submitInfo.pCommandBuffers(stack.pointers(currentCmdBuffer));

            vkResetFences(device, inFlightFences.get(currentFrame));

            if ((vkResult = dev.elemore.iroh.memory.MemoryGuard.check(vkQueueSubmit(DeviceManager.getGraphicsQueue().queue(), submitInfo, inFlightFences.get(currentFrame)), "frame vkQueueSubmit")) != VK_SUCCESS) {
                vkResetFences(device, inFlightFences.get(currentFrame));
                throw new RuntimeException("Failed to submit draw command buffer: %s".formatted(VkResult.decode(vkResult)));
            }
            acquiredPending = false; // rc5: the submit waits the acquire semaphore
            Synchronization.INSTANCE.afterFrameSubmit();

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);

            presentInfo.pWaitSemaphores(stack.longs(renderFinishedSemaphores.get(imageIndex)));

            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(swapChain.getId()));

            presentInfo.pImageIndices(stack.ints(imageIndex));

            vkResult = dev.elemore.iroh.memory.MemoryGuard.check(vkQueuePresentKHR(DeviceManager.getPresentQueue().queue(), presentInfo), "vkQueuePresentKHR");

            if (vkResult == VK_ERROR_OUT_OF_DATE_KHR || vkResult == VK_SUBOPTIMAL_KHR || swapChainUpdate) {
                swapChainUpdate = true;
                return;
            } else if (vkResult != VK_SUCCESS) {
                throw new RuntimeException("Failed to present rendered frame: %s".formatted(VkResult.decode(vkResult)));
            }

            currentFrame = (currentFrame + 1) % framesNum;
        }
    }

    /**
     * Called in case draw results are needed before the end of the frame
     */
    public void flushCmds() {
        if (!this.recordingCmds)
            return;
        net.vulkanmod.vulkan.texture.VulkanImage.flushInFrame(); // perf/uploads

        try (MemoryStack stack = stackPush()) {
            int vkResult;

            this.endRenderPass(currentCmdBuffer);
            vkEndCommandBuffer(currentCmdBuffer);
            this.recordingCmds = false; // Iroh (rc3r2): ended; beginRenderPass below sets it again

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);

            submitInfo.pCommandBuffers(stack.pointers(currentCmdBuffer));

            vkResetFences(device, inFlightFences.get(currentFrame));

            waitFences();

            if ((vkResult = dev.elemore.iroh.memory.MemoryGuard.check(vkQueueSubmit(DeviceManager.getGraphicsQueue().queue(), submitInfo, inFlightFences.get(currentFrame)), "frame vkQueueSubmit")) != VK_SUCCESS) {
                vkResetFences(device, inFlightFences.get(currentFrame));
                throw new RuntimeException("Failed to submit draw command buffer: %s".formatted(VkResult.decode(vkResult)));
            }

            waitFrameFence(); // rc8: bounded

            this.beginRenderPass(stack);
        }
    }

    public void endRenderPass() {
        endRenderPass(currentCmdBuffer);
    }

    public void endRenderPass(VkCommandBuffer commandBuffer) {
        if ((dev.elemore.iroh.shaderpack.runtime.Substitution.HOOK & 2) != 0) // P3-11: the world pass's attachments close first
            dev.elemore.iroh.shaderpack.runtime.SubstitutionHook.closeForForeign();
        if (skipRendering || !recordingCmds || this.boundFramebuffer == null)
            return;

        if (!DYNAMIC_RENDERING)
            this.boundRenderPass.endRenderPass(currentCmdBuffer);
        else
            KHRDynamicRendering.vkCmdEndRenderingKHR(commandBuffer);

        this.boundRenderPass = null;
        this.boundFramebuffer = null;

        VkGlFramebuffer.resetBoundFramebuffer();
    }

    public boolean beginRendering(RenderPass renderPass, Framebuffer framebuffer) {
        if (skipRendering || !recordingCmds)
            return false;
        if ((dev.elemore.iroh.shaderpack.runtime.Substitution.HOOK & 2) != 0) // P3-11: never begin a pass inside the world pass's attachments
            dev.elemore.iroh.shaderpack.runtime.SubstitutionHook.closeForForeign();
        // perf/uploads: queued in-frame uploads go before an off-screen pass that may render into or sample them
        if (this.boundFramebuffer != framebuffer && net.vulkanmod.vulkan.texture.VulkanImage.anyInFramePending())
            net.vulkanmod.vulkan.texture.VulkanImage.flushInFrame();

        if (this.boundFramebuffer != framebuffer) {
            this.endRenderPass(currentCmdBuffer);

            try (MemoryStack stack = stackPush()) {
                framebuffer.beginRenderPass(currentCmdBuffer, renderPass, stack);
            }

            this.boundFramebuffer = framebuffer;
        }
        return true;
    }

    public void addUsedPipeline(Pipeline pipeline) {
        usedPipelines.add(pipeline);
    }

    public void removeUsedPipeline(Pipeline pipeline) {
        usedPipelines.remove(pipeline);
    }

    private void waitFences() {
        // Make sure there are no uploads/transitions scheduled.
        // Iroh (R-3, RQ-18): the shared staging buffer may be reset here, before the frame command buffer is
        // submitted, because only upload command buffers read it (invariant in StagingBuffer, checked at every
        // recording site). flushConsumersAndReset submits all of them (UploadManager and IndirectBuffer included,
        // which the base left out) and waits before the reset.
        StagingBuffer.flushConsumersAndReset();
    }

    /** Iroh (G1 fix): something recorded its own pipeline into the frame command buffer: the next bind must not be skipped */
    public void invalidateBoundState() {
        boundPipeline = null;
        boundPipelineHandle = 0;
    }

    private void resetDescriptors() {
        for (Pipeline pipeline : usedPipelines) {
            pipeline.resetDescriptorPool(currentFrame);
        }

        usedPipelines.clear();
        boundPipeline = null;
        boundPipelineHandle = 0;
    }

    void waitForSwapChain() {
        vkResetFences(device, inFlightFences.get(currentFrame));

//        constexpr VkPipelineStageFlags t=VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            //Empty Submit
            VkSubmitInfo info = VkSubmitInfo.calloc(stack)
                                            .sType$Default()
                                            .pWaitSemaphores(stack.longs(imageAvailableSemaphores.get(currentFrame)))
                                            .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT));

            dev.elemore.iroh.memory.MemoryGuard.check(vkQueueSubmit(DeviceManager.getGraphicsQueue().queue(), info, inFlightFences.get(currentFrame)), "vkQueueSubmit (unsignal)");
            vkWaitForFences(device, inFlightFences.get(currentFrame), true, -1);
        }
    }

    @SuppressWarnings("UnreachableCode")
    private int lastResizeW = -1, lastResizeH = -1;

    private void recreateSwapChain() {
        waitFences();
        Vulkan.waitIdle();
        Synchronization.INSTANCE.deviceIdle(); // rc12: submitted frames are done: complete their epochs

        commandBuffers.forEach(commandBuffer -> vkResetCommandBuffer(commandBuffer, 0));
        recordingCmds = false;
        // Iroh (rc3r2, td-7937f0): the reset command buffers hold no render pass any more: forget the bound target, so
        // clears / viewport / scissor between here and the next frame do not record into a reset buffer.
        this.boundFramebuffer = null;
        this.boundRenderPass = null;
        net.vulkanmod.gl.VkGlFramebuffer.resetBoundFramebuffer();

        swapChain.recreate();

        //Semaphores need to be recreated in order to make them unsignaled
        destroySyncObjects();

        int newFramesNum = Initializer.CONFIG.frameQueueSize;

        if (framesNum != newFramesNum) {
            UploadManager.INSTANCE.submitUploads();

            framesNum = newFramesNum;
            MemoryManager.getInstance().freeAllBuffers();
            MemoryManager.createInstance(newFramesNum);
            createStagingBuffers();
            allocateCommandBuffers();

            Pipeline.recreateDescriptorSets(framesNum);

            drawer.createResources(framesNum);
        }

        createSyncObjects();
        this.mainPass.onResize();

        // rc12 (td-598433): only a REAL size change resizes the game's render targets. The base called resizeDisplay()
        // on every swapchain recreation, including OUT_OF_DATE / SUBOPTIMAL ones at the same extent (gamescope, focus
        // changes), so every post chain and mod render target (Satin, GPUBooster...) was destroyed and recreated, the
        // "resize storm" that multiplies any per-resize leak.
        int newW = swapChain.getWidth(), newH = swapChain.getHeight();
        boolean sizeChanged = newW != this.lastResizeW || newH != this.lastResizeH;
        this.lastResizeW = newW;
        this.lastResizeH = newH;
        this.onResizeCallbacks.forEach(Runnable::run);
        if (sizeChanged)
            ((WindowAccessor) (Object) Minecraft.getInstance().getWindow()).getEventHandler().resizeDisplay();

        currentFrame = 0;
    }

    public void cleanUpResources() {
        WorldRenderer.getInstance().cleanUp();
        destroySyncObjects();

        drawer.cleanUpResources();
        mainPass.cleanUp();
        swapChain.cleanUp();

        PipelineManager.destroyPipelines();
        VTextureSelector.getWhiteTexture().free();
    }

    private void destroySyncObjects() {
        for (int i = 0; i < framesNum; ++i) {
            vkDestroyFence(device, inFlightFences.get(i), null);
            vkDestroySemaphore(device, imageAvailableSemaphores.get(i), null);
        }
        for (long semaphore : renderFinishedSemaphores) {
            vkDestroySemaphore(device, semaphore, null);
        }
    }

    public void addOnResizeCallback(Runnable runnable) {
        this.onResizeCallbacks.add(runnable);
    }

    public void bindGraphicsPipeline(GraphicsPipeline pipeline) {
        // Iroh P3-10/P3-11: pack program substitution (observe, or the world pass binding the pack pipeline for this draw's
        // class instead); default off: one static volatile read
        if (dev.elemore.iroh.shaderpack.runtime.Substitution.HOOK != 0
                && dev.elemore.iroh.shaderpack.runtime.SubstitutionHook.bind(pipeline))
            return;
        VkCommandBuffer commandBuffer = recordingCommandBuffer("bindGraphicsPipeline");
        if (commandBuffer == null) return;

        PipelineState currentState = PipelineState.getCurrentPipelineState(boundRenderPass);
        final long handle = pipeline.getHandle(currentState);

        if (boundPipelineHandle == handle) {
            return;
        }

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, handle);
        boundPipelineHandle = handle;
        boundPipeline = pipeline;
        addUsedPipeline(pipeline);
    }

    public void uploadAndBindUBOs(Pipeline pipeline) {
        // Iroh P3-11: a substituted draw binds the pack program's descriptor sets instead (one static volatile read when off)
        if (dev.elemore.iroh.shaderpack.runtime.Substitution.HOOK != 0
                && dev.elemore.iroh.shaderpack.runtime.SubstitutionHook.beforeDraw(pipeline))
            return;
        VkCommandBuffer commandBuffer = recordingCommandBuffer("uploadAndBindUBOs");
        if (commandBuffer == null) return;
        pipeline.bindDescriptorSets(commandBuffer, currentFrame);
    }

    public void pushConstants(Pipeline pipeline) {
        VkCommandBuffer commandBuffer = recordingCommandBuffer("pushConstants");
        if (commandBuffer == null) return;

        PushConstants pushConstants = pipeline.getPushConstants();

        try (MemoryStack stack = stackPush()) {
            ByteBuffer buffer = stack.malloc(pushConstants.getSize());
            long ptr = MemoryUtil.memAddress0(buffer);
            pushConstants.update(ptr);

            nvkCmdPushConstants(commandBuffer, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, pushConstants.getSize(), ptr);
        }

    }

    public Pipeline getBoundPipeline() {
        return boundPipeline;
    }

    public void setBoundFramebuffer(Framebuffer framebuffer) {
        this.boundFramebuffer = framebuffer;
    }

    public Framebuffer getBoundFramebuffer() {
        return boundFramebuffer;
    }

    public void setBoundRenderPass(RenderPass boundRenderPass) {
        this.boundRenderPass = boundRenderPass;
    }

    public RenderPass getBoundRenderPass() {
        return boundRenderPass;
    }

    public void setMainPass(MainPass mainPass) {
        this.mainPass = mainPass;
    }

    public MainPass getMainPass() {
        return this.mainPass;
    }

    public SwapChain getSwapChain() {
        return swapChain;
    }

    private static void resetDynamicState(VkCommandBuffer commandBuffer) {
        vkCmdSetDepthBias(commandBuffer, 0.0F, 0.0F, 0.0F);

        vkCmdSetLineWidth(commandBuffer, 1.0F);
    }

    public static void setDepthBias(float constant, float slope) {
        VkCommandBuffer commandBuffer = recordingCommandBuffer("setDepthBias");
        if (commandBuffer == null) return;

        vkCmdSetDepthBias(commandBuffer, constant, 0.0f, slope);
    }

    public static void clearAttachments(int v) {
        Framebuffer framebuffer = Renderer.getInstance().boundFramebuffer;
        if (framebuffer == null)
            return;

        clearAttachments(v, framebuffer.getWidth(), framebuffer.getHeight());
    }

    public static void clearAttachments(int v, int width, int height) {
        if (skipRendering)
            return;
        VkCommandBuffer cb = recordingCommandBuffer("clearAttachments");
        if (cb == null)
            return;

        try (MemoryStack stack = stackPush()) {
            //ClearValues have to be different for each attachment to clear,
            //it seems it uses the same buffer: color and depth values override themselves
            VkClearValue colorValue = VkClearValue.calloc(stack);
            colorValue.color().float32(VRenderSystem.clearColor);

            VkClearValue depthValue = VkClearValue.calloc(stack);
            depthValue.depthStencil().set(VRenderSystem.clearDepthValue, 0); //Use fast depth clears if possible

            int attachmentsCount = v == (GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT) ? 2 : 1;
            final VkClearAttachment.Buffer pAttachments = VkClearAttachment.malloc(attachmentsCount, stack);
            switch (v) {
                case GL_DEPTH_BUFFER_BIT -> {

                    VkClearAttachment clearDepth = pAttachments.get(0);
                    clearDepth.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                    clearDepth.colorAttachment(0);
                    clearDepth.clearValue(depthValue);
                }
                case GL_COLOR_BUFFER_BIT -> {

                    VkClearAttachment clearColor = pAttachments.get(0);
                    clearColor.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                    clearColor.colorAttachment(0);
                    clearColor.clearValue(colorValue);
                }
                case GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT -> {

                    VkClearAttachment clearColor = pAttachments.get(0);
                    clearColor.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                    clearColor.colorAttachment(0);
                    clearColor.clearValue(colorValue);

                    VkClearAttachment clearDepth = pAttachments.get(1);
                    clearDepth.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                    clearDepth.colorAttachment(0);
                    clearDepth.clearValue(depthValue);
                }
                default -> throw new RuntimeException("unexpected value");
            }

            //Rect to clear
            VkRect2D renderArea = VkRect2D.malloc(stack);
            renderArea.offset().set(0, 0);
            renderArea.extent().set(width, height);

            VkClearRect.Buffer pRect = VkClearRect.malloc(1, stack);
            pRect.rect(renderArea);
            pRect.baseArrayLayer(0);
            pRect.layerCount(1);

            vkCmdClearAttachments(cb, pAttachments, pRect);
        }
    }

    public static void setInvertedViewport(int x, int y, int width, int height) {
        // Iroh (RQ-11, rc2): GL row order on purpose (post passes, off-screen FBO begin), conventions in
        // dev.elemore.iroh.glshim.Orientation. Applied directly: going through GlStateManager._viewport picks the
        // layout from the bound target, which is not bound yet here.
        float[] v = dev.elemore.iroh.glshim.Orientation.vkViewport(dev.elemore.iroh.glshim.Orientation.Target.OFFSCREEN,
                x, y, width, height, 0);
        setVkViewport(v[0], v[1], v[2], v[3]);
        dev.elemore.iroh.glshim.GlState.recordViewport(x, y, width, height);
    }

    /** Iroh (rc2): a Vulkan viewport exactly as given (sign included); callers get the values from Orientation. */
    public static void setVkViewport(float x, float y, float width, float height) {
        if (!INSTANCE.recordingCmds)
            return;
        try (MemoryStack stack = stackPush()) {
            VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
            viewport.x(x);
            viewport.y(y);
            viewport.width(width);
            viewport.height(height);
            viewport.minDepth(0.0f);
            viewport.maxDepth(1.0f);
            { VkCommandBuffer cb = recordingCommandBuffer("setViewport"); if (cb != null) vkCmdSetViewport(cb, 0, viewport); }
        }
    }

    /** Iroh (rc2): a Vulkan scissor exactly as given (from Orientation.vkScissor). */
    public static void setVkScissor(int x, int y, int width, int height) {
        if (INSTANCE.boundFramebuffer == null || !INSTANCE.recordingCmds)
            return;
        try (MemoryStack stack = stackPush()) {
            VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
            scissor.offset().set(x, y);
            scissor.extent().set(width, height);
            { VkCommandBuffer cb = recordingCommandBuffer("setScissor"); if (cb != null) vkCmdSetScissor(cb, 0, scissor); }
        }
    }

    public static void resetViewport() {
        int width = INSTANCE.getSwapChain().getWidth();
        int height = INSTANCE.getSwapChain().getHeight();

        setViewportState(0, 0, width, height);
    }

    public static void setViewportState(int x, int y, int width, int height) {
        GlStateManager._viewport(x, y, width, height);
    }

    public static void setViewport(int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            setViewport(x, y, width, height, stack);
        }
    }

    public static void setViewport(int x, int y, int width, int height, MemoryStack stack) {
        if (!INSTANCE.recordingCmds)
            return;

        VkViewport.Buffer viewport = VkViewport.malloc(1, stack);
        viewport.x(x);
        viewport.y(height + y);
        viewport.width(width);
        viewport.height(-height);
        viewport.minDepth(0.0f);
        viewport.maxDepth(1.0f);

        { VkCommandBuffer cb = recordingCommandBuffer("setViewport"); if (cb != null) vkCmdSetViewport(cb, 0, viewport); }
    }

    public static void setScissor(int x, int y, int width, int height) {
        if (INSTANCE.boundFramebuffer == null)
            return;

        try (MemoryStack stack = stackPush()) {
            int framebufferHeight = INSTANCE.boundFramebuffer.getHeight();

            x = Math.max(0, x);

            VkRect2D.Buffer scissor = VkRect2D.malloc(1, stack);
            scissor.offset().set(x, framebufferHeight - (y + height));
            scissor.extent().set(width, height);

            { VkCommandBuffer cb = recordingCommandBuffer("setScissor"); if (cb != null) vkCmdSetScissor(cb, 0, scissor); }
        }
    }

    public static void resetScissor() {
        if (INSTANCE.boundFramebuffer == null)
            return;

        try (MemoryStack stack = stackPush()) {
            VkRect2D.Buffer scissor = INSTANCE.boundFramebuffer.scissor(stack);
            { VkCommandBuffer cb = recordingCommandBuffer("setScissor"); if (cb != null) vkCmdSetScissor(cb, 0, scissor); }
        }
    }

    public static void pushDebugSection(String s) {
        if (Vulkan.ENABLE_VALIDATION_LAYERS) {
            VkCommandBuffer commandBuffer = recordingCommandBuffer("pushDebugSection");
            if (commandBuffer == null) return;

            try (MemoryStack stack = stackPush()) {
                VkDebugUtilsLabelEXT markerInfo = VkDebugUtilsLabelEXT.calloc(stack);
                markerInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT);
                ByteBuffer string = stack.UTF8(s);
                markerInfo.pLabelName(string);
                vkCmdBeginDebugUtilsLabelEXT(commandBuffer, markerInfo);
            }
        }
    }

    public static void popDebugSection() {
        if (Vulkan.ENABLE_VALIDATION_LAYERS) {
            VkCommandBuffer commandBuffer = recordingCommandBuffer("popDebugSection");
            if (commandBuffer == null) return;

            vkCmdEndDebugUtilsLabelEXT(commandBuffer);
        }
    }

    public static void popPushDebugSection(String s) {
        popDebugSection();
        pushDebugSection(s);
    }

    public static int getFramesNum() {
        return INSTANCE.framesNum;
    }

    public static VkCommandBuffer getCommandBuffer() {
        return INSTANCE.currentCmdBuffer;
    }

    private static final java.util.Set<String> LOGGED_NOT_RECORDING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Iroh (rc3r2, td-7937f0): THE check before recording into the frame command buffer. Returns it only while a frame
     * is recording (begun, not yet ended / reset); otherwise null, and the first call per {@code what} logs where it came
     * from. Every Renderer entry point that records goes through this, so a skipped frame, a swapchain recreate, a
     * world teardown or a disconnect screen turns stray GL-emulation calls into no-ops instead of commands into a
     * command buffer that is not recording (validation "vkBeginCommandBuffer() was not called", then a driver crash).
     */
    public static VkCommandBuffer recordingCommandBuffer(String what) {
        Renderer r = INSTANCE;
        if (r != null && r.recordingCmds && r.currentCmdBuffer != null)
            return r.currentCmdBuffer;
        if (LOGGED_NOT_RECORDING.add(what))
            Initializer.LOGGER.warn("Iroh: {} outside a recording frame ignored (logged once)", what, new Throwable("called from"));
        return null;
    }

    public static boolean isRecording() {
        return INSTANCE.recordingCmds;
    }

    public static void scheduleSwapChainUpdate() {
        swapChainUpdate = true;
    }
}
