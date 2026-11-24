package dev.elemore.iroh.vktest;

import dev.elemore.iroh.temporal.DeviceCaps;
import dev.elemore.iroh.upscale.fsr.FsrUpscaler;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK12.*;

/**
 * td-0fa7b8 repro on lavapipe with VK_LAYER_KHRONOS_validation (CPU only). Two paths, each "old" then "new":
 * <ol>
 * <li>CHAIN: device created with shaderFloat16 enabled. Old = the Vulkan 1.1/1.2 structs chained next to pEnabledFeatures
 *     (what DeviceManager did: the LWJGL typed pNext(struct) overload overwrote V11.pNext, so the 1.2 struct fell out of the chain and the driver never saw shaderFloat16 or drawIndirectCount): every Float16 module is flagged, New = Features2 head
 *     ({@link DeviceCaps#linkFeatureChain}, pEnabledFeatures NULL): clean. Probe = a hand-written Float16 compute module AND the real
 *     FSR 3.1 context (fp16 permutations).</li>
 * <li>FALLBACK: device created WITHOUT shaderFloat16 although the GPU offers it (upscale features not requested at launch). Old = FSR shim
 *     chosen from what the GPU offers (DeviceCaps.shaderFloat16 ...): fp16 permutations on a device without the feature, flagged.
 *     New = {@link DeviceCaps#fp16ShadersAllowed()} (enabled bits): 32-bit permutations, clean.</li>
 * </ol>
 * Last line "# float16 PASS|FAIL ...".
 */
public final class Float16Repro {
    static final List<String> ERRORS = new ArrayList<>();
    static final String GLSL = "#version 450\n#extension GL_EXT_shader_explicit_arithmetic_types_float16 : require\nlayout(local_size_x=1) in;\n"
            + "layout(binding=0, r32f) uniform image2D img;\nvoid main(){ float16_t h = float16_t(1.5); imageStore(img, ivec2(0), vec4(float(h*h))); }\n";

    static VkInstance instance;
    static VkPhysicalDevice phys;

