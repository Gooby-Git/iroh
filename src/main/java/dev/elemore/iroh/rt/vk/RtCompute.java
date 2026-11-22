package dev.elemore.iroh.rt.vk;

import dev.elemore.iroh.gpu.VkGpuDevice;
import dev.elemore.iroh.gpu.VkGpuDevice.VkBuf;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Ray-query compute pipelines for Iroh's RT core (card P5-0): set 0 = binding 0 the TLAS (when the pass traces), then
 * storage buffers 1..n; one push-constant block. Compute + VK_KHR_ray_query was chosen over ray tracing pipelines for
 * RADV + lavapipe portability and for the hybrid design (raster primaries, wavefront passes, no SBT): see
 * docs/P5-DESIGN.md section 3. Descriptor sets come from this class' own pool, reset by {@link #resetSets()} once the
 * recorded work finished.
 */
public final class RtCompute {
    public static final class Pass {
        final long pipeline, layout, setLayout;
        final int buffers, push;
        final boolean traces;
        public final String name;

        Pass(String name, long pipeline, long layout, long setLayout, int buffers, int push, boolean traces) {
            this.name = name;
            this.pipeline = pipeline;
            this.layout = layout;
            this.setLayout = setLayout;
            this.buffers = buffers;
            this.push = push;
            this.traces = traces;
        }
    }

    private final VkGpuDevice dev;
    private final VkDevice vk;
    private final long pool;

    public RtCompute(VkGpuDevice dev) {
        this.dev = dev;
        this.vk = dev.vkDevice();
        try (MemoryStack s = stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, s);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(16384);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1024);
            LongBuffer lb = s.mallocLong(1);
            VkGpuDevice.check(vkCreateDescriptorPool(vk, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1024).pPoolSizes(sizes), null, lb), "rt pool");
            pool = lb.get(0);
        }
    }

    public Pass create(String name, ByteBuffer spirv, boolean traces, int buffers, int pushBytes) {
        try (MemoryStack s = stackPush()) {
            int n = buffers + 1;
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(n, s);
            b.get(0).binding(0).descriptorType(traces ? VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(traces ? 1 : 0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            for (int i = 0; i < buffers; i++)
                b.get(i + 1).binding(i + 1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer lb = s.mallocLong(1);
            VkGpuDevice.check(vkCreateDescriptorSetLayout(vk, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b), null, lb), "rt set layout");
            long sl = lb.get(0);
            VkPipelineLayoutCreateInfo pci = VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(sl));
            if (pushBytes > 0) pci.pPushConstantRanges(VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushBytes));
            VkGpuDevice.check(vkCreatePipelineLayout(vk, pci, null, lb), "rt pipeline layout");
            long pl = lb.get(0);
            VkGpuDevice.check(vkCreateShaderModule(vk, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(spirv), null, lb), "rt module");
            long mod = lb.get(0);
            VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s).sType$Default().layout(pl);
            ci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(mod).pName(s.UTF8("main"));
            VkGpuDevice.check(vkCreateComputePipelines(vk, VK_NULL_HANDLE, ci, null, lb), "rt compute pipeline " + name);
            vkDestroyShaderModule(vk, mod, null);
            return new Pass(name, lb.get(0), pl, sl, buffers, pushBytes, traces);
        }
    }

    /** Records one dispatch. {@code tlas} is ignored by passes that do not trace. */
    public void dispatch(Pass p, long tlas, VkBuf[] buffers, ByteBuffer push, int x, int y, int z) {
        if (buffers.length != p.buffers) throw new IllegalArgumentException(p.name + ": " + p.buffers + " buffers expected, got " + buffers.length);
        try (MemoryStack s = stackPush()) {
            VkCommandBuffer c = dev.recordingCommandBuffer();
            vkCmdBindPipeline(c, VK_PIPELINE_BIND_POINT_COMPUTE, p.pipeline);
            LongBuffer lb = s.mallocLong(1);
            VkGpuDevice.check(vkAllocateDescriptorSets(vk, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(pool)
                    .pSetLayouts(s.longs(p.setLayout)), lb), "rt descriptor set");
            long set = lb.get(0);
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(p.buffers + (p.traces ? 1 : 0), s);
            int k = 0;
            if (p.traces) {
                VkWriteDescriptorSetAccelerationStructureKHR as = VkWriteDescriptorSetAccelerationStructureKHR.calloc(s).sType$Default()
                        .pAccelerationStructures(s.longs(tlas));
                w.get(k++).sType$Default().pNext(as.address()).dstSet(set).dstBinding(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
            }
            for (int i = 0; i < p.buffers; i++)
                w.get(k++).sType$Default().dstSet(set).dstBinding(i + 1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(VkDescriptorBufferInfo.calloc(1, s).buffer(buffers[i].buffer()).offset(0).range(VK_WHOLE_SIZE));
            vkUpdateDescriptorSets(vk, w, null);
            vkCmdBindDescriptorSets(c, VK_PIPELINE_BIND_POINT_COMPUTE, p.layout, 0, s.longs(set), null);
            if (p.push > 0 && push != null) vkCmdPushConstants(c, p.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(c, x, y, z);
        }
    }

    /** Compute writes -> later compute / transfer reads. */
    public void barrier() {
        dev.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT);
    }

    /** After the device finished all recorded work. */
    public void resetSets() {
        vkResetDescriptorPool(vk, pool, 0);
    }

    public void destroy(Pass p) {
        vkDestroyPipeline(vk, p.pipeline, null);
        vkDestroyPipelineLayout(vk, p.layout, null);
        vkDestroyDescriptorSetLayout(vk, p.setLayout, null);
    }

    public void close() {
        vkDestroyDescriptorPool(vk, pool, null);
    }
}
