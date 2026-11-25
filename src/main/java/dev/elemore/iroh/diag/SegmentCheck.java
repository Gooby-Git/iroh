package dev.elemore.iroh.diag;

import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.ChunkAreaManager;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.buffer.DrawParametersBuffer;
import net.vulkanmod.render.chunk.buffer.SectionSegments;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.vertex.TerrainRenderType;

/**
 * TERRAIN-HOLES section-integrity diagnostic, {@code -Diroh.debug.segmentCheck=true} (default off; render thread).
 * Every {@code -Diroh.debug.segmentCheckEvery} (120) section-intake calls, after the uploads, walks every allocated
 * area and checks that each drawn (section, layer, facing) slot points at a used area-buffer segment its own slot owns
 * and that is big enough for the draw (SectionSegments.checkLayer). A failure is exactly the RQ-P24-a damage: the
 * faces that slot draws come from freed or another section's memory. Logs one line per scan with a finding, and a
 * summary every 10th scan: "Iroh segcheck: scan N drawn D unowned U short S idxUnowned I (total bad B) first: ...".
 */
public final class SegmentCheck {
    public static final boolean ON = Boolean.getBoolean("iroh.debug.segmentCheck");
    private static final int EVERY = Math.max(1, Integer.getInteger("iroh.debug.segmentCheckEvery", 120));

    private static int calls;
    private static long scans, totalBad;

    private SegmentCheck() {
    }

    /** After a section-intake call (TaskDispatcher.updateSections). */
    public static void afterIntake() {
        if (++calls % EVERY != 0)
            return;
        WorldRenderer wr = WorldRenderer.getInstance();
        ChunkAreaManager m = wr == null ? null : wr.getChunkAreaManager();
        if (m == null)
            return;
        SectionSegments.Report r = new SectionSegments.Report();
        for (int a = 0; a < m.size; a++) {
            ChunkArea area = m.getChunkArea(a);
            if (area == null)
                continue;
            DrawBuffers db = area.getDrawBuffers();
            if (!db.isAllocated())
                continue;
            long base = db.getDrawParamsPtr();
            for (TerrainRenderType rt : TerrainRenderType.VALUES) {
                var vb = db.getAreaBuffer(rt);
                var ib = rt == TerrainRenderType.TRANSLUCENT ? db.getIndexBuffer() : null;
                for (int s = 0; s < ChunkAreaManager.AREA_SIZE; s++) {
                    long ptr0 = DrawParametersBuffer.getParamsPtr(base, s, rt.ordinal(), 0);
                    final int sec = s;
                    SectionSegments.checkLayer(vb, ib, ptr0, QuadFacing.COUNT, DrawBuffers.VERTEX_SIZE, r,
                            () -> "area " + db.origin().x() + "," + db.origin().y() + "," + db.origin().z() + " sec " + sec + " " + rt);
                }
            }
        }
        scans++;
        totalBad += r.bad();
        if (r.bad() > 0 || scans % 10 == 1)
            Initializer.LOGGER.info("Iroh segcheck: scan {} drawn {} unowned {} short {} idxUnowned {} (total bad {}){}",
                    scans, r.drawn, r.unowned, r.shortSeg, r.indexUnowned, totalBad,
                    r.firstCount > 0 ? " first: " + r.first : "");
    }
}
