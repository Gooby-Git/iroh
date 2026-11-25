package net.vulkanmod.vulkan.framebuffer;

import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class RenderPass {
    Framebuffer framebuffer;
    long id;
    /** V-3b: render pass variants with GENERAL attachment layouts (index = feedback mask: 1 colour, 2 depth); 0 = {@link #id}. */
    private final long[] feedbackIds = new long[4];

    /**
     * V-3b (td-fe54e9): feedback loop. An image sampled while it is an attachment of the bound pass: the pass is ended and
     * restarted with that attachment in GENERAL (initial, subpass and final-to-tracker transitions recorded in the frame
     * command buffer before the restart). {@code pending*} = requested for the next beginRenderPass of a framebuffer that
     * has the image; {@code active*} = the images GENERAL in the pass now. One bound pass at a time, so static.
     */
    private static VulkanImage pendingColor, pendingDepth, activeColor, activeDepth;
    private int activeMask;

    /** the image is an attachment of the bound pass and is GENERAL for it (a feedback loop already set up) */
    public static boolean isFeedbackActive(VulkanImage image) {
        return image != null && (image == activeColor || image == activeDepth);
    }

    /** request GENERAL for {@code image} in the next pass begun on a framebuffer that attaches it */
    public static void requestFeedback(VulkanImage image, boolean depth) {
        if (depth) pendingDepth = image; else pendingColor = image;
    }

    final int attachmentCount;
    /** P2-11: which subpass dependency createRenderPass wrote (0 none, 1 PRESENT_SRC external -> 0, 2 SHADER_READ_ONLY 0 -> external); dependencies take part in render pass compatibility */
    private final int dependencyKind;
    AttachmentInfo colorAttachmentInfo;
    AttachmentInfo depthAttachmentInfo;

    public RenderPass(Framebuffer framebuffer, AttachmentInfo colorAttachmentInfo, AttachmentInfo depthAttachmentInfo) {
        this.framebuffer = framebuffer;
        this.colorAttachmentInfo = colorAttachmentInfo;
        this.depthAttachmentInfo = depthAttachmentInfo;

        int count = 0;
        if (colorAttachmentInfo != null)
            count++;
        if (depthAttachmentInfo != null)
            count++;

        this.attachmentCount = count;
        this.dependencyKind = colorAttachmentInfo == null ? 0
                : colorAttachmentInfo.finalLayout == VK_IMAGE_LAYOUT_PRESENT_SRC_KHR ? 1
                : colorAttachmentInfo.finalLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL ? 2 : 0;

        if (!Vulkan.DYNAMIC_RENDERING) {
            this.id = createRenderPass(0);
        }

    }

    private long idFor(int mask) {
        if (mask == 0) return this.id;
        if (feedbackIds[mask] == 0L) feedbackIds[mask] = createRenderPass(mask);
        return feedbackIds[mask];
    }

    private long createRenderPass(int feedbackMask) {
        final int colorLayout = (feedbackMask & 1) != 0 ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        final int depthLayout = (feedbackMask & 2) != 0 ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(attachmentCount, stack);
            VkAttachmentReference.Buffer attachmentRefs = VkAttachmentReference.calloc(attachmentCount, stack);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS);

            int i = 0;

            // Color attachment
            if (colorAttachmentInfo != null) {
                VkAttachmentDescription colorAttachment = attachments.get(i);
                colorAttachment.format(colorAttachmentInfo.format)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(colorAttachmentInfo.loadOp)
                        .storeOp(colorAttachmentInfo.storeOp)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(colorLayout)
                        .finalLayout((feedbackMask & 1) != 0 ? VK_IMAGE_LAYOUT_GENERAL : colorAttachmentInfo.finalLayout); // variant: stay GENERAL, no end transition (sync-validation WAW hazard, F-2); the tracker says GENERAL after the pass

                VkAttachmentReference colorAttachmentRef = attachmentRefs.get(0)
                        .attachment(0)
                        .layout(colorLayout);

                subpass.colorAttachmentCount(1);
                subpass.pColorAttachments(VkAttachmentReference.calloc(1, stack).put(0, colorAttachmentRef));

                ++i;
            }

            // Depth-Stencil attachment
            if (depthAttachmentInfo != null) {
                VkAttachmentDescription depthAttachment = attachments.get(i);
                depthAttachment.format(depthAttachmentInfo.format)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(depthAttachmentInfo.loadOp)
                        .storeOp(depthAttachmentInfo.storeOp)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(depthLayout)
                        .finalLayout((feedbackMask & 2) != 0 ? VK_IMAGE_LAYOUT_GENERAL : depthAttachmentInfo.finalLayout);

                VkAttachmentReference depthAttachmentRef = attachmentRefs.get(1)
                        .attachment(1)
                        .layout(depthLayout);

                subpass.pDepthStencilAttachment(depthAttachmentRef);
            }

            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack);
            renderPassInfo.sType$Default()
                    .pAttachments(attachments)
                    .pSubpasses(subpass);

            //Layout transition subpass depency
            switch (colorAttachmentInfo.finalLayout) {
                case VK_IMAGE_LAYOUT_PRESENT_SRC_KHR -> {
                    VkSubpassDependency.Buffer subpassDependencies = VkSubpassDependency.calloc(1, stack);
                    subpassDependencies.get(0)
                            .srcSubpass(VK_SUBPASS_EXTERNAL)
                            .dstSubpass(0)
                            .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                            .dstStageMask(VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT)
                            .srcAccessMask(0)
                            .dstAccessMask(0);

                    renderPassInfo.pDependencies(subpassDependencies);
                }
                case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                    VkSubpassDependency.Buffer subpassDependencies = VkSubpassDependency.calloc(1, stack);
                    subpassDependencies.get(0)
                            .srcSubpass(0)
                            .dstSubpass(VK_SUBPASS_EXTERNAL)
                            .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                            .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                            .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

                    renderPassInfo.pDependencies(subpassDependencies);
                }
            }

            LongBuffer pRenderPass = stack.mallocLong(1);

            if (dev.elemore.iroh.memory.MemoryGuard.check(vkCreateRenderPass(Vulkan.getVkDevice(), renderPassInfo, null, pRenderPass), "vkCreateRenderPass") != VK_SUCCESS) {
                throw new RuntimeException("Failed to create render pass");
            }

            return pRenderPass.get(0);
        }
    }

    /**
     * V-3 (td-fe54e9) invariant: the pass's initialLayout is the attachment layout, so each attachment goes from its TRACKED
     * layout to it here, in the buffer that records the pass (compare and skip: no barrier when already there), and is
     * marked used by the frame (see VulkanImage.markAttachmentUse). endRenderPass sets the tracker to finalLayout.
     */
    private void prepareAttachments(VkCommandBuffer commandBuffer, MemoryStack stack) {
        // V-3b: an attachment requested as a feedback target (sampled while attached) is GENERAL for this pass
        int mask = 0;
        VulkanImage color = null, depth = null;
        if (colorAttachmentInfo != null) {
            color = framebuffer.getColorAttachment();
            if (pendingColor != null && pendingColor == color) mask |= 1;
        }
        if (depthAttachmentInfo != null) {
            depth = framebuffer.getDepthAttachment();
            if (pendingDepth != null && pendingDepth == depth) mask |= 2;
        }
        pendingColor = pendingDepth = null;
        this.activeMask = mask;
        activeColor = (mask & 1) != 0 ? color : null;
        activeDepth = (mask & 2) != 0 ? depth : null;
        if (color != null) {
            color.transitionImageLayout(stack, commandBuffer, (mask & 1) != 0 ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            color.markAttachmentUse();
        }
        if (depth != null) {
            depth.transitionImageLayout(stack, commandBuffer, (mask & 2) != 0 ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            depth.markAttachmentUse();
        }
    }

    public void beginRenderPass(VkCommandBuffer commandBuffer, long framebufferId, MemoryStack stack) {
        prepareAttachments(commandBuffer, stack);

        VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack);
        renderPassInfo.sType$Default();
        renderPassInfo.renderPass(idFor(this.activeMask));
        renderPassInfo.framebuffer(framebufferId);

        VkRect2D renderArea = VkRect2D.malloc(stack);
        renderArea.offset().set(0, 0);
        renderArea.extent().set(framebuffer.getWidth(), framebuffer.getHeight());
        renderPassInfo.renderArea(renderArea);

        VkClearValue.Buffer clearValues = VkClearValue.malloc(2, stack);
        clearValues.get(0).color().float32(VRenderSystem.clearColor);
        clearValues.get(1).depthStencil().set(1.0f, 0);

        renderPassInfo.pClearValues(clearValues);

        vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

        Renderer.getInstance().setBoundRenderPass(this);
    }

    public void endRenderPass(VkCommandBuffer commandBuffer) {
        vkCmdEndRenderPass(commandBuffer);
        final int mask = this.activeMask;
        this.activeMask = 0;
        activeColor = activeDepth = null;

        if (colorAttachmentInfo != null)
            framebuffer.getColorAttachment().setCurrentLayout((mask & 1) != 0 ? VK_IMAGE_LAYOUT_GENERAL : colorAttachmentInfo.finalLayout);

        if (depthAttachmentInfo != null)
            framebuffer.getDepthAttachment().setCurrentLayout((mask & 2) != 0 ? VK_IMAGE_LAYOUT_GENERAL : depthAttachmentInfo.finalLayout);

        Renderer.getInstance().setBoundRenderPass(null);
    }

    public void beginDynamicRendering(VkCommandBuffer commandBuffer, MemoryStack stack) {
        prepareAttachments(commandBuffer, stack); // V-3: same invariant (layouts stay the attachment ones after the end)
        VkRect2D renderArea = VkRect2D.malloc(stack);
        renderArea.offset().set(0, 0);
        renderArea.extent().set(framebuffer.getWidth(), framebuffer.getHeight());

        VkClearValue.Buffer clearValues = VkClearValue.malloc(2, stack);
        clearValues.get(0).color().float32(stack.floats(0.0f, 0.0f, 0.0f, 1.0f));
        clearValues.get(1).depthStencil().set(1.0f, 0);

        VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack);
        renderingInfo.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR);
        renderingInfo.renderArea(renderArea);
        renderingInfo.layerCount(1);

        // Color attachment
        if (colorAttachmentInfo != null) {
            VkRenderingAttachmentInfo.Buffer colorAttachment = VkRenderingAttachmentInfo.calloc(1, stack);
            colorAttachment.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
            colorAttachment.imageView(framebuffer.getColorAttachment().getImageView());
            colorAttachment.imageLayout((activeMask & 1) != 0 ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            colorAttachment.loadOp(colorAttachmentInfo.loadOp);
            colorAttachment.storeOp(colorAttachmentInfo.storeOp);
            colorAttachment.clearValue(clearValues.get(0));

            renderingInfo.pColorAttachments(colorAttachment);
        }

        //Depth attachment
        if (depthAttachmentInfo != null) {
            VkRenderingAttachmentInfo depthAttachment = VkRenderingAttachmentInfo.calloc(stack);
            depthAttachment.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
            depthAttachment.imageView(framebuffer.getDepthAttachment().getImageView());
            depthAttachment.imageLayout((activeMask & 2) != 0 ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            depthAttachment.loadOp(depthAttachmentInfo.loadOp);
            depthAttachment.storeOp(depthAttachmentInfo.storeOp);
            depthAttachment.clearValue(clearValues.get(1));

            renderingInfo.pDepthAttachment(depthAttachment);
        }

        KHRDynamicRendering.vkCmdBeginRenderingKHR(commandBuffer, renderingInfo);
    }

    public void endDynamicRendering(VkCommandBuffer commandBuffer) {
        KHRDynamicRendering.vkCmdEndRenderingKHR(commandBuffer);
        this.activeMask = 0;
        activeColor = activeDepth = null;
    }

    public Framebuffer getFramebuffer() {
        return framebuffer;
    }

    public void cleanUp() {
        //TODO

        if (!Vulkan.DYNAMIC_RENDERING)
            MemoryManager.getInstance().addFrameOp(() -> {
                vkDestroyRenderPass(Vulkan.getVkDevice(), this.id, null);
                for (long v : feedbackIds) if (v != 0L) vkDestroyRenderPass(Vulkan.getVkDevice(), v, null);
            });

    }

    public long getId() {
        return id;
    }

    /** P2-11: colour / depth attachment formats (0 = none); render-pass compatibility is decided by these (plus sample count 1) */
    public int colorFormat() {
        return colorAttachmentInfo != null ? colorAttachmentInfo.format : 0;
    }

    public int depthFormat() {
        return depthAttachmentInfo != null ? depthAttachmentInfo.format : 0;
    }

    public int dependencyKind() {
        return dependencyKind;
    }

    public static class AttachmentInfo {
        final Type type;
        final int format;
        int finalLayout;
        int loadOp;
        int storeOp;

        public AttachmentInfo(Type type, int format) {
            this.type = type;
            this.format = format;
            this.finalLayout = type.defaultLayout;

            this.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            this.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        }

        public AttachmentInfo setOps(int loadOp, int storeOp) {
            this.loadOp = loadOp;
            this.storeOp = storeOp;

            return this;
        }

        public AttachmentInfo setLoadOp(int loadOp) {
            this.loadOp = loadOp;

            return this;
        }

        public AttachmentInfo setFinalLayout(int finalLayout) {
            this.finalLayout = finalLayout;

            return this;
        }

        public enum Type {
            COLOR(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL),
            DEPTH(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            final int defaultLayout;

            Type(int layout) {
                defaultLayout = layout;
            }
        }
    }

    public static Builder builder(Framebuffer framebuffer) {
        return new Builder(framebuffer);
    }

    public static class Builder {
        Framebuffer framebuffer;
        AttachmentInfo colorAttachmentInfo;
        AttachmentInfo depthAttachmentInfo;

        public Builder(Framebuffer framebuffer) {
            this.framebuffer = framebuffer;

            if (framebuffer.hasColorAttachment)
                colorAttachmentInfo = new AttachmentInfo(AttachmentInfo.Type.COLOR, framebuffer.format).setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_STORE);
            if (framebuffer.hasDepthAttachment)
                depthAttachmentInfo = new AttachmentInfo(AttachmentInfo.Type.DEPTH, framebuffer.depthFormat).setOps(VK_ATTACHMENT_LOAD_OP_CLEAR, VK_ATTACHMENT_STORE_OP_DONT_CARE);
        }

        public RenderPass build() {
            return new RenderPass(framebuffer, colorAttachmentInfo, depthAttachmentInfo);
        }

        public Builder setLoadOp(int loadOp) {
            if (colorAttachmentInfo != null) {
                colorAttachmentInfo.setLoadOp(loadOp);
            }
            if (depthAttachmentInfo != null) {
                depthAttachmentInfo.setLoadOp(loadOp);
            }


            return this;
        }

        public AttachmentInfo getColorAttachmentInfo() {
            return colorAttachmentInfo;
        }

        public AttachmentInfo getDepthAttachmentInfo() {
            return depthAttachmentInfo;
        }
    }
}
