package dev.elemore.iroh.rt.denoise;

import dev.elemore.iroh.upscale.ImageRef;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Iroh's own denoiser (the default everyone gets; NRD is an optional separate component). SVGF-family, written from the
 * published papers only (Schied et al. 2017 SVGF; Dammertz et al. 2010 a-trous; Schied et al. 2018 A-SVGF for the history
 * control ideas): per frame, per channel, one temporal pass (reprojection with motion vectors + depth/normal rejection, history
 * length, temporal moments, spatial variance for short history, firefly clamp) and N edge-stopping a-trous passes (depth /
 * normal / variance-scaled luminance weights) whose first iteration feeds the colour history. Channels: IROH_DIFFUSE
 * (demodulated radiance), IROH_SPECULAR (roughness- and hit-distance-limited history; NO virtual-motion reprojection), IROH_SHADOW
 * (IN_PENUMBRA -> visibility). If {@code IN_ALBEDO} is bound the noisy input is modulated: it is divided by the albedo before
 * denoising and multiplied back at the end (keeps pixel-art texture detail out of the filter).
 * Image contract as for all Denoisers (layout GENERAL, STORAGE|SAMPLED). Inputs: IN_MV (UV, previous minus current), IN_VIEWZ
 * (R32F), IN_NORMAL_ROUGHNESS (A2B10G10R10_UNORM, NRD-compatible oct packing). Outputs: RGBA16F (rgb, a = history length;
 * shadow: r = visibility). No checkerboard / half-rate input support (UNVERIFIED need). THREAD RULE: render thread only.
 * Settings (Map keys): maxHistory 32, minAlpha 0.02, fireflyFactor 0 (off), varFallbackLen 4, zTol 0.02, zSlope 2.5, nTol 0.9,
 * roughLenScale 0.25, lagK 4 (shadow 3), lagBias 0.1 (relative to the history mean for radiance, absolute for shadow), atrousIterations 4 (shadow 3), feedbackIteration 0, phiZ 1, phiN 64, phiL 16 (shadow 0.25), radiusScale 1.
 */
public final class SvgfDenoiser implements Denoiser {
    private final DenoiseGpu gpu;
    private final VkDevice dev;
    private final DenoiserType[] types;
    private final int w, h;
    private final DenoiseGpu.Img[] zs = new DenoiseGpu.Img[2], ns = new DenoiseGpu.Img[2], filt = new DenoiseGpu.Img[2];
    private final DenoiseGpu.Img[][] hist, mom;
    private final long[] zV = new long[2], nV = new long[2], filtV = new long[2];
    private final long[][] histV, momV;
    private final long sampler, tLayout, aLayout, tpl, apl, tPipe, aPipe, pool;
    private final Map<Long, Long> views = new HashMap<>();
    private final Map<String, Float>[] cfg;
    private int cur, lastFrame = -1;
    private boolean init;
    private boolean[] first;
    /** dispatches recorded in the last denoise call (cost proxy) */
    public int lastDispatches;

