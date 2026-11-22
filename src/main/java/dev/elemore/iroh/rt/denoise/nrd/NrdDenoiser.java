package dev.elemore.iroh.rt.denoise.nrd;

import dev.elemore.iroh.rt.denoise.*;
import dev.elemore.iroh.upscale.ImageRef;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

/**
 * NRD (RELAX / REBLUR / SIGMA) behind {@link Denoiser}: the library only describes its pipelines, textures and dispatches;
 * this class creates the Vulkan objects from the embedded SPIR-V and records the dispatches. Layout facts (VERIFIED against
 * the SPIR-V of v4.18.0): set = resourcesSpaceIndex (0): textures at binding textureOffset(20)+i as SAMPLED_IMAGE, storage
 * textures at storageOffset(3)+i as STORAGE_IMAGE; set = constantBufferAndSamplersSpaceIndex (1): samplers at binding 0/1
 * (NEAREST_CLAMP, LINEAR_CLAMP), the constant buffer at binding 2 (dynamic uniform buffer here).
 * Barriers: one compute-to-compute memory barrier per dispatch (correct, not optimal: per-resource tracking is a later optimisation).
 * All user images must be in layout GENERAL, usage STORAGE|SAMPLED. THREAD RULE: render thread only.
 */
public final class NrdDenoiser implements Denoiser {
    private final DenoiseGpu gpu;
    private final VkDevice dev;
    private final DenoiserType[] types;
    private long instance;
    private final NrdInstanceDesc desc;
    private final int[] libDesc;
    private final DenoiseGpu.Img[] permanent, transient_;
    private final long[] permViews, transViews;
    private final long set0Layout, set1Layout, pipelineLayout, descPool, set1Pool, set1;
    private int lastFrameIndex = -1;
    private final int setMaxCount;
    private final long[] samplers;
    private final long[] pipelines;
    private final DenoiseGpu.Buf ubo;
    private final int uboStride;
    private final int uboSlots;
    private final Map<Long, Long> viewCache = new HashMap<>();
    private final ByteBuffer common = ByteBuffer.allocateDirect(364).order(ByteOrder.LITTLE_ENDIAN);
    private boolean firstFrame = true, poolsInit;
    private final int width, height;
    private int uboNext;
    /** compute dispatches NRD requested in the last denoise call (cost proxy) */
    public int lastDispatches;

