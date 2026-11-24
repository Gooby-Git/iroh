package dev.elemore.iroh.vktest;

import dev.elemore.iroh.glshim.ImageSync;
import net.vulkanmod.vulkan.texture.ImageBarrierBatch;
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
 * P2-12 (RQ-P213-a): image upload synchronisation on lavapipe with SYNCHRONIZATION validation and the shader-access heuristic ON
 * (build.gradle imageSyncRepro sets syncHeuristic; the other harnesses run with it off, and then validation does not look at shader
 * reads at all). One 16x16 texture receives two overlapping vkCmdCopyBufferToImage (an animated sprite ticked twice, a DynamicTexture
 * uploaded twice between frames), is moved to SHADER_READ_ONLY through the production {@link ImageBarrierBatch} and is then SAMPLED IN
 * A VERTEX SHADER (the lightmap, Sampler2 of terrain/entity/particle vertex shaders). Four recordings:
 * <ul><li>LEGACY: destination stage FRAGMENT only, no TRANSFER -> TRANSFER barrier (the base): must report hazards (negative control);</li>
 * <li>NO_STAGE_FIX / NO_WAW_FIX: one fix each: each must still report (each fix is needed);</li>
 * <li>FIXED: {@link ImageSync#SAMPLED_STAGES} + {@link ImageSync#transferWriteToTransferWrite}: 0 hazards.</li></ul>
 * Run: ./gradlew imageSyncRepro. Last line "# imagesync PASS|FAIL ...".
 */
public final class ImageSyncRepro {
    enum Mode { LEGACY, NO_STAGE_FIX, NO_WAW_FIX, FIXED }

    static final String VS = "#version 450\nlayout(set=0,binding=0) uniform sampler2D lightmap;\nvoid main() {\n"
        + "  vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);\n"
        + "  vec4 l = texelFetch(lightmap, ivec2(1, 1), 0);\n"
        + "  gl_Position = vec4(p * 2.0 - 1.0, l.r * 0.0, 1.0);\n}\n";
    static final String FS = "#version 450\nlayout(location=0) out vec4 o;\nvoid main() { o = vec4(1.0); }\n";

    public static void main(String[] a) {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp")) throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at lavapipe");
        int[] hazards = new int[Mode.values().length];
        try (MemoryStack s = MemoryStack.stackPush()) {
            Float16Repro.init(s);
            for (Mode m : Mode.values()) {
                int before = Float16Repro.ERRORS.size();
                run(s, m);
                int n = 0;
                for (String e : Float16Repro.ERRORS.subList(before, Float16Repro.ERRORS.size())) {
                    if (e.contains("hazard detected")) n++;
                    System.out.println("    ! " + m + ": " + e.substring(0, Math.min(170, e.length())).replace("\n", " "));
                }
                hazards[m.ordinal()] = n;
                System.out.println("case " + m + ": sync hazards=" + n + " other errors=" + (Float16Repro.ERRORS.size() - before - n));
            }
        }
        boolean ok = hazards[0] > 0 && hazards[1] > 0 && hazards[2] > 0 && hazards[3] == 0
            && (ImageSync.SAMPLED_STAGES & VK_PIPELINE_STAGE_VERTEX_SHADER_BIT) != 0 && (ImageSync.SAMPLED_STAGES & VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT) != 0;
        System.out.println("# imagesync " + (ok ? "PASS" : "FAIL") + " hazards legacy=" + hazards[0] + " noStageFix=" + hazards[1] + " noWawFix=" + hazards[2] + " fixed=" + hazards[3]);
        System.exit(ok ? 0 : 1);
    }

    static ByteBuffer spv(String glsl, int kind) {
        long comp = Shaderc.shaderc_compiler_initialize();
        long res = Shaderc.shaderc_compile_into_spv(comp, glsl, kind, "t.glsl", "main", 0);
        ByteBuffer spv = Shaderc.shaderc_result_get_bytes(res);
        if (spv == null) throw new IllegalStateException(Shaderc.shaderc_result_get_error_message(res));
        ByteBuffer copy = MemoryUtil.memAlloc(spv.remaining()).put(spv).flip();
        return copy;
    }

    static void run(MemoryStack outer, Mode mode) {
        VkPhysicalDevice phys = Float16Repro.phys;
        Deque<Runnable> undo = new ArrayDeque<>();
        ByteBuffer vsCode = spv(VS, Shaderc.shaderc_vertex_shader), fsCode = spv(FS, Shaderc.shaderc_fragment_shader);
        try (MemoryStack s = outer.push()) {
            VkDeviceQueueCreateInfo.Buffer q = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(0).pQueuePriorities(s.floats(1f));
            PointerBuffer pp = s.mallocPointer(1);
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(q);
            TerrainIndirectRepro.ck(vkCreateDevice(phys, dci, null, pp), "vkCreateDevice");
            VkDevice dev = new VkDevice(pp.get(0), phys, dci);
            undo.push(() -> vkDestroyDevice(dev, null));
            vkGetDeviceQueue(dev, 0, 0, pp);
            VkQueue queue = new VkQueue(pp.get(0), dev);
            VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(s);
            vkGetPhysicalDeviceMemoryProperties(phys, mp);
            LongBuffer lb = s.mallocLong(1);

            ByteBuffer texels = MemoryUtil.memCalloc(16 * 16 * 4);
            while (texels.hasRemaining()) texels.put((byte) 0x80);
            texels.flip();
            long stage = TerrainIndirectRepro.buffer(s, dev, mp, texels, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, undo);
            MemoryUtil.memFree(texels);
            long[] tex = TerrainIndirectRepro.image(s, dev, mp, 16, 16, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, undo);
            long[] target = TerrainIndirectRepro.image(s, dev, mp, 8, 8, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, undo);
            TerrainIndirectRepro.ck(vkCreateSampler(dev, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, lb), "sampler");
            long sampler = lb.get(0);
            undo.push(() -> vkDestroySampler(dev, sampler, null));

            VkDescriptorSetLayoutBinding.Buffer bind = VkDescriptorSetLayoutBinding.calloc(1, s);
            bind.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            TerrainIndirectRepro.ck(vkCreateDescriptorSetLayout(dev, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(bind), null, lb), "dsl");
            long dsl = lb.get(0);
            undo.push(() -> vkDestroyDescriptorSetLayout(dev, dsl, null));
            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(1, s).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            TerrainIndirectRepro.ck(vkCreateDescriptorPool(dev, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(ps), null, lb), "dpool");
            long dpool = lb.get(0);
            undo.push(() -> vkDestroyDescriptorPool(dev, dpool, null));
            TerrainIndirectRepro.ck(vkAllocateDescriptorSets(dev, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(dpool).pSetLayouts(s.longs(dsl)), lb), "dset");
            long set = lb.get(0);
            VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(1, s);
            wr.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(tex[1]).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            vkUpdateDescriptorSets(dev, wr, null);

            VkAttachmentDescription.Buffer at = VkAttachmentDescription.calloc(1, s);
            at.get(0).format(VK_FORMAT_R8G8B8A8_UNORM).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer sp = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1)
                .pColorAttachments(VkAttachmentReference.calloc(1, s).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL));
            TerrainIndirectRepro.ck(vkCreateRenderPass(dev, VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(at).pSubpasses(sp), null, lb), "renderpass");
            long rp = lb.get(0);
            undo.push(() -> vkDestroyRenderPass(dev, rp, null));
            TerrainIndirectRepro.ck(vkCreateFramebuffer(dev, VkFramebufferCreateInfo.calloc(s).sType$Default().renderPass(rp).pAttachments(s.longs(target[1])).width(8).height(8).layers(1), null, lb), "fb");
            long fbuf = lb.get(0);
            undo.push(() -> vkDestroyFramebuffer(dev, fbuf, null));
            TerrainIndirectRepro.ck(vkCreatePipelineLayout(dev, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(dsl)), null, lb), "layout");
            long layout = lb.get(0);
            undo.push(() -> vkDestroyPipelineLayout(dev, layout, null));
            long vsm = TerrainIndirectRepro.module(s, dev, vsCode), fsm = TerrainIndirectRepro.module(s, dev, fsCode);
            undo.push(() -> { vkDestroyShaderModule(dev, vsm, null); vkDestroyShaderModule(dev, fsm, null); });
            VkPipelineShaderStageCreateInfo.Buffer st = VkPipelineShaderStageCreateInfo.calloc(2, s);
            st.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vsm).pName(s.UTF8("main"));
            st.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fsm).pName(s.UTF8("main"));
            VkViewport.Buffer vp = VkViewport.calloc(1, s).width(8).height(8).maxDepth(1f);
            VkRect2D.Buffer sc = VkRect2D.calloc(1, s);
            sc.get(0).extent().set(8, 8);
            VkGraphicsPipelineCreateInfo.Buffer gp = VkGraphicsPipelineCreateInfo.calloc(1, s).sType$Default().pStages(st)
                .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(s).sType$Default())
                .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                .pViewportState(VkPipelineViewportStateCreateInfo.calloc(s).sType$Default().viewportCount(1).pViewports(vp).scissorCount(1).pScissors(sc))
                .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(s).sType$Default().polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f))
                .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(s).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(s).sType$Default().pAttachments(VkPipelineColorBlendAttachmentState.calloc(1, s).colorWriteMask(0xF)))
                .layout(layout).renderPass(rp).subpass(0);
            TerrainIndirectRepro.ck(vkCreateGraphicsPipelines(dev, VK_NULL_HANDLE, gp, null, lb), "pipeline");
            long pipe = lb.get(0);
            undo.push(() -> vkDestroyPipeline(dev, pipe, null));

            TerrainIndirectRepro.ck(vkCreateCommandPool(dev, VkCommandPoolCreateInfo.calloc(s).sType$Default().queueFamilyIndex(0), null, lb), "pool");
            long pool = lb.get(0);
            undo.push(() -> vkDestroyCommandPool(dev, pool, null));
            TerrainIndirectRepro.ck(vkAllocateCommandBuffers(dev, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pp), "cb");
            VkCommandBuffer c = new VkCommandBuffer(pp.get(0), dev);
            TerrainIndirectRepro.ck(vkBeginCommandBuffer(c, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin");

            // the upload command buffer as ImageUploadHelper records it: layout change, copy, (barrier,) second copy, batched read-only transition
            TerrainIndirectRepro.barrier(s, c, tex[0], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
            for (int i = 0; i < 2; i++) {
                if (i == 1 && (mode == Mode.FIXED || mode == Mode.NO_STAGE_FIX)) ImageSync.transferWriteToTransferWrite(c);
                int size = i == 0 ? 16 : 8; // the second copy overlaps the first
                VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
                r.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                r.get(0).bufferRowLength(16).bufferImageHeight(16);
                r.get(0).imageExtent().set(size, size, 1);
                vkCmdCopyBufferToImage(c, stage, tex[0], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, r);
            }
            ImageBarrierBatch batch = new ImageBarrierBatch();
            int dst = mode == Mode.FIXED || mode == Mode.NO_WAW_FIX ? ImageSync.SAMPLED_STAGES : VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            batch.add(tex[0], VK_IMAGE_ASPECT_COLOR_BIT, 0, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, dst, VK_ACCESS_SHADER_READ_BIT);
            batch.flush(c, ImageBarrierBatch.VULKAN);

            VkClearValue.Buffer clear = VkClearValue.calloc(1, s);
            VkRenderPassBeginInfo rb = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(rp).framebuffer(fbuf).pClearValues(clear);
            rb.renderArea().extent().set(8, 8);
            vkCmdBeginRenderPass(c, rb, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(c, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
            vkCmdBindDescriptorSets(c, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, s.longs(set), null);
            vkCmdDraw(c, 3, 1, 0, 0);
            vkCmdEndRenderPass(c);
            TerrainIndirectRepro.ck(vkEndCommandBuffer(c), "end");
            TerrainIndirectRepro.ck(vkQueueSubmit(queue, VkSubmitInfo.calloc(1, s).sType$Default().pCommandBuffers(s.pointers(c)), VK_NULL_HANDLE), "submit");
            TerrainIndirectRepro.ck(vkQueueWaitIdle(queue), "wait");
            vkDeviceWaitIdle(dev);
        } finally {
            while (!undo.isEmpty()) undo.pop().run();
            MemoryUtil.memFree(vsCode);
            MemoryUtil.memFree(fsCode);
        }
    }
}
