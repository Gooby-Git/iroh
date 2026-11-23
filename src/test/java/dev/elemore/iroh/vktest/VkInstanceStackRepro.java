package dev.elemore.iroh.vktest;

import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.vulkan.VK12.*;

/**
 * int-2b-v1 repro (lavapipe only, run with a small -Dorg.lwjgl.system.stackSize=KB so the pack machine's long layer lists are imitated).
 * "old": the pre-fix pattern, an instance extension enumeration on the open MemoryStack frame, then new VkInstance (expected overflow).
 * "new": Vulkan.createInstance(List) as the game now calls it (expected pass). Last line "# vkstack PASS|FAIL mode".
 */
public final class VkInstanceStackRepro {
    public static void main(String[] a) {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp")) throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at lavapipe");
        String mode = a.length > 0 ? a[0] : "new";
        try {
            VkInstance inst;
            if (mode.equals("old")) {
                try (MemoryStack s = MemoryStack.stackPush()) {
                    IntBuffer cnt = s.ints(0);
                    vkEnumerateInstanceExtensionProperties((String) null, cnt, null);
                    VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(cnt.get(0), s); // stays on the frame, as before
                    vkEnumerateInstanceExtensionProperties((String) null, cnt, props);
                    VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(s).sType$Default()
                        .pApplicationInfo(VkApplicationInfo.calloc(s).sType$Default().apiVersion(VK_API_VERSION_1_2));
                    PointerBuffer pp = s.mallocPointer(1);
                    if (vkCreateInstance(ici, null, pp) != VK_SUCCESS) throw new IllegalStateException("vkCreateInstance");
                    inst = new VkInstance(pp.get(0), ici);
                }
            } else {
                inst = Vulkan.createInstance(List.of("VK_KHR_surface"));
            }
            vkDestroyInstance(inst, null);
            System.out.println("# vkstack PASS " + mode + " stackKB=" + System.getProperty("org.lwjgl.system.stackSize"));
        } catch (Throwable t) {
            System.out.println("# vkstack FAIL " + mode + " " + t); if (System.getProperty("trace") != null) t.printStackTrace(System.out);
        }
    }
}
