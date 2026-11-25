package net.vulkanmod.render.chunk.buffer;

import org.lwjgl.system.MemoryUtil;

/**
 * P2-7a (L5a): the frame's terrain draw commands of ONE layer from the world arena, CPU-written. Commands are
 * VkDrawIndexedIndirectCommand records (20 bytes, packed), grouped into draws: one vertex page (+ index source) and a
 * contiguous command range each, so the render thread issues one bind + one vkCmdDrawIndexedIndirect per page instead
 * of a bind + push constant + UBO bind + vkCmdDrawIndexedIndirect per chunk area.
 *
 * <p>Writing is one sequential pass into one scratch (two 8-byte loads and three stores per command; a key change only
 * opens a run), then {@link #finish} groups. Order. Unordered layers (opaque, alpha-tested): the runs are grouped by key
 * (vertex page, index source) with one copy per run, skipped when the layer uses one key; inside a key the per-area
 * path's order is kept, across pages the depth test decides (ties only on exactly coplanar faces of different
 * sections). Ordered layers (translucent, tripwire: blended) keep the exact command order of the per-area path (areas
 * and sections back to front): every run is a draw, so blending is unchanged. DrawBuffers allocates an area's sections
 * in one page where it can, so runs are long.
 *
 * <p>firstInstance = the section's position relative to a per-frame origin section, 8 signed bits per axis in SECTION
 * units (x | z << 8 | y << 16), decoded by terrain_world.vsh (x16 blocks). The per-area path encodes block offsets
 * inside the 8x8x8-section area instead. Range: +-127 sections around the origin (render distance up to 126, world
 * height up to 2032 blocks); sections outside are skipped and counted ({@link #outOfRange}).
 *
 * <p>Pure Java + LWJGL MemoryUtil (offline: WorldArenaTest, TerrainWorldReplay); render thread in game.
 */
public final class TerrainCommandWriter {
    public static final int STRIDE = 20;
    /** index key of the shared auto quad index buffer (sections without sorted indices) */
    public static final int QUADS = 255;

    /**
     * DrawParametersBuffer layout (kept equal by DrawBuffers' static check): stride 16, indexCount, firstIndex,
     * vertexOffset, and at +12 (the per-area path's baseInstance) the world arena vertex PAGE + 1 (0 = none).
     */
    public static final int PARAM_STRIDE = 16, PARAM_FACINGS = 7, PARAM_PAGE_OFFSET = 12;
    private static final boolean LE = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN;

    private boolean ordered;
    private int ox, oy, oz;
    private int maxDraws = Integer.MAX_VALUE;
    /** sections skipped this layer because their offset does not fit 8 signed bits (expected 0) */
    public int outOfRange;
    /** entries with indexCount > 0 but no vertex page (expected 0: a reset that did not reach the parameters) */
    public int orphans;

    // the layer's commands in arrival order; runs of equal key (index key << 8 | vertex page)
    private long cmd;
    private int cap, n;
    private int[] runStart = new int[64], runKey = new int[64];
    private int runs, lastKey;
    // unordered with several keys: destination of each run in the grouped layout (stable by key)
    private int[] runDst = new int[64];
    private boolean grouped;
    private final int[] keyCount = new int[1 << 16], keyStart = new int[1 << 16];
    private int[] touched = new int[16];
    // tests: the final layout materialized for drawCommands()
    private long sorted;
    private int sortedCap;
    private boolean materialized;

    // draws (finish)
    private int[] drawFirst = new int[16], drawCount = new int[16], drawKey = new int[16];
    private int draws;

    /** start a layer: origin section (x, y, z), ordered = keep exact command order (blended layers), the device's maxDrawIndirectCount (raw uint32) */
    public void begin(boolean ordered, int originX, int originY, int originZ, int maxDrawCount) {
        this.n = 0;
        this.runs = 0;
        this.lastKey = -1;
        this.draws = 0;
        this.outOfRange = 0;
        this.orphans = 0;
        this.ordered = ordered;
        this.ox = originX;
        this.oy = originY;
        this.oz = originZ;
        // maxDrawIndirectCount is a uint32 (RADV, lavapipe: 0xFFFFFFFF, i.e. -1 as an int): <= 0 means no practical limit
        this.maxDraws = maxDrawCount <= 0 ? Integer.MAX_VALUE : maxDrawCount;
    }

