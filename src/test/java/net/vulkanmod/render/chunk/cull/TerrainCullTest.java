package net.vulkanmod.render.chunk.cull;

import net.vulkanmod.render.chunk.buffer.TerrainCommandWriter;
import net.vulkanmod.vulkan.device.IndirectDrawCaps;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * P2-7b: the CPU side of the GPU terrain cull, no device: CullBounds (pack, scan of compressed vertices, union,
 * overflow), SectionCullData (grid slot formula = SectionGrid.getSectionAtSectionPos, the opaque rule on VisibilitySet
 * bits, dirty tracking / full upload / records), TerrainCommandWriter cull words (same layout as the commands, grouped
 * runs included) and the cull tier decision. ./gradlew terrainCullTest ; last line "# terraincull PASS|FAIL n/n".
 */
public final class TerrainCullTest {
    static int pass, fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    /** a compressed vertex buffer with the given section-local positions (CompressedVertexBuilder: s = (x - 4) * 2048) */
    static ByteBuffer verts(float[][] pos) {
        ByteBuffer b = MemoryUtil.memAlloc(pos.length * 16);
        for (int i = 0; i < pos.length; i++) {
            long p = MemoryUtil.memAddress(b) + i * 16L;
            for (int a = 0; a < 3; a++) MemoryUtil.memPutShort(p + a * 2L, (short) Math.round((pos[i][a] - 4f) * 2048f));
        }
        return b;
    }

