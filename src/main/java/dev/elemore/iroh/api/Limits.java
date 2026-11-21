package dev.elemore.iroh.api;

/**
 * Device limits snapshot from {@link IrohApi#limits()} (api 3). Values in bytes unless named otherwise.
 *
 * @param deviceName           the Vulkan device name
 * @param maxStorageBufferRange maxStorageBufferRange
 * @param maxPushConstantsSize  maxPushConstantsSize
 * @param maxImageDimension2D   maxImageDimension2D
 * @param timestampPeriodNs     nanoseconds per GPU timestamp tick (0: no timestamps)
 * @param deviceLocalHeapBytes  size of the largest device-local memory heap
 * @param sparseBinding         the device supports sparse binding
 */
public record Limits(String deviceName, long maxStorageBufferRange, int maxPushConstantsSize, int maxImageDimension2D,
                     float timestampPeriodNs, long deviceLocalHeapBytes, boolean sparseBinding) {
}
