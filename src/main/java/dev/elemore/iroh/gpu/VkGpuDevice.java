package dev.elemore.iroh.gpu;

import dev.elemore.iroh.perf.PipelineStats;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

/**
 * The Vulkan recording shared by the headless and the in-game device: pipelines (one descriptor set 0 of storage
 * buffers then combined image samplers, one push-constant range), classic render passes per format pair (compatible
 * with every load op; no dynamic rendering needed), images kept in GENERAL layout, global barriers. Subclasses provide
 * the device, the command buffer, memory and the deferred-free rule.
 */
public abstract class VkGpuDevice implements GpuDevice {

    protected abstract VkDevice device();

    /** The command buffer to record into now (outside a render pass unless {@link #inRendering}). */
    protected abstract VkCommandBuffer cmd();

    protected abstract long pipelineCache();

    /** A descriptor set for {@code layout} valid until the recorded work finished. */
    protected abstract long allocateSet(long layout);

    protected abstract VkBuf allocBuffer(long bytes, int usage);

    protected abstract VkImg allocImage(String name, int width, int height, int format, int usage, int aspect);

    /** Run {@code r} once the GPU no longer uses what it frees. */
    protected abstract void deferFree(Runnable r);

    /**
     * int-2b (td-0cc003): a graphics pipeline was bound into {@link #cmd()}. On Iroh's frame command buffer this voids
     * the renderer's bound pipeline and its depth bias / line width (CmdState.foreign); nothing on a private one.
     */
    protected void graphicsPipelineBound() {}

    protected abstract long sampler();

    /** A buffer: Vulkan handle, size, the subclass' allocation. */
    public record VkBuf(long buffer, long size, Object alloc) {}

    /** An image in GENERAL layout with one view. */
    public static final class VkImg {
        public final long image, view;
        public final int format, width, height, aspect;
        public final Object alloc;

        public VkImg(long image, long view, int format, int width, int height, int aspect, Object alloc) {
            this.image = image;
            this.view = view;
            this.format = format;
            this.width = width;
            this.height = height;
            this.aspect = aspect;
            this.alloc = alloc;
        }
    }

    protected static final class Pipe {
        final long pipeline, layout, setLayout;
        final int storage, textures, push;
        final boolean compute;
        final int colorFormat, depthFormat;

        Pipe(long pipeline, long layout, long setLayout, int storage, int textures, int push, boolean compute, int colorFormat, int depthFormat) {
            this.pipeline = pipeline;
            this.layout = layout;
            this.setLayout = setLayout;
            this.storage = storage;
            this.textures = textures;
            this.push = push;
            this.compute = compute;
            this.colorFormat = colorFormat;
            this.depthFormat = depthFormat;
        }
    }

    protected boolean inRendering;
    private final Map<Long, Long> renderPasses = new HashMap<>();
    private final Map<String, Long> framebuffers = new HashMap<>();

    public static void check(int r, String what) {
        if (r != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + r);
    }

    // ------------------------------------------------------------------------------------------------ buffers

    @Override
    public Object createBuffer(long bytes, int usage) {
        int vk = VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        if ((usage & GpuCore.STORAGE) != 0) vk |= VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        if ((usage & GpuCore.INDIRECT) != 0) vk |= VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        if ((usage & GpuCore.INDEX) != 0) vk |= VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
        if ((usage & GpuCore.VERTEX) != 0) vk |= VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        return allocBuffer(bytes, vk);
    }

    @Override
    public long bufferSize(Object buffer) {
        return ((VkBuf) buffer).size();
    }

    // ------------------------------------------------------------------------------------------------ pipelines

