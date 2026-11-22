package dev.elemore.iroh.temporal;

import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.joml.Matrix4dc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Card P4-1a: the camera-velocity compute pass ({@code assets/iroh/shaders/temporal/velocity.comp}) and the
 * upscaler-input debug views. Self-contained raw Vulkan (own descriptor layout, pool, sampler, two pipelines), so the
 * same class runs inside the game and in the lavapipe golden test ({@code ./gradlew velocityGoldenTest}).
 * <p>Costs (paper, render 1707x960 = Quality at 2560x1440): one D32 read + one RG16F write per pixel = 8 B/pixel,
 * 13 MB per frame of bandwidth, 64-thread groups, no fragment work and no extra vertex outputs anywhere; the debug
 * variant adds an RGBA8 read-modify-write. Barriers are the caller's (it owns the image layouts).
 * <p>Requires {@code shaderStorageImageExtendedFormats} (rg16f is an extended storage format): DeviceCaps gates it.
 * Descriptor sets: one per frame in flight, rewritten before each dispatch (a set is never updated while its frame is
 * in flight because the frame slot's fence precedes reuse of the slot).
 */
public final class VelocityPass {
    public static final int PUSH_BYTES = 96;
    public static final String SHADER = "/assets/iroh/shaders/temporal/velocity.comp";

    private final VkDevice device;
    private final int frames;
    private final long setLayout, pipelineLayout, pool, sampler, pipeline, debugPipeline;
    private final long[] sets;

    public VelocityPass(VkDevice device, int frames) {
        this.device = device;
        this.frames = Math.max(1, frames);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(4, stack);
            b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo li = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b);
            LongBuffer pl = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, li, null, pl), "descriptor set layout");
            setLayout = pl.get(0);

            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo pli = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(pcr);
            check(vkCreatePipelineLayout(device, pli, null, pl), "pipeline layout");
            pipelineLayout = pl.get(0);

            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(2, stack);
            ps.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2 * this.frames);
            ps.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2 * this.frames);
            VkDescriptorPoolCreateInfo pci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(this.frames).pPoolSizes(ps);
            check(vkCreateDescriptorPool(device, pci, null, pl), "descriptor pool");
            pool = pl.get(0);

            LongBuffer layouts = stack.mallocLong(this.frames);
            for (int i = 0; i < this.frames; i++)
                layouts.put(i, setLayout);
            VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(layouts);
            LongBuffer ps2 = stack.mallocLong(this.frames);
            check(vkAllocateDescriptorSets(device, ai, ps2), "descriptor sets");
            sets = new long[this.frames];
            for (int i = 0; i < this.frames; i++)
                sets[i] = ps2.get(i);

            VkSamplerCreateInfo si = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0f);
            check(vkCreateSampler(device, si, null, pl), "sampler");
            sampler = pl.get(0);

            String src = source();
            pipeline = createPipeline(stack, "iroh_velocity.comp", src);
            debugPipeline = createPipeline(stack, "iroh_velocity_debug.comp", src.replaceFirst("#version 450\n", "#version 450\n#define DEBUG_VIEW 1\n"));
        }
    }

    public static String source() {
        try (InputStream in = VelocityPass.class.getResourceAsStream(SHADER)) {
            if (in == null)
                throw new IllegalStateException("missing " + SHADER);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private long createPipeline(MemoryStack stack, String name, String src) {
        SPIRVUtils.SPIRV spirv = SPIRVUtils.compileShader(name, src, SPIRVUtils.ShaderKind.COMPUTE_SHADER);
        LongBuffer pl = stack.mallocLong(1);
        ByteBuffer code = spirv.bytecode();
        VkShaderModuleCreateInfo smi = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
        check(vkCreateShaderModule(device, smi, null, pl), "shader module " + name);
        long module = pl.get(0);
        try {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack);
            ci.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, ci, null, pl), "compute pipeline " + name);
            return pl.get(0);
        } finally {
            vkDestroyShaderModule(device, module, null);
            spirv.free();
        }
    }

    /**
     * Records one dispatch. The depth views must be in SHADER_READ_ONLY_OPTIMAL, the velocity (and colour) views in
     * GENERAL, with the caller's barriers before and after. {@code colourView == 0} or {@code mode == 0}: velocity only.
     * {@code sceneDepthView}: the depth copied at vanilla's pre-hand depth clear, or 0 when there is none (then
     * {@code depthView} is the scene depth and no texel is treated as hand).
     */
    public void record(VkCommandBuffer cmd, int frame, long depthView, long sceneDepthView, long velocityView, long colourView,
                       int width, int height, Matrix4dc reproject, float jitterNdcX, float jitterNdcY,
                       float jitterPxX, float jitterPxY, int mode) {
        boolean debug = colourView != 0 && mode != 0;
        boolean separate = sceneDepthView != 0;
        long set = sets[Math.floorMod(frame, frames)];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int n = debug ? 4 : 3;
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(n, stack);
            VkDescriptorImageInfo.Buffer d0 = VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(depthView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkDescriptorImageInfo.Buffer d1 = VkDescriptorImageInfo.calloc(1, stack).imageView(velocityView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            w.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(d0);
            w.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(d1);
            VkDescriptorImageInfo.Buffer d3 = VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(separate ? sceneDepthView : depthView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            w.get(2).sType$Default().dstSet(set).dstBinding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(d3);
            if (debug) {
                VkDescriptorImageInfo.Buffer d2 = VkDescriptorImageInfo.calloc(1, stack).imageView(colourView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                w.get(3).sType$Default().dstSet(set).dstBinding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(d2);
            }
            vkUpdateDescriptorSets(device, w, null);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, debug ? debugPipeline : pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);

            ByteBuffer pc = stack.calloc(PUSH_BYTES);
            pushConstants(pc, reproject, jitterNdcX, jitterNdcY, jitterPxX, jitterPxY, width, height, debug ? mode : 0, separate ? 1 : 0);
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            vkCmdDispatch(cmd, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    /** push block: mat4 (column major), vec4 jitter, ivec4 info (size, mode, flags). */
    static void pushConstants(ByteBuffer pc, Matrix4dc m, float jnx, float jny, float jpx, float jpy, int w, int h, int mode, int flags) {
        for (int c = 0; c < 4; c++)
            for (int r = 0; r < 4; r++)
                pc.putFloat((c * 4 + r) * 4, (float) m.get(c, r));
        pc.putFloat(64, jnx).putFloat(68, jny).putFloat(72, jpx).putFloat(76, jpy);
        pc.putInt(80, w).putInt(84, h).putInt(88, mode).putInt(92, flags);
    }

    public void destroy() {
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipeline(device, debugPipeline, null);
        vkDestroySampler(device, sampler, null);
        vkDestroyDescriptorPool(device, pool, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, setLayout, null);
    }

    private static void check(int r, String what) {
        if (r != VK_SUCCESS)
            throw new IllegalStateException("Iroh velocity pass: " + what + " failed: " + r);
    }
}
