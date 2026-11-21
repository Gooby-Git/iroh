package net.vulkanmod.vulkan;

import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.StagingBuffer;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.util.VkResult;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static java.util.stream.Collectors.toSet;
import static net.vulkanmod.vulkan.queue.Queue.getQueueFamilies;
import static net.vulkanmod.vulkan.util.VUtil.asPointerBuffer;
import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.vma.Vma.vmaCreateAllocator;
import static org.lwjgl.util.vma.Vma.vmaDestroyAllocator;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public class Vulkan {

    // Iroh: validation is a runtime switch, not a compile-time constant. On when -Diroh.validation=true,
    // IROH_VALIDATION=1, or VK_INSTANCE_LAYERS names the Khronos layer (the Proctor gate recipe).
    // Messages go to latest.log as "Validation Error: ..." / "Validation Warning: ..." so gates can see them.
    public static boolean ENABLE_VALIDATION_LAYERS = validationRequested();

    //    public static final boolean DYNAMIC_RENDERING = true;
    public static final boolean DYNAMIC_RENDERING = false;

    public static final Set<String> VALIDATION_LAYERS = new HashSet<>(List.of("VK_LAYER_KHRONOS_validation"));

    private static boolean validationRequested() {
        if (Boolean.getBoolean("iroh.validation") || "1".equals(System.getenv("IROH_VALIDATION")))
            return true;
        String layers = System.getenv("VK_INSTANCE_LAYERS");
        return layers != null && layers.contains("VK_LAYER_KHRONOS_validation");
    }

    public static final Set<String> REQUIRED_EXTENSION = getRequiredExtensionSet();

    private static Set<String> getRequiredExtensionSet() {
        ArrayList<String> extensions = new ArrayList<>(List.of(VK_KHR_SWAPCHAIN_EXTENSION_NAME));

        if (DYNAMIC_RENDERING) {
            extensions.add(VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME);
        }

        return new HashSet<>(extensions);
    }

    private static final java.util.Map<Integer, Integer> VALIDATION_SEEN = new java.util.concurrent.ConcurrentHashMap<>();
    public static final java.util.concurrent.atomic.AtomicInteger VALIDATION_ERRORS = new java.util.concurrent.atomic.AtomicInteger();

    private static int debugCallback(int messageSeverity, int messageType, long pCallbackData, long pUserData) {
        VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
        boolean error = (messageSeverity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0;
        if (error)
            VALIDATION_ERRORS.incrementAndGet();

        // Log the first 5 of each message id, then only a count every 1000.
        int n = VALIDATION_SEEN.merge(callbackData.messageIdNumber(), 1, Integer::sum);
        if (n > 5 && n % 1000 != 0)
            return VK_FALSE;

        String msg = callbackData.pMessageString() + (n > 5 ? " (seen " + n + " times)" : "");
        // GENERAL-only messages come from the loader and other layers (e.g. a broken implicit layer), not from
        // Iroh's API use: logged as warnings so the gate only fails on real validation findings.
        if ((messageType & (VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)) == 0) {
            if (error) VALIDATION_ERRORS.decrementAndGet();
            net.vulkanmod.Initializer.LOGGER.warn("Vulkan loader/layer message: {}", msg);
            return VK_FALSE;
        }
        if (error)
            net.vulkanmod.Initializer.LOGGER.error("Validation Error: {}", msg);
        else
            net.vulkanmod.Initializer.LOGGER.warn("Validation Warning: {}", msg);

        return VK_FALSE;
    }

    private static int createDebugUtilsMessengerEXT(VkInstance instance, VkDebugUtilsMessengerCreateInfoEXT createInfo,
                                                    VkAllocationCallbacks allocationCallbacks, LongBuffer pDebugMessenger) {

        if (vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT") != NULL) {
            return vkCreateDebugUtilsMessengerEXT(instance, createInfo, allocationCallbacks, pDebugMessenger);
        }

        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }

    private static void destroyDebugUtilsMessengerEXT(VkInstance instance, long debugMessenger, VkAllocationCallbacks allocationCallbacks) {

        if (vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT") != NULL) {
            vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, allocationCallbacks);
        }

    }

    public static VkDevice getVkDevice() {
        return DeviceManager.vkDevice;
    }

    public static long getAllocator() {
        return allocator;
    }

    public static long window;

    private static VkInstance instance;
    private static long debugMessenger;
    private static long surface;

    private static long commandPool;
    private static VkCommandBuffer immediateCmdBuffer;
    private static long immediateFence;

    private static long allocator;

    private static StagingBuffer[] stagingBuffers;

    public static boolean use24BitsDepthFormat = true;
    private static int DEFAULT_DEPTH_FORMAT = 0;

    public static void initVulkan(long window) {
        createInstance();
        setupDebugMessenger();
        createSurface(window);

        DeviceManager.init(instance);

        createVma();
        MemoryTypes.createMemoryTypes();

        createCommandPool();

        setupDepthFormat();
        Renderer.initRenderer();
    }

    static void createStagingBuffers() {
        if (stagingBuffers != null) {
            freeStagingBuffers();
        }

        stagingBuffers = new StagingBuffer[Renderer.getFramesNum()];

        for (int i = 0; i < stagingBuffers.length; ++i) {
            stagingBuffers[i] = new StagingBuffer();
            stagingBuffers[i].markShared();
        }
    }

    static void setupDepthFormat() {
        DEFAULT_DEPTH_FORMAT = DeviceManager.findDepthFormat(use24BitsDepthFormat);
    }

    public static void waitIdle() {
        vkDeviceWaitIdle(DeviceManager.vkDevice);
    }

    public static void cleanUp() {
        vkDeviceWaitIdle(DeviceManager.vkDevice);
        vkDestroyCommandPool(DeviceManager.vkDevice, commandPool, null);
        vkDestroyFence(DeviceManager.vkDevice, immediateFence, null);

        Pipeline.destroyPipelineCache();

        Renderer.getInstance().cleanUpResources();

        freeStagingBuffers();

        try {
            MemoryManager.getInstance().freeAllBuffers();
            MemoryManager.getInstance().freeLeftovers(); // rc5 (td-dcbf58)
        } catch (Exception e) {
            e.printStackTrace();
        }

        vmaDestroyAllocator(allocator);

        SamplerManager.cleanUp();
        DeviceManager.destroy();
        destroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        KHRSurface.vkDestroySurfaceKHR(instance, surface, null);
        vkDestroyInstance(instance, null);
    }

    private static void freeStagingBuffers() {
        Arrays.stream(stagingBuffers).forEach(Buffer::scheduleFree);
    }

    private static void createInstance() {

        if (ENABLE_VALIDATION_LAYERS && !checkValidationLayerSupport()) {
            // Fails the Proctor gate on purpose: a gate that asked for validation must not pass without it.
            net.vulkanmod.Initializer.LOGGER.error("Validation Error: VK_LAYER_KHRONOS_validation was requested but is not installed (see iroh knowledge/proctor.md); running without validation");
            ENABLE_VALIDATION_LAYERS = false;
        }
        if (ENABLE_VALIDATION_LAYERS)
            net.vulkanmod.Initializer.LOGGER.info("Iroh: Vulkan validation layer enabled");

        try (MemoryStack stack = stackPush()) {

            // Use calloc to initialize the structs with 0s. Otherwise, the program can crash due to random values

            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack);

            appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
            appInfo.pApplicationName(stack.UTF8Safe("VulkanMod"));
            appInfo.applicationVersion(VK_MAKE_VERSION(1, 0, 0));
            appInfo.pEngineName(stack.UTF8Safe("VulkanMod Engine"));
            appInfo.engineVersion(VK_MAKE_VERSION(1, 0, 0));
            appInfo.apiVersion(VK_API_VERSION_1_2);

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack);

            createInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            createInfo.pApplicationInfo(appInfo);
            createInfo.ppEnabledExtensionNames(getRequiredInstanceExtensions());

            if (ENABLE_VALIDATION_LAYERS) {

                createInfo.ppEnabledLayerNames(asPointerBuffer(VALIDATION_LAYERS));

                VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
                populateDebugMessengerCreateInfo(debugCreateInfo);
                createInfo.pNext(debugCreateInfo.address());
            }

            // We need to retrieve the pointer of the created instance
            PointerBuffer instancePtr = stack.mallocPointer(1);

            int result = vkCreateInstance(createInfo, null, instancePtr);
            checkResult(result, "Failed to create instance");

            instance = new VkInstance(instancePtr.get(0), createInfo);
        }
    }

    static boolean checkValidationLayerSupport() {

        try (MemoryStack stack = stackPush()) {

            IntBuffer layerCount = stack.ints(0);

            vkEnumerateInstanceLayerProperties(layerCount, null);

            VkLayerProperties.Buffer availableLayers = VkLayerProperties.malloc(layerCount.get(0), stack);

            vkEnumerateInstanceLayerProperties(layerCount, availableLayers);

            Set<String> availableLayerNames = availableLayers.stream()
                    .map(VkLayerProperties::layerNameString)
                    .collect(toSet());

            return availableLayerNames.containsAll(Vulkan.VALIDATION_LAYERS);
        }
    }

    private static void populateDebugMessengerCreateInfo(VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo) {
        debugCreateInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
//        debugCreateInfo.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT);
        debugCreateInfo.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT);
        debugCreateInfo.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
