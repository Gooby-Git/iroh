package dev.elemore.iroh.rt.denoise;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;

/**
 * What the denoisers need from the renderer: the device and a way to allocate device-local images and host-visible
 * mapped buffers. Iroh's in-game implementation goes through its memory guard/VMA; the offline harness implements it with
 * plain Vulkan on lavapipe. The executor frees everything it creates through {@link #destroyImage}/{@link #destroyBuffer}.
 */
public interface DenoiseGpu {
    record Img(long image, long memory, long bytes) {}

    record Buf(long buffer, long memory, long ptr, long size) {}

    VkDevice device();

    VkPhysicalDevice physicalDevice();

    /** 2D, 1 mip, optimal tiling, device-local, usage as given (the executor asks for STORAGE | SAMPLED), initial layout UNDEFINED. */
    Img createImage(int vkFormat, int width, int height, int usage, String name);

    void destroyImage(Img img);

    /** UNIFORM_BUFFER usage, host visible + coherent, persistently mapped ({@code ptr}). */
    Buf createUniformBuffer(long size);

    void destroyBuffer(Buf b);
}
