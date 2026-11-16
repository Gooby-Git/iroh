package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.gl.*;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

@Mixin(GlStateManager.class)
public class GlStateManagerM {

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _bindTexture(int i) {
        dev.elemore.iroh.glshim.GlState.bindTexture(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _disableBlend() {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.disable(3042);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _enableBlend() {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.enable(3042);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _blendFunc(int i, int j) {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.blendFunc(i, j);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _blendFuncSeparate(int i, int j, int k, int l) {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.blendFuncSeparate(i, j, k, l);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _blendEquation(int i) {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.blendEquation(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _disableScissorTest() {
        dev.elemore.iroh.glshim.GlState.disable(3089);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _enableScissorTest() {
        dev.elemore.iroh.glshim.GlState.enable(3089);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _enableCull() {
        dev.elemore.iroh.glshim.GlState.enable(2884);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _disableCull() {
        dev.elemore.iroh.glshim.GlState.disable(2884);
    }

    /**
     * @author
     */
    @Redirect(method = "_viewport", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glViewport(IIII)V"), remap = false)
    private static void _viewport(int x, int y, int width, int height) {
        // Iroh P1-2 / RQ-11: layout follows the bound target (GL layout off-screen, top-down main)
        dev.elemore.iroh.glshim.GlState.viewport(x, y, width, height);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _scissorBox(int x, int y, int width, int height) {
        dev.elemore.iroh.glshim.GlState.scissor(x, y, width, height);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int _getError() {
        return 0;
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, @Nullable IntBuffer pixels) {
        RenderSystem.assertOnRenderThread();
        VkGlTexture.texImage2D(target, level, internalFormat, width, height, border, format, type, pixels != null ? MemoryUtil.memByteBuffer(pixels) : null);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _texSubImage2D(int target, int level, int offsetX, int offsetY, int width, int height, int format, int type, long pixels) {
        RenderSystem.assertOnRenderThread();
        VkGlTexture.texSubImage2D(target, level, offsetX, offsetY, width, height, format, type, pixels);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _activeTexture(int i) {
        dev.elemore.iroh.glshim.GlState.activeTexture(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _texParameter(int i, int j, int k) {
        VkGlTexture.texParameteri(i, j, k);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _texParameter(int i, int j, float k) {
        //TODO
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int _getTexLevelParameter(int i, int j, int k) {
        return VkGlTexture.getTexLevelParameter(i, j, k);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _pixelStore(int pname, int param) {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.pixelStore(pname, param);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int _genTexture() {
        RenderSystem.assertOnRenderThread();
        return VkGlTexture.genTextureId();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _deleteTexture(int i) {
        RenderSystem.assertOnRenderThread();
        VkGlTexture.glDeleteTextures(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.colorMask(red, green, blue, alpha);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _polygonMode(int face, int mode) {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.polygonMode(face, mode);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _enablePolygonOffset() {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.enable(32823);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _disablePolygonOffset() {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.disable(32823);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _polygonOffset(float f, float g) {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.polygonOffset(f, g);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _enableColorLogicOp() {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.enable(3058);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _disableColorLogicOp() {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.disable(3058);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _logicOp(int i) {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.logicOp(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _clearColor(float f, float g, float h, float i) {
        RenderSystem.assertOnRenderThreadOrInit();
        VRenderSystem.setClearColor(f, g, h, i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _clearDepth(double d) {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.clearDepth(d);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _clear(int mask, boolean bl) {
        RenderSystem.assertOnRenderThread();
        VRenderSystem.clear(mask);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _disableDepthTest() {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.disable(2929);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _enableDepthTest() {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.enable(2929);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _depthFunc(int i) {
        RenderSystem.assertOnRenderThreadOrInit();
        dev.elemore.iroh.glshim.GlState.depthFunc(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _depthMask(boolean bl) {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.depthMask(bl);

    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int glGenFramebuffers() {
        RenderSystem.assertOnRenderThread();
        return VkGlFramebuffer.genFramebufferId();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int glGenRenderbuffers() {
        RenderSystem.assertOnRenderThreadOrInit();
        return VkGlRenderbuffer.genId();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glBindFramebuffer(int i, int j) {
        RenderSystem.assertOnRenderThread();
        VkGlFramebuffer.bindFramebuffer(i, j);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glFramebufferTexture2D(int i, int j, int k, int l, int m) {
        RenderSystem.assertOnRenderThread();
        VkGlFramebuffer.framebufferTexture2D(i, j, k, l, m);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glBindRenderbuffer(int i, int j) {
        RenderSystem.assertOnRenderThreadOrInit();
        VkGlRenderbuffer.bindRenderbuffer(i, j);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glFramebufferRenderbuffer(int i, int j, int k, int l) {
        RenderSystem.assertOnRenderThreadOrInit();
        VkGlFramebuffer.framebufferRenderbuffer(i, j, k, l);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glRenderbufferStorage(int i, int j, int k, int l) {
        RenderSystem.assertOnRenderThreadOrInit();
        VkGlRenderbuffer.renderbufferStorage(i, j, k, l);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int glCheckFramebufferStatus(int i) {
        RenderSystem.assertOnRenderThreadOrInit();
        return VkGlFramebuffer.glCheckFramebufferStatus(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int _glGenBuffers() {
        RenderSystem.assertOnRenderThreadOrInit();
        return VkGlBuffer.glGenBuffers();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glBindBuffer(int i, int j) {
        RenderSystem.assertOnRenderThread();
        VkGlBuffer.glBindBuffer(i, j);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glBufferData(int i, ByteBuffer byteBuffer, int j) {
        RenderSystem.assertOnRenderThread();
        VkGlBuffer.glBufferData(i, byteBuffer, j);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glBufferData(int i, long l, int j) {
        RenderSystem.assertOnRenderThread();
        VkGlBuffer.glBufferData(i, l, j);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    @Nullable
    public static ByteBuffer _glMapBuffer(int i, int j) {
        RenderSystem.assertOnRenderThreadOrInit();
        return VkGlBuffer.glMapBuffer(i, j);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glUnmapBuffer(int i) {
        RenderSystem.assertOnRenderThread();
        VkGlBuffer.glUnmapBuffer(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glDeleteBuffers(int i) {
        RenderSystem.assertOnRenderThread();
        VkGlBuffer.glDeleteBuffers(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _disableVertexAttribArray(int i) {}

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void _glUseProgram(int i) {
        RenderSystem.assertOnRenderThread();
        dev.elemore.iroh.glshim.GlState.useProgram(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int glCreateProgram() {
        RenderSystem.assertOnRenderThread();
        return VkGlProgram.genProgramId();
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static void glDeleteProgram(int i) {
        RenderSystem.assertOnRenderThread();
//        GL20.glDeleteProgram(i);
    }

    /**
     * @author
     */
    @Overwrite(remap = false)
    public static int _glGenVertexArrays() {
        RenderSystem.assertOnRenderThreadOrInit();
        // TODO
        return 0;
    }
}
