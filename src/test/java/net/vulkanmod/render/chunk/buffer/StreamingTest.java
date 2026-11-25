package net.vulkanmod.render.chunk.buffer;

import dev.elemore.iroh.perf.AreaArena;
import dev.elemore.iroh.perf.IntakeBudget;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * P2-4 (perf/streaming) offline checks, no GPU: {@code ./gradlew streamingTest}; last line "# streaming PASS n/n".
 * <ol>
 *   <li>AreaArena size classes and pool rules.</li>
 *   <li>AreaBuffer segment logic on a fake device (bytes really copied between fake buffers): every live segment keeps
 *       its bytes at its offset through grows, old path and arena path; the segment list stays consistent.</li>
 *   <li>RQ-P24-a: RenderSection.resetDrawParameters' old byte-offset free leaks the segment (or frees a live one at
 *       offset*elementSize); the element-offset deferred free frees exactly it.</li>
 *   <li>Intake budget replay: per-frame intake time old rule vs predicted stop, same section stream.</li>
 *   <li>Streaming replay: areas entering/leaving and filling, render-thread cost model old path vs arena (cost numbers
 *       are a MODEL, not a measurement: the in-game ab decides).</li>
 *   <li>Bulk dirty marks: the 27 grid indices equal vanilla's 27 setSectionDirty targets (wrap included).</li>
 * </ol>
 */
public final class StreamingTest {
    private static int pass, fail;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    // ------------------------------------------------------------------ fake device

    /** Fake device: buffers are byte arrays; copies copy; deferred segment frees run after FRAMES "frames". */
    static final class FakeOps implements AreaBuffer.Ops {
        static final int FRAMES = 2;
        final boolean arena;
        boolean data = true; // false: no byte arrays (big replays)
        final Map<Long, byte[]> mem = new HashMap<>();
        long nextId = 1;
        // stats
        int allocs, syncAllocs, poolHits, copies, regions;
        long copiedBytes;
        final ArrayDeque<Object[]> deferred = new ArrayDeque<>();
        int frame;
        // arena model (real AreaArena.Pool; prefetch thread modelled as "appears next frame")
        final AreaArena.Pool<Buffer> pool = new AreaArena.Pool<>(256L << 20);
        final Map<Buffer, Integer> cls = new IdentityHashMap<>();
        final ArrayDeque<Object[]> returning = new ArrayDeque<>(), prefetching = new ArrayDeque<>();
        final int[] outstanding = new int[64];
        boolean prefetch = true;
        /** render-thread cost of the current frame (ns), by the model */
        long frameCost;
        static final long COST_VMA = 400_000, COST_NEW_BLOCK = 3_000_000, COST_POOL = 5_000, COST_COPY_RECORD = 30_000;
        long vmaBytes;

        FakeOps(boolean arena) {
            this.arena = arena;
        }

        Buffer newBuffer(long bytes) {
            Buffer b = new Buffer(0, null);
            b.setId(nextId++);
            b.setBufferSize(bytes);
            if (data) mem.put(b.getId(), new byte[(int) bytes]);
            return b;
        }

        /** a VMA creation on the render thread: model cost, a fresh 256 MiB block every 256 MiB created */
        long vmaCost(long bytes) {
            long before = vmaBytes >> 28;
            vmaBytes += bytes;
            return COST_VMA + ((vmaBytes >> 28) != before ? COST_NEW_BLOCK : 0);
        }

        @Override public boolean arena() { return arena; }

        @Override public long arenaSize(long minBytes) { return AreaArena.sizeFor(minBytes); }

        @Override
        public Buffer allocate(int usage, long bytes, boolean arena) {
            allocs++;
            if (!arena) {
                syncAllocs++;
                frameCost += vmaCost(bytes);
                return newBuffer(bytes);
            }
            int c = AreaArena.classOf(bytes);
            Buffer b = pool.take(c);
            if (b != null) {
                poolHits++;
                frameCost += COST_POOL;
                if (data) Arrays.fill(mem.get(b.getId()), (byte) 0x5A); // stale bytes from its previous owner
            } else {
                syncAllocs++;
                frameCost += vmaCost(bytes);
                b = newBuffer(AreaArena.classBytes(c));
                cls.put(b, c);
            }
            if (prefetch) {
                for (int k = c; k <= c + 1; k++)
                    if (pool.count(k) + outstanding[k] < 1) {
                        outstanding[k]++;
                        prefetching.add(new Object[]{frame + 1, k});
                    }
            }
            return b;
        }

