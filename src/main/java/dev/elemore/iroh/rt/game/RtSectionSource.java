package dev.elemore.iroh.rt.game;

import java.util.List;

/**
 * P5-2: where a built section's quads live in the RASTER arena right now (Minecraft-free). Read at BUILD time, not at
 * the section event: VulkanMod's AreaBuffer reallocates (new buffer, segments copied) between the event and the build,
 * so an address taken at the event can be stale. A BLAS, once built, holds its own copy and survives reallocation.
 */
public interface RtSectionSource {
    /** One run of quads: bucket (SectionGeometry.OPAQUE/CUTOUT/TRANSLUCENT), arena device address, byte offset, stride. */
    record Range(int bucket, long address, long byteOffset, int vertexStride, int quads) {}

    /** Live ranges of a section; empty when the section is gone or not compiled. Render thread. */
    List<Range> ranges(int sx, int sy, int sz);

    /** quads per bucket (cheap: draw parameters only), for the queue's budget; null when the section is gone */
    int[] quads(int sx, int sy, int sz);

    /** No sections (inert paths and tests). */
    RtSectionSource NONE = new RtSectionSource() {
        @Override public List<Range> ranges(int sx, int sy, int sz) { return List.of(); }
        @Override public int[] quads(int sx, int sy, int sz) { return null; }
    };
}
