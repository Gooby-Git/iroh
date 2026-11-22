package dev.elemore.iroh.rt.denoise;

import dev.elemore.iroh.upscale.ImageRef;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Iroh's own minimal temporal accumulation (no NRD, no licence): the fallback for machines or builds without the NVIDIA
 * component. Diffuse radiance only (IN_DIFF_RADIANCE_HITDIST rgb -> OUT_DIFF_RADIANCE_HITDIST, .a = accumulated frames);
 * reprojects with IN_MV (previous minus current, UV units) and accepts history where IN_VIEWZ matches. Same image contract
 * as NRD (layout GENERAL, STORAGE|SAMPLED) and the same frame loop, so it is a drop-in {@link Denoiser}.
 * Settings: maxFrames (default 32), depthTolerance (relative, default 0.02).
 */
public final class TemporalAccumulationDenoiser implements Denoiser {
    private final DenoiseGpu gpu;
    private final VkDevice dev;
    private final int w, h;
    private final DenoiseGpu.Img[] hist = new DenoiseGpu.Img[2], zs = new DenoiseGpu.Img[2];
    private final long[] histV = new long[2], zV = new long[2];
    private final long sampler, setLayout, pipelineLayout, pipeline, pool;
    private final Map<Long, Long> views = new java.util.HashMap<>();
    private int cur;
    private boolean first = true, init;
    private float maxFrames = 32f, depthTol = 0.02f;
    private int lastFrame = -1;