//        debugCreateInfo.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT);
        debugCreateInfo.pfnUserCallback(Vulkan::debugCallback);
    }

    private static void setupDebugMessenger() {

        if (!ENABLE_VALIDATION_LAYERS) {
            return;
        }

        try (MemoryStack stack = stackPush()) {

            VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);

            populateDebugMessengerCreateInfo(createInfo);

            LongBuffer pDebugMessenger = stack.longs(VK_NULL_HANDLE);

            checkResult(createDebugUtilsMessengerEXT(instance, createInfo, null, pDebugMessenger),
                    "Failed to set up debug messenger");

            debugMessenger = pDebugMessenger.get(0);
        }
    }

    public static void setDebugLabel(MemoryStack stack, int objectType, long handle, String label) {
        if (ENABLE_VALIDATION_LAYERS) {
            VkDebugUtilsObjectNameInfoEXT nameInfo = VkDebugUtilsObjectNameInfoEXT.calloc(stack);
            nameInfo.sType$Default();
            nameInfo.objectType(objectType);
            nameInfo.objectHandle(handle);
            nameInfo.pObjectName(stackUTF8(label));
            EXTDebugUtils.vkSetDebugUtilsObjectNameEXT(Vulkan.getVkDevice(), nameInfo);
        }
    }

    private static void createSurface(long handle) {
        window = handle;

        try (MemoryStack stack = stackPush()) {

            LongBuffer pSurface = stack.longs(VK_NULL_HANDLE);

            checkResult(glfwCreateWindowSurface(instance, window, null, pSurface),
                    "Failed to create window surface");

            surface = pSurface.get(0);
        }
    }

    private static void createVma() {
        try (MemoryStack stack = stackPush()) {

            VmaVulkanFunctions vulkanFunctions = VmaVulkanFunctions.calloc(stack);
            vulkanFunctions.set(instance, DeviceManager.vkDevice);

            VmaAllocatorCreateInfo allocatorCreateInfo = VmaAllocatorCreateInfo.calloc(stack);
            allocatorCreateInfo.physicalDevice(DeviceManager.physicalDevice);
            allocatorCreateInfo.device(DeviceManager.vkDevice);
            allocatorCreateInfo.pVulkanFunctions(vulkanFunctions);
            allocatorCreateInfo.instance(instance);
            allocatorCreateInfo.vulkanApiVersion(VK_API_VERSION_1_2);

            PointerBuffer pAllocator = stack.pointers(VK_NULL_HANDLE);

            checkResult(vmaCreateAllocator(allocatorCreateInfo, pAllocator),
                    "Failed to create Allocator");

            allocator = pAllocator.get(0);
        }
    }

    private static void createCommandPool() {

        try (MemoryStack stack = stackPush()) {

            Queue.QueueFamilyIndices queueFamilyIndices = getQueueFamilies();

            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            poolInfo.queueFamilyIndex(queueFamilyIndices.graphicsFamily);
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pCommandPool = stack.mallocLong(1);

            checkResult(vkCreateCommandPool(DeviceManager.vkDevice, poolInfo, null, pCommandPool),
                    "Failed to create command pool");

            commandPool = pCommandPool.get(0);
        }
    }

    private static PointerBuffer getRequiredInstanceExtensions() {

        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();

        if (ENABLE_VALIDATION_LAYERS) {

            MemoryStack stack = stackGet();

            PointerBuffer extensions = stack.mallocPointer(glfwExtensions.capacity() + 1);

            extensions.put(glfwExtensions);
            extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));

            // Rewind the buffer before returning it to reset its position back to 0
            return extensions.rewind();
        }

        return glfwExtensions;
    }

    public static void checkResult(int result, String errorMessage) {
        if (result != VK_SUCCESS) {
            throw new RuntimeException(String.format("%s: %s", errorMessage, VkResult.decode(result)));
        }
    }

    public static void setVsync(boolean b) {
        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        if (swapChain.isVsync() != b) {
            Renderer.scheduleSwapChainUpdate();
            swapChain.setVsync(b);
        }
    }

    public static int getDefaultDepthFormat() {
        return DEFAULT_DEPTH_FORMAT;
    }

    public static long getSurface() {
        return surface;
    }

    public static long getCommandPool() {
        return commandPool;
    }

    /** Iroh (R-3): true when {@code bufferId} is one of the per-frame shared staging buffers. */
    public static boolean isSharedStagingBuffer(long bufferId) {
        StagingBuffer[] arr = stagingBuffers;
        if (arr == null || bufferId == 0L)
            return false;
        for (StagingBuffer b : arr)
            if (b != null && b.getId() == bufferId)
                return true;
        return false;
    }

    public static StagingBuffer getStagingBuffer() {
        return stagingBuffers[Renderer.getCurrentFrame()];
    }

    public static Device getDevice() {
        return DeviceManager.device;
    }
}

