package dev.elemore.iroh.uploadtest;

import net.vulkanmod.vulkan.memory.staging.StagingRing;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * perf/uploads: the chained staging ring, headless with real native blocks (LWJGL nmemAlloc). Chain and budget,
 * release only after the consuming epoch completed (randomized completion lag and out-of-order reports), pending
 * reservations pin their block, dedicated oversized blocks, the R-3 checker's live/pooled distinction, and 8 worker
 * threads reserving and writing concurrently while the render thread consumes and ends epochs (data integrity proves no
 * two live reservations overlap). {@code ./gradlew stagingRingTest}; last line "# stagingring PASS n/n".
 */
public final class StagingRingTest {
    private static int pass, fail;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    static final class Blk implements StagingRing.Block {
        static final AtomicLong IDS = new AtomicLong(1);
        final long id = IDS.getAndIncrement(), addr, size;
        volatile boolean destroyed;

        Blk(long size) {
            this.size = size;
            this.addr = MemoryUtil.nmemAlloc(size);
        }

        @Override public long bufferId() { return id; }
        @Override public long address() { return addr; }
        @Override public long size() { return size; }
    }

    static final List<Blk> ALL = new ArrayList<>();

    static StagingRing ring(long block, long budget) {
        return new StagingRing(new StagingRing.Factory() {
            @Override public StagingRing.Block create(long size) {
                Blk b = new Blk(size);
                synchronized (ALL) { ALL.add(b); }
                return b;
            }

            @Override public void destroy(StagingRing.Block block) {
                Blk b = (Blk) block;
                b.destroyed = true;
                MemoryUtil.nmemFree(b.addr);
            }
        }, block, budget);
    }

    record Used(long block, long off, long size, long epoch) {}

