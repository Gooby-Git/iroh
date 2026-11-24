package net.vulkanmod.vulkan.device;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryPriorityFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/**
 * P2-12 (L9 / L10): the optional capabilities of the upload-sync and memory work, decided once at device creation. Each one is used
 * only when the device OFFERS it and Iroh ENABLED it on the device; otherwise the previous path runs:
 * <ul>
 * <li>timeline semaphore (Vulkan 1.2 core, {@code timelineSemaphore}): transfer -> frame ordering through one timeline semaphore per
 *     upload queue instead of one binary semaphore per upload command buffer ({@link net.vulkanmod.vulkan.Synchronization}).
 *     Fallback: the binary-semaphore path.</li>
 * <li>{@code VK_EXT_memory_budget}: VMA gets the allocator flag, so {@code vmaGetHeapBudgets} (MemoryGuard) reads the driver's real
 *     per-process budget instead of VMA's estimate. Fallback: VMA's estimate (80% of the heap).</li>
 * <li>{@code VK_EXT_memory_priority} + feature {@code memoryPriority}: VMA gets the allocator flag, render targets are created with
 *     priority 1.0. Fallback: the default priority (0.5).</li>
 * </ul>
 * Switches (system properties, for the hardware matrix and for bisecting): {@code -Diroh.sync.timeline=off},
 * {@code -Diroh.memory.ext=off} (both budget and priority).
 */
public final class SyncMemCaps {
    public static final String EXT_BUDGET = "VK_EXT_memory_budget";
    public static final String EXT_PRIORITY = "VK_EXT_memory_priority";

    /** the pure decision, for the hardware matrix and unit tests */
    public record Decision(boolean timeline, boolean budget, boolean priority) {
        public String path() {
            return "sync=" + (timeline ? "timeline" : "binary") + " budget=" + (budget ? "ext" : "vma-estimate") + " priority=" + (priority ? "ext" : "default");
        }
    }

    private static volatile Decision decision = new Decision(false, false, false);

    private SyncMemCaps() {}

    public static Decision decide(boolean timelineOffered, boolean budgetExtOffered, boolean priorityExtOffered, boolean priorityFeatureOffered,
                                  boolean timelineSwitchOn, boolean memorySwitchOn) {
        return new Decision(timelineOffered && timelineSwitchOn,
                budgetExtOffered && memorySwitchOn,
                priorityExtOffered && priorityFeatureOffered && memorySwitchOn);
    }

    public static boolean timelineSwitchOn() {
        return !"off".equalsIgnoreCase(System.getProperty("iroh.sync.timeline", "on"));
    }

    public static boolean memorySwitchOn() {
        return !"off".equalsIgnoreCase(System.getProperty("iroh.memory.ext", "on"));
    }

    /** Is VkPhysicalDeviceMemoryPriorityFeaturesEXT.memoryPriority offered? (only meaningful when the extension is) */
    public static boolean priorityFeatureOffered(MemoryStack stack, VkPhysicalDevice phys, Set<String> deviceExtensions) {
        if (!deviceExtensions.contains(EXT_PRIORITY)) return false;
        try (MemoryStack s = stack.push()) {
            VkPhysicalDeviceMemoryPriorityFeaturesEXT p = VkPhysicalDeviceMemoryPriorityFeaturesEXT.calloc(s).sType$Default();
            VkPhysicalDeviceFeatures2 f = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(p.address());
            vkGetPhysicalDeviceFeatures2(phys, f);
            return p.memoryPriority();
        }
    }

    /**
     * Production device-creation step: decides, sets {@code timelineSemaphore} on the enable struct and records the decision. The
     * caller adds {@link #extensionNames()} to the device extensions and chains {@link #chainPriority} when {@code decision.priority()}.
     */
    public static Decision apply(Set<String> deviceExtensions, boolean timelineOffered, boolean priorityFeatureOffered,
                                 VkPhysicalDeviceVulkan12Features enable12) {
        Decision d = decide(timelineOffered, deviceExtensions.contains(EXT_BUDGET), deviceExtensions.contains(EXT_PRIORITY), priorityFeatureOffered,
                timelineSwitchOn(), memorySwitchOn());
        enable12.timelineSemaphore(d.timeline());
        decision = d;
        return d;
    }

    /** device extension names to add to the enabled list for the current decision */
    public static List<String> extensionNames() {
        Decision d = decision;
        List<String> n = new ArrayList<>(2);
        if (d.budget()) n.add(EXT_BUDGET);
        if (d.priority()) n.add(EXT_PRIORITY);
        return n;
    }

    /** Puts the enabled memoryPriority struct at the head of {@code enable12}'s pNext chain (keeps whatever followed). */
    public static void chainPriority(VkPhysicalDeviceVulkan12Features enable12, VkPhysicalDeviceMemoryPriorityFeaturesEXT priority) {
        priority.sType$Default().memoryPriority(true).pNext(enable12.pNext());
        enable12.pNext(priority.address());
    }

    /** render targets at or above this size get their own VkDeviceMemory (RQ-APP1-e) */
    public static final long DEDICATED_MIN_BYTES = 32L << 20;

    private static int bytesPerPixel(int format) {
        return switch (format) {
            case VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_R32G32B32A32_UINT, VK_FORMAT_R32G32B32A32_SINT -> 16;
            case VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_UNORM, VK_FORMAT_R16G16B16A16_SNORM, VK_FORMAT_D32_SFLOAT_S8_UINT -> 8;
            default -> 4; // RGBA8, D32_SFLOAT, D24S8, R32_SFLOAT, RG16F ...
        };
    }

    /** Render target = colour or depth/stencil attachment usage. */
    public static boolean isRenderTarget(int usage) {
        return (usage & (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)) != 0;
    }

    /** VMA priority for an image of {@code usage}: 1.0 for render targets when VK_EXT_memory_priority is on, else -1 (leave VMA's default). */
    public static float imagePriority(int usage) {
        return memoryPriority() && isRenderTarget(usage) ? 1.0f : -1f;
    }

    /** DEDICATED_MEMORY for a render target of at least 32 MiB (no extension needed: core Vulkan 1.1 dedicated allocation) */
    public static boolean imageDedicated(int usage, int w, int h, int format) {
        return isRenderTarget(usage) && (long) w * h * bytesPerPixel(format) >= DEDICATED_MIN_BYTES;
    }

    public static Decision decision() {
        return decision;
    }

    public static boolean timeline() {
        return decision.timeline();
    }

    public static boolean memoryBudget() {
        return decision.budget();
    }

    public static boolean memoryPriority() {
        return decision.priority();
    }

    public static String report() {
        return "sync/memory path: " + decision.path();
    }
}
