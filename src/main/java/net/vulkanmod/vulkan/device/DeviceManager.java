package net.vulkanmod.vulkan.device;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.queue.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static java.util.stream.Collectors.toSet;
import static net.vulkanmod.vulkan.queue.Queue.findQueueFamilies;
import static net.vulkanmod.vulkan.util.VUtil.asPointerBuffer;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public abstract class DeviceManager {
    public static List<Device> availableDevices;
    public static List<Device> suitableDevices;

    public static VkPhysicalDevice physicalDevice;
    public static VkDevice vkDevice;

    public static Device device;

    public static VkPhysicalDeviceProperties deviceProperties;
    public static VkPhysicalDeviceMemoryProperties memoryProperties;

    public static SurfaceProperties surfaceProperties;

    static GraphicsQueue graphicsQueue;
    static PresentQueue presentQueue;
    static TransferQueue transferQueue;
    static ComputeQueue computeQueue;

    public static void init(VkInstance instance) {
        try {
            DeviceManager.getSuitableDevices(instance);
            DeviceManager.pickPhysicalDevice();
            DeviceManager.createLogicalDevice();
        } catch (Exception e) {
            Initializer.LOGGER.info(getAvailableDevicesInfo());
            throw new RuntimeException(e);
        }
    }

    static List<Device> getAvailableDevices(VkInstance instance) {
        try (MemoryStack stack = stackPush()) {
            List<Device> devices = new ObjectArrayList<>();

            IntBuffer deviceCount = stack.ints(0);

            vkEnumeratePhysicalDevices(instance, deviceCount, null);

            if (deviceCount.get(0) == 0) {
                return List.of();
            }

            PointerBuffer ppPhysicalDevices = stack.mallocPointer(deviceCount.get(0));
            vkEnumeratePhysicalDevices(instance, deviceCount, ppPhysicalDevices);

            VkPhysicalDevice currentDevice;

            for (int i = 0; i < ppPhysicalDevices.capacity(); i++) {
                currentDevice = new VkPhysicalDevice(ppPhysicalDevices.get(i), instance);

                Device device = new Device(currentDevice);
                devices.add(device);
            }

            return devices;
        }
    }

    static void getSuitableDevices(VkInstance instance) {
        availableDevices = getAvailableDevices(instance);

        List<Device> devices = new ObjectArrayList<>();
        for (Device device : availableDevices) {
            if (isDeviceSuitable(device.physicalDevice)) {
                devices.add(device);
            }
        }

        suitableDevices = devices;
    }

    public static void pickPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {

            int deviceIdx = Initializer.CONFIG.device;
            if (deviceIdx >= 0 && deviceIdx < suitableDevices.size())
                DeviceManager.device = suitableDevices.get(deviceIdx);
            else {
                DeviceManager.device = autoPickDevice();
                Initializer.CONFIG.device = -1;
            }

            physicalDevice = DeviceManager.device.physicalDevice;

            // Get device properties
            deviceProperties = device.properties;

            memoryProperties = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);

            surfaceProperties = querySurfaceProperties(physicalDevice, stack);
        }
    }

    static Device autoPickDevice() {
        ArrayList<Device> integratedGPUs = new ArrayList<>();
        ArrayList<Device> otherDevices = new ArrayList<>();

        boolean flag = false;

        Device currentDevice = null;
        for (Device device : suitableDevices) {
            currentDevice = device;

            int deviceType = device.properties.deviceType();
            if (deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                flag = true;
                break;
            } else if (deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU)
                integratedGPUs.add(device);
            else
                otherDevices.add(device);
        }

        if (!flag) {
            if (!integratedGPUs.isEmpty())
                currentDevice = integratedGPUs.get(0);
            else if (!otherDevices.isEmpty())
                currentDevice = otherDevices.get(0);
            else {
                throw new IllegalStateException("Failed to find a suitable GPU");
            }
        }

        return currentDevice;
    }

    public static void createLogicalDevice() {
        try (MemoryStack stack = stackPush()) {

            net.vulkanmod.vulkan.queue.Queue.QueueFamilyIndices indices = findQueueFamilies(physicalDevice);

            int[] uniqueQueueFamilies = indices.unique();

            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueQueueFamilies.length, stack);

            for (int i = 0; i < uniqueQueueFamilies.length; i++) {
                VkDeviceQueueCreateInfo queueCreateInfo = queueCreateInfos.get(i);
                queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
                queueCreateInfo.queueFamilyIndex(uniqueQueueFamilies[i]);
                queueCreateInfo.pQueuePriorities(stack.floats(1.0f));
            }

            VkPhysicalDeviceVulkan11Features deviceVulkan11Features = VkPhysicalDeviceVulkan11Features.calloc(stack);
            deviceVulkan11Features.sType$Default();

            // api 4 (card V-M1): drawIndirectCount when the device has it (Vulkan 1.2 core; Iroh's instance is 1.2)
            VkPhysicalDeviceVulkan12Features deviceVulkan12Features = VkPhysicalDeviceVulkan12Features.calloc(stack);
            deviceVulkan12Features.sType$Default();
            drawIndirectCountEnabled = device.availableFeatures12.drawIndirectCount();
            deviceVulkan12Features.drawIndirectCount(drawIndirectCountEnabled);

            VkPhysicalDeviceFeatures2 deviceFeatures = VkPhysicalDeviceFeatures2.calloc(stack);
            deviceFeatures.sType$Default();
            // V-4 (td-0fa7b8): the Vulkan 1.1/1.2 feature structs hang off VkPhysicalDeviceFeatures2 (pEnabledFeatures stays NULL).
            // Root cause of the Float16 validation errors: createInfo.pNext(deviceVulkan11Features) is LWJGL's typed overload and
            // OVERWRITES the struct's own pNext, so the 1.2 struct (shaderFloat16, drawIndirectCount) was never in the device chain.
            dev.elemore.iroh.temporal.DeviceCaps.linkFeatureChain(deviceFeatures, deviceVulkan11Features, deviceVulkan12Features);
            deviceFeatures.features().samplerAnisotropy(device.availableFeatures.features().samplerAnisotropy());
            deviceFeatures.features().logicOp(device.availableFeatures.features().logicOp());
            // P2-3 (RQ-P2D-a): multiDrawIndirect + shaderDrawParameters as before, plus drawIndirectFirstInstance where offered: terrain
            // indirect commands carry firstInstance = packed section offset (VUID-VkDrawIndexedIndirectCommand-firstInstance-00554),
            // and so may api 4 multiDrawIndirectCount commands (gl_InstanceIndex per-draw id; int-4c H1C). Without either bit
            // terrain is drawn directly (IndirectDrawCaps.useTerrainIndirect), whatever the indirectDraw option says.
            IndirectDrawCaps.apply(device.availableFeatures.features(), device.availableFeatures11, deviceFeatures.features(), deviceVulkan11Features);
            net.vulkanmod.Initializer.LOGGER.info("Iroh: {}", IndirectDrawCaps.report());
            try { // P2-11: capability report only; the GPL pipeline path is not implemented
                dev.elemore.iroh.temporal.DeviceCaps.probeGpl(net.vulkanmod.vulkan.util.VkEnum.deviceExtensions(device.physicalDevice));
                net.vulkanmod.Initializer.LOGGER.info("Iroh: {}", dev.elemore.iroh.temporal.DeviceCaps.gplReport());
            } catch (RuntimeException e) { net.vulkanmod.Initializer.LOGGER.warn("Iroh: GPL probe failed: {}", e.toString()); }

            // PACK-FULL (VUID-RuntimeSpirv-NonWritable-06341): shader-pack vertex/fragment image stores (Navidson voxel_img)
            String packFeatures = dev.elemore.iroh.shaderpack.runtime.PackDeviceFeatures.apply(device.availableFeatures.features(), deviceFeatures.features());
            dev.elemore.iroh.shaderpack.runtime.PackDeviceFeatures.vertexStores = deviceFeatures.features().vertexPipelineStoresAndAtomics();
            dev.elemore.iroh.shaderpack.runtime.PackDeviceFeatures.fragmentStores = deviceFeatures.features().fragmentStoresAndAtomics();
            dev.elemore.iroh.shaderpack.runtime.PackDeviceFeatures.independentBlend = deviceFeatures.features().independentBlend();

            // Must not set line width to anything other than 1.0 if this is not supported
            if (device.availableFeatures.features().wideLines()) {
                deviceFeatures.features().wideLines(true);
                VRenderSystem.canSetLineWidth = true;
            }

            // Iroh P4-1a: FSR / velocity features, only when present AND requested (iroh.upscale.mode set at launch, or
            // iroh.upscale.features=on); otherwise nothing below changes the device (DeviceCaps only queries).
            // integration/next: float16 goes into api 4's Vulkan 1.2 struct (ONE struct per sType in the pNext chain)
            dev.elemore.iroh.temporal.DeviceCaps.applySafely(stack, physicalDevice,
                    device.availableFeatures.features(), deviceFeatures.features(), device.availableFeatures11, deviceVulkan11Features,
                    deviceVulkan12Features); // INT-2: a failure here logs one line and leaves the rc14 feature set
            net.vulkanmod.Initializer.LOGGER.info("Iroh: {}", dev.elemore.iroh.temporal.DeviceCaps.report());
            // INT-2 startup self-check: every feature bit this device is created with
            enabledFeatures = "samplerAnisotropy=" + deviceFeatures.features().samplerAnisotropy()
                    + " logicOp=" + deviceFeatures.features().logicOp()
                    + " multiDrawIndirect=" + deviceFeatures.features().multiDrawIndirect()
                    + " drawIndirectFirstInstance=" + deviceFeatures.features().drawIndirectFirstInstance()
                    + " wideLines=" + deviceFeatures.features().wideLines()
                    + " shaderDrawParameters=" + deviceVulkan11Features.shaderDrawParameters()
                    + " drawIndirectCount=" + deviceVulkan12Features.drawIndirectCount()
                    + " " + packFeatures
                    + " upscaleSet=" + (dev.elemore.iroh.temporal.DeviceCaps.requested
                        ? "[storageImageExtendedFormats=" + deviceFeatures.features().shaderStorageImageExtendedFormats()
                          + " writeWithoutFormat=" + deviceFeatures.features().shaderStorageImageWriteWithoutFormat()
                          + " readWithoutFormat=" + deviceFeatures.features().shaderStorageImageReadWithoutFormat()
                          + " int16=" + deviceFeatures.features().shaderInt16()
                          + " storage16=" + deviceVulkan11Features.storageBuffer16BitAccess()
                          + " float16=" + deviceVulkan12Features.shaderFloat16() + "]"
                        : "off")
                    + " dynamicRendering=" + Vulkan.DYNAMIC_RENDERING;

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);
            createInfo.sType$Default();
            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            createInfo.pQueueCreateInfos(queueCreateInfos);
            createInfo.pNext(deviceFeatures.address()); // .address(): the typed pNext(struct) overload OVERWRITES the struct's own pNext (V-4 trap)

            if (Vulkan.DYNAMIC_RENDERING) {
                VkPhysicalDeviceDynamicRenderingFeaturesKHR dynamicRenderingFeaturesKHR = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack);
                dynamicRenderingFeaturesKHR.sType$Default();
                dynamicRenderingFeaturesKHR.dynamicRendering(true);

                deviceVulkan12Features.pNext(dynamicRenderingFeaturesKHR.address());

