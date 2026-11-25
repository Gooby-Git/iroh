package net.vulkanmod.vulkan.queue;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;
import java.util.stream.IntStream;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.VK10.*;

public abstract class Queue {
    private static VkDevice device;
    private static QueueFamilyIndices queueFamilyIndices;

    private final VkQueue queue;

    protected CommandPool commandPool;

    public synchronized CommandPool.CommandBuffer beginCommands() {
        try (MemoryStack stack = stackPush()) {
            CommandPool.CommandBuffer commandBuffer = this.commandPool.getCommandBuffer(stack);
            commandBuffer.begin(stack);

            return commandBuffer;
        }
    }

    Queue(MemoryStack stack, int familyIndex) {
        this(stack, familyIndex, true);
    }

    Queue(MemoryStack stack, int familyIndex, boolean initCommandPool) {
        PointerBuffer pQueue = stack.mallocPointer(1);
        vkGetDeviceQueue(DeviceManager.vkDevice, familyIndex, 0, pQueue);
        this.queue = new VkQueue(pQueue.get(0), DeviceManager.vkDevice);

        if (initCommandPool)
            this.commandPool = new CommandPool(familyIndex);
    }

    public synchronized long submitCommands(CommandPool.CommandBuffer commandBuffer) {
        try (MemoryStack stack = stackPush()) {
            return commandBuffer.submitCommands(stack, queue, false);
        }
    }

    /**
     * perf/uploads: submit signalling a semaphore the next frame submit waits on. P2-12: with timeline semaphores enabled
     * (SyncMemCaps) this queue's ONE timeline semaphore is signalled to the next value (values rise in submission order: this method
     * is synchronized per queue) and the frame waits the latest value; otherwise the command buffer's binary semaphore.
     */
    public synchronized long submitCommandsSignalling(CommandPool.CommandBuffer commandBuffer) {
        try (MemoryStack stack = stackPush()) {
            if (net.vulkanmod.vulkan.device.SyncMemCaps.timeline()) {
                long sem = timelineSemaphore();
                long value = ++this.timelineValue;
                long fence = commandBuffer.submitCommandsTimeline(stack, queue, sem, value);
                net.vulkanmod.vulkan.Synchronization.INSTANCE.addTimelineWait(sem, value);
                return fence;
            }
            long fence = commandBuffer.submitCommands(stack, queue, true);
            net.vulkanmod.vulkan.Synchronization.INSTANCE.addWaitSemaphore(commandBuffer);
            return fence;
        }
    }

    private long timelineSemaphore;
    private long timelineValue;

