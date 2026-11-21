package dev.elemore.iroh.glshim;

import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;

import static org.lwjgl.vulkan.VK10.*;

/**
 * glCopyTexSubImage2D / glCopyTexImage2D (P1-2 slice 2, item c): a rectangle of the READ framebuffer into the texture
 * bound on the active unit, recorded in the frame command buffer between render passes so it sees every earlier draw
 * and later draws see the copy (GL order). Colour or depth is chosen by the destination's format, as in GL.
 * Both images hold GL row order (RQ-16), so GL rectangles map to image rows directly.
 *
 * <p>Known caller: satin {@code DepthGlFramebufferMixin.freezeDepthMap} (merged into RenderTarget): bindWrite, bind
 * its "still depth" texture (DEPTH_COMPONENT24), copy the whole depth buffer.
 */
public final class CopyTex {
    private CopyTex() {}

    public static void copyTexSubImage2D(int target, int level, int xoff, int yoff, int x, int y, int w, int h) {
        if (target != 0x0DE1) {
            GlShim.unhandled("glCopyTexSubImage2D(target 0x" + Integer.toHexString(target) + ")");
            return;
        }
        VkGlTexture tex = VkGlTexture.getBoundTexture();
        VulkanImage dst = tex != null ? tex.getVulkanImage() : null;
        if (dst == null || level < 0 || level >= dst.mipLevels) return;
        boolean depth = VulkanImage.isDepthFormat(dst.format);
        VulkanImage src = VkGlFramebuffer.readImage(depth);
        if (src == null || src == dst) {
            GlShim.unhandled("glCopyTexSubImage2D(" + (src == null ? "no read attachment" : "source is the destination") + ")");
            return;
        }
        if (!Renderer.isRecording()) {
            GlShim.unhandled("glCopyTexSubImage2D(outside a frame)");
            return;
        }

        // GL clips the source rectangle to the read buffer; destination region must lie inside the level
        int dw = Math.max(1, dst.width >> level), dh = Math.max(1, dst.height >> level);
        if (x < 0) { xoff -= x; w += x; x = 0; }
        if (y < 0) { yoff -= y; h += y; y = 0; }
        w = Math.min(Math.min(w, src.width - x), dw - xoff);
        h = Math.min(Math.min(h, src.height - y), dh - yoff);
        if (w <= 0 || h <= 0) return;

        boolean sameFormat = src.format == dst.format;
        if (depth && !sameFormat) {
            GlShim.unhandled("glCopyTexSubImage2D(depth format " + src.format + " -> " + dst.format + ")");
            return;
        }

        final int fx = x, fy = y, fw = w, fh = h, fxo = xoff, fyo = yoff;
        VulkanImage.endPassRunResume(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandBuffer cb = Renderer.recordingCommandBuffer("glCopyTexSubImage2D");
                if (cb == null) return;
                src.transitionImageLayout(stack, cb, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                dst.transitionImageLayout(stack, cb, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                if (sameFormat) {
                    VkImageCopy.Buffer r = VkImageCopy.calloc(1, stack);
                    r.srcSubresource().set(src.aspect, 0, 0, 1);
                    r.dstSubresource().set(dst.aspect, level, 0, 1);
                    r.srcOffset().set(fx, fy, 0);
                    r.dstOffset().set(fxo, fyo, 0);
                    r.extent().set(fw, fh, 1);
                    vkCmdCopyImage(cb, src.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, r);
                } else {
                    VkImageBlit.Buffer b = VkImageBlit.calloc(1, stack);
                    b.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
                    b.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, level, 0, 1);
                    b.srcOffsets(0).set(fx, fy, 0);
                    b.srcOffsets(1).set(fx + fw, fy + fh, 1);
                    b.dstOffsets(0).set(fxo, fyo, 0);
                    b.dstOffsets(1).set(fxo + fw, fyo + fh, 1);
                    vkCmdBlitImage(cb, src.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, b, VK_FILTER_NEAREST);
                }
                if ((dst.usage & VK_IMAGE_USAGE_SAMPLED_BIT) != 0)
                    dst.transitionImageLayout(stack, cb, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        });
    }

    /** glCopyTexImage2D: (re)define level storage with the internal format, then copy. */
    public static void copyTexImage2D(int target, int level, int internalFormat, int x, int y, int w, int h, int border) {
        if (target != 0x0DE1 || w <= 0 || h <= 0) return;
        // allocate through the normal path (no data), then copy into it
        VkGlTexture.texImage2D(target, level, internalFormat, w, h, 0,
                VulkanImage.isDepthFormat(VkGlTexture.vkFormatFor(internalFormat)) ? 0x1902 : 0x1908, 0x1401, 0L);
        copyTexSubImage2D(target, level, 0, 0, x, y, w, h);
    }
}
