package dev.elemore.iroh.vktest;

import dev.elemore.iroh.rt.game.RtDeviceFeatures;
import dev.elemore.iroh.temporal.DeviceCaps;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * F-2 (card extra check a): the RT feature structs (acceleration structure, ray query, bufferDeviceAddress in the 1.2 struct)
 * must reach vkCreateDevice. Lavapipe + validation layer, the REAL DeviceCaps.linkFeatureChain + RtDeviceFeatures.chain in the order
 * DeviceManager uses. Probe = vkCreateAccelerationStructureKHR on a device-address buffer (validation flags it when the features are
 * not enabled). OLD = the V-3c-era chain (typed pNext overload overwrote the 1.2 struct's link, RT structs hang off it): flagged.
 * NEW: clean and RtDeviceFeatures.enabled. Run with -Diroh.rt=true -Diroh.rt.look=RAY_TRACED. Last line "# rtdevchain PASS|FAIL".
 */
public final class RtDeviceChainRepro {
    public static void main(String[] a) {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp")) throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at lavapipe");
        int[] r = new int[2];
        boolean rtOn;
        try (MemoryStack s = MemoryStack.stackPush()) {
            Float16Repro.init(s);
            String absent = HwCapsRepro.rtAbsent(s, Float16Repro.phys);
            if (absent != null) { // H1B (RQ-H1-b): a device without RT cannot run this harness; hwCapsRepro covers the production fallback
                System.out.println("# rtdevchain SKIP device lacks " + absent);
                System.exit(0);
            }
            r[0] = run(s, false);
            RtDeviceFeatures.enabled = false;
            r[1] = run(s, true);
            rtOn = RtDeviceFeatures.enabled;
        }
        boolean ok = r[0] > 0 && r[1] == 0 && rtOn;
        System.out.println((ok ? "# rtdevchain PASS" : "# rtdevchain FAIL") + " old/new errors " + r[0] + "/" + r[1] + " rtEnabled(new)=" + rtOn + " caps=" + RtDeviceFeatures.caps);
        System.exit(ok ? 0 : 1);
    }

    static int run(MemoryStack outer, boolean fixed) {
        int before = Float16Repro.ERRORS.size();
        VkPhysicalDevice phys = Float16Repro.phys;
        try (MemoryStack s = outer.push()) {
            VkPhysicalDeviceVulkan12Features e12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan11Features e11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default();
            VkPhysicalDeviceFeatures2 e2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default();
            if (fixed) DeviceCaps.linkFeatureChain(e2, e11, e12);
            else e11.pNext(e12.address());
            e12.pNext(RtDeviceFeatures.chain(s, phys, e12, e12.pNext())); // as DeviceManager: RT structs in front of the 1.2 struct's tail
            VkDeviceQueueCreateInfo.Buffer q = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(0).pQueuePriorities(s.floats(1f));
            PointerBuffer ext = s.mallocPointer(RtDeviceFeatures.EXTENSIONS.length);
            for (String e : RtDeviceFeatures.EXTENSIONS) ext.put(s.UTF8(e));
            ext.flip();
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(q).ppEnabledExtensionNames(ext);
            if (fixed) dci.pNext(e2.address());
            else dci.pEnabledFeatures(e2.features()).pNext(e11); // the typed overload: overwrites e11.pNext (the V-4 trap)
            PointerBuffer pd = s.mallocPointer(1);
            Float16Repro.ck(vkCreateDevice(phys, dci, null, pd));
            VkDevice dev = new VkDevice(pd.get(0), phys, dci);
            LongBuffer lb = s.mallocLong(1);
            VkBufferCreateInfo bi = VkBufferCreateInfo.calloc(s).sType$Default().size(1 << 16)
                    .usage(VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            Float16Repro.ck(vkCreateBuffer(dev, bi, null, lb));
            long buf = lb.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(s);
            vkGetBufferMemoryRequirements(dev, buf, mr);
            VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(s);
            vkGetPhysicalDeviceMemoryProperties(phys, mp);
            int type = 0;
            for (int i = 0; i < mp.memoryTypeCount(); i++) if ((mr.memoryTypeBits() & (1 << i)) != 0) { type = i; break; }
            VkMemoryAllocateFlagsInfo fi = VkMemoryAllocateFlagsInfo.calloc(s).sType$Default().flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
            Float16Repro.ck(vkAllocateMemory(dev, VkMemoryAllocateInfo.calloc(s).sType$Default().pNext(fi.address()).allocationSize(mr.size()).memoryTypeIndex(type), null, lb));
            long mem = lb.get(0);
            Float16Repro.ck(vkBindBufferMemory(dev, buf, mem, 0));
            VkAccelerationStructureCreateInfoKHR ci = VkAccelerationStructureCreateInfoKHR.calloc(s).sType$Default().buffer(buf).offset(0).size(1 << 12)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            int res = vkCreateAccelerationStructureKHR(dev, ci, null, lb);
            System.out.println("  fixed=" + fixed + " vkCreateAccelerationStructureKHR=" + res);
            if (res == VK_SUCCESS) vkDestroyAccelerationStructureKHR(dev, lb.get(0), null);
            vkDeviceWaitIdle(dev);
            vkDestroyBuffer(dev, buf, null);
            vkFreeMemory(dev, mem, null);
            vkDestroyDevice(dev, null);
        }
        for (String e : Float16Repro.ERRORS.subList(before, Float16Repro.ERRORS.size())) System.out.println("    ! " + e.substring(0, Math.min(170, e.length())).replace("\n", " "));
        return Float16Repro.ERRORS.size() - before;
    }
}