    private long setLayout(MemoryStack s, int storage, int textures, int stages) {
        VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(Math.max(1, storage + textures), s);
        for (int i = 0; i < storage; i++)
            b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(stages);
        for (int i = 0; i < textures; i++)
            b.get(storage + i).binding(storage + i).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(stages);
        b.limit(storage + textures);
        LongBuffer lb = s.mallocLong(1);
        check(vkCreateDescriptorSetLayout(device(), VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b), null, lb), "set layout");
        return lb.get(0);
    }

    private long pipelineLayout(MemoryStack s, long setLayout, int push, int stages) {
        VkPipelineLayoutCreateInfo ci = VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(setLayout));
        if (push > 0)
            ci.pPushConstantRanges(VkPushConstantRange.calloc(1, s).stageFlags(stages).offset(0).size(push));
        LongBuffer lb = s.mallocLong(1);
        check(vkCreatePipelineLayout(device(), ci, null, lb), "pipeline layout");
        return lb.get(0);
    }

    private long module(MemoryStack s, ByteBuffer spirv) {
        LongBuffer lb = s.mallocLong(1);
        check(vkCreateShaderModule(device(), VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(spirv), null, lb), "shader module");
        return lb.get(0);
    }

    @Override
    public Object createComputePipeline(String name, ByteBuffer spirv, int storageBuffers, int pushBytes) {
        try (MemoryStack s = stackPush()) {
            long sl = setLayout(s, storageBuffers, 0, VK_SHADER_STAGE_COMPUTE_BIT);
            long pl = pipelineLayout(s, sl, pushBytes, VK_SHADER_STAGE_COMPUTE_BIT);
            long mod = module(s, spirv);
            VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s).sType$Default().layout(pl);
            ci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(mod).pName(s.UTF8("main"));
            LongBuffer lb = s.mallocLong(1);
            long pipeT0 = System.nanoTime(); // P2-11
            check(vkCreateComputePipelines(device(), pipelineCache(), ci, null, lb), "compute pipeline " + name);
            PipelineStats.created(PipelineStats.Kind.COMPUTE, System.nanoTime() - pipeT0);
            vkDestroyShaderModule(device(), mod, null);
            return new Pipe(lb.get(0), pl, sl, storageBuffers, 0, pushBytes, true, 0, 0);
        }
    }

    /** A render pass for a (colour, depth) format pair; formats 0 = absent. LOAD or CLEAR per call, both compatible. */
    protected long renderPass(int colorFormat, int depthFormat, boolean clear) {
        long key = ((long) colorFormat << 33) | ((long) depthFormat << 1) | (clear ? 1 : 0);
        Long rp = renderPasses.get(key);
        if (rp != null) return rp;
        try (MemoryStack s = stackPush()) {
            int n = (colorFormat != 0 ? 1 : 0) + (depthFormat != 0 ? 1 : 0);
            VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(n, s);
            int i = 0;
            VkAttachmentReference.Buffer colorRef = null;
            VkAttachmentReference depthRef = null;
            if (colorFormat != 0) {
                att.get(i).format(colorFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_GENERAL).finalLayout(VK_IMAGE_LAYOUT_GENERAL);
                colorRef = VkAttachmentReference.calloc(1, s).attachment(i).layout(VK_IMAGE_LAYOUT_GENERAL);
                i++;
            }
            if (depthFormat != 0) {
                att.get(i).format(depthFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_GENERAL).finalLayout(VK_IMAGE_LAYOUT_GENERAL);
                depthRef = VkAttachmentReference.calloc(s).attachment(i).layout(VK_IMAGE_LAYOUT_GENERAL);
            }
            VkSubpassDescription.Buffer sub = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(colorRef == null ? 0 : 1).pColorAttachments(colorRef).pDepthStencilAttachment(depthRef);
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateRenderPass(device(), VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(att).pSubpasses(sub), null, lb),
                    "render pass");
            renderPasses.put(key, lb.get(0));
            return lb.get(0);
        }
    }

    @Override
    public Object createDrawPipeline(String name, ByteBuffer vs, ByteBuffer fs, int storageBuffers, int sampledTextures, int pushBytes,
                                     int colorFormat, int depthFormat, int depthCompare, boolean depthWrite, boolean blend) {
        int stages = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
        try (MemoryStack s = stackPush()) {
            long sl = setLayout(s, storageBuffers, sampledTextures, stages);
            long pl = pipelineLayout(s, sl, pushBytes, stages);
            long vm = module(s, vs), fm = module(s, fs);
            VkPipelineShaderStageCreateInfo.Buffer st = VkPipelineShaderStageCreateInfo.calloc(2, s);
            st.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vm).pName(s.UTF8("main"));
            st.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fm).pName(s.UTF8("main"));
            VkPipelineColorBlendAttachmentState.Buffer cb = VkPipelineColorBlendAttachmentState.calloc(1, s).colorWriteMask(0xF).blendEnable(blend);
            if (blend)
                cb.get(0).srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK_BLEND_OP_ADD).srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .alphaBlendOp(VK_BLEND_OP_ADD);
            if (colorFormat == 0) cb.limit(0);
            VkGraphicsPipelineCreateInfo.Buffer ci = VkGraphicsPipelineCreateInfo.calloc(1, s).sType$Default()
                    .pStages(st)
                    .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(s).sType$Default()) // vertex pulling only
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(s).sType$Default().viewportCount(1).scissorCount(1))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(s).sType$Default().polygonMode(VK_POLYGON_MODE_FILL)
                            .cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(s).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(s).sType$Default().depthTestEnable(depthFormat != 0)
                            .depthWriteEnable(depthWrite && depthFormat != 0).depthCompareOp(depthCompare))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(s).sType$Default().pAttachments(cb))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(s).sType$Default().pDynamicStates(s.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                    .layout(pl).renderPass(renderPass(colorFormat, depthFormat, false)).subpass(0);
            LongBuffer lb = s.mallocLong(1);
            long pipeT0 = System.nanoTime(); // P2-11
            check(vkCreateGraphicsPipelines(device(), pipelineCache(), ci, null, lb), "draw pipeline " + name);
            PipelineStats.created(PipelineStats.Kind.GRAPHICS, System.nanoTime() - pipeT0);
            vkDestroyShaderModule(device(), vm, null);
            vkDestroyShaderModule(device(), fm, null);
            return new Pipe(lb.get(0), pl, sl, storageBuffers, sampledTextures, pushBytes, false, colorFormat, depthFormat);
        }
    }

    @Override
    public void destroyPipeline(Object pipeline) {
        Pipe p = (Pipe) pipeline;
        VkDevice d = device();
        deferFree(() -> {
            vkDestroyPipeline(d, p.pipeline, null);
            vkDestroyPipelineLayout(d, p.layout, null);
            vkDestroyDescriptorSetLayout(d, p.setLayout, null);
        });
    }

    // ------------------------------------------------------------------------------------------------ images

    @Override
    public Object createImage(String name, int width, int height, int format, boolean depth) {
        int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                | (depth ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_STORAGE_BIT);
        VkImg img = allocImage(name, width, height, format, usage, depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT);
        try (MemoryStack s = stackPush()) { // UNDEFINED -> GENERAL once
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT).oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(img.image);
            b.subresourceRange().aspectMask(img.aspect).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(cmd(), VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, b);
        }
        return img;
    }

    // ------------------------------------------------------------------------------------------------ recording

    @Override
    public void barrier(int srcStages, int srcAccess, int dstStages, int dstAccess) {
        try (MemoryStack s = stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(cmd(), srcStages, dstStages, 0, b, null, null);
        }
    }

    private void bind(MemoryStack s, Pipe p, Object[] buffers, Object[] textures, ByteBuffer push, int bindPoint, int pushStages) {
        VkCommandBuffer c = cmd();
        vkCmdBindPipeline(c, bindPoint, p.pipeline);
        if (bindPoint == VK_PIPELINE_BIND_POINT_GRAPHICS)
            graphicsPipelineBound();
        int n = p.storage + p.textures;
        if (n > 0) {
            long set = allocateSet(p.setLayout);
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(n, s);
            for (int i = 0; i < p.storage; i++) {
                VkBuf b = (VkBuf) buffers[i];
                w.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(VkDescriptorBufferInfo.calloc(1, s).buffer(b.buffer()).offset(0).range(VK_WHOLE_SIZE));
            }
            for (int i = 0; i < p.textures; i++) {
                long view = textureView(textures[i]);
                w.get(p.storage + i).sType$Default().dstSet(set).dstBinding(p.storage + i).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler()).imageView(view).imageLayout(textureLayout(textures[i])));
            }
            vkUpdateDescriptorSets(device(), w, null);
            vkCmdBindDescriptorSets(c, bindPoint, p.layout, 0, s.longs(set), null);
        }
        if (p.push > 0 && push != null)
            vkCmdPushConstants(c, p.layout, pushStages, 0, push);
    }

    /** View of a sampled texture: our images; the game device adds Iroh's own textures. */
    protected long textureView(Object texture) {
        return ((VkImg) texture).view;
    }

    protected int textureLayout(Object texture) {
        return VK_IMAGE_LAYOUT_GENERAL;
    }

    @Override
    public void dispatch(Object pipeline, Object[] buffers, ByteBuffer push, int x, int y, int z) {
        try (MemoryStack s = stackPush()) {
            bind(s, (Pipe) pipeline, buffers, null, push, VK_PIPELINE_BIND_POINT_COMPUTE, VK_SHADER_STAGE_COMPUTE_BIT);
            vkCmdDispatch(cmd(), x, y, z);
        }
    }

    @Override
    public void dispatchIndirect(Object pipeline, Object[] buffers, ByteBuffer push, Object indirect, long offset) {
        try (MemoryStack s = stackPush()) {
            bind(s, (Pipe) pipeline, buffers, null, push, VK_PIPELINE_BIND_POINT_COMPUTE, VK_SHADER_STAGE_COMPUTE_BIT);
            vkCmdDispatchIndirect(cmd(), ((VkBuf) indirect).buffer(), offset);
        }
    }

    private int rpColor, rpDepth, rpW, rpH;

    @Override
    public void beginRendering(Object color, Object depth, float[] clearRgba, float clearDepth, boolean clear) {
        VkImg c = (VkImg) color, d = (VkImg) depth;
        int cf = c == null ? 0 : c.format, df = d == null ? 0 : d.format;
        int w = c != null ? c.width : d.width, h = c != null ? c.height : d.height;
        long rp = renderPass(cf, df, clear);
        String key = (c == null ? 0 : c.view) + ":" + (d == null ? 0 : d.view) + ":" + rp;
        Long fb = framebuffers.get(key);
        try (MemoryStack s = stackPush()) {
            if (fb == null) {
                LongBuffer att = s.mallocLong((c != null ? 1 : 0) + (d != null ? 1 : 0));
                if (c != null) att.put(c.view);
                if (d != null) att.put(d.view);
                att.flip();
                LongBuffer lb = s.mallocLong(1);
                check(vkCreateFramebuffer(device(), VkFramebufferCreateInfo.calloc(s).sType$Default().renderPass(rp).pAttachments(att)
                        .width(w).height(h).layers(1), null, lb), "framebuffer");
                fb = lb.get(0);
                framebuffers.put(key, fb);
            }
            VkClearValue.Buffer cv = VkClearValue.calloc(2, s);
            int i = 0;
            if (c != null) cv.get(i++).color().float32(0, clearRgba[0]).float32(1, clearRgba[1]).float32(2, clearRgba[2]).float32(3, clearRgba[3]);
            if (d != null) cv.get(i).depthStencil().depth(clearDepth).stencil(0);
            VkRenderPassBeginInfo bi = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(rp).framebuffer(fb).pClearValues(cv);
            bi.renderArea().extent().width(w).height(h);
            vkCmdBeginRenderPass(cmd(), bi, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdSetViewport(cmd(), 0, VkViewport.calloc(1, s).x(0).y(0).width(w).height(h).minDepth(0).maxDepth(1));
            VkRect2D.Buffer sc = VkRect2D.calloc(1, s);
            sc.extent().width(w).height(h);
            vkCmdSetScissor(cmd(), 0, sc);
        }
        inRendering = true;
        rpColor = cf;
        rpDepth = df;
        rpW = w;
        rpH = h;
    }

    @Override
    public void endRendering() {
        vkCmdEndRenderPass(cmd());
        inRendering = false;
    }

    /** Drops cached framebuffers that name an image about to be destroyed. */
    protected void forgetImage(VkImg img) {
        VkDevice d = device();
        framebuffers.entrySet().removeIf(e -> {
            String[] k = e.getKey().split(":");
            if (Long.parseLong(k[0]) == img.view || Long.parseLong(k[1]) == img.view) {
                long fb = e.getValue();
                deferFree(() -> vkDestroyFramebuffer(d, fb, null));
                return true;
            }
            return false;
        });
    }

    @Override
    public void drawIndexedIndirectCount(Object pipeline, Object[] buffers, Object[] textures, ByteBuffer push, Object index, int indexType,
                                         Object commands, long commandOffset, Object count, long countOffset, int maxDraws, int stride) {
        try (MemoryStack s = stackPush()) {
            bind(s, (Pipe) pipeline, buffers, textures, push, VK_PIPELINE_BIND_POINT_GRAPHICS, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            vkCmdBindIndexBuffer(cmd(), ((VkBuf) index).buffer(), 0, indexType);
            vkCmdDrawIndexedIndirectCount(cmd(), ((VkBuf) commands).buffer(), commandOffset, ((VkBuf) count).buffer(), countOffset, maxDraws, stride);
        }
    }

    @Override
    public void drawIndexedIndirect(Object pipeline, Object[] buffers, Object[] textures, ByteBuffer push, Object index, int indexType,
                                    Object commands, long commandOffset, int drawCount, int stride) {
        try (MemoryStack s = stackPush()) {
            bind(s, (Pipe) pipeline, buffers, textures, push, VK_PIPELINE_BIND_POINT_GRAPHICS, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            vkCmdBindIndexBuffer(cmd(), ((VkBuf) index).buffer(), 0, indexType);
            vkCmdDrawIndexedIndirect(cmd(), ((VkBuf) commands).buffer(), commandOffset, drawCount, stride);
        }
    }

    /** Destroys render passes and framebuffers (device teardown). */
    protected void destroyShared() {
        for (long fb : framebuffers.values()) vkDestroyFramebuffer(device(), fb, null);
        framebuffers.clear();
        for (long rp : renderPasses.values()) vkDestroyRenderPass(device(), rp, null);
        renderPasses.clear();
    }
}
