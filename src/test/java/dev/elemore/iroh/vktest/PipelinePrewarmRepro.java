package dev.elemore.iroh.vktest;

import dev.elemore.iroh.gpu.Glsl;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.shader.PipelinePrewarm;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.elemore.iroh.vktest.FreshTextureLayoutRepro.ck;
import static org.lwjgl.vulkan.VK12.*;

/**
 * P2-11 (L8): the pre-warm worker's render pass {@link PipelinePrewarm#compatiblePass} on lavapipe with VK_LAYER_KHRONOS_validation.
 * A pipeline created against it (as the worker does, off the render thread) must be usable in a pass built the way
 * {@code RenderPass.createRenderPass} builds the renderer's (other load / store ops, final layouts; the same subpass dependency: it is part of compatibility):
 * 0 validation errors (VUID-vkCmdDraw-renderPass-02684 is the compatibility rule). Negative control: a pipeline created against a pass with another
 * depth format and one with the wrong dependency IS flagged, so the check can fail. Cases: colour+depth (shader-read / present dependency), colour only.
 * Run: ./gradlew pipelinePrewarmRepro. Last line "# pipelineprewarm PASS|FAIL".
 */
public final class PipelinePrewarmRepro {
    static final String VS = "#version 450\nvoid main(){ vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2); gl_Position = vec4(p * 2.0 - 1.0, 0.5, 1.0); }\n";
    static final String FS = "#version 450\nlayout(location=0) out vec4 o;\nvoid main(){ o = vec4(1.0); }\n";
    static final int W = 64, H = 64, COLOR_FMT = VK_FORMAT_R8G8B8A8_UNORM, DEPTH_FMT = VK_FORMAT_D32_SFLOAT;

