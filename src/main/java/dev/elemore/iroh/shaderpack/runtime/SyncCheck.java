package dev.elemore.iroh.shaderpack.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B-3B: an independent hazard checker for the pack runtime's barriers (the offline oracle beside the Khronos
 * synchronization validation; used by {@link RecordingBackend} on every recorded command and by {@link FrameExecutor}'s
 * sync debug mode on its declared accesses). Single-barrier semantics, no execution-dependency chains (the compiled
 * barriers never rely on chains), per resource:
 * <ul>
 *   <li>read after write: some barrier recorded after the write whose source scope covers the write's stages and
 *       write accesses (memory dependency on that resource, or a global one) and whose destination scope covers the
 *       read's stages and accesses;</li>
 *   <li>write after write: the same, destination = the new write's stages/accesses;</li>
 *   <li>write after read: for every read since the last write, some barrier after that read whose source stages
 *       cover the read's stages and destination stages cover the write's (execution dependency).</li>
 * </ul>
 * Barriers are folded in as they arrive (what each covers), so the state per resource stays small however long it
 * lives. A full barrier ({@link #full}) orders and makes visible everything before it.
 */
public final class SyncCheck {

    public final List<String> violations = new ArrayList<>();
    public int count;
    /** the latest violation message */
    public String last;
    private final int max;

    public SyncCheck(int maxKept) {
        this.max = maxKept;
    }

    private static final class Res {
        boolean hasWrite;
        int wStage, wAccess;
        /** (dstStages, dstAccess) pairs of barriers that covered the pending write as a memory dependency */
        final List<int[]> wCover = new ArrayList<>(2);
        /** reads since the last write: stage mask -> destination stage masks of barriers that ordered it */
        final List<int[]> reads = new ArrayList<>(2); // {stage, n, dst0, dst1, ...}
    }

    private final Map<Integer, Res> res = new HashMap<>();

    /** everything before is ordered and visible (VkPackBackend's legacy ALL_COMMANDS memory barrier, a submit-and-wait) */
    public void full() {
        res.clear();
    }

    /** a pipeline barrier: execution dependency srcStages -> dstStages for all, memory dependencies for the listed resources */
    public void barrier(GpuBackend.Barrier b) {
        if (b == null)
            return;
        barrier(b.srcStages(), b.dstStages(), b.images(), b.imageSrc(), b.imageDst(), b.buffers(), b.bufferSrc(), b.bufferDst());
    }

    public void barrier(int ss, int ds, int[] images, int[] isrc, int[] idst, int[] buffers, int[] bsrc, int[] bdst) {
        for (Map.Entry<Integer, Res> e : res.entrySet()) {
            int id = e.getKey();
            Res r = e.getValue();
            int sa = 0, da = 0;
            if ((id & Sync.BUF) != 0) {
                int h = id & ~Sync.BUF;
                for (int i = 0; buffers != null && i < buffers.length; i++)
                    if (buffers[i] == h) {
                        sa |= bsrc[i];
                        da |= bdst[i];
                    }
            } else {
                for (int i = 0; images != null && i < images.length; i++)
                    if (images[i] == id) {
                        sa |= isrc[i];
                        da |= idst[i];
                    }
            }
            if (r.hasWrite && Sync.coversStages(ss, r.wStage) && sa != 0 && Sync.coversAccess(sa, r.wAccess))
                addPair(r.wCover, ds, da);
            for (int[] rd : r.reads)
                if (Sync.coversStages(ss, rd[0]))
                    addExec(r, rd, ds);
        }
    }

    /** an image (or BUF|buffer) barrier with explicit masks on one resource only (VkPackBackend's per-image layout barriers) */
    public void imageBarrier(int id, int ss, int ds, int sa, int da) {
        barrier(ss, ds, (id & Sync.BUF) != 0 ? null : new int[]{id}, new int[]{sa}, new int[]{da},
                (id & Sync.BUF) != 0 ? new int[]{id & ~Sync.BUF} : null, new int[]{sa}, new int[]{da});
    }

    private static void addPair(List<int[]> l, int ds, int da) {
        for (int[] p : l)
            if (p[0] == ds && p[1] == da)
                return;
        l.add(new int[]{ds, da});
    }

    private static void addExec(Res r, int[] rd, int ds) {
        for (int i = 2; i < 2 + rd[1]; i++)
            if (rd[i] == ds)
                return;
        int[] n = java.util.Arrays.copyOf(rd, Math.max(rd.length, 3 + rd[1]));
        n[2 + rd[1]] = ds;
        n[1]++;
        r.reads.set(r.reads.indexOf(rd), n);
    }

    /**
     * one access of a command; returns true when it is synchronised (or no hazard). {@code what} names the command for
     * the violation message.
     */
    public boolean access(int id, int stages, int access, boolean write, String what) {
        Res r = res.computeIfAbsent(id, k -> new Res());
        boolean ok = true;
        String why = null;
        if (r.hasWrite) {
            boolean c = false;
            for (int[] p : r.wCover)
                if (Sync.coversStages(p[0], stages) && Sync.coversAccess(p[1], access)) {
                    c = true;
                    break;
                }
            if (!c) {
                ok = false;
                why = (write ? "write after write" : "read after write") + " (" + Sync.stages(r.wStage) + " -> " + Sync.stages(stages) + ")";
            }
        }
        if (write && ok)
            for (int[] rd : r.reads) {
                boolean c = false;
                for (int i = 2; i < 2 + rd[1]; i++)
                    if (Sync.coversStages(rd[i], stages)) {
                        c = true;
                        break;
                    }
                if (!c) {
                    ok = false;
                    why = "write after read (" + Sync.stages(rd[0]) + " -> " + Sync.stages(stages) + ")";
                    break;
                }
            }
        if (!ok) {
            count++;
            last = what + ": " + why + " on " + ((id & Sync.BUF) != 0 ? "buffer " + (id & ~Sync.BUF) : "image " + id);
            if (violations.size() < max)
                violations.add(last);
        }
        if (write) {
            r.hasWrite = true;
            r.wStage = stages;
            r.wAccess = access & Sync.WRITE_BITS;
            r.wCover.clear();
            r.reads.clear();
        } else {
            int s = Sync.expandStages(stages);
            boolean found = false;
            for (int i = 0; i < r.reads.size(); i++)
                if (r.reads.get(i)[0] == s) { // the latest read of these stages is the one later writes must wait for
                    r.reads.set(i, new int[]{s, 0});
                    found = true;
                }
            if (!found)
                r.reads.add(new int[]{s, 0});
        }
        return ok;
    }

    /** an access by code ({@link Sync}) */
    public boolean access(int id, int code, String what) {
        return access(id, Sync.STAGE[code], Sync.ACCESS[code], Sync.isWrite(code), what);
    }

    /** forget one resource (freed) */
    public void forget(int id) {
        res.remove(id);
    }
}
