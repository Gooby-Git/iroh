package dev.elemore.iroh.vktest;

import net.vulkanmod.render.shader.ShaderLoadUtil;
import net.vulkanmod.vulkan.device.IndirectDrawCaps;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;

import static org.lwjgl.vulkan.VK12.*;

/**
 * P2-3 (RQ-P2D-a): a realistic terrain indirect draw on lavapipe with VK_LAYER_KHRONOS_validation. The PRODUCTION terrain
 * program (assets/vulkanmod/shaders/basic/terrain, compiled by SPIRVUtils as the game does) draws 8 sections of the
 * compressed 16-byte terrain vertex through VkDrawIndexedIndirectCommand records laid out as DrawBuffers writes them
 * (32-byte stride, firstInstance = packed section offset x | z << 8 | y << 16, decoded by terrain.vsh from gl_InstanceIndex).
 * Three devices:
 * <ul>
 * <li>old: features as int-2b/3b DeviceManager enabled them before P2-3 (multiDrawIndirect, no drawIndirectFirstInstance), indirect
 *     draw. Under GPU-AV ({@code -Pvalidate=gpuav}) the layer must report VUID-VkDrawIndexedIndirectCommand-firstInstance-00554
 *     (negative control; core validation cannot see buffer contents, so it is only reported, not required, in core mode).</li>
 * <li>new: IndirectDrawCaps.apply (production), tier INDIRECT, indirect draw: 0 errors.</li>
 * <li>fallback: the same device with drawIndirectFirstInstance masked: IndirectDrawCaps picks DIRECT (T-C), per-section
 *     vkCmdDrawIndexed with the same firstInstance: 0 errors and the SAME image as "new".</li>
 * </ul>
 * Every section must land in its own tile (proves the decode). Run: ./gradlew terrainIndirectRepro [-Pvalidate=gpuav].
 * Last line "# terrainindirect PASS|FAIL ...".
 */
public final class TerrainIndirectRepro {
    static final int W = 128, H = 128, SECTIONS = 8, CMD_STRIDE = 32; // CMD_STRIDE as DrawBuffers
    static final String MODE = System.getProperty("ti.vkval", "core");

    /** errors = all validation errors of the case; unexpected = those other than the OLD case's firstInstance-00554 negative control */
    record Result(String tier, int errors, int unexpected, String firstError, String firstUnexpected, String hash, int tilesOk, int litPixels) {}

