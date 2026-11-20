package dev.elemore.iroh.glshim;

import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.render.texture.ImageUploadHelper;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SceneTarget;
import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * Host-visible results in the middle of a frame (P1-2 slice 2). GL readbacks (glGetTexImage, glReadPixels into
 * client memory) return what every earlier GL call produced. Under Iroh those calls are still sitting in two command
 * buffers: the upload buffer (ImageUploadHelper, executes first) and the frame buffer. Reading an image through a
 * separate one-shot command buffer without submitting them returns stale data and breaks layout tracking.
 */
public final class FrameSync {
    private FrameSync() {}

    /** Submits the upload command buffer and waits for it (and every other tracked fence). */
    public static void submitUploads() {
        ImageUploadHelper.INSTANCE.submitCommands();
        Synchronization.INSTANCE.waitFences();
    }

    /**
     * Submits and waits for everything recorded so far in this frame, then resumes recording with the same target
     * bound (Renderer.flushCmds alone resumes on the main target and resets dynamic state).
     */
    public static void flushFrame() {
        if (!Renderer.isRecording()) {
            submitUploads();
            return;
        }
        Renderer r = Renderer.getInstance();
        RenderPass pass = r.getBoundRenderPass();
        Framebuffer fb = r.getBoundFramebuffer();
        VkGlFramebuffer glFb = VkGlFramebuffer.getBoundFramebuffer();

        r.flushCmds();

        if (pass != null && fb != null && !(fb instanceof SceneTarget)) {
            r.beginRendering(pass, fb);
            VkGlFramebuffer.restoreBinding(glFb);
        }
        GlState.reapplyViewportAndScissor();
        GlState.reapplyDynamicState();
    }

    /** Before {@code image} is read by a one-shot command buffer (download to host memory). */
    public static void beforeHostRead(VulkanImage image) {
        if (Renderer.isRecording() && image != null && image.usedThisFrame())
            flushFrame();
        else
            submitUploads();
    }
}