    @SuppressWarnings("unchecked")
    public SvgfDenoiser(DenoiseGpu gpu, DenoiserType[] types, int width, int height) {
        this.gpu = gpu;
        this.dev = gpu.device();
        this.types = types;
        this.w = width;
        this.h = height;
        cfg = new Map[types.length];
        first = new boolean[types.length];
        hist = new DenoiseGpu.Img[types.length][2];
        mom = new DenoiseGpu.Img[types.length][2];
        histV = new long[types.length][2];
        momV = new long[types.length][2];
        int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        for (int i = 0; i < 2; i++) {
            zs[i] = gpu.createImage(VK_FORMAT_R32_SFLOAT, w, h, usage, "svgf viewZ " + i);
            ns[i] = gpu.createImage(VK_FORMAT_R16G16B16A16_SFLOAT, w, h, usage, "svgf normal " + i);
            filt[i] = gpu.createImage(VK_FORMAT_R16G16B16A16_SFLOAT, w, h, usage, "svgf filter " + i);
            zV[i] = view(zs[i].image(), VK_FORMAT_R32_SFLOAT);
            nV[i] = view(ns[i].image(), VK_FORMAT_R16G16B16A16_SFLOAT);
            filtV[i] = view(filt[i].image(), VK_FORMAT_R16G16B16A16_SFLOAT);
        }
        for (int c = 0; c < types.length; c++) {
            cfg[c] = new HashMap<>(defaults(types[c]));
            first[c] = true;
            for (int i = 0; i < 2; i++) {
                hist[c][i] = gpu.createImage(VK_FORMAT_R16G16B16A16_SFLOAT, w, h, usage, "svgf history " + c + "." + i);
                mom[c][i] = gpu.createImage(VK_FORMAT_R16G16B16A16_SFLOAT, w, h, usage, "svgf moments " + c + "." + i);
                histV[c][i] = view(hist[c][i].image(), VK_FORMAT_R16G16B16A16_SFLOAT);
                momV[c][i] = view(mom[c][i].image(), VK_FORMAT_R16G16B16A16_SFLOAT);
            }
        }
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            vk(vkCreateSampler(dev, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, lb), "sampler");
            sampler = lb.get(0);
            tLayout = layout(s, 9, 4);
            aLayout = layout(s, 5, 3);
            tpl = plLayout(s, tLayout);
            apl = plLayout(s, aLayout);
            tPipe = pipeline(s, "svgf_temporal", tpl);
            aPipe = pipeline(s, "svgf_atrous", apl);
            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(2, s);
            ps.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(9 * 64);
            ps.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4 * 64);
            vk(vkCreateDescriptorPool(dev, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(64).pPoolSizes(ps), null, lb), "pool");
            pool = lb.get(0);
        }
    }

    static Map<String, Float> defaults(DenoiserType t) {
        Map<String, Float> m = new HashMap<>();
        m.put("maxHistory", 32f); m.put("minAlpha", 0.02f); m.put("fireflyFactor", 0f); m.put("varFallbackLen", 4f); m.put("zTol", 0.02f); m.put("zSlope", 2.5f);
        m.put("nTol", 0.9f); m.put("roughLenScale", 0.25f); m.put("feedbackIteration", 0f); m.put("phiZ", 1f); m.put("phiN", 64f);
        m.put("phiL", 16f); m.put("radiusScale", 1f); m.put("lagK", 4f); m.put("lagBias", 0.1f);
        m.put("atrousIterations", 4f);
        if (t == DenoiserType.IROH_SHADOW) { m.put("phiL", 0.25f); m.put("atrousIterations", 3f); m.put("lagK", 3f); }   // binary-like signal: tight luminance stop, lag guard on
        return m;
    }

    private long layout(MemoryStack s, int samplers, int storages) {
        VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(samplers + storages, s);
        for (int i = 0; i < samplers; i++) b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
        for (int i = 0; i < storages; i++) b.get(samplers + i).binding(samplers + i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
        LongBuffer lb = s.mallocLong(1);
        vk(vkCreateDescriptorSetLayout(dev, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b), null, lb), "layout");
        return lb.get(0);
    }

    private long plLayout(MemoryStack s, long set) {
        LongBuffer lb = s.mallocLong(1);
        VkPushConstantRange.Buffer pc = VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(64);
        vk(vkCreatePipelineLayout(dev, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(set)).pPushConstantRanges(pc), null, lb), "pipeline layout");
        return lb.get(0);
    }

    private long pipeline(MemoryStack s, String name, long pl) {
        byte[] spv;
        try (InputStream in = SvgfDenoiser.class.getResourceAsStream("/dev/elemore/iroh/rt/denoise/" + name + ".spv")) {
            spv = in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(name + ".spv missing (run tools/shaders/build-shaders.sh)", e);
        }
        ByteBuffer code = s.malloc(spv.length);
        code.put(spv).flip();
        LongBuffer lb = s.mallocLong(1);
        vk(vkCreateShaderModule(dev, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(code), null, lb), "shader module " + name);
        long m = lb.get(0);
        VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s).sType$Default().layout(pl);
        ci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(m).pName(s.UTF8("main"));
        int r = vkCreateComputePipelines(dev, VK_NULL_HANDLE, ci, null, lb);
        vkDestroyShaderModule(dev, m, null);
        vk(r, "pipeline " + name);
        return lb.get(0);
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
        return "Iroh SVGF-family denoiser (own, no NRD)";
    }

    @Override
    public DenoiserType[] types() {
        return types.clone();
    }

    /** bytes per pixel: shared (viewZ 2x4, normal 2x8, filter ping-pong 2x8) + per channel (history 2x8 + moments 2x8). */
    @Override
    public long estimateMemoryBytes(int width, int height) {
        return (long) width * height * (8 + 16 + 16 + 32L * types.length);
    }

    @Override
    public long[] memoryUsageBytes() {
        long perm = 0, trans = 0;
        for (int i = 0; i < 2; i++) { perm += zs[i].bytes() + ns[i].bytes(); trans += filt[i].bytes(); }
        for (int c = 0; c < types.length; c++) for (int i = 0; i < 2; i++) perm += hist[c][i].bytes() + mom[c][i].bytes();
        return new long[]{perm, trans};
    }

    @Override
    public void configure(int identifier, Map<String, Float> settings) {
        Map<String, Float> m = cfg[identifier];
        for (Map.Entry<String, Float> e : settings.entrySet()) {
            if (!m.containsKey(e.getKey())) throw new IllegalArgumentException("unknown SVGF setting '" + e.getKey() + "'");
            m.put(e.getKey(), e.getValue());
        }
    }

    private static float f(Map<String, Float> m, String k) {
        return m.get(k);
    }

    @Override
    public void denoise(DenoiseFrame fr) {
        VkCommandBuffer cmd = new VkCommandBuffer(fr.commandBuffer, dev);
        lastDispatches = 0;
        ImageRef mv = need(fr, Slot.IN_MV), z = need(fr, Slot.IN_VIEWZ), nrm = need(fr, Slot.IN_NORMAL_ROUGHNESS), alb = fr.images.get(Slot.IN_ALBEDO);
        try (MemoryStack s = stackPush()) {
            if (fr.settings.frameIndex != lastFrame) { vkResetDescriptorPool(dev, pool, 0); lastFrame = fr.settings.frameIndex; }
            if (!init) {
                List<Long> all = new ArrayList<>();
                for (int i = 0; i < 2; i++) { all.add(zs[i].image()); all.add(ns[i].image()); all.add(filt[i].image()); }
                for (int c = 0; c < types.length; c++) for (int i = 0; i < 2; i++) { all.add(hist[c][i].image()); all.add(mom[c][i].image()); }
                VkImageMemoryBarrier.Buffer bar = VkImageMemoryBarrier.calloc(all.size(), s);
                for (int i = 0; i < all.size(); i++)
                    bar.get(i).sType$Default().oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                            .image(all.get(i)).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, bar);
                init = true;
            }
            int prev = 1 - cur;
            VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            long albV = alb != null && alb.present() ? userView(alb) : userView(z);
            for (int id : fr.identifiers) {
                DenoiserType t = types[id];
                Map<String, Float> m = cfg[id];
                Slot in = t == DenoiserType.IROH_DIFFUSE ? Slot.IN_DIFF_RADIANCE_HITDIST : t == DenoiserType.IROH_SPECULAR ? Slot.IN_SPEC_RADIANCE_HITDIST : Slot.IN_PENUMBRA;
                Slot out = t == DenoiserType.IROH_DIFFUSE ? Slot.OUT_DIFF_RADIANCE_HITDIST : t == DenoiserType.IROH_SPECULAR ? Slot.OUT_SPEC_RADIANCE_HITDIST : Slot.OUT_SHADOW_TRANSLUCENCY;
                ImageRef noisy = need(fr, in), o = need(fr, out);
                boolean albedo = alb != null && alb.present() && t != DenoiserType.IROH_SHADOW;
                float mode = t == DenoiserType.IROH_DIFFUSE ? 0 : t == DenoiserType.IROH_SPECULAR ? 1 : 2;
                boolean reset = first[id] || fr.settings.reset;
                // ---- temporal
                LongBuffer lb = s.mallocLong(1);
                vk(vkAllocateDescriptorSets(dev, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(pool).pSetLayouts(s.longs(tLayout)), lb), "alloc temporal set");
                long set = lb.get(0);
                long[] sampled = {userView(noisy), userView(z), userView(nrm), userView(mv), zV[prev], nV[prev], histV[id][prev], momV[id][prev], albV};
                long[] stor = {filtV[0], momV[id][cur], zV[cur], nV[cur]};
                write(s, set, sampled, stor);
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, tPipe);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, tpl, 0, s.longs(set), null);
                ByteBuffer pc = s.malloc(56);
                pc.putFloat(0, w).putFloat(4, h).putFloat(8, mode).putFloat(12, (albedo ? 1 : 0) + (reset ? 2 : 0)).putFloat(16, f(m, "maxHistory")).putFloat(20, f(m, "minAlpha"))
                        .putFloat(24, f(m, "fireflyFactor")).putFloat(28, f(m, "varFallbackLen")).putFloat(32, f(m, "zTol")).putFloat(36, f(m, "zSlope")).putFloat(40, f(m, "nTol")).putFloat(44, f(m, "roughLenScale")).putFloat(48, f(m, "lagK")).putFloat(52, f(m, "lagBias"));
                vkCmdPushConstants(cmd, tpl, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
                vkCmdDispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, mb, null, null);
                lastDispatches++;
                // ---- a-trous iterations
                int iters = Math.max(1, Math.round(f(m, "atrousIterations")));
                int fb = Math.min(iters - 1, Math.round(f(m, "feedbackIteration")));
                for (int i = 0; i < iters; i++) {
                    vk(vkAllocateDescriptorSets(dev, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(pool).pSetLayouts(s.longs(aLayout)), lb), "alloc atrous set");
                    long as = lb.get(0);
                    long[] smp = {filtV[i % 2], userView(z), nV[cur], albV, momV[id][cur]};
                    long[] st = {filtV[(i + 1) % 2], histV[id][cur], userView(o)};
                    write(s, as, smp, st);
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, aPipe);
                    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, apl, 0, s.longs(as), null);
                    ByteBuffer ap = s.malloc(48);
                    ap.putFloat(0, w).putFloat(4, h).putFloat(8, 1 << i).putFloat(12, i).putFloat(16, iters - 1).putFloat(20, fb).putFloat(24, albedo ? 1 : 0)
                            .putFloat(28, f(m, "phiZ")).putFloat(32, f(m, "phiN")).putFloat(36, f(m, "phiL")).putFloat(40, mode).putFloat(44, f(m, "radiusScale"));
                    vkCmdPushConstants(cmd, apl, VK_SHADER_STAGE_COMPUTE_BIT, 0, ap);
                    vkCmdDispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);
                    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, mb, null, null);
                    lastDispatches++;
                }
                first[id] = false;
            }
            cur = prev;
        }
    }

    private void write(MemoryStack s, long set, long[] sampled, long[] stor) {
        VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(sampled.length + stor.length, s);
        for (int i = 0; i < sampled.length; i++)
            wr.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(sampler).imageView(sampled[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
        for (int i = 0; i < stor.length; i++)
            wr.get(sampled.length + i).sType$Default().dstSet(set).dstBinding(sampled.length + i).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(stor[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
        vkUpdateDescriptorSets(dev, wr, null);
    }

    private static ImageRef need(DenoiseFrame f, Slot s) {
        ImageRef r = f.images.get(s);
        if (r == null || !r.present()) throw new IllegalArgumentException("SvgfDenoiser needs " + s);
        return r;
    }

    @Override
    public void close() {
        vkDeviceWaitIdle(dev);
        vkDestroyPipeline(dev, tPipe, null);
        vkDestroyPipeline(dev, aPipe, null);
        vkDestroyPipelineLayout(dev, tpl, null);
        vkDestroyPipelineLayout(dev, apl, null);
        vkDestroyDescriptorSetLayout(dev, tLayout, null);
        vkDestroyDescriptorSetLayout(dev, aLayout, null);
        vkDestroyDescriptorPool(dev, pool, null);
        vkDestroySampler(dev, sampler, null);
        for (long v : views.values()) vkDestroyImageView(dev, v, null);
        for (int i = 0; i < 2; i++) {
            vkDestroyImageView(dev, zV[i], null); vkDestroyImageView(dev, nV[i], null); vkDestroyImageView(dev, filtV[i], null);
            gpu.destroyImage(zs[i]); gpu.destroyImage(ns[i]); gpu.destroyImage(filt[i]);
        }
        for (int c = 0; c < types.length; c++) for (int i = 0; i < 2; i++) { vkDestroyImageView(dev, histV[c][i], null); vkDestroyImageView(dev, momV[c][i], null); gpu.destroyImage(hist[c][i]); gpu.destroyImage(mom[c][i]); }
    }
}
