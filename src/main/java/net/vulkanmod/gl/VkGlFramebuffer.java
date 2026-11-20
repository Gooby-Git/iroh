package net.vulkanmod.gl;

import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.SceneTarget;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.vulkan.VK11.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK11.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

public class VkGlFramebuffer {
    private static int idCounter = 1;

    private static final Int2ReferenceOpenHashMap<VkGlFramebuffer> map = new Int2ReferenceOpenHashMap<>();
    private static VkGlFramebuffer boundFramebuffer;
    private static VkGlFramebuffer readFramebuffer;

    public static void resetBoundFramebuffer() {
        boundFramebuffer = null;
    }

    public static void beginRendering(VkGlFramebuffer glFramebuffer) {
        boolean begunRendering = glFramebuffer.beginRendering();

        if (begunRendering) {
            Framebuffer framebuffer = glFramebuffer.framebuffer;
            int viewWidth = framebuffer.getWidth();
            int viewHeight = framebuffer.getHeight();

            Renderer.setInvertedViewport(0, 0, viewWidth, viewHeight);
            Renderer.setScissor(0, 0, viewWidth, viewHeight);
            // Iroh (RQ-16): no cull change on bind (GL keeps cull state); GlState.vkCullMode handles the winding.
        }

        boundFramebuffer = glFramebuffer;
    }

    public static int genFramebufferId() {
        int id = idCounter;
        map.put(id, new VkGlFramebuffer(id));
        idCounter++;
        return id;
    }

    public static void bindFramebuffer(int target, int id) {
        // Iroh (R-1): READ bindings only select the source of glBlitFramebuffer; they must not end or begin a
        // render pass. Framebuffer 0 (null here) is the main scene target.
        if (target == GL30.GL_READ_FRAMEBUFFER) {
            readFramebuffer = id == 0 ? null : map.get(id);
            return;
        }
        if (target == GL30.GL_FRAMEBUFFER) {
            readFramebuffer = id == 0 ? null : map.get(id);
        }

        if (id == 0) {
            Renderer.getInstance()
                    .endRenderPass();

            if (Renderer.isRecording()) {
                Renderer.getInstance()
                        .getMainPass()
                        .rebindMainTarget();
            }

            boundFramebuffer = null;
            return;
        }

        VkGlFramebuffer glFramebuffer = map.get(id);

        if (glFramebuffer == null)
            throw new NullPointerException("No Framebuffer with ID: %d ".formatted(id));

        switch (target) {
            case GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_FRAMEBUFFER -> {
                if (glFramebuffer.framebuffer != null) {
                    beginRendering(glFramebuffer);
                }

                boundFramebuffer = glFramebuffer;
            }
            case GL30.GL_READ_FRAMEBUFFER -> {
                readFramebuffer = glFramebuffer;
            }
        }

    }

    public static void deleteFramebuffer(int id) {
        if (id == 0) {
            return;
        }

        // Iroh (P1-2 slice 2): GL semantics. Deleting an unknown name is a no-op; deleting the bound framebuffer
        // reverts that binding to 0; deleting another one leaves the binding alone (the base re-bound to the
        // deleted object and threw on unknown names). Attached textures stay alive: they belong to their owners.
        VkGlFramebuffer fb = map.remove(id);
        if (fb == null)
            return;
        if (readFramebuffer == fb)
            readFramebuffer = null;
        if (boundFramebuffer == fb) {
            bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        }
        if (fb.framebuffer != null)
            fb.cleanUp(false);
    }

    public static void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        VkGlFramebuffer fb = target == GL30.GL_READ_FRAMEBUFFER ? readFramebuffer : boundFramebuffer;
        if (fb == null)
            return;
        // Iroh (P1-2 slice 2): unsupported attachments make the framebuffer report itself incomplete
        // (glCheckFramebufferStatus) instead of throwing into the mod.
        if (attachment == 0x821A /* DEPTH_STENCIL_ATTACHMENT */)
            attachment = GL30.GL_DEPTH_ATTACHMENT;
        if (attachment == 0x8D20 /* STENCIL_ATTACHMENT */) {
            dev.elemore.iroh.glshim.GlShim.unhandled("glFramebufferTexture2D(GL_STENCIL_ATTACHMENT)");
            return;
        }
        if (attachment != GL30.GL_COLOR_ATTACHMENT0 && attachment != GL30.GL_DEPTH_ATTACHMENT) {
            fb.unsupported = "attachment 0x" + Integer.toHexString(attachment);
            dev.elemore.iroh.glshim.GlShim.unhandled("glFramebufferTexture2D(" + fb.unsupported + ")");
            return;
        }
        if (texture != 0 && (texTarget != GL11.GL_TEXTURE_2D || level != 0)) {
            fb.unsupported = texTarget != GL11.GL_TEXTURE_2D ? "texture target 0x" + Integer.toHexString(texTarget) : "mip level " + level;
            dev.elemore.iroh.glshim.GlShim.unhandled("glFramebufferTexture2D(" + fb.unsupported + ")");
            return;
        }
        if (texture == 0) {
            // detach
            if (attachment == GL30.GL_COLOR_ATTACHMENT0) fb.colorAttachment = null;
            else fb.depthAttachment = null;
            if (fb.framebuffer != null) fb.cleanUp(false);
            if (fb.colorAttachment != null) fb.createAndBind();
            return;
        }

