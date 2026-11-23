package dev.elemore.iroh.ui;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/**
 * What the selected GPU offers beyond what Iroh uses today (card P1-6). Strictly read-only: it enumerates device
 * extensions and queries feature structs; it creates no device and enables nothing. Shown on the About tab and written
 * to the log once at startup as one block. The optional features are the ones the later phases need (ray tracing,
 * upscaler / frame generation latency hooks, cooperative matrix for ML upscalers).
 */
public final class CapabilityReport {
    private static final Logger LOGGER = LogManager.getLogger("Iroh");
    private static CapabilityReport cached;

    public record Item(String id, String label, boolean found, String detail) {}

    public final String gpu;
    public final String vendor;
    public final String driver;
    public final String vulkan;
    public final int extensionCount;
    public final Map<String, Item> items = new LinkedHashMap<>();

    private CapabilityReport(String gpu, String vendor, String driver, String vulkan, int extensionCount) {
        this.gpu = gpu;
        this.vendor = vendor;
        this.driver = driver;
        this.vulkan = vulkan;
        this.extensionCount = extensionCount;
    }

    /** Probes once and caches; never throws (an unprobed report says so). */
    public static synchronized CapabilityReport get() {
        if (cached == null) {
            try {
                cached = probe();
            } catch (Throwable t) {
                LOGGER.warn("Capability probe failed: {}", t.toString());
                cached = new CapabilityReport("unknown", "unknown", "unknown", "unknown", 0);
            }
        }
        return cached;
    }

    public boolean has(String id) {
        Item i = items.get(id);
        return i != null && i.found();
    }

    private static CapabilityReport probe() {
        Device dev = Vulkan.getDevice();
        VkPhysicalDevice pd = DeviceManager.physicalDevice;
        Set<String> ext = net.vulkanmod.vulkan.util.VkEnum.deviceExtensions(pd);
        try (MemoryStack stack = MemoryStack.stackPush()) {

            VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR rtp = null;
            VkPhysicalDeviceRayQueryFeaturesKHR rq = null;
            VkPhysicalDeviceAccelerationStructureFeaturesKHR as = null;
            VkPhysicalDeviceCooperativeMatrixFeaturesKHR cm = null;
            VkPhysicalDeviceVulkan12Features v12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            // chain: f2 -> v12 -> [rtp -> rq -> as -> cm]; optional structs are only chained when their extension exists
            f2.pNext(v12);
            long last = v12.address();
            if (ext.contains("VK_KHR_ray_tracing_pipeline")) {
                rtp = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default();
                VkPhysicalDeviceVulkan12Features.npNext(last, rtp.address());
                last = rtp.address();
            }
            if (ext.contains("VK_KHR_ray_query")) {
                rq = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR.npNext(last, rq.address());
                last = rq.address();
            }
            if (ext.contains("VK_KHR_acceleration_structure")) {
                as = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default();
                VkPhysicalDeviceRayQueryFeaturesKHR.npNext(last, as.address());
                last = as.address();
            }
            if (ext.contains("VK_KHR_cooperative_matrix")) {
                cm = VkPhysicalDeviceCooperativeMatrixFeaturesKHR.calloc(stack).sType$Default();
                VkPhysicalDeviceAccelerationStructureFeaturesKHR.npNext(last, cm.address());
            }
            vkGetPhysicalDeviceFeatures2(pd, f2);

            CapabilityReport r = new CapabilityReport(dev.deviceName, dev.vendorIdString, dev.driverVersion, dev.vkVersion, ext.size());
            r.add("rayTracingPipeline", "Ray tracing pipeline", ext.contains("VK_KHR_ray_tracing_pipeline") && rtp != null && rtp.rayTracingPipeline(),
                    ext.contains("VK_KHR_ray_tracing_pipeline") ? "VK_KHR_ray_tracing_pipeline" : "extension missing");
            r.add("rayQuery", "Ray query", ext.contains("VK_KHR_ray_query") && rq != null && rq.rayQuery(),
                    ext.contains("VK_KHR_ray_query") ? "VK_KHR_ray_query" : "extension missing");
            r.add("accelerationStructure", "Acceleration structure", ext.contains("VK_KHR_acceleration_structure") && as != null && as.accelerationStructure(),
                    ext.contains("VK_KHR_acceleration_structure") ? "VK_KHR_acceleration_structure" : "extension missing");
            boolean di = v12.descriptorIndexing() && v12.runtimeDescriptorArray() && v12.descriptorBindingPartiallyBound();
            r.add("descriptorIndexing", "Descriptor indexing", di, di ? "runtime arrays, partially bound" : "not supported");
            r.add("bufferDeviceAddress", "Buffer device address", v12.bufferDeviceAddress(),
                    v12.bufferDeviceAddress() ? "Vulkan 1.2 feature" : "not supported");
            r.add("antiLag", "AMD Anti-Lag", ext.contains("VK_AMD_anti_lag"), ext.contains("VK_AMD_anti_lag") ? "VK_AMD_anti_lag" : "extension missing");
            r.add("lowLatency2", "NVIDIA Low Latency 2", ext.contains("VK_NV_low_latency2"), ext.contains("VK_NV_low_latency2") ? "VK_NV_low_latency2" : "extension missing");
            boolean coop = ext.contains("VK_KHR_cooperative_matrix") && cm != null && cm.cooperativeMatrix();
            r.add("cooperativeMatrix", "Cooperative matrix", coop,
                    ext.contains("VK_KHR_cooperative_matrix") ? "VK_KHR_cooperative_matrix" : (ext.contains("VK_NV_cooperative_matrix") ? "VK_NV_cooperative_matrix only" : "extension missing"));
            String f8 = null;
            for (String e : ext) if (e.toLowerCase().contains("float8")) f8 = e;
            r.add("float8", "Float8 shader types", f8 != null, f8 != null ? f8 : "extension missing");
            return r;
        }
    }

    private void add(String id, String label, boolean found, String detail) {
        items.put(id, new Item(id, label, found, detail));
    }

    /** One log block, written once at startup. */
    public List<String> lines() {
        List<String> l = new ArrayList<>();
        l.add("GPU: " + gpu + " (" + vendor + ")");
        l.add("Driver: " + driver);
        l.add("Vulkan: " + vulkan + ", " + extensionCount + " device extensions");
        for (Item i : items.values()) l.add(String.format("%-24s %s  %s", i.label(), i.found() ? "yes" : "no ", i.detail()));
        return l;
    }

    public void logBlock() {
        StringBuilder sb = new StringBuilder("Capability report (read-only, nothing enabled by this):");
        for (String s : lines()) sb.append("\n  ").append(s);
        LOGGER.info(sb);
    }
}