    /** firstInstance for the section at section coordinates (x, y, z), or -1 when it does not fit */
    public int encode(int sx, int sy, int sz) {
        int dx = sx - this.ox, dy = sy - this.oy, dz = sz - this.oz;
        if (((dx + 128) | (dy + 128) | (dz + 128)) >>> 8 != 0) {
            this.outOfRange++;
            return -1;
        }
        return (dx & 0xFF) | (dz & 0xFF) << 8 | (dy & 0xFF) << 16;
    }

    /** the inverse (tests): {x, y, z} offsets in sections, as terrain_world.vsh decodes them (signed 8-bit extract) */
    public static int[] decode(int firstInstance) {
        return new int[]{(byte) firstInstance, (byte) (firstInstance >> 16), (byte) (firstInstance >> 8)};
    }

    /**
     * The section's facings of one layer: {@code params} = its 7 DrawParametersBuffer entries (vertex page + 1 at +12),
     * {@code mask} = facing buckets to draw (bit i = facing i), {@code indexKey} = index page of its sorted indices or
     * {@link #QUADS}. Same skips as DrawBuffers.buildDrawBatchesIndirect. Two 8-byte loads and three stores per command
     * (the per-area loop: four loads, five stores): {indexCount, firstIndex} and {vertexOffset, page} are adjacent in the
     * entry, {firstIndex, vertexOffset} adjacent in the command.
     */
    public void addSection(long params, int mask, int indexKey, int firstInstance) {
        if (!LE) {
            addSectionPortable(params, mask, indexKey, firstInstance);
            return;
        }
        if (this.n + PARAM_FACINGS > this.cap) grow(this.n + PARAM_FACINGS);
        // locals only in the loop (raw native stores may alias fields for the JIT); kept small so the JIT inlines it
        int k = this.n, lastKey = this.lastKey;
        long q = this.cmd + (long) k * STRIDE;
        final int ik = indexKey << 8;
        long p = params;
        for (int i = 0; i < PARAM_FACINGS; i++, p += PARAM_STRIDE) {
            if ((mask & 1 << i) == 0) continue;
            long a = MemoryUtil.memGetLong(p);          // indexCount | firstIndex << 32
            if ((int) a <= 0) continue;
            long b = MemoryUtil.memGetLong(p + 8);      // vertexOffset | (page + 1) << 32
            int page = (int) (b >>> 32) - 1;
            if (page < 0) { this.orphans++; continue; }
            // 4-byte stores: a 20-byte record puts 8-byte stores across cache lines (measured 2x slower, WorldArenaTest)
            MemoryUtil.memPutInt(q, (int) a);
            MemoryUtil.memPutInt(q + 4, 1);
            MemoryUtil.memPutInt(q + 8, (int) (a >>> 32));
            MemoryUtil.memPutInt(q + 12, (int) b);
            MemoryUtil.memPutInt(q + 16, firstInstance);
            int key = ik | page;
            if (key != lastKey) lastKey = newRun(k, key);
            k++;
            q += STRIDE;
        }
        this.n = k;
    }

    /**
     * One chunk area's sections at once (DrawBuffers.writeWorldCommands): {@code layerParams} = the area's parameter
     * block of the layer, section j at entry {@code sec[j]} with facing mask {@code mask[j]}, firstInstance {@code fi[j]}
     * and index page {@code iPage[iPageBase + sec[j]]} (NONE = the quad indices). The same work as {@link #addSection}
     * per section, but the writer's state stays in registers across the area (a call per section cost about as much as
     * the command stores; WorldArenaTest "command writing").
     */
    public void addArea(long layerParams, int count, int[] sec, int[] mask, int[] fi, byte[] iPage, int iPageBase) {
        if (!LE) {
            for (int j = 0; j < count; j++) {
                byte ip = iPage[iPageBase + sec[j]];
                addSectionPortable(layerParams + (long) sec[j] * PARAM_FACINGS * PARAM_STRIDE, mask[j], ip < 0 ? QUADS : ip, fi[j]);
            }
            return;
        }
        if (this.n + count * PARAM_FACINGS > this.cap) grow(this.n + count * PARAM_FACINGS);
        int k = this.n, lastKey = this.lastKey;
        final long cmd = this.cmd;
        for (int j = 0; j < count; j++) {
            final int s = sec[j], m = mask[j], firstInstance = fi[j];
            final byte ip = iPage[iPageBase + s];
            final int ik = (ip < 0 ? QUADS : ip) << 8;
            long p = layerParams + (long) s * (PARAM_FACINGS * PARAM_STRIDE);
            for (int i = 0; i < PARAM_FACINGS; i++, p += PARAM_STRIDE) {
                if ((m & 1 << i) == 0) continue;
                long a = MemoryUtil.memGetLong(p);          // indexCount | firstIndex << 32
                if ((int) a <= 0) continue;
                long b = MemoryUtil.memGetLong(p + 8);      // vertexOffset | (page + 1) << 32
                int page = (int) (b >>> 32) - 1;
                if (page < 0) { this.orphans++; continue; }
                long q = cmd + (long) k * STRIDE;
                MemoryUtil.memPutInt(q, (int) a);
                MemoryUtil.memPutInt(q + 4, 1);
                MemoryUtil.memPutInt(q + 8, (int) (a >>> 32));
                MemoryUtil.memPutInt(q + 12, (int) b);
                MemoryUtil.memPutInt(q + 16, firstInstance);
                int key = ik | page;
                if (key != lastKey) lastKey = newRun(k, key);
                k++;
            }
        }
        this.n = k;
    }

