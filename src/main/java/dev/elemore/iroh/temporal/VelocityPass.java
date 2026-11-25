package dev.elemore.iroh.temporal;

import dev.elemore.iroh.perf.PipelineStats;

import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.joml.Matrix4dc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Cards P4-1a/P4-1b: the world-end compute pass ({@code assets/iroh/shaders/temporal/velocity.comp}): motion vectors
 * (camera, moving objects, hand), merged depth, reactive + T&C masks, and the upscaler-input debug views. Self-contained
 * raw Vulkan (own layout, pool, sampler, two pipelines, one small host-visible buffer for the per-frame parameters and
 * the object list), so the same class runs in the game and in the lavapipe tests (velocityGoldenTest, fsrVelocityReplay).
 * <p>Cost (paper, 1706x960 = Quality at 1440p, 1.64 Mpx): reads end depth 4 B + scene depth 4 B + colour 4 B + opaque
 * colour 4 B + opaque depth 4 B, writes MV 4 B + merged depth 4 B + 2 masks 2 B = 30 B/px, ~49 MB per frame (about
 * 0.08 ms of bandwidth on a 640 GB/s card); the object loop runs only for non-sky pixels and stops at the first box,
 * at most 64 boxes (6 KB, cached). No vertex work, no fragment work, no change to any raster pipeline.
 * <p>Needs {@code shaderStorageImageExtendedFormats} (rg16f, r8). Parameters: one 512 B uniform block + one 6 KB object
 * array per frame in flight; a slot is rewritten only after its frame's fence (frame-slot rule).
 */
public final class VelocityPass {
    public static final String SHADER = "/assets/iroh/shaders/temporal/velocity.comp";
    public static final int MAX_OBJECTS = 64, OBJECT_FLOATS = 24, OBJECT_BYTES = OBJECT_FLOATS * 4;
    static final int UBO_SLOT = 512, SSBO_SLOT = MAX_OBJECTS * OBJECT_BYTES;
    public static final int F_SEPARATE = 1, F_MASKS = 2, F_HAND = 4;

    /** Everything one dispatch needs. Views: 0 = absent where allowed (see {@link #record}). */
    public static final class Frame {
        public long endDepthView, sceneDepthView, velocityView, colourView, mergedDepthView, reactiveView, tcView,
                opaqueColourView, opaqueDepthView;
        public int width, height, mode;
        public Matrix4dc reproject, handReproject, invCurPR;
        public float jitterNdcX, jitterNdcY, jitterPxX, jitterPxY;
        public float reactiveScale = 4f, reactiveMax = 0.9f, tcTranslucent = 0.8f, reactiveOnTranslucent = 0.5f, tcHand = 0.3f;
        /** packed objects: per object lo.xyz, tc, hi.xyz, 0, toPrevClip (16, column major) */
        public float[] objects = new float[0];
        public int objectCount;
    }

    private final VkDevice device;
    private final int frames;
    private final long setLayout, pipelineLayout, pool, sampler, pipeline, debugPipeline, buffer, memory;
    private final ByteBuffer mapped;
    private final long[] sets;