    public static void main(String[] a) throws Exception {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp")) throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at lavapipe");
        boolean ok;
        Result old, nu, fb;
        try (MemoryStack s = MemoryStack.stackPush()) {
            Float16Repro.init(s);
            // review b97ae0e #3: a device (profile) without the features has nothing indirect to test: the fallback is hwCapsRepro's job
            try (MemoryStack f = s.push()) {
                VkPhysicalDeviceVulkan11Features a11 = VkPhysicalDeviceVulkan11Features.calloc(f).sType$Default();
                VkPhysicalDeviceFeatures2 a2 = VkPhysicalDeviceFeatures2.calloc(f).sType$Default().pNext(a11.address());
                vkGetPhysicalDeviceFeatures2(Float16Repro.phys, a2);
                if (!IndirectDrawCaps.multiDrawSupported(a2.features(), a11) || !a2.features().drawIndirectFirstInstance()) {
                    System.out.println("# terrainindirect SKIP device lacks multiDrawIndirect/shaderDrawParameters/drawIndirectFirstInstance (direct fallback covered by hwCapsRepro)");
                    System.exit(0);
                }
            }
            String base = ShaderLoadUtil.class.getResource("/assets/vulkanmod").toExternalForm() + "/shaders/basic";
            SPIRVUtils.SPIRV vs = SPIRVUtils.compileShader("terrain", ShaderLoadUtil.getShaderSource(base, "terrain", "terrain", SPIRVUtils.ShaderKind.VERTEX_SHADER), SPIRVUtils.ShaderKind.VERTEX_SHADER);
            SPIRVUtils.SPIRV fs = SPIRVUtils.compileShader("terrain", ShaderLoadUtil.getShaderSource(base, "terrain", "terrain", SPIRVUtils.ShaderKind.FRAGMENT_SHADER), SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
            old = run(s, vs.bytecode(), fs.bytecode(), Case.OLD);
            nu = run(s, vs.bytecode(), fs.bytecode(), Case.NEW);
            fb = run(s, vs.bytecode(), fs.bytecode(), Case.FALLBACK);
        }
        for (Object[] r : new Object[][]{{"old", old}, {"new", nu}, {"fallback", fb}}) {
            Result x = (Result) r[1];
            System.out.println("  case " + r[0] + ": tier=" + x.tier + " errors=" + x.errors + " tiles=" + x.tilesOk + "/" + SECTIONS + " lit=" + x.litPixels
                + " image=" + x.hash.substring(0, 16) + (x.firstError != null ? " first=" + x.firstError.substring(0, Math.min(200, x.firstError.length())).replace("\n", " ") : ""));
        }
        boolean gpuav = MODE.equals("gpuav");
        boolean oldFlagged = old.errors > 0 && old.firstError != null && old.firstError.contains("firstInstance-00554");
        boolean newOk = nu.errors == 0 && nu.tier.equals("INDIRECT") && nu.tilesOk == SECTIONS;
        boolean fbOk = fb.errors == 0 && fb.tier.equals("DIRECT") && fb.tilesOk == SECTIONS && fb.hash.equals(nu.hash);
        if (old.unexpected > 0) System.out.println("  ! vk old (other than firstInstance-00554): " + old.firstUnexpected);
        if (nu.errors > 0) System.out.println("  ! vk new: " + nu.firstError);
        if (fb.errors > 0) System.out.println("  ! vk fallback: " + fb.firstError);
        ok = newOk && fbOk && old.unexpected == 0 && old.tilesOk == SECTIONS && (!gpuav || oldFlagged);
        System.out.println("# terrainindirect " + (ok ? "PASS" : "FAIL") + " mode=" + MODE + " old(no firstInstance) errors=" + old.errors
            + (gpuav ? (oldFlagged ? " (VUID firstInstance-00554 flagged)" : " (NOT flagged)") : " (core: GPU-AV needed to see it)")
            + " new(indirect) errors=" + nu.errors + " fallback(direct) errors=" + fb.errors + " images " + (fb.hash.equals(nu.hash) ? "equal" : "DIFFER"));
        System.exit(ok ? 0 : 1);
    }

    enum Case { OLD, NEW, FALLBACK }