    /** big-endian hosts: the same with 4-byte accesses */
    private void addSectionPortable(long params, int mask, int indexKey, int firstInstance) {
        long p = params;
        for (int i = 0; i < PARAM_FACINGS; i++, p += PARAM_STRIDE) {
            if ((mask & 1 << i) == 0) continue;
            int indexCount = MemoryUtil.memGetInt(p);
            if (indexCount <= 0) continue;
            int page = MemoryUtil.memGetInt(p + PARAM_PAGE_OFFSET) - 1;
            if (page < 0) { this.orphans++; continue; }
            put(page, indexKey, indexCount, MemoryUtil.memGetInt(p + 4), MemoryUtil.memGetInt(p + 8), firstInstance);
        }
    }

    /** one command (tests and the replay's controls) */
    public void put(int vPage, int indexKey, int indexCount, int firstIndex, int vertexOffset, int firstInstance) {
        if (this.n + 1 > this.cap) grow(this.n + 1);
        long q = this.cmd + (long) this.n * STRIDE;
        MemoryUtil.memPutInt(q, indexCount);
        MemoryUtil.memPutInt(q + 4, 1);
        MemoryUtil.memPutInt(q + 8, firstIndex);
        MemoryUtil.memPutInt(q + 12, vertexOffset);
        MemoryUtil.memPutInt(q + 16, firstInstance);
        int key = indexKey << 8 | vPage;
        if (key != this.lastKey) newRun(this.n, key);
        this.n++;
    }

    private int newRun(int start, int key) {
        if (this.runs == this.runStart.length) {
            this.runStart = java.util.Arrays.copyOf(this.runStart, this.runs * 2);
            this.runKey = java.util.Arrays.copyOf(this.runKey, this.runs * 2);
        }
        this.runStart[this.runs] = start;
        this.runKey[this.runs++] = key;
        return this.lastKey = key;
    }

    private void grow(int need) {
        int c = Math.max(need, Math.max(4096, this.cap * 2));
        this.cmd = this.cmd == 0 ? MemoryUtil.nmemAlloc((long) c * STRIDE) : MemoryUtil.nmemRealloc(this.cmd, (long) c * STRIDE);
        if (this.cmd == 0) throw new OutOfMemoryError("terrain command scratch " + c);
        this.cap = c;
    }

    private int runEnd(int r) {
        return r + 1 < this.runs ? this.runStart[r + 1] : this.n;
    }

