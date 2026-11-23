package dev.elemore.iroh.vktest;

import dev.elemore.iroh.glshim.FreshImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK12.*;

/**
 * td-5cbe77 repro on lavapipe with VK_LAYER_KHRONOS_validation (CPU only; refuses any other ICD).
 * Models the GPUBooster render-target pair: a 1280x720 colour and a depth texture allocated through glTexImage2D with no
 * data, then used by a pass that expects them in SHADER_READ_ONLY (what Iroh's tracker claims after allocation).
 * F-1: three cases (texture pair, renderbuffer pair with TRANSFER_SRC, 4-level images attached at level 2; call sites VkGlRenderbuffer.allocateImage / VkGlTexture.texImage2D).
 * "old" = Iroh before the fix (nothing recorded at allocation): validation must report the layout error at vkQueueSubmit.
 * "new" = FreshImage.record first (the upload buffer submit): 0 errors. Last line "# freshtex PASS|FAIL ...".
 */
public final class FreshTextureLayoutRepro {
    static final List<String> ERRORS = new ArrayList<>();
    static boolean allOldFlagged;

    public static void main(String[] args) throws Exception {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp")) throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at lavapipe");
        int oldErrors, newErrors;
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(s).sType$Default().apiVersion(VK_API_VERSION_1_2);
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app)
                    .ppEnabledLayerNames(s.pointers(s.UTF8("VK_LAYER_KHRONOS_validation")))
                    .ppEnabledExtensionNames(s.pointers(s.UTF8("VK_EXT_debug_utils")));
            PointerBuffer pp = s.mallocPointer(1);
            ck(vkCreateInstance(ici, null, pp), "vkCreateInstance (validation layer missing?)");
            VkInstance instance = new VkInstance(pp.get(0), ici);
            VkDebugUtilsMessengerCallbackEXT cb = VkDebugUtilsMessengerCallbackEXT.create((sev, type, data, user) -> {
                if ((sev & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0)
                    ERRORS.add(VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString());
                return VK_FALSE;
            });
            VkDebugUtilsMessengerCreateInfoEXT mci = VkDebugUtilsMessengerCreateInfoEXT.calloc(s).sType$Default()
                    .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT).messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT)
                    .pfnUserCallback(cb);
            LongBuffer lb = s.mallocLong(1);
            ck(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, mci, null, lb), "messenger");
            IntBuffer n = s.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, n, null);
            PointerBuffer devs = s.mallocPointer(n.get(0));
            vkEnumeratePhysicalDevices(instance, n, devs);
            VkPhysicalDevice phys = null;
            for (int i = 0; i < devs.capacity(); i++) {
                VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), instance);
                VkPhysicalDeviceProperties pr = VkPhysicalDeviceProperties.calloc(s);
                vkGetPhysicalDeviceProperties(pd, pr);
                if (pr.deviceType() == VK_PHYSICAL_DEVICE_TYPE_CPU) { phys = pd; break; }
            }
            if (phys == null) throw new IllegalStateException("no CPU Vulkan device: this harness never uses a GPU");
            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(0).pQueuePriorities(s.floats(1f));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(qci);
            ck(vkCreateDevice(phys, dci, null, pp), "vkCreateDevice");
            VkDevice dev = new VkDevice(pp.get(0), phys, dci);
            vkGetDeviceQueue(dev, 0, 0, pp);
            VkQueue q = new VkQueue(pp.get(0), dev);
            ck(vkCreateCommandPool(dev, VkCommandPoolCreateInfo.calloc(s).sType$Default().queueFamilyIndex(0), null, lb), "pool");
            long pool = lb.get(0);
            VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.calloc(s);
            vkGetPhysicalDeviceMemoryProperties(phys, mem);

            // case 0 = GL texture pair (V-2), 1 = renderbuffer pair (other size, TRANSFER_SRC too), 2 = mip>0: 4-level images, pass attaches level 2
            int[] oldE = new int[3], newE = new int[3];
            for (int c = 0; c < 3; c++) {
                oldE[c] = runCase(s, dev, q, pool, mem, false, c);
                newE[c] = runCase(s, dev, q, pool, mem, true, c);
                System.out.println("  case " + new String[]{"texture", "renderbuffer", "mip>0"}[c] + ": old errors=" + oldE[c] + " new errors=" + newE[c]);
            }
            oldErrors = oldE[0] + oldE[1] + oldE[2];
            newErrors = newE[0] + newE[1] + newE[2];
            allOldFlagged = oldE[0] > 0 && oldE[1] > 0 && oldE[2] > 0;
        }
        boolean oldFlagged = allOldFlagged && oldErrors > 0 && ERRORS.stream().anyMatch(e -> e.contains("UNDEFINED"));
        for (String e : ERRORS.subList(0, Math.min(ERRORS.size(), 3))) System.out.println("  validation: " + e.substring(0, Math.min(e.length(), 260)));
        System.out.println((oldFlagged && newErrors == 0 ? "# freshtex PASS" : "# freshtex FAIL") + " old(no init) errors=" + oldErrors + " new(FreshImage) errors=" + newErrors);
        System.exit(oldFlagged && newErrors == 0 ? 0 : 1);
    }

    static int runCase(MemoryStack outer, VkDevice dev, VkQueue q, long pool, VkPhysicalDeviceMemoryProperties mem, boolean fresh, int kase) {
        int W = kase == 1 ? 800 : 1280, H = kase == 1 ? 600 : 720, levels = kase == 2 ? 4 : 1;
        int extra = kase == 1 ? VK_IMAGE_USAGE_TRANSFER_SRC_BIT : 0;
        int viewLevel = kase == 2 ? 2 : 0;
        int vw = Math.max(1, W >> viewLevel), vh = Math.max(1, H >> viewLevel);
        int before = ERRORS.size();
        try (MemoryStack s = outer.push()) {
            LongBuffer lb = s.mallocLong(1);
            long[] color = image(s, dev, mem, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | extra, VK_IMAGE_ASPECT_COLOR_BIT, W, H, levels, viewLevel, fresh);
            long[] depth = image(s, dev, mem, VK_FORMAT_D32_SFLOAT, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | extra, VK_IMAGE_ASPECT_DEPTH_BIT, W, H, levels, viewLevel, fresh);
            // pass: both attachments begin in SHADER_READ_ONLY (the layout Iroh's tracker holds), LOAD, attachment layouts inside
            VkAttachmentDescription.Buffer at = VkAttachmentDescription.calloc(2, s);
            at.get(0).format(VK_FORMAT_R8G8B8A8_UNORM).samples(1).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            at.get(1).format(VK_FORMAT_D32_SFLOAT).samples(1).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkAttachmentReference.Buffer cref = VkAttachmentReference.calloc(1, s).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkAttachmentReference dref = VkAttachmentReference.calloc(s).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer sp = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1).pColorAttachments(cref).pDepthStencilAttachment(dref);
            ck(vkCreateRenderPass(dev, VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(at).pSubpasses(sp), null, lb), "renderpass");
            long rp = lb.get(0);
            ck(vkCreateFramebuffer(dev, VkFramebufferCreateInfo.calloc(s).sType$Default().renderPass(rp).pAttachments(s.longs(color[1], depth[1]))
                    .width(vw).height(vh).layers(1), null, lb), "framebuffer");
            long fb = lb.get(0);

            PointerBuffer pc = s.mallocPointer(1);
            ck(vkAllocateCommandBuffers(dev, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pc), "cb");
            VkCommandBuffer upload = new VkCommandBuffer(pc.get(0), dev);
            ck(vkAllocateCommandBuffers(dev, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pc), "cb");
            VkCommandBuffer frame = new VkCommandBuffer(pc.get(0), dev);
            if (fresh) {
                begin(s, upload);
                FreshImage.record(s, upload, color[0], VK_IMAGE_ASPECT_COLOR_BIT, false, levels);
                FreshImage.record(s, upload, depth[0], VK_IMAGE_ASPECT_DEPTH_BIT, true, levels);
                ck(vkEndCommandBuffer(upload), "end");
                submit(s, q, upload);
            }
            begin(s, frame);
            VkRenderPassBeginInfo rb = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(rp).framebuffer(fb);
            rb.renderArea().extent().set(vw, vh);
            vkCmdBeginRenderPass(frame, rb, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdEndRenderPass(frame);
            ck(vkEndCommandBuffer(frame), "end");
            submit(s, q, frame);
            vkQueueWaitIdle(q);
            vkDestroyFramebuffer(dev, fb, null);
            vkDestroyRenderPass(dev, rp, null);
        }
        return ERRORS.size() - before;
    }

    /** returns {image, view}; memory is left to process exit (lavapipe, a few MB). */
    static long[] image(MemoryStack s, VkDevice dev, VkPhysicalDeviceMemoryProperties mp, int fmt, int usage, int aspect, int w, int h, int levels, int viewLevel, boolean fresh) {
        try (MemoryStack f = s.push()) {
            LongBuffer lb = f.mallocLong(1);
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(f).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(fmt).mipLevels(levels).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().set(w, h, 1);
            ck(vkCreateImage(dev, ci, null, lb), "image");
            long img = lb.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(f);
            vkGetImageMemoryRequirements(dev, img, mr);
            int type = 0;
            for (int i = 0; i < mp.memoryTypeCount(); i++) if ((mr.memoryTypeBits() & (1 << i)) != 0) { type = i; break; }
            ck(vkAllocateMemory(dev, VkMemoryAllocateInfo.calloc(f).sType$Default().allocationSize(mr.size()).memoryTypeIndex(type), null, lb), "alloc");
            ck(vkBindImageMemory(dev, img, lb.get(0), 0), "bind");
            if (!fresh) { // V-0 negative control: tools/vkval-allowlist.txt accepts the layout error for exactly these images
                ck(EXTDebugUtils.vkSetDebugUtilsObjectNameEXT(dev, VkDebugUtilsObjectNameInfoEXT.calloc(f).sType$Default()
                        .objectType(VK_OBJECT_TYPE_IMAGE).objectHandle(img).pObjectName(f.UTF8("v0-negative-control"))), "name");
            }
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(f).sType$Default().image(img).viewType(VK_IMAGE_VIEW_TYPE_2D).format(fmt);
            vi.subresourceRange().aspectMask(aspect).baseMipLevel(viewLevel).levelCount(1).layerCount(1);
            ck(vkCreateImageView(dev, vi, null, lb), "view");
            return new long[]{img, lb.get(0)};
        }
    }

    static void begin(MemoryStack s, VkCommandBuffer cb) {
        ck(vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin");
    }

    static void submit(MemoryStack s, VkQueue q, VkCommandBuffer cb) {
        ck(vkQueueSubmit(q, VkSubmitInfo.calloc(1, s).sType$Default().pCommandBuffers(s.pointers(cb)), 0L), "submit");
        vkQueueWaitIdle(q);
    }

    static void ck(int r, String what) {
        if (r != VK_SUCCESS) throw new IllegalStateException(what + ": VkResult " + r);
    }
}