        @Override
        public void retire(Buffer b) {
            if (arena && cls.containsKey(b)) returning.add(new Object[]{frame + FRAMES, b});
            else mem.remove(b.getId());
        }

        @Override
        public void recordUpload(Buffer dst, long dstOffset, long size, ByteBuffer src) {
            if (!data) return;
            byte[] m = mem.get(dst.getId());
            for (int i = 0; i < size; i++) m[(int) dstOffset + i] = src.get(src.position() + i);
        }

        @Override
        public void copyAll(Buffer src, Buffer dst) {
            copies++;
            regions++;
            frameCost += COST_COPY_RECORD;
            long n = Math.min(src.getBufferSize(), dst.getBufferSize());
            if (data) System.arraycopy(mem.get(src.getId()), 0, mem.get(dst.getId()), 0, (int) n);
            copiedBytes += n;
        }

        @Override
        public void copyRegions(Buffer src, Buffer dst, long[] offsets, long[] sizes, int n) {
            copies++;
            regions += n;
            frameCost += COST_COPY_RECORD;
            for (int i = 0; i < n; i++) {
                if (data) System.arraycopy(mem.get(src.getId()), (int) offsets[i], mem.get(dst.getId()), (int) offsets[i], (int) sizes[i]);
                copiedBytes += sizes[i];
            }
        }

        @Override
        public void deferSegmentFree(AreaBuffer a, int elementOffset) {
            deferred.add(new Object[]{frame + FRAMES, a, elementOffset});
        }

        /** next frame: due segment frees, pool returns (epoch done), prefetches (off-thread, no render cost) */
        void nextFrame() {
            frame++;
            frameCost = 0;
            while (!deferred.isEmpty() && (int) deferred.peek()[0] <= frame) {
                Object[] d = deferred.poll();
                ((AreaBuffer) d[1]).setSegmentFree((int) d[2]);
            }
            while (!returning.isEmpty() && (int) returning.peek()[0] <= frame) {
                Buffer b = (Buffer) returning.poll()[1];
                if (!pool.put(cls.get(b), b)) mem.remove(b.getId());
            }
            while (!prefetching.isEmpty() && (int) prefetching.peek()[0] <= frame) {
                int c = (int) prefetching.poll()[1];
                outstanding[c]--;
                Buffer b = newBuffer(AreaArena.classBytes(c));
                cls.put(b, c);
                if (!pool.put(c, b)) mem.remove(b.getId());
            }
        }
    }

    /** a section mesh of {@code bytes} filled with a pattern derived from {@code tag} */
    static ByteBuffer mesh(int bytes, int tag) {
        ByteBuffer b = MemoryUtil.memAlloc(bytes);
        for (int i = 0; i < bytes; i++) b.put(i, (byte) (tag * 31 + i * 7));
        return b;
    }

    static boolean holds(FakeOps ops, AreaBuffer a, int offset, int bytes, int tag) {
        byte[] m = ops.mem.get(a.getBuffer().getId());
        for (int i = 0; i < bytes; i++) if (m[offset + i] != (byte) (tag * 31 + i * 7)) return false;
        return true;
    }

    record Live(int offset, int bytes, int tag) {}

    /**
     * Random fill / replace / clear on one vertex AreaBuffer (16-byte elements). Returns {grows, copiedBytes, errors,
     * corrupt}. Each "slot" is one section facing: an upload replaces its previous segment (deferred free), like
     * DrawBuffers.upload.
     */
    static long[] churn(boolean arena, long seed, int ops, boolean elementFree) {
        SplittableRandom r = new SplittableRandom(seed);
        FakeOps f = new FakeOps(arena);
        AreaBuffer a = new AreaBuffer(AreaBuffer.Usage.VERTEX, 100000, 16, f);
        Map<Integer, Live> slots = new HashMap<>();
        long errors = 0, corrupt = 0;
        int tag = 0;
        for (int i = 0; i < ops; i++) {
            int slot = r.nextInt(400);
            Live old = slots.get(slot);
            if (old != null && r.nextInt(8) == 0) {
                // the section lost this layer: RenderSection.resetDrawParameters(renderType)
                if (elementFree) a.freeSegmentDeferred(old.offset / 16);
                else a.setSegmentFree(old.offset); // the pre-P2-4 call (byte offset)
                slots.remove(slot);
            } else {
                int bytes = 16 * (1 + (int) Math.min(40000, Math.abs(r.nextGaussian()) * 2500));
                ByteBuffer m = mesh(bytes, ++tag);
                AreaBuffer.Segment s = a.upload(m, old == null ? -1 : old.offset / 16, 0);
                MemoryUtil.memFree(m);
                slots.put(slot, new Live(s.getOffset(), bytes, tag));
            }
            if (i % 7 == 0) f.nextFrame();
            if (i % 97 == 0) {
                errors += a.segmentErrors().size();
                for (Live l : slots.values()) if (!holds(f, a, l.offset, l.bytes, l.tag)) corrupt++;
            }
        }
        for (int k = 0; k < 4; k++) f.nextFrame();
        errors += a.segmentErrors().size();
        for (Live l : slots.values()) if (!holds(f, a, l.offset, l.bytes, l.tag)) corrupt++;
        long live = 0;
        for (Live l : slots.values()) live += l.bytes;
        return new long[]{a.grows, a.grownCopyBytes, errors, corrupt, a.getSize(), live, a.getUsed()};
    }

