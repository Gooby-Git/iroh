package dev.elemore.iroh.render;

import dev.elemore.iroh.gpu.Glsl;
import net.vulkanmod.vulkan.shader.PipelineState;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * V-0 (td-0cc003 class, real Vulkan): Iroh's REAL {@link CmdState} (with a copy of GraphicsPipeline.dynamicStates) drives real
 * vkCmdBindPipeline / vkCmdSetDepthBias / vkCmdSetLineWidth / vkCmdDraw on lavapipe under VK_LAYER_KHRONOS_validation.
 * Pipelines are created with exactly the dynamic-state lists GraphicsPipeline.createGraphicsPipeline uses (DEPTH_BIAS,
 * VIEWPORT, SCISSOR [+ LINE_WIDTH]); a FOREIGN pipeline (viewport + scissor only, like the pack / api-4 pipelines) stands
 * for a recording Iroh does not own (CmdState.foreign / restore). Negative control: rc14's raw sequence (bind a static
 * line width pipeline, vkCmdSetLineWidth, draw) must be reported as VUID-vkCmdDraw-None-08608 by the layer itself, on a
 * pipeline named "v0-negative-control" (tools/vkval-allowlist.txt accepts exactly that one). Then 300 random frames through
 * CmdState must produce no validation message. What stays out (needs Minecraft): Renderer itself (its constructor reaches
 * Minecraft / the swapchain), so the Renderer -> CmdState wiring is still covered only by passResumeTest's model.
 * ./gradlew dynStateVkReplay ; last line "# dynstatevk PASS|FAIL n/m". lavapipe only.
 */
