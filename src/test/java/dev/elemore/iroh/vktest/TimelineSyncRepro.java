package dev.elemore.iroh.vktest;

import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.queue.UploadSubmit;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.lwjgl.vulkan.VK12.*;

/**
 * P2-12 (L9): transfer -> frame ordering with the upload wait narrowed from ALL_COMMANDS to {@link Synchronization#UPLOAD_WAIT_STAGES},
 * binary and timeline semaphores, on lavapipe with SYNCHRONIZATION validation + the shader-access heuristic (build.gradle
 * timelineSyncRepro). The upload submit copies a staging buffer into a buffer; the frame submit READS it three ways: vertex fetch (a
 * draw), a compute shader (storage read) and a transfer copy. Cases:
 * <ul><li>BINARY_ALL (the base), BINARY_NARROW (P2-12 fallback path), TIMELINE_NARROW (P2-12, when timelineSemaphore is offered): 0 hazards;</li>
 * <li>TIMELINE_FRAG_ONLY: the wait only at FRAGMENT: must report (negative control: the mask is what orders the reads).</li></ul>
 * The submit chaining is {@link UploadSubmit}, the production helper. Run: ./gradlew timelineSyncRepro; last line "# timelinesync PASS|FAIL|SKIP".
 */
public final class TimelineSyncRepro {
    enum Case { BINARY_ALL, BINARY_NARROW, TIMELINE_NARROW, TIMELINE_FRAG_ONLY }

    static final String VS = "#version 450\nlayout(location=0) in vec4 p;\nvoid main() { gl_Position = p; gl_PointSize = 1.0; }\n";
    static final String FS = "#version 450\nlayout(location=0) out vec4 o;\nvoid main() { o = vec4(1.0); }\n";
    static final String CS = "#version 450\nlayout(local_size_x=1) in;\nlayout(std430,set=0,binding=0) readonly buffer A { vec4 a[]; };\n"
        + "layout(std430,set=0,binding=1) buffer B { vec4 b[]; };\nvoid main() { b[0] = a[0] + a[1]; }\n";

