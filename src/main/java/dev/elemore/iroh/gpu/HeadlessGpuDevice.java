package dev.elemore.iroh.gpu;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

/**
 * A standalone headless Vulkan device for the offline api exerciser (./gradlew apiReplay) and Vista's GPU tests: no
 * window, no game, not Iroh's renderer. Approach copied from the pack replay harness (iroh-wt-p3 HeadlessVkBackend),
 * not its code: SAFETY: every physical device that is not a CPU implementation (lavapipe / llvmpipe) is refused, and
 * device memory is capped ({@link #cap}, 1 GiB by default), so a test can never touch the owner's GPU; the Gradle task
 * also restricts the loader to the lavapipe ICD (VK_DRIVER_FILES). One primary command buffer; {@link #flush} and
 * {@link #readback} submit and wait.
 */
public final class HeadlessGpuDevice extends VkGpuDevice implements AutoCloseable {
    private final VkInstance instance;
    private final VkPhysicalDevice phys;
    private final VkDevice device;
    private final VkQueue queue;
    private final long cmdPool, fence, descPool, sampler, samplerLinear;
    private final VkCommandBuffer cmd, uploadCmd;
    private final double timestampNs;
    private final VkPhysicalDeviceMemoryProperties memProps;
    private final boolean drawIndirectCount;
    /** P5-0: acceleration structures + ray query + buffer device address enabled (rtReplay). */
    public final boolean rayTracing;
    public final String deviceName;
    public final long cap;
    private long allocated;
    private boolean recording, uploadRecording;
    private final java.util.Map<String, VkImg> standIns = new java.util.HashMap<>();
    private VkImg mainColor, mainDepth;
    private final List<Runnable> later = new ArrayList<>();
    private final List<VkBuf> staging = new ArrayList<>();

    private record Mem(long memory, long size) {}

    /** {@code forceNoIndirectCount}: test the fallback path even where the feature exists. */
    public HeadlessGpuDevice(long capBytes, boolean forceNoIndirectCount) {
        this(capBytes, forceNoIndirectCount, false);
    }

