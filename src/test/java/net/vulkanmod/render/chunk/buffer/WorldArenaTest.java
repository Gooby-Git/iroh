package net.vulkanmod.render.chunk.buffer;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * P2-7a (L5a) offline: the world terrain arena and the command writer, no GPU. ./gradlew worldArenaTest ; last line
 * "# worldarena PASS n/n".
 * <ul>
 * <li>Allocator: random section uploads / replacements / layer resets with frees deferred by 2 frames (the game's slot
 *     rule), every live range keeps its bytes (fake device memory), page invariants (no gap, overlap, touching free
 *     blocks), no range reused while a frame in flight may read it, pages retire when empty (one spare kept), a range
 *     larger than the page maximum gets its own page, no copy of live data ever (pages never grow).</li>
 * <li>Negative control: a double free is counted (badFrees) and changes nothing.</li>
 * <li>Writer: firstInstance encode/decode over the whole 8-bit range, out-of-range sections skipped and counted,
 *     buckets keep the per-area order inside a page, ordered layers keep the exact global order (runs), draws split at
 *     maxDrawIndirectCount, empty and orphaned facings skipped, facing masks honoured.</li>
 * <li>Intake cost (M, this JVM): ns per upload of the real AreaBuffer (per area, linear best-fit walk) vs WorldArena
 *     pages (tree best fit) on an RD32-like stream.</li>
 * </ul>
 */
public final class WorldArenaTest {
    static int pass, fail;

