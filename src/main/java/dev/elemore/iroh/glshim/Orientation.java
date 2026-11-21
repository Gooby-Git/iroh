package dev.elemore.iroh.glshim;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Iroh (rc2, td-eb1e26 / RQ-21): THE place that says how GL's window conventions map onto Vulkan. Pure functions, no
 * game or device state; {@code ./gradlew orientationTest} checks them against a truth table derived from the GL and
 * Vulkan specs (a GL counter-clockwise triangle must survive GL_BACK culling on every target kind and come out upright
 * on screen after the present flip).
 *
 * <h2>Decision (rc2, 2026-11-20): keep RQ-16</h2>
 * rc1 drew no terrain / HUD / buttons. The cause was not this convention but PipelineState's bit packing: a 5-bit
 * topology mask over a 4-bit field turned cull mode FRONT (the normal value under this convention) into topology 19,
 * so every culled pipeline was invalid. The convention itself is proven by orientationTest (culling, upright rows,
 * scissor, for every target kind), it is what P3-1's pack path needs (GL-layout targets, CCW front faces, one present
 * flip), and reverting to slice 1 (main target top-down) would bring back the RQ-16 bugs (mods sampling the main
 * target upside down: owo/FancyMenu blur, satin, charta). The packing bug was latent in slice 1 too: any mod calling
 * glCullFace(GL_FRONT) or GL_FRONT_AND_BACK produced the same invalid topology.
 *
 * <h2>Convention (RQ-16)</h2>
 * Every render target Iroh draws into (the scene target behind {@code getMainRenderTarget()}, every off-screen GL
 * framebuffer / RenderTarget, post-chain targets) stores GL's row order: image row 0 = GL window row 0 = the BOTTOM
 * row. The swapchain is the only top-down image, and nothing draws into it: the frame ends with ONE vertically
 * flipped {@code vkCmdBlitImage} scene -> swapchain ({@code DefaultMainPass.copyToSwapChain}).
 *
 * <table>
 *   <tr><th>target kind</th><th>Vulkan viewport</th><th>front face</th><th>cull from GL_BACK (CCW)</th><th>scissor y</th></tr>
 *   <tr><td>SCENE, OFFSCREEN (GL layout)</td><td>y = GL y, height = +h</td><td>COUNTER_CLOCKWISE</td>
 *       <td>VK FRONT</td><td>GL y</td></tr>
 *   <tr><td>SWAPCHAIN (top-down, unused for drawing)</td><td>y = H - GL y, height = -h</td><td>COUNTER_CLOCKWISE</td>
 *       <td>VK BACK</td><td>H - (GL y + h)</td></tr>
 * </table>
 * Why it composes: with a positive height, framebuffer coordinates are numerically GL window coordinates. Vulkan's
 * polygon area is {@code a = -1/2 * sum(x_i*y_{i+1} - x_{i+1}*y_i)}, GL's is the same sum without the minus, so a GL
 * counter-clockwise (front) triangle has a < 0 in Vulkan: with VK_FRONT_FACE_COUNTER_CLOCKWISE it is a Vulkan BACK
 * face. Hence GL_BACK -> VK_CULL_MODE_FRONT_BIT and GL_FRONT -> VK_CULL_MODE_BACK_BIT on GL-layout targets, and
 * glFrontFace(GL_CW) swaps them again. A negative height (swapchain) negates y and the sign: no mirroring there.
 * Consequences, all GL-identical: gl_FragCoord.y counts from the bottom (Vulkan's origin is image row 0); texture
 * coordinates v = 0 sample row 0 = GL bottom; glReadPixels / glGetTexImage / glCopyTexSubImage2D copy rows in image
 * order (= GL order, no flip, {@code Readback}); screenshots flip rows once like vanilla's NativeImage path; polygon
 * offset needs no sign change (depth is not affected by the viewport height sign); clip depth stays Vulkan [0, 1]
 * (projection matrices are Vulkan-corrected elsewhere, unchanged by this).
 * Paths that do not go through GlStateManager's cull state still take their cull from {@code PipelineState}
 * (terrain, clouds, particles, GUI, FRAPI, post passes): every
 * pipeline is built from {@code PipelineState.getAssemblyRasterState()}, which calls {@link #vkCullMode}.
 * Shader packs (P3-1 front end contract: GL clip-depth epilogue, no Y flip, CCW front faces, present-time flip) render
 * into the same GL-layout targets and get the same answers from this class.
 */
public final class Orientation {
    public enum Target {
        /** Iroh's scene target (Minecraft's main render target). GL row order. */
        SCENE,
        /** Any other render target / GL framebuffer object, post-chain targets. GL row order. */
        OFFSCREEN,
        /** The presentation image. Top-down. Only the frame-end blit writes it. */
        SWAPCHAIN;

        public boolean glLayout() {
            return this != SWAPCHAIN;
        }
    }

    public static final int FRONT_FACE = VK_FRONT_FACE_COUNTER_CLOCKWISE;

    private static final int GL_FRONT = 0x0404, GL_BACK = 0x0405, GL_FRONT_AND_BACK = 0x0408, GL_CW = 0x0900;

    private Orientation() {
    }

    /** Vulkan viewport {x, y, width, height} for GL's glViewport(x, y, w, h) on a target {@code fbHeight} rows tall. */
    public static float[] vkViewport(Target t, int x, int y, int w, int h, int fbHeight) {
        if (t.glLayout())
            return new float[]{x, y, w, h};
        return new float[]{x, fbHeight - y, w, -h};
    }

    /** Vulkan scissor {x, y, width, height} (offsets clamped to >= 0) for GL's glScissor(x, y, w, h). */
    public static int[] vkScissor(Target t, int x, int y, int w, int h, int fbHeight) {
        int vy = t.glLayout() ? y : fbHeight - (y + h);
        int vx = x;
        if (vx < 0) { w += vx; vx = 0; }
        if (vy < 0) { h += vy; vy = 0; }
        return new int[]{vx, vy, Math.max(0, w), Math.max(0, h)};
    }

    /** Vulkan cull mode for glCullFace(mode) + glFrontFace(frontFace), with {@link #FRONT_FACE}. */
    public static int vkCullMode(Target t, int glCullFace, int glFrontFace) {
        boolean mirrored = t.glLayout() ^ (glFrontFace == GL_CW);
        return switch (glCullFace) {
            case GL_FRONT -> mirrored ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_FRONT_BIT;
            case GL_FRONT_AND_BACK -> VK_CULL_MODE_FRONT_AND_BACK;
            default /* GL_BACK */ -> mirrored ? VK_CULL_MODE_FRONT_BIT : VK_CULL_MODE_BACK_BIT;
        };
    }
}