    public static void main(String[] args) throws Exception {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp")) throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at lavapipe");
        int[] errors = new int[5];
        try (MemoryStack s = MemoryStack.stackPush()) {
            Float16Repro.init(s);
            VkPhysicalDevice phys = Float16Repro.phys;
            VkDeviceQueueCreateInfo.Buffer q = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(0).pQueuePriorities(s.floats(1f));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(q);
            PointerBuffer pp = s.mallocPointer(1);
            ck(vkCreateDevice(phys, dci, null, pp), "vkCreateDevice");
            VkDevice dev = new VkDevice(pp.get(0), phys, dci);
            DeviceManager.vkDevice = dev; // what PipelinePrewarm.compatiblePass reads
            vkGetDeviceQueue(dev, 0, 0, pp);
            VkQueue queue = new VkQueue(pp.get(0), dev);
            LongBuffer lb = s.mallocLong(1);
            ck(vkCreateCommandPool(dev, VkCommandPoolCreateInfo.calloc(s).sType$Default().queueFamilyIndex(0), null, lb), "pool");
            long pool = lb.get(0);
            VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.calloc(s);
            vkGetPhysicalDeviceMemoryProperties(phys, mem);
            long plLayout;
            ck(vkCreatePipelineLayout(dev, VkPipelineLayoutCreateInfo.calloc(s).sType$Default(), null, lb), "layout");
            plLayout = lb.get(0);
            ByteBuffer vsb = Glsl.compile("prewarm.vert", VS, Glsl.VERTEX), fsb = Glsl.compile("prewarm.frag", FS, Glsl.FRAGMENT);
            long vm = module(s, dev, vsb), fm = module(s, dev, fsb);

            // the worker's pass for (colour, depth, dependency kind) vs a renderer-style pass: 0 = shader-read colour (offscreen target), 1 = present, 2 = colour only
            errors[0] = draw(s, dev, queue, pool, mem, plLayout, vm, fm, COLOR_FMT, DEPTH_FMT, 2, PipelinePrewarm.compatiblePass(COLOR_FMT, DEPTH_FMT, 2), true);
            errors[1] = draw(s, dev, queue, pool, mem, plLayout, vm, fm, COLOR_FMT, DEPTH_FMT, 1, PipelinePrewarm.compatiblePass(COLOR_FMT, DEPTH_FMT, 1), true);
            errors[2] = draw(s, dev, queue, pool, mem, plLayout, vm, fm, COLOR_FMT, 0, 0, PipelinePrewarm.compatiblePass(COLOR_FMT, 0, 0), false);
            // negative controls: another depth format; the wrong dependency kind (what the first version of the worker pass had: none)
            errors[3] = draw(s, dev, queue, pool, mem, plLayout, vm, fm, COLOR_FMT, DEPTH_FMT, 2, PipelinePrewarm.compatiblePass(COLOR_FMT, VK_FORMAT_D16_UNORM, 2), true);
            errors[4] = draw(s, dev, queue, pool, mem, plLayout, vm, fm, COLOR_FMT, DEPTH_FMT, 2, PipelinePrewarm.compatiblePass(COLOR_FMT, DEPTH_FMT, 0), true);
            vkDestroyShaderModule(dev, vm, null);
            vkDestroyShaderModule(dev, fm, null);
            vkDestroyPipelineLayout(dev, plLayout, null);
        }
        for (String e : Float16Repro.ERRORS.subList(0, Math.min(Float16Repro.ERRORS.size(), 3))) System.out.println("  validation (controls, and a sync-validation hazard of the present-style pass): " + e.substring(0, Math.min(e.length(), 160)).replace("\n", " "));
        boolean gpuav = "gpuav".equals(System.getProperty("ti.vkval")); // GPU-AV mode (vkval-extra-gate) has core draw-time checks off: the controls cannot fire there
        boolean ok = errors[0] == 0 && errors[1] == 0 && errors[2] == 0 && (gpuav || (errors[3] > 0 && errors[4] > 0));
        System.out.println("  colour+depth shader-read: " + errors[0] + ", colour+depth present: " + errors[1] + ", colour only: " + errors[2] + " (want 0 each); controls: other depth format " + errors[3]
                + ", wrong dependency kind " + errors[4] + " (want > 0 each in core mode" + (gpuav ? "; gpuav: not required" : "") + ")");
        System.out.println((ok ? "# pipelineprewarm PASS" : "# pipelineprewarm FAIL") + " compatible=" + errors[0] + "/" + errors[1] + "/" + errors[2] + " controls=" + errors[3] + "/" + errors[4]);
        System.exit(ok ? 0 : 1);
    }

    /** one pass: pipeline against {@code workerPass}, drawn inside a renderer-style pass; returns the validation errors it caused */
    static int draw(MemoryStack outer, VkDevice dev, VkQueue queue, long pool, VkPhysicalDeviceMemoryProperties mem, long layout, long vm, long fm,
                    int colorFmt, int depthFmt, int depKind, long workerPass, boolean depth) {
        int before = Float16Repro.ERRORS.size();
        try (MemoryStack s = outer.push()) {
            LongBuffer lb = s.mallocLong(1);
            int n = depth ? 2 : 1;
            long[] c = FreshTextureLayoutRepro.image(s, dev, mem, colorFmt, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_ASPECT_COLOR_BIT, W, H, 1, 0, true);
            long[] d = depth ? FreshTextureLayoutRepro.image(s, dev, mem, depthFmt, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, VK_IMAGE_ASPECT_DEPTH_BIT, W, H, 1, 0, true) : null;
            long real = rendererStylePass(s, dev, colorFmt, depthFmt, depth, depKind);
            ck(vkCreateFramebuffer(dev, VkFramebufferCreateInfo.calloc(s).sType$Default().renderPass(real).pAttachments(depth ? s.longs(c[1], d[1]) : s.longs(c[1]))
                    .width(W).height(H).layers(1), null, lb), "fb");
            long fb = lb.get(0);
            long pipe = pipeline(s, dev, layout, vm, fm, workerPass, depth);
            PointerBuffer pc = s.mallocPointer(1);
            ck(vkAllocateCommandBuffers(dev, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pc), "cb");
            VkCommandBuffer cb = new VkCommandBuffer(pc.get(0), dev);
            FreshTextureLayoutRepro.begin(s, cb);
            VkClearValue.Buffer cv = VkClearValue.calloc(n, s);
            if (depth) cv.get(1).depthStencil().depth(1f);
            VkRenderPassBeginInfo rb = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(real).framebuffer(fb).pClearValues(cv);
            rb.renderArea().extent().set(W, H);
            vkCmdBeginRenderPass(cb, rb, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
            vkCmdSetViewport(cb, 0, VkViewport.calloc(1, s).width(W).height(H).maxDepth(1f));
            vkCmdSetScissor(cb, 0, VkRect2D.calloc(1, s).extent(e -> e.set(W, H)));
            vkCmdSetDepthBias(cb, 0f, 0f, 0f);
            vkCmdDraw(cb, 3, 1, 0, 0);
            vkCmdEndRenderPass(cb);
            ck(vkEndCommandBuffer(cb), "end");
            FreshTextureLayoutRepro.submit(s, queue, cb);
            vkFreeCommandBuffers(dev, pool, cb);
            vkDestroyPipeline(dev, pipe, null);
            vkDestroyFramebuffer(dev, fb, null);
            vkDestroyRenderPass(dev, real, null);
            vkDestroyRenderPass(dev, workerPass, null);
        }
        // compatibility errors only: the sync validation also reports a loadOp WAW hazard on the renderer-style PRESENT_SRC dependency (srcAccessMask 0), which
        // is RenderPass.createRenderPass's own table, not the worker's pass (printed below, not counted)
        int n = 0;
        for (String e : Float16Repro.ERRORS.subList(before, Float16Repro.ERRORS.size()))
            if (e.contains("incompatible") || e.contains("renderPass-02684")) n++;
        return n;
    }

    /** as RenderPass.createRenderPass: CLEAR loads, STORE / DONT_CARE stores, a final layout, an external dependency */
    static long rendererStylePass(MemoryStack s, VkDevice dev, int colorFmt, int depthFmt, boolean depth, int depKind) {
        VkAttachmentDescription.Buffer at = VkAttachmentDescription.calloc(depth ? 2 : 1, s);
        at.get(0).format(colorFmt).samples(1).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VkSubpassDescription.Buffer sp = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1)
                .pColorAttachments(VkAttachmentReference.calloc(1, s).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL));
        if (depth) {
            at.get(1).format(depthFmt).samples(1).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            sp.pDepthStencilAttachment(VkAttachmentReference.calloc(s).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL));
        }
        // the dependency table of RenderPass.createRenderPass (copied, not shared: the harness must not load the game's RenderPass)
        VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(at).pSubpasses(sp);
        if (depKind == 1)
            info.pDependencies(VkSubpassDependency.calloc(1, s).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0).srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT).srcAccessMask(0).dstAccessMask(0));
        else if (depKind == 2)
            info.pDependencies(VkSubpassDependency.calloc(1, s).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL).srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT).srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT));
        LongBuffer lb = s.mallocLong(1);
        ck(vkCreateRenderPass(dev, info, null, lb), "real pass");
        return lb.get(0);
    }

    static long pipeline(MemoryStack s, VkDevice dev, long layout, long vm, long fm, long pass, boolean depth) {
        VkPipelineShaderStageCreateInfo.Buffer st = VkPipelineShaderStageCreateInfo.calloc(2, s);
        st.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vm).pName(s.UTF8("main"));
        st.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fm).pName(s.UTF8("main"));
        VkPipelineColorBlendAttachmentState.Buffer cba = VkPipelineColorBlendAttachmentState.calloc(1, s).colorWriteMask(0xF);
        VkGraphicsPipelineCreateInfo.Buffer gp = VkGraphicsPipelineCreateInfo.calloc(1, s).sType$Default().pStages(st)
                .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(s).sType$Default())
                .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                .pViewportState(VkPipelineViewportStateCreateInfo.calloc(s).sType$Default().viewportCount(1).scissorCount(1))
                .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(s).sType$Default().polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).lineWidth(1f).depthBiasEnable(true))
                .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(s).sType$Default().rasterizationSamples(1))
                .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(s).sType$Default().pAttachments(cba))
                .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(s).sType$Default().pDynamicStates(s.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                .layout(layout).renderPass(pass);
        if (depth)
            gp.pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(s).sType$Default().depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS));
        LongBuffer lb = s.mallocLong(1);
        ck(vkCreateGraphicsPipelines(dev, 0L, gp, null, lb), "pipeline");
        return lb.get(0);
    }

    static long module(MemoryStack s, VkDevice dev, ByteBuffer spv) {
        LongBuffer lb = s.mallocLong(1);
        ck(vkCreateShaderModule(dev, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(spv), null, lb), "module");
        return lb.get(0);
    }
}
