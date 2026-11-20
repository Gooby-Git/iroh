package dev.elemore.iroh.glshim;

import com.mojang.blaze3d.platform.GlStateManager;
import net.vulkanmod.gl.VkGlBuffer;
import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.gl.VkGlProgram;
import net.vulkanmod.gl.VkGlRenderbuffer;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.SceneTarget;
import net.vulkanmod.vulkan.shader.PipelineState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static dev.elemore.iroh.glshim.GlNet.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Iroh's GL state as GL sees it (P1-2). One place that
 * <ul>
 *   <li>applies a GL state change to Iroh's Vulkan state (VRenderSystem, PipelineState.blendInfo, VkGl* objects),</li>
 *   <li>keeps vanilla's {@code GlStateManager} caches (BLEND, DEPTH, CULL, ..., activeTexture, TEXTURES) in step, because
 *       mods read them directly (ImmediatelyFast reads {@code GlStateManager.DEPTH} to restore depth test) and Iroh's
 *       GlStateManagerM overwrites skip the vanilla bodies that used to update them,</li>
 *   <li>answers glGet / glIsEnabled with valid GL enums, so a mod's save / restore pair round-trips
 *       (WaterMedia GLEngine$Env, FancyMenu GlslOpenGlStateSnapshot, owo, ImmediatelyFast).</li>
 * </ul>
 * Both paths call it: {@code GlStateManagerM} (vanilla and mods through GlStateManager / RenderSystem) and the raw-GL
 * handlers registered here on {@link GlNet} (mods calling {@code GL11.glEnable}, {@code GL13C.glActiveTexture}, ...
 * on a thread without a GL context). Threads with their own real context never reach the handlers.
 *
 * <p>Render thread only, like the GL calls it stands for. Stencil state is tracked (and answered) but not emulated:
 * Iroh's pipelines have no stencil attachment yet.
 */
public final class GlState {
    // -------- GL enums used here
    static final int GL_TEXTURE_2D = 0x0DE1, GL_TEXTURE0 = 0x84C0;
    static final int GL_BLEND = 0x0BE2, GL_DEPTH_TEST = 0x0B71, GL_CULL_FACE = 0x0B44, GL_SCISSOR_TEST = 0x0C11,
            GL_STENCIL_TEST = 0x0B90, GL_POLYGON_OFFSET_FILL = 0x8037, GL_POLYGON_OFFSET_LINE = 0x2A02,
            GL_COLOR_LOGIC_OP = 0x0BF2, GL_DITHER = 0x0BD0, GL_MULTISAMPLE = 0x809D;
    static final int GL_FRAMEBUFFER = 0x8D40, GL_READ_FRAMEBUFFER = 0x8CA8, GL_DRAW_FRAMEBUFFER = 0x8CA9;
    static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;

    private GlState() {}

    // ================================================================ tracked state (not held elsewhere)

    private static final Set<Integer> OTHER_CAPS = ConcurrentHashMap.newKeySet();
    static {
        OTHER_CAPS.add(GL_DITHER);      // GL defaults: dither on, multisample on
        OTHER_CAPS.add(GL_MULTISAMPLE);
    }

    private static boolean scissorEnabled;
    private static final int[] scissorBox = new int[4];
    private static final int[] viewport = new int[4];
    private static boolean polygonOffsetFill, polygonOffsetLine;
    private static float polygonOffsetFactor, polygonOffsetUnits;
    private static int cullFaceMode = 0x0405 /* GL_BACK */, frontFace = 0x0901 /* GL_CCW */;
    private static int polygonModeGl = 0x1B02; // GL_FILL
    private static float lineWidth = 1.0f;
    private static int logicOpGl = 0x1503;     // GL_COPY

    private static boolean stencilTest;
    private static int stencilFunc = 0x0207 /* GL_ALWAYS */, stencilRef, stencilValueMask = -1, stencilWriteMask = -1;
    private static int stencilFail = 0x1E00 /* GL_KEEP */, stencilZFail = 0x1E00, stencilZPass = 0x1E00;
    private static int clearStencil;
    private static final float[] blendColor = new float[4];

    private static int currentProgram, vertexArray;
    private static long fenceCounter;
    private static int nextVertexArray = 1;
    // pixel store: unpack / pack alignment, row length, skip rows, skip pixels
    private static int unpackAlignment = 4, unpackRowLength, unpackSkipRows, unpackSkipPixels;
    private static int packAlignment = 4, packRowLength, packSkipRows, packSkipPixels;

    // ================================================================ GL-layout of the bound target (RQ-11)

    /**
     * True when the bound Vulkan framebuffer stores GL's row order (row 0 = GL's bottom row; positive Vulkan viewport
     * height). Since RQ-16 that is EVERY render target, the main scene target included: mods that sample a target with
     * GL texture coordinates, read it back, copy or blit sub-rectangles get what GL gives, and gl_FragCoord.y counts
     * from the bottom as in GL. Only the swapchain is top-down; nothing draws into it (the frame ends with one flipped
     * blit from the scene target, DefaultMainPass.copyToSwapChain).
     */
    public static boolean glLayoutBound() {
        Renderer r = Renderer.getInstance();
        if (r == null) return true;
        Framebuffer fb = r.getBoundFramebuffer();
        return !(fb instanceof net.vulkanmod.vulkan.framebuffer.SwapChain);
    }

    /** Pre-RQ-16 name, kept for callers outside the shim: an off-screen (non-main) target is bound. */
    public static boolean offscreenBound() {
        Renderer r = Renderer.getInstance();
        if (r == null) return false;
        Framebuffer fb = r.getBoundFramebuffer();
        return fb != null && !(fb instanceof SceneTarget) && !(fb instanceof net.vulkanmod.vulkan.framebuffer.SwapChain);
    }

    /**
     * Vulkan cull mode for the GL cull state (glCullFace + glFrontFace). GL-layout targets mirror the winding Vulkan
     * sees (positive viewport height), so GL's back faces are Vulkan's front faces with VK_FRONT_FACE_COUNTER_CLOCKWISE.
     */
    public static int vkCullMode() {
        boolean mirrored = glLayoutBound() ^ (frontFace == 0x0900 /* GL_CW */);
        return switch (cullFaceMode) {
            case 0x0404 /* GL_FRONT */ -> mirrored ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_FRONT_BIT;
            case 0x0408 /* GL_FRONT_AND_BACK */ -> VK_CULL_MODE_FRONT_AND_BACK;
            default /* GL_BACK */ -> mirrored ? VK_CULL_MODE_FRONT_BIT : VK_CULL_MODE_BACK_BIT;
        };
    }

