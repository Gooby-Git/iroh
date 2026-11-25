package net.vulkanmod.render.chunk.cull;

/**
 * P2-7b (L5b): the CPU mirror of the GPU cull's section metadata, one uvec4 per section-grid slot
 * (RenderSection.gridIndex): x = flags, y/z/w = CullBounds box of the opaque layers SOLID, CUTOUT_MIPPED, CUTOUT
 * (TerrainRenderType ordinals 0-2). Written on the render thread when a section's compile result is applied
 * (CompileResult.updateSection), when a layer is uploaded into the world arena (DrawBuffers.uploadWorld) and when a
 * section is reset; uploaded to the device copy by TerrainCull (dirty slots, or the whole mirror after a reset of the
 * device copy). Exists while the world arena exists on a GPU-cull tier (WorldRenderer.allChanged), so turning a
 * technique on needs no chunk reload.
 *
 * <p>Flags: bit 0 = compiled, bit 1 = opaque (no pair of distinct faces connected by non-opaque blocks:
 * VisibilitySet bits (from << 3) + to with from != to all clear; any straight segment entering through one face and
 * leaving through another crosses an opaque block), bits 8-19 = section x & 0xFFF, bits 20-31 = section z & 0xFFF (the
 * horizon pass checks a grid slot still holds the column it looks up). Pure Java, tested by terrainCullTest.
 */
public final class SectionCullData {
    public static final int F_COMPILED = 1, F_OPAQUE = 2;
    public static final int OPAQUE_LAYERS = 3;
    /** VisibilitySet pairs of distinct faces (bit (from << 3) + to, from != to, both < 6) */
    public static final long OFF_DIAGONAL;

    static {
        long m = 0;
        for (int a = 0; a < 6; a++) for (int b = 0; b < 6; b++) if (a != b) m |= 1L << ((a << 3) + b);
        OFF_DIAGONAL = m;
    }

    private static volatile SectionCullData current;

    public final int width, height, minSecY, size;
    final int[] words;
    // dirty slots (deduplicated by the bit set), or everything when the device copy must be rewritten
    private final long[] dirtyBits;
    private int[] dirty = new int[256];
    private int dirtyCount;
    private boolean full = true;

    public SectionCullData(int gridWidth, int gridHeight, int minSecY) {
        this.width = gridWidth;
        this.height = gridHeight;
        this.minSecY = minSecY;
        this.size = gridWidth * gridHeight * gridWidth;
        this.words = new int[this.size * 4];
        this.dirtyBits = new long[(this.size + 63) >> 6];
    }

    public static SectionCullData current() {
        return current;
    }

    public static void setCurrent(SectionCullData d) {
        current = d;
    }

    /** SectionGrid.getSectionAtSectionPos's slot for absolute section coordinates (y in sections) */
    public int gridIndex(int sx, int sy, int sz) {
        return (Math.floorMod(sz, this.width) * this.height + (sy - this.minSecY)) * this.width + Math.floorMod(sx, this.width);
    }

    public static int flags(boolean compiled, boolean opaque, int sx, int sz) {
        return (compiled ? F_COMPILED : 0) | (opaque ? F_OPAQUE : 0) | (sx & 0xFFF) << 8 | (sz & 0xFFF) << 20;
    }

    public static boolean opaque(long visibility) {
        return (visibility & OFF_DIAGONAL) == 0;
    }

    /** CompileResult.updateSection: a compiled section's flags (section coordinates) */
    public void sectionCompiled(int slot, int sx, int sz, long visibility, boolean completelyEmpty) {
        set(slot, 0, flags(true, !completelyEmpty && opaque(visibility), sx, sz));
    }

    /** a layer's geometry box after an upload into the world arena (layer = TerrainRenderType ordinal; others ignored) */
    public void layerBounds(int slot, int layer, int packed) {
        if (layer < OPAQUE_LAYERS) set(slot, 1 + layer, packed);
    }

    /** the section left its slot or was reset: nothing compiled, no geometry */
    public void sectionReset(int slot) {
        for (int i = 0; i < 4; i++) set(slot, i, 0);
    }

    public int word(int slot, int i) {
        return this.words[slot * 4 + i];
    }

    private void set(int slot, int i, int v) {
        if (slot < 0 || slot >= this.size) return;
        int k = slot * 4 + i;
        if (this.words[k] == v) return;
        this.words[k] = v;
        if (this.full) return;
        long bit = 1L << (slot & 63);
        if ((this.dirtyBits[slot >> 6] & bit) != 0) return;
        this.dirtyBits[slot >> 6] |= bit;
        if (this.dirtyCount == this.dirty.length)
            this.dirty = java.util.Arrays.copyOf(this.dirty, this.dirtyCount * 2);
        this.dirty[this.dirtyCount++] = slot;
        if (this.dirtyCount > (this.size >> 3) + 64) // a large share (20 B per record vs 16 B per slot): rewrite the device copy
            markFull();
    }

    /** the device copy is (re)created: the next upload writes every slot */
    public void markFull() {
        this.full = true;
        java.util.Arrays.fill(this.dirtyBits, 0);
        this.dirtyCount = 0;
    }

    public boolean fullPending() {
        return this.full;
    }

    public int dirtyCount() {
        return this.dirtyCount;
    }

    /** writes the whole mirror (size * 16 bytes) to {@code dst} and clears the pending state */
    public void drainFull(long dst) {
        org.lwjgl.system.MemoryUtil.memIntBuffer(dst, this.words.length).put(this.words);
        this.full = false;
        this.dirtyCount = 0;
    }

    /**
     * writes the dirty slots as update records {slot, x, y, z, w} (5 uints each, 20 bytes) to {@code dst}, returns the
     * count and clears the pending state
     */
    public int drainDirty(long dst) {
        int n = this.dirtyCount;
        long p = dst;
        for (int j = 0; j < n; j++, p += 20) {
            int slot = this.dirty[j];
            this.dirtyBits[slot >> 6] &= ~(1L << (slot & 63));
            int k = slot * 4;
            org.lwjgl.system.MemoryUtil.memPutInt(p, slot);
            org.lwjgl.system.MemoryUtil.memPutInt(p + 4, this.words[k]);
            org.lwjgl.system.MemoryUtil.memPutInt(p + 8, this.words[k + 1]);
            org.lwjgl.system.MemoryUtil.memPutInt(p + 12, this.words[k + 2]);
            org.lwjgl.system.MemoryUtil.memPutInt(p + 16, this.words[k + 3]);
        }
        this.dirtyCount = 0;
        return n;
    }
}
