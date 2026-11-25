package net.vulkanmod.render.chunk.buffer;

/**
 * TERRAIN-HOLES: who owns the area-buffer segments a section's draw parameters point at. Pure (AreaBuffer +
 * DrawParametersBuffer only) so StreamingTest runs it on the fake device.
 *
 * <p>One draw-parameter slot per (section, layer, facing); {@link AreaBuffer#upload} records the slot address as the
 * segment's owner. A layer free therefore frees a segment only when the slot still owns it: the old
 * {@code RenderSection.resetDrawParameters} passed a BYTE offset to {@code setSegmentFree} (which takes elements), so it
 * leaked its own segment and, when another section's live segment started at offset * elementSize, freed THAT one at
 * once; the next upload then overwrote the victim's vertices while its draw parameters still pointed there (RQ-P24-a).
 */
public final class SectionSegments {
    private SectionSegments() {
    }

    /** -Diroh.area.legacyLayerFree=true: the pre-fix byte-offset free, ONLY to reproduce RQ-P24-a in game (A/B). */
    public static final boolean LEGACY_LAYER_FREE = Boolean.getBoolean("iroh.area.legacyLayerFree");

    /**
     * Frees (after the frames in flight) every segment of one layer of one section that the layer's slots own: the
     * vertex segment of each facing, and with {@code index} non-null the sorted translucent index segment of the
     * UNDEFINED facing. {@code facing0Ptr} is the slot of facing 0; the facings follow at
     * {@link DrawParametersBuffer#STRIDE}. Does not reset the parameters. Returns the number of frees queued.
     */
    public static int freeLayer(AreaBuffer vertex, AreaBuffer index, long facing0Ptr, int facings) {
        int n = 0;
        for (int i = 0; i < facings; i++) {
            long ptr = facing0Ptr + i * DrawParametersBuffer.STRIDE;
            int vertexOffset = DrawParametersBuffer.getVertexOffset(ptr);
            if (vertex != null && vertexOffset != -1) {
                if (LEGACY_LAYER_FREE)
                    vertex.setSegmentFree(vertexOffset * vertex.elementSize()); // the bug, on purpose
                else if (vertex.freeOwnedDeferred(vertexOffset, ptr))
                    n++;
            }
            if (index != null && !LEGACY_LAYER_FREE && i == facings - 1 && DrawParametersBuffer.getIndexCount(ptr) > 0
                    && index.freeOwnedDeferred(DrawParametersBuffer.getFirstIndex(ptr), ptr))
                n++;
        }
        return n;
    }

    /** What one integrity check found. */
    public static final class Report {
        /** drawn facings checked (indexCount > 0) */
        public long drawn;
        /** drawn facings whose vertex segment is not owned by their slot (freed, or another section's) */
        public long unowned;
        /** owned, but smaller than the vertices the draw reads */
        public long shortSeg;
        /** drawn translucent facings whose index segment is not owned by their slot (sequential indices, or lost) */
        public long indexUnowned;
        public final StringBuilder first = new StringBuilder();
        public int firstCount;

        void note(String what) {
            if (this.firstCount++ < 5)
                this.first.append(this.first.length() == 0 ? "" : "; ").append(what);
        }

        public long bad() {
            return this.unowned + this.shortSeg;
        }
    }

    /**
     * Checks the slots of one layer of one section: every facing that draws (indexCount > 0) must point at a used
     * segment its own slot owns, at least as large as the vertices the draw reads. {@code vertexSize} in bytes.
     */
    public static void checkLayer(AreaBuffer vertex, AreaBuffer index, long facing0Ptr, int facings, int vertexSize,
                                  Report r, java.util.function.Supplier<String> where) {
        for (int i = 0; i < facings; i++) {
            long ptr = facing0Ptr + i * DrawParametersBuffer.STRIDE;
            int indexCount = DrawParametersBuffer.getIndexCount(ptr);
            if (indexCount <= 0)
                continue;
            r.drawn++;
            int vertexOffset = DrawParametersBuffer.getVertexOffset(ptr);
            long owner = vertex == null || vertexOffset < 0 ? 0L : vertex.ownerAt(vertexOffset);
            if (owner != ptr) {
                r.unowned++;
                r.note(where.get() + " facing " + i + " vertexOffset " + vertexOffset + (owner == 0L ? " not in use" : " owned by another slot"));
            } else {
                long need = (long) indexCount / 6 * 4 * vertexSize;
                if (vertex.usedSizeAt(vertexOffset) < need) {
                    r.shortSeg++;
                    r.note(where.get() + " facing " + i + " segment " + vertex.usedSizeAt(vertexOffset) + " B < " + need + " B drawn");
                }
            }
            if (index != null && i == facings - 1 && index.ownerAt(DrawParametersBuffer.getFirstIndex(ptr)) != ptr)
                r.indexUnowned++;
        }
    }
}