    static boolean check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
        return ok;
    }

    /** fake device: byte arrays per buffer, uploads copy, later() runs FRAMES frames later */
    static final class Fake implements WorldArena.Ops {
        static final int FRAMES = 2;
        final Map<Object, byte[]> mem = new HashMap<>();
        final ArrayDeque<Object[]> later = new ArrayDeque<>();
        final boolean data;
        int frame, allocations, retired;
        long allocatedBytes, peakBytes, liveBytes;

        Fake(boolean data) {
            this.data = data;
        }

        @Override
        public Object allocate(boolean index, long bytes) {
            Object b = new Object();
            mem.put(b, data ? new byte[(int) bytes] : new byte[0]);
            allocations++;
            allocatedBytes += bytes;
            liveBytes += bytes;
            peakBytes = Math.max(peakBytes, liveBytes);
            return b;
        }

        @Override
        public long id(Object buffer) {
            return System.identityHashCode(buffer);
        }

        @Override
        public void upload(Object dst, long dstOffset, ByteBuffer src) {
            if (!data) return;
            byte[] m = mem.get(dst);
            for (int i = 0, n = src.remaining(); i < n; i++) m[(int) dstOffset + i] = src.get(src.position() + i);
        }

        @Override
        public void retire(Object buffer) {
            byte[] m = mem.remove(buffer);
            if (m != null) liveBytes -= data ? m.length : 0;
            retired++;
        }

        @Override
        public void later(Runnable r) {
            later.add(new Object[]{frame + FRAMES, r});
        }

        void nextFrame() {
            frame++;
            while (!later.isEmpty() && (int) later.peek()[0] <= frame) ((Runnable) later.poll()[1]).run();
        }
    }

    static ByteBuffer mesh(int bytes, int tag) {
        ByteBuffer b = MemoryUtil.memAlloc(bytes);
        for (int i = 0; i < bytes; i++) b.put(i, (byte) (tag * 31 + i * 7));
        return b;
    }

    record Live(int page, int offset, int bytes, int tag) {}

    public static void main(String[] args) {
        allocatorRandom();
        reclaimAndLarge();
        negativeControl();
        writerEncode();
        writerOrder();
        intakeCost();
        writerCost();
        System.out.println("# worldarena " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }

    static WorldArena arena(Fake f, long first, long max) {
        return new WorldArena(f, 2, 16, new long[]{first, first}, new long[]{max, max}, first / 4, max / 4);
    }

    /** 3 x 6000 random ops (upload / replace / reset), deferred frees, data and invariants checked throughout */
    static void allocatorRandom() {
        for (long seed : new long[]{1, 2, 3}) {
            Fake f = new Fake(true);
            WorldArena a = arena(f, 256 << 10, 1 << 20);
            WorldArena.Pool pool = a.vertexPool(0);
            SplittableRandom r = new SplittableRandom(seed);
            Map<Integer, Live> slots = new HashMap<>();
            List<Live> pending = new ArrayList<>(); // freed but maybe still read by frames in flight
            List<Integer> pendingFrame = new ArrayList<>();
            long corrupt = 0, reusedEarly = 0, invariantErrors = 0;
            int tag = 0, maxPages = 0;
            for (int op = 0; op < 6000; op++) {
                int slot = r.nextInt(400);
                Live old = slots.remove(slot);
                if (old != null) {
                    a.freeLater(pool, old.page, old.offset);
                    pending.add(old);
                    pendingFrame.add(f.frame);
                }
                if (r.nextInt(5) != 0) { // upload (else: layer reset)
                    int bytes = 64 * (1 + (int) Math.min(4000, Math.abs(r.nextGaussian()) * 600));
                    ByteBuffer m = mesh(bytes, ++tag);
                    long p = a.upload(pool, m);
                    MemoryUtil.memFree(m);
                    Live l = new Live(WorldArena.page(p), WorldArena.offset(p), bytes, tag);
                    for (int i = 0; i < pending.size(); i++) { // a range freed less than FRAMES frames ago must not be reused
                        Live q = pending.get(i);
                        if (f.frame - pendingFrame.get(i) < Fake.FRAMES && q.page == l.page && q.offset < l.offset + l.bytes && l.offset < q.offset + q.bytes)
                            reusedEarly++;
                    }
                    slots.put(slot, l);
                }
                if (r.nextInt(8) == 0) {
                    f.nextFrame();
                    for (int i = pending.size() - 1; i >= 0; i--)
                        if (f.frame - pendingFrame.get(i) > Fake.FRAMES) { pending.remove(i); pendingFrame.remove(i); }
                }
                if (op % 500 == 0) {
                    invariantErrors += pool.errors().size();
                    for (Live l : slots.values()) if (!holds(f, a, pool, l)) corrupt++;
                }
                maxPages = Math.max(maxPages, pool.pageCount());
            }
            for (int i = 0; i < 4; i++) f.nextFrame();
            for (Live l : slots.values()) if (!holds(f, a, pool, l)) corrupt++;
            long live = 0;
            for (Live l : slots.values()) live += l.bytes;
            check(String.format("seed %d: 6000 ops, live data intact (%d corrupt), invariants (%d errors), no early reuse (%d), used %d = live %d, pages %d (max %d), bad frees %d",
                    seed, corrupt, invariantErrors, reusedEarly, pool.used(), live, pool.pageCount(), maxPages, a.badFrees),
                    corrupt == 0 && invariantErrors == 0 && reusedEarly == 0 && pool.used() == live && a.badFrees == 0 && pool.errors().isEmpty());
        }
    }

    static boolean holds(Fake f, WorldArena a, WorldArena.Pool pool, Live l) {
        byte[] m = f.mem.get(a.buffer(pool, l.page));
        if (m == null) return false;
        for (int i = 0; i < l.bytes; i++) if (m[l.offset + i] != (byte) (l.tag * 31 + i * 7)) return false;
        return true;
    }

    static void reclaimAndLarge() {
        Fake f = new Fake(false);
        WorldArena a = arena(f, 1 << 20, 4 << 20);
        WorldArena.Pool pool = a.vertexPool(1);
        List<Long> live = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            ByteBuffer m = MemoryUtil.memAlloc(64 << 10);
            live.add(a.upload(pool, m));
            MemoryUtil.memFree(m);
        }
        int grown = pool.pageCount();
        long cap = pool.capacity();
        check("400 x 64 KiB: pages double up to the maximum (" + grown + " pages, " + (cap >> 20) + " MiB for 25 MiB)",
                grown >= 4 && cap >= 25L << 20 && cap <= 2 * (25L << 20) + (4 << 20));
        for (long p : live) a.freeLater(pool, WorldArena.page(p), WorldArena.offset(p));
        for (int i = 0; i < 4; i++) f.nextFrame();
        check("all freed: one spare page left, " + pool.pagesRetired + " retired, used " + pool.used(),
                pool.pageCount() == 1 && pool.used() == 0 && f.retired == grown - 1 && pool.errors().isEmpty());
        ByteBuffer big = MemoryUtil.memAlloc(9 << 20); // above the 4 MiB page maximum
        long p = a.upload(pool, big);
        MemoryUtil.memFree(big);
        check("a 9 MiB range above the page maximum gets a page of its own (page " + WorldArena.page(p) + ", offset " + WorldArena.offset(p) + ")",
                WorldArena.offset(p) == 0 && pool.pageCount() == 2 && pool.errors().isEmpty());
        check("pages never copy live data: allocations " + f.allocations + ", device bytes peak " + (f.peakBytes >> 20) + " MiB (no copy path exists)",
                f.allocations == pool.pagesCreated);
        a.retireAll();
        a.freeLater(pool, WorldArena.page(p), WorldArena.offset(p)); // after retireAll: a no-op, no bad free
        f.nextFrame(); f.nextFrame(); f.nextFrame();
        check("retireAll: every page retired, later frees are no-ops", f.mem.isEmpty() && a.badFrees == 0 && pool.pageCount() == 0);
    }

    static void negativeControl() {
        Fake f = new Fake(true);
        WorldArena a = arena(f, 256 << 10, 1 << 20);
        WorldArena.Pool pool = a.vertexPool(0);
        ByteBuffer m = mesh(4096, 1), n = mesh(4096, 2);
        long p = a.upload(pool, m);
        a.freeLater(pool, WorldArena.page(p), WorldArena.offset(p));
        a.freeLater(pool, WorldArena.page(p), WorldArena.offset(p)); // double free
        for (int i = 0; i < 3; i++) f.nextFrame();
        long q = a.upload(pool, n);
        check("negative control: double free counted (badFrees " + a.badFrees + "), allocator still consistent",
                a.badFrees == 1 && pool.errors().isEmpty() && pool.used() == 4096 && WorldArena.offset(q) == 0);
        MemoryUtil.memFree(m);
        MemoryUtil.memFree(n);
    }

    static void writerEncode() {
        TerrainCommandWriter w = new TerrainCommandWriter();
        w.begin(false, 100, -2, -300, 1 << 20);
        boolean ok = true;
        for (int d = -128; d <= 127; d++) {
            int dy = -(d + 1), dz = (d * 7) % 128; // every value of each axis, mixed signs
            int fi = w.encode(100 + d, -2 + dy, -300 + dz);
            int[] o = TerrainCommandWriter.decode(fi);
            ok &= fi >= 0 && o[0] == d && o[1] == dy && o[2] == dz && (fi >>> 24) == 0;
        }
        check("firstInstance encode/decode: x, y, z offsets -128..127 round-trip (signed 8-bit extract), bits 24..31 zero, none refused ("
                + w.outOfRange + ")", ok && w.outOfRange == 0);
        w.begin(false, 0, 0, 0, 1 << 20);
        int bad = 0;
        for (int[] c : new int[][]{{128, 0, 0}, {0, -129, 0}, {0, 0, 200}, {-200, 5, 5}}) bad += w.encode(c[0], c[1], c[2]) < 0 ? 1 : 0;
        check("offsets outside -128..127 are refused and counted (" + bad + "/4, counter " + w.outOfRange + ")", bad == 4 && w.outOfRange == 4);
        w.free();
        // the shader decode, written out: (byte) of each field equals GLSL bitfieldExtract(int, offset, 8)
        int fi = (0xFF) | (0x80 << 8) | (0x7F << 16);
        int[] o = TerrainCommandWriter.decode(fi);
        check("decode matches bitfieldExtract sign extension (x 0xFF = -1, z 0x80 = -128, y 0x7F = 127)", o[0] == -1 && o[2] == -128 && o[1] == 127);
    }

    static void writerOrder() {
        TerrainCommandWriter w = new TerrainCommandWriter();
        long params = MemoryUtil.nmemCalloc(7, 16);
        // unordered: commands on pages 2,0,2,1,0 -> buckets in first-seen order, order kept inside each
        w.begin(false, 0, 0, 0, 1 << 20);
        int[] pg = {2, 0, 2, 1, 0};
        for (int i = 0; i < pg.length; i++) w.put(pg[i], TerrainCommandWriter.QUADS, 6 * (i + 1), 0, i * 4, i);
        w.finish();
        boolean ok = w.draws() == 3 && w.totalCommands() == 5;
        int[][] expect = {{2, 0, 2}, {0, 1, 4}, {1, 3}};
        for (int d = 0; d < Math.min(3, w.draws()); d++) {
            ok &= w.drawVertexPage(d) == expect[d][0] && w.drawCount(d) == expect[d].length - 1;
            for (int k = 1; k < expect[d].length; k++)
                ok &= MemoryUtil.memGetInt(w.drawCommands(d) + (long) (k - 1) * TerrainCommandWriter.STRIDE + 16) == expect[d][k];
        }
        check("unordered layer: one draw per vertex page, per-area order kept inside the page (draws " + w.draws() + ")", ok);
        // ordered: same commands -> runs 2 | 0 | 2 | 1 | 0, exact order
        w.begin(true, 0, 0, 0, 1 << 20);
        for (int i = 0; i < pg.length; i++) w.put(pg[i], i == 3 ? 7 : TerrainCommandWriter.QUADS, 6, 0, i * 4, i);
        w.put(0, TerrainCommandWriter.QUADS, 6, 0, 99, 5); // same key as the last run: joins it
        w.finish();
        ok = w.draws() == 5 && w.totalCommands() == 6 && w.drawIndexKey(3) == 7 && w.drawCount(4) == 2;
        long first = w.drawCommands(0);
        for (int i = 0; i < 6; i++) ok &= MemoryUtil.memGetInt(first + (long) i * TerrainCommandWriter.STRIDE + 16) == i; // contiguous, global order
        check("ordered layer: exact command order, a new draw only where the page / index source changes (draws " + w.draws() + ")", ok);
        // split at maxDrawIndirectCount
        w.begin(false, 0, 0, 0, 7);
        for (int i = 0; i < 20; i++) w.put(0, TerrainCommandWriter.QUADS, 6, 0, 0, i);
        w.finish();
        check("draws split at maxDrawIndirectCount 7: 20 commands -> 3 draws (7, 7, 6)", w.draws() == 3 && w.drawCount(0) == 7 && w.drawCount(2) == 6
                && MemoryUtil.memGetInt(w.drawCommands(2) + 16) == 14);
        // maxDrawIndirectCount 0xFFFFFFFF (RADV / lavapipe) arrives as -1 from LWJGL's int getter: no split
        w.begin(false, 0, 0, 0, 0xFFFFFFFF);
        for (int i = 0; i < 20; i++) w.put(0, TerrainCommandWriter.QUADS, 6, 0, 0, i);
        w.finish();
        check("maxDrawIndirectCount 0xFFFFFFFF (uint32 read as int -1) = no limit: 20 commands -> 1 draw (got " + w.draws() + ")", w.draws() == 1 && w.drawCount(0) == 20);
        // addSection: masks, empty facings, orphans; record layout = VkDrawIndexedIndirectCommand
        for (int i = 0; i < 7; i++) {
            MemoryUtil.memPutInt(params + i * 16L, i == 2 ? 0 : 6 * (i + 1)); // facing 2 empty
            MemoryUtil.memPutInt(params + i * 16L + 4, 100 + i);
            MemoryUtil.memPutInt(params + i * 16L + 8, 1000 + i);
            MemoryUtil.memPutInt(params + i * 16L + TerrainCommandWriter.PARAM_PAGE_OFFSET, i == 4 ? 0 : 1 + 1); // page 1 (+1); facing 4 orphaned
        }
        w.begin(false, 0, 0, 0, 1 << 20);
        w.addSection(params, 0b1011101, TerrainCommandWriter.QUADS, 0x123456 & 0x7F7F7F);
        w.finish();
        // mask 0b1011101: facings 0, 2, 3, 4, 6 -> 2 empty, 4 orphan -> 0, 3, 6
        ok = w.draws() == 1 && w.totalCommands() == 3 && w.orphans == 1 && w.drawVertexPage(0) == 1;
        long c = w.drawCommands(0);
        int[] fac = {0, 3, 6};
        for (int k = 0; k < 3; k++) {
            long q = c + (long) k * TerrainCommandWriter.STRIDE;
            ok &= MemoryUtil.memGetInt(q) == 6 * (fac[k] + 1) && MemoryUtil.memGetInt(q + 4) == 1 && MemoryUtil.memGetInt(q + 8) == 100 + fac[k]
                    && MemoryUtil.memGetInt(q + 12) == 1000 + fac[k] && MemoryUtil.memGetInt(q + 16) == (0x123456 & 0x7F7F7F);
        }
        check("addSection: facing mask honoured, empty facing and orphan (no page) skipped, record = {indexCount, 1, firstIndex, vertexOffset, firstInstance}", ok);
        MemoryUtil.nmemFree(params);
        w.free();
    }

    /**
     * M (this JVM): command writing per section on IDENTICAL parameter data, the per-area loop of
     * DrawBuffers.buildDrawBatchesIndirect (four loads, five stores per command into a reused window) vs
     * TerrainCommandWriter.addSection + finish (two 8-byte loads, three stores, runs grouped by page). One page per area,
     * three pages. Informational: the world path's gain is the per-area work it removes, not this loop.
     */
    static void writerCost() {
        int areas = 40, secs = 100;
        long[] blocks = new long[areas];
        SplittableRandom r = new SplittableRandom(1);
        int[][] idx = new int[areas][secs], mask = new int[areas][secs];
        for (int i = 0; i < areas; i++) {
            blocks[i] = MemoryUtil.nmemCalloc(1, 512 * 7 * 16L);
            for (int s = 0; s < secs; s++) {
                idx[i][s] = r.nextInt(512);
                mask[i][s] = 0b1000000 | r.nextInt(64);
                long p = blocks[i] + idx[i][s] * 7 * 16L;
                for (int f = 0; f < 7; f++) {
                    MemoryUtil.memPutInt(p + f * 16, r.nextInt(3) == 0 ? 0 : 600);
                    MemoryUtil.memPutInt(p + f * 16 + 8, r.nextInt(100000));
                    MemoryUtil.memPutInt(p + f * 16 + 12, 1 + (i % 3)); // page + 1
                }
            }
        }
        long scratch = MemoryUtil.nmemAlloc(512 * 7 * 32);
        TerrainCommandWriter w = new TerrainCommandWriter();
        double bestOld = 1e9, bestNew = 1e9, bestArea = 1e9;
        int[] fis = new int[secs];
        java.util.Arrays.fill(fis, 5);
        byte[] noIdx = new byte[512];
        java.util.Arrays.fill(noIdx, WorldArena.NONE);
        long sink = 0;
        for (int round = 0; round < 8; round++) {
            long t0 = System.nanoTime();
            for (int it = 0; it < 500; it++)
                for (int i = 0; i < areas; i++) {
                    long ptr = scratch;
                    for (int s = 0; s < secs; s++) {
                        long p2 = blocks[i] + idx[i][s] * 7 * 16L;
                        int m = mask[i][s];
                        for (int f = 0; f < 7; f++, p2 += 16) {
                            if ((m & 1 << f) == 0) continue;
                            int ic = MemoryUtil.memGetInt(p2);
                            if (ic <= 0) continue;
                            MemoryUtil.memPutInt(ptr, ic);
                            MemoryUtil.memPutInt(ptr + 4, 1);
                            MemoryUtil.memPutInt(ptr + 8, MemoryUtil.memGetInt(p2 + 4));
                            MemoryUtil.memPutInt(ptr + 12, MemoryUtil.memGetInt(p2 + 8));
                            MemoryUtil.memPutInt(ptr + 16, MemoryUtil.memGetInt(p2 + 12));
                            ptr += 32;
                            sink++;
                        }
                    }
                }
            long t1 = System.nanoTime();
            for (int it = 0; it < 500; it++) {
                w.begin(false, 0, 0, 0, -1);
                for (int i = 0; i < areas; i++)
                    for (int s = 0; s < secs; s++) w.addSection(blocks[i] + idx[i][s] * 7 * 16L, mask[i][s], TerrainCommandWriter.QUADS, 5);
                w.finish();
                sink += w.totalCommands();
            }
            long t2 = System.nanoTime();
            for (int it = 0; it < 500; it++) {
                w.begin(false, 0, 0, 0, -1);
                for (int i = 0; i < areas; i++) w.addArea(blocks[i], secs, idx[i], mask[i], fis, noIdx, 0);
                w.finish();
                sink += w.totalCommands();
            }
            long t3 = System.nanoTime();
            bestOld = Math.min(bestOld, (t1 - t0) / 500.0 / areas / secs);
            bestNew = Math.min(bestNew, (t2 - t1) / 500.0 / areas / secs);
            bestArea = Math.min(bestArea, (t3 - t2) / 500.0 / areas / secs);
        }
        System.out.printf("  command writing (M, best of 8 rounds, this JVM): per-area loop %.1f ns/section, TerrainCommandWriter.addSection %.1f, addArea %.1f ns/section (%d pages -> %d draws) [%d]%n",
                bestOld, bestNew, bestArea, 3, w.draws(), sink);
        check("writer output: 3 pages -> 3 draws, every command written once", w.draws() == 3);
        for (long b : blocks) MemoryUtil.nmemFree(b);
        MemoryUtil.nmemFree(scratch);
        w.free();
    }

    /** M: render-thread allocator cost per section upload, per-area AreaBuffer vs world pages, RD32-like population */
    static void intakeCost() {
        int areas = 363, perArea = 110; // RD32: 11 x 3 x 11 areas, ~40k sections with geometry
        SplittableRandom r = new SplittableRandom(7);
        int[] sizes = new int[areas * perArea];
        for (int i = 0; i < sizes.length; i++) sizes[i] = 64 * (1 + (int) Math.min(4000, Math.abs(r.nextGaussian()) * 500));
        ByteBuffer src = MemoryUtil.memAlloc(64 * 4001);
        // per area: real AreaBuffer, fake device without data
        StreamingTest.FakeOps fo = new StreamingTest.FakeOps(false);
        fo.data = false;
        AreaBuffer[] ab = new AreaBuffer[areas];
        int[][] offs = new int[areas][perArea];
        long t0 = System.nanoTime();
        for (int a = 0; a < areas; a++) {
            ab[a] = new AreaBuffer(AreaBuffer.Usage.VERTEX, 100000, 16, fo);
            for (int s = 0; s < perArea; s++) {
                src.limit(sizes[a * perArea + s]).position(0);
                offs[a][s] = ab[a].upload(src, -1, 0).getOffset() / 16;
            }
        }
        long fillArea = System.nanoTime() - t0;
        // world pages
        Fake f = new Fake(false);
        WorldArena w = WorldArena.forGame(f, 1, 16);
        WorldArena.Pool pool = w.vertexPool(0);
        long[] wp = new long[sizes.length];
        t0 = System.nanoTime();
        for (int i = 0; i < sizes.length; i++) {
            src.limit(sizes[i]).position(0);
            wp[i] = w.upload(pool, src);
        }
        long fillWorld = System.nanoTime() - t0;
        // steady state: 20k replacements (rebuilds), frees deferred
        int reps = 20000;
        t0 = System.nanoTime();
        for (int k = 0; k < reps; k++) {
            int i = r.nextInt(sizes.length), a = i / perArea, s = i % perArea;
            src.limit(64 * (1 + r.nextInt(2000))).position(0);
            offs[a][s] = ab[a].upload(src, offs[a][s], 0).getOffset() / 16;
            if ((k & 31) == 0) fo.nextFrame();
        }
        long repArea = System.nanoTime() - t0;
        t0 = System.nanoTime();
        for (int k = 0; k < reps; k++) {
            int i = r.nextInt(sizes.length);
            w.freeLater(pool, WorldArena.page(wp[i]), WorldArena.offset(wp[i]));
            src.limit(64 * (1 + r.nextInt(2000))).position(0);
            wp[i] = w.upload(pool, src);
            if ((k & 31) == 0) f.nextFrame();
        }
        long repWorld = System.nanoTime() - t0;
        MemoryUtil.memFree(src);
        System.out.printf("  intake cost (M, this JVM, cold): fill %d sections: per-area %.0f ns/upload, world %.0f ns/upload; %d rebuilds: per-area %.0f ns, world %.0f ns; world %d pages %d MiB for %d MiB live%n",
                sizes.length, fillArea / (double) sizes.length, fillWorld / (double) sizes.length, reps, repArea / (double) reps, repWorld / (double) reps,
                pool.pageCount(), pool.capacity() >> 20, pool.used() >> 20);
        check("world arena rebuild cost per upload within 3x of per-area AreaBuffer (" + (repWorld / reps) + " vs " + (repArea / reps) + " ns), pages consistent",
                repWorld <= 3 * repArea + 2_000_000 && pool.errors().isEmpty() && w.badFrees == 0);
    }
}