    public TemporalAccumulationDenoiser(DenoiseGpu gpu, int width, int height) {
        this.gpu = gpu;
        this.dev = gpu.device();
        this.w = width;
        this.h = height;
        try (MemoryStack s = stackPush()) {
            for (int i = 0; i < 2; i++) {
                hist[i] = gpu.createImage(VK_FORMAT_R16G16B16A16_SFLOAT, w, h, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "temporal history " + i);
                zs[i] = gpu.createImage(VK_FORMAT_R32_SFLOAT, w, h, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "temporal viewZ " + i);
                histV[i] = view(hist[i].image(), VK_FORMAT_R16G16B16A16_SFLOAT);
                zV[i] = view(zs[i].image(), VK_FORMAT_R32_SFLOAT);
            }
            LongBuffer lb = s.mallocLong(1);
            vk(vkCreateSampler(dev, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, lb), "sampler");
            sampler = lb.get(0);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(8, s);
            for (int i = 0; i < 5; i++) b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            for (int i = 5; i < 8; i++) b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            vk(vkCreateDescriptorSetLayout(dev, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b), null, lb), "layout");
            setLayout = lb.get(0);
            VkPushConstantRange.Buffer pc = VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(20);
            vk(vkCreatePipelineLayout(dev, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(setLayout)).pPushConstantRanges(pc), null, lb), "pipeline layout");
            pipelineLayout = lb.get(0);
            byte[] spv;
            try (InputStream in = TemporalAccumulationDenoiser.class.getResourceAsStream("/dev/elemore/iroh/rt/denoise/temporal_accum.spv")) {
                spv = in.readAllBytes();
            } catch (Exception e) {
                throw new IllegalStateException("temporal_accum.spv missing (run tools/shaders/build-shaders.sh)", e);
            }
            ByteBuffer code = s.malloc(spv.length);
            code.put(spv).flip();
            vk(vkCreateShaderModule(dev, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(code), null, lb), "shader module");
            long module = lb.get(0);
            VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s).sType$Default().layout(pipelineLayout);
            ci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(s.UTF8("main"));
            int r = vkCreateComputePipelines(dev, VK_NULL_HANDLE, ci, null, lb);
            vkDestroyShaderModule(dev, module, null);
            vk(r, "vkCreateComputePipelines");
            pipeline = lb.get(0);
            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(2, s);
            ps.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(5 * 8);
            ps.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(3 * 8);
            vk(vkCreateDescriptorPool(dev, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(8).pPoolSizes(ps), null, lb), "pool");
            pool = lb.get(0);
        }
    }

    private static void vk(int r, String what) {
        if (r != VK_SUCCESS) throw new IllegalStateException(what + ": VkResult " + r);
    }

    private long view(long image, int format) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(s).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            ci.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vk(vkCreateImageView(dev, ci, null, lb), "view");
            return lb.get(0);
        }
    }

    private long userView(ImageRef r) {
        return views.computeIfAbsent(r.image() ^ ((long) r.format() << 48), k -> view(r.image(), r.format()));
    }

    @Override
    public String name() {
        return "Iroh temporal accumulation (fallback, no NRD)";
    }

    @Override
    public DenoiserType[] types() {
        return new DenoiserType[]{DenoiserType.TEMPORAL_ACCUMULATION};
    }

    @Override
    public long estimateMemoryBytes(int width, int height) {
        return (long) width * height * (8 + 4) * 2;
    }

    @Override
    public long[] memoryUsageBytes() {
        return new long[]{hist[0].bytes() + hist[1].bytes() + zs[0].bytes() + zs[1].bytes(), 0};
    }

    @Override
    public void configure(int identifier, Map<String, Float> settings) {
        for (Map.Entry<String, Float> e : settings.entrySet()) {
            switch (e.getKey()) {
                case "maxFrames" -> maxFrames = e.getValue();
                case "depthTolerance" -> depthTol = e.getValue();
                default -> throw new IllegalArgumentException("unknown temporal setting " + e.getKey());
            }
        }
    }

    @Override
    public void denoise(DenoiseFrame f) {
        ImageRef noisy = f.images.get(Slot.IN_DIFF_RADIANCE_HITDIST), z = f.images.get(Slot.IN_VIEWZ), mv = f.images.get(Slot.IN_MV), out = f.images.get(Slot.OUT_DIFF_RADIANCE_HITDIST);
        if (noisy == null || z == null || mv == null || out == null) throw new IllegalArgumentException("needs IN_DIFF_RADIANCE_HITDIST, IN_VIEWZ, IN_MV, OUT_DIFF_RADIANCE_HITDIST");
        VkCommandBuffer cmd = new VkCommandBuffer(f.commandBuffer, dev);
        try (MemoryStack s = stackPush()) {
            if (f.settings.frameIndex != lastFrame) { vkResetDescriptorPool(dev, pool, 0); lastFrame = f.settings.frameIndex; }
            if (!init) {
                VkImageMemoryBarrier.Buffer bar = VkImageMemoryBarrier.calloc(4, s);
                long[] imgs = {hist[0].image(), hist[1].image(), zs[0].image(), zs[1].image()};
                for (int i = 0; i < 4; i++)
                    bar.get(i).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                            .image(imgs[i]).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, bar);
                init = true;
            }
            int prev = 1 - cur;
            LongBuffer lb = s.mallocLong(1);
            vk(vkAllocateDescriptorSets(dev, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(pool).pSetLayouts(s.longs(setLayout)), lb), "alloc set");
            long set = lb.get(0);
            long[] sampled = {userView(noisy), userView(z), userView(mv), zV[prev], histV[prev]};
            long[] stor = {userView(out), zV[cur], histV[cur]};
            VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(8, s);
            for (int i = 0; i < 5; i++)
                wr.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(sampled[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
            for (int i = 0; i < 3; i++)
                wr.get(5 + i).sType$Default().dstSet(set).dstBinding(5 + i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(stor[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
            vkUpdateDescriptorSets(dev, wr, null);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, s.longs(set), null);
            ByteBuffer pc = s.malloc(20);
            pc.putFloat(0, w).putFloat(4, h).putFloat(8, maxFrames).putFloat(12, (first || f.settings.reset) ? 1f : 0f).putFloat(16, depthTol);
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            vkCmdDispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);
            VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, mb, null, null);
            cur = prev;
            first = false;
        }
    }

    @Override
    public void close() {
        vkDeviceWaitIdle(dev);
        vkDestroyPipeline(dev, pipeline, null);
        vkDestroyPipelineLayout(dev, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(dev, setLayout, null);
        vkDestroyDescriptorPool(dev, pool, null);
        vkDestroySampler(dev, sampler, null);
        for (long v : histV) vkDestroyImageView(dev, v, null);
        for (long v : zV) vkDestroyImageView(dev, v, null);
        for (long v : views.values()) vkDestroyImageView(dev, v, null);
        for (DenoiseGpu.Img i : hist) gpu.destroyImage(i);
        for (DenoiseGpu.Img i : zs) gpu.destroyImage(i);
    }
}
