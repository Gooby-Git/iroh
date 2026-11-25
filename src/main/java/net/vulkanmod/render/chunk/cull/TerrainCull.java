package net.vulkanmod.render.chunk.cull;

import net.vulkanmod.render.chunk.buffer.TerrainCommandWriter;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.VK12.*;

/**
 * P2-7b (L5b): GPU culling of the world arena's opaque terrain layers ({@code assets/iroh/shaders/terrain/terrain_cull.comp}).
 * Self-contained raw Vulkan (layout, pool, four compute pipelines, own buffers), so the same class runs in the game
 * (WorldRenderer.drawWorldLayer) and in the lavapipe replay (terrainCullReplay).
 *
 * <p>Frame flow ({@link #record}, outside any render pass; in game inside VulkanImage.endPassRunResume once per frame,
 * before the first opaque layer draws): the CPU walk's reachable sections are written by TerrainCommandWriter as for the
 * CPU path (P2-7a layout: draws per vertex page, firstInstance = section offset) plus one cull word per command (grid
 * slot | facing). They go into this frame slot's host staging region with the metadata updates (dirty slots of
 * {@link SectionCullData}, or the whole mirror after the device copy was (re)created) and the segment table (each
 * writer draw split into segments of {@link #SEG} commands). Then: barrier (the previous frames' indirect reads and
 * metadata reads finish before anything here writes), metadata scatter (or full copy), horizon clear/build/prefix when
 * the horizon technique is on, the cull (one workgroup per segment), barrier to DRAW_INDIRECT. The per-slot staging is
 * rewritten only after that slot's fence (the frame-slot rule), so no upload overwrites what a dispatch still reads.
 *
 * <p>Tiers (IndirectDrawCaps.cullTier): COUNT compacts each segment's visible commands in command order (the CPU order,
 * front to back for the opaque layers) and writes the segment's count; {@link #drawCulled} issues one
 * vkCmdDrawIndexedIndirectCount per segment. FIXED copies every command in place with instanceCount 0 when culled and
 * draws the writer's ranges with fixed-count vkCmdDrawIndexedIndirect. CPU: this class is never created.
 *
 * <p>Memory: metadata 16 B per grid slot (RD32: 1.6 MiB), out buffer 20 B per command + 4 B per segment, horizon 40 KiB,
 * per frame slot one host buffer sized to the frame's commands (grows, never shrinks). P2-7c (Hi-Z) hook: technique bit
 * {@link #T_HIZ} is reserved and ignored; the pyramid would be a fifth binding read in {@code visible()}.
 */
public final class TerrainCull {
    public static final int T_FRUSTUM = 1, T_HORIZON = 2, T_SCREEN = 4, T_FACING = 8, T_HIZ = 16;
    public static final int SEG = 2048, BINS = 512, RINGS = 20, HORIZON_RADIUS = 12, HB_MAX = 64;
    static final int PARAMS = 256, SETS_PER_SLOT = 4, LAYERS = SectionCullData.OPAQUE_LAYERS;
    public static final String SHADER = "/assets/iroh/shaders/terrain/terrain_cull.comp";

    /** what a frame's cull needs besides the commands */
    public static final class Frame {
        /** the terrain draw's MVP (column major) */
        public final float[] mvp = new float[16];
        public float originRelX, originRelY, originRelZ;
        public float vpX, vpY, vpW, vpH;
        public int camSecX, camSecY, camSecZ;
        public float camFracX, camFracY, camFracZ;
        public int techniques;
        public int horizonRadius = HORIZON_RADIUS;
    }

    public interface Deferred {
        /** run {@code r} once the frames recorded so far have finished on the GPU */
        void later(Runnable r);
    }

    private final VkDevice device;
    private final VkPhysicalDeviceMemoryProperties memProps;
    private final int frames;
    private final boolean compact;
    private final Deferred deferred;
    private final long setLayout, pipelineLayout, pool;
    private final long pScatter, pBuild, pPrefix, pCull;
    private final long[][] sets;
    private final int[] setUse;
    private final long[] slotSerial;

    private final Buf[] host;
    private final long[] bump;
    private Buf meta, out, horizon;
    private int metaSlots = -1;
    private long outCmdBytes, outCountBytes;

