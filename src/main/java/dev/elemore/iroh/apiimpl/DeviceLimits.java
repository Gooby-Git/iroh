package dev.elemore.iroh.apiimpl;

import dev.elemore.iroh.api.Limits;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import static org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;

/** Iroh internal (P1-13a): {@link dev.elemore.iroh.api.IrohApi#limits()}. */
public final class DeviceLimits {
    private static volatile Limits cached;

    private DeviceLimits() {
    }

    public static Limits get() {
        Limits l = cached;
        if (l != null) return l;
        if (DeviceManager.deviceProperties == null) return null;
        VkPhysicalDeviceLimits d = DeviceManager.deviceProperties.limits();
        long heap = 0;
        VkPhysicalDeviceMemoryProperties mem = DeviceManager.memoryProperties;
        if (mem != null)
            for (int i = 0; i < mem.memoryHeapCount(); i++)
                if ((mem.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0)
                    heap = Math.max(heap, mem.memoryHeaps(i).size());
        boolean sparse = DeviceManager.device != null && DeviceManager.device.availableFeatures.features().sparseBinding();
        l = new Limits(DeviceManager.deviceProperties.deviceNameString(), d.maxStorageBufferRange() & 0xFFFFFFFFL,
                d.maxPushConstantsSize(), d.maxImageDimension2D(), d.timestampPeriod(), heap, sparse);
        cached = l;
        return l;
    }
}