    /** One device, one frame, everything destroyed again (object_lifetime clean). */
    static Result run(MemoryStack outer, ByteBuffer vsCode, ByteBuffer fsCode, Case kase) throws Exception {
        VkPhysicalDevice phys = Float16Repro.phys;
        int before = Float16Repro.ERRORS.size();
        Deque<Runnable> undo = new ArrayDeque<>();
        String tier;
        String hash;
        int tilesOk = 0, lit = 0;
        try (MemoryStack s = outer.push()) {
            // ---- device: the feature decision under test
            VkPhysicalDeviceVulkan11Features a11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default();
            VkPhysicalDeviceFeatures2 a2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(a11.address());
            vkGetPhysicalDeviceFeatures2(phys, a2);
            VkPhysicalDeviceVulkan11Features e11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default();
            VkPhysicalDeviceFeatures2 e2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(e11.address());
            boolean indirect;
            if (kase == Case.OLD) { // int-2b/3b DeviceManager before P2-3
                boolean mdi = IndirectDrawCaps.multiDrawSupported(a2.features(), a11);
                e2.features().multiDrawIndirect(mdi);
                e11.shaderDrawParameters(mdi);
                indirect = true; // the indirectDraw option (default true) was the only gate
                tier = "INDIRECT(old)";
            } else {
                if (kase == Case.FALLBACK) a2.features().drawIndirectFirstInstance(false); // a GPU without it
                IndirectDrawCaps.TerrainTier t = IndirectDrawCaps.apply(a2.features(), a11, e2.features(), e11);
                indirect = t == IndirectDrawCaps.TerrainTier.INDIRECT; // = useTerrainIndirect() with indirectDraw on
                tier = t.name();
            }
            VkDeviceQueueCreateInfo.Buffer q = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(0).pQueuePriorities(s.floats(1f));
            PointerBuffer pp = s.mallocPointer(1);
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(q).pNext(e2.address());
            ck(vkCreateDevice(phys, dci, null, pp), "vkCreateDevice");
            VkDevice dev = new VkDevice(pp.get(0), phys, dci);
            undo.push(() -> vkDestroyDevice(dev, null));
            vkGetDeviceQueue(dev, 0, 0, pp);
            VkQueue queue = new VkQueue(pp.get(0), dev);
            VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(s);
            vkGetPhysicalDeviceMemoryProperties(phys, mp);
            LongBuffer lb = s.mallocLong(1);

            // ---- geometry: one quad (face at local z = 8, local x/y 1..15) per section, compressed terrain vertex
            ByteBuffer verts = MemoryUtil.memCalloc(SECTIONS * 4 * 16);
            int[][] corner = {{1, 1}, {15, 1}, {15, 15}, {1, 15}};
            for (int i = 0; i < SECTIONS; i++)
                for (int c = 0; c < 4; c++) {
                    int o = (i * 4 + c) * 16;
                    verts.putShort(o, pos(corner[c][0])).putShort(o + 2, pos(corner[c][1])).putShort(o + 4, pos(8))
                        .putShort(o + 6, (short) 0xF0F0)                     // light: block 15, sky 15 (sample_lightmap2)
                        .putShort(o + 8, (short) 16384).putShort(o + 10, (short) 16384) // uv0 = 0.5 (x 1/32768)
                        .putInt(o + 12, 0xFFFFFFFF);                           // packed RGBA
                }
            ByteBuffer idx = MemoryUtil.memAlloc(12);
            for (int v : new int[]{0, 1, 2, 2, 3, 0}) idx.putShort((short) v);
            idx.flip();
            // indirect commands exactly as DrawBuffers.buildDrawBatchesIndirect writes them (ptr+0..16, CMD_STRIDE 32)
            ByteBuffer cmds = MemoryUtil.memCalloc(SECTIONS * CMD_STRIDE);
            int[] sx = new int[SECTIONS], sy = new int[SECTIONS], sz = new int[SECTIONS], inst = new int[SECTIONS];
            for (int i = 0; i < SECTIONS; i++) {
                sx[i] = 16 * i; sy[i] = 16 * ((i * 3) % 8); sz[i] = 16 * (i % 3); // block offsets inside the area (0..112)
                inst[i] = (sy[i] & 127) << 16 | (sz[i] & 127) << 8 | (sx[i] & 127); // DrawBuffers.encodeSectionOffset (minHeight 0)
                int o = i * CMD_STRIDE;
                cmds.putInt(o, 6).putInt(o + 4, 1).putInt(o + 8, 0).putInt(o + 12, i * 4).putInt(o + 16, inst[i]);
            }
            // UBOs (std140): vertex {mat4 MVP; int FogShape}, fragment {vec4 FogColor; float FogStart, FogEnd, AlphaCutout}
            ByteBuffer ubo0 = MemoryUtil.memCalloc(80);
            float[] mvp = {1f / 64, 0, 0, 0, 0, 1f / 64, 0, 0, 0, 0, 0, 0, -1, -1, 0.5f, 1}; // ortho: x,y in [0,128] -> clip, z -> 0.5
            for (int i = 0; i < 16; i++) ubo0.putFloat(i * 4, mvp[i]);
            ByteBuffer ubo1 = MemoryUtil.memCalloc(32);
            ubo1.putFloat(16, 1e6f).putFloat(20, 2e6f).putFloat(24, 0.1f);
            ByteBuffer white = MemoryUtil.memAlloc(16 * 16 * 4);
            while (white.hasRemaining()) white.put((byte) 0xFF);
            white.flip();

            long vb = buffer(s, dev, mp, verts, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, undo);
            long ib = buffer(s, dev, mp, idx, VK_BUFFER_USAGE_INDEX_BUFFER_BIT, undo);
            long cb = buffer(s, dev, mp, cmds, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, undo);
            long ub0 = buffer(s, dev, mp, ubo0, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, undo);
            long ub1 = buffer(s, dev, mp, ubo1, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, undo);
            long stage = buffer(s, dev, mp, white, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, undo);
            ByteBuffer rbData = MemoryUtil.memCalloc(W * H * 4);
            long[] readback = new long[2];
            readback[0] = buffer(s, dev, mp, rbData, VK_BUFFER_USAGE_TRANSFER_DST_BIT, undo, readback);
            for (ByteBuffer b : new ByteBuffer[]{verts, idx, cmds, ubo0, ubo1, white, rbData}) MemoryUtil.memFree(b);

            long[] atlas = image(s, dev, mp, 16, 16, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, undo);   // Sampler0
            long[] lightmap = image(s, dev, mp, 16, 16, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, undo); // Sampler2
            long[] target = image(s, dev, mp, W, H, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, undo);
            ck(vkCreateSampler(dev, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .maxLod(0f), null, lb), "sampler");
            long sampler = lb.get(0);
            undo.push(() -> vkDestroySampler(dev, sampler, null));

            // ---- descriptors: terrain.json bindings (0 vertex UBO, 1 fragment UBO, 2 Sampler0 fragment, 3 Sampler2 vertex)
            VkDescriptorSetLayoutBinding.Buffer bind = VkDescriptorSetLayoutBinding.calloc(4, s);
            bind.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            bind.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            bind.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            bind.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            ck(vkCreateDescriptorSetLayout(dev, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(bind), null, lb), "dsl");
            long dsl = lb.get(0);
            undo.push(() -> vkDestroyDescriptorSetLayout(dev, dsl, null));
            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(2, s);
            ps.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(2);
            ps.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
            ck(vkCreateDescriptorPool(dev, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(ps), null, lb), "dpool");
            long dpool = lb.get(0);
            undo.push(() -> vkDestroyDescriptorPool(dev, dpool, null));
            ck(vkAllocateDescriptorSets(dev, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(dpool).pSetLayouts(s.longs(dsl)), lb), "dset");
            long set = lb.get(0);
            VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(4, s);
            wr.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
                .pBufferInfo(VkDescriptorBufferInfo.calloc(1, s).buffer(ub0).range(VK_WHOLE_SIZE));
            wr.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
                .pBufferInfo(VkDescriptorBufferInfo.calloc(1, s).buffer(ub1).range(VK_WHOLE_SIZE));
            wr.get(2).sType$Default().dstSet(set).dstBinding(2).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(atlas[1]).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            wr.get(3).sType$Default().dstSet(set).dstBinding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(lightmap[1]).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            vkUpdateDescriptorSets(dev, wr, null);

            // ---- pass + pipeline (terrain vertex input as GraphicsPipeline builds it for COMPRESSED_TERRAIN)
            VkAttachmentDescription.Buffer at = VkAttachmentDescription.calloc(1, s);
            at.get(0).format(VK_FORMAT_R8G8B8A8_UNORM).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            VkSubpassDescription.Buffer sp = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1)
                .pColorAttachments(VkAttachmentReference.calloc(1, s).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL));
            VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(2, s);
            dep.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                .srcAccessMask(0).dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            dep.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT)
                .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            ck(vkCreateRenderPass(dev, VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(at).pSubpasses(sp).pDependencies(dep), null, lb), "renderpass");
            long rp = lb.get(0);
            undo.push(() -> vkDestroyRenderPass(dev, rp, null));
            ck(vkCreateFramebuffer(dev, VkFramebufferCreateInfo.calloc(s).sType$Default().renderPass(rp).pAttachments(s.longs(target[1])).width(W).height(H).layers(1), null, lb), "fb");
            long fbuf = lb.get(0);
            undo.push(() -> vkDestroyFramebuffer(dev, fbuf, null));
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(12);
            ck(vkCreatePipelineLayout(dev, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(dsl)).pPushConstantRanges(pcr), null, lb), "layout");
            long layout = lb.get(0);
            undo.push(() -> vkDestroyPipelineLayout(dev, layout, null));
            long vsm = module(s, dev, vsCode), fsm = module(s, dev, fsCode);
            undo.push(() -> { vkDestroyShaderModule(dev, vsm, null); vkDestroyShaderModule(dev, fsm, null); });
            VkPipelineShaderStageCreateInfo.Buffer st = VkPipelineShaderStageCreateInfo.calloc(2, s);
            st.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vsm).pName(s.UTF8("main"));
            st.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fsm).pName(s.UTF8("main"));
            VkVertexInputAttributeDescription.Buffer attr = VkVertexInputAttributeDescription.calloc(3, s);
            attr.get(0).location(0).binding(0).format(VK_FORMAT_R16G16B16A16_SINT).offset(0);
            attr.get(1).location(1).binding(0).format(VK_FORMAT_R16G16_UINT).offset(8);
            attr.get(2).location(2).binding(0).format(VK_FORMAT_R32_UINT).offset(12);
            VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(s).sType$Default()
                .pVertexBindingDescriptions(VkVertexInputBindingDescription.calloc(1, s).binding(0).stride(16).inputRate(VK_VERTEX_INPUT_RATE_VERTEX))
                .pVertexAttributeDescriptions(attr);
            VkViewport.Buffer vp = VkViewport.calloc(1, s).width(W).height(H).maxDepth(1f);
            VkRect2D.Buffer sc = VkRect2D.calloc(1, s);
            sc.get(0).extent().set(W, H);
            VkGraphicsPipelineCreateInfo.Buffer gp = VkGraphicsPipelineCreateInfo.calloc(1, s).sType$Default().pStages(st).pVertexInputState(vi)
                .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                .pViewportState(VkPipelineViewportStateCreateInfo.calloc(s).sType$Default().viewportCount(1).pViewports(vp).scissorCount(1).pScissors(sc))
                .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(s).sType$Default().polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f))
                .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(s).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(s).sType$Default()
                    .pAttachments(VkPipelineColorBlendAttachmentState.calloc(1, s).colorWriteMask(0xF)))
                .layout(layout).renderPass(rp).subpass(0);
            ck(vkCreateGraphicsPipelines(dev, VK_NULL_HANDLE, gp, null, lb), "pipeline");
            long pipe = lb.get(0);
            undo.push(() -> vkDestroyPipeline(dev, pipe, null));

            // ---- record: texture uploads, the terrain pass, readback
            ck(vkCreateCommandPool(dev, VkCommandPoolCreateInfo.calloc(s).sType$Default().queueFamilyIndex(0), null, lb), "pool");
            long pool = lb.get(0);
            undo.push(() -> vkDestroyCommandPool(dev, pool, null));
            ck(vkAllocateCommandBuffers(dev, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pp), "cb");
            VkCommandBuffer c = new VkCommandBuffer(pp.get(0), dev);
            ck(vkBeginCommandBuffer(c, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin");
            for (long img : new long[]{atlas[0], lightmap[0]}) {
                barrier(s, c, img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
                r.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                r.get(0).imageExtent().set(16, 16, 1);
                vkCmdCopyBufferToImage(c, stage, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, r);
                barrier(s, c, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            }
            VkClearValue.Buffer clear = VkClearValue.calloc(1, s);
            VkRenderPassBeginInfo rb = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(rp).framebuffer(fbuf).pClearValues(clear);
            rb.renderArea().extent().set(W, H);
            vkCmdBeginRenderPass(c, rb, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(c, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
            vkCmdBindDescriptorSets(c, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, s.longs(set), null);
            vkCmdBindVertexBuffers(c, 0, s.longs(vb), s.longs(0));
            vkCmdBindIndexBuffer(c, ib, 0, VK_INDEX_TYPE_UINT16);
            // ModelOffset = area origin + POS_OFFSET - camera (DrawBuffers.updateChunkAreaOrigin; origin 0, camera 0)
            float mo = -net.vulkanmod.render.vertex.VertexBuilder.CompressedVertexBuilder.POS_OFFSET;
            vkCmdPushConstants(c, layout, VK_SHADER_STAGE_VERTEX_BIT, 0, s.floats(mo, mo, mo));
            if (indirect)
                vkCmdDrawIndexedIndirect(c, cb, 0, SECTIONS, CMD_STRIDE);       // DrawBuffers.buildDrawBatchesIndirect
            else
                for (int i = 0; i < SECTIONS; i++)
                    vkCmdDrawIndexed(c, 6, 1, 0, i * 4, inst[i]);              // DrawBuffers.buildDrawBatchesDirect
            vkCmdEndRenderPass(c);
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            r.get(0).imageExtent().set(W, H, 1);
            vkCmdCopyImageToBuffer(c, target[0], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback[0], r);
            VkBufferMemoryBarrier.Buffer hb = VkBufferMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).buffer(readback[0]).size(VK_WHOLE_SIZE);
            vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, hb, null);
            ck(vkEndCommandBuffer(c), "end");
            ck(vkQueueSubmit(queue, VkSubmitInfo.calloc(1, s).sType$Default().pCommandBuffers(s.pointers(c)), VK_NULL_HANDLE), "submit");
            ck(vkQueueWaitIdle(queue), "wait");

            // ---- read back: hash + one lit tile per section (pixel = block, ortho)
            PointerBuffer mapped = s.mallocPointer(1);
            ck(vkMapMemory(dev, readback[1], 0, VK_WHOLE_SIZE, 0, mapped), "map");
            ByteBuffer px = MemoryUtil.memByteBuffer(mapped.get(0), W * H * 4);
            for (int i = 0; i < W * H; i++) if ((px.getInt(i * 4) & 0x00FFFFFF) != 0) lit++;
            for (int i = 0; i < SECTIONS; i++) {
                int x = sx[i] + 8, y = sy[i] + 8;
                if ((px.getInt((y * W + x) * 4) & 0x00FFFFFF) != 0) tilesOk++;
            }
            byte[] copy = new byte[W * H * 4];
            px.get(0, copy);
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(copy));
            vkUnmapMemory(dev, readback[1]);
            vkDeviceWaitIdle(dev);
        } finally {
            while (!undo.isEmpty()) undo.pop().run();
        }
        int n = Float16Repro.ERRORS.size() - before, unexpected = 0;
        String first = null, firstUnexpected = null;
        for (String e : Float16Repro.ERRORS.subList(before, Float16Repro.ERRORS.size())) {
            boolean expected = kase == Case.OLD && e.contains("firstInstance-00554"); // only the negative control's own VUID
            if (expected) { if (first == null) first = e; }
            else { unexpected++; if (firstUnexpected == null) firstUnexpected = e; }
        }
        if (first == null) first = firstUnexpected;
        if (kase == Case.OLD) // the expected ones are reported here, not left for other cases' counts
            Float16Repro.ERRORS.subList(before, Float16Repro.ERRORS.size()).removeIf(e -> e.contains("firstInstance-00554"));
        return new Result(tier, n, unexpected, first, firstUnexpected, hash, tilesOk, lit);
    }

    /** section-local block coordinate -> compressed vertex int16 (VertexBuilder.CompressedVertexBuilder: (v + POS_OFFSET) * 2048) */
    static short pos(float local) {
        return (short) Math.round((local + net.vulkanmod.render.vertex.VertexBuilder.CompressedVertexBuilder.POS_OFFSET) * 2048f);
    }

    static long module(MemoryStack s, VkDevice dev, ByteBuffer code) {
        LongBuffer lb = s.mallocLong(1);
        ck(vkCreateShaderModule(dev, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(code), null, lb), "module");
        return lb.get(0);
    }

    static long buffer(MemoryStack s, VkDevice dev, VkPhysicalDeviceMemoryProperties mp, ByteBuffer data, int usage, Deque<Runnable> undo) {
        return buffer(s, dev, mp, data, usage, undo, null);
    }

    /** host-visible coherent buffer filled with {@code data}; {@code out[1]} receives the memory when given */
    static long buffer(MemoryStack outer, VkDevice dev, VkPhysicalDeviceMemoryProperties mp, ByteBuffer data, int usage, Deque<Runnable> undo, long[] out) {
        try (MemoryStack s = outer.push()) {
            LongBuffer lb = s.mallocLong(1);
            ck(vkCreateBuffer(dev, VkBufferCreateInfo.calloc(s).sType$Default().size(data.remaining()).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, lb), "buffer");
            long buf = lb.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(s);
            vkGetBufferMemoryRequirements(dev, buf, mr);
            long mem = alloc(s, dev, mp, mr, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            ck(vkBindBufferMemory(dev, buf, mem, 0), "bindBuffer");
            PointerBuffer p = s.mallocPointer(1);
            ck(vkMapMemory(dev, mem, 0, VK_WHOLE_SIZE, 0, p), "map");
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), p.get(0), data.remaining());
            vkUnmapMemory(dev, mem);
            undo.push(() -> { vkDestroyBuffer(dev, buf, null); vkFreeMemory(dev, mem, null); });
            if (out != null) out[1] = mem;
            return buf;
        }
    }

    /** RGBA8 optimal image + view: {image, view} */
    static long[] image(MemoryStack outer, VkDevice dev, VkPhysicalDeviceMemoryProperties mp, int w, int h, int usage, Deque<Runnable> undo) {
        try (MemoryStack s = outer.push()) {
            LongBuffer lb = s.mallocLong(1);
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM).mipLevels(1).arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().set(w, h, 1);
            ck(vkCreateImage(dev, ci, null, lb), "image");
            long img = lb.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(s);
            vkGetImageMemoryRequirements(dev, img, mr);
            long mem = alloc(s, dev, mp, mr, 0);
            ck(vkBindImageMemory(dev, img, mem, 0), "bindImage");
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(s).sType$Default().image(img).viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM);
            vi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            ck(vkCreateImageView(dev, vi, null, lb), "view");
            long view = lb.get(0);
            undo.push(() -> { vkDestroyImageView(dev, view, null); vkDestroyImage(dev, img, null); vkFreeMemory(dev, mem, null); });
            return new long[]{img, view};
        }
    }

    static long alloc(MemoryStack s, VkDevice dev, VkPhysicalDeviceMemoryProperties mp, VkMemoryRequirements mr, int props) {
        int type = -1;
        for (int i = 0; i < mp.memoryTypeCount() && type < 0; i++)
            if ((mr.memoryTypeBits() & (1 << i)) != 0 && (mp.memoryTypes(i).propertyFlags() & props) == props) type = i;
        if (type < 0) throw new IllegalStateException("no memory type for props " + props);
        LongBuffer lb = s.mallocLong(1);
        ck(vkAllocateMemory(dev, VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(mr.size()).memoryTypeIndex(type), null, lb), "alloc");
        return lb.get(0);
    }

    static void barrier(MemoryStack s, VkCommandBuffer c, long img, int from, int to, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, s).sType$Default().oldLayout(from).newLayout(to).srcAccessMask(srcAccess).dstAccessMask(dstAccess)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(img);
        b.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(c, srcStage, dstStage, 0, null, null, b);
    }

    static void ck(int r, String what) {
        if (r != VK_SUCCESS) throw new IllegalStateException(what + ": VkResult " + r);
    }
}
