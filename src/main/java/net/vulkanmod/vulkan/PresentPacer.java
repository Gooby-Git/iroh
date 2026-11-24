package net.vulkanmod.vulkan;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.util.VkEnum;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPresentId.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRPresentId.VK_STRUCTURE_TYPE_PRESENT_ID_KHR;
import static org.lwjgl.vulkan.KHRPresentWait.VK_KHR_PRESENT_WAIT_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPresentWait.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRPresentWait.vkWaitForPresentKHR;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/**
 * P2-10: optional VK_KHR_present_wait pacing, OFF by default ({@code -Diroh.presentWait=true}). When both VK_KHR_present_id and
 * VK_KHR_present_wait are offered and their features supported they are enabled at device creation; every present then carries an
 * id, and after queuing present N the render thread waits until present N-1 has reached the display (at most one present
 * outstanding beyond the frame being built, so latency is bounded while frames in flight stay 2). The wait is inside the frame's
 * present call, so it counts as BUSY time for Proctor's limiter hook (limiter time = RenderSystem.limitDisplayFPS only); compare
 * runs with the switch in the same state. Any wait failure other than a timeout / out-of-date turns the pacer off for the session.
 */
public final class PresentPacer {
    public static final boolean REQUESTED = Boolean.getBoolean("iroh.presentWait");
    private static final long TIMEOUT_NS = 50_000_000L;
    private static volatile boolean enabled;
    private static long nextId;

    private PresentPacer() {}

    public static boolean enabled() { return enabled; }

    /** device creation: adds the two extensions to {@code exts} and links their feature structs behind {@code tail} when everything is offered */
    public static void configure(MemoryStack stack, VkPhysicalDevice pd, List<String> exts, VkPhysicalDeviceVulkan12Features tail) {
        if (!REQUESTED) return;
        Set<String> avail = VkEnum.deviceExtensions(pd);
        if (!avail.contains(VK_KHR_PRESENT_ID_EXTENSION_NAME) || !avail.contains(VK_KHR_PRESENT_WAIT_EXTENSION_NAME)) {
            Initializer.LOGGER.info("Iroh: present_wait pacing requested, not offered by this device (present_id={} present_wait={})",
                    avail.contains(VK_KHR_PRESENT_ID_EXTENSION_NAME), avail.contains(VK_KHR_PRESENT_WAIT_EXTENSION_NAME));
            return;
        }
        VkPhysicalDevicePresentIdFeaturesKHR qId = VkPhysicalDevicePresentIdFeaturesKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR);
        VkPhysicalDevicePresentWaitFeaturesKHR qWait = VkPhysicalDevicePresentWaitFeaturesKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_FEATURES_KHR);
        qId.pNext(qWait.address());
        VkPhysicalDeviceFeatures2 q = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
        q.pNext(qId.address());
        vkGetPhysicalDeviceFeatures2(pd, q);
        if (!qId.presentId() || !qWait.presentWait()) {
            Initializer.LOGGER.info("Iroh: present_wait pacing requested, features not supported (presentId={} presentWait={})", qId.presentId(), qWait.presentWait());
            return;
        }
        VkPhysicalDevicePresentIdFeaturesKHR id = VkPhysicalDevicePresentIdFeaturesKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_ID_FEATURES_KHR).presentId(true);
        VkPhysicalDevicePresentWaitFeaturesKHR wait = VkPhysicalDevicePresentWaitFeaturesKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRESENT_WAIT_FEATURES_KHR).presentWait(true);
        // chain: tail -> wait -> id -> whatever followed tail (raw addresses: the typed pNext(struct) overload would overwrite the chain)
        id.pNext(tail.pNext());
        wait.pNext(id.address());
        tail.pNext(wait.address());
        exts.add(VK_KHR_PRESENT_ID_EXTENSION_NAME);
        exts.add(VK_KHR_PRESENT_WAIT_EXTENSION_NAME);
        enabled = true;
        Initializer.LOGGER.info("Iroh: present_wait pacing ON (VK_KHR_present_id + VK_KHR_present_wait enabled)");
    }

    /** present time: hangs a VkPresentIdKHR (id returned through {@link #lastId}) off {@code info}; no-op when off */
    public static void attach(MemoryStack stack, VkPresentInfoKHR info) {
        if (!enabled) return;
        VkPresentIdKHR pid = VkPresentIdKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_PRESENT_ID_KHR);
        pid.swapchainCount(1);
        pid.pPresentIds(stack.longs(++nextId));
        info.pNext(pid.address());
    }

    /** after a successful present: waits until the previous present reached the display (bounded) */
    public static void afterPresent(VkDevice device, long swapchain) {
        if (!enabled || nextId < 2) return;
        int r = vkWaitForPresentKHR(device, swapchain, nextId - 1, TIMEOUT_NS);
        if (r < 0 && r != org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
            enabled = false;
            Initializer.LOGGER.warn("Iroh: vkWaitForPresentKHR failed ({}), present_wait pacing off for this session", r);
        }
    }
}
