package dev.elemore.iroh.shaderpack.runtime;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The Vulkan half of a {@link GpuBackend} shared by the offline replay device ({@link HeadlessVkBackend}, lavapipe) and
 * the in-game backend ({@code IrohVkBackend}, Iroh's own device): render passes, framebuffers, pipelines, descriptors,
 * barriers, clears, mips, copies, blits, draws and dispatches. Vulkan 1.0 render passes only (Iroh's device does not
 * enable dynamic rendering), so the replay runs exactly the code the game runs. Subclasses own the device, memory,
 * the recording command buffer, per-frame descriptor pools and the (deferred) release of objects.
 * <p>
 * Every image the pack owns lives in GENERAL layout (attachment, sampling, storage and transfer all allowed); imported
 * images (the raster path's scene colour / depth) are transitioned to a transfer layout around each blit and returned
 * to the layout they had. {@link #barrier} is one full memory barrier.
 */
public abstract class VkPackBackend implements GpuBackend {

    protected static final int QUAD_STRIDE = 80; // pos vec4, uv vec4, white vec4, zero vec4, int zero ivec4

    /** an image: owned (alloc != null) or imported (alloc == null, layout callbacks) */
    protected static final class Img {
        final ImageDesc d;
        final long image, viewAll, viewMip0;
        final int format, aspect;
        final Object alloc;
        final ImportedLayout imported;
        boolean freed;

        Img(ImageDesc d, long image, long viewAll, long viewMip0, int format, int aspect, Object alloc, ImportedLayout imported) {
            this.d = d;
            this.image = image;
            this.viewAll = viewAll;
            this.viewMip0 = viewMip0;
            this.format = format;
            this.aspect = aspect;
            this.alloc = alloc;
            this.imported = imported;
        }
    }

    /** current layout of an imported image, read and written around transfers */
    public interface ImportedLayout {
        int get();

        void set(int layout);
    }

    protected record HostBuf(long buffer, Object alloc, long size, ByteBuffer mapped) {}

    private record Pipe(String id, long pipeline, long layout, long set0, long set1, boolean compute) {}

    protected final List<Img> imgs = new ArrayList<>();
    private final List<HostBuf> bufs = new ArrayList<>();
    private final List<Pipe> pipes = new ArrayList<>();
    private final Map<String, Long> samplers = new HashMap<>();
    private final Map<String, Long> renderPasses = new HashMap<>();
    private final Map<String, long[]> framebuffers = new HashMap<>(); // key -> {framebuffer, image handles...}
    private HostBuf quad;
    public final List<String> log = new ArrayList<>();

    // ------------------------------------------------------------------------------------------------ subclass

    protected abstract VkDevice device();

    protected abstract VkPhysicalDevice phys();

    protected abstract long pipelineCache();

    /** the command buffer being recorded for this frame */
    protected abstract VkCommandBuffer cmd();

    /** allocate a device image (memory accounted and gated by the subclass); returns {image, allocation object} */
    protected abstract Object[] allocImage(ImageDesc d, int format, int usage, boolean three);

    /** release an owned image + its views; deferred until no in-flight frame uses it (in game) */
    protected abstract void releaseImage(long image, Object alloc, long[] views);

    /** release framebuffers / other handles after in-flight frames */
    protected abstract void releaseLater(Runnable r);

    protected abstract HostBuf hostBuffer(long size, int usage);

    protected abstract void freeHostBuffer(HostBuf b);

    /** free after the current frame has executed */
    protected abstract void freeHostBufferAfterFrame(HostBuf b);

    protected abstract long descriptorPool();

    protected void initQuad() {
        quad = hostBuffer(4L * QUAD_STRIDE, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        float[][] v = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
        for (int i = 0; i < 4; i++) {
            ByteBuffer m = quad.mapped();
            int o = i * QUAD_STRIDE;
            m.putFloat(o, v[i][0]).putFloat(o + 4, v[i][1]).putFloat(o + 8, 0).putFloat(o + 12, 1);
            m.putFloat(o + 16, v[i][0]).putFloat(o + 20, v[i][1]).putFloat(o + 24, 0).putFloat(o + 28, 1);
            m.putFloat(o + 32, 1).putFloat(o + 36, 1).putFloat(o + 40, 1).putFloat(o + 44, 1);
            m.putFloat(o + 60, 1);
            for (int k = 64; k < 80; k++)
                m.put(o + k, (byte) 0);
        }
    }

    static void check(int r, String what) {
        if (r != VK_SUCCESS)
            throw new IllegalStateException(what + " failed: VkResult " + r);
    }

    public static int vkFormat(String name) {
        try {
            return VK10.class.getField("VK_FORMAT_" + name).getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("unknown VkFormat " + name);
        }
    }

    // ------------------------------------------------------------------------------------------------ images

    @Override
    public int createImage(ImageDesc d) {
        int fmt = vkFormat(d.vkFormat());
        return createImage(d, fmt);
    }

    protected int createImage(ImageDesc d, int fmt) {
        boolean depth = d.vkFormat().startsWith("D");
        int aspect = depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
        int usage = 0, need = 0;
        for (String u : d.usage())
            switch (u) {
                case "COLOR_ATTACHMENT" -> {
                    if (!depth) {
                        usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
                        need |= VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
                    }
                }
                case "SAMPLED" -> {
                    usage |= VK_IMAGE_USAGE_SAMPLED_BIT;
                    need |= VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
                }
                case "STORAGE" -> {
                    usage |= VK_IMAGE_USAGE_STORAGE_BIT;
                    need |= VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
                }
                case "TRANSFER_SRC" -> usage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
                case "TRANSFER_DST" -> usage |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
                case "DEPTH", "DEPTH_STENCIL_ATTACHMENT" -> usage |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
                default -> {
                }
            }
        try (MemoryStack s = stackPush()) {
            VkFormatProperties fp = VkFormatProperties.calloc(s);
            vkGetPhysicalDeviceFormatProperties(phys(), fmt, fp);
            if ((fp.optimalTilingFeatures() & need) != need)
                throw new IllegalStateException(d.name() + ": " + d.vkFormat() + " lacks format features 0x"
                        + Integer.toHexString(need & ~fp.optimalTilingFeatures()) + " (FormatCaps runtime check)");
        }
        boolean three = d.depth() > 1;
        Object[] a = allocImage(d, fmt, usage, three);
        long image = (Long) a[0];
        long viewAll = a.length > 2 && a[2] != null ? (Long) a[2] : view(image, three, fmt, aspect, 0, d.mips());
        long viewMip0 = d.mips() == 1 ? viewAll : (a.length > 3 && a[3] != null ? (Long) a[3] : view(image, three, fmt, aspect, 0, 1));
        imgs.add(new Img(d, image, viewAll, viewMip0, fmt, aspect, a[1], null));
        int h = imgs.size() - 1;
        // GENERAL for its whole life, zeroed (GL drivers hand zeroed memory to packs)
        layout(cmd(), image, barrierAspect(fmt, aspect), d.mips(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        clear(h, new float[]{0, 0, 0, 0});
        return h;
    }

    /** register an image this backend does not own */
    protected int importRaw(ImageDesc d, long image, long viewAll, int format, ImportedLayout layout) {
        int aspect = d.vkFormat().startsWith("D") ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
        imgs.add(new Img(d, image, viewAll, viewAll, format, aspect, null, layout));
        return imgs.size() - 1;
    }

    protected long view(long image, boolean three, int fmt, int aspect, int base, int count) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(s).sType$Default().image(image)
                    .viewType(three ? VK_IMAGE_VIEW_TYPE_3D : VK_IMAGE_VIEW_TYPE_2D).format(fmt);
            vi.subresourceRange().aspectMask(aspect).baseMipLevel(base).levelCount(count).baseArrayLayer(0).layerCount(1);
            check(vkCreateImageView(device(), vi, null, lb), "vkCreateImageView");
            return lb.get(0);
        }
    }

    /** layout transitions of a depth/stencil image must name both aspects (copies and sampled views use depth only) */
    protected static int barrierAspect(int format, int aspect) {
        return switch (format) {
            case VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D16_UNORM_S8_UINT ->
                    VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
            default -> aspect;
        };
    }

    protected static void layout(VkCommandBuffer c, long image, int aspect, int mips, int from, int to) {
        try (MemoryStack s = stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s).sType$Default().oldLayout(from).newLayout(to)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image)
                    .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            b.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(mips).baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, b);
        }
    }

    @Override
    public ImageDesc desc(int image) {
        return imgs.get(image).d;
    }

    protected Img img(int h) {
        Img im = imgs.get(h);
        if (im.freed)
            throw new IllegalStateException("use after free: " + im.d.name());
        return im;
    }

    @Override
    public void freeImage(int image) {
        Img im = imgs.get(image);
        if (im.freed)
            return;
        im.freed = true;
        // framebuffers that reference it go first
        for (Iterator<Map.Entry<String, long[]>> it = framebuffers.entrySet().iterator(); it.hasNext(); ) {
            long[] fb = it.next().getValue();
            for (int k = 1; k < fb.length; k++)
                if (fb[k] == image) {
                    long handle = fb[0];
                    releaseLater(() -> vkDestroyFramebuffer(device(), handle, null));
                    it.remove();
                    break;
                }
        }
        if (im.alloc != null) {
            long[] views = im.viewMip0 != im.viewAll ? new long[]{im.viewAll, im.viewMip0} : new long[]{im.viewAll};
            releaseImage(im.image, im.alloc, views);
        }
    }

    @Override
    public void freePipeline(int pipeline) {
        Pipe p = pipes.get(pipeline);
        if (p == null)
            return;
        pipes.set(pipeline, null);
        releaseLater(() -> {
            vkDestroyPipeline(device(), p.pipeline(), null);
            vkDestroyPipelineLayout(device(), p.layout(), null);
            vkDestroyDescriptorSetLayout(device(), p.set0(), null);
            vkDestroyDescriptorSetLayout(device(), p.set1(), null);
        });
    }

    public int liveImages() {
        int n = 0;
        for (Img im : imgs)
            if (!im.freed && im.alloc != null)
                n++;
        return n;
    }

    @Override
    public void upload(int image, float[] rgba) {
        Img im = img(image);
        ByteBuffer data = TexelCodec.encode(im.d.vkFormat(), rgba);
        HostBuf st = hostBuffer(Math.max(16, data.remaining()), VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        st.mapped().put(0, data, 0, data.remaining());
        try (MemoryStack s = stackPush()) {
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.imageSubresource().aspectMask(im.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.imageExtent().width(im.d.width()).height(im.d.height()).depth(im.d.depth());
            vkCmdCopyBufferToImage(cmd(), st.buffer(), im.image, VK_IMAGE_LAYOUT_GENERAL, r);
        }
        freeHostBufferAfterFrame(st);
    }

    @Override
    public int createBuffer(String name, long bytes) {
        bufs.add(hostBuffer(Math.max(16, bytes), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT));
        return bufs.size() - 1;
    }

    // ------------------------------------------------------------------------------------------------ pipelines

    private long module(byte[] spirv) {
        ByteBuffer code = MemoryUtil.memAlloc(spirv.length).put(spirv).flip();
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateShaderModule(device(), VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(code), null, lb), "shader module");
            return lb.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private long setLayout(List<Slot> slots, int set) {
        try (MemoryStack s = stackPush()) {
            List<Slot> mine = new ArrayList<>();
            for (Slot sl : slots)
                if (sl.set() == set)
                    mine.add(sl);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(mine.size(), s);
            for (int i = 0; i < mine.size(); i++) {
                Slot sl = mine.get(i);
                b.get(i).binding(sl.binding()).descriptorType(descType(sl.kind())).descriptorCount(1).stageFlags(VK_SHADER_STAGE_ALL);
            }
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device(), VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b), null, lb), "set layout");
            return lb.get(0);
        }
    }

    static int descType(String kind) {
        return switch (kind) {
            case "UNIFORM_BUFFER" -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case "STORAGE_IMAGE" -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            case "STORAGE_BUFFER" -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            default -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        };
    }

    private static int blendFactor(String f) {
        return switch (f) {
            case "ZERO" -> VK_BLEND_FACTOR_ZERO;
            case "ONE" -> VK_BLEND_FACTOR_ONE;
            case "SRC_COLOR" -> VK_BLEND_FACTOR_SRC_COLOR;
            case "ONE_MINUS_SRC_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case "DST_COLOR" -> VK_BLEND_FACTOR_DST_COLOR;
            case "ONE_MINUS_DST_COLOR" -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case "SRC_ALPHA" -> VK_BLEND_FACTOR_SRC_ALPHA;
            case "ONE_MINUS_SRC_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case "DST_ALPHA" -> VK_BLEND_FACTOR_DST_ALPHA;
            case "ONE_MINUS_DST_ALPHA" -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case "SRC_ALPHA_SATURATE" -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            default -> VK_BLEND_FACTOR_ONE;
        };
    }

    /** a render pass for a list of colour formats: LOAD / STORE, GENERAL in and out (compatible for every draw with these formats) */
    private long renderPass(int[] formats) {
        StringBuilder key = new StringBuilder();
        for (int f : formats)
            key.append(f).append(',');
        return renderPasses.computeIfAbsent(key.toString(), k -> {
            try (MemoryStack s = stackPush()) {
                VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(formats.length, s);
                VkAttachmentReference.Buffer refs = VkAttachmentReference.calloc(formats.length, s);
                for (int i = 0; i < formats.length; i++) {
                    att.get(i).format(formats[i]).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                            .storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                            .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).initialLayout(VK_IMAGE_LAYOUT_GENERAL).finalLayout(VK_IMAGE_LAYOUT_GENERAL);
                    refs.get(i).attachment(i).layout(VK_IMAGE_LAYOUT_GENERAL);
                }
                VkSubpassDescription.Buffer sub = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                        .colorAttachmentCount(formats.length).pColorAttachments(refs);
                LongBuffer lb = s.mallocLong(1);
                check(vkCreateRenderPass(device(), VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(att).pSubpasses(sub), null, lb),
                        "render pass");
                return lb.get(0);
            }
        });
    }

    @Override
    public int createPipeline(PipelineDesc d) {
        try (MemoryStack s = stackPush()) {
            long l0 = setLayout(d.slots(), 0), l1 = setLayout(d.slots(), 1);
            LongBuffer lb = s.mallocLong(1);
            check(vkCreatePipelineLayout(device(), VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(l0, l1)), null, lb),
                    "pipeline layout " + d.id());
            long layout = lb.get(0);
            ByteBuffer main = s.UTF8("main");
            if (d.computeSpirv() != null) {
                long m = module(d.computeSpirv());
                VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s).sType$Default().layout(layout);
                ci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(m).pName(main);
                check(vkCreateComputePipelines(device(), pipelineCache(), ci, null, lb), "compute pipeline " + d.id());
                vkDestroyShaderModule(device(), m, null);
                pipes.add(new Pipe(d.id(), lb.get(0), layout, l0, l1, true));
                return pipes.size() - 1;
            }
            long vm = module(d.vertexSpirv()), fm = module(d.fragmentSpirv());
            VkPipelineShaderStageCreateInfo.Buffer st = VkPipelineShaderStageCreateInfo.calloc(2, s);
            st.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vm).pName(main);
            st.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fm).pName(main);
            VkVertexInputBindingDescription.Buffer vb = VkVertexInputBindingDescription.calloc(1, s);
            vb.get(0).binding(0).stride(QUAD_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer va = VkVertexInputAttributeDescription.calloc(d.inputs().size(), s);
            for (int i = 0; i < d.inputs().size(); i++) {
                VertexInput in = d.inputs().get(i);
                boolean sint = in.type().startsWith("i"), uint = in.type().startsWith("u");
                int off = sint || uint ? 64 : switch (in.feed()) {
                    case "position" -> 0;
                    case "uv0" -> 16;
                    case "white" -> 32;
                    default -> 48;
                };
                va.get(i).location(in.location()).binding(0).offset(off)
                        .format(sint ? VK_FORMAT_R32G32B32A32_SINT : uint ? VK_FORMAT_R32G32B32A32_UINT : VK_FORMAT_R32G32B32A32_SFLOAT);
            }
            VkPipelineVertexInputStateCreateInfo vis = VkPipelineVertexInputStateCreateInfo.calloc(s).sType$Default()
                    .pVertexBindingDescriptions(vb).pVertexAttributeDescriptions(va);
            VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType$Default()
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP);
            VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(s).sType$Default().viewportCount(1).scissorCount(1);
            VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(s).sType$Default()
                    .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f);
            VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(s).sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            int n = d.colourFormats().size();
            VkPipelineColorBlendAttachmentState.Buffer cb = VkPipelineColorBlendAttachmentState.calloc(n, s);
            int[] formats = new int[n];
            for (int i = 0; i < n; i++) {
                String f = d.colourFormats().get(i);
                formats[i] = vkFormat(f);
                cb.get(i).colorWriteMask(0xF);
                Object b = i < d.blend().size() ? d.blend().get(i) : "off";
                boolean integer = f.endsWith("INT");
                if (b instanceof List<?> l && l.size() == 4 && !integer) {
                    cb.get(i).blendEnable(true).srcColorBlendFactor(blendFactor(String.valueOf(l.get(0))))
                            .dstColorBlendFactor(blendFactor(String.valueOf(l.get(1)))).colorBlendOp(VK_BLEND_OP_ADD)
                            .srcAlphaBlendFactor(blendFactor(String.valueOf(l.get(2)))).dstAlphaBlendFactor(blendFactor(String.valueOf(l.get(3))))
                            .alphaBlendOp(VK_BLEND_OP_ADD);
                }
            }
            VkPipelineColorBlendStateCreateInfo cbs = VkPipelineColorBlendStateCreateInfo.calloc(s).sType$Default().pAttachments(cb);
            VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(s).sType$Default()
                    .pDynamicStates(s.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            VkGraphicsPipelineCreateInfo.Buffer ci = VkGraphicsPipelineCreateInfo.calloc(1, s).sType$Default()
                    .pStages(st).pVertexInputState(vis).pInputAssemblyState(ia).pViewportState(vp).pRasterizationState(rs)
                    .pMultisampleState(ms).pColorBlendState(cbs).pDynamicState(dyn).layout(layout).renderPass(renderPass(formats)).subpass(0);
            check(vkCreateGraphicsPipelines(device(), pipelineCache(), ci, null, lb), "graphics pipeline " + d.id());
            vkDestroyShaderModule(device(), vm, null);
            vkDestroyShaderModule(device(), fm, null);
            pipes.add(new Pipe(d.id(), lb.get(0), layout, l0, l1, false));
            return pipes.size() - 1;
        }
    }

    // ------------------------------------------------------------------------------------------------ commands

    @Override
    public void barrier(int image, String from, String to) {
        try (MemoryStack s = stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(cmd(), VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, b, null, null);
        }
    }

    @Override
    public void clear(int image, float[] rgba) {
        Img im = img(image);
        try (MemoryStack s = stackPush()) {
            VkImageSubresourceRange r = VkImageSubresourceRange.calloc(s).aspectMask(im.aspect).baseMipLevel(0)
                    .levelCount(im.d.mips()).baseArrayLayer(0).layerCount(1);
            if (im.aspect == VK_IMAGE_ASPECT_DEPTH_BIT) {
                vkCmdClearDepthStencilImage(cmd(), im.image, VK_IMAGE_LAYOUT_GENERAL, VkClearDepthStencilValue.calloc(s).depth(rgba[0]), r);
            } else {
                VkClearColorValue cv = VkClearColorValue.calloc(s);
                if (im.d.vkFormat().endsWith("UINT") || im.d.vkFormat().endsWith("SINT"))
                    cv.uint32(0, Math.round(rgba[0])).uint32(1, Math.round(rgba[1])).uint32(2, Math.round(rgba[2])).uint32(3, Math.round(rgba[3]));
                else
                    cv.float32(0, rgba[0]).float32(1, rgba[1]).float32(2, rgba[2]).float32(3, rgba[3]);
                vkCmdClearColorImage(cmd(), im.image, VK_IMAGE_LAYOUT_GENERAL, cv, r);
            }
        }
    }

    @Override
    public void generateMips(int image) {
        Img im = img(image);
        try (MemoryStack s = stackPush()) {
            VkFormatProperties fp = VkFormatProperties.calloc(s);
            vkGetPhysicalDeviceFormatProperties(phys(), im.format, fp);
            int need = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if ((fp.optimalTilingFeatures() & need) != need) {
                String note = "mips of " + im.d.name() + " skipped: " + im.d.vkFormat() + " cannot be blitted linearly here";
                if (!log.contains(note))
                    log.add(note);
                return;
            }
            int w = im.d.width(), h = im.d.height();
            for (int m = 1; m < im.d.mips(); m++) {
                int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
                VkImageBlit.Buffer b = VkImageBlit.calloc(1, s);
                b.srcSubresource().aspectMask(im.aspect).mipLevel(m - 1).baseArrayLayer(0).layerCount(1);
                b.srcOffsets(1).set(w, h, 1);
                b.dstSubresource().aspectMask(im.aspect).mipLevel(m).baseArrayLayer(0).layerCount(1);
                b.dstOffsets(1).set(nw, nh, 1);
                vkCmdBlitImage(cmd(), im.image, VK_IMAGE_LAYOUT_GENERAL, im.image, VK_IMAGE_LAYOUT_GENERAL, b, VK_FILTER_LINEAR);
                barrier(image, "TRANSFER_WRITE", "TRANSFER_READ");
                w = nw;
                h = nh;
            }
        }
    }

    @Override
    public void copy(int src, int dst) {
        Img a = img(src), b = img(dst);
        try (MemoryStack s = stackPush()) {
            VkImageCopy.Buffer r = VkImageCopy.calloc(1, s);
            r.srcSubresource().aspectMask(a.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.dstSubresource().aspectMask(b.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.extent().width(a.d.width()).height(a.d.height()).depth(a.d.depth());
            vkCmdCopyImage(cmd(), a.image, VK_IMAGE_LAYOUT_GENERAL, b.image, VK_IMAGE_LAYOUT_GENERAL, r);
        }
    }

    @Override
    public void blit(int src, int dst) {
        Img a = img(src), b = img(dst);
        int la = enterTransfer(a, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        int lb = enterTransfer(b, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        int sa = a.imported != null ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : VK_IMAGE_LAYOUT_GENERAL;
        int sb = b.imported != null ? VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL : VK_IMAGE_LAYOUT_GENERAL;
        try (MemoryStack s = stackPush()) {
            boolean sameSize = a.d.width() == b.d.width() && a.d.height() == b.d.height();
            if (a.format == b.format && sameSize) {
                VkImageCopy.Buffer r = VkImageCopy.calloc(1, s);
                r.srcSubresource().aspectMask(a.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
                r.dstSubresource().aspectMask(b.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
                r.extent().width(a.d.width()).height(a.d.height()).depth(1);
                vkCmdCopyImage(cmd(), a.image, sa, b.image, sb, r);
            } else if (a.aspect == VK_IMAGE_ASPECT_COLOR_BIT && b.aspect == VK_IMAGE_ASPECT_COLOR_BIT) {
                VkImageBlit.Buffer r = VkImageBlit.calloc(1, s);
                r.srcSubresource().aspectMask(a.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
                r.srcOffsets(1).set(a.d.width(), a.d.height(), 1);
                r.dstSubresource().aspectMask(b.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
                r.dstOffsets(1).set(b.d.width(), b.d.height(), 1);
                vkCmdBlitImage(cmd(), a.image, sa, b.image, sb, r, VK_FILTER_LINEAR);
            } else {
                String note = "blit " + a.d.name() + " -> " + b.d.name() + " skipped: depth formats or sizes differ";
                if (!log.contains(note))
                    log.add(note);
            }
        }
        leaveTransfer(a, la, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        leaveTransfer(b, lb, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    }

    private int enterTransfer(Img im, int to) {
        if (im.imported == null)
            return VK_IMAGE_LAYOUT_GENERAL;
        int was = im.imported.get();
        layout(cmd(), im.image, barrierAspect(im.format, im.aspect), 1, was, to);
        return was;
    }

    private void leaveTransfer(Img im, int was, int now) {
        if (im.imported == null)
            return;
        int back = was == VK_IMAGE_LAYOUT_UNDEFINED ? VK_IMAGE_LAYOUT_GENERAL : was;
        layout(cmd(), im.image, barrierAspect(im.format, im.aspect), 1, now, back);
        im.imported.set(back);
    }

    protected long sampler(String kind) {
        return samplers.computeIfAbsent(kind, k -> {
            try (MemoryStack s = stackPush()) {
                boolean linear = k.equals("LINEAR") || k.equals("COMPARE");
                VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(s).sType$Default()
                        .magFilter(linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST).minFilter(linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                        .mipmapMode(linear ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(VK_LOD_CLAMP_NONE)
                        .compareEnable(k.equals("COMPARE")).compareOp(VK_COMPARE_OP_LESS_OR_EQUAL);
                LongBuffer lb = s.mallocLong(1);
                check(vkCreateSampler(device(), ci, null, lb), "sampler");
                return lb.get(0);
            }
        });
    }

    private long[] sets(Pipe p, Map<Integer, Bind> set0, Map<Integer, Bind> set1, byte[] ubo) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(2);
            check(vkAllocateDescriptorSets(device(), VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(descriptorPool())
                    .pSetLayouts(s.longs(p.set0(), p.set1())), lb), "descriptor sets");
            long s0 = lb.get(0), s1 = lb.get(1);
            HostBuf u = hostBuffer(Math.max(16, ubo.length), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            u.mapped().put(0, ubo);
            freeHostBufferAfterFrame(u);
            int count = 1 + set0.size() + set1.size();
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(count, s);
            w.get(0).sType$Default().dstSet(s0).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, s).buffer(u.buffer()).offset(0).range(VK_WHOLE_SIZE)).descriptorCount(1);
            int i = 1;
            for (int set = 0; set < 2; set++)
                for (Map.Entry<Integer, Bind> e : (set == 0 ? set0 : set1).entrySet()) {
                    Bind b = e.getValue();
                    VkWriteDescriptorSet x = w.get(i++).sType$Default().dstSet(set == 0 ? s0 : s1).dstBinding(e.getKey());
                    if (b.buffer() >= 0) {
                        x.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(VkDescriptorBufferInfo.calloc(1, s)
                                .buffer(bufs.get(b.buffer()).buffer()).offset(0).range(VK_WHOLE_SIZE));
                    } else {
                        Img im = img(b.image());
                        boolean storage = "STORAGE".equals(b.sampler());
                        VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, s)
                                .imageView(storage ? im.viewMip0 : im.viewAll).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                        if (!storage)
                            ii.sampler(sampler(b.sampler()));
                        x.descriptorType(storage ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(ii);
                    }
                    x.descriptorCount(1);
                }
            vkUpdateDescriptorSets(device(), w, null);
            return new long[]{s0, s1};
        }
    }

    private long framebuffer(long renderPass, int[] colour, int w, int h) {
        StringBuilder key = new StringBuilder().append(renderPass).append(':').append(w).append('x').append(h);
        for (int c : colour)
            key.append(',').append(c);
        long[] fb = framebuffers.computeIfAbsent(key.toString(), k -> {
            try (MemoryStack s = stackPush()) {
                LongBuffer views = s.mallocLong(colour.length);
                long[] rec = new long[1 + colour.length];
                for (int i = 0; i < colour.length; i++) {
                    Img im = img(colour[i]);
                    views.put(i, im.viewMip0);
                    rec[1 + i] = im.image;
                }
                LongBuffer lb = s.mallocLong(1);
                check(vkCreateFramebuffer(device(), VkFramebufferCreateInfo.calloc(s).sType$Default().renderPass(renderPass)
                        .pAttachments(views).width(w).height(h).layers(1), null, lb), "framebuffer");
                rec[0] = lb.get(0);
                return rec;
            }
        });
        return fb[0];
    }

    @Override
    public void draw(Draw d) {
        Pipe p = pipes.get(d.pipeline());
        long[] ds = sets(p, d.set0(), d.set1(), d.uniforms());
        try (MemoryStack s = stackPush()) {
            int n = d.colour().length;
            int[] formats = new int[n];
            int aw = Integer.MAX_VALUE, ah = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                Img im = img(d.colour()[i]);
                formats[i] = im.format;
                aw = Math.min(aw, im.d.width());
                ah = Math.min(ah, im.d.height());
            }
            long rp = renderPass(formats);
            long fb = framebuffer(rp, d.colour(), aw, ah);
            VkRenderPassBeginInfo bi = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(rp).framebuffer(fb);
            bi.renderArea().extent().width(aw).height(ah);
            vkCmdBeginRenderPass(cmd(), bi, VK_SUBPASS_CONTENTS_INLINE);
            int[] v = d.viewport();
            vkCmdSetViewport(cmd(), 0, VkViewport.calloc(1, s).x(v[0]).y(v[1]).width(v[2]).height(v[3]).minDepth(0).maxDepth(1));
            VkRect2D.Buffer sc = VkRect2D.calloc(1, s);
            sc.get(0).offset().set(v[0], v[1]);
            sc.get(0).extent().set(Math.min(v[2], aw), Math.min(v[3], ah));
            vkCmdSetScissor(cmd(), 0, sc);
            vkCmdBindPipeline(cmd(), VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipeline());
            vkCmdBindDescriptorSets(cmd(), VK_PIPELINE_BIND_POINT_GRAPHICS, p.layout(), 0, s.longs(ds[0], ds[1]), null);
            vkCmdBindVertexBuffers(cmd(), 0, s.longs(quad.buffer()), s.longs(0));
            vkCmdDraw(cmd(), 4, 1, 0, 0);
            vkCmdEndRenderPass(cmd());
        }
    }

    @Override
    public void dispatch(Dispatch d) {
        Pipe p = pipes.get(d.pipeline());
        long[] ds = sets(p, d.set0(), d.set1(), d.uniforms());
        try (MemoryStack s = stackPush()) {
            vkCmdBindPipeline(cmd(), VK_PIPELINE_BIND_POINT_COMPUTE, p.pipeline());
            vkCmdBindDescriptorSets(cmd(), VK_PIPELINE_BIND_POINT_COMPUTE, p.layout(), 0, s.longs(ds[0], ds[1]), null);
            vkCmdDispatch(cmd(), d.groups()[0], d.groups()[1], d.groups()[2]);
        }
    }

    /** destroys pipelines, layouts, samplers, render passes, framebuffers (deferred), owned images and buffers */
    protected void destroyShared() {
        for (int i = 0; i < imgs.size(); i++)
            freeImage(i);
        for (long[] fb : framebuffers.values()) {
            long h = fb[0];
            releaseLater(() -> vkDestroyFramebuffer(device(), h, null));
        }
        framebuffers.clear();
        List<Pipe> ps = new ArrayList<>();
        for (Pipe p : pipes)
            if (p != null)
                ps.add(p);
        List<Long> sm = new ArrayList<>(samplers.values());
        List<Long> rps = new ArrayList<>(renderPasses.values());
        List<HostBuf> bs = new ArrayList<>(bufs);
        HostBuf q = quad;
        releaseLater(() -> {
            for (Pipe p : ps) {
                vkDestroyPipeline(device(), p.pipeline(), null);
                vkDestroyPipelineLayout(device(), p.layout(), null);
                vkDestroyDescriptorSetLayout(device(), p.set0(), null);
                vkDestroyDescriptorSetLayout(device(), p.set1(), null);
            }
            for (long x : sm)
                vkDestroySampler(device(), x, null);
            for (long x : rps)
                vkDestroyRenderPass(device(), x, null);
            for (HostBuf b : bs)
                freeHostBuffer(b);
            if (q != null)
                freeHostBuffer(q);
        });
        pipes.clear();
        samplers.clear();
        renderPasses.clear();
        bufs.clear();
        quad = null;
    }

    protected static IntBuffer unused(IntBuffer b) {
        return b;
    }
}
