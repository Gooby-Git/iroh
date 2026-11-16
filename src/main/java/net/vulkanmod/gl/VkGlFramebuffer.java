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

            // TODO: invert cull instead of disabling
            VRenderSystem.disableCull();
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

        boundFramebuffer = map.remove(id);

        if (boundFramebuffer == null)
            throw new NullPointerException("bound framebuffer is null");

        boundFramebuffer.cleanUp(true);
        boundFramebuffer = null;
    }

    public static void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        if (attachment != GL30.GL_COLOR_ATTACHMENT0 && attachment != GL30.GL_DEPTH_ATTACHMENT) {
            throw new UnsupportedOperationException();
        }
        if (texTarget != GL11.GL_TEXTURE_2D) {
            throw new UnsupportedOperationException();
        }
        if (level != 0) {
            throw new UnsupportedOperationException();
        }

        boundFramebuffer.setAttachmentTexture(attachment, texture);
    }

    public static void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        if (boundFramebuffer == null)
            return;

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
        //TODO
        return GL30.GL_FRAMEBUFFER_COMPLETE;
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

    VkGlFramebuffer(int i) {
        this.id = i;
    }

    boolean beginRendering() {
        return Renderer.getInstance().beginRendering(this.renderPass, this.framebuffer);
    }

    void setAttachmentTexture(int attachment, int texture) {
        VkGlTexture glTexture = VkGlTexture.getTexture(texture);

        if (glTexture == null)
            throw new NullPointerException(String.format("Texture %d is null", texture));

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
