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
                if (fb[k] == im.image) { // P3-6 fix: compared the VkImage handle with the backend index (never matched: framebuffers leaked until close)
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
        return renderPass(formats, 0);
    }

    /** colour formats + optional depth format (0 = none); every attachment LOAD/STORE in GENERAL */
    private long renderPass(int[] formats, int depthFormat) {
        StringBuilder key = new StringBuilder();
        for (int f : formats)
            key.append(f).append(',');
        key.append("d").append(depthFormat);
        return renderPasses.computeIfAbsent(key.toString(), k -> {
            try (MemoryStack s = stackPush()) {
                int n = formats.length, total = n + (depthFormat != 0 ? 1 : 0);
                VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(total, s);
                VkAttachmentReference.Buffer refs = VkAttachmentReference.calloc(n, s);
                for (int i = 0; i < total; i++) {
                    int f = i < n ? formats[i] : depthFormat;
                    att.get(i).format(f).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                            .storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(i < n ? VK_ATTACHMENT_LOAD_OP_DONT_CARE : VK_ATTACHMENT_LOAD_OP_LOAD)
                            .stencilStoreOp(i < n ? VK_ATTACHMENT_STORE_OP_DONT_CARE : VK_ATTACHMENT_STORE_OP_STORE)
                            .initialLayout(VK_IMAGE_LAYOUT_GENERAL).finalLayout(VK_IMAGE_LAYOUT_GENERAL);
                    if (i < n)
                        refs.get(i).attachment(i).layout(VK_IMAGE_LAYOUT_GENERAL);
                }
                VkSubpassDescription.Buffer sub = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                        .colorAttachmentCount(n).pColorAttachments(refs);
                if (depthFormat != 0)
                    sub.pDepthStencilAttachment(VkAttachmentReference.calloc(s).attachment(n).layout(VK_IMAGE_LAYOUT_GENERAL));
                LongBuffer lb = s.mallocLong(1);
                check(vkCreateRenderPass(device(), VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(att).pSubpasses(sub), null, lb),
                        "render pass");
                return lb.get(0);
            }
        });
    }

    /**
     * The 36-byte WideTerrainVertex (P3-2 GeometryNeeds.WIDE_TERRAIN) as vertex attributes, by Iris attribute
     * location. Scaled formats keep raw integers (positions are decoded in the shader by iroh_VertexDecode; light
     * 0..240 goes through gl_TextureMatrix[1] as in Iris). An input this layout has no data for reads the zero pad byte
     * at +35. Only formats Vulkan requires for vertex buffers are used.
     */
    static int[] wideAttribute(int location, String type) {
        boolean sint = type.startsWith("i"), uint = type.startsWith("u");
        if (sint || uint)
            return new int[]{35, uint ? VK_FORMAT_R8_UINT : VK_FORMAT_R8_SINT};
        return switch (location) {
            case 0 -> new int[]{0, VK_FORMAT_R16G16B16A16_SSCALED};
            case 1 -> new int[]{12, VK_FORMAT_R8G8B8A8_UNORM};
            case 2 -> new int[]{8, VK_FORMAT_R16G16_UNORM};
            case 3, 4 -> new int[]{6, VK_FORMAT_R8G8_USCALED};
            case 6 -> new int[]{16, VK_FORMAT_R8G8B8A8_SNORM};
            case 7 -> new int[]{32, VK_FORMAT_R16G16_USCALED};
            case 8 -> new int[]{24, VK_FORMAT_R16G16_UNORM};
            case 9 -> new int[]{20, VK_FORMAT_R8G8B8A8_SNORM};
            case 10 -> new int[]{28, VK_FORMAT_R8G8B8A8_SSCALED};
            default -> new int[]{35, VK_FORMAT_R8_UNORM};
        };
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
            Geo geo = d.geo();
            boolean wide = geo != null && "wide-terrain".equals(geo.layout());
            VanillaLayout vl = geo != null && VanillaLayout.is(geo.layout()) ? VanillaLayout.of(geo.layout()) : null;
            VkVertexInputBindingDescription.Buffer vb = VkVertexInputBindingDescription.calloc(vl != null ? 2 : 1, s);
            vb.get(0).binding(0).stride(wide ? dev.elemore.iroh.shaderpack.mesh.WideTerrainVertex.BYTES : vl != null ? vl.stride : QUAD_STRIDE)
                    .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            if (vl != null)
                vb.get(1).binding(1).stride(0).inputRate(VK_VERTEX_INPUT_RATE_VERTEX); // zeros for missing inputs
            VkVertexInputAttributeDescription.Buffer va = VkVertexInputAttributeDescription.calloc(d.inputs().size(), s);
            for (int i = 0; i < d.inputs().size(); i++) {
                VertexInput in = d.inputs().get(i);
                if (vl != null) {
                    VanillaLayout.Attr a = vl.attribute(in.location(), in.type());
                    if (a != null)
                        va.get(i).location(in.location()).binding(0).offset(a.offset()).format(a.format());
                    else
                        va.get(i).location(in.location()).binding(1).offset(0).format(VanillaLayout.zeroFormat(in.type()));
                    continue;
                }
                if (wide) {
                    int[] a = wideAttribute(in.location(), in.type());
                    va.get(i).location(in.location()).binding(0).offset(a[0]).format(a[1]);
                    continue;
                }
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
                    .topology(geo != null ? VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP);
            VkPipelineViewportStateCreateInfo vp = VkPipelineViewportStateCreateInfo.calloc(s).sType$Default().viewportCount(1).scissorCount(1);
            VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(s).sType$Default()
                    .polygonMode(VK_POLYGON_MODE_FILL).cullMode(geo != null && geo.cullBack() ? VK_CULL_MODE_FRONT_BIT : VK_CULL_MODE_NONE) // RQ-16: GL_BACK -> VK FRONT (positive-height viewport mirrors y)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f);
            int depthFmt = geo != null && geo.depthFormat() != null ? vkFormat(geo.depthFormat()) : 0;
            VkPipelineDepthStencilStateCreateInfo dss = VkPipelineDepthStencilStateCreateInfo.calloc(s).sType$Default()
                    .depthTestEnable(depthFmt != 0 && geo.depthTest()).depthWriteEnable(depthFmt != 0 && geo.depthWrite())
                    .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);
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
                    .pMultisampleState(ms).pColorBlendState(cbs).pDynamicState(dyn).layout(layout).renderPass(renderPass(formats, depthFmt)).subpass(0);
            if (depthFmt != 0)
                ci.get(0).pDepthStencilState(dss);
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
            // P3-11: an imported image (the scene colour, cleared transparent for the world pass) is cleared in TRANSFER_DST
            int was = enterTransfer(im, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            int lay = im.imported != null ? VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL : VK_IMAGE_LAYOUT_GENERAL;
            if (im.imported != null)
                r.levelCount(1);
            if (im.aspect == VK_IMAGE_ASPECT_DEPTH_BIT) {
                vkCmdClearDepthStencilImage(cmd(), im.image, lay, VkClearDepthStencilValue.calloc(s).depth(rgba[0]), r);
            } else {
                VkClearColorValue cv = VkClearColorValue.calloc(s);
                if (im.d.vkFormat().endsWith("UINT") || im.d.vkFormat().endsWith("SINT"))
                    cv.uint32(0, Math.round(rgba[0])).uint32(1, Math.round(rgba[1])).uint32(2, Math.round(rgba[2])).uint32(3, Math.round(rgba[3]));
                else
                    cv.float32(0, rgba[0]).float32(1, rgba[1]).float32(2, rgba[2]).float32(3, rgba[3]);
                vkCmdClearColorImage(cmd(), im.image, lay, cv, r);
            }
            leaveTransfer(im, was, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
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
            } else if (a.format == b.format && a.aspect == VK_IMAGE_ASPECT_DEPTH_BIT && depthBlit(a.format)) {
                // P3-7: depth resized for passes after the upscale cut: NEAREST is the only filter allowed for depth
                VkImageBlit.Buffer r = VkImageBlit.calloc(1, s);
                r.srcSubresource().aspectMask(a.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
                r.srcOffsets(1).set(a.d.width(), a.d.height(), 1);
                r.dstSubresource().aspectMask(b.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
                r.dstOffsets(1).set(b.d.width(), b.d.height(), 1);
                vkCmdBlitImage(cmd(), a.image, sa, b.image, sb, r, VK_FILTER_NEAREST);
            } else {
                String note = "blit " + a.d.name() + " -> " + b.d.name() + " skipped: depth formats or sizes differ";
                if (!log.contains(note))
                    log.add(note);
            }
        }
        leaveTransfer(a, la, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        leaveTransfer(b, lb, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    }

    private final Map<Integer, Boolean> depthBlitOk = new HashMap<>();

    private boolean depthBlit(int format) {
        return depthBlitOk.computeIfAbsent(format, f -> {
            try (MemoryStack s = stackPush()) {
                VkFormatProperties fp = VkFormatProperties.calloc(s);
                vkGetPhysicalDeviceFormatProperties(phys(), f, fp);
                int need = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT;
                return (fp.optimalTilingFeatures() & need) == need;
            }
        });
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
                boolean linear = k.equals("LINEAR") || k.equals("LINEAR_NOMIP") || k.equals("COMPARE");
                boolean mips = k.equals("LINEAR"); // only a pass's own mipmapped targets read other levels
                VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(s).sType$Default()
                        .magFilter(linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST).minFilter(linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                        .mipmapMode(linear ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(mips ? VK_LOD_CLAMP_NONE : 0f)
                        .compareEnable(k.equals("COMPARE")).compareOp(VK_COMPARE_OP_LESS_OR_EQUAL);
                LongBuffer lb = s.mallocLong(1);
                check(vkCreateSampler(device(), ci, null, lb), "sampler");
                return lb.get(0);
            }
        });
    }

    // ---- per-frame-slot uniform ring (P3-6 perf: no buffer allocation per draw; chunks are kept and reused)
    private static final int UBO_CHUNK = 1 << 20, UBO_ALIGN = 256;
    private final List<List<HostBuf>> uboChunks = new ArrayList<>();
    private int uboSlot, uboChunk, uboCursor;

    /** call when frame slot {@code slot} starts recording again (its previous use has completed) */
    protected void resetUboRing(int slot) {
        while (uboChunks.size() <= slot)
            uboChunks.add(new ArrayList<>());
        uboSlot = slot;
        uboChunk = 0;
        uboCursor = 0;
    }

    /** {chunk index, byte offset} in the current slot's ring */
    private long[] uboAlloc(int bytes) {
        if (uboChunks.isEmpty())
            resetUboRing(0);
        List<HostBuf> chunks = uboChunks.get(uboSlot);
        int size = (bytes + UBO_ALIGN - 1) / UBO_ALIGN * UBO_ALIGN;
        if (uboChunk < chunks.size() && uboCursor + size > chunks.get(uboChunk).size()) {
            uboChunk++;
            uboCursor = 0;
        }
        if (uboChunk >= chunks.size())
            chunks.add(hostBuffer(Math.max(UBO_CHUNK, size), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT));
        long[] r = {uboChunk, uboCursor};
        uboCursor += size;
        return r;
    }

    /** chunks in use (tests / profile) */
    public int uboChunks() {
        int n = 0;
        for (List<HostBuf> l : uboChunks)
            n += l.size();
        return n;
    }

    private long[] sets(Pipe p, Map<Integer, Bind> set0, Map<Integer, Bind> set1, byte[] ubo) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(2);
            check(vkAllocateDescriptorSets(device(), VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(descriptorPool())
                    .pSetLayouts(s.longs(p.set0(), p.set1())), lb), "descriptor sets");
            long s0 = lb.get(0), s1 = lb.get(1);
            long[] at = uboAlloc(Math.max(16, ubo.length));
            HostBuf u = uboChunks.get(uboSlot).get((int) at[0]);
            u.mapped().put((int) at[1], ubo);
            int count = 1 + set0.size() + set1.size();
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(count, s);
            w.get(0).sType$Default().dstSet(s0).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, s).buffer(u.buffer()).offset(at[1]).range(Math.max(16, ubo.length))).descriptorCount(1);
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
                                .imageView(storage ? im.viewMip0 : im.viewAll)
                                // P3-6: an imported image (the block atlas) is sampled in the layout Iroh keeps it in
                                .imageLayout(im.imported != null && im.imported.get() != VK_IMAGE_LAYOUT_UNDEFINED ? im.imported.get() : VK_IMAGE_LAYOUT_GENERAL);
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
        return framebuffer(renderPass, colour, -1, w, h);
    }

    private long framebuffer(long renderPass, int[] colour, int depth, int w, int h) {
        StringBuilder key = new StringBuilder().append(renderPass).append(':').append(w).append('x').append(h);
        for (int c : colour)
            key.append(',').append(c);
        key.append(",d").append(depth);
        long[] fb = framebuffers.computeIfAbsent(key.toString(), k -> {
            try (MemoryStack s = stackPush()) {
                int n = colour.length + (depth >= 0 ? 1 : 0);
                LongBuffer views = s.mallocLong(n);
                long[] rec = new long[1 + n];
                for (int i = 0; i < n; i++) {
                    Img im = img(i < colour.length ? colour[i] : depth);
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

    private final List<HostBuf> vbufs = new ArrayList<>();
    private HostBuf zeroVertex;
    private HostBuf quadIndex;
    private int quadIndexQuads;

    @Override
    public int createVertexBuffer(String name, ByteBuffer data) {
        HostBuf b = hostBuffer(Math.max(16, data.remaining()), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        b.mapped().put(0, data, data.position(), data.remaining());
        vbufs.add(b);
        return vbufs.size() - 1;
    }

    @Override
    public void freeBuffer(int buffer) {
        HostBuf b = buffer >= 0 && buffer < vbufs.size() ? vbufs.get(buffer) : null;
        if (b == null)
            return;
        vbufs.set(buffer, null);
        freeHostBufferAfterFrame(b);
    }

    /** shared quad index buffer (0 1 2, 2 3 0 per quad), grown on demand */
    private long quadIndices(int quads) {
        if (quadIndex == null || quadIndexQuads < quads) {
            if (quadIndex != null)
                freeHostBufferAfterFrame(quadIndex);
            int n = Math.max(quads, 4096);
            quadIndex = hostBuffer(n * 6L * 4, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            ByteBuffer m = quadIndex.mapped();
            for (int q = 0; q < n; q++) {
                int v = q * 4, o = q * 24;
                m.putInt(o, v).putInt(o + 4, v + 1).putInt(o + 8, v + 2).putInt(o + 12, v + 2).putInt(o + 16, v + 3).putInt(o + 20, v);
            }
            quadIndexQuads = n;
        }
        return quadIndex.buffer();
    }

    @Override
    public void drawGeometry(GeoDraw d) {
        Pipe p = pipes.get(d.pipeline());
        int maxQuads = 1;
        for (GeoBatch gb : d.batches())
            maxQuads = Math.max(maxQuads, gb.quads());
        long[][] ds = new long[d.batches().size()][];
        for (int i = 0; i < ds.length; i++)
            ds[i] = sets(p, d.set0(), d.set1(), d.batches().get(i).uniforms());
        long ib = quadIndices(maxQuads);
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
            int depthFmt = 0;
            if (d.depth() >= 0) {
                Img dm = img(d.depth());
                depthFmt = dm.format;
                aw = Math.min(aw, dm.d.width());
                ah = Math.min(ah, dm.d.height());
            }
            long rp = renderPass(formats, depthFmt);
            long fb = framebuffer(rp, d.colour(), d.depth(), aw, ah);
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
            boolean boundQuadIndex = false;
            for (int i = 0; i < ds.length; i++) {
                GeoBatch gb = d.batches().get(i);
                PackFrameInputs.Indirect ind = gb.indirect();
                if (ind != null) {
                    // Iroh's own section buffers and the indirect commands it wrote (P3-6)
                    if (ind.drawCount() <= 0)
                        continue;
                    vkCmdBindDescriptorSets(cmd(), VK_PIPELINE_BIND_POINT_GRAPHICS, p.layout(), 0, s.longs(ds[i][0], ds[i][1]), null);
                    vkCmdBindVertexBuffers(cmd(), 0, s.longs(ind.vertexBuffer()), s.longs(0));
                    vkCmdBindIndexBuffer(cmd(), ind.indexBuffer(), 0, ind.indexType());
                    vkCmdDrawIndexedIndirect(cmd(), ind.indirectBuffer(), ind.indirectOffset(), ind.drawCount(), ind.stride());
                    boundQuadIndex = false;
                    continue;
                }
                if (gb.quads() <= 0)
                    continue;
                if (!boundQuadIndex) {
                    vkCmdBindIndexBuffer(cmd(), ib, 0, VK_INDEX_TYPE_UINT32);
                    boundQuadIndex = true;
                }
                vkCmdBindDescriptorSets(cmd(), VK_PIPELINE_BIND_POINT_GRAPHICS, p.layout(), 0, s.longs(ds[i][0], ds[i][1]), null);
                if (zeroVertex == null)
                {
                    zeroVertex = hostBuffer(64, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
                    zeroVertex.mapped().put(0, new byte[64]);
                }
                vkCmdBindVertexBuffers(cmd(), 1, s.longs(zeroVertex.buffer()), s.longs(0)); // P3-9: zero binding (vanilla layouts)
                if (gb.vertexBuffer() >= 0)
                    vkCmdBindVertexBuffers(cmd(), 0, s.longs(vbufs.get(gb.vertexBuffer()).buffer()), s.longs(0));
                else
                    vkCmdBindVertexBuffers(cmd(), 0, s.longs(gb.externalBuffer()), s.longs(gb.externalOffset()));
                vkCmdDrawIndexed(cmd(), gb.quads() * 6, 1, 0, 0, 0);
            }
            vkCmdEndRenderPass(cmd());
        }
    }

    // ------------------------------------------------------------------------------------------------ P3-11 external draws

    private boolean attachmentsOpen;

    /** P3-11: one render pass instance over a pack pass's attachments for draws Iroh's renderer issues (substituted draws) */
    @Override
    public void beginAttachments(String pass, int[] colour, int depth, int[] viewport) {
        if (attachmentsOpen)
            throw new IllegalStateException("beginAttachments " + pass + " while attachments are open");
        try (MemoryStack s = stackPush()) {
            int n = colour.length;
            int[] formats = new int[n];
            int aw = Integer.MAX_VALUE, ah = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                Img im = img(colour[i]);
                formats[i] = im.format;
                aw = Math.min(aw, im.d.width());
                ah = Math.min(ah, im.d.height());
            }
            int depthFmt = 0;
            if (depth >= 0) {
                Img dm = img(depth);
                depthFmt = dm.format;
                aw = Math.min(aw, dm.d.width());
                ah = Math.min(ah, dm.d.height());
            }
            long rp = renderPass(formats, depthFmt);
            long fb = framebuffer(rp, colour, depth, aw, ah);
            VkRenderPassBeginInfo bi = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(rp).framebuffer(fb);
            bi.renderArea().extent().width(aw).height(ah);
            vkCmdBeginRenderPass(cmd(), bi, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdSetViewport(cmd(), 0, VkViewport.calloc(1, s).x(viewport[0]).y(viewport[1]).width(viewport[2]).height(viewport[3]).minDepth(0).maxDepth(1));
            VkRect2D.Buffer sc = VkRect2D.calloc(1, s);
            sc.get(0).offset().set(viewport[0], viewport[1]);
            sc.get(0).extent().set(Math.min(viewport[2], aw), Math.min(viewport[3], ah));
            vkCmdSetScissor(cmd(), 0, sc);
            attachmentsOpen = true;
        }
    }

    @Override
    public void bindExternalDraw(String pass, int pipeline, Map<Integer, Bind> set0, Map<Integer, Bind> set1, byte[] uniforms) {
        if (!attachmentsOpen)
            throw new IllegalStateException("bindExternalDraw " + pass + " outside beginAttachments");
        Pipe p = pipes.get(pipeline);
        long[] ds = sets(p, set0, set1, uniforms);
        try (MemoryStack s = stackPush()) {
            vkCmdBindPipeline(cmd(), VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipeline());
            vkCmdBindDescriptorSets(cmd(), VK_PIPELINE_BIND_POINT_GRAPHICS, p.layout(), 0, s.longs(ds[0], ds[1]), null);
            if (zeroVertex == null) {
                zeroVertex = hostBuffer(64, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
                zeroVertex.mapped().put(0, new byte[64]);
            }
            vkCmdBindVertexBuffers(cmd(), 1, s.longs(zeroVertex.buffer()), s.longs(0)); // inputs the vanilla format lacks
        }
    }

    @Override
    public void endAttachments() {
        if (!attachmentsOpen)
            throw new IllegalStateException("endAttachments without beginAttachments");
        vkCmdEndRenderPass(cmd());
        attachmentsOpen = false;
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
        for (List<HostBuf> l : uboChunks)
            bs.addAll(l);
        uboChunks.clear();
        for (HostBuf vb : vbufs)
            if (vb != null)
                bs.add(vb);
        vbufs.clear();
        if (quadIndex != null)
            bs.add(quadIndex);
        if (zeroVertex != null)
            bs.add(zeroVertex);
        zeroVertex = null;
        quadIndex = null;
        quadIndexQuads = 0;
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