    // last record: per layer the global index of its first command, per writer draw its segments
    private final int[] layerBase = new int[LAYERS];
    private final int[][] drawSegStart = new int[LAYERS][];
    private int[] segFirst = new int[256], segCount = new int[256];
    private int segments, commands;
    // COUNT tier statistics: counts of the last record of each slot copied back (read after the slot's fence)
    private final long[] readbackOff;
    private final int[] readbackSegs;
    public long keptLast = -1, candidatesLast;
    private final long[] candidatesSlot;

    record Buf(long buffer, long memory, long ptr, long size, long memBytes) {}

    /**
     * @param compact COUNT tier (drawIndirectCount enabled) or FIXED. Offsets are aligned to 256 bytes, which covers
     *                every device's minStorageBufferOffsetAlignment / minUniformBufferOffsetAlignment.
     */
    public TerrainCull(VkDevice device, VkPhysicalDeviceMemoryProperties memProps, int frames, boolean compact, Deferred deferred) {
        this.device = device;
        this.memProps = memProps;
        this.frames = Math.max(1, frames);
        this.compact = compact;
        this.deferred = deferred;
        this.host = new Buf[this.frames];
        this.bump = new long[this.frames];
        this.slotSerial = new long[this.frames];
        java.util.Arrays.fill(this.slotSerial, Long.MIN_VALUE);
        this.readbackOff = new long[this.frames];
        this.readbackSegs = new int[this.frames];
        this.candidatesSlot = new long[this.frames];
        java.util.Arrays.fill(this.readbackOff, -1);
        try (MemoryStack s = MemoryStack.stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(5, s);
            b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            for (int i = 1; i < 5; i++)
                b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b), null, lb), "cull set layout");
            this.setLayout = lb.get(0);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(this.setLayout)), null, lb), "cull pipeline layout");
            this.pipelineLayout = lb.get(0);
            int nSets = this.frames * SETS_PER_SLOT;
            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(2, s);
            ps.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(nSets);
            ps.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(4 * nSets);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(nSets).pPoolSizes(ps), null, lb), "cull pool");
            this.pool = lb.get(0);
            this.sets = new long[this.frames][SETS_PER_SLOT];
            this.setUse = new int[this.frames];
            LongBuffer layouts = s.mallocLong(SETS_PER_SLOT);
            for (int i = 0; i < SETS_PER_SLOT; i++) layouts.put(i, this.setLayout);
            LongBuffer out = s.mallocLong(SETS_PER_SLOT);
            for (int f = 0; f < this.frames; f++) {
                check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(this.pool).pSetLayouts(layouts), out), "cull sets");
                for (int i = 0; i < SETS_PER_SLOT; i++) this.sets[f][i] = out.get(i);
            }
            String src = source();
            this.pScatter = pipeline(s, "terrain_cull_scatter.comp", src, "SCATTER");
            this.pBuild = pipeline(s, "terrain_cull_horizon.comp", src, "HORIZON_BUILD");
            this.pPrefix = pipeline(s, "terrain_cull_prefix.comp", src, "HORIZON_PREFIX");
            this.pCull = pipeline(s, "terrain_cull.comp", src, "CULL");
            this.horizon = buffer(s, (long) BINS * RINGS * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
        }
    }

    public boolean compact() {
        return this.compact;
    }

    /** frames in flight this instance was made for (a frame-queue change needs a new instance) */
    public int frames() {
        return this.frames;
    }

    static String source() {
        try (InputStream in = TerrainCull.class.getResourceAsStream(SHADER)) {
            if (in == null) throw new IllegalStateException("missing " + SHADER);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private long pipeline(MemoryStack outer, String name, String src, String define) {
        try (MemoryStack s = outer.push()) {
            String code = src.replaceFirst("#version 450\n", "#version 450\n#define " + define + " 1\n");
            SPIRVUtils.SPIRV spirv = SPIRVUtils.compileShader(name, code, SPIRVUtils.ShaderKind.COMPUTE_SHADER);
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateShaderModule(this.device, VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(spirv.bytecode()), null, lb), "shader module " + name);
            long module = lb.get(0);
            try {
                VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, s);
                ci.get(0).sType$Default().layout(this.pipelineLayout).stage(VkPipelineShaderStageCreateInfo.calloc(s).sType$Default()
                        .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(s.UTF8("main")));
                check(vkCreateComputePipelines(this.device, VK_NULL_HANDLE, ci, null, lb), "compute pipeline " + name);
                return lb.get(0);
            } finally {
                vkDestroyShaderModule(this.device, module, null);
                spirv.free();
            }
        }
    }

    private int memoryType(int bits, int want, int fallback) {
        for (int pass = 0; pass < 2; pass++) {
            int flags = pass == 0 ? want : fallback;
            for (int i = 0; i < this.memProps.memoryTypeCount(); i++)
                if ((bits & 1 << i) != 0 && (this.memProps.memoryTypes(i).propertyFlags() & flags) == flags) return i;
        }
        throw new IllegalStateException("terrain cull: no memory type for " + want);
    }

    private Buf buffer(MemoryStack outer, long size, int usage, boolean hostVisible) {
        try (MemoryStack s = outer.push()) {
            LongBuffer lb = s.mallocLong(1);
            check(vkCreateBuffer(this.device, VkBufferCreateInfo.calloc(s).sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, lb), "cull buffer");
            long buf = lb.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(s);
            vkGetBufferMemoryRequirements(this.device, buf, mr);
            int hv = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            int type = hostVisible ? memoryType(mr.memoryTypeBits(), hv, hv) : memoryType(mr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
            int r = vkAllocateMemory(this.device, VkMemoryAllocateInfo.calloc(s).sType$Default().allocationSize(mr.size()).memoryTypeIndex(type), null, lb);
            if (r != VK_SUCCESS) {
                vkDestroyBuffer(this.device, buf, null);
                check(r, "cull memory " + size);
            }
            long mem = lb.get(0);
            check(vkBindBufferMemory(this.device, buf, mem, 0), "cull bind");
            long ptr = 0;
            if (hostVisible) {
                PointerBuffer pp = s.mallocPointer(1);
                check(vkMapMemory(this.device, mem, 0, VK_WHOLE_SIZE, 0, pp), "cull map");
                ptr = pp.get(0);
            }
            EXTERNAL.accept(mr.size());
            return new Buf(buf, mem, ptr, size, mr.size());
        }
    }

    /** device memory outside VMA is reported to the memory guard (game); no-op offline */
    public static volatile Consumer<Long> EXTERNAL = b -> {};

    private void free(Buf b) {
        if (b == null) return;
        VkDevice d = this.device;
        long bytes = b.memBytes;
        this.deferred.later(() -> {
            vkDestroyBuffer(d, b.buffer, null);
            vkFreeMemory(d, b.memory, null);
            EXTERNAL.accept(-bytes);
        });
    }

    private static long align(long v) {
        return (v + 255) & ~255L;
    }

    /**
     * Writes this frame's inputs into slot {@code slot}'s staging and records the cull. {@code layers[l]} = the finished
     * writer of opaque layer l (TerrainRenderType ordinals 0-2; null or empty = nothing), written with
     * {@link TerrainCommandWriter#beginCull}. {@code serial} changes once per frame (a new frame resets the slot's staging).
     * Returns false when nothing can be culled (no technique, no commands, out of descriptor sets this frame): the caller
     * draws through the CPU path.
     */
    public boolean record(VkCommandBuffer cmd, int slot, long serial, Frame f, SectionCullData data, TerrainCommandWriter[] layers) {
        if (f.techniques == 0) return false;
        if (this.slotSerial[slot] != serial) {
            this.slotSerial[slot] = serial;
            this.bump[slot] = 0;
            this.setUse[slot] = 0;
            readStats(slot);
        }
        if (this.setUse[slot] >= SETS_PER_SLOT) return false;
        this.gridW = data.width;
        this.gridH = data.height;
        this.gridMinY = data.minSecY;
        // segments per writer draw
        int total = 0, segs = 0;
        for (int l = 0; l < LAYERS; l++) {
            TerrainCommandWriter w = layers[l];
            this.layerBase[l] = total;
            int draws = w == null ? 0 : w.draws();
            if (this.drawSegStart[l] == null || this.drawSegStart[l].length < draws + 1) this.drawSegStart[l] = new int[Math.max(16, draws + 1) * 2];
            for (int d = 0; d < draws; d++) {
                this.drawSegStart[l][d] = segs;
                int first = total + w.drawFirst(d), count = w.drawCount(d);
                for (int o = 0; o < count; o += SEG) {
                    if (segs == this.segFirst.length) {
                        this.segFirst = java.util.Arrays.copyOf(this.segFirst, segs * 2);
                        this.segCount = java.util.Arrays.copyOf(this.segCount, segs * 2);
                    }
                    this.segFirst[segs] = first + o;
                    this.segCount[segs++] = Math.min(SEG, count - o);
                }
            }
            this.drawSegStart[l][draws] = segs;
            if (w != null) total += w.totalCommands();
        }
        this.segments = segs;
        this.commands = total;
        if (total == 0) return false;

        try (MemoryStack s = MemoryStack.stackPush()) {
            // metadata device copy
            if (this.metaSlots != data.size) {
                free(this.meta);
                this.meta = buffer(s, Math.max(16L, (long) data.size * 16), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
                this.metaSlots = data.size;
                data.markFull();
            }
            boolean full = data.fullPending();
            long updBytes = full ? (long) data.size * 16 : (long) data.dirtyCount() * 20;
            // out buffer: counts, then commands
            long countBytes = align((long) segs * 4), cmdBytes = (long) total * TerrainCommandWriter.STRIDE;
            if (this.out == null || countBytes > this.outCountBytes || cmdBytes > this.outCmdBytes) {
                free(this.out);
                this.outCountBytes = Math.max(countBytes, this.outCountBytes) * 3 / 2 + 256;
                this.outCountBytes = align(this.outCountBytes);
                this.outCmdBytes = Math.max(cmdBytes, this.outCmdBytes) * 3 / 2;
                this.out = buffer(s, this.outCountBytes + this.outCmdBytes,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, false);
            }
            // host region: params | updates | segments | words | commands | count readback
            long oUpd = 0, oSeg = align(oUpd + updBytes), oWords = align(oSeg + (long) segs * 16), oCmd = align(oWords + (long) total * 4);
            long oRead = align(oCmd + cmdBytes), hostBytes = oRead + (this.compact ? (long) segs * 4 : 0);
            long need = PARAMS + hostBytes;
            Buf h = this.host[slot];
            if (h == null || this.bump[slot] + need > h.size) {
                if (h != null) free(h);
                long size = Math.max(need * 3 / 2, h == null ? 1 << 20 : h.size * 2);
                this.host[slot] = h = buffer(s, align(size), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
                this.bump[slot] = 0;
            }
            long base = this.bump[slot];
            this.bump[slot] = align(base + need);
            long p = h.ptr + base, hb = p + PARAMS;
            int updates = 0;
            if (full) data.drainFull(hb + oUpd);
            else updates = data.drainDirty(hb + oUpd);
            for (int i = 0; i < segs; i++) {
                long q = hb + oSeg + i * 16L;
                MemoryUtil.memPutInt(q, this.segFirst[i]);
                MemoryUtil.memPutInt(q + 4, this.segCount[i]);
                MemoryUtil.memPutInt(q + 8, layerOf(this.segFirst[i]));
                MemoryUtil.memPutInt(q + 12, 0);
            }
            for (int l = 0; l < LAYERS; l++) {
                TerrainCommandWriter w = layers[l];
                if (w == null || w.totalCommands() == 0) continue;
                w.copyWordsTo(hb + oWords + this.layerBase[l] * 4L);
                w.copyTo(hb + oCmd + (long) this.layerBase[l] * TerrainCommandWriter.STRIDE);
            }
            writeParams(p, f, segs, updates, oUpd, oSeg, oWords, oCmd);

            long set = this.sets[slot][this.setUse[slot]++];
            VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(5, s);
            bufInfo(s, wr, 0, set, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, h.buffer, base, PARAMS);
            bufInfo(s, wr, 1, set, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, this.meta.buffer, 0, VK_WHOLE_SIZE);
            bufInfo(s, wr, 2, set, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, h.buffer, base + PARAMS, hostBytes);
            bufInfo(s, wr, 3, set, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, this.out.buffer, 0, VK_WHOLE_SIZE);
            bufInfo(s, wr, 4, set, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, this.horizon.buffer, 0, VK_WHOLE_SIZE);
            vkUpdateDescriptorSets(this.device, wr, null);

            // the previous frames' indirect reads / metadata and horizon reads finish before this frame writes
            barrier(s, cmd, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
            boolean horizonOn = (f.techniques & T_HORIZON) != 0;
            if (full) {
                VkBufferCopy.Buffer c = VkBufferCopy.calloc(1, s).srcOffset(base + PARAMS + oUpd).dstOffset(0).size((long) data.size * 16);
                vkCmdCopyBuffer(cmd, h.buffer, this.meta.buffer, c);
            }
            if (horizonOn) vkCmdFillBuffer(cmd, this.horizon.buffer, 0, VK_WHOLE_SIZE, 0);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, s.longs(set), null);
            if (updates > 0) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pScatter);
                vkCmdDispatch(cmd, (updates + 63) / 64, 1, 1);
            }
            barrier(s, cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            if (horizonOn) {
                int d = 2 * f.horizonRadius + 1;
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pBuild);
                vkCmdDispatch(cmd, (d * d + 63) / 64, 1, 1);
                barrier(s, cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pPrefix);
                vkCmdDispatch(cmd, (BINS + 63) / 64, 1, 1);
                barrier(s, cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            }
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pCull);
            vkCmdDispatch(cmd, segs, 1, 1);
            int dstStage = VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | (this.compact ? VK_PIPELINE_STAGE_TRANSFER_BIT : 0);
            barrier(s, cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    dstStage, VK_ACCESS_INDIRECT_COMMAND_READ_BIT | (this.compact ? VK_ACCESS_TRANSFER_READ_BIT : 0));
            if (this.compact) { // kept-command statistics, read after this slot's fence
                VkBufferCopy.Buffer c = VkBufferCopy.calloc(1, s).srcOffset(0).dstOffset(base + PARAMS + oRead).size((long) segs * 4);
                vkCmdCopyBuffer(cmd, this.out.buffer, h.buffer, c);
                this.readbackOff[slot] = base + PARAMS + oRead;
                this.readbackSegs[slot] = segs;
            }
            this.candidatesSlot[slot] = total;
        }
        return true;
    }

    /** the slot's last COUNT-tier counts (its fence has passed when the slot records again) */
    private void readStats(int slot) {
        Buf h = this.host[slot];
        if (h == null || this.readbackOff[slot] < 0) return;
        long sum = 0;
        for (int i = 0; i < this.readbackSegs[slot]; i++) sum += Integer.toUnsignedLong(MemoryUtil.memGetInt(h.ptr + this.readbackOff[slot] + i * 4L));
        this.keptLast = sum;
        this.candidatesLast = this.candidatesSlot[slot];
        this.readbackOff[slot] = -1;
    }

    private int layerOf(int globalCmd) {
        int l = 0;
        while (l + 1 < LAYERS && globalCmd >= this.layerBase[l + 1]) l++;
        return l;
    }

    private void writeParams(long p, Frame f, int segs, int updates, long oUpd, long oSeg, long oWords, long oCmd) {
        MemoryUtil.memSet(p, 0, PARAMS);
        for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(p + i * 4L, f.mvp[i]);
        putF(p + 64, f.originRelX, f.originRelY, f.originRelZ, 0);
        putF(p + 80, f.vpX, f.vpY, f.vpW, f.vpH);
        putF(p + 96, f.camFracX, f.camFracY, f.camFracZ, 0);
        putI(p + 112, f.camSecX, f.camSecY, f.camSecZ, f.horizonRadius);
        putI(p + 144, f.techniques & ~T_HIZ, this.compact ? 1 : 0, segs, updates);
        putI(p + 160, (int) (oUpd >> 2), (int) (oSeg >> 2), (int) (oWords >> 2), (int) (oCmd >> 2));
        putI(p + 176, (int) (this.outCountBytes >> 2), 0, 0, 0);
        putI(p + 128, this.gridW, this.gridH, this.gridMinY, 0);
    }

    private int gridW, gridH, gridMinY;

    private static void putF(long p, float a, float b, float c, float d) {
        MemoryUtil.memPutFloat(p, a);
        MemoryUtil.memPutFloat(p + 4, b);
        MemoryUtil.memPutFloat(p + 8, c);
        MemoryUtil.memPutFloat(p + 12, d);
    }

    private static void putI(long p, int a, int b, int c, int d) {
        MemoryUtil.memPutInt(p, a);
        MemoryUtil.memPutInt(p + 4, b);
        MemoryUtil.memPutInt(p + 8, c);
        MemoryUtil.memPutInt(p + 12, d);
    }

    private static void bufInfo(MemoryStack s, VkWriteDescriptorSet.Buffer wr, int i, long set, int type, long buffer, long off, long range) {
        wr.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorType(type).descriptorCount(1)
                .pBufferInfo(VkDescriptorBufferInfo.calloc(1, s).buffer(buffer).offset(off).range(range));
    }

    private static void barrier(MemoryStack s, VkCommandBuffer cmd, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, mb, null, null);
    }

    /**
     * Draw d of opaque layer {@code layer} from the last {@link #record} (the caller has bound the pipeline, the draw's
     * vertex page and index buffer): COUNT one vkCmdDrawIndexedIndirectCount per segment, FIXED one fixed-count
     * vkCmdDrawIndexedIndirect over the writer's range. Returns the indirect calls issued.
     */
    public int drawCulled(VkCommandBuffer cmd, int layer, int d, TerrainCommandWriter w) {
        final int stride = TerrainCommandWriter.STRIDE;
        long cmdBase = this.outCountBytes;
        if (!this.compact) {
            vkCmdDrawIndexedIndirect(cmd, this.out.buffer, cmdBase + (long) (this.layerBase[layer] + w.drawFirst(d)) * stride, w.drawCount(d), stride);
            return 1;
        }
        int s0 = this.drawSegStart[layer][d], s1 = this.drawSegStart[layer][d + 1];
        for (int sg = s0; sg < s1; sg++)
            vkCmdDrawIndexedIndirectCount(cmd, this.out.buffer, cmdBase + (long) this.segFirst[sg] * stride, this.out.buffer, sg * 4L, this.segCount[sg], stride);
        return s1 - s0;
    }

    public int segments() {
        return this.segments;
    }

    public int commands() {
        return this.commands;
    }

    /** tests: the out buffer handle, its command offset (bytes) and the segment table of the last record */
    public long outBuffer() { return this.out == null ? 0 : this.out.buffer; }
    public long outCommandOffset() { return this.outCountBytes; }
    public int segFirst(int i) { return this.segFirst[i]; }
    public int segCount(int i) { return this.segCount[i]; }
    public int layerBase(int l) { return this.layerBase[l]; }

    public long memoryBytes() {
        long n = this.horizon.size + (this.meta == null ? 0 : this.meta.size) + (this.out == null ? 0 : this.out.size);
        for (Buf b : this.host) if (b != null) n += b.size;
        return n;
    }

    /** destroys everything now (the caller made sure the GPU no longer uses it, e.g. vkDeviceWaitIdle or a frame op) */
    public void destroyNow() {
        for (Buf b : new Buf[]{this.meta, this.out, this.horizon}) destroyBuf(b);
        for (int i = 0; i < this.host.length; i++) destroyBuf(this.host[i]);
        this.meta = this.out = this.horizon = null;
        java.util.Arrays.fill(this.host, null);
        for (long p : new long[]{this.pScatter, this.pBuild, this.pPrefix, this.pCull}) vkDestroyPipeline(this.device, p, null);
        vkDestroyDescriptorPool(this.device, this.pool, null);
        vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
        vkDestroyDescriptorSetLayout(this.device, this.setLayout, null);
    }

    private void destroyBuf(Buf b) {
        if (b == null) return;
        vkDestroyBuffer(this.device, b.buffer, null);
        vkFreeMemory(this.device, b.memory, null);
        EXTERNAL.accept(-b.memBytes);
    }

    private static void check(int r, String what) {
        if (r != VK_SUCCESS) throw new IllegalStateException("terrain cull: " + what + ": VkResult " + r);
    }
}
