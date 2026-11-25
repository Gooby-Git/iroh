package net.vulkanmod.render.chunk.cull;

import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.vulkan.VK12.*;

/**
 * P2-7c (L5c): the Hi-Z depth pyramid of the main scene depth ({@code assets/iroh/shaders/terrain/hiz_reduce.comp}).
 * Raw Vulkan like {@link TerrainCull}, so the same class runs in the game and in the lavapipe replay (hizCullReplay).
 *
 * <p>Image: R32_SFLOAT, level 0 = ceil(W/2) x ceil(H/2) of the W x H depth image, level k = ceil(level k-1 / 2) down to
 * 1 x 1 (at most {@link #MAX_LEVELS}), kept in GENERAL (storage writes by the build, texelFetch by the cull). Texel
 * (x, y) of level k = the FARTHEST depth of the pixels [x * 2^(k+1), (x + 1) * 2^(k+1)) of the depth image (clamped):
 * with ceil halving every pixel belongs to exactly one texel per level, so the reduction is conservative at odd sizes.
 * Farthest = MAX for standard depth, MIN for reversed-Z (constructor flag; Iroh is standard depth today, FSR-Q Q9/Q10
 * may switch: the cull reads the same flag).
 *
 * <p>Reduction path ({@code IndirectDrawCaps.hiZPath}): level 0 always reads the depth through a depth-only view with
 * four texelFetch (depth formats are not required to support min/max filtering; D24S8 needs the depth-only view anyway).
 * Levels 1.. use one textureLod through a MAX / MIN reduction sampler where samplerFilterMinmax is enabled and R32F
 * supports SAMPLED_IMAGE_FILTER_MINMAX (MINMAX), else four texelFetch (SHADER). Both give identical values.
 *
 * <p>Frame rule: the caller records {@link #build} outside a render pass with the depth readable (DEPTH_STENCIL_READ_ONLY,
 * SHADER_READ_ONLY or GENERAL) and its attachment writes made visible to COMPUTE. The slot's descriptor sets are
 * rewritten at the first build of a new frame serial (that slot's previous frame has finished: the frame-slot rule),
 * so a second build in the same frame reuses them. The build ends with COMPUTE write -> COMPUTE read.
 */
public final class HiZPyramid {
    public static final int FORMAT = VK_FORMAT_R32_SFLOAT, MAX_LEVELS = 16;
    public static final String SHADER = "/assets/iroh/shaders/terrain/hiz_reduce.comp";

    private final VkDevice device;
    private final VkPhysicalDeviceMemoryProperties memProps;
    private final int frames;
    private final boolean minmax, reversed;
    private final TerrainCull.Deferred deferred;
    private final long setLayout, pipelineLayout, pool, pFetch, pMinmax, nearest, reduceSampler;
    private final long[][] sets;
    private final long[] slotSerial;

    private long image, memory, memBytes, fullView;
    private final long[] levelView = new long[MAX_LEVELS];
    private int srcW, srcH, w0, h0, levels;
    private boolean layoutReady;
    private long generation;
    /** frame serial of the last {@link #build} (the pyramid then holds that frame's depth) */
    public long builtSerial = Long.MIN_VALUE;
    /** counts completed builds: a record trusts only a build counted after it (review P2-7c #1) */
    public long builds;

    private Object depthOwner;
    private long depthImage, depthViewHandle;
    private int depthFormat;

