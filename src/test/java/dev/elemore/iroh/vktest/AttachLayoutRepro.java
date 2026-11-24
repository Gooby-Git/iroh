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
 * <li>case feedback-colour / feedback-depth (V-3b): attach colour + depth, then a draw of the SAME pass samples one of them
 *   (real pipeline, descriptor, draws). OLD = transition into U + descriptor READ while attached = layout errors; NEW =
 *   end pass, GENERAL in F, restart on a GENERAL pass variant, descriptor GENERAL = 0 errors.</li>
 * </ul>
 * OLD objects are named "v0-negative-control" (V-0's vkvalCheck allowlist on branches that have it). Last line
 * "# attachlayout PASS|FAIL ...".
 */
public final class AttachLayoutRepro {
    static final List<String> ERRORS = new ArrayList<>();
    /** F-2b negative control (-Dattach.oldEnd=true): the V-3b variant as before F-2, ending in the normal final layout (GENERAL -> final at pass end) */
    static final boolean OLD_END = Boolean.getBoolean("attach.oldEnd");

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
            if (OLD_END) { // negative control: only the feedback cases on the fixed path, sync-validation hazards counted
                int b0 = ERRORS.size();
                for (String c : new String[]{"feedback-colour", "feedback-depth"}) runCase(s, env, c, true);
                long hz = ERRORS.subList(b0, ERRORS.size()).stream().filter(e -> e.contains(" hazard detected")).count();
                for (String e : ERRORS.subList(b0, Math.min(ERRORS.size(), b0 + 3))) System.out.println("  " + e.substring(0, Math.min(e.length(), 300)));
                System.out.println((hz > 0 ? "# attachlayout-oldend HAZARD " : "# attachlayout-oldend CLEAN ") + hz);
                System.exit(hz > 0 ? 0 : 1);
            }
            for (String c : new String[]{"feedback-colour", "feedback-depth"}) {
                int before2 = ERRORS.size();
                int o = runCase(s, env, c, false);
                boolean flagged = o > 0 && ERRORS.subList(before2, ERRORS.size()).stream().anyMatch(e -> e.contains("layout"));
                int b3 = ERRORS.size();
                int nw = runCase(s, env, c, true);
                if (nw > 0) for (String e : ERRORS.subList(b3, ERRORS.size())) System.out.println("  NEW ERROR: " + e);
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
            name(s, env.dev, color.img, fixed ? tag + " colour" : "v0-negative-control");
            name(s, env.dev, depth.img, fixed ? tag + " depth" : "v0-negative-control");
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
                case "feedback-colour", "feedback-depth" -> {
                    // V-3b: an attached texture sampled by a draw of the SAME pass (GL feedback loop), two frames, two draws per pass
                    Img t = kase.equals("feedback-colour") ? color : depth;
                    Feedback fbk = new Feedback(s, env, rp, color, depth, fb);
                    for (int frame = 0; frame < 2; frame++) {
                        r.beginFrame(s);
                        if (frame == 0) { r.fresh(s, color); r.fresh(s, depth); }
                        fbk.pass(s, r, t);
                        r.endFrame(s, false);
                    }
                    vkQueueWaitIdle(env.q);
                    fbk.destroy();
                }
                default -> throw new IllegalArgumentException(kase);
            }
            vkQueueWaitIdle(env.q);
            vkDestroyFramebuffer(env.dev, fb, null);
            vkDestroyRenderPass(env.dev, rp, null);
        }
        return ERRORS.size() - before;
    }

    /**
     * V-3b model of the feedback path: a pass with draws that sample an attachment. OLD = the transition for the sample is
     * recorded into the upload buffer U and the descriptor says SHADER_READ_ONLY while the pass has the image attached; NEW =
     * VulkanImage.feedbackSample: end the pass, GENERAL in F, restart on a pass variant whose layouts for that image are
     * GENERAL, descriptor GENERAL, a second sample in the same pass costs nothing.
     */
    static final class Feedback {
        final Env env;
        final Img color, depth;
        final long rpNormal, fbo, dsl, pl, pipe, pool, sampler, setRead, setGeneral;
        final long[] variants = new long[4];
        final java.util.Set<Long> written = new java.util.HashSet<>();

        Feedback(MemoryStack outer, Env env, long rpNormal, Img color, Img depth, long fbo) {
            this.env = env; this.color = color; this.depth = depth; this.rpNormal = rpNormal; this.fbo = fbo;
            try (MemoryStack s = outer.push()) {
                LongBuffer lb = s.mallocLong(1);
                VkDescriptorSetLayoutBinding.Buffer bd = VkDescriptorSetLayoutBinding.calloc(1, s)
                        .binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
                FreshTextureLayoutRepro.ck(vkCreateDescriptorSetLayout(env.dev, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(bd), null, lb), "dsl");
                dsl = lb.get(0);
                FreshTextureLayoutRepro.ck(vkCreatePipelineLayout(env.dev, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(dsl)), null, lb), "pl");
                pl = lb.get(0);
                FreshTextureLayoutRepro.ck(vkCreateSampler(env.dev, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, lb), "sampler");
                sampler = lb.get(0);
                VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(1, s).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
                FreshTextureLayoutRepro.ck(vkCreateDescriptorPool(env.dev, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(2).pPoolSizes(ps), null, lb), "pool");
                pool = lb.get(0);
                LongBuffer sets = s.mallocLong(2);
                FreshTextureLayoutRepro.ck(vkAllocateDescriptorSets(env.dev, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(pool).pSetLayouts(s.longs(dsl, dsl)), sets), "sets");
                setRead = sets.get(0);
                setGeneral = sets.get(1);
                long vs = module(s, env.dev, V), fs = module(s, env.dev, F);
                VkPipelineShaderStageCreateInfo.Buffer st = VkPipelineShaderStageCreateInfo.calloc(2, s);
                st.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vs).pName(s.UTF8("main"));
                st.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fs).pName(s.UTF8("main"));
                VkViewport.Buffer vp = VkViewport.calloc(1, s).width(1280).height(720).maxDepth(1f);
                VkRect2D.Buffer sc = VkRect2D.calloc(1, s);
                sc.extent().set(1280, 720);
                VkPipelineColorBlendAttachmentState.Buffer cba = VkPipelineColorBlendAttachmentState.calloc(1, s).colorWriteMask(0xF);
                VkGraphicsPipelineCreateInfo.Buffer gp = VkGraphicsPipelineCreateInfo.calloc(1, s).sType$Default().pStages(st)
                        .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(s).sType$Default())
                        .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                        .pViewportState(VkPipelineViewportStateCreateInfo.calloc(s).sType$Default().viewportCount(1).pViewports(vp).scissorCount(1).pScissors(sc))
                        .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(s).sType$Default().polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).lineWidth(1f))
                        .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(s).sType$Default().rasterizationSamples(1))
                        .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(s).sType$Default().depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_ALWAYS))
                        .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(s).sType$Default().pAttachments(cba))
                        .layout(pl).renderPass(rpNormal);
                FreshTextureLayoutRepro.ck(vkCreateGraphicsPipelines(env.dev, 0L, gp, null, lb), "pipeline");
                pipe = lb.get(0);
                vkDestroyShaderModule(env.dev, vs, null);
                vkDestroyShaderModule(env.dev, fs, null);
                variants[0] = rpNormal;
            }
        }

        static long module(MemoryStack s, VkDevice dev, String b64) {
            byte[] spv = java.util.Base64.getDecoder().decode(b64);
            java.nio.ByteBuffer bb = s.malloc(spv.length);
            bb.put(spv).flip();
            LongBuffer lb = s.mallocLong(1);
            FreshTextureLayoutRepro.ck(vkCreateShaderModule(dev, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(bb), null, lb), "shader");
            return lb.get(0);
        }

        /** the render pass for a feedback mask (1 colour GENERAL, 2 depth GENERAL): same formats, so framebuffer compatible */
        long variant(MemoryStack outer, int mask) {
            if (variants[mask] != 0L) return variants[mask];
            try (MemoryStack s = outer.push()) {
                int cl = (mask & 1) != 0 ? VK_IMAGE_LAYOUT_GENERAL : COLOR, dl = (mask & 2) != 0 ? VK_IMAGE_LAYOUT_GENERAL : DEPTH;
                VkAttachmentDescription.Buffer at = VkAttachmentDescription.calloc(2, s);
                at.get(0).format(VK_FORMAT_R8G8B8A8_UNORM).samples(1).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).initialLayout(cl).finalLayout((mask & 1) != 0 && !OLD_END ? VK_IMAGE_LAYOUT_GENERAL : READ); // mirrors RenderPass.createRenderPass: GENERAL attachments end GENERAL
                at.get(1).format(VK_FORMAT_D32_SFLOAT).samples(1).loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).initialLayout(dl).finalLayout((mask & 2) != 0 && !OLD_END ? VK_IMAGE_LAYOUT_GENERAL : DEPTH);
                VkAttachmentReference.Buffer cref = VkAttachmentReference.calloc(1, s).attachment(0).layout(cl);
                VkAttachmentReference dref = VkAttachmentReference.calloc(s).attachment(1).layout(dl);
                VkSubpassDescription.Buffer sp = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                        .colorAttachmentCount(1).pColorAttachments(cref).pDepthStencilAttachment(dref);
                VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(1, s);
                dep.get(0).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL).srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT).srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                LongBuffer lb = s.mallocLong(1);
                FreshTextureLayoutRepro.ck(vkCreateRenderPass(env.dev, VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(at).pSubpasses(sp).pDependencies(dep), null, lb), "rp variant");
                return variants[mask] = lb.get(0);
            }
        }

        void begin(MemoryStack s, VkCommandBuffer f, long rp) {
            VkRenderPassBeginInfo rb = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(rp).framebuffer(fbo);
            rb.renderArea().extent().set(1280, 720);
            vkCmdBeginRenderPass(f, rb, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(f, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
        }

        void draw(MemoryStack s, VkCommandBuffer f, Img t, boolean general) {
            try (MemoryStack fr = s.push()) {
                // write each set once (a set updated after it was bound in a recording buffer invalidates that buffer)
                if (written.add(t.view * 2 + (general ? 1 : 0))) {
                VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, fr).sampler(sampler).imageView(t.view).imageLayout(general ? VK_IMAGE_LAYOUT_GENERAL : READ);
                VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, fr).sType$Default().dstSet(general ? setGeneral : setRead).dstBinding(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(ii);
                vkUpdateDescriptorSets(env.dev, w, null);
                }
                vkCmdBindDescriptorSets(f, VK_PIPELINE_BIND_POINT_GRAPHICS, pl, 0, fr.longs(general ? setGeneral : setRead), null);
                vkCmdDraw(f, 3, 1, 0, 0);
            }
        }

        void pass(MemoryStack s, Run r, Img t) {
            VkCommandBuffer f = r.f;
            r.transition(s, f, color, COLOR);
            r.transition(s, f, depth, DEPTH);
            if (r.fixed) { color.frameUse = r.serial; color.uploadOwned = false; depth.frameUse = r.serial; depth.uploadOwned = false; }
            begin(s, f, rpNormal);
            if (!r.fixed) {
                // OLD readOnlyLayout: attachment of the bound pass -> the transition goes to the upload buffer (runs before the frame)
                r.transition(s, r.upload(s), t, READ);
                draw(s, f, t, false);
                draw(s, f, t, false);
            } else {
                // NEW VulkanImage.feedbackSample: end pass (tracker = final layouts), GENERAL in F, restart on the variant
                vkCmdEndRenderPass(f);
                color.layout = READ;
                depth.layout = DEPTH;
                int mask = t == color ? 1 : 2;
                r.transition(s, f, t, VK_IMAGE_LAYOUT_GENERAL);
                if (t != color) r.transition(s, f, color, COLOR); else r.transition(s, f, depth, DEPTH);
                begin(s, f, variant(s, mask));
                draw(s, f, t, true);
                draw(s, f, t, true); // second sample, same pass instance: GENERAL already, nothing recorded
            }
            vkCmdEndRenderPass(f);
            color.layout = READ;
            depth.layout = DEPTH;
            if (r.fixed && !OLD_END) t.layout = VK_IMAGE_LAYOUT_GENERAL; // the variant pass ends GENERAL (RenderPass.endRenderPass tracker)
        }

        void destroy() {
            vkDestroyPipeline(env.dev, pipe, null);
            vkDestroyPipelineLayout(env.dev, pl, null);
            vkDestroyDescriptorPool(env.dev, pool, null);
            vkDestroyDescriptorSetLayout(env.dev, dsl, null);
            vkDestroySampler(env.dev, sampler, null);
            for (int i = 1; i < 4; i++) if (variants[i] != 0L) vkDestroyRenderPass(env.dev, variants[i], null);
        }
    }

    static final String V = "AwIjBwAAAQALAA0AKwAAAAAAAAARAAIAAQAAAAsABgABAAAAR0xTTC5zdGQuNDUwAAAAAA4AAwAAAAAAAQAAAA8ABwAAAAAABAAAAG1haW4AAAAADAAAAB0AAAADAAMAAgAAAMIBAAAEAAoAR0xfR09PR0xFX2NwcF9zdHlsZV9saW5lX2RpcmVjdGl2ZQAABAAIAEdMX0dPT0dMRV9pbmNsdWRlX2RpcmVjdGl2ZQAFAAQABAAAAG1haW4AAAAABQADAAkAAABwAAAABQAGAAwAAABnbF9WZXJ0ZXhJbmRleAAABQAGABsAAABnbF9QZXJWZXJ0ZXgAAAAABgAGABsAAAAAAAAAZ2xfUG9zaXRpb24ABgAHABsAAAABAAAAZ2xfUG9pbnRTaXplAAAAAAYABwAbAAAAAgAAAGdsX0NsaXBEaXN0YW5jZQAGAAcAGwAAAAMAAABnbF9DdWxsRGlzdGFuY2UABQADAB0AAAAAAAAARwAEAAwAAAALAAAAKgAAAEcAAwAbAAAAAgAAAEgABQAbAAAAAAAAAAsAAAAAAAAASAAFABsAAAABAAAACwAAAAEAAABIAAUAGwAAAAIAAAALAAAAAwAAAEgABQAbAAAAAwAAAAsAAAAEAAAAEwACAAIAAAAhAAMAAwAAAAIAAAAWAAMABgAAACAAAAAXAAQABwAAAAYAAAACAAAAIAAEAAgAAAAHAAAABwAAABUABAAKAAAAIAAAAAEAAAAgAAQACwAAAAEAAAAKAAAAOwAEAAsAAAAMAAAAAQAAACsABAAKAAAADgAAAAEAAAArAAQACgAAABAAAAACAAAAFwAEABcAAAAGAAAABAAAABUABAAYAAAAIAAAAAAAAAArAAQAGAAAABkAAAABAAAAHAAEABoAAAAGAAAAGQAAAB4ABgAbAAAAFwAAAAYAAAAaAAAAGgAAACAABAAcAAAAAwAAABsAAAA7AAQAHAAAAB0AAAADAAAAKwAEAAoAAAAeAAAAAAAAACsABAAGAAAAIAAAAAAAAEArAAQABgAAACIAAAAAAIA/KwAEAAYAAAAlAAAAAAAAPyAABAApAAAAAwAAABcAAAA2AAUAAgAAAAQAAAAAAAAAAwAAAPgAAgAFAAAAOwAEAAgAAAAJAAAABwAAAD0ABAAKAAAADQAAAAwAAADEAAUACgAAAA8AAAANAAAADgAAAMcABQAKAAAAEQAAAA8AAAAQAAAAbwAEAAYAAAASAAAAEQAAAD0ABAAKAAAAEwAAAAwAAADHAAUACgAAABQAAAATAAAAEAAAAG8ABAAGAAAAFQAAABQAAABQAAUABwAAABYAAAASAAAAFQAAAD4AAwAJAAAAFgAAAD0ABAAHAAAAHwAAAAkAAACOAAUABwAAACEAAAAfAAAAIAAAAFAABQAHAAAAIwAAACIAAAAiAAAAgwAFAAcAAAAkAAAAIQAAACMAAABRAAUABgAAACYAAAAkAAAAAAAAAFEABQAGAAAAJwAAACQAAAABAAAAUAAHABcAAAAoAAAAJgAAACcAAAAlAAAAIgAAAEEABQApAAAAKgAAAB0AAAAeAAAAPgADACoAAAAoAAAA/QABADgAAQA=", F = "AwIjBwAAAQALAA0AHQAAAAAAAAARAAIAAQAAAAsABgABAAAAR0xTTC5zdGQuNDUwAAAAAA4AAwAAAAAAAQAAAA8ABwAEAAAABAAAAG1haW4AAAAACQAAABAAAAAQAAMABAAAAAcAAAADAAMAAgAAAMIBAAAEAAoAR0xfR09PR0xFX2NwcF9zdHlsZV9saW5lX2RpcmVjdGl2ZQAABAAIAEdMX0dPT0dMRV9pbmNsdWRlX2RpcmVjdGl2ZQAFAAQABAAAAG1haW4AAAAABQADAAkAAABvAAAABQADAA0AAAB0AAAABQAGABAAAABnbF9GcmFnQ29vcmQAAAAARwAEAAkAAAAeAAAAAAAAAEcABAANAAAAIQAAAAAAAABHAAQADQAAACIAAAAAAAAARwAEABAAAAALAAAADwAAABMAAgACAAAAIQADAAMAAAACAAAAFgADAAYAAAAgAAAAFwAEAAcAAAAGAAAABAAAACAABAAIAAAAAwAAAAcAAAA7AAQACAAAAAkAAAADAAAAGQAJAAoAAAAGAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAGwADAAsAAAAKAAAAIAAEAAwAAAAAAAAACwAAADsABAAMAAAADQAAAAAAAAAgAAQADwAAAAEAAAAHAAAAOwAEAA8AAAAQAAAAAQAAABcABAARAAAABgAAAAIAAAArAAQABgAAABQAAAAAAKBEKwAEAAYAAAAVAAAAAAA0RCwABQARAAAAFgAAABQAAAAVAAAAFQAEABkAAAAgAAAAAAAAACsABAAZAAAAGgAAAAAAAAA2AAUAAgAAAAQAAAAAAAAAAwAAAPgAAgAFAAAAPQAEAAsAAAAOAAAADQAAAD0ABAAHAAAAEgAAABAAAABPAAcAEQAAABMAAAASAAAAEgAAAAAAAAABAAAAiAAFABEAAAAXAAAAEwAAABYAAABXAAUABwAAABgAAAAOAAAAFwAAAFEABQAGAAAAGwAAABgAAAAAAAAAUAAHAAcAAAAcAAAAGwAAABsAAAAbAAAAGwAAAD4AAwAJAAAAHAAAAP0AAQA4AAEA";

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
            case VK_IMAGE_LAYOUT_GENERAL -> new int[]{VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT};
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
            case VK_IMAGE_LAYOUT_GENERAL -> new int[]{VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT};
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
