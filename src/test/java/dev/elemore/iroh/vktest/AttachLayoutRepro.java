package dev.elemore.iroh.vktest;

import dev.elemore.iroh.glshim.FreshImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK12.*;

/**
 * V-3 (td-fe54e9) repro on lavapipe with VK_LAYER_KHRONOS_validation (CPU only; refuses any other ICD).
 * <p>
 * Iroh keeps one layout per VulkanImage ("tracked") and records barriers from it into two command buffers per frame: the
 * upload buffer U (submitted first) and the frame buffer F. The tracker is only true if every recorded buffer executes,
 * in the order the tracker assumed. This harness drives raw Vulkan with a small model of Iroh's rules (compare-and-skip
 * transitions, GL framebuffer pass = attachment layouts inside, colour final SHADER_READ_ONLY, depth final
 * DEPTH_STENCIL_ATTACHMENT; FreshImage at allocation; the U/F choice by "used this frame") and checks the validation layer.
 * <ul>
 * <li>case seq-depth / seq-colour: fresh -> sample -> attach -> sample, every frame submitted (the card's sequence).</li>
 * <li>case dropped-frame: the in-game gate-create failure. Frame 1 attaches the fresh GPUBooster pair, then a swapchain
 *   update is pending; OLD Renderer.submitFrame dropped the recorded F (U was submitted), the tracker kept its changes.
 *   Frame 2 attaches again. OLD = 1 error "expects DEPTH_STENCIL_ATTACHMENT_OPTIMAL, current SHADER_READ_ONLY" (v4 log).</li>
 * <li>case dropped-frame-rc14: the same without FreshImage = 2 errors (depth expects DEPTH current UNDEFINED, colour
 *   expects SHADER_READ_ONLY current UNDEFINED), the exact rc14 pair: confirms the diagnosis.</li>
 * <li>case attach-then-upload: a depth attachment whose layout already matched (no barrier) was not marked "used this
 *   frame", so a later upload went to U and executed before the pass. OLD = error, NEW (begin marks attachments) = 0.</li>
 * </ul>
 * OLD objects are named "v0-negative-control" (V-0's vkvalCheck allowlist on branches that have it). Last line
 * "# attachlayout PASS|FAIL ...".
 */
public final class AttachLayoutRepro {
    static final List<String> ERRORS = new ArrayList<>();

    static final int READ = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, COLOR = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            DEPTH = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, DST = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

