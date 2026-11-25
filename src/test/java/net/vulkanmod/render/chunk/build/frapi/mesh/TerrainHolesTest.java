package net.vulkanmod.render.chunk.build.frapi.mesh;

import dev.elemore.iroh.api.ShadeHook;
import dev.elemore.iroh.apiimpl.ShadeHooks;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.chunk.build.frapi.helper.NormalHelper;
import org.joml.Vector3f;

/**
 * TERRAIN-HOLES offline: the per-facing bucket of a terrain quad must follow the quad's FINAL geometry, or
 * QuadFacing.facingMask drops a face the camera can see (see-through block sides). {@code ./gradlew terrainHolesTest};
 * last line "# terrainholes PASS n/n". Uses the real FRAPI quad (MutableQuadViewImpl / QuadViewImpl) and the real
 * ShadeHooks save/restore, in the call order of AbstractBlockRenderContext.shadeQuad -> BlockRenderer.bufferQuad.
 */
public final class TerrainHolesTest {
    private static int pass, fail;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    static MutableQuadViewImpl editor() {
        return new MutableQuadViewImpl() {
            {
                data = new int[EncodingFormat.TOTAL_STRIDE];
                clear();
            }

            @Override
            public void emitDirectly() {
            }
        };
    }

    /** the top face of the block at the section origin (y = 1), wound like a vanilla UP face (normal +y) */
    static void topFace(MutableQuadViewImpl q) {
        q.pos(0, 0f, 1f, 0f);
        q.pos(1, 0f, 1f, 1f);
        q.pos(2, 1f, 1f, 1f);
        q.pos(3, 1f, 1f, 0f);
    }

    /** true when the quad's front side faces the camera (counter-clockwise front, as the raster path culls) */
    static boolean frontFacing(MutableQuadViewImpl q, double cx, double cy, double cz) {
        Vector3f n = new Vector3f();
        NormalHelper.computeFaceNormal(n, q);
        return n.x * (cx - q.x(0)) + n.y * (cy - q.y(0)) + n.z * (cz - q.z(0)) > 1e-6;
    }

