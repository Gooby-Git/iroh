package dev.elemore.iroh.gpu.game;

import dev.elemore.iroh.gpu.VkGpuDevice;
import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * api 4 on Iroh's renderer (card V-M1). COMPILED, NOT RUN IN GAME YET.
 * <ul>
 *   <li>buffers: Iroh {@link Buffer}s in device memory (VMA, accounted by MemoryManager and MemoryGuard), uploads through
 *       {@link UploadManager#recordUpload} (the staging ring; the transfer submit is waited by the frame), frees by
 *       {@link Buffer#scheduleFree} (the rc12 epoch rule);</li>
 *   <li>images: {@link VulkanImage}s kept in GENERAL layout, freed by {@link VulkanImage#free};</li>
 *   <li>pipelines / framebuffers / render passes: raw handles freed through {@link MemoryManager#addEpochOp};</li>
 *   <li>recording into the frame command buffer; compute and mod passes only where no render pass is bound (the
 *       BEFORE_MAIN_PASS stage); descriptor sets from a pool per frame slot, reset when the slot's fence was waited.</li>
 * </ul>
 */
public final class IrohGpuDevice extends VkGpuDevice {
    private final long[] pools;
    private final boolean indirectCount;

    public IrohGpuDevice(int frames, boolean drawIndirectCount) {
        this.indirectCount = drawIndirectCount;
        this.pools = new long[frames];
        try (MemoryStack s = stackPush()) {
            for (int i = 0; i < frames; i++) {
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, s);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(4096);
                sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1024);
                LongBuffer lb = s.mallocLong(1);
                check(vkCreateDescriptorPool(device(), VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1024).pPoolSizes(sizes), null, lb),
                        "api4 descriptor pool");
                pools[i] = lb.get(0);
            }
        }
    }

    /** Renderer.beginFrame, after the slot's fence: that slot's descriptor sets are free again. */
    public void frameStart(int slot) {
        if (slot >= 0 && slot < pools.length) vkResetDescriptorPool(device(), pools[slot], 0);
    }

    public void destroy() {
        for (long p : pools) vkDestroyDescriptorPool(device(), p, null);
        destroyShared();
    }

    @Override public String name() { return "iroh"; }
    @Override public boolean supportsDrawIndirectCount() { return indirectCount; }

    @Override
    public long capBytes() {
        long cap = dev.elemore.iroh.memory.MemoryGuard.BUDGET.cap();
        return cap > 0 ? cap : 2L << 30;
    }

    /** INT-3 (MainPassArbiter rule 2): the shader pack's world pass yields before any api-4 recording; one volatile read when off */
    private static void closePackWorldPass() {
        if ((dev.elemore.iroh.shaderpack.runtime.Substitution.HOOK & dev.elemore.iroh.shaderpack.runtime.Substitution.WORLD) != 0)
            dev.elemore.iroh.shaderpack.runtime.SubstitutionHook.closeForForeign();
    }

    /** INT-3: the renderer as the arbiter sees it (main join) */
    private static final dev.elemore.iroh.render.MainPassArbiter.Hooks GAME = new dev.elemore.iroh.render.MainPassArbiter.Hooks() {
        public void closePackWorldPass() { IrohGpuDevice.closePackWorldPass(); }
        public boolean mainTargetBound() { return Renderer.getInstance().getMainPass().isMainTargetBound(); }
        public void resumeMain() { Renderer.getInstance().getMainPass().rebindMainTarget(); }
        public boolean passBound() { return Renderer.getInstance().getBoundRenderPass() != null; }
        public void endPass() { Renderer.getInstance().endRenderPass(); }
        public void resumePass() { resumeMain(); }
        // int-2b: PassResume binds Iroh's pipeline again and sets only the dynamic states it declares (CmdState)
        public dev.elemore.iroh.render.CmdState cmdState() { return Renderer.getInstance().cmdState(); }
        public void reapplyViewportAndScissor() { dev.elemore.iroh.glshim.GlState.reapplyViewportAndScissor(); }
    };

    @Override
    public boolean canRecordOutsideMainPass() {
        closePackWorldPass();
        return !inRendering && Renderer.getInstance().getBoundRenderPass() == null && Renderer.recordingCommandBuffer("api4") != null;
    }

    @Override protected VkDevice device() { return Vulkan.getVkDevice(); }
    @Override protected long pipelineCache() { return net.vulkanmod.vulkan.shader.Pipeline.pipelineCacheHandle(); }
    @Override
    protected long sampler(boolean linear) {
        byte flags = (byte) (SamplerManager.CLAMP_BIT | SamplerManager.USE_MIPMAPS_BIT | SamplerManager.MIPMAP_LINEAR_FILTERING_BIT
                | (linear ? SamplerManager.LINEAR_FILTERING_BIT : 0));
        return SamplerManager.getTextureSampler((byte) 15, flags);
    }

    private double tsPeriod = -1;

    @Override
    protected double timestampPeriodNs() {
        if (tsPeriod < 0) {
            try (MemoryStack s = stackPush()) {
                VkPhysicalDeviceProperties p = VkPhysicalDeviceProperties.calloc(s);
                vkGetPhysicalDeviceProperties(device().getPhysicalDevice(), p);
                tsPeriod = p.limits().timestampComputeAndGraphics() ? p.limits().timestampPeriod() : 0;
            }
        }
        return tsPeriod;
    }

    @Override public int framesInFlight() { return pools.length; }
    @Override public int frameSlot() { return Renderer.getCurrentFrame(); }

    /** Iroh's own textures by key: {@code iroh:block_atlas} (nearest, its mips), {@code iroh:lightmap} (linear). */
    @Override
    protected VkImg resolveExternal(String key) {
        net.minecraft.resources.ResourceLocation id = switch (key) {
            case "iroh:block_atlas" -> net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS;
            case "iroh:lightmap" -> dev.elemore.iroh.compat.LightmapFilter.lightmapLocation();
            default -> null;
        };
        if (id == null) return null;
        var tex = net.minecraft.client.Minecraft.getInstance().getTextureManager().getTexture(id, null);
        var gl = tex == null ? null : net.vulkanmod.gl.VkGlTexture.getTexture(tex.getId());
        VulkanImage vi = gl == null ? null : gl.getVulkanImage();
        if (vi == null) return null;
        int layout = vi.getCurrentLayout() == VK_IMAGE_LAYOUT_UNDEFINED ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : vi.getCurrentLayout();
        return new VkImg(vi.getId(), vi.getImageView(), vi.format, vi.width, vi.height, VK_IMAGE_ASPECT_COLOR_BIT, vi.mipLevels,
                key.equals("iroh:lightmap"), layout, vi);
    }

    @Override
    protected VkImg allocTexture(String name, int width, int height, int levels, boolean linear) {
        throw new UnsupportedOperationException("api 4.1: IrohTextures.create is offline-only (in game: sample Iroh's textures or a target)");
    }

    @Override
    public void uploadImage(Object image, ByteBuffer texels) {
        throw new UnsupportedOperationException("api 4.1: image uploads are offline-only");
    }

    @Override
    public void setStandIn(String key, Object texture) {
        throw new UnsupportedOperationException("stand-ins are offline-only");
    }

    @Override
    public void setMainStandIn(Object color, Object depth) {
        throw new UnsupportedOperationException("stand-ins are offline-only");
    }

    @Override
    public void copyMainDepth(Object dst) {
        MainDepth.copy(this, (VkImg) dst);
    }

    @Override public int mainColorFormat() { return net.vulkanmod.vulkan.framebuffer.SceneTarget.COLOR_FORMAT; }
    @Override public int mainDepthFormat() { return Vulkan.getDefaultDepthFormat(); }

    /**
     * Joins the bound main pass (world stages): the pipelines' render pass has the main formats, so it is compatible.
     * INT-3: through MainPassArbiter.prepareJoin (the pack world pass yields; a mod framebuffer bound = refused); the
     * size is the main target's NOW (DefaultMainPass MAIN TARGET CONTRACT: render size inside the render-scale world phase).
     */
    @Override
    public void beginMainRendering() {
        dev.elemore.iroh.render.MainPassArbiter.prepareJoin(GAME);
        int w = 1, h = 1;
        if (Renderer.getInstance().getMainPass() instanceof net.vulkanmod.vulkan.pass.DefaultMainPass dmp) {
            w = dmp.mainTarget().getWidth();
            h = dmp.mainTarget().getHeight();
        } else {
            var t = Renderer.getInstance().getMainPass().getDepthAttachment();
            if (t != null) {
                w = t.getVulkanImage().width;
                h = t.getVulkanImage().height;
            }
        }
        try (MemoryStack s = stackPush()) {
            vkCmdSetViewport(cmd(), 0, VkViewport.calloc(1, s).x(0).y(0).width(w).height(h).minDepth(0).maxDepth(1));
            VkRect2D.Buffer sc = VkRect2D.calloc(1, s);
            sc.extent().width(w).height(h);
            vkCmdSetScissor(cmd(), 0, sc);
        }
        inRendering = true;
    }

    @Override
    public void endMainRendering() {
        inRendering = false; // the main pass goes on
        Renderer.getInstance().invalidateBoundPipeline();
        // INT-3: Iroh's own viewport / scissor, not the mod's full-target ones. int-2b: depth bias / line width are
        // stale after the mod's pipeline (invalidateBoundPipeline) and set by Iroh's next bind that declares them
        dev.elemore.iroh.glshim.GlState.reapplyViewportAndScissor();
    }

    @Override
    public void runOutsideMainPass(Runnable work) {
        cmd(); // throws when no frame is recording
        // INT-3: ONE mechanism with Iroh's own out-of-pass recordings: closes the pack world pass first, ends the bound
        // pass, runs, resumes THAT pass (main target on the active set, or a mod framebuffer), drops Iroh's bound-state
        // cache and re-applies dynamic state (PassResume order, passResumeTest / interactionTest)
        VulkanImage.endPassRunResume(work);
    }

    @Override
    public boolean frameRecording() {
        return Renderer.recordingCommandBuffer("api4") != null;
    }

    @Override
    protected VkCommandBuffer cmd() {
        closePackWorldPass(); // INT-3: never record inside the pack world pass's attachments
        VkCommandBuffer c = Renderer.recordingCommandBuffer("api4");
        if (c == null) throw new IllegalStateException("api 4: no frame is recording (call from a frame stage)");
        return c;
    }

    @Override
    protected void graphicsPipelineBound() {
        Renderer.getInstance().invalidateBoundState(); // int-2b: the next Iroh draw binds again and sets its dynamic state
    }

    @Override
    protected long allocateSet(long layout) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            check(vkAllocateDescriptorSets(device(), VkDescriptorSetAllocateInfo.calloc(s).sType$Default()
                    .descriptorPool(pools[Renderer.getCurrentFrame()]).pSetLayouts(s.longs(layout)), lb), "api4 descriptor set");
            return lb.get(0);
        }
    }

    @Override
    protected VkBuf allocBuffer(long bytes, int usage) {
        Buffer b = new Buffer(usage, MemoryTypes.GPU_MEM);
        b.createBuffer(bytes);
        return new VkBuf(b.getId(), bytes, b);
    }

    @Override
    public void destroyBuffer(Object buffer) {
        ((Buffer) ((VkBuf) buffer).alloc()).scheduleFree();
    }

    @Override
    public void upload(Object buffer, long offset, ByteBuffer src) {
        UploadManager.INSTANCE.recordUpload((Buffer) ((VkBuf) buffer).alloc(), offset, src.remaining(), src);
    }

    @Override
    protected VkImg allocImage(String name, int width, int height, int format, int usage, int aspect) {
        VulkanImage img = new VulkanImage.Builder(width, height).setName("api4 " + name).setOwner("api4 " + name)
                .setFormat(format).setUsage(usage).setClamp(true).createVulkanImage();
        img.setCurrentLayout(VK_IMAGE_LAYOUT_GENERAL); // createImage transitions it right after
        return new VkImg(img.getId(), img.getImageView(), format, width, height, aspect, img);
    }

    @Override
    public void destroyImage(Object image) {
        VkImg img = (VkImg) image;
        forgetImage(img);
        ((VulkanImage) img.alloc).free();
    }

    @Override
    protected void deferFree(Runnable r) {
        MemoryManager.getInstance().addEpochOp(r);
    }

    @Override
    public ByteBuffer readback(Object image) {
        throw new UnsupportedOperationException("api 4 readback is offline-only (in game: a later async readback)");
    }

    @Override
    public void flush() {
        // the frame submits everything recorded
    }

    /** The main depth image (for MainDepth). */
    static VulkanImage mainDepth() {
        var t = Renderer.getInstance().getMainPass().getDepthAttachment();
        return t == null ? null : t.getVulkanImage();
    }
}