//                //Vulkan 1.3 dynamic rendering
//                VkPhysicalDeviceVulkan13Features deviceVulkan13Features = VkPhysicalDeviceVulkan13Features.calloc(stack);
//                deviceVulkan13Features.sType$Default();
//                if(!deviceInfo.availableFeatures13.dynamicRendering())
//                    throw new RuntimeException("Device does not support dynamic rendering feature.");
//
//                deviceVulkan13Features.dynamicRendering(true);
//                createInfo.pNext(deviceVulkan13Features);
//                deviceVulkan13Features.pNext(deviceVulkan11Features.address());
            }

            // P2-10: optional VK_KHR_present_id + present_wait pacing (off unless -Diroh.presentWait=true and offered)
            java.util.List<String> deviceExts = new ArrayList<>(Vulkan.REQUIRED_EXTENSION);
            net.vulkanmod.vulkan.PresentPacer.configure(stack, physicalDevice, deviceExts, deviceVulkan12Features);
            // P2-12 (L9/L10): timeline semaphore (Vulkan 1.2 core feature), VK_EXT_memory_budget and VK_EXT_memory_priority, each only when offered
            java.util.Set<String> offeredExts = net.vulkanmod.vulkan.util.VkEnum.deviceExtensions(physicalDevice);
            SyncMemCaps.Decision syncMem = SyncMemCaps.apply(offeredExts, device.availableFeatures12.timelineSemaphore(),
                    SyncMemCaps.priorityFeatureOffered(stack, physicalDevice, offeredExts), deviceVulkan12Features);
            if (syncMem.priority())
                SyncMemCaps.chainPriority(deviceVulkan12Features, VkPhysicalDeviceMemoryPriorityFeaturesEXT.calloc(stack));
            net.vulkanmod.Initializer.LOGGER.info("Iroh: {}", SyncMemCaps.report());
            enabledFeatures += " timelineSemaphore=" + syncMem.timeline() + " memoryBudget=" + syncMem.budget() + " memoryPriority=" + syncMem.priority();

            // H1A: extension name pointers and strings on the heap (freed in the finally below), so device creation does not scale with the list on the 64 KB stack
            java.util.List<Runnable> extAllocs = new ArrayList<>();
            try {
                deviceExts.addAll(SyncMemCaps.extensionNames());
                createInfo.ppEnabledExtensionNames(heapNames(deviceExts, extAllocs));

//            Configuration.DEBUG_FUNCTIONS.set(true);

                // Iroh (P1-1): device layers are deprecated since Vulkan 1.0 (VUID-VkDeviceCreateInfo-enabledLayerCount-12384).
                createInfo.ppEnabledLayerNames(null);

                PointerBuffer pDevice = stack.pointers(VK_NULL_HANDLE);

                int res = vkCreateDevice(physicalDevice, createInfo, null, pDevice);
                Vulkan.checkResult(res, "Failed to create logical device");

                vkDevice = new VkDevice(pDevice.get(0), physicalDevice, createInfo, VK_API_VERSION_1_2);
            } finally {
                extAllocs.forEach(Runnable::run);
            }

            graphicsQueue = new GraphicsQueue(stack, indices.graphicsFamily);
            transferQueue = new TransferQueue(stack, indices.transferFamily);
            presentQueue = new PresentQueue(stack, indices.presentFamily);
            computeQueue = new ComputeQueue(stack, indices.computeFamily);
        }
    }

    /** H1A: heap pointer array + UTF-8 strings for extension names; every allocation adds its free action to {@code allocs} for the caller to run. */
    private static PointerBuffer heapNames(java.util.Collection<String> names, java.util.List<Runnable> allocs) {
        PointerBuffer pb = org.lwjgl.system.MemoryUtil.memAllocPointer(names.size());
        allocs.add(() -> org.lwjgl.system.MemoryUtil.memFree(pb));
        for (String n : names) {
            java.nio.ByteBuffer b = org.lwjgl.system.MemoryUtil.memUTF8(n);
            allocs.add(() -> org.lwjgl.system.MemoryUtil.memFree(b));
            pb.put(b);
        }
        return pb.rewind();
    }

    private static PointerBuffer getRequiredExtensions() {
        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();

        if (Vulkan.ENABLE_VALIDATION_LAYERS) {

            MemoryStack stack = stackGet();

            PointerBuffer extensions = stack.mallocPointer(glfwExtensions.capacity() + 1);

            extensions.put(glfwExtensions);
            extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));

            // Rewind the buffer before returning it to reset its position back to 0
            return extensions.rewind();
        }

        return glfwExtensions;
    }

    private static boolean isDeviceSuitable(VkPhysicalDevice device) {
        try (MemoryStack stack = stackPush()) {
            Queue.QueueFamilyIndices indices = findQueueFamilies(device);

            boolean extensionsSupported = net.vulkanmod.vulkan.util.VkEnum.deviceExtensions(device)
                    .containsAll(Vulkan.REQUIRED_EXTENSION);

            boolean swapChainAdequate = false;

            if (extensionsSupported) {
                SurfaceProperties surfaceProperties = querySurfaceProperties(device, stack);
                swapChainAdequate = surfaceProperties.formats.hasRemaining() && surfaceProperties.presentModes.hasRemaining();
            }

            VkPhysicalDeviceFeatures supportedFeatures = VkPhysicalDeviceFeatures.malloc(stack);
            vkGetPhysicalDeviceFeatures(device, supportedFeatures);
            boolean anisotropicFilterSupported = supportedFeatures.samplerAnisotropy();

            return indices.isSuitable() && extensionsSupported && swapChainAdequate;
        }
    }

    // Use the optimal most performant depth format for the specific GPU
    // Nvidia performs best with 24 bit depth, while AMD is most performant with 32-bit float
    public static int findDepthFormat(boolean use24BitsDepthFormat) {
        int[] formats = use24BitsDepthFormat ? new int[]
                {VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT}
                : new int[]{VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT};

        return findSupportedFormat(
                VK_IMAGE_TILING_OPTIMAL,
                VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT,
                formats);
    }

    private static int findSupportedFormat(int tiling, int features, int... formatCandidates) {
        try (MemoryStack stack = stackPush()) {

            VkFormatProperties props = VkFormatProperties.calloc(stack);

            for (int format : formatCandidates) {

                vkGetPhysicalDeviceFormatProperties(physicalDevice, format, props);

                if (tiling == VK_IMAGE_TILING_LINEAR && (props.linearTilingFeatures() & features) == features) {
                    return format;
                } else if (tiling == VK_IMAGE_TILING_OPTIMAL && (props.optimalTilingFeatures() & features) == features) {
                    return format;
                }

            }
        }

        throw new RuntimeException("Failed to find supported format");
    }

    public static String getAvailableDevicesInfo() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("\n");

        if (availableDevices == null) {
            stringBuilder.append("\tDevice Manager not initialized");
            return stringBuilder.toString();
        }

        if (availableDevices.isEmpty()) {
            stringBuilder.append("\tNo available device found");
        }

        for (Device device : availableDevices) {
            stringBuilder.append("\tDevice: %s\n".formatted(device.deviceName));

            stringBuilder.append("\t\tVulkan Version: %s\n".formatted(device.vkVersion));

            stringBuilder.append("\t\t");
            var unsupportedExtensions = device.getUnsupportedExtensions(Vulkan.REQUIRED_EXTENSION);
            if (unsupportedExtensions.isEmpty()) {
                stringBuilder.append("All required extensions are supported\n");
            } else {
                stringBuilder.append("Unsupported extension: %s\n".formatted(unsupportedExtensions));
            }
        }

        return stringBuilder.toString();
    }

    /** INT-2: the feature bits the logical device was created with (startup self-check) */
    public static String enabledFeatures = "device not created";

    /** api 4: vkCmdDrawIndexedIndirectCount enabled on the logical device. */
    public static boolean drawIndirectCountEnabled;

    public static void destroy() {
        graphicsQueue.cleanUp();
        transferQueue.cleanUp();
        computeQueue.cleanUp();

        vkDestroyDevice(vkDevice, null);
    }

    public static GraphicsQueue getGraphicsQueue() {
        return graphicsQueue;
    }

    public static PresentQueue getPresentQueue() {
        return presentQueue;
    }

    public static TransferQueue getTransferQueue() {
        return transferQueue;
    }

    public static ComputeQueue getComputeQueue() {
        return computeQueue;
    }

    public static SurfaceProperties querySurfaceProperties(VkPhysicalDevice device, MemoryStack stack) {

        long surface = Vulkan.getSurface();
        SurfaceProperties details = new SurfaceProperties();

        details.capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, details.capabilities);

        IntBuffer count = stack.ints(0);

        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, null);

        if (count.get(0) != 0) {
            details.formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, details.formats);
        }

        vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, null);

        if (count.get(0) != 0) {
            details.presentModes = stack.mallocInt(count.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, details.presentModes);
        }

        return details;
    }

    public static class SurfaceProperties {
        public VkSurfaceCapabilitiesKHR capabilities;
        public VkSurfaceFormatKHR.Buffer formats;
        public IntBuffer presentModes;
    }

}