    public static void main(String[] args) throws Exception {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp")) throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at lavapipe");
        boolean pass = true;
        StringBuilder sum = new StringBuilder();
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(s).sType$Default().apiVersion(VK_API_VERSION_1_2);
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app)
                    .ppEnabledLayerNames(s.pointers(s.UTF8("VK_LAYER_KHRONOS_validation")))
                    .ppEnabledExtensionNames(s.pointers(s.UTF8("VK_EXT_debug_utils")));
            PointerBuffer pp = s.mallocPointer(1);
            FreshTextureLayoutRepro.ck(vkCreateInstance(ici, null, pp), "vkCreateInstance (validation layer missing?)");
            VkInstance instance = new VkInstance(pp.get(0), ici);
            VkDebugUtilsMessengerCallbackEXT cb = VkDebugUtilsMessengerCallbackEXT.create((sev, type, data, user) -> {
                if ((sev & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0)
                    ERRORS.add(VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString());
                return VK_FALSE;
            });
            VkDebugUtilsMessengerCreateInfoEXT mci = VkDebugUtilsMessengerCreateInfoEXT.calloc(s).sType$Default()
                    .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                    .messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT)
                    .pfnUserCallback(cb);
            LongBuffer lb = s.mallocLong(1);
            FreshTextureLayoutRepro.ck(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, mci, null, lb), "messenger");
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
            FreshTextureLayoutRepro.ck(vkCreateDevice(phys, dci, null, pp), "vkCreateDevice");
            VkDevice dev = new VkDevice(pp.get(0), phys, dci);
            vkGetDeviceQueue(dev, 0, 0, pp);
            VkQueue q = new VkQueue(pp.get(0), dev);
            FreshTextureLayoutRepro.ck(vkCreateCommandPool(dev, VkCommandPoolCreateInfo.calloc(s).sType$Default().queueFamilyIndex(0), null, lb), "pool");
            long pool = lb.get(0);
            VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.calloc(s);
            vkGetPhysicalDeviceMemoryProperties(phys, mem);
            Env env = new Env(dev, q, pool, mem);

            for (String c : new String[]{"seq-depth", "seq-colour"}) {
                int o = runCase(s, env, c, false), nw = runCase(s, env, c, true);
                boolean ok = o == 0 && nw == 0; // the attachment path itself was already consistent
                pass &= ok;
                line(sum, c, o, nw, ok);
            }
            for (String c : new String[]{"dropped-frame", "attach-then-upload"}) {
                int before = ERRORS.size();
                int o = runCase(s, env, c, false);
                boolean flagged = o > 0 && ERRORS.subList(before, ERRORS.size()).stream().anyMatch(e -> e.contains("DEPTH_STENCIL_ATTACHMENT_OPTIMAL")
                        && e.contains("current layout is VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL"));
                int nw = runCase(s, env, c, true);
                boolean ok = flagged && nw == 0;
                pass &= ok;
                line(sum, c, o, nw, ok);
            }
            int before = ERRORS.size();
            int rc14 = runCase(s, env, "dropped-frame-rc14", false);
            List<String> e14 = ERRORS.subList(before, ERRORS.size());
            boolean rc14Shape = rc14 == 2
                    && e14.stream().anyMatch(e -> e.contains("to be in layout VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL--instead, current layout is VK_IMAGE_LAYOUT_UNDEFINED"))
                    && e14.stream().anyMatch(e -> e.contains("to be in layout VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL--instead, current layout is VK_IMAGE_LAYOUT_UNDEFINED"));
            int rc14New = runCase(s, env, "dropped-frame-rc14", true);
            pass &= rc14Shape && rc14New == 0;
            line(sum, "dropped-frame-rc14", rc14, rc14New, rc14Shape && rc14New == 0);
        }
        System.out.print(sum);
        for (String e : ERRORS.subList(0, Math.min(ERRORS.size(), 4)))
            System.out.println("  validation (old): " + e.substring(Math.max(0, e.indexOf("expects")), Math.min(e.length(), Math.max(0, e.indexOf("expects")) + 230)));
        System.out.println((pass ? "# attachlayout PASS" : "# attachlayout FAIL") + " errors total=" + ERRORS.size() + " (all from OLD paths when PASS)");
        System.exit(pass ? 0 : 1);
    }

    static void line(StringBuilder sb, String c, int o, int nw, boolean ok) {
        sb.append("  case ").append(c).append(": old errors=").append(o).append(" new errors=").append(nw).append(ok ? "  ok" : "  UNEXPECTED").append('\n');
    }

    record Env(VkDevice dev, VkQueue q, long pool, VkPhysicalDeviceMemoryProperties mem) {}

    /** The Iroh tracker model: one tracked layout per image plus the U/F bookkeeping of VulkanImage. */
    static final class Img {
        final long img, view;
        final int aspect;
        final boolean depth;
        int layout = VK_IMAGE_LAYOUT_UNDEFINED;
        long frameUse = -1;
        boolean uploadOwned;

        Img(long[] iv, int aspect, boolean depth) {
            this.img = iv[0];
            this.view = iv[1];
            this.aspect = aspect;
            this.depth = depth;
        }
    }

    /** One run of frames: U and F of the current frame, the old/new switch, the frame serial. */
    static final class Run {
        final Env env;
        final boolean fixed;
        VkCommandBuffer u, f;
        long serial;
        final List<Img> touched = new ArrayList<>();

        Run(Env env, boolean fixed) {
            this.env = env;
            this.fixed = fixed;
        }

        VkCommandBuffer alloc(MemoryStack s) {
            PointerBuffer pc = s.mallocPointer(1);
            FreshTextureLayoutRepro.ck(vkAllocateCommandBuffers(env.dev, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(env.pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pc), "cb");
            VkCommandBuffer cb = new VkCommandBuffer(pc.get(0), env.dev);
            FreshTextureLayoutRepro.begin(s, cb);
            return cb;
        }

        void beginFrame(MemoryStack s) {
            serial++;
            f = alloc(s);
        }

        VkCommandBuffer upload(MemoryStack s) {
            if (u == null) u = alloc(s);
            return u;
        }

        /** VulkanImage.noteRecordedIn */
        void note(Img i, VkCommandBuffer cb) {
            if (cb == u) {
                i.uploadOwned = true;
                if (!touched.contains(i)) touched.add(i);
            } else if (cb == f) {
                i.uploadOwned = false;
                i.frameUse = serial;
            }
        }

        /** VulkanImage.transitionImageLayout: compare and skip; stage/access as in the V-3 helper. */
        void transition(MemoryStack s, VkCommandBuffer cb, Img i, int newLayout) {
            if (i.layout == newLayout) return;
            barrier(s, cb, i.img, i.aspect, i.layout, newLayout);
            i.layout = newLayout;
            note(i, cb);
        }

        /** VkGlTexture.texImage2D(null) -> VulkanImage.initFresh (FreshImage, the real code). */
        void fresh(MemoryStack s, Img i) {
            VkCommandBuffer cb = upload(s);
            FreshImage.record(s, cb, i.img, i.aspect, i.depth, 1);
            i.layout = READ;
            note(i, cb);
        }

        /** VulkanImage.readOnlyLayout with no pass bound (a draw samples the texture). */
        void sample(MemoryStack s, Img i) {
            if (i.layout == READ) { i.frameUse = serial; return; }
            transition(s, i.uploadOwned ? upload(s) : f, i, READ);
            i.frameUse = serial;
        }

        /** Renderer.beginRendering + endRenderPass of a GL framebuffer (VkGlFramebuffer.createAndBind pass). */
        void attach(MemoryStack s, long rp, long fb, int w, int h, Img color, Img depth) {
            if (color != null) transition(s, f, color, COLOR);
            if (depth != null) transition(s, f, depth, DEPTH);
            if (fixed) { // V-3: RenderPass.beginRenderPass marks its attachments used by the frame buffer
                if (color != null) { color.frameUse = serial; color.uploadOwned = false; }
                if (depth != null) { depth.frameUse = serial; depth.uploadOwned = false; }
            }
            VkRenderPassBeginInfo rb = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(rp).framebuffer(fb);
            rb.renderArea().extent().set(w, h);
            vkCmdBeginRenderPass(f, rb, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdEndRenderPass(f);
            if (color != null) color.layout = READ;  // VkGlFramebuffer colour finalLayout
            if (depth != null) depth.layout = DEPTH; // RenderPass default depth finalLayout
        }

        /** VulkanImage.uploadSubTextureAsync: in the frame buffer if the frame used the image, else in U (clear = the copy). */
        void uploadInto(MemoryStack s, Img i) {
            VkCommandBuffer cb = i.frameUse == serial ? f : upload(s);
            transition(s, cb, i, DST);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, s);
            range.get(0).aspectMask(i.aspect).levelCount(1).layerCount(1);
            if (i.depth) vkCmdClearDepthStencilImage(cb, i.img, DST, VkClearDepthStencilValue.calloc(s).depth(0.5f), range);
            else vkCmdClearColorImage(cb, i.img, DST, VkClearColorValue.calloc(s), range);
            if (cb == f) transition(s, f, i, READ); // flushInFrame: back to READ in the frame buffer
        }

        /**
         * Renderer.endFrame: U submitted first (ImageUploadHelper.submitCommands turns U-owned TRANSFER_DST images back to
         * READ), then F. {@code swapchainUpdate}: OLD submitFrame returned without submitting F; NEW always submits.
         */
        void endFrame(MemoryStack s, boolean swapchainUpdate) {
            if (u != null) {
                for (Img i : touched) {
                    if (i.uploadOwned && i.layout == DST) transition(s, u, i, READ);
                    i.uploadOwned = false;
                }
                touched.clear();
                FreshTextureLayoutRepro.ck(vkEndCommandBuffer(u), "end u");
                FreshTextureLayoutRepro.submit(s, env.q, u);
                u = null;
            }
            FreshTextureLayoutRepro.ck(vkEndCommandBuffer(f), "end f");
            if (!swapchainUpdate || fixed)
                FreshTextureLayoutRepro.submit(s, env.q, f);
            f = null; // a dropped F is never executed (recreateSwapChain resets it)
            vkQueueWaitIdle(env.q);
        }
    }

    static int runCase(MemoryStack outer, Env env, String kase, boolean fixed) {
        int before = ERRORS.size();
        final int W = 1280, H = 720;
        try (MemoryStack s = outer.push()) {
            LongBuffer lb = s.mallocLong(1);
            String tag = (fixed ? "v3-new " : "v0-negative-control ") + kase;
            Img color = new Img(FreshTextureLayoutRepro.image(s, env.dev, env.mem, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                    VK_IMAGE_ASPECT_COLOR_BIT, W, H, 1, 0), VK_IMAGE_ASPECT_COLOR_BIT, false);
            Img depth = new Img(FreshTextureLayoutRepro.image(s, env.dev, env.mem, VK_FORMAT_D32_SFLOAT, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                    VK_IMAGE_ASPECT_DEPTH_BIT, W, H, 1, 0), VK_IMAGE_ASPECT_DEPTH_BIT, true);
            name(s, env.dev, color.img, tag + " colour");
            name(s, env.dev, depth.img, tag + " depth");
            // Iroh's RenderPass for a GL framebuffer: initial = attachment layouts (barriers come from the tracker), LOAD
            VkAttachmentDescription.Buffer at = VkAttachmentDescription.calloc(2, s);
            at.get(0).format(VK_FORMAT_R8G8B8A8_UNORM).samples(1).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(COLOR).finalLayout(READ);
            at.get(1).format(VK_FORMAT_D32_SFLOAT).samples(1).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(DEPTH).finalLayout(DEPTH);
            VkAttachmentReference.Buffer cref = VkAttachmentReference.calloc(1, s).attachment(0).layout(COLOR);
            VkAttachmentReference dref = VkAttachmentReference.calloc(s).attachment(1).layout(DEPTH);
            VkSubpassDescription.Buffer sp = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1).pColorAttachments(cref).pDepthStencilAttachment(dref);
            VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(1, s);
            dep.get(0).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL).srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT).srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            FreshTextureLayoutRepro.ck(vkCreateRenderPass(env.dev, VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(at).pSubpasses(sp)
                    .pDependencies(dep), null, lb), "renderpass");
            long rp = lb.get(0);
            FreshTextureLayoutRepro.ck(vkCreateFramebuffer(env.dev, VkFramebufferCreateInfo.calloc(s).sType$Default().renderPass(rp)
                    .pAttachments(s.longs(color.view, depth.view)).width(W).height(H).layers(1), null, lb), "framebuffer");
            long fb = lb.get(0);

            Run r = new Run(env, fixed);
            switch (kase) {
                case "seq-depth", "seq-colour" -> {
                    Img t = kase.equals("seq-depth") ? depth : color;
                    r.beginFrame(s);
                    r.fresh(s, color);
                    r.fresh(s, depth);
                    r.sample(s, t);
                    r.attach(s, rp, fb, W, H, color, depth);
                    r.sample(s, t);
                    r.endFrame(s, false);
                    r.beginFrame(s);           // next frame: same again
                    r.sample(s, t);
                    r.attach(s, rp, fb, W, H, color, depth);
                    r.sample(s, t);
                    r.endFrame(s, false);
                }
                case "dropped-frame", "dropped-frame-rc14" -> {
                    r.beginFrame(s);
                    if (kase.equals("dropped-frame")) { r.fresh(s, color); r.fresh(s, depth); }
                    r.attach(s, rp, fb, W, H, color, depth);
                    r.sample(s, color);
                    r.endFrame(s, true);       // Minecraft.resizeDisplay mid-frame (same size): swapchain update pending
                    r.beginFrame(s);
                    r.attach(s, rp, fb, W, H, color, depth);
                    r.sample(s, color);
                    r.sample(s, depth);
                    r.endFrame(s, false);
                }
                case "attach-then-upload" -> {
                    r.beginFrame(s);
                    r.fresh(s, color);
                    r.fresh(s, depth);
                    r.attach(s, rp, fb, W, H, color, depth);
                    r.endFrame(s, false);
                    r.beginFrame(s);
                    r.attach(s, rp, fb, W, H, color, depth); // depth already DEPTH: no barrier recorded
                    r.uploadInto(s, depth);
                    r.endFrame(s, false);
                }
                default -> throw new IllegalArgumentException(kase);
            }
            vkQueueWaitIdle(env.q);
            vkDestroyFramebuffer(env.dev, fb, null);
            vkDestroyRenderPass(env.dev, rp, null);
        }
        return ERRORS.size() - before;
    }

    /** Same masks as VulkanImage.transitionImageLayout after V-3. */
    static void barrier(MemoryStack s, VkCommandBuffer cb, long image, int aspect, int oldL, int newL) {
        int[] src = srcOf(oldL), dst = dstOf(newL);
        try (MemoryStack f = s.push()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, f);
            b.sType$Default().oldLayout(oldL).newLayout(newL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image).srcAccessMask(src[1]).dstAccessMask(dst[1]);
            b.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(VK_REMAINING_MIP_LEVELS).baseArrayLayer(0).layerCount(VK_REMAINING_ARRAY_LAYERS);
            vkCmdPipelineBarrier(cb, src[0], dst[0], 0, null, null, b);
        }
    }

    static int[] srcOf(int l) {
        return switch (l) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> new int[]{VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0};
            case DST -> new int[]{VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT};
            case READ -> new int[]{VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0};
            case COLOR -> new int[]{VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT};
            case DEPTH -> new int[]{VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT};
            default -> throw new IllegalArgumentException("layout " + l);
        };
    }

    static int[] dstOf(int l) {
        return switch (l) {
            case DST -> new int[]{VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT};
            case READ -> new int[]{VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT};
            case COLOR -> new int[]{VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT};
            case DEPTH -> new int[]{VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT};
            default -> throw new IllegalArgumentException("layout " + l);
        };
    }

    static void name(MemoryStack s, VkDevice dev, long handle, String name) {
        try (MemoryStack f = s.push()) {
            EXTDebugUtils.vkSetDebugUtilsObjectNameEXT(dev, VkDebugUtilsObjectNameInfoEXT.calloc(f).sType$Default()
                    .objectType(VK_OBJECT_TYPE_IMAGE).objectHandle(handle).pObjectName(f.UTF8(name)));
        }
    }
}
