package dev.elemore.iroh.rt.game;

import dev.elemore.iroh.rt.RtCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK10.*;

/**
 * P5-2 device-creation hook (DeviceManager.createLogicalDevice calls {@link #chain}, {@link #extensions},
 * {@link #deviceCreated}; merge notes in knowledge/rt-iroh.md). Integration rule (vulkanmod.md INT-1): ONE feature struct
 * per sType in the pNext chain. So RT adds exactly one VkPhysicalDeviceAccelerationStructureFeaturesKHR and one
 * VkPhysicalDeviceRayQueryFeaturesKHR, and sets {@code bufferDeviceAddress} in the SINGLE VkPhysicalDeviceVulkan12Features
 * the device code (DeviceCaps / api 4) owns; it never creates a second 1.2 struct. With RT not requested every method
 * returns its input unchanged after one read (no query, no allocation): the rc14 device is created bit for bit.
 */
public final class RtDeviceFeatures {
    private RtDeviceFeatures() {}

    public static final String[] EXTENSIONS = {VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME, VK_KHR_RAY_QUERY_EXTENSION_NAME,
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME};

    /** RT features were put into the create-info chain (requested AND available AND no failure). */
    public static volatile boolean enabled;
    /** What the device offered (queried only when requested). */
    public static volatile RtCapabilities caps = RtCapabilities.NONE;
    /** R16G16B16A16_SNORM accepted as an AS vertex format (Iroh's terrain vertex; R0 probe). */
    public static volatile boolean snormVertexFormat;

    /**
     * Appends the RT feature structs in front of {@code tail} and sets bufferDeviceAddress on {@code enable12} when RT
     * was requested and the device has everything; returns the new pNext value for the 1.2 struct (= {@code tail} when
     * off). Never throws: a failure is recorded (seam {@code rt}) and the chain is left as it was.
     */
    public static long chain(MemoryStack s, VkPhysicalDevice pd, VkPhysicalDeviceVulkan12Features enable12, long tail) {
        if (!RtSwitch.requestedAtLaunch()) return tail;
        try {
            RtFailures.inject("deviceFeatures");
            boolean ext = hasExtensions(s, pd);
            VkPhysicalDeviceAccelerationStructureFeaturesKHR as = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(s).sType$Default();
            VkPhysicalDeviceRayQueryFeaturesKHR rq = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(s).sType$Default().pNext(as.address());
            VkPhysicalDeviceVulkan12Features a12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default().pNext(rq.address());
            vkGetPhysicalDeviceFeatures2(pd, VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(a12.address()));
            VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(s);
            vkGetPhysicalDeviceMemoryProperties(pd, mp);
            long local = 0;
            for (int i = 0; i < mp.memoryHeapCount(); i++)
                if ((mp.memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) local = Math.max(local, mp.memoryHeaps(i).size());
            caps = new RtCapabilities(ext && as.accelerationStructure(), ext && rq.rayQuery(), a12.bufferDeviceAddress(), false, false, local);
            if (!caps.supported()) return tail;
            VkFormatProperties fp = VkFormatProperties.calloc(s);
            vkGetPhysicalDeviceFormatProperties(pd, VK_FORMAT_R16G16B16A16_SNORM, fp);
            snormVertexFormat = (fp.bufferFeatures() & VK_FORMAT_FEATURE_ACCELERATION_STRUCTURE_VERTEX_BUFFER_BIT_KHR) != 0;
            enable12.bufferDeviceAddress(true);
            VkPhysicalDeviceAccelerationStructureFeaturesKHR eas = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(s).sType$Default()
                    .accelerationStructure(true).pNext(tail);
            VkPhysicalDeviceRayQueryFeaturesKHR erq = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(s).sType$Default().rayQuery(true).pNext(eas.address());
            enabled = true;
            return erq.address();
        } catch (Throwable t) {
            enabled = false;
            try {
                enable12.bufferDeviceAddress(false);
            } catch (Throwable ignored) {
            }
            RtSwitch.fallBack("deviceFeatures", t);
            return tail;
        }
    }

    static boolean hasExtensions(MemoryStack s, VkPhysicalDevice pd) {
        IntBuffer n = s.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(pd, (String) null, n, null);
        VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(n.get(0), s);
        vkEnumerateDeviceExtensionProperties(pd, (String) null, n, props);
        Set<String> have = new java.util.HashSet<>();
        for (VkExtensionProperties p : props) have.add(p.extensionNameString());
        for (String e : EXTENSIONS) if (!have.contains(e)) return false;
        return true;
    }

    /** The device extension set: {@code base} unchanged (same object) unless the RT features were chained. */
    public static Set<String> extensions(Set<String> base) {
        if (!enabled) return base;
        Set<String> out = new LinkedHashSet<>(base);
        java.util.Collections.addAll(out, EXTENSIONS);
        return out;
    }

    /** After vkCreateDevice succeeded with the chain: RT may run (the session itself starts lazily at the first frame). */
    public static void deviceCreated() {
        if (!enabled) return;
        RtSwitch.ON = true;
        RtFrameHooks.install();
    }

    /** Appended to the self-check device{..} bits; empty when RT was not requested (the rc14 line stays identical). */
    public static String selfCheckBits() {
        if (!RtSwitch.requestedAtLaunch()) return "";
        RtCapabilities c = caps;
        return " rt=" + (enabled ? "[accelerationStructure rayQuery bufferDeviceAddress snormAsVertex=" + snormVertexFormat + "]"
                : "unavailable(as=" + c.accelerationStructure() + " rq=" + c.rayQuery() + " bda=" + c.bufferDeviceAddress()
                + (RtFailures.reason() != null ? " failed" : "") + ")");
    }

    public static void resetForTest() {
        enabled = false;
        caps = RtCapabilities.NONE;
        snormVertexFormat = false;
    }
}
