package dev.elemore.iroh.vktest;

import dev.elemore.iroh.temporal.DeviceCaps;
import dev.elemore.iroh.upscale.fsr.FsrUpscaler;
import net.vulkanmod.vulkan.util.VkEnum;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.VK12.*;

/**
 * H1B (RQ-H1-c): the PRODUCTION capability decision (DeviceCaps.apply -> linkFeatureChain -> vkCreateDevice -> the tier getters) run once per
 * device, so the hardware matrix (tools/hw-matrix.sh, Khronos profiles on lavapipe) exercises it per GPU tier. For each device it:
 * (1) creates the rc14 device (upscale features off) and checks nothing is enabled; (2) creates the upscale device with the real chain and checks
 * every enabled bit is offered, and that DeviceCaps' fsr / velocity / fp16 answers equal an independent derivation from raw Vulkan queries;
 * (3) builds the real FSR context when fsrCapable says so (must succeed); (4) validation errors must be 0. Reports tier, enabled features and
 * upscaler availability. Run: ./gradlew hwCapsRepro. Last line "# hwcaps PASS|FAIL ..." (key=value tier line follows as "# hwcaps-tier ...").
 */
public final class HwCapsRepro {
    static final List<String> PROBLEMS = new ArrayList<>();

    public static void main(String[] a) {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp")) throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at lavapipe");
        String tier = "?", name = "?", extra = "";
        try (MemoryStack s = MemoryStack.stackPush()) {
            Float16Repro.init(s);
            VkPhysicalDevice phys = Float16Repro.phys;
            VkPhysicalDeviceProperties pr = VkPhysicalDeviceProperties.calloc(s);
            vkGetPhysicalDeviceProperties(phys, pr);
            name = pr.deviceNameString();
            Set<String> exts = VkEnum.deviceExtensions(phys);
            int before = Float16Repro.ERRORS.size();

            // (1) rc14 device: features not requested
            System.setProperty("iroh.upscale.features", "off");
            create(s, false);
            check(!DeviceCaps.requested && !DeviceCaps.enabledFloat16 && !DeviceCaps.enabledInt16 && !DeviceCaps.enabledStorage16 && !DeviceCaps.fp16ShadersAllowed(),
                "features off must enable nothing: " + DeviceCaps.report());
            check(!DeviceCaps.velocityCapable() && !DeviceCaps.fsrCapable(), "features off must report no velocity/fsr");

            // (2) upscale device, the real decision
            System.setProperty("iroh.upscale.features", "on");
            boolean fsrMade = create(s, true);
            Raw raw = Raw.query(s, phys);
            boolean fsrExpect = raw.extFormats && raw.writeWithoutFormat && raw.sgBasic && raw.sgQuad && raw.sgCompute;
            boolean velExpect = raw.extFormats && raw.rg16fStorage;
            boolean fp16Expect = raw.float16 && raw.int16 && raw.storage16;
            check(DeviceCaps.requested, "features=on must be requested");
            check(DeviceCaps.fsrCapable() == fsrExpect, "fsrCapable " + DeviceCaps.fsrCapable() + " != raw " + fsrExpect);
            check(DeviceCaps.velocityCapable() == velExpect, "velocityCapable " + DeviceCaps.velocityCapable() + " != raw " + velExpect);
            check(DeviceCaps.fp16ShadersAllowed() == fp16Expect, "fp16ShadersAllowed " + DeviceCaps.fp16ShadersAllowed() + " != raw " + fp16Expect);
            check(!DeviceCaps.enabledFloat16 || raw.float16, "float16 enabled but not offered");
            check(!DeviceCaps.enabledInt16 || raw.int16, "int16 enabled but not offered");
            check(!DeviceCaps.enabledStorage16 || raw.storage16, "storage16 enabled but not offered");
            // idempotent: a second decision on the same inputs gives the same answer
            String r1 = DeviceCaps.report();
            create(s, true);
            check(r1.equals(DeviceCaps.report()), "decision is not deterministic: " + r1 + " vs " + DeviceCaps.report());

            boolean natives = dev.elemore.iroh.upscale.fsr.NativeLoader.load();
            if (natives && DeviceCaps.fsrCapable()) check(fsrMade, "FSR context could not be created on a device DeviceCaps calls fsrCapable");

            String upTier = !DeviceCaps.velocityCapable() ? "base" : !DeviceCaps.fsrCapable() ? "velocity" : DeviceCaps.fp16ShadersAllowed() ? "fsr-fp16" : "fsr-fp32";
            boolean nvidia = pr.vendorID() == 0x10DE;
            tier = upTier;
            extra = " fsr3=" + (natives && DeviceCaps.fsrCapable() ? "available" : natives ? "needs-device-features" : "needs-native-library")
                + " fsrCtx=" + (fsrMade ? "created" : "n/a") + " dlss=" + (nvidia ? "notYet(nvidia)" : "needsNvidia")
                + rtTier(s, phys, exts);
            for (String e : Float16Repro.ERRORS.subList(before, Float16Repro.ERRORS.size())) {
                System.out.println("    ! " + e.substring(0, Math.min(170, e.length())).replace("\n", " "));
            }
            check(Float16Repro.ERRORS.size() == before, (Float16Repro.ERRORS.size() - before) + " validation errors");
            System.out.println("# hwcaps-tier device=\"" + name + "\" api=" + VK_API_VERSION_MAJOR(pr.apiVersion()) + "." + VK_API_VERSION_MINOR(pr.apiVersion())
                + " exts=" + exts.size() + " tier=" + tier + " fp16=" + DeviceCaps.enabledFloat16 + " int16=" + DeviceCaps.enabledInt16 + " storage16=" + DeviceCaps.enabledStorage16
                + " subgroup=" + DeviceCaps.subgroupSize + (DeviceCaps.subgroupQuad ? "+quad" : "") + " rg16fStorage=" + DeviceCaps.rg16fStorage + extra);
        } catch (Throwable t) {
            PROBLEMS.add("crash: " + t);
            t.printStackTrace(System.out);
        }
        for (String p : PROBLEMS) System.out.println("  problem: " + p);
        boolean ok = PROBLEMS.isEmpty();
        System.out.println((ok ? "# hwcaps PASS" : "# hwcaps FAIL") + " " + name + " tier=" + tier);
        System.exit(ok ? 0 : 1);
    }

    /** RT tier hook: the 2b branch has no RT core. */
    static String rtTier(MemoryStack s, VkPhysicalDevice phys, Set<String> exts) { return " rt=n/a(branch has no RT)"; }

    static void check(boolean ok, String msg) { if (!ok) PROBLEMS.add(msg); }

    /** What a raw Vulkan query says, independent of DeviceCaps. */
    record Raw(boolean extFormats, boolean writeWithoutFormat, boolean float16, boolean int16, boolean storage16,
               boolean sgBasic, boolean sgQuad, boolean sgCompute, boolean rg16fStorage) {
        static Raw query(MemoryStack outer, VkPhysicalDevice phys) {
            try (MemoryStack s = outer.push()) {
                VkPhysicalDeviceVulkan11Features a11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default();
                VkPhysicalDeviceVulkan12Features a12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
                VkPhysicalDeviceFeatures2 a2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(a11.address());
                a11.pNext(a12.address());
                vkGetPhysicalDeviceFeatures2(phys, a2);
                VkPhysicalDeviceSubgroupProperties sg = VkPhysicalDeviceSubgroupProperties.calloc(s).sType$Default();
                vkGetPhysicalDeviceProperties2(phys, VkPhysicalDeviceProperties2.calloc(s).sType$Default().pNext(sg.address()));
                VkFormatProperties fp = VkFormatProperties.calloc(s);
                vkGetPhysicalDeviceFormatProperties(phys, VK_FORMAT_R16G16_SFLOAT, fp);
                VkPhysicalDeviceFeatures f = a2.features();
                int ops = sg.supportedOperations();
                return new Raw(f.shaderStorageImageExtendedFormats(), f.shaderStorageImageWriteWithoutFormat(), a12.shaderFloat16(), f.shaderInt16(), a11.storageBuffer16BitAccess(),
                    (ops & VK_SUBGROUP_FEATURE_BASIC_BIT) != 0, (ops & VK_SUBGROUP_FEATURE_QUAD_BIT) != 0, (sg.supportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) != 0,
                    (fp.optimalTilingFeatures() & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0);
            }
        }
    }

    /** The production device-creation sequence (DeviceManager order). Returns true when an FSR context was created on it (only attempted when fsrCapable). */
    static boolean create(MemoryStack outer, boolean tryFsr) {
        VkPhysicalDevice phys = Float16Repro.phys;
        try (MemoryStack s = outer.push()) {
            VkPhysicalDeviceVulkan12Features e12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan11Features e11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default();
            VkPhysicalDeviceFeatures2 e2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan11Features a11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan12Features a12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
            VkPhysicalDeviceFeatures2 a2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(a11.address());
            a11.pNext(a12.address());
            vkGetPhysicalDeviceFeatures2(phys, a2);
            DeviceCaps.apply(s, phys, a2.features(), e2.features(), a11, e11, e12);
            DeviceCaps.linkFeatureChain(e2, e11, e12);
            extendChain(s, phys, e12);
            VkDeviceQueueCreateInfo.Buffer q = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(0).pQueuePriorities(s.floats(1f));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(q).pNext(e2.address());
            deviceExtensions(s, dci);
            PointerBuffer pd = s.mallocPointer(1);
            Float16Repro.ck(vkCreateDevice(phys, dci, null, pd));
            VkDevice vdev = new VkDevice(pd.get(0), phys, dci);
            boolean made = false;
            if (tryFsr && DeviceCaps.fsrCapable() && dev.elemore.iroh.upscale.fsr.NativeLoader.load()) {
                int shim = DeviceCaps.fp16ShadersAllowed() ? 0 : FsrUpscaler.NO_FP16;
                try (FsrUpscaler up = FsrUpscaler.create(Float16Repro.instance.address(), phys.address(), vdev.address(), 0L, FsrUpscaler.AUTO_EXPOSURE, shim, 64, 64, 128, 128)) {
                    made = true;
                } catch (RuntimeException e) {
                    System.out.println("  fsr create failed: " + e.getMessage());
                }
            }
            vkDeviceWaitIdle(vdev);
            vkDestroyDevice(vdev, null);
            return made;
        }
    }

    /** Branch hook: extra feature structs the branch's own device code chains behind the 1.2 struct (none on 2b). */
    static void extendChain(MemoryStack s, VkPhysicalDevice phys, VkPhysicalDeviceVulkan12Features e12) {}

    /** Branch hook: device extensions the branch's own device code enables (none on 2b). */
    static void deviceExtensions(MemoryStack s, VkDeviceCreateInfo dci) {}
}