    public static void main(String[] a) {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp")) throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at lavapipe");
        int[] hazards = new int[Case.values().length];
        boolean timeline;
        try (MemoryStack s = MemoryStack.stackPush()) {
            Float16Repro.init(s);
            VkPhysicalDeviceVulkan12Features a12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
            vkGetPhysicalDeviceFeatures2(Float16Repro.phys, VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(a12.address()));
            timeline = a12.timelineSemaphore();
            for (Case c : Case.values()) {
                if (!timeline && c.name().startsWith("TIMELINE")) { hazards[c.ordinal()] = c == Case.TIMELINE_FRAG_ONLY ? 1 : 0; continue; }
                int before = Float16Repro.ERRORS.size();
                run(s, c, timeline);
                int n = 0;
                for (String e : Float16Repro.ERRORS.subList(before, Float16Repro.ERRORS.size())) {
                    if (e.contains("hazard detected")) n++;
                    System.out.println("    ! " + c + ": " + e.substring(0, Math.min(170, e.length())).replace("\n", " "));
                }
                hazards[c.ordinal()] = n;
                System.out.println("case " + c + ": sync hazards=" + n + " other errors=" + (Float16Repro.ERRORS.size() - before - n));
            }
        }
        boolean ok = hazards[0] == 0 && hazards[1] == 0 && hazards[2] == 0 && hazards[3] > 0
            && (Synchronization.UPLOAD_WAIT_STAGES & VK_PIPELINE_STAGE_ALL_COMMANDS_BIT) == 0;
        System.out.println("# timelinesync " + (timeline ? (ok ? "PASS" : "FAIL") : (ok ? "PASS" : "FAIL")) + " path=" + (timeline ? "timeline" : "binary (timelineSemaphore not offered: fallback)")
            + " hazards binaryAll=" + hazards[0] + " binaryNarrow=" + hazards[1] + " timelineNarrow=" + hazards[2] + " timelineFragOnly(neg)=" + hazards[3]);
        System.exit(ok ? 0 : 1);
    }

    static ByteBuffer spv(String glsl, int kind) {
        long comp = Shaderc.shaderc_compiler_initialize();
        long res = Shaderc.shaderc_compile_into_spv(comp, glsl, kind, "t.glsl", "main", 0);
        ByteBuffer spv = Shaderc.shaderc_result_get_bytes(res);
        if (spv == null) throw new IllegalStateException(Shaderc.shaderc_result_get_error_message(res));
        return MemoryUtil.memAlloc(spv.remaining()).put(spv).flip();
    }

    static void ck(int r, String w) { TerrainIndirectRepro.ck(r, w); }

    static void run(MemoryStack outer, Case kase, boolean timelineOffered) {
        VkPhysicalDevice phys = Float16Repro.phys;
        Deque<Runnable> undo = new ArrayDeque<>();
        ByteBuffer vsCode = spv(VS, Shaderc.shaderc_vertex_shader), fsCode = spv(FS, Shaderc.shaderc_fragment_shader), csCode = spv(CS, Shaderc.shaderc_compute_shader);
        try (MemoryStack s = outer.push()) {
            boolean tl = kase.name().startsWith("TIMELINE");
            VkPhysicalDeviceVulkan12Features e12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default().timelineSemaphore(tl);
            VkDeviceQueueCreateInfo.Buffer q = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(0).pQueuePriorities(s.floats(1f));
            PointerBuffer pp = s.mallocPointer(1);
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(q).pNext(e12.address());
            ck(vkCreateDevice(phys, dci, null, pp), "vkCreateDevice");
            VkDevice dev = new VkDevice(pp.get(0), phys, dci);
            undo.push(() -> vkDestroyDevice(dev, null));
            vkGetDeviceQueue(dev, 0, 0, pp);
            VkQueue queue = new VkQueue(pp.get(0), dev);
            VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(s);
            vkGetPhysicalDeviceMemoryProperties(phys, mp);
            LongBuffer lb = s.mallocLong(1);

            ByteBuffer data = MemoryUtil.memCalloc(64);
            for (int i = 0; i < 16; i++) data.putFloat(i * 4, i == 3 || i == 7 ? 1f : 0f);
            long stage = TerrainIndirectRepro.buffer(s, dev, mp, data, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, undo);
            long vb = TerrainIndirectRepro.buffer(s, dev, mp, data, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, undo); // the "uploaded" buffer (its host content is overwritten by the copy)
            long out = TerrainIndirectRepro.buffer(s, dev, mp, data, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, undo);
            long copyDst = TerrainIndirectRepro.buffer(s, dev, mp, data, VK_BUFFER_USAGE_TRANSFER_DST_BIT, undo);
            MemoryUtil.memFree(data);
            long[] target = TerrainIndirectRepro.image(s, dev, mp, 8, 8, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, undo);

            // compute pipeline: set 0 = two storage buffers
            VkDescriptorSetLayoutBinding.Buffer bind = VkDescriptorSetLayoutBinding.calloc(2, s);
            for (int i = 0; i < 2; i++) bind.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            ck(vkCreateDescriptorSetLayout(dev, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(bind), null, lb), "dsl");
            long dsl = lb.get(0);
            undo.push(() -> vkDestroyDescriptorSetLayout(dev, dsl, null));
            ck(vkCreateDescriptorPool(dev, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1)
                .pPoolSizes(VkDescriptorPoolSize.calloc(1, s).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2)), null, lb), "dpool");
            long dpool = lb.get(0);
            undo.push(() -> vkDestroyDescriptorPool(dev, dpool, null));
            ck(vkAllocateDescriptorSets(dev, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(dpool).pSetLayouts(s.longs(dsl)), lb), "dset");
            long set = lb.get(0);
            VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(2, s);
            long[] bufs = {vb, out};
            for (int i = 0; i < 2; i++)
                wr.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, s).buffer(bufs[i]).range(VK_WHOLE_SIZE));
            vkUpdateDescriptorSets(dev, wr, null);
            ck(vkCreatePipelineLayout(dev, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(dsl)), null, lb), "cplayout");
            long cplayout = lb.get(0);
            undo.push(() -> vkDestroyPipelineLayout(dev, cplayout, null));
            long csm = TerrainIndirectRepro.module(s, dev, csCode);
            undo.push(() -> vkDestroyShaderModule(dev, csm, null));
            ck(vkCreateComputePipelines(dev, VK_NULL_HANDLE, VkComputePipelineCreateInfo.calloc(1, s).sType$Default().layout(cplayout)
                .stage(VkPipelineShaderStageCreateInfo.calloc(s).sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(csm).pName(s.UTF8("main"))), null, lb), "cpipe");
            long cpipe = lb.get(0);
            undo.push(() -> vkDestroyPipeline(dev, cpipe, null));

            // graphics pipeline: one vec4 vertex attribute from the uploaded buffer
            VkAttachmentDescription.Buffer at = VkAttachmentDescription.calloc(1, s);
            at.get(0).format(VK_FORMAT_R8G8B8A8_UNORM).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer sp = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1)
                .pColorAttachments(VkAttachmentReference.calloc(1, s).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL));
            ck(vkCreateRenderPass(dev, VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(at).pSubpasses(sp), null, lb), "renderpass");
            long rp = lb.get(0);
            undo.push(() -> vkDestroyRenderPass(dev, rp, null));
            ck(vkCreateFramebuffer(dev, VkFramebufferCreateInfo.calloc(s).sType$Default().renderPass(rp).pAttachments(s.longs(target[1])).width(8).height(8).layers(1), null, lb), "fb");
            long fbuf = lb.get(0);
            undo.push(() -> vkDestroyFramebuffer(dev, fbuf, null));
            ck(vkCreatePipelineLayout(dev, VkPipelineLayoutCreateInfo.calloc(s).sType$Default(), null, lb), "glayout");
            long glayout = lb.get(0);
            undo.push(() -> vkDestroyPipelineLayout(dev, glayout, null));
            long vsm = TerrainIndirectRepro.module(s, dev, vsCode), fsm = TerrainIndirectRepro.module(s, dev, fsCode);
            undo.push(() -> { vkDestroyShaderModule(dev, vsm, null); vkDestroyShaderModule(dev, fsm, null); });
            VkPipelineShaderStageCreateInfo.Buffer st = VkPipelineShaderStageCreateInfo.calloc(2, s);
            st.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vsm).pName(s.UTF8("main"));
            st.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fsm).pName(s.UTF8("main"));
            VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(s).sType$Default()
                .pVertexBindingDescriptions(VkVertexInputBindingDescription.calloc(1, s).binding(0).stride(16).inputRate(VK_VERTEX_INPUT_RATE_VERTEX))
                .pVertexAttributeDescriptions(VkVertexInputAttributeDescription.calloc(1, s).location(0).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(0));
            VkViewport.Buffer vp = VkViewport.calloc(1, s).width(8).height(8).maxDepth(1f);
            VkRect2D.Buffer sc = VkRect2D.calloc(1, s);
            sc.get(0).extent().set(8, 8);
            VkGraphicsPipelineCreateInfo.Buffer gp = VkGraphicsPipelineCreateInfo.calloc(1, s).sType$Default().pStages(st).pVertexInputState(vi)
                .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_POINT_LIST))
                .pViewportState(VkPipelineViewportStateCreateInfo.calloc(s).sType$Default().viewportCount(1).pViewports(vp).scissorCount(1).pScissors(sc))
                .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(s).sType$Default().polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f))
                .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(s).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(s).sType$Default().pAttachments(VkPipelineColorBlendAttachmentState.calloc(1, s).colorWriteMask(0xF)))
                .layout(glayout).renderPass(rp).subpass(0);
            ck(vkCreateGraphicsPipelines(dev, VK_NULL_HANDLE, gp, null, lb), "gpipe");
            long gpipe = lb.get(0);
            undo.push(() -> vkDestroyPipeline(dev, gpipe, null));

            ck(vkCreateCommandPool(dev, VkCommandPoolCreateInfo.calloc(s).sType$Default().queueFamilyIndex(0), null, lb), "pool");
            long pool = lb.get(0);
            undo.push(() -> vkDestroyCommandPool(dev, pool, null));
            PointerBuffer cbp = s.mallocPointer(2);
            ck(vkAllocateCommandBuffers(dev, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(2), cbp), "cbs");
            VkCommandBuffer upCb = new VkCommandBuffer(cbp.get(0), dev), frCb = new VkCommandBuffer(cbp.get(1), dev);

            // ---- upload command buffer: staging -> vb (transfer queue's work in the game)
            ck(vkBeginCommandBuffer(upCb, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin up");
            vkCmdCopyBuffer(upCb, stage, vb, VkBufferCopy.calloc(1, s).size(64));
            ck(vkEndCommandBuffer(upCb), "end up");
            // ---- frame command buffer: reads vb as vertex data, as a storage buffer and as a copy source
            ck(vkBeginCommandBuffer(frCb, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin fr");
            VkRenderPassBeginInfo rb = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(rp).framebuffer(fbuf).pClearValues(VkClearValue.calloc(1, s));
            rb.renderArea().extent().set(8, 8);
            vkCmdBeginRenderPass(frCb, rb, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(frCb, VK_PIPELINE_BIND_POINT_GRAPHICS, gpipe);
            vkCmdBindVertexBuffers(frCb, 0, s.longs(vb), s.longs(0));
            vkCmdDraw(frCb, 4, 1, 0, 0);
            vkCmdEndRenderPass(frCb);
            vkCmdBindPipeline(frCb, VK_PIPELINE_BIND_POINT_COMPUTE, cpipe);
            vkCmdBindDescriptorSets(frCb, VK_PIPELINE_BIND_POINT_COMPUTE, cplayout, 0, s.longs(set), null);
            vkCmdDispatch(frCb, 1, 1, 1);
            vkCmdCopyBuffer(frCb, vb, copyDst, VkBufferCopy.calloc(1, s).size(64));
            ck(vkEndCommandBuffer(frCb), "end fr");

            // ---- submits
            long bin = 0, tsem = 0;
            if (tl) {
                VkSemaphoreTypeCreateInfo ti = VkSemaphoreTypeCreateInfo.calloc(s).sType$Default().semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0L);
                ck(vkCreateSemaphore(dev, VkSemaphoreCreateInfo.calloc(s).sType$Default().pNext(ti.address()), null, lb), "timeline");
                tsem = lb.get(0);
            } else {
                ck(vkCreateSemaphore(dev, VkSemaphoreCreateInfo.calloc(s).sType$Default(), null, lb), "binary");
                bin = lb.get(0);
            }
            final long fb = bin, ft = tsem;
            undo.push(() -> { if (fb != 0) vkDestroySemaphore(dev, fb, null); if (ft != 0) vkDestroySemaphore(dev, ft, null); });
            VkSubmitInfo up = VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(upCb));
            if (tl) UploadSubmit.signalTimeline(s, up, tsem, 1L); else up.pSignalSemaphores(s.longs(bin));
            ck(vkQueueSubmit(queue, up, VK_NULL_HANDLE), "submit up");
            int waitMask = switch (kase) {
                case BINARY_ALL -> VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
                case TIMELINE_FRAG_ONLY -> VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
                default -> Synchronization.UPLOAD_WAIT_STAGES;
            };
            VkSubmitInfo fr = VkSubmitInfo.calloc(s).sType$Default().pCommandBuffers(s.pointers(frCb))
                .waitSemaphoreCount(1).pWaitSemaphores(s.longs(tl ? tsem : bin)).pWaitDstStageMask(s.ints(waitMask));
            if (tl) UploadSubmit.chainWaitValues(s, fr, s.longs(1L));
            ck(vkQueueSubmit(queue, fr, VK_NULL_HANDLE), "submit frame");
            ck(vkQueueWaitIdle(queue), "wait");
            vkDeviceWaitIdle(dev);
        } finally {
            while (!undo.isEmpty()) undo.pop().run();
            MemoryUtil.memFree(vsCode);
            MemoryUtil.memFree(fsCode);
            MemoryUtil.memFree(csCode);
        }
    }
}