    /**
     * Close the layer: the draw list (one per key, or per run when ordered; split at maxDrawCount commands) over the
     * FINAL command layout, which {@link #copyTo} writes (grouped by key when unordered with several keys, else the
     * arrival order). Draw d covers commands {@link #drawFirst}(d) .. + {@link #drawCount}(d) of that layout.
     */
    public void finish() {
        this.draws = 0;
        this.grouped = false;
        this.materialized = false;
        if (this.n == 0) return;
        if (this.ordered) {
            for (int r = 0; r < this.runs; r++) addDraws(this.runStart[r], runEnd(r) - this.runStart[r], this.runKey[r]);
            return;
        }
        // unordered: commands per key (first-seen order); one draw if the layer uses one key, else group the runs
        final int[] count = this.keyCount;
        int t = 0;
        for (int r = 0; r < this.runs; r++) {
            int k = this.runKey[r];
            if (count[k] == 0) {
                if (t == this.touched.length) this.touched = java.util.Arrays.copyOf(this.touched, t * 2);
                this.touched[t++] = k;
            }
            count[k] += runEnd(r) - this.runStart[r];
        }
        if (t == 1) {
            count[this.touched[0]] = 0;
            addDraws(0, this.n, this.touched[0]);
            return;
        }
        final int[] start = this.keyStart;
        int pos = 0;
        for (int j = 0; j < t; j++) {
            int k = this.touched[j];
            start[k] = pos;
            addDraws(pos, count[k], k);
            pos += count[k];
            count[k] = 0;
        }
        if (this.runDst.length < this.runs) this.runDst = new int[Math.max(this.runs, this.runDst.length * 2)];
        for (int r = 0; r < this.runs; r++) {
            int k = this.runKey[r];
            this.runDst[r] = start[k];
            start[k] += runEnd(r) - this.runStart[r];
        }
        this.grouped = true;
    }

    /**
     * Writes the layer's {@link #totalCommands} commands in the final layout to {@code dst} (the frame's mapped indirect
     * buffer in game: one pass, no intermediate copy).
     */
    public void copyTo(long dst) {
        if (this.n == 0) return;
        if (!this.grouped) {
            MemoryUtil.memCopy(this.cmd, dst, (long) this.n * STRIDE);
            return;
        }
        for (int r = 0; r < this.runs; r++) {
            int len = runEnd(r) - this.runStart[r];
            long src = this.cmd + (long) this.runStart[r] * STRIDE, d = dst + (long) this.runDst[r] * STRIDE;
            if (len >= 8) MemoryUtil.memCopy(src, d, (long) len * STRIDE);
            else for (int i = 0; i < len; i++, src += STRIDE, d += STRIDE) { // short runs: a call per run costs more than the copy
                MemoryUtil.memPutLong(d, MemoryUtil.memGetLong(src));
                MemoryUtil.memPutLong(d + 8, MemoryUtil.memGetLong(src + 8));
                MemoryUtil.memPutInt(d + 16, MemoryUtil.memGetInt(src + 16));
            }
        }
    }

    private void addDraws(int first, int count, int key) {
        while (count > 0) {
            int c = Math.min(count, this.maxDraws);
            if (this.draws == this.drawFirst.length) {
                int m = this.draws * 2;
                this.drawFirst = java.util.Arrays.copyOf(this.drawFirst, m);
                this.drawCount = java.util.Arrays.copyOf(this.drawCount, m);
                this.drawKey = java.util.Arrays.copyOf(this.drawKey, m);
            }
            this.drawFirst[this.draws] = first;
            this.drawCount[this.draws] = c;
            this.drawKey[this.draws] = key;
            this.draws++;
            first += c;
            count -= c;
        }
    }

    public int draws() {
        return this.draws;
    }

    public int totalCommands() {
        return this.n;
    }

    public int drawVertexPage(int d) {
        return this.drawKey[d] & 0xFF;
    }

    public int drawIndexKey(int d) {
        return this.drawKey[d] >>> 8;
    }

    public int drawCount(int d) {
        return this.drawCount[d];
    }

    /** draw d's first command in the final layout ({@link #copyTo}) */
    public int drawFirst(int d) {
        return this.drawFirst[d];
    }

    /** tests: address of draw d's first command in a private copy of the final layout */
    public long drawCommands(int d) {
        if (!this.grouped) return this.cmd + (long) this.drawFirst[d] * STRIDE;
        if (!this.materialized) {
            if (this.sortedCap < this.n) {
                this.sorted = this.sorted == 0 ? MemoryUtil.nmemAlloc((long) this.n * STRIDE) : MemoryUtil.nmemRealloc(this.sorted, (long) this.n * STRIDE);
                this.sortedCap = this.n;
            }
            copyTo(this.sorted);
            this.materialized = true;
        }
        return this.sorted + (long) this.drawFirst[d] * STRIDE;
    }

    /** native scratch back to the allocator (renderer close / tests) */
    public void free() {
        if (this.cmd != 0) MemoryUtil.nmemFree(this.cmd);
        if (this.sorted != 0) MemoryUtil.nmemFree(this.sorted);
        this.cmd = this.sorted = 0;
        this.cap = this.sortedCap = this.n = this.draws = this.runs = 0;
    }
}