    public VelocityPass(VkDevice device, int frames) {
        this.device = device;
        this.frames = Math.max(1, frames);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int[] types = {
                    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                    VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER};
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++)
                b.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer pl = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, pl), "descriptor set layout");
            setLayout = pl.get(0);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)), null, pl), "pipeline layout");
            pipelineLayout = pl.get(0);

            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(4, stack);
            ps.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(4 * this.frames);
            ps.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(5 * this.frames);
            ps.get(2).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(this.frames);
            ps.get(3).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(this.frames);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(this.frames).pPoolSizes(ps), null, pl), "descriptor pool");
            pool = pl.get(0);
            LongBuffer layouts = stack.mallocLong(this.frames);
            for (int i = 0; i < this.frames; i++)
                layouts.put(i, setLayout);
            LongBuffer ps2 = stack.mallocLong(this.frames);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(layouts), ps2), "descriptor sets");
            sets = new long[this.frames];
            for (int i = 0; i < this.frames; i++)
                sets[i] = ps2.get(i);

            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0f), null, pl), "sampler");
            sampler = pl.get(0);

            // parameters + object list, host visible (7 KB per frame in flight)
            long size = (long) this.frames * (UBO_SLOT + SSBO_SLOT);
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                    .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, pl), "buffer");
            buffer = pl.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, mr);
            VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), mp);
            int want = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, type = -1;
            for (int i = 0; i < mp.memoryTypeCount() && type < 0; i++)
                if ((mr.memoryTypeBits() & (1 << i)) != 0 && (mp.memoryTypes(i).propertyFlags() & want) == want)
                    type = i;
            if (type < 0)
                throw new IllegalStateException("Iroh velocity pass: no host-visible coherent memory");
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(mr.size()).memoryTypeIndex(type), null, pl), "memory");
            memory = pl.get(0);
            check(vkBindBufferMemory(device, buffer, memory, 0), "bind");
            PointerBuffer pp = stack.mallocPointer(1);
            check(vkMapMemory(device, memory, 0, size, 0, pp), "map");
            mapped = MemoryUtil.memByteBuffer(pp.get(0), (int) size);

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

    /** bytes of device-visible memory this pass holds (for the memory report) */
    public long memoryBytes() {
        return (long) frames * (UBO_SLOT + SSBO_SLOT);
    }

    private long createPipeline(MemoryStack stack, String name, String src) {
        SPIRVUtils.SPIRV spirv = SPIRVUtils.compileShader(name, src, SPIRVUtils.ShaderKind.COMPUTE_SHADER);
        LongBuffer pl = stack.mallocLong(1);
        check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv.bytecode()), null, pl), "shader module " + name);
        long module = pl.get(0);
        try {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack);
            ci.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            long pipeT0 = System.nanoTime(); // P2-11
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, ci, null, pl), "compute pipeline " + name);
            PipelineStats.created(PipelineStats.Kind.COMPUTE, System.nanoTime() - pipeT0);
            return pl.get(0);
        } finally {
            vkDestroyShaderModule(device, module, null);
            spirv.free();
        }
    }

    /**
     * Records one dispatch. Layouts expected: end depth, scene depth, opaque colour/depth in SHADER_READ_ONLY_OPTIMAL;
     * velocity, colour, merged depth, reactive, T&C in GENERAL. Optional: {@code sceneDepthView} (0 = the end depth is
     * the scene depth, no hand), {@code opaqueColourView}/{@code opaqueDepthView} (0 = no masks: reactive and T&C are
     * written 0 apart from objects/hand), {@code handReproject} (null = hand texels get zero motion). Debug variant when
     * {@code mode != 0}.
     */
    public void record(VkCommandBuffer cmd, int frame, Frame f) {
        int slot = Math.floorMod(frame, frames);
        long set = sets[slot];
        boolean separate = f.sceneDepthView != 0, masks = f.opaqueColourView != 0 && f.opaqueDepthView != 0;
        int flags = (separate ? F_SEPARATE : 0) | (masks ? F_MASKS : 0) | (f.handReproject != null ? F_HAND : 0);
        writeParams(mapped, slot * UBO_SLOT, f, flags);
        int n = Math.min(f.objectCount, MAX_OBJECTS);
        int so = frames * UBO_SLOT + slot * SSBO_SLOT;
        for (int i = 0; i < n * OBJECT_FLOATS; i++)
            mapped.putFloat(so + i * 4, f.objects[i]);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(11, stack);
            int R = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, G = VK_IMAGE_LAYOUT_GENERAL;
            img(stack, w, 0, set, 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, f.endDepthView, R);
            img(stack, w, 1, set, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, f.velocityView, G);
            img(stack, w, 2, set, 2, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, f.colourView, G);
            img(stack, w, 3, set, 3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, separate ? f.sceneDepthView : f.endDepthView, R);
            VkDescriptorBufferInfo.Buffer ub = VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer).offset((long) slot * UBO_SLOT).range(UBO_SLOT);
            w.get(4).sType$Default().dstSet(set).dstBinding(4).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(ub);
            VkDescriptorBufferInfo.Buffer sb = VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer).offset(so).range(SSBO_SLOT);
            w.get(5).sType$Default().dstSet(set).dstBinding(5).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(sb);
            img(stack, w, 6, set, 6, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, f.mergedDepthView, G);
            img(stack, w, 7, set, 7, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, f.reactiveView, G);
            img(stack, w, 8, set, 8, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, f.tcView, G);
            // without masks the opaque inputs are never read; bind valid stand-ins (colour in GENERAL, the scene depth)
            img(stack, w, 9, set, 9, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, masks ? f.opaqueColourView : f.colourView, masks ? R : G);
            img(stack, w, 10, set, 10, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, masks ? f.opaqueDepthView : (separate ? f.sceneDepthView : f.endDepthView), R);
            vkUpdateDescriptorSets(device, w, null);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, f.mode != 0 ? debugPipeline : pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
            vkCmdDispatch(cmd, (f.width + 7) / 8, (f.height + 7) / 8, 1);
        }
    }

    private void img(MemoryStack stack, VkWriteDescriptorSet.Buffer w, int i, long set, int binding, int type, long view, int layout) {
        VkDescriptorImageInfo.Buffer d = VkDescriptorImageInfo.calloc(1, stack).imageView(view).imageLayout(layout);
        if (type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            d.sampler(sampler);
        w.get(i).sType$Default().dstSet(set).dstBinding(binding).descriptorType(type).descriptorCount(1).pImageInfo(d);
    }

    /** std140 Params block (velocity.comp): 3 mat4, vec4 jitter, ivec4 info, ivec4 counts, vec4 maskA, vec4 maskB. */
    static void writeParams(ByteBuffer b, int o, Frame f, int flags) {
        mat(b, o, f.reproject);
        mat(b, o + 64, f.handReproject != null ? f.handReproject : new org.joml.Matrix4d());
        mat(b, o + 128, f.invCurPR != null ? f.invCurPR : new org.joml.Matrix4d());
        b.putFloat(o + 192, f.jitterNdcX).putFloat(o + 196, f.jitterNdcY).putFloat(o + 200, f.jitterPxX).putFloat(o + 204, f.jitterPxY);
        b.putInt(o + 208, f.width).putInt(o + 212, f.height).putInt(o + 216, f.mode).putInt(o + 220, flags);
        b.putInt(o + 224, Math.min(f.objectCount, MAX_OBJECTS)).putInt(o + 228, 0).putInt(o + 232, 0).putInt(o + 236, 0);
        b.putFloat(o + 240, f.reactiveScale).putFloat(o + 244, f.reactiveMax).putFloat(o + 248, f.tcTranslucent).putFloat(o + 252, f.reactiveOnTranslucent);
        b.putFloat(o + 256, f.tcHand).putFloat(o + 260, 0).putFloat(o + 264, 0).putFloat(o + 268, 0);
    }

    private static void mat(ByteBuffer b, int o, Matrix4dc m) {
        for (int c = 0; c < 4; c++)
            for (int r = 0; r < 4; r++)
                b.putFloat(o + (c * 4 + r) * 4, (float) m.get(c, r));
    }

    public void destroy() {
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipeline(device, debugPipeline, null);
        vkDestroySampler(device, sampler, null);
        vkDestroyDescriptorPool(device, pool, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, setLayout, null);
        vkUnmapMemory(device, memory);
        vkDestroyBuffer(device, buffer, null);
        vkFreeMemory(device, memory, null);
    }

    private static void check(int r, String what) {
        if (r != VK_SUCCESS)
            throw new IllegalStateException("Iroh velocity pass: " + what + " failed: " + r);
    }
}
