package net.vulkanmod.vulkan.util;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Layer and extension enumerations whose count depends on the user's layers and drivers (Gamescope WSI, validation, overlays...).
 * Records are 256+ bytes each, so they live on the heap: the 64 KB MemoryStack holds the caller's frame AND what LWJGL's
 * VkInstance constructor needs, and an unbounded count overflowed it (int-2b-v1, "Out of stack space"). Only the 4-byte count
 * uses a (nested, tiny) stack frame.
 */
public final class VkEnum {
    private VkEnum() {}

    /** Names of instance extensions (layer == null) or of one layer's instance extensions; empty on failure. */
    public static Set<String> instanceExtensions(String layer) {
        Set<String> out = new HashSet<>();
        try (MemoryStack s = MemoryStack.stackPush()) {
            IntBuffer cnt = s.ints(0);
            if (vkEnumerateInstanceExtensionProperties(layer, cnt, null) != VK_SUCCESS || cnt.get(0) <= 0) return out;
            for (int attempt = 0; attempt < 3; attempt++) {
                int n = cnt.get(0);
                VkExtensionProperties.Buffer p = VkExtensionProperties.calloc(n);
                try {
                    int r = vkEnumerateInstanceExtensionProperties(layer, cnt, p);
                    if (r == VK_SUCCESS || r == VK_INCOMPLETE) {
                        for (int i = 0, m = Math.min(n, cnt.get(0)); i < m; i++) out.add(p.get(i).extensionNameString());
                        if (r == VK_SUCCESS) return out;
                    } else return out;
                } finally { MemoryUtil.memFree(p); }
            }
        }
        return out;
    }

    public static Set<String> instanceLayers() {
        Set<String> out = new HashSet<>();
        try (MemoryStack s = MemoryStack.stackPush()) {
            IntBuffer cnt = s.ints(0);
            if (vkEnumerateInstanceLayerProperties(cnt, null) != VK_SUCCESS || cnt.get(0) <= 0) return out;
            for (int attempt = 0; attempt < 3; attempt++) {
                int n = cnt.get(0);
                VkLayerProperties.Buffer p = VkLayerProperties.calloc(n);
                try {
                    int r = vkEnumerateInstanceLayerProperties(cnt, p);
                    if (r == VK_SUCCESS || r == VK_INCOMPLETE) {
                        for (int i = 0, m = Math.min(n, cnt.get(0)); i < m; i++) out.add(p.get(i).layerNameString());
                        if (r == VK_SUCCESS) return out;
                    } else return out;
                } finally { MemoryUtil.memFree(p); }
            }
        }
        return out;
    }

    public static Set<String> deviceExtensions(VkPhysicalDevice dev) {
        Set<String> out = new HashSet<>();
        try (MemoryStack s = MemoryStack.stackPush()) {
            IntBuffer cnt = s.ints(0);
            if (vkEnumerateDeviceExtensionProperties(dev, (String) null, cnt, null) != VK_SUCCESS || cnt.get(0) <= 0) return out;
            for (int attempt = 0; attempt < 3; attempt++) {
                int n = cnt.get(0);
                VkExtensionProperties.Buffer p = VkExtensionProperties.calloc(n);
                try {
                    int r = vkEnumerateDeviceExtensionProperties(dev, (String) null, cnt, p);
                    if (r == VK_SUCCESS || r == VK_INCOMPLETE) {
                        for (int i = 0, m = Math.min(n, cnt.get(0)); i < m; i++) out.add(p.get(i).extensionNameString());
                        if (r == VK_SUCCESS) return out;
                    } else return out;
                } finally { MemoryUtil.memFree(p); }
            }
        }
        return out;
    }
}