public final class DynStateVkReplay {
    static int pass, fail;
    static int phase; // 0 setup, 1 negative control, 2 CmdState
    static final List<String>[] msgs = new List[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    static void vk(int r, String what) {
        if (r != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + r);
    }

    static VkInstance instance;
    static VkDevice device;
    static VkQueue queue;
    static long pool, fence, renderPass, framebuffer, image, imageMem, view, layout, messenger;
    static boolean debugUtils;

    static final String VS = """
            #version 450
            void main() { vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2); gl_Position = vec4(p * 2.0 - 1.0, 0.5, 1.0); }
            """;
    static final String FS = """
            #version 450
            layout(location = 0) out vec4 o;
            void main() { o = vec4(1.0, 0.5, 0.25, 1.0); }
            """;

    public static void main(String[] args) {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp")) throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at the lavapipe ICD (use the gradle task)");
        // The rule is GraphicsPipeline.dynamicStates(PipelineState), but ANY static call into GraphicsPipeline runs
        // net.vulkanmod.vulkan.shader.Pipeline's static initializer (DEVICE = Vulkan.getVkDevice(), PIPELINE_CACHE =
        // createPipelineCache()), which needs the game's Vulkan device: NPE headless (verified). Seam needed: the 3-line
        // rule as a static of a Vulkan-free class (e.g. CmdState.dynamicFor(int assemblyRasterState)) that GraphicsPipeline
        // delegates to. Until then: a COPY of the rule over Iroh's real AssemblyRasterState decoder (keep in sync).
        int triDyn = dynFor(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL);
        int lineDyn = dynFor(VK_PRIMITIVE_TOPOLOGY_LINE_LIST, VK_POLYGON_MODE_FILL);
        int polyLineDyn = dynFor(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_LINE);
        check("dynamic-state rule (copy of GraphicsPipeline.dynamicStates): triangles DEPTH_BIAS only, line list / polygon LINE also LINE_WIDTH",
                triDyn == CmdState.DEPTH_BIAS && lineDyn == CmdState.ALL && polyLineDyn == CmdState.ALL);
        try {
            setup();
            long tri = pipeline(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, triDyn, true, "iroh-tri");
            long line = pipeline(VK_PRIMITIVE_TOPOLOGY_LINE_LIST, lineDyn, true, "iroh-lines");
            long foreign = pipeline(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, 0, false, "foreign-pack");
            long negTri = pipeline(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, triDyn, true, "v0-negative-control");

            // 1. negative control: rc14 recorded the GL line width right after a static-line-width bind
            phase = 1;
            VkCommandBuffer cb = begin();
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, negTri);
            vkCmdSetDepthBias(cb, 0f, 0f, 0f);
            vkCmdSetLineWidth(cb, 1f);
            vkCmdDraw(cb, 3, 1, 0, 0);
            submit(cb);
            boolean neg08608 = msgs[1].stream().anyMatch(m -> m.contains("VUID-vkCmdDraw-None-08608"));
            if (debugUtils)
                check("negative control: the layer reports rc14's sequence as VUID-vkCmdDraw-None-08608 (" + msgs[1].size() + " messages)", neg08608);
            else
                System.out.println("SKIP negative control: VK_EXT_debug_utils absent (validation layer not loaded? run with -Pvalidate)");

            // 2. the real CmdState over real commands: 300 frames x 40 random operations
            phase = 2;
            Random r = new Random(0x0cc003L);
            long[] cmdBuf = {0};
            int draws = 0, foreigns = 0, restores = 0;
            for (int f = 0; f < 300; f++) {
                VkCommandBuffer c = begin();
                CmdState st = new CmdState(new CmdState.Backend() {
                    public void bindPipeline(long h) { vkCmdBindPipeline(c, VK_PIPELINE_BIND_POINT_GRAPHICS, h); }
                    public void depthBias(float k, float s) { vkCmdSetDepthBias(c, k, 0f, s); }
                    public void lineWidth(float w) { vkCmdSetLineWidth(c, w); }
                });
                st.newCommandBuffer();
                long savedH = 0;
                int savedDyn = 0;
                for (int i = 0; i < 40; i++) {
                    switch (r.nextInt(8)) {
                        case 0, 1 -> { long h = r.nextBoolean() ? tri : line; int d = h == tri ? triDyn : lineDyn; if (h != st.boundHandle()) st.bind(h, d, h); }
                        case 2 -> st.setDepthBias(r.nextInt(3) - 1, r.nextInt(3) - 1);
                        case 3 -> st.setLineWidth(1f); // wideLines is not enabled: the value stays 1, the command is what is checked
                        case 4 -> { // a foreign recording binds its own pipeline (pack frame / api-4 / Vista): PassResume
                            savedH = st.boundHandle();
                            savedDyn = st.boundDynamic();
                            st.foreign();
                            vkCmdBindPipeline(c, VK_PIPELINE_BIND_POINT_GRAPHICS, foreign);
                            vkCmdDraw(c, 3, 1, 0, 0);
                            foreigns++;
                            if (r.nextBoolean()) { st.restore(savedH, savedDyn, savedH == 0 ? null : savedH); restores++; }
                        }
                        default -> { if (st.boundHandle() != 0) { vkCmdDraw(c, 3, 1, 0, 0); draws++; } }
                    }
                }
                submit(c);
            }
            check("CmdState on real Vulkan: 300 frames, " + draws + " Iroh draws, " + foreigns + " foreign recordings, " + restores
                    + " restores, validation messages " + msgs[2].size() + (msgs[2].isEmpty() ? "" : ": " + msgs[2].get(0)), msgs[2].isEmpty() && draws > 1000);
            if (!debugUtils) System.out.println("     (no messenger: the build's vkvalCheck on the layer log is the authority)");
            check("setup produced no validation message " + msgs[0], msgs[0].isEmpty());

            for (long p : new long[]{tri, line, foreign, negTri}) vkDestroyPipeline(device, p, null);
            teardown();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            fail++;
        }
        System.out.println("# dynstatevk " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }

    /** copy of GraphicsPipeline.dynamicStates (see main); AssemblyRasterState is a static nested class: no outer init */
    static int dynFor(int topology, int polygonMode) {
        int ars = PipelineState.AssemblyRasterState.encode(false, topology, polygonMode);
        return PipelineState.AssemblyRasterState.decodeTopology(ars) == VK_PRIMITIVE_TOPOLOGY_LINE_LIST
                || PipelineState.AssemblyRasterState.decodePolygonMode(ars) == VK_POLYGON_MODE_LINE ? CmdState.ALL : CmdState.DEPTH_BIAS;
    }