        fb.setAttachmentTexture(attachment, texture);
    }

    /**
     * glCheckFramebufferStatus, honestly: COMPLETE only when Iroh can render into it (a colour attachment; Iroh has
     * no depth-only render passes yet), MISSING_ATTACHMENT without attachments, UNSUPPORTED for what Iroh cannot
     * attach (mip level > 0, other attachment points or texture targets). Framebuffer 0 is complete.
     */
    public static int checkStatus(int target) {
        VkGlFramebuffer fb = target == GL30.GL_READ_FRAMEBUFFER ? readFramebuffer : boundFramebuffer;
        if (fb == null)
            return GL30.GL_FRAMEBUFFER_COMPLETE;
        if (fb.unsupported != null)
            return 0x8CDD; // GL_FRAMEBUFFER_UNSUPPORTED
        if (fb.colorAttachment == null && fb.depthAttachment == null)
            return 0x8CD7; // GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT
        if (fb.colorAttachment == null)
            return 0x8CDD;
        return GL30.GL_FRAMEBUFFER_COMPLETE;
    }

    /** glDrawBuffers / glDrawBuffer / glReadBuffer state of the bound framebuffers (answered by glGet). */
    public static void drawBuffer(int buf) {
        if (boundFramebuffer != null) boundFramebuffer.drawBuffer = buf;
        else defaultDrawBuffer = buf;
    }

    public static void readBuffer(int buf) {
        if (readFramebuffer != null) readFramebuffer.readBuffer = buf;
        else defaultReadBuffer = buf;
    }

    public static int getDrawBuffer() {
        return boundFramebuffer != null ? boundFramebuffer.drawBuffer : defaultDrawBuffer;
    }

    public static int getReadBuffer() {
        return readFramebuffer != null ? readFramebuffer.readBuffer : defaultReadBuffer;
    }

    private static int defaultDrawBuffer = 0x0405 /* GL_BACK */, defaultReadBuffer = 0x0405;

    public static void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        if (boundFramebuffer == null)
            return;

        if (attachment == 0x821A /* DEPTH_STENCIL_ATTACHMENT */)
            attachment = GL30.GL_DEPTH_ATTACHMENT;
        if (attachment != GL30.GL_COLOR_ATTACHMENT0 && attachment != GL30.GL_DEPTH_ATTACHMENT) {
            dev.elemore.iroh.glshim.GlShim.unhandled("glFramebufferRenderbuffer(attachment 0x" + Integer.toHexString(attachment) + ")");
            if (attachment != 0x8D20) boundFramebuffer.unsupported = "attachment 0x" + Integer.toHexString(attachment);
            return;
        }
        boundFramebuffer.setAttachmentRenderbuffer(attachment, renderbuffer);
    }

    public static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1,
                                         int dstY1, int mask, int filter) {
        // Iroh (R-1): source = READ framebuffer, destination = DRAW framebuffer; 0 (null) = main scene target.
        // The 0.5.5 base always used the current swapchain image as source, ignored mask (a depth copy became a
        // colour copy) and the rects.
        VkGlFramebuffer draw = boundFramebuffer;
        VkGlFramebuffer read = readFramebuffer;
        SceneTarget scene = Renderer.getInstance().getMainPass().getSceneTarget();
        if (scene == null)
            return;

        VulkanImage srcColor = read != null ? read.colorAttachment : scene.getColorAttachment();
        VulkanImage srcDepth = read != null ? read.depthAttachment : scene.getDepthAttachment();
        VulkanImage dstColor = draw != null ? draw.colorAttachment : scene.getColorAttachment();
        VulkanImage dstDepth = draw != null ? draw.depthAttachment : scene.getDepthAttachment();

        ImageUtil.blitFramebuffer(srcColor, srcDepth, dstColor, dstDepth,
                                  srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);

        // GL keeps bindings across a blit; the blit ended the render pass, so re-begin what was bound.
        if (!Renderer.isRecording())
            return;
        if (draw != null && draw.framebuffer != null) {
            beginRendering(draw);
        } else {
            Renderer.getInstance().getMainPass().rebindMainTarget();
            boundFramebuffer = null;
        }
        readFramebuffer = read;
    }

    public static int glCheckFramebufferStatus(int target) {
        return checkStatus(target == GL30.GL_FRAMEBUFFER ? GL30.GL_DRAW_FRAMEBUFFER : target);
    }

    /**
     * Iroh (P1-2 slice 2): the image GL would read from (glReadPixels, glCopyTexSubImage2D): the READ framebuffer's
     * colour (or depth) attachment, or the main scene target for framebuffer 0. Null when there is none.
     */
    public static VulkanImage readImage(boolean depth) {
        VkGlFramebuffer read = readFramebuffer;
        if (read != null)
            return depth ? read.depthAttachment : read.colorAttachment;
        Renderer r = Renderer.getInstance();
        SceneTarget scene = r != null && r.getMainPass() != null ? r.getMainPass().getSceneTarget() : null;
        if (scene == null)
            return null;
        return depth ? scene.getDepthAttachment() : scene.getColorAttachment();
    }

    /** GL_DRAW_FRAMEBUFFER_BINDING (0 = the main target). */
    public static int getBoundDrawId() {
        return boundFramebuffer != null ? boundFramebuffer.id : 0;
    }

    /** GL_READ_FRAMEBUFFER_BINDING (0 = the main target). */
    public static int getBoundReadId() {
        return readFramebuffer != null ? readFramebuffer.id : 0;
    }

    /**
     * Iroh (RQ-10): put back the bound off-screen framebuffer after a render pass was ended and re-begun around a
     * layout transition ({@code Renderer.endRenderPass} forgets it). No viewport/scissor/cull side effects.
     */
    public static void restoreBinding(VkGlFramebuffer framebuffer) {
        boundFramebuffer = framebuffer;
    }

    public static VkGlFramebuffer getBoundFramebuffer() {
        return boundFramebuffer;
    }

    public static VkGlFramebuffer getFramebuffer(int id) {
        return map.get(id);
    }

    public final int id;
    Framebuffer framebuffer;
    RenderPass renderPass;

    VulkanImage colorAttachment;
    VulkanImage depthAttachment;
    /** Iroh: why this framebuffer cannot be complete (null = fine). */
    String unsupported;
    int drawBuffer = GL30.GL_COLOR_ATTACHMENT0, readBuffer = GL30.GL_COLOR_ATTACHMENT0;

    VkGlFramebuffer(int i) {
        this.id = i;
    }

    boolean beginRendering() {
        return Renderer.getInstance().beginRendering(this.renderPass, this.framebuffer);
    }

    void setAttachmentTexture(int attachment, int texture) {
        VkGlTexture glTexture = VkGlTexture.getTexture(texture);

        if (glTexture == null) {
            this.unsupported = "unknown texture " + texture; // GL: INVALID_OPERATION, attachment unchanged
            return;
        }

        if (glTexture.vulkanImage == null)
            return;

        switch (attachment) {
            case (GL30.GL_COLOR_ATTACHMENT0) -> this.setColorAttachment(glTexture.getVulkanImage());
            case (GL30.GL_DEPTH_ATTACHMENT) -> this.setDepthAttachment(glTexture.getVulkanImage());

            default -> throw new IllegalStateException("Unexpected value: " + attachment);
        }
    }

    void setAttachmentRenderbuffer(int attachment, int texture) {
        VkGlRenderbuffer renderbuffer = VkGlRenderbuffer.getRenderbuffer(texture);

        if (renderbuffer == null)
            throw new NullPointerException(String.format("Texture %d is null", texture));

        if (renderbuffer.vulkanImage == null)
            return;

        switch (attachment) {
            case (GL30.GL_COLOR_ATTACHMENT0) -> this.setColorAttachment(renderbuffer.getVulkanImage());
            case (GL30.GL_DEPTH_ATTACHMENT) -> this.setDepthAttachment(renderbuffer.getVulkanImage());

            default -> throw new IllegalStateException("Unexpected value: " + attachment);
        }
    }

    void setColorAttachment(VulkanImage image) {
        this.colorAttachment = image;
        createAndBind();
    }

    void setDepthAttachment(VulkanImage image) {
        //TODO check if texture is in depth format
        this.depthAttachment = image;
        createAndBind();
    }

    void createAndBind() {
        // Cannot create without color attachment
        if (this.colorAttachment == null)
            return;

        if (this.framebuffer != null) {
            this.cleanUp(false);
        }

        boolean hasDepthImage = this.depthAttachment != null;
        VulkanImage depthImage = this.depthAttachment;

        this.framebuffer = Framebuffer.builder(this.colorAttachment, depthImage)
                                      .build();
        RenderPass.Builder builder = RenderPass.builder(this.framebuffer);

        builder.getColorAttachmentInfo()
               .setLoadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
               .setFinalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        if (hasDepthImage) {
            builder.getDepthAttachmentInfo()
                   .setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_LOAD_OP_LOAD);
        }

        this.renderPass = builder.build();

        VkGlFramebuffer.beginRendering(this);
    }

    public Framebuffer getFramebuffer() {
        return framebuffer;
    }

    public RenderPass getRenderPass() {
        return renderPass;
    }

    void cleanUp(boolean freeAttachments) {
        this.framebuffer.cleanUp(freeAttachments);
        this.renderPass.cleanUp();

        this.framebuffer = null;
        this.renderPass = null;
    }
}