    public static void main(String[] args) {
        // the FRAPI encoding pulls in BlendMode -> RenderType -> Items: vanilla registries must exist (no game, no GPU)
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        // ---- 1. Polaris slope: SlopeTransform sags the downhill (+x) corners; the shade hook lifts them back flat for
        // the light calculation; Iroh restores the positions; bufferQuad then reads getQuadFacing().
        MutableQuadViewImpl q = editor();
        topFace(q);
        q.lightFace(); // the mesher computes geometry before bucketing (shadeQuad); the old code relied on that
        check("flat top face: bucket Y_POS", q.getQuadFacing() == QuadFacing.Y_POS);
        q.pos(2, 1f, 0.55f, 1f);   // sag 0.45 (Polaris max_drop is capped at 0.49)
        q.pos(3, 1f, 0.55f, 0f);
        q.lightFace();             // anything that reads geometry after the transform (as shadeQuad does)
        ShadeHook lift = (state, pos, quad) -> {
            boolean moved = false;
            for (int i = 0; i < 4; i++)
                if (quad.y(i) < 1f - 1e-4f && quad.y(i) >= 1f - 0.49f - 1e-3f) {
                    quad.pos(i, quad.x(i), 1f, quad.z(i));
                    moved = true;
                }
            return moved;
        };
        float[] saved = new float[12];
        boolean restore = ShadeHooks.before(new ShadeHook[]{lift}, null, null, q, saved);
        q.lightFace();             // lightPipeline.calculate(quad, ..., quad.lightFace(), ...) sees the lifted quad
        boolean liftedFlat = q.faceNormal().y > 0.999f;
        if (restore) ShadeHooks.restore(q, saved);
        boolean restored = q.y(2) == 0.55f && q.y(3) == 0.55f;
        QuadFacing bucket = q.getQuadFacing(); // BlockRenderer.bufferQuad: first line
        Vector3f real = new Vector3f();
        NormalHelper.computeFaceNormal(real, q);
        QuadFacing truth = QuadFacing.fromNormal(real);
        check("slope: lighting saw the flat quad (" + liftedFlat + "), positions restored (" + restored + ")", restore && liftedFlat && restored);
        check("slope: bucket follows the written (tilted) geometry: " + bucket + " (geometry says " + truth + ")", bucket == truth && truth == QuadFacing.UNDEFINED);

        // the camera that loses the face: below the section floor (secY = 0), downhill, far enough to see the slope
        double cx = 9.0, cy = -0.5, cz = 0.5;
        int mask = QuadFacing.facingMask(cx, cy, cz, 0, 0, 0);
        boolean visible = frontFacing(q, cx, cy, cz);
        boolean drawnFixed = (mask & 1 << bucket.ordinal()) != 0;
        boolean drawnStale = (mask & 1 << QuadFacing.Y_POS.ordinal()) != 0;
        check("slope seen from below the section floor is front-facing (" + visible + ") and drawn (" + drawnFixed
                + "); the stale Y_POS bucket would drop it (" + !drawnStale + ")", visible && drawnFixed && !drawnStale);

        // ---- 2. copyFrom: the copied quad keeps the source's bucket, not the editor's previous quad's
        MutableQuadViewImpl src = editor();
        src.pos(0, 0f, 0f, 0f);    // a north face (normal -z) at z = 0
        src.pos(1, 0f, 1f, 0f);
        src.pos(2, 1f, 1f, 0f);
        src.pos(3, 1f, 0f, 0f);
        src.lightFace();
        QuadFacing srcFacing = src.getQuadFacing();
        MutableQuadViewImpl e = editor();
        topFace(e);
        e.lightFace();             // the editor last held an UP face
        e.copyFrom(src);           // Continuity's emissive overlay: copyFrom + emit, positions untouched
        check("copyFrom: bucket of the copied quad " + e.getQuadFacing() + " = source " + srcFacing,
                srcFacing == QuadFacing.Z_NEG && e.getQuadFacing() == srcFacing);

        // ---- 3. no change for quads nobody moved: a flat side face keeps its axis bucket
        MutableQuadViewImpl side = editor();
        side.pos(0, 1f, 0f, 1f);   // east face (normal +x) at x = 1
        side.pos(1, 1f, 0f, 0f);
        side.pos(2, 1f, 1f, 0f);
        side.pos(3, 1f, 1f, 1f);
        side.lightFace();
        check("untouched east face: bucket X_POS", side.getQuadFacing() == QuadFacing.X_POS);

        // ---- 4. RQ-HOLES-b: a result built for a section's old position is not uploaded after the section was relocated
        var sec = new net.vulkanmod.render.chunk.RenderSection(0, 0, 64, 0);
        var res = net.vulkanmod.render.chunk.build.task.CompileResult.forOrigin(sec, true, 0, 64, 0);
        check("HOLES-b: result of an unmoved section is current", !res.originStale());
        sec.setOrigin(256, 64, 0); // SectionGrid.moveSection: the slot now stands at the far edge
        check("HOLES-b: result built before the relocation is stale (dropped, not uploaded into the new slot)", res.originStale());
        var res2 = net.vulkanmod.render.chunk.build.task.CompileResult.forOrigin(sec, true, 256, 64, 0);
        check("HOLES-b: result built after the relocation is current", !res2.originStale());

        // ---- 5. RQ-HOLES-c: area rows cover the top section for every dimension height
        int[][] dims = {{24, -64}, {16, 0}, {24, 0}, {10, 0}, {18, 0}, {26, 0}, {10, 16}, {34, 0}, {8, 0}, {9, 0}, {48, -64}, {10, -32}};
        boolean allCovered = true, oldFails = false;
        for (int[] d : dims) {
            int n = d[0], minY = d[1];
            int need = ((n - 1) * 16 >> 7) + 1; // getChunkArea: row (y - minY) >> 7 of the top section
            int rows = net.vulkanmod.render.chunk.ChunkAreaManager.areaRows(n, minY);
            int relativeHeight = n - (minY >> 4);
            int old = (relativeHeight & 0x5) == 0 ? relativeHeight >> 3 : (relativeHeight >> 3) + 1;
            if (rows < need) { allCovered = false; System.out.println("   rows " + rows + " < " + need + " for " + n + " sections, minY " + minY); }
            if (old < need) { oldFails = true; System.out.println("   (old formula: " + old + " rows < " + need + " for " + n + " sections, minY " + minY + ")"); }
        }
        check("HOLES-c: areaRows covers the top section row in every table entry", allCovered);
        check("HOLES-c: the old formula under-allocated in at least one entry (repro)", oldFails);

        System.out.println("# terrainholes " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail));
        if (fail != 0) System.exit(1);
    }
}
