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

    @Override
    public boolean canRecordOutsideMainPass() {
        return !inRendering && Renderer.getInstance().getBoundRenderPass() == null && Renderer.recordingCommandBuffer("api4") != null;
    }

    @Override protected VkDevice device() { return Vulkan.getVkDevice(); }
    @Override protected long pipelineCache() { return net.vulkanmod.vulkan.shader.Pipeline.pipelineCacheHandle(); }
    @Override protected long sampler() { return SamplerManager.getTextureSampler((byte) 0, SamplerManager.CLAMP_BIT); }

    @Override
    protected VkCommandBuffer cmd() {
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
        // pre-frame: submitted at endFrame (StagingBuffer.flushConsumers) BEFORE the frame submit, which waits on it
        // (Renderer.submitFrame, ALL_COMMANDS); recorded between frames: at the next preInitFrame. Never after the frame.
        UploadManager.INSTANCE.recordUpload((Buffer) ((VkBuf) buffer).alloc(), offset, src.remaining(), src);
    }

    @Override public boolean frameRecording() { return Renderer.isRecordingSafe(); } // UPLOAD-SYNC
    @Override public int framesInFlight() { return pools.length; }

    @Override
    public boolean inFrameCopyPossible() {
        return !inRendering && Renderer.isRecordingSafe() && Renderer.getInstance().getBoundRenderPass() == null;
    }

    /** UPLOAD-SYNC: ring staging read by the FRAME command buffer (consumedByFrame: released with the frame, rc12). */
    @Override
    public void uploadInFrame(Object buffer, long offset, ByteBuffer src) {
        VkCommandBuffer c = cmd();
        long len = src.remaining();
        var alloc = net.vulkanmod.vulkan.memory.buffer.StagingBuffer.stageForFrame(len, 4, org.lwjgl.system.MemoryUtil.memAddress(src));
        try (MemoryStack s = stackPush()) {
            vkCmdCopyBuffer(c, alloc.bufferId, ((VkBuf) buffer).buffer(), VkBufferCopy.calloc(1, s).srcOffset(alloc.offset).dstOffset(offset).size(len));
        }
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
