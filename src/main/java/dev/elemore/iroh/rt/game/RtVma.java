package dev.elemore.iroh.rt.game;

import static org.lwjgl.util.vma.Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

/**
 * P5-2 memory hooks: the VMA allocator flag and the terrain arena's extra buffer usage, both 0 unless the RT device
 * features were enabled at device creation (then the arena is read IN PLACE by BLAS builds: no copy of any vertex).
 */
public final class RtVma {
    private RtVma() {}

    /** Vulkan.createVma: OR-ed into VmaAllocatorCreateInfo.flags. */
    public static int allocatorFlags() {
        return RtDeviceFeatures.enabled ? VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT : 0;
    }

    /** AreaBuffer.allocateBuffer (VERTEX arenas only): OR-ed into the buffer usage. */
    public static int arenaUsage() {
        return RtDeviceFeatures.enabled ? VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR : 0;
    }
}