    public static void main(String[] args) throws Exception {
        final long MB = 1 << 20;

        // 1. chain + budget
        StagingRing r = ring(4 * MB, 16 * MB);
        List<StagingRing.Alloc> held = new ArrayList<>();
        StagingRing.Alloc a;
        while ((a = r.reserve(512 * 1024, 4)) != null) held.add(a);
        check("chain: blocks chained until the budget (" + r.totalBytes() / MB + " MB, " + held.size() + " reservations)",
                r.totalBytes() <= 16 * MB && held.size() == 32 && r.statBudgetMisses == 1);
        for (var h : held) r.consumed(h);
        r.endEpoch();
        check("budget: nothing reusable before the epoch completed", r.reserve(512 * 1024, 4) == null);
        r.completed(0);
        check("after the epoch completed, blocks are pooled and reused without new allocation",
                r.reserve(512 * 1024, 4) != null && r.statBlocksCreated == 4);

        // 2. randomized lag: no reservation overlaps memory a not-yet-completed epoch may still read
        StagingRing r2 = ring(1 * MB, 8 * MB);
        SplittableRandom rnd = new SplittableRandom(7);
        List<Used> inFlight = new ArrayList<>();
        long completed = -1;
        int overlaps = 0, nulls = 0, reserves = 0;
        for (int frame = 0; frame < 3000; frame++) {
            int n = rnd.nextInt(1, 40);
            for (int i = 0; i < n; i++) {
                long size = rnd.nextInt(16, 200_000);
                var x = r2.reserve(size, 4);
                reserves++;
                if (x == null) { nulls++; continue; }
                for (Used u : inFlight)
                    if (u.epoch > completed && u.block == x.bufferId && x.offset < u.off + u.size && u.off < x.offset + x.size)
                        overlaps++;
                r2.consumed(x);
                inFlight.add(new Used(x.bufferId, x.offset, x.size, r2.epoch()));
            }
            r2.endEpoch();
            long target = frame - rnd.nextInt(1, 5); // the GPU lags 1-4 frames
            if (target > completed) {
                // report out of order sometimes: a later epoch first, then an older one (must not go backwards)
                if (rnd.nextInt(4) == 0 && target - 1 > completed) r2.completed(target - 1);
                r2.completed(target);
                completed = Math.max(completed, target);
                final long c = completed;
                inFlight.removeIf(u -> u.epoch <= c - 8);
            }
        }
        check("3000 frames, lag 1-4, out-of-order reports: 0 overlaps with in-flight data (" + overlaps + "), budget held ("
                + r2.totalBytes() / MB + " MB), " + nulls + "/" + reserves + " budget misses", overlaps == 0 && r2.totalBytes() <= 8 * MB);

        // 3. a pending reservation pins its block
        StagingRing r3 = ring(1 * MB, 2 * MB);
        var pinned = r3.reserve(600_000, 4);
        var other = r3.reserve(600_000, 4); // second block
        r3.consumed(other);
        r3.endEpoch();
        r3.completed(0);
        check("pending reservation pins its block (block live, not pooled)", r3.isLiveBlock(pinned.bufferId) && r3.pooledBlocks() == 1);
        r3.cancel(pinned);
        check("cancel releases it once its epoch is done", !r3.isLiveBlock(pinned.bufferId) && r3.isRingBuffer(pinned.bufferId));
        boolean twice = false;
        try { r3.consumed(other); } catch (IllegalStateException e) { twice = true; }
        check("double consumption is an error", twice);

        // 4. dedicated oversized block
        StagingRing r4 = ring(1 * MB, 64 * MB);
        var big = r4.reserve(10 * MB, 4);
        r4.consumed(big);
        long id = big.bufferId;
        r4.endEpoch();
        Blk bigBlk = ALL.stream().filter(b -> b.id == id).findFirst().orElseThrow();
        check("oversized upload gets a dedicated block, kept while in flight", !bigBlk.destroyed && r4.isLiveBlock(id));
        r4.completed(0);
        check("dedicated block destroyed after its epoch (not pooled)", bigBlk.destroyed && !r4.isRingBuffer(id) && r4.totalBytes() == 0);

        // 5. R-3 checker cases
        StagingRing r5 = ring(1 * MB, 4 * MB);
        var c1 = r5.reserve(1000, 4);
        check("R-3: a live block may be read", r5.isLiveBlock(c1.bufferId));
        r5.consumed(c1);
        r5.endEpoch();
        r5.completed(0);
        check("R-3: after release the block is pooled: reading it would be reported", !r5.isLiveBlock(c1.bufferId) && r5.isRingBuffer(c1.bufferId));
        check("R-3: an unrelated buffer is not ring staging", !r5.isRingBuffer(987654321L));

        // 5b. rc8: workers may pin at most half the budget; the render thread still finds room
        StagingRing r7 = ring(1 * MB, 8 * MB);
        List<StagingRing.Alloc> pinnedByWorkers = new ArrayList<>();
        StagingRing.Alloc wa;
        while ((wa = r7.reserveForWorker(256 * 1024, 4)) != null) pinnedByWorkers.add(wa);
        long pinnedBytes = pinnedByWorkers.size() * 256L * 1024;
        check("workers pin at most half the budget (" + pinnedBytes / MB + " MB of 8)", pinnedBytes <= 4 * MB);
        check("render thread still gets staging while workers are at their share", r7.reserve(1 * MB, 4) != null);
        for (var p2 : pinnedByWorkers) r7.cancel(p2);
        check("after the worker reservations end, workers may reserve again", r7.reserveForWorker(256 * 1024, 4) != null);

        // 6. concurrent workers
        StagingRing r6 = ring(2 * MB, 64 * MB);
        ConcurrentLinkedQueue<StagingRing.Alloc> ready = new ConcurrentLinkedQueue<>();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong produced = new AtomicLong(), fallbacks = new AtomicLong();
        Thread[] workers = new Thread[8];
        for (int w = 0; w < workers.length; w++) {
            final int seed = w;
            workers[w] = new Thread(() -> {
                SplittableRandom wr = new SplittableRandom(seed);
                while (!stop.get()) {
                    int size = wr.nextInt(4, 64_000) & ~3;
                    var x = r6.reserve(size, 4);
                    if (x == null) { fallbacks.incrementAndGet(); Thread.onSpinWait(); continue; }
                    int pattern = (int) (x.address ^ size);
                    for (long o = 0; o < size; o += 4) MemoryUtil.memPutInt(x.address + o, pattern);
                    r6.track(x);
                    ready.add(x);
                    produced.incrementAndGet();
                }
            });
            workers[w].start();
        }
        int corrupt = 0, consumed = 0;
        long epochDone = -1;
        long t0 = System.nanoTime();
        List<Long> epochs = new ArrayList<>();
        while (System.nanoTime() - t0 < 2_000_000_000L) {
            StagingRing.Alloc x;
            int k = 0;
            while (k++ < 200 && (x = ready.poll()) != null) {
                int pattern = (int) (x.address ^ x.size);
                for (long o = 0; o < x.size; o += 4)
                    if (MemoryUtil.memGetInt(x.address + o) != pattern) { corrupt++; break; }
                if (r6.tracked(x.address) != x) corrupt++;
                r6.consumed(x);
                consumed++;
            }
            epochs.add(r6.endEpoch());
            if (epochs.size() > 3) { epochDone = epochs.get(epochs.size() - 4); r6.completed(epochDone); }
        }
        stop.set(true);
        for (Thread t : workers) t.join();
        check("8 workers x 2 s: " + produced.get() + " reservations written concurrently, " + consumed + " consumed, 0 corrupt ("
                + corrupt + "), budget " + r6.totalBytes() / MB + " MB <= 64", corrupt == 0 && consumed > 1000 && r6.totalBytes() <= 64 * MB);

        System.out.println("# stagingring " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
