package net.vulkanmod.vulkan.framebuffer;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.util.MathUtil;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.vulkanmod.vulkan.Vulkan.*;
import static net.vulkanmod.vulkan.device.DeviceManager.vkDevice;
import static net.vulkanmod.vulkan.util.VUtil.UINT32_MAX;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class SwapChain extends Framebuffer {
    // Necessary until tearing-control-unstable-v1 is fully implemented on all GPU Drivers for Wayland
    // (As Immediate Mode (and by extension Screen tearing) doesn't exist on some Wayland installations currently)
    private static final int defUncappedMode = checkPresentMode(VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_MAILBOX_KHR);

    private final Long2ReferenceOpenHashMap<long[]> FBO_map = new Long2ReferenceOpenHashMap<>();

    private long swapChainId = VK_NULL_HANDLE;
    private List<VulkanImage> swapChainImages;
    private VkExtent2D extent2D;
    public boolean isBGRAformat;
    private boolean vsync = false;

    // Iroh (R-1): nothing renders into the swapchain any more. Each frame the scene target
    // (SceneTarget) is copied/blitted into the acquired image at the tail of DefaultMainPass.end, so the
    // swapchain has no depth image and needs TRANSFER_DST usage.
    private boolean canBlitTo;

    public SwapChain() {
        this.attachmentCount = 1;
        this.depthFormat = Vulkan.getDefaultDepthFormat();

        this.hasColorAttachment = true;
        this.hasDepthAttachment = false;

        recreate();
    }

    public void recreate() {
        if (this.depthAttachment != null) {
            this.depthAttachment.free();
            this.depthAttachment = null;
        }

        if (!DYNAMIC_RENDERING) {
            this.FBO_map.forEach((pass, framebuffers) -> Arrays.stream(framebuffers).forEach(id -> vkDestroyFramebuffer(getVkDevice(), id, null)));
            this.FBO_map.clear();
        }

        createSwapChain();
    }

    private void createSwapChain() {
        try (MemoryStack stack = stackPush()) {
            VkDevice device = Vulkan.getVkDevice();
            DeviceManager.SurfaceProperties surfaceProperties = DeviceManager.querySurfaceProperties(device.getPhysicalDevice(), stack);

            VkSurfaceFormatKHR surfaceFormat = getFormat(surfaceProperties.formats);
            int presentMode = getPresentMode(surfaceProperties.presentModes);
            VkExtent2D extent = getExtent(surfaceProperties.capabilities);

            // Iroh (R-1): either side 0 is unusable (a 0xN swapchain is invalid), not only both.
            if (extent.width() == 0 || extent.height() == 0) {
                if (this.swapChainId != VK_NULL_HANDLE) {
                    this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));
                    vkDestroySwapchainKHR(device, this.swapChainId, null);
                    this.swapChainId = VK_NULL_HANDLE;
                }

                this.width = 0;
                this.height = 0;
                return;
            }

            int requestedImages = surfaceProperties.capabilities.minImageCount() + 1;
            if (surfaceProperties.capabilities.maxImageCount() > 0 && requestedImages > surfaceProperties.capabilities.maxImageCount()) {
                requestedImages = surfaceProperties.capabilities.maxImageCount();
            }

            IntBuffer imageCount = stack.ints(requestedImages);

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack);

            createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
            createInfo.surface(Vulkan.getSurface());

            // Image settings
            this.format = surfaceFormat.format();
            this.extent2D = VkExtent2D.create().set(extent);

            createInfo.minImageCount(requestedImages);
            createInfo.imageFormat(this.format);
            createInfo.imageColorSpace(surfaceFormat.colorSpace());
            createInfo.imageExtent(extent);
            createInfo.imageArrayLayers(1);
            int imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            int supportedUsage = surfaceProperties.capabilities.supportedUsageFlags();
            // Iroh (R-1): the frame ends with a copy/blit from the scene target into the acquired image.
            if ((supportedUsage & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
                throw new RuntimeException("Iroh: the surface does not support TRANSFER_DST swapchain images "
                        + "(supportedUsageFlags=0x" + Integer.toHexString(supportedUsage) + "); the final scene copy needs it");
            }
            imageUsage |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            createInfo.imageUsage(imageUsage);

            Queue.QueueFamilyIndices indices = Queue.getQueueFamilies();

            if (indices.graphicsFamily != indices.presentFamily) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT);
                createInfo.pQueueFamilyIndices(stack.ints(indices.graphicsFamily, indices.presentFamily));
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            createInfo.preTransform(surfaceProperties.capabilities.currentTransform());
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            createInfo.presentMode(presentMode);
            createInfo.clipped(true);

            createInfo.oldSwapchain(this.swapChainId);

            LongBuffer pSwapChain = stack.longs(VK_NULL_HANDLE);

            int result = vkCreateSwapchainKHR(device, createInfo, null, pSwapChain);
            Vulkan.checkResult(result, "Failed to create swap chain");

            if (this.swapChainId != VK_NULL_HANDLE) {
                this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));
                vkDestroySwapchainKHR(device, this.swapChainId, null);
            }

            this.swapChainId = pSwapChain.get(0);

            vkGetSwapchainImagesKHR(device, this.swapChainId, imageCount, null);

            LongBuffer pSwapchainImages = stack.mallocLong(imageCount.get(0));

            vkGetSwapchainImagesKHR(device, this.swapChainId, imageCount, pSwapchainImages);

            this.swapChainImages = new ArrayList<>(imageCount.get(0));

            this.width = extent2D.width();
            this.height = extent2D.height();

            VkFormatProperties formatProperties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(device.getPhysicalDevice(), this.format, formatProperties);
            this.canBlitTo = (formatProperties.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_DST_BIT) != 0;

            Initializer.LOGGER.info("Iroh: swapchain {}x{} format {} colorspace {} images {} present mode {} blitDst {}",
                    this.width, this.height, this.format, surfaceFormat.colorSpace(), imageCount.get(0),
                    getDisplayModeString(presentMode), this.canBlitTo);

            for (int i = 0; i < pSwapchainImages.capacity(); i++) {
                long imageId = pSwapchainImages.get(i);
                long imageView = VulkanImage.createImageView(imageId, this.format, VK_IMAGE_ASPECT_COLOR_BIT, 1);

                VulkanImage image = new VulkanImage("Swapchain", imageId, this.format, 1, this.width, this.height, 4, imageUsage, imageView);
                image.updateTextureSampler(true, true, false);
                this.swapChainImages.add(image);
            }
        }
    }

    private long[] createFramebuffers(RenderPass renderPass) {

        try (MemoryStack stack = MemoryStack.stackPush()) {

            long[] framebuffers = new long[this.swapChainImages.size()];

            for (int i = 0; i < this.swapChainImages.size(); ++i) {
                LongBuffer attachments = this.depthAttachment != null
                        ? stack.longs(this.swapChainImages.get(i).getImageView(), this.depthAttachment.getImageView())
                        : stack.longs(this.swapChainImages.get(i).getImageView());

                LongBuffer pFramebuffer = stack.mallocLong(1);

                VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack);
                framebufferInfo.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
                framebufferInfo.renderPass(renderPass.getId());
                framebufferInfo.width(this.width);
                framebufferInfo.height(this.height);
                framebufferInfo.layers(1);
                framebufferInfo.pAttachments(attachments);

                if (vkCreateFramebuffer(Vulkan.getVkDevice(), framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create framebuffer");
                }

                framebuffers[i] = pFramebuffer.get(0);
            }

            return framebuffers;
        }
    }

    @Override
    protected long getFramebufferId(RenderPass renderPass) {
        long[] framebuffers = this.FBO_map.computeIfAbsent(renderPass.id, renderPass1 -> createFramebuffers(renderPass));
        return framebuffers[Renderer.getCurrentImage()];
    }

    public void cleanUp() {
        VkDevice device = Vulkan.getVkDevice();

        if (!DYNAMIC_RENDERING) {
            this.FBO_map.forEach((pass, framebuffers) -> Arrays.stream(framebuffers).forEach(id -> vkDestroyFramebuffer(getVkDevice(), id, null)));
            this.FBO_map.clear();
        }

        // Iroh (R-1): image views first (they belong to the swapchain images), then the swapchain.
        // swapChainId is VK_NULL_HANDLE while minimised (0x0): nothing to destroy then.
        if (this.swapChainId != VK_NULL_HANDLE) {
            this.swapChainImages.forEach(image -> vkDestroyImageView(device, image.getImageView(), null));
            vkDestroySwapchainKHR(device, this.swapChainId, null);
            this.swapChainId = VK_NULL_HANDLE;
        }

        if (this.depthAttachment != null)
            this.depthAttachment.free();
    }

    /** Iroh (R-1): whether vkCmdBlitImage may write the swapchain format (needed only when it differs from the scene format or size). */
    public boolean canBlitTo() {
        return this.canBlitTo;
    }

    public long getId() {
        return this.swapChainId;
    }

    public List<VulkanImage> getImages() {
        return this.swapChainImages;
    }

    public long getImageId(int i) {
        return this.swapChainImages.get(i).getId();
    }

    public VkExtent2D getExtent() {
        return this.extent2D;
    }

    public VulkanImage getColorAttachment() {
        return this.swapChainImages.get(Renderer.getCurrentImage());
    }

    public long getImageView(int i) {
        return this.swapChainImages.get(i).getImageView();
    }

    private VkSurfaceFormatKHR getFormat(VkSurfaceFormatKHR.Buffer availableFormats) {
        List<VkSurfaceFormatKHR> list = availableFormats.stream().toList();

        VkSurfaceFormatKHR format = list.get(0);

        for (VkSurfaceFormatKHR availableFormat : list) {
            if (availableFormat.format() == VK_FORMAT_R8G8B8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                return availableFormat;

            if (availableFormat.format() == VK_FORMAT_B8G8R8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                format = availableFormat;
            }
        }

        if (format.format() == VK_FORMAT_B8G8R8A8_UNORM)
            isBGRAformat = true;
        return format;
    }

    private int getPresentMode(IntBuffer availablePresentModes) {
        int requestedMode = vsync ? VK_PRESENT_MODE_FIFO_KHR : defUncappedMode;

        // FIFO mode is the only mode that has to be supported
        if (requestedMode == VK_PRESENT_MODE_FIFO_KHR)
            return VK_PRESENT_MODE_FIFO_KHR;

        for (int i = 0; i < availablePresentModes.capacity(); i++) {
            if (availablePresentModes.get(i) == requestedMode) {
                return requestedMode;
            }
        }

        Initializer.LOGGER.warn("Requested mode not supported: " + getDisplayModeString(requestedMode) + ": using FIFO present mode");
        return VK_PRESENT_MODE_FIFO_KHR;
    }

    private String getDisplayModeString(int requestedMode) {
        return switch (requestedMode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "Immediate";
            case VK_PRESENT_MODE_MAILBOX_KHR -> "Mailbox (FastSync)";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO Relaxed (Adaptive VSync)";
            default -> "FIFO (VSync)";
        };
    }

    private static VkExtent2D getExtent(VkSurfaceCapabilitiesKHR capabilities) {

        if (capabilities.currentExtent().width() != UINT32_MAX) {
            return capabilities.currentExtent();
        }

        // Fallback
        IntBuffer width = stackGet().ints(0);
        IntBuffer height = stackGet().ints(0);

        glfwGetFramebufferSize(window, width, height);

        VkExtent2D actualExtent = VkExtent2D.mallocStack().set(width.get(0), height.get(0));

        VkExtent2D minExtent = capabilities.minImageExtent();
        VkExtent2D maxExtent = capabilities.maxImageExtent();

        actualExtent.width(MathUtil.clamp(minExtent.width(), maxExtent.width(), actualExtent.width()));
        actualExtent.height(MathUtil.clamp(minExtent.height(), maxExtent.height(), actualExtent.height()));

        return actualExtent;
    }

    private static int checkPresentMode(int... requestedModes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var a = DeviceManager.querySurfaceProperties(vkDevice.getPhysicalDevice(), stack).presentModes;
            for (int dMode : requestedModes) {
                for (int i = 0; i < a.capacity(); i++) {
                    if (a.get(i) == dMode) {
                        return dMode;
                    }
                }
            }
            return VK_PRESENT_MODE_FIFO_KHR; // If None of the request modes exist/are supported by Driver
        }
    }

    /**
     * Iroh (R-1): true when the surface can hold a swapchain again (non-zero extent). Renderer polls this
     * while rendering is skipped for a 0x0 swapchain, because a surface can come back without a GLFW
     * framebuffer-size event (seen as a frozen last frame: nothing acquired, rendered or presented).
     */
    public static boolean surfaceHasExtent() {
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(DeviceManager.physicalDevice, Vulkan.getSurface(), capabilities);

            if (capabilities.currentExtent().width() != UINT32_MAX) {
                return capabilities.currentExtent().width() > 0 && capabilities.currentExtent().height() > 0;
            }

            IntBuffer width = stack.ints(0);
            IntBuffer height = stack.ints(0);
            glfwGetFramebufferSize(window, width, height);
            return width.get(0) > 0 && height.get(0) > 0;
        }
    }

    public boolean isVsync() {
        return this.vsync;
    }

    public void setVsync(boolean vsync) {
        this.vsync = vsync;
    }

    public int getImagesNum() {
        return this.swapChainImages.size();
    }
}
