package net.vulkanmod.mixin.render.target;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.SceneTarget;
import net.vulkanmod.vulkan.pass.MainPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's main render target is Iroh's scene target (R-1, {@code DefaultMainPass} / {@code SceneTarget}).
 * <ul>
 *   <li>{@code frameBufferId} is 0: in Iroh's GL emulation framebuffer 0 IS the main target, so mods that bind
 *       {@code getMainRenderTarget().frameBufferId}, or bind 0, draw into it.</li>
 *   <li>{@code colorTextureId} / {@code depthBufferId} are GL texture ids wrapping the scene images; they stay
 *       the same for the whole session (a resize rebinds the same ids to the new images).</li>
 *   <li>width/height follow the scene images (the swapchain extent), not the size passed to resize().</li>
 *   <li>resize / createBuffers / destroyBuffers never allocate or free anything: the images belong to the
 *       main pass and are recreated by Renderer.recreateSwapChain.</li>
 *   <li>blitToScreen is a no-op: "the screen" is this target until the end-of-frame copy to the swapchain.</li>
 * </ul>
 * These are overrides on MainTarget only; every other RenderTarget keeps the vanilla methods (and every mod
 * injector on them). createFrameBuffer uses a HEAD-cancel inject, not an Overwrite (lessons.md P1-1b).
 */
@Mixin(MainTarget.class)
public class MainTargetMixin extends RenderTarget {

    // RenderTarget.clearChannels is private; MainTarget keeps its own copy (vanilla default 1,1,1,0).
    @Unique private float iroh$clearR = 1.0f, iroh$clearG = 1.0f, iroh$clearB = 1.0f, iroh$clearA = 0.0f;

    public MainTargetMixin(boolean useDepth) {
        super(useDepth);
    }

    @Inject(method = "createFrameBuffer", at = @At("HEAD"), cancellable = true)
    private void iroh$createFrameBuffer(int width, int height, CallbackInfo ci) {
        this.frameBufferId = 0;

        MainPass mainPass = Renderer.getInstance().getMainPass();
        this.colorTextureId = mainPass.getColorAttachment().id;
        this.depthBufferId = mainPass.getDepthAttachment().id;

        // Same as the 0.5.5 base: report the requested size here; Minecraft compares it to the window size
        // right after construction. resize() / Renderer.recreateSwapChain then sync to the scene images.
        this.viewWidth = width;
        this.viewHeight = height;
        this.width = width;
        this.height = height;

        ci.cancel();
    }

    @Unique
    private void iroh$syncSize() {
        SceneTarget sceneTarget = Renderer.getInstance().getMainPass().getSceneTarget();
        if (sceneTarget != null && sceneTarget.getWidth() > 0 && sceneTarget.getHeight() > 0) {
            this.width = this.viewWidth = sceneTarget.getWidth();
            this.height = this.viewHeight = sceneTarget.getHeight();
        }
    }

    @Override
    public void resize(int width, int height, boolean clearError) {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(this::iroh$syncSize);
        } else {
            this.iroh$syncSize();
        }
    }

    @Override
    public void createBuffers(int width, int height, boolean clearError) {
        RenderSystem.assertOnRenderThreadOrInit();
        this.iroh$syncSize();
    }

    @Override
    public void destroyBuffers() {
        RenderSystem.assertOnRenderThreadOrInit();
        // The scene images belong to the main pass; only drop the texture binding like vanilla does.
        this.unbindRead();
    }

    @Override
    public void bindWrite(boolean updateViewport) {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(() -> this.iroh$bindWrite(updateViewport));
        } else {
            this.iroh$bindWrite(updateViewport);
        }
    }

    @Unique
    private void iroh$bindWrite(boolean updateViewport) {
        if (!Renderer.isRecording())
            return;

        Renderer.getInstance().getMainPass().rebindMainTarget();

        if (updateViewport)
            GlStateManager._viewport(0, 0, this.viewWidth, this.viewHeight);
    }

    @Override
    public void bindRead() {
        Renderer.getInstance().getMainPass().bindAsTexture();
    }

    @Override
    public void setClearColor(float r, float g, float b, float a) {
        super.setClearColor(r, g, b, a);
        this.iroh$clearR = r;
        this.iroh$clearG = g;
        this.iroh$clearB = b;
        this.iroh$clearA = a;
    }

    /** Vanilla semantics: bind the main target, clear colour (+ depth), leave it bound. */
    @Override
    public void clear(boolean clearError) {
        RenderSystem.assertOnRenderThreadOrInit();

        if (!Renderer.isRecording())
            return;

        this.iroh$bindWrite(true);

        GlStateManager._clearColor(this.iroh$clearR, this.iroh$clearG, this.iroh$clearB, this.iroh$clearA);
        int mask = 16384;
        if (this.useDepth) {
            GlStateManager._clearDepth(1.0);
            mask |= 256;
        }

        GlStateManager._clear(mask, clearError);
    }

    @Override
    public void blitToScreen(int width, int height, boolean disableBlend) {
        // The main target is what reaches the screen (DefaultMainPass copies it to the swapchain at frame end).
    }

    @Override
    public int getColorTextureId() {
        return Renderer.getInstance().getMainPass().getColorAttachment().id;
    }

    @Override
    public int getDepthTextureId() {
        return Renderer.getInstance().getMainPass().getDepthAttachment().id;
    }
}
