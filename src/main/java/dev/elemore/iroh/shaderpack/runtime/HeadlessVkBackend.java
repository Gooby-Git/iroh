package dev.elemore.iroh.shaderpack.runtime;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * A standalone headless Vulkan device for the offline replay (no window, no swapchain, no game, not Iroh's renderer).
 * SAFETY: it refuses every physical device that is not a CPU implementation (lavapipe / llvmpipe) unless explicitly
 * allowed, and it caps its own device memory ({@link #cap}, 1 GiB by default): a replay can never touch the owner's
 * GPU. The harness also restricts the Vulkan loader to the lavapipe ICD (VK_DRIVER_FILES).
 * <p>
 * One primary command buffer: setup commands (image creation, uploads) and the frame are recorded into it as they
 * come; {@link #endFrame} / {@link #readback} submit and wait. The draw code is {@link VkPackBackend}'s, shared with
 * the in-game backend.
 */
public final class HeadlessVkBackend extends VkPackBackend {

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
    private final List<HostBuf> frameBufs = new ArrayList<>();
    private final List<Runnable> later = new ArrayList<>();

    private record Mem(long memory, long size) {}

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
            // enable the core 1.0 features the device supports (the in-game device does the same for what packs need)
            VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default();
            vkGetPhysicalDeviceFeatures2(phys, f2);
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
            descPool = createDescriptorPool(device);
        }
        initQuad();
    }

    static long createDescriptorPool(VkDevice device) {
        try (MemoryStack s = stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(4, s);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(4096);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(16384);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4096);
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1024);
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1024).pPoolSizes(sizes),
                    null, lb), "descriptor pool");
            return lb.get(0);
        }
    }

    @Override
    public String name() {
        return "headless:" + deviceName;
    }

    @Override
    protected VkDevice device() {
        return device;
    }

    @Override
    protected VkPhysicalDevice phys() {
        return phys;
    }

    @Override
    protected long pipelineCache() {
        return VK_NULL_HANDLE;
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
    protected long descriptorPool() {
        return descPool;
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

    @Override
    protected Object[] allocImage(ImageDesc d, int fmt, int usage, boolean three) {
        try (MemoryStack s = stackPush()) {
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
            return new Object[]{image, new Mem(mem, req.size())};
        }
    }

    @Override
    protected void releaseImage(long image, Object alloc, long[] views) {
        later.add(() -> {
            for (long v : views)
                vkDestroyImageView(device, v, null);
            vkDestroyImage(device, image, null);
            Mem m = (Mem) alloc;
            vkFreeMemory(device, m.memory(), null);
            allocated -= m.size();
        });
    }

    @Override
    protected void releaseLater(Runnable r) {
        later.add(r);
    }

    @Override
    protected HostBuf hostBuffer(long size, int usage) {
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
            return new HostBuf(b, new Mem(mem, req.size()), size, MemoryUtil.memByteBuffer(pp.get(0), (int) size));
        }
    }

    @Override
    protected void freeHostBuffer(HostBuf b) {
        Mem m = (Mem) b.alloc();
        vkUnmapMemory(device, m.memory());
        vkDestroyBuffer(device, b.buffer(), null);
        vkFreeMemory(device, m.memory(), null);
        allocated -= m.size();
    }

    @Override
    protected void freeHostBufferAfterFrame(HostBuf b) {
        frameBufs.add(b);
    }

    // ------------------------------------------------------------------------------------------------ frame

    private void submitWait() {
        if (!recording)
            return;
        try (MemoryStack s = stackPush()) {
            vkEndCommandBuffer(cmd);
            recording = false;
            vkResetFences(device, fence);
            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(cmd)), fence), "vkQueueSubmit");
            int r = vkWaitForFences(device, fence, true, 120_000_000_000L);
            if (r != VK_SUCCESS)
                throw new IllegalStateException("replay frame did not finish in 120 s (VkResult " + r + ")");
        }
        for (HostBuf b : frameBufs)
            freeHostBuffer(b);
        frameBufs.clear();
        for (Runnable r : later)
            r.run();
        later.clear();
    }

    @Override
    public void beginFrame() {
        submitWait(); // setup work first, so the descriptor pool reset below cannot hit in-flight sets
        vkResetDescriptorPool(device, descPool, 0);
        cmd();
    }

    @Override
    public void endFrame() {
        submitWait();
    }

    @Override
    public float[] readback(int image) {
        submitWait();
        Img im = img(image);
        long texels = (long) im.d.width() * im.d.height() * im.d.depth();
        int bpt = TexelCodec.bytes(im.d.vkFormat());
        HostBuf st = hostBuffer(Math.max(16, texels * bpt), VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        try (MemoryStack s = stackPush()) {
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.imageSubresource().aspectMask(im.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.imageExtent().width(im.d.width()).height(im.d.height()).depth(im.d.depth());
            vkCmdCopyImageToBuffer(cmd(), im.image, VK_IMAGE_LAYOUT_GENERAL, st.buffer(), r);
        }
        submitWait();
        float[] out = TexelCodec.decode(im.d.vkFormat(), st.mapped(), (int) texels);
        freeHostBuffer(st);
        return out;
    }

    public long allocatedBytes() {
        return allocated;
    }

    @Override
    public void close() {
        submitWait();
        destroyShared();
        submitWait();
        for (Runnable r : later)
            r.run();
        later.clear();
        vkDeviceWaitIdle(device);
        vkDestroyDescriptorPool(device, descPool, null);
        vkDestroyFence(device, fence, null);
        vkDestroyCommandPool(device, cmdPool, null);
        vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
        memProps.free();
    }
}
