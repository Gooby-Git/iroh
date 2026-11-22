package dev.elemore.iroh.shaderpack.runtime;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * A standalone headless Vulkan 1.3 device for the offline replay (no window, no swapchain, no game, not Iroh's
 * renderer). SAFETY: it refuses every physical device that is not a CPU implementation (lavapipe / llvmpipe) unless
 * explicitly allowed, and it caps its own device memory ({@link #cap}, 1 GiB by default): a replay can never touch the
 * owner's GPU. The harness additionally restricts the Vulkan loader to the lavapipe ICD (VK_DRIVER_FILES).
 * <p>
 * Simple by design: every image lives in GENERAL layout; {@link #barrier} is a full memory barrier; one primary command
 * buffer per frame, submitted and waited at {@link #endFrame}; per-draw uniform buffers freed after the frame.
 */
public final class HeadlessVkBackend implements GpuBackend {

    private static final int QUAD_STRIDE = 80; // pos vec4, uv vec4, white vec4, zero vec4, int zero ivec4

    private final VkInstance instance;
    private final VkPhysicalDevice phys;
    private final VkDevice device;
    private final VkQueue queue;
    private final long cmdPool, fence, descPool;
    private final VkCommandBuffer cmd;
    private final VkPhysicalDeviceMemoryProperties memProps;
    public final String deviceName;
    public final long cap;
    private long allocated;
    private boolean recording;

    private final List<Img> imgs = new ArrayList<>();
    private final List<Buf> bufs = new ArrayList<>();
    private final List<Pipe> pipes = new ArrayList<>();
    private final List<Buf> frameBufs = new ArrayList<>();
    private final Map<String, Long> samplers = new HashMap<>();
    private final Buf quad;
    public final List<String> log = new ArrayList<>();

    private record Img(ImageDesc d, long image, long memory, long viewAll, long viewMip0, int format, int aspect, long bytes) {}

    private record Buf(long buffer, long memory, long size, ByteBuffer mapped) {}

    private record Pipe(String id, long pipeline, long layout, long set0, long set1, boolean compute) {}

    public HeadlessVkBackend(boolean allowGpu, long capBytes) {
        this.cap = capBytes;
        try (MemoryStack s = stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(s).sType$Default().pApplicationName(s.UTF8("iroh-pack-replay"))
                    .apiVersion(VK_API_VERSION_1_3);
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app);
            PointerBuffer pp = s.mallocPointer(1);
            check(vkCreateInstance(ici, null, pp), "vkCreateInstance");
            instance = new VkInstance(pp.get(0), ici);
            IntBuffer n = s.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, n, null);
            PointerBuffer devs = s.mallocPointer(n.get(0));
            vkEnumeratePhysicalDevices(instance, n, devs);
            VkPhysicalDevice chosen = null;
            String name = null;
            for (int i = 0; i < devs.capacity(); i++) {
                VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), instance);
                VkPhysicalDeviceProperties pr = VkPhysicalDeviceProperties.calloc(s);
                vkGetPhysicalDeviceProperties(pd, pr);
                boolean cpu = pr.deviceType() == VK_PHYSICAL_DEVICE_TYPE_CPU;
                if (cpu || allowGpu) {
                    chosen = pd;
                    name = pr.deviceNameString();
                    if (cpu)
                        break;
                }
            }
            if (chosen == null) {
                vkDestroyInstance(instance, null);
                throw new IllegalStateException("no CPU Vulkan device (lavapipe) found; the replay never uses a GPU");
            }
            phys = chosen;
            deviceName = name;
            memProps = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(phys, memProps);
            vkGetPhysicalDeviceQueueFamilyProperties(phys, n, null);
            VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.calloc(n.get(0), s);
            vkGetPhysicalDeviceQueueFamilyProperties(phys, n, qf);
            int family = -1;
            for (int i = 0; i < qf.capacity(); i++)
                if ((qf.get(i).queueFlags() & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) == (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
                    family = i;
                    break;
                }
            // enable every feature the device supports (core 1.0 .. 1.3)
            VkPhysicalDeviceVulkan13Features f13 = VkPhysicalDeviceVulkan13Features.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan12Features f12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default().pNext(f13.address());
            VkPhysicalDeviceVulkan11Features f11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default().pNext(f12.address());
            VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(f11.address());
            vkGetPhysicalDeviceFeatures2(phys, f2);
            if (!f13.dynamicRendering())
                throw new IllegalStateException(name + " lacks dynamicRendering");
            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(family)
                    .pQueuePriorities(s.floats(1f));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pNext(f2.address()).pQueueCreateInfos(qci);
            check(vkCreateDevice(phys, dci, null, pp), "vkCreateDevice");
            device = new VkDevice(pp.get(0), phys, dci);
            vkGetDeviceQueue(device, family, 0, pp);
            queue = new VkQueue(pp.get(0), device);
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(s).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, lb), "pool");
            cmdPool = lb.get(0);
            VkCommandBufferAllocateInfo cai = VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(cmdPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            check(vkAllocateCommandBuffers(device, cai, pp), "cmd");
            cmd = new VkCommandBuffer(pp.get(0), device);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(s).sType$Default(), null, lb), "fence");
            fence = lb.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, s);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(4096);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(16384);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4096);
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1024);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1024).pPoolSizes(sizes),
                    null, lb), "descriptor pool");
            descPool = lb.get(0);
        }
        // the full-screen quad (0..1), Iris composite convention
        quad = buffer(4L * QUAD_STRIDE, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        float[][] v = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
        for (int i = 0; i < 4; i++) {
            ByteBuffer m = quad.mapped();
            int o = i * QUAD_STRIDE;
            m.putFloat(o, v[i][0]).putFloat(o + 4, v[i][1]).putFloat(o + 8, 0).putFloat(o + 12, 1);
            m.putFloat(o + 16, v[i][0]).putFloat(o + 20, v[i][1]).putFloat(o + 24, 0).putFloat(o + 28, 1);
            m.putFloat(o + 32, 1).putFloat(o + 36, 1).putFloat(o + 40, 1).putFloat(o + 44, 1);
            m.putFloat(o + 60, 1);
        }
    }

    static void check(int r, String what) {
        if (r != VK_SUCCESS)
            throw new IllegalStateException(what + " failed: VkResult " + r);
    }

    @Override
    public String name() {
        return "headless:" + deviceName;
    }

    // ------------------------------------------------------------------------------------------------ memory

    private int memoryType(int bits, int flags) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++)
            if ((bits & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & flags) == flags)
                return i;
        throw new IllegalStateException("no memory type for bits " + bits + " flags " + flags);
    }

    private long allocate(VkMemoryRequirements req, int flags, String what) {
        if (allocated + req.size() > cap)
            throw new IllegalStateException("replay device memory cap " + (cap >> 20) + " MiB reached allocating " + what);
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(req.size())
                    .memoryTypeIndex(memoryType(req.memoryTypeBits(), flags)), null, lb), "vkAllocateMemory " + what);
            allocated += req.size();
            return lb.get(0);
        }
    }

    private Buf buffer(long size, int usage) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(s).sType$Default().size(size).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, lb), "vkCreateBuffer");
            long b = lb.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(s);
            vkGetBufferMemoryRequirements(device, b, req);
            long mem = allocate(req, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, "buffer");
            vkBindBufferMemory(device, b, mem, 0);
            PointerBuffer pp = s.mallocPointer(1);
            vkMapMemory(device, mem, 0, size, 0, pp);
            return new Buf(b, mem, size, MemoryUtil.memByteBuffer(pp.get(0), (int) size));
        }
    }

    private void freeBuf(Buf b) {
        vkUnmapMemory(device, b.memory());
        vkDestroyBuffer(device, b.buffer(), null);
        vkFreeMemory(device, b.memory(), null);
        allocated -= b.size();
    }

    // ------------------------------------------------------------------------------------------------ images

    public static int vkFormat(String name) {
        try {
            return VK10.class.getField("VK_FORMAT_" + name).getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("unknown VkFormat " + name);
        }
    }

    @Override
    public int createImage(ImageDesc d) {
        int fmt = vkFormat(d.vkFormat());
        boolean depth = d.vkFormat().startsWith("D");
        int aspect = depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
        int usage = 0;
        int need = 0;
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
            vkGetPhysicalDeviceFormatProperties(phys, fmt, fp);
            if ((fp.optimalTilingFeatures() & need) != need)
                throw new IllegalStateException(d.name() + ": " + d.vkFormat() + " lacks format features 0x" + Integer.toHexString(need & ~fp.optimalTilingFeatures())
                        + " on " + deviceName + " (FormatCaps runtime check)");
            boolean three = d.depth() > 1;
            LongBuffer lb = s.mallocLong(1);
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType$Default().imageType(three ? VK_IMAGE_TYPE_3D : VK_IMAGE_TYPE_2D)
                    .format(fmt).mipLevels(d.mips()).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().width(d.width()).height(d.height()).depth(d.depth());
            check(vkCreateImage(device, ci, null, lb), "vkCreateImage " + d.name());
            long image = lb.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(s);
            vkGetImageMemoryRequirements(device, image, req);
            long mem = allocate(req, 0, d.name());
            vkBindImageMemory(device, image, mem, 0);
            long viewAll = view(image, three, fmt, aspect, 0, d.mips());
            long viewMip0 = d.mips() == 1 ? viewAll : view(image, three, fmt, aspect, 0, 1);
            imgs.add(new Img(d, image, mem, viewAll, viewMip0, fmt, aspect, req.size()));
            int h = imgs.size() - 1;
            // GENERAL layout for its whole life, zeroed (GL drivers hand zeroed memory to packs)
            Consumer<VkCommandBuffer> init = c -> {
                layout(c, image, aspect, d.mips(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
                clearCmd(c, h, new float[]{0, 0, 0, 0});
            };
            if (recording)
                init.accept(cmd);
            else
                oneShot(init);
            return h;
        }
    }

    private long view(long image, boolean three, int fmt, int aspect, int base, int count) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(s).sType$Default().image(image)
                    .viewType(three ? VK_IMAGE_VIEW_TYPE_3D : VK_IMAGE_VIEW_TYPE_2D).format(fmt);
            vi.subresourceRange().aspectMask(aspect).baseMipLevel(base).levelCount(count).baseArrayLayer(0).layerCount(1);
            check(vkCreateImageView(device, vi, null, lb), "vkCreateImageView");
            return lb.get(0);
        }
    }

    private static void layout(VkCommandBuffer c, long image, int aspect, int mips, int from, int to) {
        try (MemoryStack s = stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s).sType$Default().oldLayout(from).newLayout(to)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image)
                    .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            b.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(mips).baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, b);
        }
    }

    private void oneShot(Consumer<VkCommandBuffer> rec) {
        try (MemoryStack s = stackPush()) {
            vkResetCommandBuffer(cmd, 0);
            vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            rec.accept(cmd);
            vkEndCommandBuffer(cmd);
            submitWait();
        }
    }

    private void submitWait() {
        try (MemoryStack s = stackPush()) {
            vkResetFences(device, fence);
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(cmd)), fence), "vkQueueSubmit");
            int r = vkWaitForFences(device, fence, true, 120_000_000_000L);
            if (r != VK_SUCCESS)
                throw new IllegalStateException("replay frame did not finish in 120 s (VkResult " + r + ")");
        }
    }

    @Override
    public ImageDesc desc(int image) {
        return imgs.get(image).d();
    }

    @Override
    public void upload(int image, float[] rgba) {
        Img im = imgs.get(image);
        ByteBuffer data = TexelCodec.encode(im.d().vkFormat(), rgba);
        Buf st = buffer(Math.max(16, data.remaining()), VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        st.mapped().put(0, data, 0, data.remaining());
        Consumer<VkCommandBuffer> c = cb -> {
            try (MemoryStack s = stackPush()) {
                VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
                r.imageSubresource().aspectMask(im.aspect()).mipLevel(0).baseArrayLayer(0).layerCount(1);
                r.imageExtent().width(im.d().width()).height(im.d().height()).depth(im.d().depth());
                vkCmdCopyBufferToImage(cb, st.buffer(), im.image(), VK_IMAGE_LAYOUT_GENERAL, r);
            }
        };
        if (recording) {
            c.accept(cmd);
            frameBufs.add(st);
        } else {
            oneShot(c);
            freeBuf(st);
        }
    }

    @Override
    public int createBuffer(String name, long bytes) {
        bufs.add(buffer(Math.max(16, bytes), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT));
        return bufs.size() - 1;
    }

    // ------------------------------------------------------------------------------------------------ pipelines

    private long module(byte[] spirv) {
        ByteBuffer code = MemoryUtil.memAlloc(spirv.length).put(spirv).flip();
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(code), null, lb), "shader module");
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
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b), null, lb), "set layout");
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

    @Override
    public int createPipeline(PipelineDesc d) {
        try (MemoryStack s = stackPush()) {
            long l0 = setLayout(d.slots(), 0), l1 = setLayout(d.slots(), 1);
            LongBuffer lb = s.mallocLong(1);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(l0, l1)), null, lb),
                    "pipeline layout " + d.id());
            long layout = lb.get(0);
            ByteBuffer main = s.UTF8("main");
            if (d.computeSpirv() != null) {
                long m = module(d.computeSpirv());
                VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s).sType$Default().layout(layout);
                ci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(m).pName(main);
                check(vkCreateComputePipelines(device, VK_NULL_HANDLE, ci, null, lb), "compute pipeline " + d.id());
                vkDestroyShaderModule(device, m, null);
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
            IntBuffer formats = s.mallocInt(n);
            for (int i = 0; i < n; i++) {
                String f = d.colourFormats().get(i);
                formats.put(i, vkFormat(f));
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
            VkPipelineRenderingCreateInfo ri = VkPipelineRenderingCreateInfo.calloc(s).sType$Default().colorAttachmentCount(n)
                    .pColorAttachmentFormats(formats);
            VkGraphicsPipelineCreateInfo.Buffer ci = VkGraphicsPipelineCreateInfo.calloc(1, s).sType$Default().pNext(ri.address())
                    .pStages(st).pVertexInputState(vis).pInputAssemblyState(ia).pViewportState(vp).pRasterizationState(rs)
                    .pMultisampleState(ms).pColorBlendState(cbs).pDynamicState(dyn).layout(layout);
            check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, ci, null, lb), "graphics pipeline " + d.id());
            vkDestroyShaderModule(device, vm, null);
            vkDestroyShaderModule(device, fm, null);
            pipes.add(new Pipe(d.id(), lb.get(0), layout, l0, l1, false));
            return pipes.size() - 1;
        }
    }

    // ------------------------------------------------------------------------------------------------ frame

    @Override
    public void beginFrame() {
        try (MemoryStack s = stackPush()) {
            vkResetDescriptorPool(device, descPool, 0);
            vkResetCommandBuffer(cmd, 0);
            vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            recording = true;
        }
    }

    @Override
    public void barrier(int image, String from, String to) {
        try (MemoryStack s = stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, b, null, null);
        }
    }

    private void clearCmd(VkCommandBuffer c, int image, float[] rgba) {
        Img im = imgs.get(image);
        try (MemoryStack s = stackPush()) {
            VkImageSubresourceRange r = VkImageSubresourceRange.calloc(s).aspectMask(im.aspect()).baseMipLevel(0)
                    .levelCount(im.d().mips()).baseArrayLayer(0).layerCount(1);
            if (im.aspect() == VK_IMAGE_ASPECT_DEPTH_BIT) {
                vkCmdClearDepthStencilImage(c, im.image(), VK_IMAGE_LAYOUT_GENERAL, VkClearDepthStencilValue.calloc(s).depth(rgba[0]), r);
            } else {
                VkClearColorValue cv = VkClearColorValue.calloc(s);
                if (im.d().vkFormat().endsWith("UINT") || im.d().vkFormat().endsWith("SINT"))
                    cv.uint32(0, Math.round(rgba[0])).uint32(1, Math.round(rgba[1])).uint32(2, Math.round(rgba[2])).uint32(3, Math.round(rgba[3]));
                else
                    cv.float32(0, rgba[0]).float32(1, rgba[1]).float32(2, rgba[2]).float32(3, rgba[3]);
                vkCmdClearColorImage(c, im.image(), VK_IMAGE_LAYOUT_GENERAL, cv, r);
            }
        }
    }

    @Override
    public void clear(int image, float[] rgba) {
        clearCmd(cmd, image, rgba);
    }

    @Override
    public void generateMips(int image) {
        Img im = imgs.get(image);
        try (MemoryStack s = stackPush()) {
            VkFormatProperties fp = VkFormatProperties.calloc(s);
            vkGetPhysicalDeviceFormatProperties(phys, im.format(), fp);
            int need = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if ((fp.optimalTilingFeatures() & need) != need) {
                log.add("mips of " + im.d().name() + " skipped: " + im.d().vkFormat() + " cannot be blitted linearly here");
                return;
            }
            int w = im.d().width(), h = im.d().height();
            for (int m = 1; m < im.d().mips(); m++) {
                int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
                VkImageBlit.Buffer b = VkImageBlit.calloc(1, s);
                b.srcSubresource().aspectMask(im.aspect()).mipLevel(m - 1).baseArrayLayer(0).layerCount(1);
                b.srcOffsets(1).set(w, h, 1);
                b.dstSubresource().aspectMask(im.aspect()).mipLevel(m).baseArrayLayer(0).layerCount(1);
                b.dstOffsets(1).set(nw, nh, 1);
                vkCmdBlitImage(cmd, im.image(), VK_IMAGE_LAYOUT_GENERAL, im.image(), VK_IMAGE_LAYOUT_GENERAL, b, VK_FILTER_LINEAR);
                barrier(image, "TRANSFER_WRITE", "TRANSFER_READ");
                w = nw;
                h = nh;
            }
        }
    }

    @Override
    public void copy(int src, int dst) {
        Img a = imgs.get(src), b = imgs.get(dst);
        try (MemoryStack s = stackPush()) {
            VkImageCopy.Buffer r = VkImageCopy.calloc(1, s);
            r.srcSubresource().aspectMask(a.aspect()).mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.dstSubresource().aspectMask(b.aspect()).mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.extent().width(a.d().width()).height(a.d().height()).depth(a.d().depth());
            vkCmdCopyImage(cmd, a.image(), VK_IMAGE_LAYOUT_GENERAL, b.image(), VK_IMAGE_LAYOUT_GENERAL, r);
        }
    }

    private long sampler(String kind) {
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
                check(vkCreateSampler(device, ci, null, lb), "sampler");
                return lb.get(0);
            }
        });
    }

    private long[] sets(Pipe p, Map<Integer, Bind> set0, Map<Integer, Bind> set1, byte[] ubo) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(2);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(descPool)
                    .pSetLayouts(s.longs(p.set0(), p.set1())), lb), "descriptor sets");
            long s0 = lb.get(0), s1 = lb.get(1);
            Buf u = buffer(Math.max(16, ubo.length), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            u.mapped().put(0, ubo);
            frameBufs.add(u);
            int count = 1 + set0.size() + set1.size();
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(count, s);
            w.get(0).sType$Default().dstSet(s0).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, s).buffer(u.buffer()).offset(0).range(VK_WHOLE_SIZE));
            int i = 1;
            for (int set = 0; set < 2; set++)
                for (Map.Entry<Integer, Bind> e : (set == 0 ? set0 : set1).entrySet()) {
                    Bind b = e.getValue();
                    VkWriteDescriptorSet x = w.get(i++).sType$Default().dstSet(set == 0 ? s0 : s1).dstBinding(e.getKey()).descriptorCount(1);
                    if (b.buffer() >= 0) {
                        x.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(VkDescriptorBufferInfo.calloc(1, s)
                                .buffer(bufs.get(b.buffer()).buffer()).offset(0).range(VK_WHOLE_SIZE));
                    } else {
                        Img im = imgs.get(b.image());
                        boolean storage = "STORAGE".equals(b.sampler());
                        VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, s)
                                .imageView(storage ? im.viewMip0() : im.viewAll()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                        if (!storage)
                            ii.sampler(sampler(b.sampler()));
                        x.descriptorType(storage ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(ii);
                    }
                    x.descriptorCount(1);
                }
            vkUpdateDescriptorSets(device, w, null);
            return new long[]{s0, s1};
        }
    }

    @Override
    public void draw(Draw d) {
        Pipe p = pipes.get(d.pipeline());
        long[] ds = sets(p, d.set0(), d.set1(), d.uniforms());
        try (MemoryStack s = stackPush()) {
            int n = d.colour().length;
            VkRenderingAttachmentInfo.Buffer att = VkRenderingAttachmentInfo.calloc(n, s);
            int aw = Integer.MAX_VALUE, ah = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                Img im = imgs.get(d.colour()[i]);
                att.get(i).sType$Default().imageView(im.viewMip0()).imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                aw = Math.min(aw, im.d().width());
                ah = Math.min(ah, im.d().height());
            }
            VkRenderingInfo ri = VkRenderingInfo.calloc(s).sType$Default().layerCount(1).pColorAttachments(att);
            ri.renderArea().extent().width(aw).height(ah);
            vkCmdBeginRendering(cmd, ri);
            int[] v = d.viewport();
            vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, s).x(v[0]).y(v[1]).width(v[2]).height(v[3]).minDepth(0).maxDepth(1));
            VkRect2D.Buffer sc = VkRect2D.calloc(1, s);
            sc.get(0).offset().set(v[0], v[1]);
            sc.get(0).extent().set(Math.min(v[2], aw), Math.min(v[3], ah));
            vkCmdSetScissor(cmd, 0, sc);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, p.pipeline());
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, p.layout(), 0, s.longs(ds[0], ds[1]), null);
            vkCmdBindVertexBuffers(cmd, 0, s.longs(quad.buffer()), s.longs(0));
            vkCmdDraw(cmd, 4, 1, 0, 0);
            vkCmdEndRendering(cmd);
        }
    }

    @Override
    public void dispatch(Dispatch d) {
        Pipe p = pipes.get(d.pipeline());
        long[] ds = sets(p, d.set0(), d.set1(), d.uniforms());
        try (MemoryStack s = stackPush()) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p.pipeline());
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p.layout(), 0, s.longs(ds[0], ds[1]), null);
            vkCmdDispatch(cmd, d.groups()[0], d.groups()[1], d.groups()[2]);
        }
    }

    @Override
    public void endFrame() {
        vkEndCommandBuffer(cmd);
        recording = false;
        submitWait();
        for (Buf b : frameBufs)
            freeBuf(b);
        frameBufs.clear();
    }

    @Override
    public float[] readback(int image) {
        Img im = imgs.get(image);
        long texels = (long) im.d().width() * im.d().height() * im.d().depth();
        int bpt = TexelCodec.bytes(im.d().vkFormat());
        Buf st = buffer(Math.max(16, texels * bpt), VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        oneShot(c -> {
            try (MemoryStack s = stackPush()) {
                VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
                r.imageSubresource().aspectMask(im.aspect()).mipLevel(0).baseArrayLayer(0).layerCount(1);
                r.imageExtent().width(im.d().width()).height(im.d().height()).depth(im.d().depth());
                vkCmdCopyImageToBuffer(c, im.image(), VK_IMAGE_LAYOUT_GENERAL, st.buffer(), r);
            }
        });
        float[] out = TexelCodec.decode(im.d().vkFormat(), st.mapped(), (int) texels);
        freeBuf(st);
        return out;
    }

    public long allocatedBytes() {
        return allocated;
    }

    @Override
    public void close() {
        vkDeviceWaitIdle(device);
        for (Pipe p : pipes) {
            vkDestroyPipeline(device, p.pipeline(), null);
            vkDestroyPipelineLayout(device, p.layout(), null);
            vkDestroyDescriptorSetLayout(device, p.set0(), null);
            vkDestroyDescriptorSetLayout(device, p.set1(), null);
        }
        for (long sm : samplers.values())
            vkDestroySampler(device, sm, null);
        for (Img im : imgs) {
            if (im.viewMip0() != im.viewAll())
                vkDestroyImageView(device, im.viewMip0(), null);
            vkDestroyImageView(device, im.viewAll(), null);
            vkDestroyImage(device, im.image(), null);
            vkFreeMemory(device, im.memory(), null);
        }
        for (Buf b : bufs)
            freeBuf(b);
        freeBuf(quad);
        vkDestroyDescriptorPool(device, descPool, null);
        vkDestroyFence(device, fence, null);
        vkDestroyCommandPool(device, cmdPool, null);
        vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
        memProps.free();
    }
}