    /**
     * {@code rayTracing}: also enable VK_KHR_acceleration_structure + VK_KHR_ray_query (+ deferred host operations) and
     * bufferDeviceAddress (P5-0 offline RT harness). Throws when the CPU device lacks them.
     */
    public HeadlessGpuDevice(long capBytes, boolean forceNoIndirectCount, boolean rayTracing) {
        this.cap = capBytes;
        this.rayTracing = rayTracing;
        try (MemoryStack s = stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(s).sType$Default().pApplicationName(s.UTF8("iroh-api-replay")).apiVersion(VK_API_VERSION_1_2);
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app);
            PointerBuffer pp = s.mallocPointer(1);
            check(vkCreateInstance(ici, null, pp), "vkCreateInstance");
            instance = new VkInstance(pp.get(0), ici);
            IntBuffer n = s.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, n, null);
            PointerBuffer devs = s.mallocPointer(Math.max(1, n.get(0)));
            vkEnumeratePhysicalDevices(instance, n, devs);
            VkPhysicalDevice chosen = null;
            String name = null;
            for (int i = 0; i < n.get(0); i++) {
                VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), instance);
                VkPhysicalDeviceProperties pr = VkPhysicalDeviceProperties.calloc(s);
                vkGetPhysicalDeviceProperties(pd, pr);
                if (pr.deviceType() == VK_PHYSICAL_DEVICE_TYPE_CPU) {
                    chosen = pd;
                    name = pr.deviceNameString();
                    break;
                }
            }
            if (chosen == null) {
                vkDestroyInstance(instance, null);
                throw new IllegalStateException("no CPU Vulkan device (lavapipe) found; the api exerciser never uses a GPU");
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
            VkPhysicalDeviceVulkan12Features have12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
            VkPhysicalDeviceFeatures2 have = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(have12.address());
            vkGetPhysicalDeviceFeatures2(phys, have);
            drawIndirectCount = have12.drawIndirectCount() && !forceNoIndirectCount;
            VkPhysicalDeviceVulkan12Features want12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default().drawIndirectCount(drawIndirectCount);
            VkPhysicalDeviceFeatures2 want = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(want12.address());
            want.features().multiDrawIndirect(have.features().multiDrawIndirect());
            PointerBuffer exts = null;
            if (rayTracing) {
                VkPhysicalDeviceAccelerationStructureFeaturesKHR haveAs = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(s).sType$Default();
                VkPhysicalDeviceRayQueryFeaturesKHR haveRq = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(s).sType$Default().pNext(haveAs.address());
                VkPhysicalDeviceFeatures2 have2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(haveRq.address());
                vkGetPhysicalDeviceFeatures2(phys, have2);
                if (!haveAs.accelerationStructure() || !haveRq.rayQuery() || !have12.bufferDeviceAddress()) {
                    vkDestroyInstance(instance, null);
                    throw new IllegalStateException("CPU Vulkan device lacks accelerationStructure/rayQuery/bufferDeviceAddress");
                }
                want12.bufferDeviceAddress(true).shaderInt8(have12.shaderInt8()).storageBuffer8BitAccess(have12.storageBuffer8BitAccess());
                want.features().shaderInt64(have.features().shaderInt64()).fragmentStoresAndAtomics(have.features().fragmentStoresAndAtomics())
                        .drawIndirectFirstInstance(have.features().drawIndirectFirstInstance());
                VkPhysicalDeviceAccelerationStructureFeaturesKHR wantAs = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(s).sType$Default()
                        .accelerationStructure(true);
                VkPhysicalDeviceRayQueryFeaturesKHR wantRq = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(s).sType$Default().rayQuery(true)
                        .pNext(wantAs.address());
                want12.pNext(wantRq.address());
                exts = s.pointers(s.UTF8(KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME),
                        s.UTF8(KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME),
                        s.UTF8(KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME));
            }
            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(family)
                    .pQueuePriorities(s.floats(1f));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pNext(want.address()).pQueueCreateInfos(qci)
                    .ppEnabledExtensionNames(exts);
            check(vkCreateDevice(phys, dci, null, pp), "vkCreateDevice");
            device = new VkDevice(pp.get(0), phys, dci);
            vkGetDeviceQueue(device, family, 0, pp);
            queue = new VkQueue(pp.get(0), device);
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(s).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, lb), "pool");
            cmdPool = lb.get(0);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(cmdPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pp), "cmd");
            cmd = new VkCommandBuffer(pp.get(0), device);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(cmdPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pp), "upload cmd");
            uploadCmd = new VkCommandBuffer(pp.get(0), device);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(s);
            vkGetPhysicalDeviceProperties(phys, props);
            timestampNs = qf.get(family).timestampValidBits() > 0 ? props.limits().timestampPeriod() : 0;
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(s).sType$Default(), null, lb), "fence");
            fence = lb.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, s);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(8192);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2048);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(2048).pPoolSizes(sizes), null, lb), "descriptor pool");
            descPool = lb.get(0);
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR).maxLod(VK_LOD_CLAMP_NONE), null, lb), "sampler");
            sampler = lb.get(0);
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR).maxLod(VK_LOD_CLAMP_NONE), null, lb), "sampler");
            samplerLinear = lb.get(0);
        }
    }

    @Override public String name() { return "headless:" + deviceName; }
    @Override public boolean supportsDeviceAddress() { return rayTracing; }
    @Override public boolean supportsDrawIndirectCount() { return drawIndirectCount; }
    @Override public long capBytes() { return cap; }
    @Override public boolean canRecordOutsideMainPass() { return !inRendering; }
    @Override protected VkDevice device() { return device; }
    @Override protected long pipelineCache() { return VK_NULL_HANDLE; }
    @Override protected long sampler(boolean linear) { return linear ? samplerLinear : sampler; }
    @Override protected double timestampPeriodNs() { return timestampNs; }
    @Override public int framesInFlight() { return 1; }
    @Override public int frameSlot() { return 0; }

    @Override
    protected VkImg resolveExternal(String key) {
        return standIns.get(key);
    }

    @Override
    public void setStandIn(String key, Object texture) {
        standIns.put(key, (VkImg) texture);
    }

    @Override
    public void setMainStandIn(Object color, Object depth) {
        mainColor = (VkImg) color;
        mainDepth = (VkImg) depth;
    }

    @Override public int mainColorFormat() { return mainColor == null ? 0 : mainColor.format; }
    @Override public int mainDepthFormat() { return mainDepth == null ? 0 : mainDepth.format; }

    @Override
    public void copyMainDepth(Object dst) {
        if (mainDepth == null) throw new UnsupportedOperationException("no main depth stand-in (GpuCore.standInMain)");
        copyImage(mainDepth, dst);
    }

    @Override
    public void beginMainRendering() {
        if (mainColor == null) throw new UnsupportedOperationException("no main stand-in (GpuCore.standInMain)");
        beginRendering(mainColor, mainDepth, new float[4], 1f, false);
    }

    @Override
    public void endMainRendering() {
        endRendering();
    }

    @Override
    public void runOutsideMainPass(Runnable work) {
        work.run(); // offline there is no main pass to suspend
    }

    /**
     * Uploads go into a separate command buffer submitted BEFORE the frame's (see {@link #flush}): the game's order (Iroh's
     * UploadManager submits a frame's uploads ahead of the frame), so a replay catches code that relies on in-stream order.
     */
    private VkCommandBuffer uploadCmd() {
        if (!uploadRecording) {
            try (MemoryStack s = stackPush()) {
                vkResetCommandBuffer(uploadCmd, 0);
                vkBeginCommandBuffer(uploadCmd, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            }
            uploadRecording = true;
        }
        return uploadCmd;
    }

    @Override
    protected VkCommandBuffer cmd() {
        if (!recording) {
            try (MemoryStack s = stackPush()) {
                vkResetCommandBuffer(cmd, 0);
                vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            }
            recording = true;
        }
        return cmd;
    }

    @Override
    protected long allocateSet(long layout) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(descPool)
                    .pSetLayouts(s.longs(layout)), lb), "descriptor set");
            return lb.get(0);
        }
    }

    private int memoryType(int bits, int flags) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++)
            if ((bits & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & flags) == flags) return i;
        throw new IllegalStateException("no memory type for bits " + bits + " flags " + flags);
    }

    private long allocate(VkMemoryRequirements req, int flags, String what) {
        return allocate(req, flags, what, false);
    }

    private long allocate(VkMemoryRequirements req, int flags, String what, boolean deviceAddress) {
        if (allocated + req.size() > cap)
            throw new IllegalStateException("headless device memory cap " + (cap >> 20) + " MiB reached allocating " + what);
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(req.size())
                    .memoryTypeIndex(memoryType(req.memoryTypeBits(), flags));
            if (deviceAddress)
                ai.pNext(VkMemoryAllocateFlagsInfo.calloc(s).sType$Default().flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT).address());
            check(vkAllocateMemory(device, ai, null, lb), "vkAllocateMemory " + what);
            allocated += req.size();
            return lb.get(0);
        }
    }

    private VkBuf buffer(long bytes, int usage, int memFlags) {
        boolean bda = rayTracing && (usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0;
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(s).sType$Default().size(bytes).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, lb), "vkCreateBuffer");
            long b = lb.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(s);
            vkGetBufferMemoryRequirements(device, b, req);
            long mem = allocate(req, memFlags, "buffer", bda);
            vkBindBufferMemory(device, b, mem, 0);
            return new VkBuf(b, bytes, new Mem(mem, req.size()));
        }
    }

    @Override
    protected VkBuf allocBuffer(long bytes, int usage) {
        return buffer(bytes, usage, 0); // lavapipe: every type is host memory; device-local is not required here
    }

    private void freeBuf(VkBuf b) {
        Mem m = (Mem) b.alloc();
        vkDestroyBuffer(device, b.buffer(), null);
        vkFreeMemory(device, m.memory(), null);
        allocated -= m.size();
    }

    @Override
    public void destroyBuffer(Object buffer) {
        VkBuf b = (VkBuf) buffer;
        later.add(() -> freeBuf(b));
    }

    @Override
    public void upload(Object buffer, long offset, ByteBuffer src) {
        int len = src.remaining();
        VkBuf st = buffer(len, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        try (MemoryStack s = stackPush()) {
            PointerBuffer pp = s.mallocPointer(1);
            vkMapMemory(device, ((Mem) st.alloc()).memory(), 0, len, 0, pp);
            MemoryUtil.memCopy(MemoryUtil.memAddress(src), pp.get(0), len);
            vkUnmapMemory(device, ((Mem) st.alloc()).memory());
            vkCmdCopyBuffer(uploadCmd(), st.buffer(), ((VkBuf) buffer).buffer(), VkBufferCopy.calloc(1, s).srcOffset(0).dstOffset(offset).size(len));
        }
        staging.add(st);
    }

    @Override
    protected VkImg allocImage(String name, int width, int height, int format, int usage, int aspect) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format).mipLevels(1)
                    .arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().width(width).height(height).depth(1);
            check(vkCreateImage(device, ci, null, lb), "vkCreateImage " + name);
            long image = lb.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(s);
            vkGetImageMemoryRequirements(device, image, req);
            long mem = allocate(req, 0, name);
            vkBindImageMemory(device, image, mem, 0);
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(s).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            vi.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            check(vkCreateImageView(device, vi, null, lb), "view " + name);
            return new VkImg(image, lb.get(0), format, width, height, aspect, new Mem(mem, req.size()));
        }
    }

    @Override
    protected VkImg allocTexture(String name, int width, int height, int levels, boolean linear) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(levels).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().width(width).height(height).depth(1);
            check(vkCreateImage(device, ci, null, lb), "vkCreateImage " + name);
            long image = lb.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(s);
            vkGetImageMemoryRequirements(device, image, req);
            long mem = allocate(req, 0, name);
            vkBindImageMemory(device, image, mem, 0);
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(s).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM);
            vi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(levels).baseArrayLayer(0).layerCount(1);
            check(vkCreateImageView(device, vi, null, lb), "view " + name);
            return new VkImg(image, lb.get(0), VK_FORMAT_R8G8B8A8_UNORM, width, height, VK_IMAGE_ASPECT_COLOR_BIT, levels, linear,
                    VK_IMAGE_LAYOUT_GENERAL, new Mem(mem, req.size()));
        }
    }

    @Override
    public void uploadImage(Object image, ByteBuffer texels) {
        VkImg im = (VkImg) image;
        int bpp = 4;
        long need = 0;
        for (int l = 0; l < im.levels; l++) need += (long) Math.max(1, im.width >> l) * Math.max(1, im.height >> l) * bpp;
        if (texels.remaining() < need) throw new IllegalArgumentException("image " + im.width + "x" + im.height + " x" + im.levels + " needs " + need + " bytes");
        VkBuf st = buffer(need, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        try (MemoryStack s = stackPush()) {
            PointerBuffer pp = s.mallocPointer(1);
            vkMapMemory(device, ((Mem) st.alloc()).memory(), 0, need, 0, pp);
            MemoryUtil.memCopy(MemoryUtil.memAddress(texels), pp.get(0), need);
            vkUnmapMemory(device, ((Mem) st.alloc()).memory());
            barrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT);
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(im.levels, s);
            long off = 0;
            for (int l = 0; l < im.levels; l++) {
                int w = Math.max(1, im.width >> l), h = Math.max(1, im.height >> l);
                r.get(l).bufferOffset(off);
                r.get(l).imageSubresource().aspectMask(im.aspect).mipLevel(l).baseArrayLayer(0).layerCount(1);
                r.get(l).imageExtent().width(w).height(h).depth(1);
                off += (long) w * h * bpp;
            }
            vkCmdCopyBufferToImage(cmd(), st.buffer(), im.image, VK_IMAGE_LAYOUT_GENERAL, r);
            barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        }
        staging.add(st);
    }

    @Override
    public void destroyImage(Object image) {
        VkImg img = (VkImg) image;
        forgetImage(img);
        later.add(() -> {
            vkDestroyImageView(device, img.view, null);
            vkDestroyImage(device, img.image, null);
            Mem m = (Mem) img.alloc;
            vkFreeMemory(device, m.memory(), null);
            allocated -= m.size();
        });
    }

    @Override
    protected void deferFree(Runnable r) {
        later.add(r);
    }

    @Override
    public void flush() {
        if (recording || uploadRecording) {
            try (MemoryStack s = stackPush()) {
                cmd(); // a frame with uploads only still submits an (empty) frame buffer after them
                vkEndCommandBuffer(cmd);
                recording = false;
                PointerBuffer bufs;
                if (uploadRecording) {
                    vkEndCommandBuffer(uploadCmd);
                    uploadRecording = false;
                    bufs = s.pointers(uploadCmd, cmd); // the frame's uploads first (game order)
                } else bufs = s.pointers(cmd);
                vkResetFences(device, fence);
                check(vkQueueSubmit(queue, VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(bufs), fence), "vkQueueSubmit");
                int r = vkWaitForFences(device, fence, true, 120_000_000_000L);
                if (r != VK_SUCCESS) throw new IllegalStateException("headless work did not finish in 120 s (VkResult " + r + ")");
            }
            vkResetDescriptorPool(device, descPool, 0);
        }
        for (VkBuf b : staging) freeBuf(b);
        staging.clear();
        for (Runnable r : later) r.run();
        later.clear();
    }

    @Override
    public ByteBuffer readback(Object image) {
        VkImg im = (VkImg) image;
        long bytes = (long) im.width * im.height * texelBytes(im.format);
        VkBuf st = buffer(bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        try (MemoryStack s = stackPush()) {
            barrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.imageSubresource().aspectMask(im.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.imageExtent().width(im.width).height(im.height).depth(1);
            vkCmdCopyImageToBuffer(cmd(), im.image, VK_IMAGE_LAYOUT_GENERAL, st.buffer(), r);
        }
        flush();
        ByteBuffer out = ByteBuffer.allocateDirect((int) bytes);
        try (MemoryStack s = stackPush()) {
            PointerBuffer pp = s.mallocPointer(1);
            vkMapMemory(device, ((Mem) st.alloc()).memory(), 0, bytes, 0, pp);
            MemoryUtil.memCopy(pp.get(0), MemoryUtil.memAddress(out), bytes);
            vkUnmapMemory(device, ((Mem) st.alloc()).memory());
        }
        freeBuf(st);
        return out;
    }

    private static int texelBytes(int format) {
        return switch (format) {
            case VK_FORMAT_R32G32B32A32_UINT, VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_R32G32B32A32_SINT -> 16;
            case VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32_UINT -> 8;
            default -> 4;
        };
    }

    /** Offline (P5-0): records a copy of a colour/depth image (GENERAL layout) into a buffer, tightly packed. */
    public void copyImageToBuffer(Object image, Object buffer) {
        VkImg im = (VkImg) image;
        try (MemoryStack s = stackPush()) {
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.imageSubresource().aspectMask(im.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.imageExtent().width(im.width).height(im.height).depth(1);
            vkCmdCopyImageToBuffer(cmd(), im.image, VK_IMAGE_LAYOUT_GENERAL, ((VkBuf) buffer).buffer(), r);
        }
    }

    /** Offline: reads a buffer back (tests). */
    public ByteBuffer readBuffer(Object buffer, long offset, int bytes) {
        VkBuf st = buffer(bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        try (MemoryStack s = stackPush()) {
            barrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdCopyBuffer(cmd(), ((VkBuf) buffer).buffer(), st.buffer(), VkBufferCopy.calloc(1, s).srcOffset(offset).dstOffset(0).size(bytes));
        }
        flush();
        ByteBuffer out = ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        try (MemoryStack s = stackPush()) {
            PointerBuffer pp = s.mallocPointer(1);
            vkMapMemory(device, ((Mem) st.alloc()).memory(), 0, bytes, 0, pp);
            MemoryUtil.memCopy(pp.get(0), MemoryUtil.memAddress(out), bytes);
            vkUnmapMemory(device, ((Mem) st.alloc()).memory());
        }
        freeBuf(st);
        return out;
    }

    public long allocatedBytes() {
        return allocated;
    }

    @Override
    public void close() {
        flush();
        vkDeviceWaitIdle(device);
        destroyShared();
        vkDestroySampler(device, sampler, null);
        vkDestroySampler(device, samplerLinear, null);
        vkDestroyDescriptorPool(device, descPool, null);
        vkDestroyFence(device, fence, null);
        vkDestroyCommandPool(device, cmdPool, null);
        vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
        memProps.free();
    }
}
