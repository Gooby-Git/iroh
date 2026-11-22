package dev.elemore.iroh.shaderpack.runtime;

import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/**
 * P3-11: Iroh's side of {@link WorldPass} in game: the pass switch (same order and state invalidation as
 * VulkanImage.endPassRunResume / PassResume) and the per-draw inputs (VRenderSystem matrices, the texture bound to
 * sampler 0, DrawIds). Render thread only. Nothing here has run in the game yet: expected, unverified.
 */
final class WorldPassGame implements WorldPass.Host, WorldPass.DrawSource {

    private final IrohVkBackend gpu;
    private RenderPass pass;
    private Framebuffer fb;
    private VkGlFramebuffer glFb;

    WorldPassGame(IrohVkBackend gpu) {
        this.gpu = gpu;
    }

    // ---- Host
    @Override
    public boolean irohPassBound() {
        return Renderer.getInstance().getBoundRenderPass() != null;
    }

    @Override
    public void endIrohPass() {
        Renderer r = Renderer.getInstance();
        pass = r.getBoundRenderPass();
        fb = r.getBoundFramebuffer();
        glFb = VkGlFramebuffer.getBoundFramebuffer();
        r.endRenderPass();
        // int-2b: pack pipelines (depth bias / line width static) are bound until leave(): a GL polygon offset or line
        // width meanwhile is deferred to Iroh's next bind instead of landing between a pack bind and its draw
        r.invalidateBoundState();
    }

    @Override
    public void resumeIrohPass() {
        Renderer r = Renderer.getInstance();
        if (glFb == null && fb instanceof net.vulkanmod.vulkan.framebuffer.SceneTarget)
            r.getMainPass().rebindMainTarget();          // LOAD pass (the CLEAR pass is frame-start only)
        else if (fb != null) {
            r.beginRendering(pass, fb);
            VkGlFramebuffer.restoreBinding(glFb);
        }
        pass = null;
        fb = null;
        glFb = null;
    }

    @Override
    public void invalidateBoundState() {
        Renderer.getInstance().invalidateBoundState();
    }

    @Override
    public void reapplyDynamicState() {
        // int-2b (td-0cc003): viewport/scissor only (dynamic in every pipeline). Depth bias / line width are NOT set
        // eagerly: invalidateBoundState marked them stale and Iroh's next bind sets the ones its pipeline declares
        // (setting line width here while a GUI pipeline is bound was the title-screen VUID-08608 of int-2).
        if (Renderer.getInstance().getBoundRenderPass() != null)
            dev.elemore.iroh.glshim.GlState.reapplyViewportAndScissor();
    }

    // ---- DrawSource
    @Override
    public void modelView(double[] out) {
        read(VRenderSystem.modelViewMatrix.buffer, out);
    }

    @Override
    public void projection(double[] out) {
        read(VRenderSystem.projectionMatrix.buffer, out);
    }

    private static void read(ByteBuffer b, double[] out) {
        ByteBuffer f = b.duplicate().order(ByteOrder.nativeOrder());
        for (int i = 0; i < 16; i++)
            out[i] = f.getFloat(i * 4);
    }

    @Override
    public Object texture() {
        return VTextureSelector.getBoundTexture(0);
    }

    @Override
    public long textureKey(Object tex) {
        return ((VulkanImage) tex).getImageView();
    }

    @Override
    public int importTexture(Object tex) {
        return gpu.importImage(tex, "draw texture");
    }

    @Override
    public void ids(Map<String, double[]> out) {
        out.get("entityId")[0] = DrawIds.entityId;
        out.get("blockEntityId")[0] = DrawIds.blockEntityId;
        out.get("currentRenderedItemId")[0] = DrawIds.itemId;
        double[] c = out.get("entityColor");
        for (int i = 0; i < 4; i++)
            c[i] = DrawIds.entityColor[i];
    }
}