    private long timelineSemaphore() {
        if (this.timelineSemaphore == 0L) {
            try (MemoryStack stack = stackPush()) {
                org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo type = org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                        .semaphoreType(org.lwjgl.vulkan.VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0L);
                org.lwjgl.vulkan.VkSemaphoreCreateInfo info = org.lwjgl.vulkan.VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type.address());
                java.nio.LongBuffer p = stack.mallocLong(1);
                Vulkan.checkResult(vkCreateSemaphore(DeviceManager.vkDevice, info, null, p), "Failed to create upload timeline semaphore");
                this.timelineSemaphore = p.get(0);
            }
        }
        return this.timelineSemaphore;
    }

    public VkQueue queue() {
        return this.queue;
    }

    public void cleanUp() {
        if (this.timelineSemaphore != 0L) {
            vkDestroySemaphore(DeviceManager.vkDevice, this.timelineSemaphore, null);
            this.timelineSemaphore = 0L;
        }
        if (commandPool != null)
            commandPool.cleanUp();
    }

    public void waitIdle() {
        vkQueueWaitIdle(queue);
    }

    public CommandPool getCommandPool() {
        return commandPool;
    }

    public enum Family {
        Graphics,
        Transfer,
        Compute
    }

    public static QueueFamilyIndices getQueueFamilies() {
        if (device == null)
            device = Vulkan.getVkDevice();

        if (queueFamilyIndices == null) {
            queueFamilyIndices = findQueueFamilies(device.getPhysicalDevice());
        }
        return queueFamilyIndices;
    }

    public static QueueFamilyIndices findQueueFamilies(VkPhysicalDevice device) {
        QueueFamilyIndices indices = new QueueFamilyIndices();

        try (MemoryStack stack = stackPush()) {

            IntBuffer queueFamilyCount = stack.ints(0);

            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null);

            VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.mallocStack(queueFamilyCount.get(0), stack);

            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies);

            IntBuffer presentSupport = stack.ints(VK_FALSE);

            for (int i = 0; i < queueFamilies.capacity(); i++) {
                int queueFlags = queueFamilies.get(i).queueFlags();

                if ((queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    indices.graphicsFamily = i;

                    vkGetPhysicalDeviceSurfaceSupportKHR(device, i, Vulkan.getSurface(), presentSupport);

                    if (presentSupport.get(0) == VK_TRUE) {
                        indices.presentFamily = i;
                    }
                } else if ((queueFlags & (VK_QUEUE_GRAPHICS_BIT)) == 0
                        && (queueFlags & VK_QUEUE_COMPUTE_BIT) != 0) {
                    indices.computeFamily = i;
                } else if ((queueFlags & (VK_QUEUE_COMPUTE_BIT | VK_QUEUE_GRAPHICS_BIT)) == 0
                        && (queueFlags & VK_QUEUE_TRANSFER_BIT) != 0) {
                    indices.transferFamily = i;
                }

                if (indices.presentFamily == -1) {
                    vkGetPhysicalDeviceSurfaceSupportKHR(device, i, Vulkan.getSurface(), presentSupport);

                    if (presentSupport.get(0) == VK_TRUE) {
                        indices.presentFamily = i;
                    }
                }

                if (indices.isComplete())
                    break;
            }

            if (indices.presentFamily == -1) {
                // Some drivers will not show present support even if some queue supports it
                // Use compute queue as fallback

                indices.presentFamily = indices.computeFamily;
                Initializer.LOGGER.warn("Using compute queue as present fallback");
            }

            // In case there's no dedicated transfer queue, we need choose another one
            // preferably a different one from the already selected queues
            if (indices.transferFamily == -1) {

                int transferIndex = -1;
                for (int i = 0; i < queueFamilies.capacity(); i++) {
                    int queueFlags = queueFamilies.get(i).queueFlags();

                    if ((queueFlags & VK_QUEUE_TRANSFER_BIT) != 0) {
                        if (transferIndex == -1)
                            transferIndex = i;

                        if ((queueFlags & (VK_QUEUE_GRAPHICS_BIT)) == 0) {
                            indices.transferFamily = i;

                            if (i != indices.computeFamily)
                                break;

                            transferIndex = i;
                        }
                    }
                }

                if (transferIndex == -1)
                    throw new RuntimeException("Failed to find queue family with transfer support");

                indices.transferFamily = transferIndex;
            }

            if (indices.computeFamily == -1) {
                for (int i = 0; i < queueFamilies.capacity(); i++) {
                    int queueFlags = queueFamilies.get(i).queueFlags();

                    if ((queueFlags & VK_QUEUE_COMPUTE_BIT) != 0) {
                        indices.computeFamily = i;
                        break;
                    }
                }
            }

            if (indices.graphicsFamily == VK_QUEUE_FAMILY_IGNORED)
                throw new RuntimeException("Unable to find queue family with graphics support.");
            if (indices.presentFamily == VK_QUEUE_FAMILY_IGNORED)
                throw new RuntimeException("Unable to find queue family with present support.");
            if (indices.computeFamily == VK_QUEUE_FAMILY_IGNORED)
                throw new RuntimeException("Unable to find queue family with compute support.");

            return indices;
        }
    }

    public static class QueueFamilyIndices {
        public int graphicsFamily = VK_QUEUE_FAMILY_IGNORED;
        public int presentFamily = VK_QUEUE_FAMILY_IGNORED;
        public int transferFamily = VK_QUEUE_FAMILY_IGNORED;
        public int computeFamily = VK_QUEUE_FAMILY_IGNORED;

        public boolean isComplete() {
            return graphicsFamily != -1 && presentFamily != -1 && transferFamily != -1 && computeFamily != -1;
        }

        public boolean isSuitable() {
            return graphicsFamily != -1 && presentFamily != -1;
        }

        public int[] unique() {
            return IntStream.of(graphicsFamily, presentFamily, transferFamily, computeFamily).distinct().toArray();
        }

        public int[] array() {
            return new int[]{graphicsFamily, presentFamily};
        }
    }
}
