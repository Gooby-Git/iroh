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
 * per frame slot one host buffer sized to the frame's commands (grows, never shrinks).
 *
 * <p>P2-7c (L5c) two-pass Hi-Z occlusion ({@link #T_HIZ}, {@link HiZPyramid} owned here, binding 5): pass 1 ({@link #record})
 * also tests each command's box, moved into the previous camera's space, against the pyramid of the previous frame
 * ({@link Frame#prevMvp}; only when the box lies wholly inside that frame's screen). A command that fails ONLY that test
 * is not drawn and gets a retest flag. After the opaque layers drew, the caller builds the pyramid from the current depth
 * ({@link #buildPyramid}) and records {@link #recordRetest}: each flagged command is tested against it with the current
 * MVP and the survivors (disocclusions: geometry the last frame's depth hid) are drawn by {@link #drawRetest}. The retest
 * is exact against what was drawn, so pass 1 may be wrong in any way (fast turns, teleports, changed blocks) without a
 * visible section going missing. Translucent ({@link #recordTranslucent}, its own job and out buffer): frustum / screen
 * size as asked plus Hi-Z against the pyramid built right before (the depth the translucent draw is tested against), order
 * kept (compaction is stable, segments drawn in order). Out buffer per job: counts | commands, and with Hi-Z also
 * counts2 | flagged per segment | commands2 | flags (4 B per command).
 */
public final class TerrainCull {
    public static final int T_FRUSTUM = 1, T_HORIZON = 2, T_SCREEN = 4, T_FACING = 8, T_HIZ = 16;
    public static final int SEG = 2048, BINS = 512, RINGS = 20, HORIZON_RADIUS = 12, HB_MAX = 64;
    static final int PARAMS = 512, SETS_PER_SLOT = 6, LAYERS = SectionCullData.OPAQUE_LAYERS;
    /** params hz.w: pass 1 may test the previous pyramid, depth is reversed-Z, the CULL entry tests the CURRENT pyramid (translucent) */
    static final int HZ_PREV = 1, HZ_REVERSED = 2, HZ_CURRENT = 4;
    /** Hi-Z depth slack (NDC depth units, about 16 float ulps near 1) and pixel margin of the projected box */
    public static final float HIZ_EPS = 1f / (1 << 20), HIZ_MARGIN = 0.0625f;
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
        // ---- P2-7c Hi-Z
        /** the depth target the pyramid follows (the main scene target; Hi-Z is off on any other target) */
        public int depthW, depthH;
        /** pass 1: the MVP of the frame whose depth the pyramid holds, and camera now - camera then (blocks) */
        public final float[] prevMvp = new float[16];
        public float prevShiftX, prevShiftY, prevShiftZ;
        /** pass 1 may use the pyramid (built last frame at this size with prevMvp); the class also checks the size */
        public boolean prevValid;
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
    private final long pScatter, pBuild, pPrefix, pCull, pRetest;
    private final long[][] sets;
    private final int[] setUse;
    private final long[] slotSerial;
    private final HiZPyramid hiz;

    private final Buf[] host;
    private final long[] bump;
    private Buf meta, horizon;
    private int metaSlots = -1;

    /**
     * One cull's state (the opaque layers' record, or the translucent layer's): per writer the global index of its first
     * command, per writer draw its segments, the out buffer and the offsets of its regions, the descriptor set it used.
     */
    private final class Job {
        final int[] layerIds;
        final int[] layerBase;
        final int[][] drawSegStart;
        int[] segFirst = new int[256], segCount = new int[256];
        int segments, commands, tech, mode, slot;
        Buf out;
        int capSegs = -1, capCmds = -1;
        boolean outHiz;
        long oCmd1, oCnt2, oFlg2, oCmd2, oFlags;
        long set;
        boolean recorded, retested;
        long hizGeneration;
        // statistics, read after the slot's fence: per slot the host offsets of the copied counts
        final long[] readCnt1 = new long[TerrainCull.this.frames], readCnt2 = new long[TerrainCull.this.frames];
        final int[] readSegs = new int[TerrainCull.this.frames];
        final long[] candidatesSlot = new long[TerrainCull.this.frames];
        long kept = -1, candidates, hizFlagged = -1, hizKept = -1;

        Job(int... layerIds) {
            this.layerIds = layerIds;
            this.layerBase = new int[layerIds.length];
            this.drawSegStart = new int[layerIds.length][];
            java.util.Arrays.fill(this.readCnt1, -1);
            java.util.Arrays.fill(this.readCnt2, -1);
        }

        int writerOf(int globalCmd) {
            int l = 0;
            while (l + 1 < this.layerIds.length && globalCmd >= this.layerBase[l + 1]) l++;
            return l;
        }

        void readStats(int slot) {
            Buf h = TerrainCull.this.host[slot];
            if (h == null) return;
            int n = this.readSegs[slot];
            if (this.readCnt1[slot] >= 0) {
                long sum = 0;
                for (int i = 0; i < n; i++) sum += Integer.toUnsignedLong(MemoryUtil.memGetInt(h.ptr + this.readCnt1[slot] + i * 4L));
                this.kept = sum;
                this.candidates = this.candidatesSlot[slot];
                this.readCnt1[slot] = -1;
            }
            if (this.readCnt2[slot] >= 0) { // counts2 then flagged per segment
                long k = 0, f = 0;
                for (int i = 0; i < n; i++) {
                    k += Integer.toUnsignedLong(MemoryUtil.memGetInt(h.ptr + this.readCnt2[slot] + i * 4L));
                    f += Integer.toUnsignedLong(MemoryUtil.memGetInt(h.ptr + this.readCnt2[slot] + (n + i) * 4L));
                }
                this.hizKept = k;
                this.hizFlagged = f;
                this.readCnt2[slot] = -1;
            }
        }
    }

    private final Job opaque, translucent; // made in the constructor (their per-slot arrays need this.frames)

    record Buf(long buffer, long memory, long ptr, long size, long memBytes) {}

    /** P2-7b constructor: standard depth, shader reduction */
    public TerrainCull(VkDevice device, VkPhysicalDeviceMemoryProperties memProps, int frames, boolean compact, Deferred deferred) {
        this(device, memProps, frames, compact, deferred, false, false);
    }

    /**
     * @param compact      COUNT tier (drawIndirectCount enabled) or FIXED. Offsets are aligned to 256 bytes, which covers
     *                     every device's minStorageBufferOffsetAlignment / minUniformBufferOffsetAlignment.
     * @param hizMinmax    Hi-Z levels 1.. through the reduction sampler (IndirectDrawCaps.hiZPath() == MINMAX)
     * @param reversedZ    the depth buffer is reversed-Z (pyramid keeps MIN, the occlusion test flips)
     */
    public TerrainCull(VkDevice device, VkPhysicalDeviceMemoryProperties memProps, int frames, boolean compact, Deferred deferred,
                       boolean hizMinmax, boolean reversedZ) {
        this.device = device;
        this.memProps = memProps;
        this.frames = Math.max(1, frames);
        this.compact = compact;
        this.deferred = deferred;
        this.host = new Buf[this.frames];
        this.bump = new long[this.frames];
        this.slotSerial = new long[this.frames];
        java.util.Arrays.fill(this.slotSerial, Long.MIN_VALUE);
        this.opaque = new Job(0, 1, 2);
        this.translucent = new Job(SectionCullData.TRANSLUCENT_LAYER);
        try (MemoryStack s = MemoryStack.stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(6, s);
            b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            for (int i = 1; i < 5; i++)
                b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(5).binding(5).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(b), null, lb), "cull set layout");
            this.setLayout = lb.get(0);
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(4);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(this.setLayout)).pPushConstantRanges(pcr), null, lb), "cull pipeline layout");
            this.pipelineLayout = lb.get(0);
            int nSets = this.frames * SETS_PER_SLOT;
            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(3, s);
            ps.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(nSets);
            ps.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(4 * nSets);
            ps.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(nSets);
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
            this.pRetest = pipeline(s, "terrain_cull_retest.comp", src, "RETEST");
            this.horizon = buffer(s, (long) BINS * RINGS * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
        }
        this.hiz = new HiZPyramid(device, memProps, this.frames, hizMinmax, reversedZ, deferred);
    }

    public boolean compact() {
        return this.compact;
    }

    /** frames in flight this instance was made for (a frame-queue change needs a new instance) */
    public int frames() {
        return this.frames;
    }

    /** P2-7c: the Hi-Z pyramid this cull reads (built by {@link #buildPyramid}) */
    public HiZPyramid hiz() {
        return this.hiz;
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

    /** a new frame on the slot: its staging, descriptor sets and statistics are free again (its fence has passed) */
    private void beginSlot(int slot, long serial) {
        if (this.slotSerial[slot] == serial) return;
        this.slotSerial[slot] = serial;
        this.bump[slot] = 0;
        this.setUse[slot] = 0;
        this.opaque.readStats(slot);
        this.translucent.readStats(slot);
        this.opaque.recorded = this.opaque.retested = false;
        this.translucent.recorded = false;
    }

    /**
     * Writes this frame's inputs into slot {@code slot}'s staging and records the cull. {@code layers[l]} = the finished
     * writer of opaque layer l (TerrainRenderType ordinals 0-2; null or empty = nothing), written with
     * {@link TerrainCommandWriter#beginCull}. {@code serial} changes once per frame (a new frame resets the slot's staging).
     * With {@link #T_HIZ} in {@code f.techniques} the pyramid is (re)sized for {@code f.depthW x f.depthH} first (a resize
     * makes the previous pyramid unusable for pass 1) and pass 1 writes the retest flags. Returns false when nothing can
     * be culled (no technique, no commands, out of descriptor sets this frame): the caller draws through the CPU path.
     */
    public boolean record(VkCommandBuffer cmd, int slot, long serial, Frame f, SectionCullData data, TerrainCommandWriter[] layers) {
        if (f.techniques == 0) return false;
        beginSlot(slot, serial);
        int tech = f.techniques;
        int mode = this.hiz.reversed() ? HZ_REVERSED : 0;
        if ((tech & T_HIZ) != 0) {
            if (f.depthW <= 0 || f.depthH <= 0) tech &= ~T_HIZ;
            else {
                boolean reset = this.hiz.ensure(f.depthW, f.depthH);
                if (f.prevValid && !reset && this.hiz.builtSerial != Long.MIN_VALUE) mode |= HZ_PREV;
            }
        }
        return recordJob(this.opaque, cmd, slot, f, data, layers, tech, mode);
    }

    /**
     * P2-7c: the translucent layer (one writer, {@link TerrainCommandWriter#beginCull(boolean, int, int, int, int)} ordered)
     * through frustum / screen size as asked and Hi-Z against the pyramid built THIS frame ({@link #buildPyramid}, the
     * depth the translucent draw is tested against). Order is kept. False = draw it through the CPU path.
     */
    public boolean recordTranslucent(VkCommandBuffer cmd, int slot, long serial, Frame f, SectionCullData data, TerrainCommandWriter writer) {
        beginSlot(slot, serial);
        int tech = f.techniques & (T_FRUSTUM | T_SCREEN | T_HIZ);
        if ((tech & T_HIZ) != 0 && (this.hiz.builtSerial != serial || this.hiz.sourceWidth() != f.depthW || this.hiz.sourceHeight() != f.depthH))
            tech &= ~T_HIZ; // no pyramid of this frame's depth: frustum / screen size only
        if (tech == 0) return false;
        int mode = (this.hiz.reversed() ? HZ_REVERSED : 0) | HZ_CURRENT;
        return recordJob(this.translucent, cmd, slot, f, data, new TerrainCommandWriter[]{writer}, tech, mode);
    }

    private boolean recordJob(Job job, VkCommandBuffer cmd, int slot, Frame f, SectionCullData data, TerrainCommandWriter[] layers, int tech, int mode) {
        job.recorded = false;
        job.retested = false;
        if (this.setUse[slot] >= SETS_PER_SLOT) return false;
        this.gridW = data.width;
        this.gridH = data.height;
        this.gridMinY = data.minSecY;
        int nl = job.layerIds.length;
        // segments per writer draw
        int total = 0, segs = 0;
        for (int l = 0; l < nl; l++) {
            TerrainCommandWriter w = layers[l];
            job.layerBase[l] = total;
            int draws = w == null ? 0 : w.draws();
            if (job.drawSegStart[l] == null || job.drawSegStart[l].length < draws + 1) job.drawSegStart[l] = new int[Math.max(16, draws + 1) * 2];
            for (int d = 0; d < draws; d++) {
                job.drawSegStart[l][d] = segs;
                int first = total + w.drawFirst(d), count = w.drawCount(d);
                for (int o = 0; o < count; o += SEG) {
                    if (segs == job.segFirst.length) {
                        job.segFirst = java.util.Arrays.copyOf(job.segFirst, segs * 2);
                        job.segCount = java.util.Arrays.copyOf(job.segCount, segs * 2);
                    }
                    job.segFirst[segs] = first + o;
                    job.segCount[segs++] = Math.min(SEG, count - o);
                }
            }
            job.drawSegStart[l][draws] = segs;
            if (w != null) total += w.totalCommands();
        }
        job.segments = segs;
        job.commands = total;
        if (total == 0) return false;
        boolean hizOn = (tech & T_HIZ) != 0;
        boolean flags = hizOn && (mode & HZ_CURRENT) == 0; // pass 1 of the opaque job: retest flags + regions 2

        try (MemoryStack s = MemoryStack.stackPush()) {
            // metadata device copy
            if (this.metaSlots != data.size) {
                free(this.meta);
                this.meta = buffer(s, Math.max(32L, (long) data.size * SectionCullData.WORDS * 4), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false);
                this.metaSlots = data.size;
                data.markFull();
            }
            boolean full = data.fullPending();
            long updBytes = full ? (long) data.size * SectionCullData.WORDS * 4 : (long) data.dirtyCount() * SectionCullData.RECORD_BYTES;
            // out buffer: counts, commands [, counts2, flagged per segment, commands2, flags]
            if (job.out == null || segs > job.capSegs || total > job.capCmds || (flags && !job.outHiz)) {
                free(job.out);
                job.capSegs = Math.max(segs, job.capSegs) * 3 / 2 + 64;
                job.capCmds = Math.max(total, job.capCmds) * 3 / 2;
                job.outHiz = flags || job.outHiz;
                long cnt = align((long) job.capSegs * 4), cmds = align((long) job.capCmds * TerrainCommandWriter.STRIDE);
                job.oCmd1 = cnt;
                job.oCnt2 = cnt + cmds;
                job.oFlg2 = job.oCnt2 + cnt;
                job.oCmd2 = job.oFlg2 + cnt;
                job.oFlags = job.oCmd2 + cmds;
                long size = job.outHiz ? job.oFlags + align((long) job.capCmds * 4) : job.oCnt2;
                job.out = buffer(s, size, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, false);
            }
            // host region: params | updates | segments | words | commands | readback counts [| counts2 + flagged]
            long cmdBytes = (long) total * TerrainCommandWriter.STRIDE;
            long oUpd = 0, oSeg = align(oUpd + updBytes), oWords = align(oSeg + (long) segs * 16), oCmd = align(oWords + (long) total * 4);
            long oRead = align(oCmd + cmdBytes), oRead2 = align(oRead + (this.compact ? (long) segs * 4 : 0));
            long hostBytes = oRead2 + (flags ? (long) segs * 8 : 0);
            long need = PARAMS + hostBytes;
            Buf h = this.host[slot];
            if (h == null || this.bump[slot] + need > h.size) {
                if (h != null) {
                    // a mid-frame grow: the slot's earlier records of this frame still read the old buffer (freed after the frame)
                    free(h);
                    this.opaque.readCnt1[slot] = this.opaque.readCnt2[slot] = this.translucent.readCnt1[slot] = -1;
                }
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
                MemoryUtil.memPutInt(q, job.segFirst[i]);
                MemoryUtil.memPutInt(q + 4, job.segCount[i]);
                MemoryUtil.memPutInt(q + 8, job.layerIds[job.writerOf(job.segFirst[i])]);
                MemoryUtil.memPutInt(q + 12, 0);
            }
            for (int l = 0; l < nl; l++) {
                TerrainCommandWriter w = layers[l];
                if (w == null || w.totalCommands() == 0) continue;
                w.copyWordsTo(hb + oWords + job.layerBase[l] * 4L);
                w.copyTo(hb + oCmd + (long) job.layerBase[l] * TerrainCommandWriter.STRIDE);
            }
            writeParams(p, f, job, tech, mode, segs, updates, oUpd, oSeg, oWords, oCmd);

            long set = this.sets[slot][this.setUse[slot]++];
            VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(6, s);
            bufInfo(s, wr, 0, set, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, h.buffer, base, PARAMS);
            bufInfo(s, wr, 1, set, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, this.meta.buffer, 0, VK_WHOLE_SIZE);
            bufInfo(s, wr, 2, set, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, h.buffer, base + PARAMS, hostBytes);
            bufInfo(s, wr, 3, set, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, job.out.buffer, 0, VK_WHOLE_SIZE);
            bufInfo(s, wr, 4, set, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, this.horizon.buffer, 0, VK_WHOLE_SIZE);
            wr.get(5).sType$Default().dstSet(set).dstBinding(5).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(this.hiz.sampler()).imageView(this.hiz.fullView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
            vkUpdateDescriptorSets(this.device, wr, null);
            this.hiz.prepare(cmd); // a new pyramid image: GENERAL before anything reads it

            // the previous frames' indirect reads / metadata and horizon reads finish before this frame writes
            barrier(s, cmd, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
            boolean horizonOn = (tech & T_HORIZON) != 0;
            if (full) {
                VkBufferCopy.Buffer c = VkBufferCopy.calloc(1, s).srcOffset(base + PARAMS + oUpd).dstOffset(0).size((long) data.size * SectionCullData.WORDS * 4);
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
            int dstStage = VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | (this.compact ? VK_PIPELINE_STAGE_TRANSFER_BIT : 0) | (flags ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : 0);
            barrier(s, cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    dstStage, VK_ACCESS_INDIRECT_COMMAND_READ_BIT | (this.compact ? VK_ACCESS_TRANSFER_READ_BIT : 0) | (flags ? VK_ACCESS_SHADER_READ_BIT : 0));
            if (this.compact && job == this.opaque) { // kept-command statistics, read after this slot's fence
                VkBufferCopy.Buffer c = VkBufferCopy.calloc(1, s).srcOffset(0).dstOffset(base + PARAMS + oRead).size((long) segs * 4);
                vkCmdCopyBuffer(cmd, job.out.buffer, h.buffer, c);
                barrier(s, cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
                job.readCnt1[slot] = base + PARAMS + oRead;
            }
            job.readSegs[slot] = segs;
            job.candidatesSlot[slot] = total;
            job.readCnt2[slot] = -1;
            this.retestHost = flags ? base + PARAMS + oRead2 : -1;
            this.retestHostBuf = flags ? h : null;
            job.set = set;
            job.slot = slot;
            job.tech = tech;
            job.mode = mode;
            job.hizGeneration = this.hiz.generation();
            job.recorded = true;
        }
        return true;
    }

    private long retestHost = -1;
    private Buf retestHostBuf;

    /**
     * P2-7c: builds the pyramid from the current depth (see {@link HiZPyramid#build}; the caller made the depth readable
     * in {@code depthLayout} with its writes visible to COMPUTE, outside a render pass).
     */
    public void buildPyramid(VkCommandBuffer cmd, int slot, long serial, long depthImage, int depthFormat, int depthLayout, int w, int h) {
        this.hiz.ensure(w, h);
        this.hiz.build(cmd, slot, serial, this.hiz.depthView(depthImage, depthFormat), depthLayout, w, h);
    }

    /** whether the last opaque {@link #record} of this frame asked for a retest (Hi-Z pass 1 ran) */
    public boolean retestPending() {
        Job j = this.opaque;
        return j.recorded && !j.retested && (j.tech & T_HIZ) != 0;
    }

    /**
     * P2-7c: the retest of the commands pass 1 culled only by Hi-Z, against the pyramid just built ({@link #buildPyramid})
     * with the current MVP. Without a pyramid of this frame (not built, or recreated since the record) every flagged
     * command is kept. Outside a render pass; then {@link #drawRetest} per opaque layer draw. False = nothing to retest.
     * {@code slot} is unused (the record's slot holds the descriptor set and staging); {@code serial} = this frame's.
     */
    public boolean recordRetest(VkCommandBuffer cmd, int slot, long serial) {
        Job job = this.opaque;
        if (!retestPending()) return false;
        boolean pyramidOk = this.hiz.builtSerial == serial && this.hiz.generation() == job.hizGeneration;
        try (MemoryStack s = MemoryStack.stackPush()) {
            barrier(s, cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pRetest);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipelineLayout, 0, s.longs(job.set), null);
            vkCmdPushConstants(cmd, this.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, s.ints(pyramidOk ? 1 : 0));
            vkCmdDispatch(cmd, job.segments, 1, 1);
            barrier(s, cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT);
            Buf hb = this.retestHostBuf;
            if (this.retestHost >= 0 && hb != null) { // counts2 + flagged per segment, read after the slot's fence
                VkBufferCopy.Buffer c = VkBufferCopy.calloc(1, s).srcOffset(job.oCnt2).dstOffset(this.retestHost).size((long) job.segments * 4);
                vkCmdCopyBuffer(cmd, job.out.buffer, hb.buffer, c);
                c.srcOffset(job.oFlg2).dstOffset(this.retestHost + (long) job.segments * 4);
                vkCmdCopyBuffer(cmd, job.out.buffer, hb.buffer, c);
                barrier(s, cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
                if (hb == this.host[job.slot]) job.readCnt2[job.slot] = this.retestHost; // else the slot's staging grew since: no statistics this frame
            }
        }
        job.retested = true;
        this.retestPyramidOk = pyramidOk;
        return true;
    }

    /** whether the last retest tested against a pyramid (false: it kept every flagged command) */
    public boolean retestPyramidOk;

    private void writeParams(long p, Frame f, Job job, int tech, int mode, int segs, int updates, long oUpd, long oSeg, long oWords, long oCmd) {
        MemoryUtil.memSet(p, 0, PARAMS);
        for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(p + i * 4L, f.mvp[i]);
        putF(p + 64, f.originRelX, f.originRelY, f.originRelZ, 0);
        putF(p + 80, f.vpX, f.vpY, f.vpW, f.vpH);
        putF(p + 96, f.camFracX, f.camFracY, f.camFracZ, 0);
        putI(p + 112, f.camSecX, f.camSecY, f.camSecZ, f.horizonRadius);
        putI(p + 128, this.gridW, this.gridH, this.gridMinY, 0);
        putI(p + 144, tech, this.compact ? 1 : 0, segs, updates);
        putI(p + 160, (int) (oUpd >> 2), (int) (oSeg >> 2), (int) (oWords >> 2), (int) (oCmd >> 2));
        putI(p + 176, (int) (job.oCmd1 >> 2), (int) (job.oCnt2 >> 2), (int) (job.oCmd2 >> 2), (int) (job.oFlags >> 2));
        for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(p + 192 + i * 4L, f.prevMvp[i]);
        putF(p + 256, f.prevShiftX, f.prevShiftY, f.prevShiftZ, 0);
        putI(p + 272, this.hiz.width0(), this.hiz.height0(), this.hiz.levels(), mode);
        putF(p + 288, f.depthW, f.depthH, HIZ_EPS, HIZ_MARGIN);
        putI(p + 304, (int) (job.oFlg2 >> 2), 0, 0, 0);
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

    private int drawJob(Job job, long cmdBase, long cntBase, VkCommandBuffer cmd, int writer, int d, TerrainCommandWriter w) {
        final int stride = TerrainCommandWriter.STRIDE;
        if (!this.compact) {
            vkCmdDrawIndexedIndirect(cmd, job.out.buffer, cmdBase + (long) (job.layerBase[writer] + w.drawFirst(d)) * stride, w.drawCount(d), stride);
            return 1;
        }
        int s0 = job.drawSegStart[writer][d], s1 = job.drawSegStart[writer][d + 1];
        for (int sg = s0; sg < s1; sg++)
            vkCmdDrawIndexedIndirectCount(cmd, job.out.buffer, cmdBase + (long) job.segFirst[sg] * stride, job.out.buffer, cntBase + sg * 4L, job.segCount[sg], stride);
        return s1 - s0;
    }

    /**
     * Draw d of opaque layer {@code layer} from the last {@link #record} (the caller has bound the pipeline, the draw's
     * vertex page and index buffer): COUNT one vkCmdDrawIndexedIndirectCount per segment, FIXED one fixed-count
     * vkCmdDrawIndexedIndirect over the writer's range. Returns the indirect calls issued.
     */
    public int drawCulled(VkCommandBuffer cmd, int layer, int d, TerrainCommandWriter w) {
        return drawJob(this.opaque, this.opaque.oCmd1, 0, cmd, layer, d, w);
    }

    /** P2-7c: draw d of opaque layer {@code layer} from the retest's output (after {@link #recordRetest}) */
    public int drawRetest(VkCommandBuffer cmd, int layer, int d, TerrainCommandWriter w) {
        return drawJob(this.opaque, this.opaque.oCmd2, this.opaque.oCnt2, cmd, layer, d, w);
    }

    /** P2-7c: draw d of the translucent writer from {@link #recordTranslucent} (the caller binds vertex page and index source per draw) */
    public int drawTranslucent(VkCommandBuffer cmd, int d, TerrainCommandWriter w) {
        return drawJob(this.translucent, this.translucent.oCmd1, 0, cmd, 0, d, w);
    }

    public int segments() {
        return this.opaque.segments;
    }

    public int commands() {
        return this.opaque.commands;
    }

    public int translucentCommands() {
        return this.translucent.commands;
    }

    /** COUNT tier: commands pass 1 kept in the slot's last frame (read 2-3 frames late), and the candidates then; -1 = unknown */
    public long keptLast() { return this.opaque.kept; }
    public long candidatesLast() { return this.opaque.candidates; }
    /** P2-7c: commands pass 1 culled only by Hi-Z, and how many of them the retest drew (read 2-3 frames late); -1 = unknown */
    public long hizFlaggedLast() { return this.opaque.hizFlagged; }
    public long hizRetestKeptLast() { return this.opaque.hizKept; }

    /** tests: the out buffer handle, its command offset (bytes) and the segment table of the last record */
    public long outBuffer() { return this.opaque.out == null ? 0 : this.opaque.out.buffer; }
    public long outCommandOffset() { return this.opaque.oCmd1; }
    public int segFirst(int i) { return this.opaque.segFirst[i]; }
    public int segCount(int i) { return this.opaque.segCount[i]; }
    public int layerBase(int l) { return this.opaque.layerBase[l]; }
    /** tests (P2-7c): offsets of the retest regions and the translucent job's out buffer */
    public long outCounts2Offset() { return this.opaque.oCnt2; }
    public long outCommands2Offset() { return this.opaque.oCmd2; }
    public long outFlagsOffset() { return this.opaque.oFlags; }
    public long outFlagged2Offset() { return this.opaque.oFlg2; }
    public long outBytes() { return this.opaque.out == null ? 0 : this.opaque.out.size; }
    public long translucentOutBuffer() { return this.translucent.out == null ? 0 : this.translucent.out.buffer; }
    public long translucentCommandOffset() { return this.translucent.oCmd1; }
    public int translucentSegments() { return this.translucent.segments; }
    public int translucentSegFirst(int i) { return this.translucent.segFirst[i]; }
    public int translucentSegCount(int i) { return this.translucent.segCount[i]; }

    public long memoryBytes() {
        long n = this.horizon.size + (this.meta == null ? 0 : this.meta.size) + this.hiz.memoryBytes();
        for (Job j : new Job[]{this.opaque, this.translucent}) if (j.out != null) n += j.out.size;
        for (Buf b : this.host) if (b != null) n += b.size;
        return n;
    }

    /** destroys everything now (the caller made sure the GPU no longer uses it, e.g. vkDeviceWaitIdle or a frame op) */
    public void destroyNow() {
        for (Buf b : new Buf[]{this.meta, this.opaque.out, this.translucent.out, this.horizon}) destroyBuf(b);
        for (int i = 0; i < this.host.length; i++) destroyBuf(this.host[i]);
        this.meta = this.horizon = null;
        this.opaque.out = this.translucent.out = null;
        java.util.Arrays.fill(this.host, null);
        this.hiz.destroyNow();
        for (long p : new long[]{this.pScatter, this.pBuild, this.pPrefix, this.pCull, this.pRetest}) vkDestroyPipeline(this.device, p, null);
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