    private static int boundHeight() {
        Renderer r = Renderer.getInstance();
        Framebuffer fb = r != null ? r.getBoundFramebuffer() : null;
        return fb != null ? fb.getHeight() : 0;
    }

    // ================================================================ setters (both paths)

    public static void enable(int cap) {
        setEnabled(cap, true);
    }

    public static void disable(int cap) {
        setEnabled(cap, false);
    }

    public static void setEnabled(int cap, boolean on) {
        switch (cap) {
            case GL_BLEND -> {
                if (on) VRenderSystem.enableBlend(); else VRenderSystem.disableBlend();
                GlStateManager.BLEND.mode.enabled = on;
            }
            case GL_DEPTH_TEST -> {
                if (on) VRenderSystem.enableDepthTest(); else VRenderSystem.disableDepthTest();
                GlStateManager.DEPTH.mode.enabled = on;
            }
            case GL_CULL_FACE -> {
                if (on) VRenderSystem.enableCull(); else VRenderSystem.disableCull();
                GlStateManager.CULL.enable.enabled = on;
            }
            case GL_SCISSOR_TEST -> {
                scissorEnabled = on;
                GlStateManager.SCISSOR.mode.enabled = on;
                if (on) applyScissor(); else Renderer.resetScissor();
            }
            case GL_POLYGON_OFFSET_FILL -> {
                polygonOffsetFill = on;
                GlStateManager.POLY_OFFSET.fill.enabled = on;
                if (on) VRenderSystem.enablePolygonOffset(); else VRenderSystem.disablePolygonOffset();
            }
            case GL_POLYGON_OFFSET_LINE -> {
                polygonOffsetLine = on;
                GlStateManager.POLY_OFFSET.line.enabled = on;
            }
            case GL_COLOR_LOGIC_OP -> {
                if (on) VRenderSystem.enableColorLogicOp(); else VRenderSystem.disableColorLogicOp();
                GlStateManager.COLOR_LOGIC.enable.enabled = on;
            }
            case GL_STENCIL_TEST -> stencilTest = on;
            default -> {
                if (on) OTHER_CAPS.add(cap); else OTHER_CAPS.remove(cap);
            }
        }
    }

    public static boolean isEnabled(int cap) {
        return switch (cap) {
            case GL_BLEND -> PipelineState.blendInfo.enabled;
            case GL_DEPTH_TEST -> VRenderSystem.depthTest;
            case GL_CULL_FACE -> VRenderSystem.cull;
            case GL_SCISSOR_TEST -> scissorEnabled;
            case GL_POLYGON_OFFSET_FILL -> polygonOffsetFill;
            case GL_POLYGON_OFFSET_LINE -> polygonOffsetLine;
            case GL_COLOR_LOGIC_OP -> VRenderSystem.logicOp;
            case GL_STENCIL_TEST -> stencilTest;
            default -> OTHER_CAPS.contains(cap);
        };
    }

    public static void blendFunc(int src, int dst) {
        VRenderSystem.blendFunc(src, dst);
        GlStateManager.BLEND.srcRgb = src;
        GlStateManager.BLEND.srcAlpha = src;
        GlStateManager.BLEND.dstRgb = dst;
        GlStateManager.BLEND.dstAlpha = dst;
    }