    public static void main(String[] args) {
        // ---- CullBounds
        int box = CullBounds.pack(0, 1, 2, 16, 15, 14);
        int[] u = CullBounds.unpack(box);
        check("pack/unpack round trip", u[0] == 0 && u[1] == 1 && u[2] == 2 && u[3] == 16 && u[4] == 15 && u[5] == 14 && (box & CullBounds.PRESENT) != 0);
        check("pack range limits: -8..23 lo, -7..24 hi in range, -9 overflow", (CullBounds.pack(-8, -8, -8, 24, 24, 24) & CullBounds.OVERFLOW) == 0
                && (CullBounds.pack(-9, 0, 0, 1, 1, 1) & CullBounds.OVERFLOW) != 0 && (CullBounds.pack(0, 0, 0, 25, 1, 1) & CullBounds.OVERFLOW) != 0);
        int[] whole = CullBounds.unpack(0);
        check("absent box = the compressed format's whole range", whole[0] == -12 && whole[5] == 20);
        ByteBuffer a = verts(new float[][]{{0.5f, 3f, 7.25f}, {15.99f, 3f, 8f}, {2f, 12.5f, 0f}});
        int s = CullBounds.scan(new ByteBuffer[]{a, null});
        int[] sb = CullBounds.unpack(s);
        check("scan: floor(min) / ceil(max) of the vertices (" + java.util.Arrays.toString(sb) + ")",
                sb[0] == 0 && sb[1] == 3 && sb[2] == 0 && sb[3] == 16 && sb[4] == 13 && sb[5] == 8);
        ByteBuffer b2 = verts(new float[][]{{-1f, -0.5f, 16f}, {17f, 16f, 17f}}); // a model reaching one block outside the section
        int s2 = CullBounds.scan(new ByteBuffer[]{b2});
        int[] s2b = CullBounds.unpack(s2);
        check("scan: geometry outside the section is kept (" + java.util.Arrays.toString(s2b) + ")", s2b[0] == -1 && s2b[1] == -1 && s2b[3] == 17 && s2b[5] == 17);
        int un = CullBounds.union(s, s2);
        int[] ub = CullBounds.unpack(un);
        check("union", ub[0] == -1 && ub[1] == -1 && ub[2] == 0 && ub[3] == 17 && ub[4] == 16 && ub[5] == 17 && CullBounds.union(0, s) == s);
        ByteBuffer far = verts(new float[][]{{-11.9f, 0f, 0f}});
        check("scan: beyond the packable range = overflow (whole range in the shader)", (CullBounds.scan(new ByteBuffer[]{far}) & CullBounds.OVERFLOW) != 0);
        check("scan: no vertices = 0", CullBounds.scan(new ByteBuffer[]{null}) == 0);
        // random: every vertex inside the unpacked box
        Random r = new Random(7);
        boolean inside = true;
        for (int t = 0; t < 200; t++) {
            float[][] pos = new float[1 + r.nextInt(20)][3];
            for (float[] p : pos) for (int k = 0; k < 3; k++) p[k] = -7.9f + r.nextFloat() * 27.8f; // the int16 format holds -12..20
            ByteBuffer v = verts(pos);
            int[] bb = CullBounds.unpack(CullBounds.scan(new ByteBuffer[]{v}));
            for (float[] p : pos) for (int k = 0; k < 3; k++) inside &= p[k] >= bb[k] - 1e-3f && p[k] <= bb[k + 3] + 1e-3f;
            MemoryUtil.memFree(v);
        }
        check("random vertex sets: every vertex inside its box", inside);
        MemoryUtil.memFree(a);
        MemoryUtil.memFree(b2);
        MemoryUtil.memFree(far);

        // ---- SectionCullData
        SectionCullData d = new SectionCullData(65, 24, -4);
        boolean formula = true;
        for (int t = 0; t < 1000; t++) {
            int sx = r.nextInt(2000) - 1000, sy = -4 + r.nextInt(24), sz = r.nextInt(2000) - 1000;
            int expect = (Math.floorMod(sz, 65) * 24 + (sy + 4)) * 65 + Math.floorMod(sx, 65); // SectionGrid.getChunkIndex(floorMod...)
            formula &= d.gridIndex(sx, sy, sz) == expect;
        }
        check("grid slot = SectionGrid.getSectionAtSectionPos's index", formula);
        long diag = 0;
        for (int f = 0; f < 6; f++) diag |= 1L << ((f << 3) + f);
        check("opaque rule: no pairs or only face-to-itself pairs = opaque; any pair of distinct faces = not; setAll = not",
                SectionCullData.opaque(0) && SectionCullData.opaque(diag) && !SectionCullData.opaque(1L << ((2 << 3) + 3)) && !SectionCullData.opaque(-1L));
        // dirty tracking
        check("new mirror: full upload pending, no dirty list", d.fullPending() && d.dirtyCount() == 0);
        long buf = MemoryUtil.nmemAlloc((long) d.size * SectionCullData.WORDS * 4);
        d.drainFull(buf);
        check("after the full upload nothing is pending", !d.fullPending() && d.dirtyCount() == 0);
        d.sectionCompiled(5, 3, -2, 0L, false);
        d.layerBounds(5, 1, box);
        d.layerBounds(5, 4, box); // tripwire is not culled: ignored
        d.layerBounds(9, 0, box);
        d.layerBounds(9, SectionCullData.TRANSLUCENT_LAYER, box + 1); // P2-7c: the translucent box, word 4
        check("two slots dirty (deduplicated)", d.dirtyCount() == 2);
        int n = d.drainDirty(buf);
        int R = SectionCullData.RECORD_BYTES;
        boolean rec = n == 2 && R == 36 && MemoryUtil.memGetInt(buf) == 5 && MemoryUtil.memGetInt(buf + 4) == SectionCullData.flags(true, true, 3, -2)
                && MemoryUtil.memGetInt(buf + 12) == box && MemoryUtil.memGetInt(buf + 20) == 0
                && MemoryUtil.memGetInt(buf + R) == 9 && MemoryUtil.memGetInt(buf + R + 8) == box && MemoryUtil.memGetInt(buf + R + 4 + 16) == box + 1;
        check("update records {slot, flags, solid, cutout_mipped, cutout, translucent, 0, 0, 0} (36 B; tripwire ignored)", rec);
        int fl = d.word(5, 0);
        check("flags: compiled + opaque + x/z low 12 bits", (fl & 3) == 3 && (fl >>> 8 & 0xFFF) == 3 && (fl >>> 20) == ((-2) & 0xFFF));
        d.sectionCompiled(5, 3, -2, 1L << ((0 << 3) + 1), false);
        check("a connected pair clears the opaque bit", (d.word(5, 0) & SectionCullData.F_OPAQUE) == 0);
        d.sectionCompiled(6, 3, -2, 0L, true);
        check("completely empty is never opaque", (d.word(6, 0) & SectionCullData.F_OPAQUE) == 0 && (d.word(6, 0) & SectionCullData.F_COMPILED) != 0);
        d.sectionReset(5);
        d.sectionReset(9);
        check("reset clears every word", d.word(5, 0) == 0 && d.word(5, 2) == 0 && d.word(9, SectionCullData.TRANSLUCENT_WORD) == 0);
        d.drainDirty(buf);
        for (int i = 0; i < d.size / 8 + 300; i++) d.layerBounds(i, 0, box + (i & 1));
        check("a large share of dirty slots turns into one full upload", d.fullPending() && d.dirtyCount() == 0);
        MemoryUtil.nmemFree(buf);
        check("unchanged value does not mark dirty", unchangedNotDirty());

        // ---- TerrainCommandWriter cull words follow the command layout (grouped by page)
        check("cull words: same layout as the commands, slot | facing << 28", wordsFollowCommands(r));

        // ---- tier
        check("cull tier: indirect + drawIndirectCount = COUNT, indirect only = FIXED, direct = CPU",
                IndirectDrawCaps.decideCull(IndirectDrawCaps.TerrainTier.INDIRECT, true) == IndirectDrawCaps.CullTier.COUNT
                        && IndirectDrawCaps.decideCull(IndirectDrawCaps.TerrainTier.INDIRECT, false) == IndirectDrawCaps.CullTier.FIXED
                        && IndirectDrawCaps.decideCull(IndirectDrawCaps.TerrainTier.DIRECT, true) == IndirectDrawCaps.CullTier.CPU);

        // RQ-P27b-b: the CPU facing mask must keep a +x face whose plane lies one block outside the section (x = secX - 1)
        {
            int secX = 0, secY = 0, secZ = 0;
            double camX = -0.5; // between the plane at x = -1 and the section origin: that +X face (normal +x) faces the camera
            int oldStyle = (camX - secX >= 0) ? 1 : 0; // the old predicate for X_POS
            int fbox = CullBounds.pack(-1, 0, 0, 16, 16, 16);
            int viaBox = QuadFacing.facingMaskBox(camX, 8, 8, secX, secY, secZ, fbox);
            int plain = QuadFacing.facingMask(camX, 8, 8, secX, secY, secZ);
            int xpos = 1 << QuadFacing.X_POS.ordinal();
            check("facing mask: old predicate drops X_POS for a plane at x=-1 with the camera at x=-0.5 (repro)", oldStyle == 0);
            check("facing mask: box (-1..16) keeps X_POS", (viaBox & xpos) != 0);
            check("facing mask: box-less default (-1..17) keeps X_POS", (plain & xpos) != 0);
            // a tight box still culls: planes all at x >= 4, camera at x = 2 -> no +x face can face it
            int tight = QuadFacing.facingMaskBox(2, 8, 8, 0, 0, 0, CullBounds.pack(4, 0, 0, 12, 16, 16));
            check("facing mask: tight box (4..12) with camera x=2 drops X_POS, keeps X_NEG", (tight & xpos) == 0 && (tight & 1 << QuadFacing.X_NEG.ordinal()) != 0);
            // far -x side: plane at x = 17, camera at 17.5 -> X_NEG (normal -x) faces away; box hi=17 keeps nothing wrong, box hi=16 would also drop
            int hiOut = QuadFacing.facingMaskBox(16.5, 8, 8, 0, 0, 0, CullBounds.pack(0, 0, 0, 17, 16, 16));
            check("facing mask: X_NEG kept for a plane at x=17 with camera x=16.5", (hiOut & 1 << QuadFacing.X_NEG.ordinal()) != 0);
            check("facing mask: no box / overflow word = whole compressed range", QuadFacing.facingMaskBox(-11, 8, 8, 0, 0, 0, 0) == QuadFacing.facingMaskBox(-11, 8, 8, 0, 0, 0, CullBounds.PRESENT | CullBounds.OVERFLOW)
                    && (QuadFacing.facingMaskBox(-11, 8, 8, 0, 0, 0, 0) & xpos) != 0);
        }

        System.out.println("# terraincull " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }

    static boolean unchangedNotDirty() {
        SectionCullData d = new SectionCullData(5, 4, 0);
        long buf = MemoryUtil.nmemAlloc((long) d.size * SectionCullData.WORDS * 4);
        d.drainFull(buf);
        d.layerBounds(3, 0, 0);
        boolean ok = d.dirtyCount() == 0;
        MemoryUtil.nmemFree(buf);
        return ok;
    }

    /** areas of random sections over 3 vertex pages; the writer groups by page; words must move with their commands */
    static boolean wordsFollowCommands(Random r) {
        TerrainCommandWriter w = new TerrainCommandWriter();
        int sections = 64;
        long params = MemoryUtil.nmemCalloc(1, (long) sections * 7 * 16);
        for (int sct = 0; sct < sections; sct++)
            for (int f = 0; f < 7; f++) {
                long p = params + ((long) sct * 7 + f) * 16;
                if (r.nextInt(3) == 0) continue;
                MemoryUtil.memPutInt(p, 6 * (1 + r.nextInt(50)));     // indexCount
                MemoryUtil.memPutInt(p + 8, sct * 1000 + f);          // vertexOffset: identifies (section, facing)
                MemoryUtil.memPutInt(p + 12, 1 + r.nextInt(3));       // page + 1
            }
        int[] sec = new int[sections], mask = new int[sections], fi = new int[sections], slot = new int[sections];
        for (int j = 0; j < sections; j++) { sec[j] = j; mask[j] = 0x7F; fi[j] = j; slot[j] = 1000 + j; }
        byte[] iPage = new byte[sections];
        java.util.Arrays.fill(iPage, (byte) -1);
        w.beginCull(0, 0, 0, -1);
        w.addAreaCull(params, 32, sec, mask, fi, slot, iPage, 0);
        int[] sec2 = java.util.Arrays.copyOfRange(sec, 32, 64), m2 = java.util.Arrays.copyOfRange(mask, 32, 64), f2 = java.util.Arrays.copyOfRange(fi, 32, 64), s2 = java.util.Arrays.copyOfRange(slot, 32, 64);
        w.addAreaCull(params, 32, sec2, m2, f2, s2, iPage, 0);
        w.finish();
        int n = w.totalCommands();
        long cmds = MemoryUtil.nmemAlloc((long) n * 20), words = MemoryUtil.nmemAlloc((long) n * 4);
        w.copyTo(cmds);
        w.copyWordsTo(words);
        boolean ok = n > 0 && w.draws() > 1;
        for (int i = 0; i < n; i++) {
            int vo = MemoryUtil.memGetInt(cmds + i * 20L + 12), word = MemoryUtil.memGetInt(words + i * 4L);
            int s = vo / 1000, f = vo % 1000;
            ok &= (word & TerrainCommandWriter.WORD_SLOT_MASK) == 1000 + s && (word >>> TerrainCommandWriter.WORD_FACING_SHIFT) == f
                    && MemoryUtil.memGetInt(cmds + i * 20L + 16) == s;
        }
        MemoryUtil.nmemFree(cmds);
        MemoryUtil.nmemFree(words);
        MemoryUtil.nmemFree(params);
        w.free();
        return ok;
    }
}