    public static void main(String[] args) {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp")) throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at lavapipe");
        int[] r = new int[8];
        try (MemoryStack s = MemoryStack.stackPush()) {
            init(s);
            ByteBuffer spv = compile();
            boolean natives = dev.elemore.iroh.upscale.fsr.NativeLoader.load();
            System.out.println("natives=" + natives);
            // path 1: CHAIN
            r[0] = run(s, true, false, false, spv, natives);
            r[1] = run(s, true, true, true, spv, natives);
            // path 2: FALLBACK (device without float16; old decision from "offered", new from "enabled")
            r[2] = run(s, false, true, false, spv, natives);
            r[3] = run(s, false, true, true, spv, natives);
        }
        for (String e : ERRORS.subList(0, Math.min(ERRORS.size(), 3))) System.out.println("  validation: " + e.substring(0, Math.min(e.length(), 240)));
        boolean ok = r[0] > 0 && r[1] == 0 && r[2] > 0 && r[3] == 0;
        System.out.println("  chain: old=" + r[0] + " new=" + r[1] + "   fallback: old=" + r[2] + " new=" + r[3]);
        System.out.println((ok ? "# float16 PASS" : "# float16 FAIL") + " chain old/new " + r[0] + "/" + r[1] + " fallback old/new " + r[2] + "/" + r[3]);
        System.exit(ok ? 0 : 1);
    }

    /** @return Float16 validation errors counted; enabling: float16 on the device (chain case) or not (fallback case) */
    static int run(MemoryStack outer, boolean enableFloat16, boolean newPaths, boolean newDecision, ByteBuffer spv, boolean natives) {
        int before = ERRORS.size();
        try (MemoryStack s = outer.push()) {
            VkPhysicalDeviceVulkan12Features e12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan11Features e11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default();
            VkPhysicalDeviceFeatures2 e2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan11Features a11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan12Features a12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
            VkPhysicalDeviceFeatures2 a2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(a11.address());
            a11.pNext(a12.address());
            vkGetPhysicalDeviceFeatures2(phys, a2);
            // the real DeviceCaps decides (requested via -Diroh.upscale.features=on); with enableFloat16=false the 1.2 struct is withheld
            DeviceCaps.apply(s, phys, a2.features(), e2.features(), a11, e11, enableFloat16 ? e12 : null);
                if (newPaths) DeviceCaps.linkFeatureChain(e2, e11, e12);
            VkDeviceQueueCreateInfo.Buffer q = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(0).pQueuePriorities(s.floats(1f));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(q);
            if (newPaths) dci.pNext(e2.address());
            else { e11.pNext(e12.address()); dci.pEnabledFeatures(e2.features()).pNext(e11); }
            PointerBuffer pd = s.mallocPointer(1);
            ck(vkCreateDevice(phys, dci, null, pd));
            VkDevice dev = new VkDevice(pd.get(0), phys, dci);
            LongBuffer lb = s.mallocLong(1);
            // probe 1: a Float16 module (what any fp16 shader is to the device)
            if (enableFloat16) {
                vkCreateShaderModule(dev, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(spv), null, lb);
                vkDestroyShaderModule(dev, lb.get(0), null);
            }
            // probe 2: the real FSR context. Shim flags: old = from what the GPU offers, new = from what the device enabled.
            if (natives) {
                int shim = (newDecision ? DeviceCaps.fp16ShadersAllowed() : DeviceCaps.shaderFloat16 && DeviceCaps.shaderInt16 && DeviceCaps.storage16Bit) ? 0 : FsrUpscaler.NO_FP16;
                try (FsrUpscaler up = FsrUpscaler.create(instance.address(), phys.address(), dev.address(), 0L, FsrUpscaler.AUTO_EXPOSURE, shim, 64, 64, 128, 128)) {
                    System.out.println("  fsr context created shim=" + shim);
                } catch (RuntimeException e) {
                    System.out.println("  fsr create failed: " + e.getMessage());
                }
            }
            vkDeviceWaitIdle(dev);
            vkDestroyDevice(dev, null);
        }
        for (String e : ERRORS.subList(before, ERRORS.size())) System.out.println("    ! " + e.substring(0, Math.min(150, e.length())).replace("\n", " "));
        int n = ERRORS.size() - before; // ANY validation error counts: the new paths must be completely clean
        System.out.println("case enableFloat16=" + enableFloat16 + " newPaths=" + newPaths + " newDecision=" + newDecision + ": errors=" + n);
        return n;
    }

    static ByteBuffer compile() {
        long comp = Shaderc.shaderc_compiler_initialize();
        long res = Shaderc.shaderc_compile_into_spv(comp, GLSL, Shaderc.shaderc_compute_shader, "p.comp", "main", 0);
        ByteBuffer spv = Shaderc.shaderc_result_get_bytes(res);
        if (spv == null) throw new IllegalStateException(Shaderc.shaderc_result_get_error_message(res));
        return spv;
    }

    static void init(MemoryStack s) {
        VkApplicationInfo app = VkApplicationInfo.calloc(s).sType$Default().apiVersion(VK_API_VERSION_1_2);
        VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app)
                .ppEnabledLayerNames(s.pointers(s.UTF8("VK_LAYER_KHRONOS_validation")))
                .ppEnabledExtensionNames(s.pointers(s.UTF8("VK_EXT_debug_utils")));
        PointerBuffer pp = s.mallocPointer(1);
        ck(vkCreateInstance(ici, null, pp));
        instance = new VkInstance(pp.get(0), ici);
        VkDebugUtilsMessengerCallbackEXT cb = VkDebugUtilsMessengerCallbackEXT.create((sev, type, data, user) -> {
            if ((sev & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) ERRORS.add(VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString());
            return VK_FALSE;
        });
        LongBuffer lb = s.mallocLong(1);
        ck(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, VkDebugUtilsMessengerCreateInfoEXT.calloc(s).sType$Default()
                .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT).pfnUserCallback(cb), null, lb));
        IntBuffer n = s.mallocInt(1);
        vkEnumeratePhysicalDevices(instance, n, null);
        PointerBuffer devs = s.mallocPointer(n.get(0));
        vkEnumeratePhysicalDevices(instance, n, devs);
        for (int i = 0; i < devs.capacity(); i++) {
            VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), instance);
            VkPhysicalDeviceProperties pr = VkPhysicalDeviceProperties.calloc(s);
            vkGetPhysicalDeviceProperties(pd, pr);
            if (pr.deviceType() == VK_PHYSICAL_DEVICE_TYPE_CPU) { phys = pd; break; }
        }
        if (phys == null) throw new IllegalStateException("no CPU Vulkan device: this harness never uses a GPU");
    }

    static void ck(int r) { if (r != VK_SUCCESS) throw new IllegalStateException("vk " + r); }
}