    /**
     * TERRAIN-HOLES draw model: {@code SECTIONS} sections x 7 facings of real native draw-parameter slots on one vertex
     * AreaBuffer (fake device, 16-byte elements). Rebuilds upload facings like DrawBuffers.upload (a facing that becomes
     * empty keeps its vertexOffset with indexCount 0); a section losing the layer goes through
     * {@link SectionSegments#freeLayer} ({@code legacy}: the base's byte-offset setSegmentFree); relocation frees the
     * same way ({@code legacy}: parameters reset, segments leak). Every frame the "GPU" draws each slot with
     * indexCount > 0 and compares the bytes it reads with the mesh that slot uploaded. Returns {corrupt draws, slots
     * the integrity check flags, used bytes, live bytes, frames with a corrupt draw}.
     */
    static long[] drawModel(boolean legacy, long seed) {
        final int SECTIONS = 96, F = 7, ES = 16;
        SplittableRandom r = new SplittableRandom(seed);
        FakeOps f = new FakeOps(false);
        AreaBuffer a = new AreaBuffer(AreaBuffer.Usage.VERTEX, 4096, ES, f);
        long base = MemoryUtil.nmemCalloc(SECTIONS * F, DrawParametersBuffer.STRIDE);
        for (int i = 0; i < SECTIONS * F; i++) DrawParametersBuffer.resetParameters(base + i * DrawParametersBuffer.STRIDE);
        int[] tags = new int[SECTIONS * F], sizes = new int[SECTIONS * F];
        int tag = 0;
        long corrupt = 0, flagged = 0, badFrames = 0;
        for (int step = 0; step < 4000; step++) {
            int sec = r.nextInt(SECTIONS);
            long p0 = base + (long) sec * F * DrawParametersBuffer.STRIDE;
            int op = r.nextInt(10);
            if (op < 7) { // rebuild with the layer present
                for (int i = 0; i < F; i++) {
                    long ptr = p0 + i * DrawParametersBuffer.STRIDE;
                    int vo = DrawParametersBuffer.getVertexOffset(ptr);
                    if (r.nextInt(3) == 0) { // facing empty this time
                        DrawParametersBuffer.setIndexCount(ptr, 0);
                        continue;
                    }
                    int verts = 4 * (1 + r.nextInt(120));
                    ByteBuffer m = mesh(verts * ES, ++tag);
                    AreaBuffer.Segment sg = a.upload(m, vo, ptr);
                    MemoryUtil.memFree(m);
                    DrawParametersBuffer.setVertexOffset(ptr, sg.getOffset() / ES);
                    DrawParametersBuffer.setIndexCount(ptr, verts * 6 / 4);
                    tags[sec * F + i] = tag;
                    sizes[sec * F + i] = verts * ES;
                }
            } else { // op 7..8: the layer is gone (resetDrawParameters(renderType)); op 9: relocation (reset())
                if (legacy) {
                    if (op < 9)
                        for (int i = 0; i < F; i++) {
                            int vo = DrawParametersBuffer.getVertexOffset(p0 + i * DrawParametersBuffer.STRIDE);
                            if (vo != -1) a.setSegmentFree(vo * ES); // the base's call: a BYTE offset
                        }
                } else {
                    SectionSegments.freeLayer(a, null, p0, F);
                }
                for (int i = 0; i < F; i++) DrawParametersBuffer.resetParameters(p0 + i * DrawParametersBuffer.STRIDE);
            }
            if (step % 5 == 4) {
                f.nextFrame();
                // the frame's draws: what each drawn slot reads from the current buffer
                long before = corrupt;
                byte[] mem = f.mem.get(a.getBuffer().getId());
                for (int k = 0; k < SECTIONS * F; k++) {
                    long ptr = base + (long) k * DrawParametersBuffer.STRIDE;
                    int ic = DrawParametersBuffer.getIndexCount(ptr);
                    if (ic <= 0) continue;
                    int off = DrawParametersBuffer.getVertexOffset(ptr) * ES, bytes = ic / 6 * 4 * ES;
                    boolean ok = bytes == sizes[k] && off + bytes <= mem.length;
                    for (int b = 0; ok && b < bytes; b++) ok = mem[off + b] == (byte) (tags[k] * 31 + b * 7);
                    if (!ok) corrupt++;
                }
                if (corrupt > before) badFrames++;
                SectionSegments.Report rep = new SectionSegments.Report();
                for (int sc = 0; sc < SECTIONS; sc++)
                    SectionSegments.checkLayer(a, null, base + (long) sc * F * DrawParametersBuffer.STRIDE, F, ES, rep, () -> "");
                flagged += rep.bad();
            }
        }
        // everything drawn is live; after the frames in flight, used bytes must equal them (no leak)
        for (int k = 0; k < 4; k++) f.nextFrame();
        long live = 0;
        for (int k = 0; k < SECTIONS * F; k++) {
            long ptr = base + (long) k * DrawParametersBuffer.STRIDE;
            if (DrawParametersBuffer.getVertexOffset(ptr) != -1) live += sizes[k];
        }
        long used = a.getUsed();
        MemoryUtil.nmemFree(base);
        return new long[]{corrupt, flagged, used, live, badFrames};
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) {
        // ---- 1. size classes + pool
        long[] want = {256L << 10, 384L << 10, 512L << 10, 768L << 10, 1L << 20, 3L << 19, 2L << 20};
        boolean seq = true;
        for (int c = 0; c < want.length; c++) seq &= AreaArena.classBytes(c) == want[c];
        check("size classes are half octaves from 256 KiB (256K 384K 512K 768K 1M 1.5M 2M)", seq);
        boolean minimal = true;
        SplittableRandom r0 = new SplittableRandom(3);
        for (int i = 0; i < 10000; i++) {
            long b = 1 + r0.nextLong(600L << 20);
            int c = AreaArena.classOf(b);
            minimal &= c >= 0 && AreaArena.classBytes(c) >= b && (c == 0 || AreaArena.classBytes(c - 1) < b);
            minimal &= AreaArena.classBytes(c) < 2 * b || b < (256L << 10); // never more than 1.5x (+ the 256 KiB floor)
        }
        check("classOf = the smallest class that holds the request, at most 1.5x it", minimal);
        check("beyond 1 GiB: not pooled (exact size)", AreaArena.classOf((1L << 30) + 1) == -1 && AreaArena.sizeFor((1L << 30) + 1) == (1L << 30) + 1);
        AreaArena.Pool<String> p = new AreaArena.Pool<>(3L << 20);
        boolean put1 = p.put(4, "a"), put2 = p.put(4, "b"), put3 = p.put(5, "c"), put4 = p.put(4, "d"); // 1 + 1 + 1.5 > 3 MiB
        String t1 = p.take(4), t2 = p.take(4), t3 = p.take(4);
        check("pool: cap in bytes, LIFO per class, miss = null (" + put1 + put2 + put3 + put4 + " " + t1 + t2 + t3 + ")",
                put1 && put2 && !put3 && put4 && "d".equals(t1) && "b".equals(t2) && "a".equals(t3) && p.hits == 3);
        check("pool: drain empties it", p.drain().isEmpty() && p.put(5, "e") && p.drain().size() == 1 && p.pooledBytes() == 0);

        // ---- 2. AreaBuffer on a fake device
        long oldGrows = 0, newGrows = 0, oldCopy = 0, newCopy = 0, errs = 0, corrupt = 0;
        for (long seed = 1; seed <= 6; seed++) {
            long[] o = churn(false, seed, 6000, true);
            long[] n = churn(true, seed, 6000, true);
            oldGrows += o[0]; newGrows += n[0]; oldCopy += o[1]; newCopy += n[1];
            errs += o[2] + n[2];
            corrupt += o[3] + n[3];
        }
        check("segment list consistent after every op (old path and arena path, 6 x 6000 ops): " + errs + " errors", errs == 0);
        check("every live segment keeps its bytes at its offset through grows (fake device copies): " + corrupt + " corrupt", corrupt == 0);
        check("arena grows by half into classes: grows " + oldGrows + " -> " + newGrows + " (old rule +1/8)", newGrows * 2 < oldGrows);
        check("arena grow copies used ranges only: copied " + oldCopy / 1024 / 1024 + " -> " + newCopy / 1024 / 1024 + " MiB", newCopy < oldCopy / 2);

        // gaps: a buffer with a big hole copies two regions, not the hole
        FakeOps g = new FakeOps(true);
        AreaBuffer ga = new AreaBuffer(AreaBuffer.Usage.VERTEX, 16384, 16, g); // 256 KiB class
        ByteBuffer m1 = mesh(16 * 1024, 1), m2 = mesh(16 * 8192, 2), m3 = mesh(16 * 1024, 3);
        ga.upload(m1, -1, 0);
        AreaBuffer.Segment mid = ga.upload(m2, -1, 0);
        AreaBuffer.Segment tail = ga.upload(m3, -1, 0);
        ga.setSegmentFree(mid.getOffset() / 16);
        ByteBuffer big = mesh(16 * 12000, 4);
        ga.upload(big, -1, 0); // does not fit the hole or the tail: grow
        check("grow over a 128 KiB hole: 2 regions, " + g.copiedBytes + " B copied (old rule would copy the whole 256 KiB)",
                ga.grows == 1 && g.regions == 2 && g.copiedBytes == 2 * 16 * 1024 && holds(g, ga, tail.getOffset(), 16 * 1024, 3)
                        && ga.segmentErrors().isEmpty());
        MemoryUtil.memFree(m1); MemoryUtil.memFree(m2); MemoryUtil.memFree(m3); MemoryUtil.memFree(big);

        // ---- 3. RQ-P24-a: resetDrawParameters freed by BYTE offset
        long[] leak = churn(false, 11, 6000, false), fixed = churn(false, 11, 6000, true);
        check("old layer-reset free (byte offset) leaks: buffer used " + leak[6] / 1024 + " KiB for " + leak[5] / 1024
                + " KiB live; element-offset free: " + fixed[6] / 1024 + " KiB for " + fixed[5] / 1024 + " KiB", leak[6] > leak[5] && fixed[6] == fixed[5]);
        FakeOps h = new FakeOps(false);
        AreaBuffer ha = new AreaBuffer(AreaBuffer.Usage.VERTEX, 100000, 16, h);
        ByteBuffer e1 = mesh(16, 7), e15 = mesh(240, 8), e16 = mesh(256, 9);
        ha.upload(e1, -1, 0);                                   // byte 0, element 0
        AreaBuffer.Segment owner = ha.upload(e15, -1, 0);       // byte 16, element 1: its layer is reset
        AreaBuffer.Segment bystander = ha.upload(e16, -1, 0);   // byte 256, element 16: live, someone else's
        int usedBefore = ha.getUsed();
        int vertexOffset = owner.getOffset() / 16;              // what the draw parameters hold (elements)
        ha.setSegmentFree(vertexOffset * 16);                   // pre-P2-4 RenderSection.resetDrawParameters
        boolean wrongFreed = bystander.isFree() && !owner.isFree() && usedBefore - ha.getUsed() == 256;
        h.nextFrame();
        AreaBuffer hb = new AreaBuffer(AreaBuffer.Usage.VERTEX, 100000, 16, h);
        hb.upload(e1, -1, 0);
        AreaBuffer.Segment owner2 = hb.upload(e15, -1, 0);
        AreaBuffer.Segment bystander2 = hb.upload(e16, -1, 0);
        hb.freeSegmentDeferred(owner2.getOffset() / 16);         // P2-4 (arena switch on)
        boolean notYet = !owner2.isFree();
        for (int k = 0; k < FakeOps.FRAMES; k++) h.nextFrame();
        check("old byte-offset layer free hits the LIVE segment at offset*16 and leaks its own (" + wrongFreed
                + "); element-offset deferred free frees exactly its own, after the frames in flight (" + (notYet && owner2.isFree() && !bystander2.isFree()) + ")",
                wrongFreed && notYet && owner2.isFree() && !bystander2.isFree());
        MemoryUtil.memFree(e1); MemoryUtil.memFree(e15); MemoryUtil.memFree(e16);

        // ---- 3b. TERRAIN-HOLES: live sections LOSE FACES under the byte-offset free; the owned element free does not
        long oc = 0, of = 0, obf = 0, nc = 0, nf = 0, nbf = 0, leakBytes = 0;
        for (long seed = 1; seed <= 4; seed++) {
            long[] o = drawModel(true, seed), n = drawModel(false, seed);
            oc += o[0]; of += o[1]; obf += o[4];
            nc += n[0]; nf += n[1]; nbf += n[4];
            leakBytes += n[2] - n[3];
        }
        check("draw model, base layer free: " + oc + " corrupt draws in " + obf + " frames (another section's live mesh freed, then overwritten); integrity check flags " + of, oc > 0 && of > 0);
        check("draw model, owned element free (SectionSegments.freeLayer): " + nc + " corrupt draws, " + nf + " flagged, used - live = " + leakBytes + " B", nc == 0 && nf == 0 && leakBytes == 0);
        // the free never touches a segment the slot does not own: sequential translucent indices (firstIndex 0)
        FakeOps ti = new FakeOps(false);
        AreaBuffer idx = new AreaBuffer(AreaBuffer.Usage.INDEX, 4096, 2, ti);
        long slots = MemoryUtil.nmemCalloc(2 * 7, DrawParametersBuffer.STRIDE);
        for (int i = 0; i < 14; i++) DrawParametersBuffer.resetParameters(slots + i * DrawParametersBuffer.STRIDE);
        long yUndef = slots + 6 * DrawParametersBuffer.STRIDE, xUndef = slots + 13 * DrawParametersBuffer.STRIDE;
        ByteBuffer ib = mesh(600, 3);
        AreaBuffer.Segment ys = idx.upload(ib, -1, yUndef); // section Y: sorted indices at index offset 0
        DrawParametersBuffer.setFirstIndex(yUndef, ys.getOffset() / 2);
        DrawParametersBuffer.setIndexCount(yUndef, 300);
        DrawParametersBuffer.setFirstIndex(xUndef, 0);     // section X: sequential (auto) indices, firstIndex 0
        DrawParametersBuffer.setIndexCount(xUndef, 600);
        int q1 = SectionSegments.freeLayer(null, idx, slots + 7 * DrawParametersBuffer.STRIDE, 7);
        for (int k = 0; k < 4; k++) ti.nextFrame();
        boolean yKept = !ys.isFree();
        int q2 = SectionSegments.freeLayer(null, idx, slots, 7);
        for (int k = 0; k < 4; k++) ti.nextFrame();
        check("translucent index free only when owned: auto-index section frees nothing (" + q1 + ", Y kept " + yKept + "), Y frees its own (" + q2 + ", freed " + ys.isFree() + ")",
                q1 == 0 && yKept && q2 == 1 && ys.isFree() && idx.segmentErrors().isEmpty());
        // upload path: a sorted upload after a sequential one names oldOffset 0 it does not own: must not free it
        FakeOps tu = new FakeOps(false);
        AreaBuffer idx2 = new AreaBuffer(AreaBuffer.Usage.INDEX, 4096, 2, tu);
        AreaBuffer.Segment owner0 = idx2.upload(ib, -1, yUndef);   // Y owns index offset 0
        AreaBuffer.Segment xs = idx2.upload(ib, 0, xUndef);        // X was sequential: firstIndex 0, indexCount > 0
        for (int k = 0; k < 4; k++) tu.nextFrame();
        boolean y0Kept = !owner0.isFree() && owner0.getOffset() == 0;
        idx2.upload(ib, xs.getOffset() / 2, xUndef);               // X's own old segment is freed as before
        for (int k = 0; k < 4; k++) tu.nextFrame();
        check("upload frees the old segment only when its slot owns it (Y's offset 0 kept " + y0Kept + ", X's own freed " + xs.isFree() + ")",
                y0Kept && xs.isFree() && idx2.segmentErrors().isEmpty());
        MemoryUtil.memFree(ib);
        MemoryUtil.nmemFree(slots);

        // ---- 4. intake budget replay
        intakeReplay();

        // ---- 5. streaming replay: area turnover + growth, render-thread cost model
        long[] oldRun = streamingReplay(false), newRun = streamingReplay(true);
        System.out.println("info streaming model (per frame intake ms of area work): old p99 " + oldRun[0] / 1e6 + " max " + oldRun[1] / 1e6
                + " frames>1ms " + oldRun[2] + " render-thread allocs " + oldRun[3] + "; arena p99 " + newRun[0] / 1e6 + " max " + newRun[1] / 1e6
                + " frames>1ms " + newRun[2] + " render-thread allocs " + newRun[3] + " (after warm-up " + newRun[4] + ")");
        check("model: arena removes most render-thread buffer creations after warm-up (" + oldRun[3] + " -> " + newRun[4] + ")", newRun[4] * 10 < oldRun[3]);
        check("model: frames with > 1 ms of area-buffer work fall (" + oldRun[2] + " -> " + newRun[2] + ")", newRun[2] * 4 < Math.max(1, oldRun[2]));

        // ---- 6. bulk dirty marks
        boolean same = true;
        int[] out = new int[27];
        SplittableRandom rr = new SplittableRandom(5);
        for (int i = 0; i < 20000; i++) {
            int w = 1 + 2 * (2 + rr.nextInt(33)), hgt = 24, minSec = -4;
            int x = rr.nextInt(-3000, 3000), y = rr.nextInt(minSec - 2, minSec + hgt + 2), z = rr.nextInt(-3000, 3000);
            SectionGrid.neighbourIndices(x, y, z, w, hgt, minSec, out);
            int[] exp = new int[27];
            int n = 0;
            for (int l = z - 1; l <= z + 1; l++)          // vanilla LevelRenderer.setSectionDirtyWithNeighbors order
                for (int m = x - 1; m <= x + 1; m++)
                    for (int o = y - 1; o <= y + 1; o++)
                        exp[n++] = SectionGrid.gridIndex(m, o, l, w, hgt, minSec);
            same &= Arrays.equals(out, exp);
        }
        check("bulk dirty: the 27 indices (and order) equal vanilla's 27 setSectionDirty targets, wrap included", same);

        System.out.println("# streaming " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * A section stream: bursts of 20-80 sections every 6-12 frames, sizes log-normal around 40 KiB with a heavy tail
     * (block 6: sectionKB 85-886 for 1-16 sections). Cost model per section = 20 us + 0.6 ns/B (+ 2 ms when it grows an
     * area buffer, 1 in 400: not predictable from the size; the arena makes it cheap). Old rule: stop when spent > 1 ms after a section; new: IntakeBudget.
     */
    static void intakeReplay() {
        long[] o = intakeRun(false), n = intakeRun(true);
        System.out.println("info intake (ms): old max " + o[0] / 1e6 + " p99 " + o[1] / 1e6 + " over1.2ms " + o[2] + " sections " + o[3]
                + "; new max " + n[0] / 1e6 + " p99 " + n[1] / 1e6 + " over1.2ms " + n[2] + " sections " + n[3] + " backlog end " + n[4]);
        check("intake: frames pushed over 1.2 ms by a section's size (not an area grow) " + o[5] + " -> " + n[5] + "; all frames over 1.2 ms " + o[2] + " -> " + n[2],
                o[5] > 0 && n[5] == 0 && n[2] < o[2]);
        check("intake: same sections taken (" + o[3] + " vs " + n[3] + "), queue drained at the end (" + o[4] + "/" + n[4] + ")",
                n[3] >= o[3] * 98 / 100 && n[4] == 0 && o[4] == 0);
        IntakeBudget b = new IntakeBudget();
        for (int i = 0; i < 400; i++) b.observe(100_000 + i * 100, 20_000 + (long) (0.6 * (100_000 + i * 100)), false);
        check("intake: cost per byte learned (" + String.format("%.3f", b.perByteNs()) + " ns/B, true 0.6)", Math.abs(b.perByteNs() - 0.6) < 0.05);
        b.observe(200_000, 80_000_000, false); // one descheduled section
        check("intake: one 80 ms outlier does not freeze the intake (estimate of 100 KiB " + b.estimate(100 << 10) / 1000 + " us)", b.estimate(100 << 10) < 300_000);
    }

    /** {max, p99, frames over 1.2 ms, sections, left at end, frames over 1.2 ms with 2+ sections and no area grow} */
    static long[] intakeRun(boolean predict) {
        SplittableRandom r = new SplittableRandom(42);
        ArrayDeque<int[]> q = new ArrayDeque<>(); // {bytes, grows}
        IntakeBudget ib = new IntakeBudget();
        long budget = 1_000_000;
        List<Long> per = new ArrayList<>();
        long sections = 0, overBad = 0;
        int nextBurst = 0;
        for (int f = 0; f < 6000; f++) {
            if (f < 5000 && f >= nextBurst) {
                for (int i = 20 + r.nextInt(60); i > 0; i--) {
                    int bytes = (int) Math.min(900 << 10, Math.exp(10.6 + r.nextGaussian() * 0.9));
                    q.add(new int[]{bytes, r.nextInt(400) == 0 ? 1 : 0});
                }
                nextBurst = f + 6 + r.nextInt(7);
            }
            long t = 0;
            int n = 0;
            boolean grew = false;
            while (!q.isEmpty()) {
                int[] s = q.peek();
                if (predict) {
                    if (!ib.admit(t, budget, 0, Long.MAX_VALUE, s[0], n)) break;
                } else if (n > 0 && t > budget) break;
                q.poll();
                long cost = 20_000 + (long) (0.6 * s[0]) + (s[1] == 1 ? 2_000_000 : 0);
                t += cost;
                grew |= s[1] == 1;
                n++;
                if (predict) ib.observe(s[0], cost, s[1] == 1);
            }
            sections += n;
            per.add(t);
            if (t > 1_200_000 && n > 1 && !grew) overBad++; // crossed the budget by a section's size, not by a grow
        }
        Collections.sort(per);
        long over = per.stream().filter(x -> x > 1_200_000).count();
        return new long[]{per.get(per.size() - 1), per.get(per.size() * 99 / 100), over, sections, q.size(), overBad};
    }

    /**
     * Flight model: 64 area slots; every 30 frames 4 areas leave (their buffers retired) and 4 new ones start; sections
     * arrive in the new areas and are re-meshed now and then. Each area has 3 vertex buffers (solid, cutout-mipped,
     * translucent initial sizes as DrawBuffers). Render-thread cost per frame from FakeOps' model. Returns {p99, max,
     * frames > 1 ms, render-thread allocations, allocations after frame 600}.
     */
    static long[] streamingReplay(boolean arena) {
        FakeOps f = new FakeOps(arena);
        f.data = false;
        SplittableRandom r = new SplittableRandom(9);
        int[] initial = {100000, 250000, 60000};
        List<AreaBuffer[]> areas = new ArrayList<>();
        List<Map<Integer, int[]>> slots = new ArrayList<>();
        List<Long> per = new ArrayList<>();
        long warmAllocs = 0;
        int frames = 3000;
        ByteBuffer scratch = MemoryUtil.memAlloc(2 << 20);
        for (int fr = 0; fr < frames; fr++) {
            f.nextFrame();
            int allocs0 = f.syncAllocs;
            if (fr % 30 == 0) {
                for (int k = 0; k < 4 && areas.size() >= 60; k++) {
                    int i = r.nextInt(areas.size());
                    for (AreaBuffer a : areas.remove(i)) a.freeBuffer();
                    slots.remove(i);
                }
                for (int k = 0; k < 4 || areas.size() < 60; k++) {
                    AreaBuffer[] a = new AreaBuffer[3];
                    for (int t = 0; t < 3; t++) a[t] = new AreaBuffer(AreaBuffer.Usage.VERTEX, initial[t], 16, f);
                    areas.add(a);
                    slots.add(new HashMap<>());
                }
            }
            // ~10 section uploads per frame into the newest areas (streaming edge), sometimes a re-mesh
            for (int u = 0; u < 10; u++) {
                int ai = areas.size() - 1 - r.nextInt(Math.min(12, areas.size()));
                int t = r.nextInt(3);
                int slot = r.nextInt(512) * 3 + t;
                int bytes = 16 * (1 + (int) Math.min(60000, Math.exp(7.5 + r.nextGaussian())));
                int[] old = slots.get(ai).get(slot);
                scratch.clear().limit(bytes);
                AreaBuffer.Segment s = areas.get(ai)[t].upload(scratch, old == null ? -1 : old[0] / 16, 0);
                slots.get(ai).put(slot, new int[]{s.getOffset()});
            }
            per.add(f.frameCost);
            if (fr >= 600) warmAllocs += f.syncAllocs - allocs0;
        }
        MemoryUtil.memFree(scratch);
        List<Long> sorted = new ArrayList<>(per);
        Collections.sort(sorted);
        long over = per.stream().filter(x -> x > 1_000_000).count();
        return new long[]{sorted.get(sorted.size() * 99 / 100), sorted.get(sorted.size() - 1), over, f.syncAllocs, warmAllocs};
    }
}