    static void setup() {
        try (MemoryStack s = stackPush()) {
            IntBuffer n = s.mallocInt(1);
            vkEnumerateInstanceExtensionProperties((String) null, n, null);
            VkExtensionProperties.Buffer ie = VkExtensionProperties.calloc(n.get(0), s);
            vkEnumerateInstanceExtensionProperties((String) null, n, ie);
            for (int i = 0; i < ie.capacity(); i++) if (ie.get(i).extensionNameString().equals(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) debugUtils = true;
            VkApplicationInfo app = VkApplicationInfo.calloc(s).sType$Default().pApplicationName(s.UTF8("iroh-dynstate-vk")).apiVersion(VK12.VK_API_VERSION_1_2);
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app);
            if (debugUtils) ici.ppEnabledExtensionNames(s.pointers(s.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)));
            PointerBuffer pp = s.mallocPointer(1);
            vk(vkCreateInstance(ici, null, pp), "vkCreateInstance");
            instance = new VkInstance(pp.get(0), ici);
            if (debugUtils) {
                VkDebugUtilsMessengerCreateInfoEXT mci = VkDebugUtilsMessengerCreateInfoEXT.calloc(s).sType$Default()
                        .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                        .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT)
                        .pfnUserCallback((sev, type, data, user) -> {
                            String m = VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString();
                            if (!m.startsWith("Logging validation")) msgs[phase].add(m.replaceAll("\\s+", " "));
                            return VK_FALSE;
                        });
                LongBuffer lb = s.mallocLong(1);
                vk(vkCreateDebugUtilsMessengerEXT(instance, mci, null, lb), "messenger");
                messenger = lb.get(0);
            }
            vkEnumeratePhysicalDevices(instance, n, null);
            PointerBuffer devs = s.mallocPointer(n.get(0));
            vkEnumeratePhysicalDevices(instance, n, devs);
            VkPhysicalDevice phys = null;
            for (int i = 0; i < devs.capacity(); i++) {
                VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), instance);
                VkPhysicalDeviceProperties pr = VkPhysicalDeviceProperties.calloc(s);
                vkGetPhysicalDeviceProperties(pd, pr);
                if (pr.deviceType() == VK_PHYSICAL_DEVICE_TYPE_CPU) { phys = pd; System.out.println("     device " + pr.deviceNameString()); break; }
            }
            if (phys == null) throw new IllegalStateException("no CPU Vulkan device (lavapipe): this harness never uses a GPU");
            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(0).pQueuePriorities(s.floats(1f));
            VkPhysicalDeviceFeatures feat = VkPhysicalDeviceFeatures.calloc(s).fillModeNonSolid(true);
            vk(vkCreateDevice(phys, VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(qci).pEnabledFeatures(feat), null, pp), "vkCreateDevice");
            device = new VkDevice(pp.get(0), phys, VkDeviceCreateInfo.calloc(s).sType$Default());
            vkGetDeviceQueue(device, 0, 0, pp);
            queue = new VkQueue(pp.get(0), device);
            LongBuffer lb = s.mallocLong(1);
            vk(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(s).sType$Default().flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(0), null, lb), "pool");
            pool = lb.get(0);
            vk(vkCreateFence(device, VkFenceCreateInfo.calloc(s).sType$Default(), null, lb), "fence");
            fence = lb.get(0);
            // 16x16 RGBA8 colour target (tiny: lavapipe only, far under the 1 GiB cap)
            VkImageCreateInfo ii = VkImageCreateInfo.calloc(s).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM)
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ii.extent().set(16, 16, 1);
            vk(vkCreateImage(device, ii, null, lb), "image");
            image = lb.get(0);
            VkMemoryRequirements req = VkMemoryRequirements.calloc(s);
            vkGetImageMemoryRequirements(device, image, req);
            if (req.size() > (1L << 30)) throw new IllegalStateException("over the 1 GiB cap");
            VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(s);
            vkGetPhysicalDeviceMemoryProperties(phys, mp);
            int type = -1;
            for (int i = 0; i < mp.memoryTypeCount(); i++) if ((req.memoryTypeBits() & (1 << i)) != 0) { type = i; break; }
            vk(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(req.size()).memoryTypeIndex(type), null, lb), "mem");
            imageMem = lb.get(0);
            vkBindImageMemory(device, image, imageMem, 0);
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(s).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM);
            vi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vk(vkCreateImageView(device, vi, null, lb), "view");
            view = lb.get(0);
            VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(1, s).format(VK_FORMAT_R8G8B8A8_UNORM).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer sub = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1)
                    .pColorAttachments(VkAttachmentReference.calloc(1, s).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL));
            VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(1, s).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            vk(vkCreateRenderPass(device, VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(att).pSubpasses(sub).pDependencies(dep), null, lb), "render pass");
            renderPass = lb.get(0);
            vk(vkCreateFramebuffer(device, VkFramebufferCreateInfo.calloc(s).sType$Default().renderPass(renderPass).pAttachments(s.longs(view))
                    .width(16).height(16).layers(1), null, lb), "framebuffer");
            framebuffer = lb.get(0);
            vk(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(s).sType$Default(), null, lb), "layout");
            layout = lb.get(0);
        }
    }

    /** dynamic-state list exactly as GraphicsPipeline.createGraphicsPipeline (iroh) or viewport + scissor (foreign) */
    static long pipeline(int topology, int dyn, boolean iroh, String name) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            ByteBuffer vs = Glsl.compile("v0:vs", VS, Glsl.VERTEX), fs = Glsl.compile("v0:fs", FS, Glsl.FRAGMENT);
            vk(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(vs), null, lb), "vs");
            long vm = lb.get(0);
            vk(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(fs), null, lb), "fs");
            long fm = lb.get(0);
            MemoryUtil.memFree(vs);
            MemoryUtil.memFree(fs);
            VkPipelineShaderStageCreateInfo.Buffer st = VkPipelineShaderStageCreateInfo.calloc(2, s);
            st.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vm).pName(s.UTF8("main"));
            st.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fm).pName(s.UTF8("main"));
            IntBuffer ds = iroh ? ((dyn & CmdState.LINE_WIDTH) != 0
                    ? s.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_LINE_WIDTH)
                    : s.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))
                    : s.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
            VkPipelineColorBlendAttachmentState.Buffer cba = VkPipelineColorBlendAttachmentState.calloc(1, s).colorWriteMask(0xF);
            VkGraphicsPipelineCreateInfo.Buffer gi = VkGraphicsPipelineCreateInfo.calloc(1, s).sType$Default().pStages(st)
                    .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(s).sType$Default())
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType$Default().topology(topology))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(s).sType$Default().viewportCount(1).scissorCount(1))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(s).sType$Default().polygonMode(VK_POLYGON_MODE_FILL)
                            .cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).depthBiasEnable(iroh).lineWidth(1f))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(s).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(s).sType$Default().pAttachments(cba))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(s).sType$Default().pDynamicStates(ds))
                    .layout(layout).renderPass(renderPass).subpass(0);
            vk(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, gi, null, lb), "pipeline " + name);
            long p = lb.get(0);
            vkDestroyShaderModule(device, vm, null);
            vkDestroyShaderModule(device, fm, null);
            if (debugUtils)
                vkSetDebugUtilsObjectNameEXT(device, VkDebugUtilsObjectNameInfoEXT.calloc(s).sType$Default()
                        .objectType(VK_OBJECT_TYPE_PIPELINE).objectHandle(p).pObjectName(s.UTF8(name)));
            return p;
        }
    }

    static VkCommandBuffer begin() {
        try (MemoryStack s = stackPush()) {
            PointerBuffer pp = s.mallocPointer(1);
            vk(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pp), "cmd");
            VkCommandBuffer c = new VkCommandBuffer(pp.get(0), device);
            vk(vkBeginCommandBuffer(c, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin");
            VkClearValue.Buffer cv = VkClearValue.calloc(1, s);
            VkRenderPassBeginInfo rb = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(renderPass).framebuffer(framebuffer).pClearValues(cv);
            rb.renderArea().extent().set(16, 16);
            vkCmdBeginRenderPass(c, rb, VK_SUBPASS_CONTENTS_INLINE);
            VkViewport.Buffer vp = VkViewport.calloc(1, s).width(16).height(16).maxDepth(1f);
            vkCmdSetViewport(c, 0, vp);
            VkRect2D.Buffer sc = VkRect2D.calloc(1, s);
            sc.get(0).extent().set(16, 16);
            vkCmdSetScissor(c, 0, sc);
            return c;
        }
    }

    static void submit(VkCommandBuffer c) {
        try (MemoryStack s = stackPush()) {
            vkCmdEndRenderPass(c);
            vk(vkEndCommandBuffer(c), "end");
            vk(vkQueueSubmit(queue, VkSubmitInfo.calloc(1, s).sType$Default().pCommandBuffers(s.pointers(c)), fence), "submit");
            vk(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "wait");
            vkResetFences(device, fence);
            vkFreeCommandBuffers(device, pool, c);
        }
    }

    static void teardown() {
        vkDestroyPipelineLayout(device, layout, null);
        vkDestroyFramebuffer(device, framebuffer, null);
        vkDestroyRenderPass(device, renderPass, null);
        vkDestroyImageView(device, view, null);
        vkDestroyImage(device, image, null);
        vkFreeMemory(device, imageMem, null);
        vkDestroyFence(device, fence, null);
        vkDestroyCommandPool(device, pool, null);
        vkDestroyDevice(device, null);
        if (messenger != 0) vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
        vkDestroyInstance(instance, null);
    }
}
