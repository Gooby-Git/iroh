package dev.elemore.iroh.rttest;

import dev.elemore.iroh.rt.denoise.DenoiseGpu;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Headless lavapipe device for the offline harnesses (NEVER a GPU): refuses to run unless VK_DRIVER_FILES names the lavapipe ICD,
 * picks only a CPU device, caps its own device memory at 1 GiB. Implements {@link DenoiseGpu}.
 */
public final class LvpDevice implements DenoiseGpu {
    public static final long CAP = 1L << 30;
    public VkInstance instance;
    public VkPhysicalDevice phys;
    public VkDevice device;
    public VkQueue queue;
    public int family;
    public long pool, fence;
    public VkCommandBuffer cmd;
    public VkPhysicalDeviceMemoryProperties memProps;
    public String deviceName;
    public long allocated;
    public final List<String> report = new ArrayList<>();
    public boolean computeDerivativesQuads;
    public boolean hasComputeDerivativesExt;
    public int subgroupSize;

    public LvpDevice(boolean wantComputeDerivatives) {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp")) throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at the lavapipe ICD (use the gradle task)");
        try (MemoryStack s = stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(s).sType$Default().pApplicationName(s.UTF8("iroh-denoise-replay")).apiVersion(VK_API_VERSION_1_2);
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app);
            PointerBuffer pp = s.mallocPointer(1);
            vk(vkCreateInstance(ici, null, pp), "vkCreateInstance");
            instance = new VkInstance(pp.get(0), ici);
            IntBuffer n = s.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, n, null);
            PointerBuffer devs = s.mallocPointer(n.get(0));
            vkEnumeratePhysicalDevices(instance, n, devs);
            for (int i = 0; i < devs.capacity(); i++) {
                VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), instance);
                VkPhysicalDeviceProperties pr = VkPhysicalDeviceProperties.calloc(s);
                vkGetPhysicalDeviceProperties(pd, pr);
                if (pr.deviceType() == VK_PHYSICAL_DEVICE_TYPE_CPU) {
                    phys = pd;
                    deviceName = pr.deviceNameString() + " api " + VK_VERSION_MAJOR(pr.apiVersion()) + "." + VK_VERSION_MINOR(pr.apiVersion());
                    break;
                }
            }
            if (phys == null) throw new IllegalStateException("no CPU Vulkan device (lavapipe): this harness never uses a GPU");
            memProps = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(phys, memProps);
            vkEnumerateDeviceExtensionProperties(phys, (String) null, n, null);
            VkExtensionProperties.Buffer exts = VkExtensionProperties.calloc(n.get(0), s);
            vkEnumerateDeviceExtensionProperties(phys, (String) null, n, exts);
            for (int i = 0; i < exts.capacity(); i++) if (exts.get(i).extensionNameString().equals("VK_KHR_compute_shader_derivatives")) hasComputeDerivativesExt = true;
            VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default();
            // raw VkPhysicalDeviceComputeShaderDerivativesFeaturesKHR (LWJGL 3.3.3 only has the NV variant): sType 1000511000, pNext, 2 x VkBool32
            ByteBuffer cd = s.calloc(8, 24);
            cd.putInt(0, 1000511000);
            if (hasComputeDerivativesExt) f2.pNext(MemoryUtil.memAddress(cd));
            vkGetPhysicalDeviceFeatures2(phys, f2);
            VkPhysicalDeviceFeatures ft = f2.features();
            boolean quads = hasComputeDerivativesExt && cd.getInt(16) != 0;
            computeDerivativesQuads = quads;
            VkPhysicalDeviceSubgroupProperties sg = VkPhysicalDeviceSubgroupProperties.calloc(s).sType$Default();
            VkPhysicalDeviceProperties2 p2 = VkPhysicalDeviceProperties2.calloc(s).sType$Default().pNext(sg.address());
            vkGetPhysicalDeviceProperties2(phys, p2);
            subgroupSize = sg.subgroupSize();
            report.add("device: " + deviceName);
            report.add(String.format("features: storageImageExtendedFormats=%b writeWithoutFormat=%b readWithoutFormat=%b imageQuery(core)", ft.shaderStorageImageExtendedFormats(), ft.shaderStorageImageWriteWithoutFormat(), ft.shaderStorageImageReadWithoutFormat()));
            report.add(String.format("VK_KHR_compute_shader_derivatives: extension=%b computeDerivativeGroupQuads=%b; subgroup size %d quad=%b", hasComputeDerivativesExt, quads, sg.subgroupSize(), (sg.supportedOperations() & VK_SUBGROUP_FEATURE_QUAD_BIT) != 0));
            vkGetPhysicalDeviceQueueFamilyProperties(phys, n, null);
            VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.calloc(n.get(0), s);
            vkGetPhysicalDeviceQueueFamilyProperties(phys, n, qf);
            family = -1;
            for (int i = 0; i < qf.capacity(); i++) if ((qf.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) { family = i; break; }
            VkPhysicalDeviceFeatures2 e2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default();
            e2.features().shaderStorageImageExtendedFormats(ft.shaderStorageImageExtendedFormats()).shaderStorageImageWriteWithoutFormat(ft.shaderStorageImageWriteWithoutFormat())
                    .shaderStorageImageReadWithoutFormat(ft.shaderStorageImageReadWithoutFormat());
            ByteBuffer ecd = s.calloc(8, 24);
            ecd.putInt(0, 1000511000);
            ecd.putInt(16, quads && wantComputeDerivatives ? 1 : 0);
            PointerBuffer extNames = null;
            if (quads && wantComputeDerivatives) {
                e2.pNext(MemoryUtil.memAddress(ecd));
                extNames = s.pointers(s.UTF8("VK_KHR_compute_shader_derivatives"));
            }
            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(family).pQueuePriorities(s.floats(1f));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pNext(e2.address()).pQueueCreateInfos(qci);
            if (extNames != null) dci.ppEnabledExtensionNames(extNames);
            vk(vkCreateDevice(phys, dci, null, pp), "vkCreateDevice");
            device = new VkDevice(pp.get(0), phys, dci);
            vkGetDeviceQueue(device, family, 0, pp);
            queue = new VkQueue(pp.get(0), device);
            LongBuffer lb = s.mallocLong(1);
            vk(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(s).sType$Default().flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, lb), "pool");
            pool = lb.get(0);
            vk(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pp), "cmd");
            cmd = new VkCommandBuffer(pp.get(0), device);
            vk(vkCreateFence(device, VkFenceCreateInfo.calloc(s).sType$Default(), null, lb), "fence");
            fence = lb.get(0);
        }
    }

    static void vk(int r, String what) {
        if (r != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + r);
    }

    int memType(int bits, int props) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++) if ((bits & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & props) == props) return i;
        throw new IllegalStateException("no memory type");
    }

    @Override
    public VkDevice device() {
        return device;
    }

    @Override
    public VkPhysicalDevice physicalDevice() {
        return phys;
    }

    @Override
    public Img createImage(int fmt, int w, int h, int usage, String name) {
        try (MemoryStack s = stackPush()) {
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(fmt).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().set(w, h, 1);
            LongBuffer lb = s.mallocLong(1);
            vk(vkCreateImage(device, ci, null, lb), "vkCreateImage " + name + " fmt " + fmt);
            long img = lb.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(s);
            vkGetImageMemoryRequirements(device, img, mr);
            if (allocated + mr.size() > CAP) throw new IllegalStateException("1 GiB device memory cap reached");
            allocated += mr.size();
            vk(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(mr.size()).memoryTypeIndex(memType(mr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)), null, lb), "alloc image");
            long mem = lb.get(0);
            vk(vkBindImageMemory(device, img, mem, 0), "bind");
            return new Img(img, mem, mr.size());
        }
    }

    @Override
    public void destroyImage(Img i) {
        vkDestroyImage(device, i.image(), null);
        vkFreeMemory(device, i.memory(), null);
        allocated -= i.bytes();
    }

    public Buf hostBuffer(long size, int usage) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            vk(vkCreateBuffer(device, VkBufferCreateInfo.calloc(s).sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, lb), "vkCreateBuffer");
            long b = lb.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(s);
            vkGetBufferMemoryRequirements(device, b, mr);
            if (allocated + mr.size() > CAP) throw new IllegalStateException("1 GiB device memory cap reached");
            allocated += mr.size();
            vk(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(mr.size()).memoryTypeIndex(memType(mr.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)), null, lb), "alloc buffer");
            long mem = lb.get(0);
            vk(vkBindBufferMemory(device, b, mem, 0), "bind buffer");
            PointerBuffer pp = s.mallocPointer(1);
            vk(vkMapMemory(device, mem, 0, size, 0, pp), "map");
            return new Buf(b, mem, pp.get(0), mr.size());
        }
    }

    @Override
    public Buf createUniformBuffer(long size) {
        return hostBuffer(size, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
    }

    @Override
    public void destroyBuffer(Buf b) {
        vkDestroyBuffer(device, b.buffer(), null);
        vkFreeMemory(device, b.memory(), null);
        allocated -= b.size();
    }

    public void begin() {
        try (MemoryStack s = stackPush()) {
            vkResetCommandBuffer(cmd, 0);
            vk(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin");
        }
    }

    public void submitWait() {
        try (MemoryStack s = stackPush()) {
            vk(vkEndCommandBuffer(cmd), "end");
            vk(vkQueueSubmit(queue, VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(cmd)), fence), "submit");
            vk(vkWaitForFences(device, s.longs(fence), true, 300_000_000_000L), "wait");
            vkResetFences(device, s.longs(fence));
        }
    }

    public void layout(long image, int from, int to) {
        try (MemoryStack s = stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s).sType$Default().oldLayout(from).newLayout(to).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image).srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT).dstAccessMask(VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT);
            b.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, b);
        }
    }

    /** upload a buffer into an image (any layout -> GENERAL). */
    public void upload(long image, int w, int h, Buf b) {
        layout(image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack s = stackPush()) {
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            r.imageExtent().set(w, h, 1);
            vkCmdCopyBufferToImage(cmd, b.buffer(), image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, r);
        }
        layout(image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
    }

    public void download(long image, int w, int h, Buf b) {
        layout(image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        try (MemoryStack s = stackPush()) {
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            r.imageExtent().set(w, h, 1);
            vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, b.buffer(), r);
        }
        layout(image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
    }

    /** copy a small image (any size) into a buffer; the image is assumed to be in GENERAL and is left there. */
    public void downloadSized(long image, int w, int h, Buf b, int unused) {
        download(image, w, h, b);
    }

    public static ByteBuffer bytes(Buf b) {
        return MemoryUtil.memByteBuffer(b.ptr(), (int) b.size()).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** P5-1: tear the device down (the RT harness opens several devices in one JVM). */
    public void close() {
        vkDeviceWaitIdle(device);
        vkDestroyFence(device, fence, null);
        vkDestroyCommandPool(device, pool, null);
        vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
    }
}