    /** R32_SFLOAT with optimal tiling supports min/max reduction filtering (needed for the MINMAX path) */
    public static boolean r32fMinmax(VkPhysicalDevice phys) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkFormatProperties fp = VkFormatProperties.calloc(s);
            vkGetPhysicalDeviceFormatProperties(phys, FORMAT, fp);
            return (fp.optimalTilingFeatures() & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_MINMAX_BIT) != 0;
        }
    }

    /** the depth format can be sampled (the main depth is created SAMPLED; checked once before the first build) */
    public static boolean depthSampleable(VkPhysicalDevice phys, int depthFormat) {
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkFormatProperties fp = VkFormatProperties.calloc(s);
            vkGetPhysicalDeviceFormatProperties(phys, depthFormat, fp);
            return (fp.optimalTilingFeatures() & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0;
        }
    }

    /**
     * @param minmax   use the reduction sampler for levels 1.. (the caller passes IndirectDrawCaps.hiZPath() == MINMAX)
     * @param reversed reversed-Z depth (farthest = MIN)
     */
    public HiZPyramid(VkDevice device, VkPhysicalDeviceMemoryProperties memProps, int frames, boolean minmax, boolean reversed, TerrainCull.Deferred deferred) {
        this.device = device;
        this.memProps = memProps;
        this.frames = Math.max(1, frames);
        this.minmax = minmax;
        this.reversed = reversed;
        this.deferred = deferred;
        this.slotSerial = new long[this.frames];
        java.util.Arrays.fill(this.slotSerial, Long.MIN_VALUE);
        try (MemoryStack s = MemoryStack.stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(2, s);
            b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b), null, lb), "hiz set layout");
            this.setLayout = lb.get(0);
            VkPushConstantRange.Buffer pc = VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(20);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(this.setLayout)).pPushConstantRanges(pc), null, lb), "hiz layout");
            this.pipelineLayout = lb.get(0);
            int nSets = this.frames * MAX_LEVELS;
            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(2, s);
            ps.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(nSets);
            ps.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(nSets);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(nSets).pPoolSizes(ps), null, lb), "hiz pool");
            this.pool = lb.get(0);
            this.sets = new long[this.frames][MAX_LEVELS];
            LongBuffer layouts = s.mallocLong(MAX_LEVELS), out = s.mallocLong(MAX_LEVELS);
            for (int i = 0; i < MAX_LEVELS; i++) layouts.put(i, this.setLayout);
            for (int f = 0; f < this.frames; f++) {
                check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(this.pool).pSetLayouts(layouts), out), "hiz sets");
                for (int i = 0; i < MAX_LEVELS; i++) this.sets[f][i] = out.get(i);
            }
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(VK_LOD_CLAMP_NONE), null, lb), "hiz nearest sampler");
            this.nearest = lb.get(0);
            if (minmax) {
                VkSamplerReductionModeCreateInfo red = VkSamplerReductionModeCreateInfo.calloc(s).sType$Default()
                        .reductionMode(reversed ? VK_SAMPLER_REDUCTION_MODE_MIN : VK_SAMPLER_REDUCTION_MODE_MAX);
                check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(s).sType$Default().pNext(red.address()).magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0f), null, lb), "hiz reduction sampler");
                this.reduceSampler = lb.get(0);
            } else this.reduceSampler = VK_NULL_HANDLE;
            String src = source();
            this.pFetch = pipeline(s, "hiz_reduce.comp", src, null);
            this.pMinmax = minmax ? pipeline(s, "hiz_reduce_minmax.comp", src, "MINMAX") : VK_NULL_HANDLE;
        }
        ensure(2, 2); // a valid 1x1 pyramid from the start: the cull's descriptor always has an image
    }

    static String source() {
        try (InputStream in = HiZPyramid.class.getResourceAsStream(SHADER)) {
            if (in == null) throw new IllegalStateException("missing " + SHADER);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private long pipeline(MemoryStack outer, String name, String src, String define) {
        try (MemoryStack s = outer.push()) {
            String code = define == null ? src : src.replaceFirst("#version 450\n", "#version 450\n#define " + define + " 1\n");
            SPIRVUtils.SPIRV spirv = SPIRVUtils.compileShader(name, code, SPIRVUtils.ShaderKind.COMPUTE_SHADER);
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateShaderModule(this.device, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(spirv.bytecode()), null, lb), "shader module " + name);
            long module = lb.get(0);
            try {
                VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s);
                ci.get(0).sType$Default().layout(this.pipelineLayout).stage(VkPipelineShaderStageCreateInfo.calloc(s).sType$Default()
                        .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(s.UTF8("main")));
                long t0 = System.nanoTime();
                check(vkCreateComputePipelines(this.device, VK_NULL_HANDLE, ci, null, lb), "compute pipeline " + name);
                dev.elemore.iroh.perf.PipelineStats.created(dev.elemore.iroh.perf.PipelineStats.Kind.COMPUTE, System.nanoTime() - t0); // P2-11 rule
                return lb.get(0);
            } finally {
                vkDestroyShaderModule(this.device, module, null);
                spirv.free();
            }
        }
    }

    public boolean minmax() {
        return this.minmax;
    }

    public boolean reversed() {
        return this.reversed;
    }

    /**
     * the depth-only view of {@code image} (cached per {@code owner} object AND handle: a recreated image may reuse a handle
     * value, its owner object is new; the old view goes after the frames in flight when the image changes)
     */
    public long depthView(Object owner, long image, int format) {
        if (owner == this.depthOwner && image == this.depthImage && format == this.depthFormat && this.depthViewHandle != 0) return this.depthViewHandle;
        long old = this.depthViewHandle;
        VkDevice d = this.device;
        if (old != 0) this.deferred.later(() -> vkDestroyImageView(d, old, null));
        try (MemoryStack s = MemoryStack.stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(s).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            vi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            check(vkCreateImageView(this.device, vi, null, lb), "hiz depth view");
            this.depthViewHandle = lb.get(0);
        }
        this.depthOwner = owner;
        this.depthImage = image;
        this.depthFormat = format;
        return this.depthViewHandle;
    }

    /** level-0 size and level count for a w x h depth image */
    public static int[] layout(int w, int h) {
        int a = Math.max(1, (w + 1) >> 1), b = Math.max(1, (h + 1) >> 1);
        int n = 1;
        for (int x = a, y = b; (x > 1 || y > 1) && n < MAX_LEVELS; x = (x + 1) >> 1, y = (y + 1) >> 1) n++;
        return new int[]{a, b, n};
    }

    /** (re)creates the pyramid for a w x h depth image; true when it was recreated (its contents are undefined) */
    public boolean ensure(int w, int h) {
        if (w == this.srcW && h == this.srcH && this.image != 0) return false;
        destroyImage(true);
        int[] l = layout(w, h);
        this.srcW = w;
        this.srcH = h;
        this.w0 = l[0];
        this.h0 = l[1];
        this.levels = l[2];
        try (MemoryStack s = MemoryStack.stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(FORMAT).mipLevels(this.levels).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().set(this.w0, this.h0, 1);
            check(vkCreateImage(this.device, ci, null, lb), "hiz image");
            this.image = lb.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(s);
            vkGetImageMemoryRequirements(this.device, this.image, mr);
            int type = -1;
            for (int pass = 0; pass < 2 && type < 0; pass++)
                for (int i = 0; i < this.memProps.memoryTypeCount() && type < 0; i++)
                    if ((mr.memoryTypeBits() & 1 << i) != 0 && (pass == 1 || (this.memProps.memoryTypes(i).propertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0)) type = i;
            if (type < 0) throw new IllegalStateException("hiz: no memory type");
            int r = vkAllocateMemory(this.device, VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(mr.size()).memoryTypeIndex(type), null, lb);
            if (r != VK_SUCCESS) {
                vkDestroyImage(this.device, this.image, null);
                this.image = 0;
                check(r, "hiz memory " + mr.size());
            }
            this.memory = lb.get(0);
            this.memBytes = mr.size();
            check(vkBindImageMemory(this.device, this.image, this.memory, 0), "hiz bind");
            TerrainCull.EXTERNAL.accept(this.memBytes);
            for (int k = 0; k < this.levels; k++) this.levelView[k] = view(s, k, 1);
            this.fullView = view(s, 0, this.levels);
        }
        this.layoutReady = false;
        this.generation++;
        this.builtSerial = Long.MIN_VALUE;
        java.util.Arrays.fill(this.slotSerial, Long.MIN_VALUE); // the sets point at the old views
        return true;
    }

    private long view(MemoryStack outer, int base, int count) {
        try (MemoryStack s = outer.push()) {
            LongBuffer lb = s.mallocLong(1);
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(s).sType$Default().image(this.image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(FORMAT);
            vi.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(base).levelCount(count).baseArrayLayer(0).layerCount(1);
            check(vkCreateImageView(this.device, vi, null, lb), "hiz view");
            return lb.get(0);
        }
    }

    /**
     * Before the first read of a new image: UNDEFINED -> GENERAL (nothing written yet; the cull ignores a pyramid that
     * was never built). Outside a render pass.
     */
    public void prepare(VkCommandBuffer cmd) {
        if (this.layoutReady) return;
        try (MemoryStack s = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer ib = VkImageMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT).oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(this.image);
            ib.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(this.levels).baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, ib);
        }
        this.layoutReady = true;
    }

    /**
     * Records the pyramid of the depth seen through {@code depthView} (a depth-only view of a {@code w} x {@code h}
     * image resting in {@code depthLayout}). {@link #ensure} must have been called for this size in this frame before
     * anything bound the pyramid. Outside a render pass. Returns false and records nothing when the slot's descriptor sets,
     * already bound this frame, describe another depth view or layout (they cannot be rewritten until the slot's fence).
     */
    public boolean build(VkCommandBuffer cmd, int slot, long serial, long depthView, int depthLayout, int w, int h) {
        if (w != this.srcW || h != this.srcH) return false;
        if (this.slotSerial[slot] == serial && (this.slotDepthView != depthView || this.slotDepthLayout != depthLayout)) return false;
        prepare(cmd);
        try (MemoryStack s = MemoryStack.stackPush()) {
            long[] set = this.sets[slot];
            if (this.slotSerial[slot] != serial) { // first build of this frame on the slot: its previous frame is done
                this.slotSerial[slot] = serial;
                VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(2 * this.levels, s);
                for (int k = 0; k < this.levels; k++) {
                    long srcView = k == 0 ? depthView : this.levelView[k - 1];
                    int srcLayout = k == 0 ? depthLayout : VK_IMAGE_LAYOUT_GENERAL;
                    long smp = k > 0 && this.minmax ? this.reduceSampler : this.nearest;
                    wr.get(2 * k).sType$Default().dstSet(set[k]).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                            .pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(smp).imageView(srcView).imageLayout(srcLayout));
                    wr.get(2 * k + 1).sType$Default().dstSet(set[k]).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                            .pImageInfo(VkDescriptorImageInfo.calloc(1, s).imageView(this.levelView[k]).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                }
                vkUpdateDescriptorSets(this.device, wr, null);
                this.slotDepthView = depthView;
                this.slotDepthLayout = depthLayout;
            }
            // earlier reads of the pyramid (the cull) and earlier builds finish before this one writes
            VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            ByteBuffer pc = s.malloc(20);
            int sw = w, sh = h;
            int dw = this.w0, dh = this.h0;
            for (int k = 0; k < this.levels; k++) {
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, mb, null, null);
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, k > 0 && this.minmax ? this.pMinmax : this.pFetch);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, s.longs(set[k]), null);
                pc.putInt(0, sw).putInt(4, sh).putInt(8, dw).putInt(12, dh).putInt(16, this.reversed ? 1 : 0);
                vkCmdPushConstants(cmd, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
                vkCmdDispatch(cmd, (dw + 7) >> 3, (dh + 7) >> 3, 1);
                sw = dw;
                sh = dh;
                dw = Math.max(1, (dw + 1) >> 1);
                dh = Math.max(1, (dh + 1) >> 1);
            }
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, mb, null, null);
        }
        this.builtSerial = serial;
        this.builds++;
        return true;
    }

    private long slotDepthView;
    private int slotDepthLayout;

    public long fullView() { return this.fullView; }
    public long sampler() { return this.nearest; }
    public long image() { return this.image; }
    public int width0() { return this.w0; }
    public int height0() { return this.h0; }
    public int levels() { return this.levels; }
    public int sourceWidth() { return this.srcW; }
    public int sourceHeight() { return this.srcH; }
    /** changes whenever the image is recreated */
    public long generation() { return this.generation; }

    public long memoryBytes() {
        return this.memBytes;
    }

    private void destroyImage(boolean later) {
        if (this.image == 0) return;
        final long img = this.image, mem = this.memory, full = this.fullView, bytes = this.memBytes;
        final long[] views = java.util.Arrays.copyOf(this.levelView, this.levels);
        VkDevice d = this.device;
        Runnable r = () -> {
            for (long v : views) vkDestroyImageView(d, v, null);
            vkDestroyImageView(d, full, null);
            vkDestroyImage(d, img, null);
            vkFreeMemory(d, mem, null);
            TerrainCull.EXTERNAL.accept(-bytes);
        };
        if (later) this.deferred.later(r); else r.run();
        this.image = this.memory = this.fullView = 0;
        java.util.Arrays.fill(this.levelView, 0);
    }

    /** destroys everything now (the GPU no longer uses it) */
    public void destroyNow() {
        destroyImage(false);
        if (this.depthViewHandle != 0) vkDestroyImageView(this.device, this.depthViewHandle, null);
        this.depthViewHandle = 0;
        this.depthOwner = null;
        for (long p : new long[]{this.pFetch, this.pMinmax}) if (p != VK_NULL_HANDLE) vkDestroyPipeline(this.device, p, null);
        vkDestroySampler(this.device, this.nearest, null);
        if (this.reduceSampler != VK_NULL_HANDLE) vkDestroySampler(this.device, this.reduceSampler, null);
        vkDestroyDescriptorPool(this.device, this.pool, null);
        vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
        vkDestroyDescriptorSetLayout(this.device, this.setLayout, null);
    }

    private static void check(int r, String what) {
        if (r != VK_SUCCESS) throw new IllegalStateException("hiz: " + what + ": VkResult " + r);
    }
}