    public static void blendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        VRenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GlStateManager.BLEND.srcRgb = srcRgb;
        GlStateManager.BLEND.dstRgb = dstRgb;
        GlStateManager.BLEND.srcAlpha = srcAlpha;
        GlStateManager.BLEND.dstAlpha = dstAlpha;
    }

    public static void blendEquation(int mode) {
        VRenderSystem.blendOp(mode);
    }

    public static void depthFunc(int func) {
        VRenderSystem.depthFunc(func);
        GlStateManager.DEPTH.func = func;
    }

    public static void depthMask(boolean mask) {
        VRenderSystem.depthMask(mask);
        GlStateManager.DEPTH.mask = mask;
    }

    public static void colorMask(boolean r, boolean g, boolean b, boolean a) {
        VRenderSystem.colorMask(r, g, b, a);
        GlStateManager.COLOR_MASK.red = r;
        GlStateManager.COLOR_MASK.green = g;
        GlStateManager.COLOR_MASK.blue = b;
        GlStateManager.COLOR_MASK.alpha = a;
    }

    /**
     * glPolygonOffset(factor, units): offset = factor * DZ + r * units. Vulkan's depthBiasSlopeFactor is GL's factor,
     * depthBiasConstantFactor GL's units (RQ-17: the base passed them swapped, so vanilla's polygonOffset(-1, -10)
     * for the block-breaking overlay became slope -10, constant -1).
     */
    public static void polygonOffset(float factor, float units) {
        polygonOffsetFactor = factor;
        polygonOffsetUnits = units;
        GlStateManager.POLY_OFFSET.factor = factor;
        GlStateManager.POLY_OFFSET.units = units;
        VRenderSystem.polygonOffset(factor, units); // (slope, constant)
    }

    public static void polygonMode(int face, int mode) {
        polygonModeGl = mode;
        VRenderSystem.setPolygonModeGL(mode);
    }

    public static void logicOp(int op) {
        logicOpGl = op;
        VRenderSystem.logicOp(op);
        GlStateManager.COLOR_LOGIC.op = op;
    }

    public static void cullFace(int mode) {
        cullFaceMode = mode;
        GlStateManager.CULL.mode = mode;
    }

    public static void lineWidth(float w) {
        lineWidth = w;
        VRenderSystem.setLineWidth(w);
    }

    /** glViewport / GlStateManager._viewport: GL coordinates; layout chosen by the bound target (RQ-11). */
    public static void viewport(int x, int y, int w, int h) {
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = w;
        viewport[3] = h;
        if (glLayoutBound()) Renderer.setViewport(x, y + h, w, -h); // GL layout: positive Vulkan height (RQ-16)
        else Renderer.setViewport(x, y, w, h);                      // swapchain: top-down
    }

    /** Iroh's own GL-layout viewport (Renderer.setInvertedViewport: post passes, FBO begin): tracked, not re-derived. */
    public static void recordViewport(int x, int y, int w, int h) {
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = w;
        viewport[3] = h;
    }

    public static void scissor(int x, int y, int w, int h) {
        scissorBox[0] = x;
        scissorBox[1] = y;
        scissorBox[2] = w;
        scissorBox[3] = h;
        if (scissorEnabled) applyScissor();
    }

    /**
     * Re-applies the GL scissor box to the bound target. {@code Renderer.setScissor} converts GL's bottom-left y to
     * the top-down main target; on a GL-layout (off-screen) target the rows already match, so y is pre-converted.
     */
    public static void applyScissor() {
        int x = scissorBox[0], y = scissorBox[1], w = scissorBox[2], h = scissorBox[3];
        if (w <= 0 || h <= 0) return;
        if (glLayoutBound()) Renderer.setScissor(x, boundHeight() - (y + h), w, h);
        else Renderer.setScissor(x, y, w, h);
    }

    /** After a render pass was re-begun (Framebuffer.beginRenderPass resets both to full size). */
    public static void reapplyViewportAndScissor() {
        if (viewport[2] > 0 && viewport[3] > 0) {
            int x = viewport[0], y = viewport[1], w = viewport[2], h = viewport[3];
            if (glLayoutBound()) Renderer.setViewport(x, y + h, w, -h);
            else Renderer.setViewport(x, y, w, h);
        }
        if (scissorEnabled) applyScissor();
    }

    /** After Renderer.flushCmds began a new command buffer (resetDynamicState zeroed depth bias and line width). */
    public static void reapplyDynamicState() {
        if (polygonOffsetFill) Renderer.setDepthBias(polygonOffsetUnits, polygonOffsetFactor); // (constant, slope)
        VRenderSystem.setLineWidth(lineWidth);
    }

    public static void activeTexture(int unitEnum) {
        VkGlTexture.activeTexture(unitEnum);
        int unit = unitEnum - GL_TEXTURE0;
        if (unit >= 0 && unit < GlStateManager.TEXTURES.length) GlStateManager.activeTexture = unit;
    }

    public static void bindTexture(int id) {
        VkGlTexture.bindTexture(id);
        int unit = VkGlTexture.getActiveUnit();
        if (unit >= 0 && unit < GlStateManager.TEXTURES.length) GlStateManager.TEXTURES[unit].binding = id;
    }

    public static void stencilFunc(int func, int ref, int mask) {
        stencilFunc = func;
        stencilRef = ref;
        stencilValueMask = mask;
        GlStateManager.STENCIL.func.func = func;
        GlStateManager.STENCIL.func.ref = ref;
        GlStateManager.STENCIL.func.mask = mask;
    }

    public static void stencilMask(int mask) {
        stencilWriteMask = mask;
        GlStateManager.STENCIL.mask = mask;
    }

    public static void stencilOp(int fail, int zfail, int zpass) {
        stencilFail = fail;
        stencilZFail = zfail;
        stencilZPass = zpass;
        GlStateManager.STENCIL.fail = fail;
        GlStateManager.STENCIL.zfail = zfail;
        GlStateManager.STENCIL.zpass = zpass;
    }

    public static void clearStencil(int s) {
        clearStencil = s;
    }

    public static int currentProgram() {
        return currentProgram;
    }

    public static int currentVertexArray() {
        return vertexArray;
    }

    public static void useProgram(int id) {
        currentProgram = id;
        if (id == 0 || VkGlProgram.getProgram(id) != null) VkGlProgram.glUseProgram(id);
    }

    public static void pixelStore(int pname, int param) {
        switch (pname) {
            case 0x0CF5 -> unpackAlignment = param;
            case 0x0CF2 -> unpackRowLength = param;
            case 0x0CF3 -> unpackSkipRows = param;
            case 0x0CF4 -> unpackSkipPixels = param;
            case 0x0D05 -> packAlignment = param;
            case 0x0D02 -> packRowLength = param;
            case 0x0D03 -> packSkipRows = param;
            case 0x0D04 -> packSkipPixels = param;
            default -> {}
        }
        VkGlTexture.pixelStoreI(pname, param);
    }

    // ================================================================ getters

    private static final int UNKNOWN = Integer.MIN_VALUE;

    /**
     * Writes the value(s) of {@code pname} as doubles into {@code out} (length 16) and returns how many, or -1 for a
     * pname Iroh does not know (the caller then writes 0 and logs once).
     */
    static int get(int pname, double[] out) {
        switch (pname) {
            // --- textures
            case 0x84E0 -> { out[0] = GL_TEXTURE0 + VkGlTexture.getActiveUnit(); return 1; }        // GL_ACTIVE_TEXTURE
            case 0x8069 -> { out[0] = VkGlTexture.getUnitBinding(VkGlTexture.getActiveUnit()); return 1; } // GL_TEXTURE_BINDING_2D
            case 0x8919 -> { out[0] = 0; return 1; }                                                 // GL_SAMPLER_BINDING
            // --- blend
            case GL_BLEND -> { out[0] = b(PipelineState.blendInfo.enabled); return 1; }
            case 0x80C9, 0x0BE1 -> { out[0] = glFactor(PipelineState.blendInfo.srcRgbFactor); return 1; }  // SRC_RGB, BLEND_SRC
            case 0x80C8, 0x0BE0 -> { out[0] = glFactor(PipelineState.blendInfo.dstRgbFactor); return 1; }  // DST_RGB, BLEND_DST
            case 0x80CB -> { out[0] = glFactor(PipelineState.blendInfo.srcAlphaFactor); return 1; }
            case 0x80CA -> { out[0] = glFactor(PipelineState.blendInfo.dstAlphaFactor); return 1; }
            case 0x8009, 0x883D -> { out[0] = glBlendOp(PipelineState.blendInfo.blendOp); return 1; }    // EQUATION_RGB/ALPHA
            case 0x8005 -> { for (int k = 0; k < 4; k++) out[k] = blendColor[k]; return 4; }              // GL_BLEND_COLOR
            // --- depth
            case GL_DEPTH_TEST -> { out[0] = b(VRenderSystem.depthTest); return 1; }
            case 0x0B74 -> { out[0] = VRenderSystem.depthFun; return 1; }                              // GL_DEPTH_FUNC
            case 0x0B72 -> { out[0] = b(VRenderSystem.depthMask); return 1; }                          // GL_DEPTH_WRITEMASK
            case 0x0B73 -> { out[0] = VRenderSystem.clearDepthValue; return 1; }                       // GL_DEPTH_CLEAR_VALUE
            case 0x0B70 -> { out[0] = 0; out[1] = 1; return 2; }                                       // GL_DEPTH_RANGE
            // --- raster
            case GL_CULL_FACE -> { out[0] = b(VRenderSystem.cull); return 1; }
            case 0x0B45 -> { out[0] = cullFaceMode; return 1; }
            case 0x0B46 -> { out[0] = frontFace; return 1; }
            case 0x0B40 -> { out[0] = polygonModeGl; out[1] = polygonModeGl; return 2; }              // GL_POLYGON_MODE
            case GL_POLYGON_OFFSET_FILL -> { out[0] = b(polygonOffsetFill); return 1; }
            case GL_POLYGON_OFFSET_LINE -> { out[0] = b(polygonOffsetLine); return 1; }
            case 0x8038 -> { out[0] = polygonOffsetFactor; return 1; }
            case 0x2A00 -> { out[0] = polygonOffsetUnits; return 1; }
            case 0x0B21 -> { out[0] = lineWidth; return 1; }                                          // GL_LINE_WIDTH
            case GL_COLOR_LOGIC_OP -> { out[0] = b(VRenderSystem.logicOp); return 1; }
            case 0x0BF0 -> { out[0] = logicOpGl; return 1; }                                          // GL_LOGIC_OP_MODE
            case GL_SCISSOR_TEST -> { out[0] = b(scissorEnabled); return 1; }
            case 0x0C10 -> { System.arraycopy(toD(scissorBox), 0, out, 0, 4); return 4; }            // GL_SCISSOR_BOX
            case 0x0BA2 -> { System.arraycopy(toD(viewport), 0, out, 0, 4); return 4; }              // GL_VIEWPORT
            case 0x0C23 -> {                                                                        // GL_COLOR_WRITEMASK
                int m = VRenderSystem.getColorMask();
                out[0] = b((m & VK_COLOR_COMPONENT_R_BIT) != 0);
                out[1] = b((m & VK_COLOR_COMPONENT_G_BIT) != 0);
                out[2] = b((m & VK_COLOR_COMPONENT_B_BIT) != 0);
                out[3] = b((m & VK_COLOR_COMPONENT_A_BIT) != 0);
                return 4;
            }
            case 0x0C22 -> {                                                                        // GL_COLOR_CLEAR_VALUE
                for (int k = 0; k < 4; k++) out[k] = VRenderSystem.clearColor.get(k);
                return 4;
            }
            case GL_DITHER, GL_MULTISAMPLE -> { out[0] = b(OTHER_CAPS.contains(pname)); return 1; }
            // --- stencil (tracked, not emulated)
            case GL_STENCIL_TEST -> { out[0] = b(stencilTest); return 1; }
            case 0x0B92, 0x8800 -> { out[0] = stencilFunc; return 1; }
            case 0x0B97, 0x8CA3 -> { out[0] = stencilRef; return 1; }
            case 0x0B93, 0x8CA4 -> { out[0] = stencilValueMask; return 1; }
            case 0x0B98, 0x8CA5 -> { out[0] = stencilWriteMask; return 1; }
            case 0x0B94, 0x8801 -> { out[0] = stencilFail; return 1; }
            case 0x0B95, 0x8802 -> { out[0] = stencilZFail; return 1; }
            case 0x0B96, 0x8803 -> { out[0] = stencilZPass; return 1; }
            case 0x0B91 -> { out[0] = clearStencil; return 1; }
            // --- objects
            case 0x8CA6 -> { out[0] = VkGlFramebuffer.getBoundDrawId(); return 1; }  // GL_(DRAW_)FRAMEBUFFER_BINDING
            case 0x8CAA -> { out[0] = VkGlFramebuffer.getBoundReadId(); return 1; }  // GL_READ_FRAMEBUFFER_BINDING
            case 0x0C01, 0x8825 -> { out[0] = VkGlFramebuffer.getDrawBuffer(); return 1; } // GL_DRAW_BUFFER(0)
            case 0x0C02 -> { out[0] = VkGlFramebuffer.getReadBuffer(); return 1; }        // GL_READ_BUFFER
            case 0x8CA7 -> { out[0] = VkGlRenderbuffer.getBoundId(); return 1; }     // GL_RENDERBUFFER_BINDING
            case 0x85B5 -> { out[0] = vertexArray; return 1; }                        // GL_VERTEX_ARRAY_BINDING
            case 0x8B8D -> { out[0] = currentProgram; return 1; }                     // GL_CURRENT_PROGRAM
            case 0x8894 -> { out[0] = VkGlBuffer.getBinding(0x8892); return 1; }      // ARRAY_BUFFER_BINDING
            case 0x8895 -> { out[0] = VkGlBuffer.getBinding(0x8893); return 1; }      // ELEMENT_ARRAY_BUFFER_BINDING
            case 0x88ED -> { out[0] = VkGlBuffer.getBinding(0x88EB); return 1; }      // PIXEL_PACK_BUFFER_BINDING
            case 0x88EF -> { out[0] = VkGlBuffer.getBinding(0x88EC); return 1; }      // PIXEL_UNPACK_BUFFER_BINDING
            case 0x8A28 -> { out[0] = VkGlBuffer.getBinding(0x8A11); return 1; }      // UNIFORM_BUFFER_BINDING
            case 0x8F36 -> { out[0] = VkGlBuffer.getBinding(0x8F36); return 1; }      // COPY_READ_BUFFER(_BINDING)
            case 0x8F37 -> { out[0] = VkGlBuffer.getBinding(0x8F37); return 1; }      // COPY_WRITE_BUFFER(_BINDING)
            case 0x8C8F -> { out[0] = VkGlBuffer.getBinding(0x8C8E); return 1; }      // TRANSFORM_FEEDBACK_BUFFER_BINDING
            // --- pixel store
            case 0x0CF5 -> { out[0] = unpackAlignment; return 1; }
            case 0x0CF2 -> { out[0] = unpackRowLength; return 1; }
            case 0x0CF3 -> { out[0] = unpackSkipRows; return 1; }
            case 0x0CF4 -> { out[0] = unpackSkipPixels; return 1; }
            case 0x0D05 -> { out[0] = packAlignment; return 1; }
            case 0x0D02 -> { out[0] = packRowLength; return 1; }
            case 0x0D03 -> { out[0] = packSkipRows; return 1; }
            case 0x0D04 -> { out[0] = packSkipPixels; return 1; }
            // --- implementation limits and identity ("3.2 core, no extensions", matches GlStrings)
            case 0x0D33, 0x84E8 -> { out[0] = maxTextureSize(); return 1; }           // MAX_TEXTURE_SIZE, MAX_RENDERBUFFER_SIZE
            case 0x0D3A -> { out[0] = maxTextureSize(); out[1] = maxTextureSize(); return 2; } // MAX_VIEWPORT_DIMS
            case 0x8872, 0x8B4D, 0x8B4C -> { out[0] = 12; return 1; }                 // MAX_(COMBINED_|VERTEX_)TEXTURE_IMAGE_UNITS
            case 0x8869 -> { out[0] = 16; return 1; }                                 // MAX_VERTEX_ATTRIBS
            case 0x8824, 0x8CDF -> { out[0] = 1; return 1; }                          // MAX_DRAW_BUFFERS, MAX_COLOR_ATTACHMENTS
            case 0x84FF -> { out[0] = 1; return 1; }                                  // MAX_TEXTURE_MAX_ANISOTROPY
            case 0x821B -> { out[0] = 3; return 1; }                                  // MAJOR_VERSION
            case 0x821C -> { out[0] = 2; return 1; }                                  // MINOR_VERSION
            case 0x821D -> { out[0] = 0; return 1; }                                  // NUM_EXTENSIONS
            case 0x9126 -> { out[0] = 1; return 1; }                                  // CONTEXT_PROFILE_MASK: core
            case 0x821E -> { out[0] = 0; return 1; }                                  // CONTEXT_FLAGS
            case 0x80A9, 0x80A8 -> { out[0] = 0; return 1; }                          // SAMPLES, SAMPLE_BUFFERS
            case 0x0D50, 0x0D51, 0x0D52, 0x0D53 -> { out[0] = 8; return 1; }          // SUBPIXEL/RED/GREEN/BLUE_BITS
            case 0x0D55, 0x0D57 -> { out[0] = pname == 0x0D55 ? 8 : 0; return 1; }   // ALPHA_BITS, STENCIL_BITS
            case 0x0D56 -> { out[0] = 24; return 1; }                                 // DEPTH_BITS
            case 0x8B4A, 0x8B49 -> { out[0] = 4096; return 1; }                       // MAX_VERTEX/FRAGMENT_UNIFORM_COMPONENTS
            case 0x8A2F -> { out[0] = 16384; return 1; }                              // MAX_UNIFORM_BLOCK_SIZE
            case 0x8A2E, 0x8A2B, 0x8A2D -> { out[0] = 12; return 1; }                 // MAX_*_UNIFORM_BLOCKS
            case 0x8A34 -> { out[0] = 256; return 1; }                                // UNIFORM_BUFFER_OFFSET_ALIGNMENT
            default -> { return -1; }
        }
    }

    private static int maxTextureSize() {
        try {
            return VRenderSystem.maxSupportedTextureSize();
        } catch (Throwable t) {
            return 16384;
        }
    }

    private static double b(boolean v) {
        return v ? 1 : 0;
    }

    private static double[] toD(int[] a) {
        double[] d = new double[a.length];
        for (int k = 0; k < a.length; k++) d[k] = a[k];
        return d;
    }

    /** Vulkan blend factor -> GL enum (inverse of PipelineState's mapping). */
    static int glFactor(int vk) {
        return switch (vk) {
            case VK_BLEND_FACTOR_ZERO -> 0;
            case VK_BLEND_FACTOR_ONE -> 1;
            case VK_BLEND_FACTOR_SRC_COLOR -> 0x0300;
            case VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR -> 0x0301;
            case VK_BLEND_FACTOR_SRC_ALPHA -> 0x0302;
            case VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA -> 0x0303;
            case VK_BLEND_FACTOR_DST_ALPHA -> 0x0304;
            case VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA -> 0x0305;
            case VK_BLEND_FACTOR_DST_COLOR -> 0x0306;
            case VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR -> 0x0307;
            case VK_BLEND_FACTOR_SRC_ALPHA_SATURATE -> 0x0308;
            case VK_BLEND_FACTOR_CONSTANT_COLOR -> 0x8001;
            case VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR -> 0x8002;
            case VK_BLEND_FACTOR_CONSTANT_ALPHA -> 0x8003;
            case VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA -> 0x8004;
            default -> 1;
        };
    }

    static int glBlendOp(int vk) {
        return switch (vk) {
            case VK_BLEND_OP_SUBTRACT -> 0x800A;
            case VK_BLEND_OP_REVERSE_SUBTRACT -> 0x800B;
            case VK_BLEND_OP_MIN -> 0x8007;
            case VK_BLEND_OP_MAX -> 0x8008;
            default -> 0x8006; // GL_FUNC_ADD
        };
    }

    private static final double[] SCRATCH = new double[16];

    /** glGetIntegerv semantics: booleans 0/1, floats rounded, colours in [0,1] scaled as GL does is not needed here. */
    public static void getIntegerv(int pname, long ptr) {
        if (ptr == NULL) return;
        int n = get(pname, SCRATCH);
        if (n < 0) {
            unknownPname("glGetIntegerv", pname);
            memPutInt(ptr, 0);
            return;
        }
        for (int k = 0; k < n; k++) memPutInt(ptr + 4L * k, (int) Math.round(SCRATCH[k]));
    }

    public static void getFloatv(int pname, long ptr) {
        if (ptr == NULL) return;
        int n = get(pname, SCRATCH);
        if (n < 0) {
            unknownPname("glGetFloatv", pname);
            memPutFloat(ptr, 0f);
            return;
        }
        for (int k = 0; k < n; k++) memPutFloat(ptr + 4L * k, (float) SCRATCH[k]);
    }

    public static void getDoublev(int pname, long ptr) {
        if (ptr == NULL) return;
        int n = get(pname, SCRATCH);
        if (n < 0) {
            unknownPname("glGetDoublev", pname);
            memPutDouble(ptr, 0);
            return;
        }
        for (int k = 0; k < n; k++) memPutDouble(ptr + 8L * k, SCRATCH[k]);
    }

    public static void getBooleanv(int pname, long ptr) {
        if (ptr == NULL) return;
        int n = get(pname, SCRATCH);
        if (n < 0) {
            unknownPname("glGetBooleanv", pname);
            memPutByte(ptr, (byte) 0);
            return;
        }
        for (int k = 0; k < n; k++) memPutByte(ptr + k, (byte) (SCRATCH[k] != 0 ? 1 : 0));
    }

    /** Same values for the Java array overloads (GL11C.glGetIntegerv(int, int[]) etc.). */
    public static void getIntegerv(int pname, int[] out) {
        if (out == null || out.length == 0) return;
        int n = get(pname, SCRATCH);
        if (n < 0) {
            unknownPname("glGetIntegerv", pname);
            out[0] = 0;
            return;
        }
        for (int k = 0; k < n && k < out.length; k++) out[k] = (int) Math.round(SCRATCH[k]);
    }

    public static void getFloatv(int pname, float[] out) {
        if (out == null || out.length == 0) return;
        int n = get(pname, SCRATCH);
        if (n < 0) {
            unknownPname("glGetFloatv", pname);
            out[0] = 0;
            return;
        }
        for (int k = 0; k < n && k < out.length; k++) out[k] = (float) SCRATCH[k];
    }

    private static void unknownPname(String fn, int pname) {
        GlShim.unhandled(fn + "(0x" + Integer.toHexString(pname) + ")");
    }

    // ================================================================ raw-GL handlers (threads without a context)

    private static boolean registered;

    /** Registers the handlers on {@link GlNet}; called by {@link GlNet#install()} before it builds the closures. */
    static synchronized void register() {
        if (registered) return;
        registered = true;

        // ---- errors, sync, hints
        on("glGetError", "I:", a -> 0);
        on("glFinish", "V:", a -> 0);
        on("glFlush", "V:", a -> 0);
        on("glHint", "V:II", a -> 0);

        // ---- capabilities
        on("glEnable", "V:I", a -> { enable(i(a, 0)); return 0; });
        on("glDisable", "V:I", a -> { disable(i(a, 0)); return 0; });
        on("glIsEnabled", "Z:I", a -> isEnabled(i(a, 0)) ? 1 : 0);

        // ---- queries
        on("glGetIntegerv", "V:IP", a -> { getIntegerv(i(a, 0), p(a, 1)); return 0; });
        on("glGetFloatv", "V:IP", a -> { getFloatv(i(a, 0), p(a, 1)); return 0; });
        on("glGetDoublev", "V:IP", a -> { getDoublev(i(a, 0), p(a, 1)); return 0; });
        on("glGetBooleanv", "V:IP", a -> { getBooleanv(i(a, 0), p(a, 1)); return 0; });

        // ---- blend / depth / raster
        on("glBlendFunc", "V:II", a -> { blendFunc(i(a, 0), i(a, 1)); return 0; });
        on("glBlendFuncSeparate", "V:IIII", a -> { blendFuncSeparate(i(a, 0), i(a, 1), i(a, 2), i(a, 3)); return 0; });
        on("glBlendEquation", "V:I", a -> { blendEquation(i(a, 0)); return 0; });
        on("glBlendEquationSeparate", "V:II", a -> { blendEquation(i(a, 0)); return 0; });
        on("glBlendColor", "V:FFFF", a -> {
            for (int k = 0; k < 4; k++) blendColor[k] = f(a, k);
            return 0;
        });
        on("glDepthFunc", "V:I", a -> { depthFunc(i(a, 0)); return 0; });
        on("glDepthMask", "V:Z", a -> { depthMask(z(a, 0)); return 0; });
        on("glColorMask", "V:ZZZZ", a -> { colorMask(z(a, 0), z(a, 1), z(a, 2), z(a, 3)); return 0; });
        on("glCullFace", "V:I", a -> { cullFace(i(a, 0)); return 0; });
        on("glFrontFace", "V:I", a -> { frontFace = i(a, 0); return 0; });
        on("glPolygonMode", "V:II", a -> { polygonMode(i(a, 0), i(a, 1)); return 0; });
        on("glPolygonOffset", "V:FF", a -> { polygonOffset(f(a, 0), f(a, 1)); return 0; });
        on("glLogicOp", "V:I", a -> { logicOp(i(a, 0)); return 0; });
        on("glLineWidth", "V:F", a -> { lineWidth(f(a, 0)); return 0; });
        on("glViewport", "V:IIII", a -> { viewport(i(a, 0), i(a, 1), i(a, 2), i(a, 3)); return 0; });
        on("glScissor", "V:IIII", a -> { scissor(i(a, 0), i(a, 1), i(a, 2), i(a, 3)); return 0; });

        // ---- clears
        on("glClear", "V:I", a -> { VRenderSystem.clear(i(a, 0)); return 0; });
        on("glClearColor", "V:FFFF", a -> { VRenderSystem.setClearColor(f(a, 0), f(a, 1), f(a, 2), f(a, 3)); return 0; });
        on("glClearDepth", "V:D", a -> { VRenderSystem.clearDepth(d(a, 0)); return 0; });
        on("glClearDepthf", "V:F", a -> { VRenderSystem.clearDepth(f(a, 0)); return 0; });
        on("glClearStencil", "V:I", a -> { clearStencil(i(a, 0)); return 0; });

        // ---- stencil (tracked)
        on("glStencilFunc", "V:III", a -> { stencilFunc(i(a, 0), i(a, 1), i(a, 2)); return 0; });
        on("glStencilFuncSeparate", "V:IIII", a -> { stencilFunc(i(a, 1), i(a, 2), i(a, 3)); return 0; });
        on("glStencilMask", "V:I", a -> { stencilMask(i(a, 0)); return 0; });
        on("glStencilMaskSeparate", "V:II", a -> { stencilMask(i(a, 1)); return 0; });
        on("glStencilOp", "V:III", a -> { stencilOp(i(a, 0), i(a, 1), i(a, 2)); return 0; });
        on("glStencilOpSeparate", "V:IIII", a -> { stencilOp(i(a, 1), i(a, 2), i(a, 3)); return 0; });

        // ---- textures
        on("glActiveTexture", "V:I", a -> { activeTexture(i(a, 0)); return 0; });
        on("glBindTexture", "V:II", a -> {
            if (i(a, 0) == GL_TEXTURE_2D) bindTexture(i(a, 1));
            else GlShim.unhandled("glBindTexture(target 0x" + Integer.toHexString(i(a, 0)) + ")");
            return 0;
        });
        on("glGenTextures", "V:IP", a -> {
            int n = i(a, 0);
            long p = p(a, 1);
            for (int k = 0; k < n; k++) memPutInt(p + 4L * k, VkGlTexture.genTextureId());
            return 0;
        });
        on("glDeleteTextures", "V:IP", a -> {
            int n = i(a, 0);
            long p = p(a, 1);
            for (int k = 0; k < n; k++) VkGlTexture.glDeleteTextures(memGetInt(p + 4L * k));
            return 0;
        });
        on("glIsTexture", "Z:I", a -> VkGlTexture.exists(i(a, 0)) ? 1 : 0);
        on("glTexImage2D", "V:IIIIIIIIP", a -> {
            if (i(a, 0) != GL_TEXTURE_2D) {
                GlShim.unhandled("glTexImage2D(target 0x" + Integer.toHexString(i(a, 0)) + ")");
                return 0;
            }
            VkGlTexture.texImage2D(i(a, 0), i(a, 1), i(a, 2), i(a, 3), i(a, 4), i(a, 5), i(a, 6), i(a, 7), p(a, 8));
            return 0;
        });
        on("glTexSubImage2D", "V:IIIIIIIIP", a -> {
            if (i(a, 0) != GL_TEXTURE_2D) {
                GlShim.unhandled("glTexSubImage2D(target 0x" + Integer.toHexString(i(a, 0)) + ")");
                return 0;
            }
            VkGlTexture.texSubImage2D(i(a, 0), i(a, 1), i(a, 2), i(a, 3), i(a, 4), i(a, 5), i(a, 6), i(a, 7), p(a, 8));
            return 0;
        });
        on("glTexParameteri", "V:III", a -> { texParameter(i(a, 0), i(a, 1), i(a, 2)); return 0; });
        on("glTexParameterf", "V:IIF", a -> { texParameter(i(a, 0), i(a, 1), (int) f(a, 2)); return 0; });
        on("glGetTexParameteriv", "V:IIP", a -> {
            int v = i(a, 0) == GL_TEXTURE_2D && VkGlTexture.getBoundTexture() != null ? VkGlTexture.getTexParameteri(i(a, 0), i(a, 1)) : 0;
            memPutInt(p(a, 2), v);
            return 0;
        });
        on("glGetTexLevelParameteriv", "V:IIIP", a -> {
            int v = i(a, 0) == GL_TEXTURE_2D && VkGlTexture.getBoundTexture() != null && VkGlTexture.getBoundTexture().getVulkanImage() != null
                    ? VkGlTexture.getTexLevelParameter(i(a, 0), i(a, 1), i(a, 2)) : 0;
            memPutInt(p(a, 3), v);
            return 0;
        });
        on("glPixelStorei", "V:II", a -> { pixelStore(i(a, 0), i(a, 1)); return 0; });
        on("glPixelStoref", "V:IF", a -> { pixelStore(i(a, 0), (int) f(a, 1)); return 0; });
        on("glGenerateMipmap", "V:I", a -> { if (i(a, 0) == GL_TEXTURE_2D) VkGlTexture.generateMipmap(i(a, 0)); return 0; });
        on("glGetTexImage", "V:IIIIP", a -> {
            VkGlTexture t = VkGlTexture.getBoundTexture();
            if (t == null || t.getVulkanImage() == null) return 0;
            VkGlTexture.getTexImage(i(a, 0), i(a, 1), i(a, 2), i(a, 3), p(a, 4));
            return 0;
        });

        // ---- copies and readbacks from the READ framebuffer (P1-2 slice 2)
        on("glCopyTexSubImage2D", "V:IIIIIIII", a -> {
            CopyTex.copyTexSubImage2D(i(a, 0), i(a, 1), i(a, 2), i(a, 3), i(a, 4), i(a, 5), i(a, 6), i(a, 7));
            return 0;
        });
        on("glCopyTexImage2D", "V:IIIIIIII", a -> {
            CopyTex.copyTexImage2D(i(a, 0), i(a, 1), i(a, 2), i(a, 3), i(a, 4), i(a, 5), i(a, 6), i(a, 7));
            return 0;
        });
        on("glReadPixels", "V:IIIIIIP", a -> { readPixels(i(a, 0), i(a, 1), i(a, 2), i(a, 3), i(a, 4), i(a, 5), p(a, 6)); return 0; });

        // ---- buffers (pixel pack / unpack have storage; others are tracked bindings)
        on("glGenBuffers", "V:IP", a -> {
            int n = i(a, 0);
            long p = p(a, 1);
            for (int k = 0; k < n; k++) memPutInt(p + 4L * k, VkGlBuffer.glGenBuffers());
            return 0;
        });
        on("glBindBuffer", "V:II", a -> { VkGlBuffer.glBindBuffer(i(a, 0), i(a, 1)); return 0; });
        on("glDeleteBuffers", "V:IP", a -> {
            int n = i(a, 0);
            long p = p(a, 1);
            for (int k = 0; k < n; k++) VkGlBuffer.glDeleteBuffers(memGetInt(p + 4L * k));
            return 0;
        });
        on("glBufferData", "V:IJPI", a -> {
            int target = i(a, 0);
            long size = j(a, 1), data = p(a, 2);
            if (data != NULL) VkGlBuffer.glBufferData(target, memByteBuffer(data, (int) size), i(a, 3));
            else VkGlBuffer.glBufferData(target, size, i(a, 3));
            return 0;
        });
        on("glMapBuffer", "P:II", a -> {
            java.nio.ByteBuffer b = VkGlBuffer.glMapBuffer(i(a, 0), i(a, 1));
            return b != null ? memAddress(b) : NULL;
        });
        on("glUnmapBuffer", "Z:I", a -> 1);
        on("glGetBufferParameteriv", "V:IIP", a -> {
            memPutInt(p(a, 2), i(a, 1) == 0x8764 /* GL_BUFFER_SIZE */ ? VkGlBuffer.boundSize(i(a, 0)) : 0);
            return 0;
        });

        // ---- framebuffers / renderbuffers
        on("glGenFramebuffers", "V:IP", a -> {
            int n = i(a, 0);
            long p = p(a, 1);
            for (int k = 0; k < n; k++) memPutInt(p + 4L * k, VkGlFramebuffer.genFramebufferId());
            return 0;
        });
        on("glBindFramebuffer", "V:II", a -> { VkGlFramebuffer.bindFramebuffer(i(a, 0), i(a, 1)); return 0; });
        on("glDeleteFramebuffers", "V:IP", a -> {
            int n = i(a, 0);
            long p = p(a, 1);
            for (int k = 0; k < n; k++) VkGlFramebuffer.deleteFramebuffer(memGetInt(p + 4L * k));
            return 0;
        });
        on("glFramebufferTexture2D", "V:IIIII", a -> {
            VkGlFramebuffer.framebufferTexture2D(i(a, 0), i(a, 1), i(a, 2), i(a, 3), i(a, 4));
            return 0;
        });
        on("glFramebufferRenderbuffer", "V:IIII", a -> {
            VkGlFramebuffer.framebufferRenderbuffer(i(a, 0), i(a, 1), i(a, 2), i(a, 3));
            return 0;
        });
        on("glCheckFramebufferStatus", "I:I", a -> VkGlFramebuffer.glCheckFramebufferStatus(i(a, 0)));
        on("glFramebufferTexture", "V:IIII", a -> {
            VkGlFramebuffer.framebufferTexture2D(i(a, 0), i(a, 1), GL_TEXTURE_2D, i(a, 2), i(a, 3));
            return 0;
        });
        on("glDrawBuffer", "V:I", a -> { VkGlFramebuffer.drawBuffer(i(a, 0)); return 0; });
        on("glDrawBuffers", "V:IP", a -> {
            int n = i(a, 0);
            if (n > 1) GlShim.unhandled("glDrawBuffers(n > 1: one colour attachment under Iroh)");
            if (n >= 1) VkGlFramebuffer.drawBuffer(memGetInt(p(a, 1)));
            return 0;
        });
        on("glReadBuffer", "V:I", a -> { VkGlFramebuffer.readBuffer(i(a, 0)); return 0; });
        on("glBlitFramebuffer", "V:IIIIIIIIII", a -> {
            VkGlFramebuffer.glBlitFramebuffer(i(a, 0), i(a, 1), i(a, 2), i(a, 3), i(a, 4), i(a, 5), i(a, 6), i(a, 7), i(a, 8), i(a, 9));
            return 0;
        });
        on("glGenRenderbuffers", "V:IP", a -> {
            int n = i(a, 0);
            long p = p(a, 1);
            for (int k = 0; k < n; k++) memPutInt(p + 4L * k, VkGlRenderbuffer.genId());
            return 0;
        });
        on("glBindRenderbuffer", "V:II", a -> { VkGlRenderbuffer.bindRenderbuffer(i(a, 0), i(a, 1)); return 0; });
        on("glRenderbufferStorage", "V:IIII", a -> {
            VkGlRenderbuffer.renderbufferStorage(i(a, 0), i(a, 1), i(a, 2), i(a, 3));
            return 0;
        });
        on("glDeleteRenderbuffers", "V:IP", a -> {
            int n = i(a, 0);
            long p = p(a, 1);
            for (int k = 0; k < n; k++) VkGlRenderbuffer.deleteRenderbuffer(memGetInt(p + 4L * k));
            return 0;
        });

        // ---- program / vertex array bindings (tracked; mod GLSL programs are not compiled, see P1-2 design list)
        on("glUseProgram", "V:I", a -> { useProgram(i(a, 0)); return 0; });
        on("glGenVertexArrays", "V:IP", a -> {
            int n = i(a, 0);
            long p = p(a, 1);
            for (int k = 0; k < n; k++) memPutInt(p + 4L * k, nextVertexArray++);
            return 0;
        });
        on("glBindVertexArray", "V:I", a -> { vertexArray = i(a, 0); return 0; });
        on("glDeleteVertexArrays", "V:IP", a -> {
            int n = i(a, 0);
            long p = p(a, 1);
            for (int k = 0; k < n; k++) GlPrograms.deleteVertexArray(memGetInt(p + 4L * k));
            return 0;
        });

        // ---- sync objects (P1-2 slice 2, item 4). Every emulated GL command that produces host-visible data
        // (glReadPixels, glGetTexImage, buffer storage) completes before it returns (FrameSync), so a fence is
        // signalled when it is created; GPU work stays ordered by Iroh's own submission order.
        on("glFenceSync", "P:II", a -> 0x5EC00000L + (++fenceCounter));
        on("glIsSync", "Z:P", a -> p(a, 0) != NULL ? 1 : 0);
        on("glDeleteSync", "V:P", a -> 0);
        on("glWaitSync", "V:PIJ", a -> 0);
        on("glClientWaitSync", "I:PIJ", a -> 0x911A); // GL_ALREADY_SIGNALED
        on("glGetSynciv", "V:PIIPP", a -> {
            int pname = i(a, 1), bufSize = i(a, 2);
            long length = p(a, 3), values = p(a, 4);
            int v = switch (pname) {
                case 0x9112 -> 0x9116;  // OBJECT_TYPE -> SYNC_FENCE
                case 0x9113 -> 0x9117;  // SYNC_CONDITION -> SYNC_GPU_COMMANDS_COMPLETE
                case 0x9114 -> 0x9119;  // SYNC_STATUS -> SIGNALED
                default -> 0;           // SYNC_FLAGS
            };
            if (values != NULL && bufSize > 0) memPutInt(values, v);
            if (length != NULL) memPutInt(length, 1);
            return 0;
        });

        // ---- GLSL programs through raw GL, vertex arrays, draws (P1-2 slice 2, item 2)
        GlPrograms.register();
    }

    /** glReadPixels: rectangle of the READ framebuffer (GL row order, RQ-16) with pack state and pack buffers. */
    public static void readPixels(int x, int y, int w, int h, int format, int type, long pixels) {
        net.vulkanmod.vulkan.texture.VulkanImage img = VkGlFramebuffer.readImage(format == 0x1902 /* DEPTH_COMPONENT */);
        if (img == null || w <= 0 || h <= 0) return;
        PixelTransfer.Dst layout = VkGlTexture.dstLayout(img.format);
        if (layout == null) {
            GlShim.unhandled("glReadPixels(vk format " + img.format + ")");
            return;
        }
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(img.width, x + w), y1 = Math.min(img.height, y + h);
        if (x1 <= x0 || y1 <= y0) return;
        if (x0 != x || y0 != y || x1 - x0 != w || y1 - y0 != h) {
            // partly outside the buffer: GL leaves those client pixels undefined; read the inside part in place
            GlShim.note("glReadPixels(clipped)", "");
        }
        long offset = (long) (y0 - y) * VkGlTexture.packStride(w, format, type) + (long) (x0 - x) * PixelTransfer.bytesPerPixel(format, type);
        VkGlTexture.readInto(img, 0, x0, y0, x1 - x0, y1 - y0, format, type, pixels + offset, layout, "glReadPixels");
    }

    private static void texParameter(int target, int pname, int param) {
        if (target != GL_TEXTURE_2D) {
            GlShim.unhandled("glTexParameter(target 0x" + Integer.toHexString(target) + ")");
            return;
        }
        VkGlTexture.texParameteri(target, pname, param);
    }
}