    public NrdDenoiser(DenoiseGpu gpu, DenoiserType[] types, int width, int height) {
        if (!NrdLoader.load()) throw new NrdException("NRD unavailable: " + NrdLoader.unavailableReason());
        this.gpu = gpu;
        this.dev = gpu.device();
        this.types = types;
        this.width = width;
        this.height = height;
        int[] ids = new int[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i].nrdValue < 0) throw new NrdException("not an NRD denoiser type: " + types[i]);
            ids[i] = types[i].nrdValue;
        }
        this.libDesc = NrdNative.libraryDesc();
        this.instance = NrdNative.create(ids);
        this.desc = NrdInstanceDesc.parse(NrdNative.instanceDesc(instance));
        try (MemoryStack s = stackPush()) {
            // pool textures
            permanent = new DenoiseGpu.Img[desc.permanent.length];
            permViews = new long[permanent.length];
            for (int i = 0; i < permanent.length; i++) {
                NrdInstanceDesc.Tex t = desc.permanent[i];
                permanent[i] = gpu.createImage(NrdFormats.vkFormat(t.nrdFormat()), (width + t.downsample() - 1) / t.downsample(), (height + t.downsample() - 1) / t.downsample(),
                        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "NRD permanent " + i);
                permViews[i] = view(permanent[i].image(), NrdFormats.vkFormat(t.nrdFormat()));
            }
            transient_ = new DenoiseGpu.Img[desc.transient_.length];
            transViews = new long[transient_.length];
            for (int i = 0; i < transient_.length; i++) {
                NrdInstanceDesc.Tex t = desc.transient_[i];
                transient_[i] = gpu.createImage(NrdFormats.vkFormat(t.nrdFormat()), (width + t.downsample() - 1) / t.downsample(), (height + t.downsample() - 1) / t.downsample(),
                        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "NRD transient " + i);
                transViews[i] = view(transient_[i].image(), NrdFormats.vkFormat(t.nrdFormat()));
            }
            LongBuffer lb = s.mallocLong(1);
            // samplers: 0 NEAREST_CLAMP, 1 LINEAR_CLAMP
            samplers = new long[desc.samplers.length];
            for (int i = 0; i < samplers.length; i++) {
                int filter = desc.samplers[i] == 0 ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(filter).minFilter(filter)
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0f).borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK);
                chk(vkCreateSampler(dev, sci, null, lb), "vkCreateSampler");
                samplers[i] = lb.get(0);
            }
            // set 0: resources
            int texB = libDesc[1], storB = libDesc[3];
            VkDescriptorSetLayoutBinding.Buffer b0 = VkDescriptorSetLayoutBinding.calloc(desc.perSetTex + desc.perSetStor, s);
            for (int i = 0; i < desc.perSetTex; i++) b0.get(i).binding(texB + desc.resBase + i).descriptorType(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            for (int i = 0; i < desc.perSetStor; i++) b0.get(desc.perSetTex + i).binding(storB + desc.resBase + i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            chk(vkCreateDescriptorSetLayout(dev, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b0), null, lb), "set0 layout");
            set0Layout = lb.get(0);
            // set 1: samplers + dynamic constant buffer
            VkDescriptorSetLayoutBinding.Buffer b1 = VkDescriptorSetLayoutBinding.calloc(desc.samplers.length + 1, s);
            for (int i = 0; i < samplers.length; i++)
                b1.get(i).binding(libDesc[0] + desc.samplerBase + i).descriptorType(VK_DESCRIPTOR_TYPE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).pImmutableSamplers(s.longs(samplers[i]));
            b1.get(samplers.length).binding(libDesc[2] + desc.cbReg).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            chk(vkCreateDescriptorSetLayout(dev, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b1), null, lb), "set1 layout");
            set1Layout = lb.get(0);
            int spaces = Math.max(desc.resSpace, desc.cbSpace) + 1;
            LongBuffer layouts = s.mallocLong(spaces);
            for (int i = 0; i < spaces; i++) layouts.put(i, i == desc.resSpace ? set0Layout : set1Layout);
            chk(vkCreatePipelineLayout(dev, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(layouts), null, lb), "pipeline layout");
            pipelineLayout = lb.get(0);
            // pipelines from the library's own SPIR-V
            pipelines = new long[desc.pipelines.length];
            for (int i = 0; i < pipelines.length; i++) {
                ByteBuffer spv = NrdNative.spirv(instance, i);
                chk(vkCreateShaderModule(dev, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(spv), null, lb), "vkCreateShaderModule " + desc.pipelines[i].identifier());
                long module = lb.get(0);
                VkComputePipelineCreateInfo.Buffer pci = VkComputePipelineCreateInfo.calloc(1, s).sType$Default().layout(pipelineLayout);
                pci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(s.UTF8("main"));
                int r = vkCreateComputePipelines(dev, VK_NULL_HANDLE, pci, null, lb);
                vkDestroyShaderModule(dev, module, null);
                if (r != VK_SUCCESS) throw new NrdException("vkCreateComputePipelines " + desc.pipelines[i].identifier() + ": VkResult " + r);
                pipelines[i] = lb.get(0);
            }
            // descriptor pool: sets for set0 (one per dispatch; a frame can issue more than setsMax over several instances, so size generously) + set1
            setMaxCount = Math.max(256, desc.setsMax * 8);
            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(2, s);
            ps.get(0).type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(Math.max(1, desc.perSetTex) * setMaxCount);
            ps.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(Math.max(1, desc.perSetStor) * setMaxCount);
            chk(vkCreateDescriptorPool(dev, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(setMaxCount).pPoolSizes(ps), null, lb), "descriptor pool");
            descPool = lb.get(0);
            VkDescriptorPoolSize.Buffer ps1 = VkDescriptorPoolSize.calloc(2, s);
            ps1.get(0).type(VK_DESCRIPTOR_TYPE_SAMPLER).descriptorCount(samplers.length);
            ps1.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1);
            chk(vkCreateDescriptorPool(dev, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(ps1), null, lb), "set1 pool");
            set1Pool = lb.get(0);
            // uniform ring (one frame): up to 128 dispatches
            VkPhysicalDeviceProperties pr = VkPhysicalDeviceProperties.calloc(s);
            vkGetPhysicalDeviceProperties(gpu.physicalDevice(), pr);
            int align = (int) pr.limits().minUniformBufferOffsetAlignment();
            uboStride = (desc.cbMaxSize + align - 1) / align * align;
            uboSlots = 256;
            ubo = gpu.createUniformBuffer((long) uboStride * uboSlots);
            chk(vkAllocateDescriptorSets(dev, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(set1Pool).pSetLayouts(s.longs(set1Layout)), lb), "alloc set1");
            set1 = lb.get(0);
            VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, s).buffer(ubo.buffer()).offset(0).range(desc.cbMaxSize);
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, s).sType$Default().dstSet(set1).dstBinding(libDesc[2] + desc.cbReg).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).pBufferInfo(bi);
            w.descriptorCount(1);
            vkUpdateDescriptorSets(dev, w, null);
        }
    }

    private long view(long image, int format) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(s).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            ci.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            chk(vkCreateImageView(dev, ci, null, lb), "vkCreateImageView");
            return lb.get(0);
        }
    }

    private long userView(ImageRef r) {
        return viewCache.computeIfAbsent(r.image() ^ ((long) r.format() << 48), k -> view(r.image(), r.format()));
    }

    private static void chk(int r, String what) {
        if (r != VK_SUCCESS) throw new NrdException(what + ": VkResult " + r);
    }

    @Override
    public String name() {
        return NrdNative.version();
    }

    @Override
    public DenoiserType[] types() {
        return types.clone();
    }

    @Override
    public long estimateMemoryBytes(int w, int h) {
        return desc.poolBytes(desc.permanent, w, h) + desc.poolBytes(desc.transient_, w, h);
    }

    @Override
    public long[] memoryUsageBytes() {
        long p = 0, t = 0;
        for (DenoiseGpu.Img i : permanent) p += i.bytes();
        for (DenoiseGpu.Img i : transient_) t += i.bytes();
        return new long[]{p, t};
    }

    public NrdInstanceDesc description() {
        return desc;
    }

    @Override
    public void configure(int identifier, Map<String, Float> settings) {
        DenoiserType t = types[identifier];
        String n = t.name();
        int kind = n.startsWith("REBLUR") ? 0 : n.startsWith("RELAX") ? 1 : 2;
        NrdNative.setDenoiser(instance, identifier, kind, NrdSettings.toKeyValues(kind, settings));
    }

    @Override
    public void denoise(DenoiseFrame f) {
        FrameSettings fs = f.settings;
        common.clear();
        for (float[] m : new float[][]{fs.viewToClip, fs.viewToClipPrev, fs.worldToView, fs.worldToViewPrev}) for (float x : m) common.putFloat(x);
        for (float x : fs.motionVectorScale) common.putFloat(x);
        for (float x : fs.motionVectorBias) common.putFloat(x);
        for (float x : fs.jitter) common.putFloat(x);
        for (float x : fs.jitterPrev) common.putFloat(x);
        for (int[] a : new int[][]{fs.resourceSize, fs.resourceSizePrev, fs.rectSize, fs.rectSizePrev}) for (int x : a) common.putInt(x);
        common.putFloat(fs.viewZScale).putFloat(fs.timeDeltaMs).putFloat(fs.denoisingRange).putFloat(fs.disocclusionThreshold).putFloat(fs.disocclusionThresholdAlt).putFloat(fs.splitScreen);
        common.putInt(fs.frameIndex).putInt(firstFrame ? 2 : fs.reset ? 1 : 0);
        common.putInt((fs.motionVectorsWorldSpace ? 1 : 0) | (fs.historyConfidence ? 2 : 0) | (fs.disocclusionMix ? 4 : 0) | (fs.validation ? 8 : 0));
        if (fs.frameIndex != lastFrameIndex) { beginFrame(); lastFrameIndex = fs.frameIndex; }
        NrdNative.setCommon(instance, common);
        ByteBuffer blob = NrdNative.dispatches(instance, f.identifiers).order(ByteOrder.LITTLE_ENDIAN);
        VkCommandBuffer cmd = new VkCommandBuffer(f.commandBuffer, dev);
        try (MemoryStack s = stackPush()) {
            if (!poolsInit) {
                // pool images start UNDEFINED: move them to GENERAL once
                List<Long> all = new ArrayList<>();
                for (DenoiseGpu.Img i : permanent) all.add(i.image());
                for (DenoiseGpu.Img i : transient_) all.add(i.image());
                VkImageMemoryBarrier.Buffer bar = VkImageMemoryBarrier.calloc(all.size(), s);
                for (int i = 0; i < all.size(); i++) {
                    bar.get(i).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(all.get(i)).srcAccessMask(0).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                            .subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                }
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, bar);
                poolsInit = true;
            }
            int n = blob.getInt();
            lastDispatches = n;
            int lastPipeline = -1;
            VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            for (int d = 0; d < n; d++) {
                int pipe = blob.getInt(), gw = blob.getInt(), gh = blob.getInt();
                blob.getInt();                     // identifier
                int resN = blob.getInt(), cbSize = blob.getInt();
                blob.getInt();                     // matches previous
                int nameLen = blob.getInt();
                blob.position(blob.position() + ((nameLen + 3) & ~3));
                int[] rt = new int[resN * 3];
                for (int i = 0; i < rt.length; i++) rt[i] = blob.getInt();
                int cbOff = blob.position();
                blob.position(cbOff + ((cbSize + 3) & ~3));
                // constants into the ring
                if (uboNext >= uboSlots) throw new NrdException("constant buffer ring exhausted (" + uboSlots + " dispatches in one frame)");
                int slot = uboNext++;
                MemoryUtil.memCopy(MemoryUtil.memAddress(blob, cbOff), ubo.ptr() + (long) slot * uboStride, cbSize);
                // descriptor set for this dispatch
                LongBuffer lb = s.mallocLong(1);
                chk(vkAllocateDescriptorSets(dev, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(descPool).pSetLayouts(s.longs(set0Layout)), lb), "alloc set0");
                long set0 = lb.get(0);
                NrdInstanceDesc.Pipeline p = desc.pipelines[pipe];
                VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(resN, s);
                int texIdx = 0, storIdx = 0;
                for (int i = 0; i < resN; i++) {
                    int dtype = rt[i * 3], rtype = rt[i * 3 + 1], idx = rt[i * 3 + 2];
                    long vw;
                    if (rtype == Slot.PERMANENT_POOL.ordinal()) vw = permViews[idx];
                    else if (rtype == Slot.TRANSIENT_POOL.ordinal()) vw = transViews[idx];
                    else {
                        ImageRef r = f.images.get(Slot.values()[rtype]);
                        if (r == null || !r.present()) throw new NrdException("missing image for " + Slot.values()[rtype] + " (needed by " + p.identifier() + ")");
                        vw = userView(r);
                    }
                    int binding = dtype == 0 ? libDesc[1] + desc.resBase + texIdx++ : libDesc[3] + desc.resBase + storIdx++;
                    VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, s).imageView(vw).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                    w.get(i).sType$Default().dstSet(set0).dstBinding(binding).descriptorCount(1)
                            .descriptorType(dtype == 0 ? VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(ii);
                }
                if (resN > 0) vkUpdateDescriptorSets(dev, w, null);
                if (pipe != lastPipeline) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelines[pipe]);
                    lastPipeline = pipe;
                }
                // the two sets are bound by their space index (0 resources, 1 constants + samplers in v4.18.0)
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, desc.resSpace, s.longs(set0), null);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, desc.cbSpace, s.longs(set1), s.ints(slot * uboStride));
                vkCmdDispatch(cmd, gw, gh, 1);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, mb, null, null);
            }
        }
        firstFrame = false;
    }

    /** Resets the per-frame descriptor sets and the constant ring; called automatically when {@code settings.frameIndex} changes. The previous frame's GPU work must be complete (frames in flight = 1). */
    public void beginFrame() {
        vkResetDescriptorPool(dev, descPool, 0);
        uboNext = 0;
    }

    @Override
    public void close() {
        if (instance == 0) return;
        vkDeviceWaitIdle(dev);
        for (long p : pipelines) vkDestroyPipeline(dev, p, null);
        vkDestroyPipelineLayout(dev, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(dev, set0Layout, null);
        vkDestroyDescriptorSetLayout(dev, set1Layout, null);
        vkDestroyDescriptorPool(dev, descPool, null);
        vkDestroyDescriptorPool(dev, set1Pool, null);
        for (long sm : samplers) vkDestroySampler(dev, sm, null);
        for (long v : permViews) vkDestroyImageView(dev, v, null);
        for (long v : transViews) vkDestroyImageView(dev, v, null);
        for (long v : viewCache.values()) vkDestroyImageView(dev, v, null);
        for (DenoiseGpu.Img i : permanent) gpu.destroyImage(i);
        for (DenoiseGpu.Img i : transient_) gpu.destroyImage(i);
        gpu.destroyBuffer(ubo);
        NrdNative.destroy(instance);
        instance = 0;
    }
}
