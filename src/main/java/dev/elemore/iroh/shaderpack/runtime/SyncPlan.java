package dev.elemore.iroh.shaderpack.runtime;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B-3B (RQ-APP1-b): the pack frame's barriers, decided ahead of time from the frame plan and replayed by
 * {@link FrameExecutor}, instead of a hazard tracker deciding at record time.
 * <p>
 * <b>Sites.</b> Every command the executor records is a SITE: a pass's draw / dispatch / geometry instance / capture /
 * mip chain, a clear, upload, copy or blit of the frame's begin or end, the upscale cut, a world-pass attachment set.
 * A site has a stable id, a position in the plan ({@code order}: pass index and sub-step, so sites sort in the order
 * the executor records them) and the accesses it makes (owned images and buffers, as {@link Sync} codes). Its
 * accesses are the executor's own declarations, registered the first time the site runs (that run is ISOLATED: a full
 * barrier before and after, so an unregistered command is invisible to the compiled schedule).
 * <p>
 * <b>Compile</b> (at the next frame begin after a site was registered: in steady state never): a static analysis over
 * the sites in plan order, on a lattice per resource (pending write stages/accesses, read stages since that write,
 * read families the write is already visible to). Sites that may not run in a frame (geometry without batches,
 * shadow passes while the maps are reused, inputs that come and go) are joined over both outcomes; a REPEAT site
 * (several geometry render pass instances in a row) and a REGION (sites that run in any order and number: the frame
 * begin's clears/uploads/copies, the world pass's substituted attachment sets between executor segments) are solved to
 * a fixed point. The frame is iterated until the state at its start equals the state at its end (frames in flight on
 * one queue: frame N+1 starts where frame N stopped; V-7's carry, computed once instead of per frame). Each site gets
 * ONE barrier: source = the stages/accesses of the conflicting earlier accesses, destination = its own, image barriers
 * GENERAL -> GENERAL (owned images never change layout) for resources that need a memory dependency, an execution-only
 * barrier for pure write-after-read. Nothing is credited across barriers by execution chains.
 * <p>
 * <b>Replay</b>: per site an array lookup, an order check and the precomputed barrier. A site out of plan order, after
 * a skipped site the schedule relied on (MUST), or after a new image was created, runs isolated and the plan is
 * recompiled; a frame that did not end (aborted world pass) or a recompile is followed by one full barrier (fence).
 * Imported images (the renderer's scene colour/depth, atlas) are not tracked: the backend wraps every transfer on
 * them in layout barriers (ALL_COMMANDS, the global fallback) and the renderer orders its own writes.
 * <p>
 * <b>Debug</b> ({@code -Diroh.pack.syncDebug=true}, on in the offline harnesses): the executor also runs the old
 * dynamic tracker (V-6/V-7) without emitting its barriers, every declared access is checked by a {@link SyncCheck}
 * against the barriers actually emitted, every access the old tracker would have barriered must be covered, and a
 * compiled site whose declarations differ from what it was compiled with is reported (drift).
 */
public final class SyncPlan {

    /** MUST: runs whenever the frame does (its barrier's effect is credited); REPEAT: may run several times in a row;
     *  OPAQUE: commands the schedule cannot see into (always isolated); ONCE: a region member that runs at most once a frame */
    public static final int MUST = 1, REPEAT = 2, OPAQUE = 4, ONCE = 8;

    /** one command site (see the class comment) */
    static final class Site {
        final long id, order;
        final int region;
        int flags;
        Object label;
        /** compiled index, -1 = not in the compiled schedule */
        int idx = -1;
        /** registered accesses: resource ids and per resource a bit mask of access codes; null = not registered */
        int[] res, codes;
        int[] slots;
        GpuBackend.Barrier barrier;
        /** region members: the barrier the first member of the region run in a frame emits (the independent members' needs) */
        GpuBackend.Barrier entry;
        int prevMust = -1, regionStart = -1, regionEnd = -1, ranFrame = -1;
        boolean warned;

        Site(long id, long order, int region, int flags, Object label) {
            this.id = id;
            this.order = order;
            this.region = region;
            this.flags = flags;
            this.label = label;
        }

        String name() {
            return String.valueOf(label) + "@" + Long.toHexString(order);
        }
    }

    private final GpuBackend gpu;
    private final BitSet owned;
    private final SiteMap byId = new SiteMap();

    /** id -> site without boxing (P3-8: no allocation per frame in the executor) */
    static final class SiteMap {
        private long[] keys = new long[256];
        private Site[] vals = new Site[256];
        private int n;

        Site get(long k) {
            int m = keys.length - 1;
            for (int i = mix(k) & m; ; i = (i + 1) & m) {
                Site v = vals[i];
                if (v == null)
                    return null;
                if (keys[i] == k)
                    return v;
            }
        }

        void put(long k, Site v) {
            if (2 * (n + 1) > keys.length) {
                long[] ok = keys;
                Site[] ov = vals;
                keys = new long[ok.length * 2];
                vals = new Site[ok.length * 2];
                n = 0;
                for (int i = 0; i < ok.length; i++)
                    if (ov[i] != null)
                        put(ok[i], ov[i]);
            }
            int m = keys.length - 1;
            for (int i = mix(k) & m; ; i = (i + 1) & m) {
                if (vals[i] == null) {
                    keys[i] = k;
                    vals[i] = v;
                    n++;
                    return;
                }
                if (keys[i] == k) {
                    vals[i] = v;
                    return;
                }
            }
        }

        List<Site> values() {
            List<Site> l = new ArrayList<>(n);
            for (Site v : vals)
                if (v != null)
                    l.add(v);
            return l;
        }

        void clear() {
            java.util.Arrays.fill(vals, null);
            n = 0;
        }

        private static int mix(long k) {
            long h = k * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32));
        }
    }
    private Site[] sched = new Site[0];
    private int endPrevMust = -1;
    private boolean dirty, fresh, needFence = true, inFrame;
    private int lastIdx = -1, frameNo;
    // the current site
    private Site cur;
    private int curPipe;
    private boolean isolating, collecting;
    private int[] dRes = new int[32];
    private String[] dKind = new String[32];
    private int nDecl;
    // stats (per frame; totals)
    public int frameCompiled, frameFull, frameIsolated;
    public long compiles, totalCompiled, totalFull, totalIsolated;
    public String lastCompile = "never";
    final List<String> problems;

    // debug
    final boolean debug;
    final SyncCheck check;
    private int[] oldFlag = new int[16];
    private int nOldFlag;
    public int oldBarriers, frameOld;

    /** negative control (packExecTest): compiled barriers are not emitted; the device model and the debug checker must both object */
    public static boolean dropCompiledForTest;

    /** V-7 negative control: compile without the cross-frame state (each frame assumed to start clean) */
    boolean noCarryForTest;
    /** V-6 negative control: no fence after creation, isolated sites without the barrier before them */
    boolean untrackedCreateForTest;

    SyncPlan(GpuBackend gpu, BitSet owned, boolean debug, List<String> problems) {
        this.gpu = gpu;
        this.owned = owned;
        this.debug = debug;
        this.problems = problems;
        this.check = debug ? new SyncCheck(64) : null;
    }

    boolean declaring() {
        return collecting || debug;
    }

    // ================================================================================================ runtime

    void frameBegin() {
        if (inFrame) // the previous frame did not end (abandoned world pass): its state is not what the schedule assumes
            needFence = true;
        if (dirty) {
            compile();
            dirty = false;
            needFence = true;
        }
        if (needFence && !untrackedCreateForTest)
            full();
        needFence = false;
        fresh = false;
        lastIdx = -1;
        frameNo++;
        inFrame = true;
        frameCompiled = frameFull = frameIsolated = frameOld = 0;
    }

    void frameEnd() {
        if (cur != null)
            after();
        if (endPrevMust > lastIdx) { // a site the end state relied on did not run: demote it, fence the next frame
            sched[endPrevMust].flags &= ~MUST;
            dirty = true;
            needFence = true;
        }
        inFrame = false;
    }

    /** a new image or buffer was created (its zeroing clear is not in the schedule): the next site runs isolated */
    void created() {
        fresh = true;
    }

    /**
     * before the command of site {@code id}: emits its compiled barrier, or isolates it. {@code id 0} = a one-off command
     * (inner uploads of lazily made images): always isolated, never registered.
     */
    void at(long id, long order, int region, int flags, int pipe, Object label) {
        if (cur != null)
            after();
        Site s = null;
        if (id != 0) {
            s = byId.get(id);
            if (s == null) {
                s = new Site(id, order, region, flags, label);
                byId.put(id, s);
            }
        }
        cur = s;
        curPipe = pipe;
        nDecl = 0;
        nOldFlag = 0;
        boolean iso = s == null || s.idx < 0 || (flags & OPAQUE) != 0 || fresh;
        if (!iso && (s.flags & ONCE) != 0 && s.ranFrame == frameNo) {
            iso = true; // a run-once region member ran twice: its hoisted barrier covered the first run only
            warn(s, "ran twice in one frame: isolated");
        }
        if (s != null)
            s.ranFrame = frameNo;
        if (!iso) {
            if (s.region == 0) {
                if (s.idx < lastIdx || (s.idx == lastIdx && (s.flags & REPEAT) == 0)) {
                    iso = true; // recorded out of plan order: the schedule's state at this point is unknown
                    warn(s, "recorded out of plan order (after #" + lastIdx + "): isolated");
                } else if (s.prevMust > lastIdx) {
                    demote(s.prevMust);
                    iso = true;
                }
            } else if (lastIdx > s.regionEnd || s.prevMust > lastIdx) {
                if (s.prevMust > lastIdx)
                    demote(s.prevMust);
                else
                    warn(s, "(region " + s.region + ") recorded after a later site: isolated");
                iso = true;
            }
        }
        if (iso) {
            if (!(untrackedCreateForTest && s == null))
                full();
            isolating = true;
            collecting = s != null && s.res == null && (flags & OPAQUE) == 0;
            fresh = false;
            frameIsolated++;
            totalIsolated++;
            if (s != null && s.idx >= 0)
                lastIdx = Math.max(lastIdx, s.region == 0 ? s.idx : s.regionStart);
        } else {
            isolating = collecting = false;
            if (s.region != 0 && lastIdx < s.regionStart && s.entry != null && !dropCompiledForTest) { // first member of the region this frame
                gpu.barrier(s.entry);
                frameCompiled++;
                totalCompiled++;
                if (debug)
                    check.barrier(s.entry);
            }
            lastIdx = s.region == 0 ? s.idx : Math.max(lastIdx, s.regionStart);
            if (s.barrier != null && !dropCompiledForTest) {
                gpu.barrier(s.barrier);
                frameCompiled++;
                totalCompiled++;
                if (debug)
                    check.barrier(s.barrier);
            }
        }
    }

    private void warn(Site s, String m) {
        if (s.warned)
            return;
        s.warned = true;
        problems.add("sync: " + s.name() + " " + m);
    }

    private void demote(int idx) {
        Site m = sched[idx];
        m.flags &= ~MUST;
        dirty = true;
    }

    /** the current site is abandoned before its command (a problem): nothing is registered */
    void cancel() {
        if (isolating)
            full();
        cur = null;
        nDecl = 0;
        isolating = collecting = false;
    }

    /** one access declaration of the current command (FrameExecutor.access) */
    void decl(int res, String kind) {
        if (nDecl == dRes.length) {
            dRes = java.util.Arrays.copyOf(dRes, nDecl * 2);
            dKind = java.util.Arrays.copyOf(dKind, nDecl * 2);
        }
        dRes[nDecl] = res;
        dKind[nDecl++] = kind;
    }

    /** debug: the old tracker would have put a barrier before this access of {@code image} */
    void oldWould(int image) {
        oldBarriers++;
        frameOld++;
        if (nOldFlag == oldFlag.length)
            oldFlag = java.util.Arrays.copyOf(oldFlag, nOldFlag * 2);
        oldFlag[nOldFlag++] = image;
    }

    private boolean tracked(int res) {
        return (res & Sync.BUF) != 0 || (res >= 0 && owned.get(res));
    }

    private int codeOf(int i) {
        int r = dRes[i];
        boolean depth = (r & Sync.BUF) == 0 && gpu.desc(r).vkFormat().startsWith("D");
        return Sync.code(dKind[i], curPipe, depth);
    }

    /** after the current command (FrameExecutor.recorded) */
    void after() {
        Site s = cur;
        if (collecting && s != null) {
            Map<Integer, Integer> m = new java.util.LinkedHashMap<>();
            for (int i = 0; i < nDecl; i++)
                if (tracked(dRes[i]))
                    m.merge(dRes[i], 1 << codeOf(i), (a, b) -> a | b);
            s.res = new int[m.size()];
            s.codes = new int[m.size()];
            int k = 0;
            for (Map.Entry<Integer, Integer> e : m.entrySet()) {
                s.res[k] = e.getKey();
                s.codes[k++] = e.getValue();
            }
            dirty = true;
        }
        if (debug)
            debugCheck(s);
        if (isolating)
            full();
        cur = null;
        nDecl = 0;
        nOldFlag = 0;
        isolating = collecting = false;
    }

    private void debugCheck(Site s) {
        String what = s == null ? "one-off command" : s.name();
        Map<Integer, Integer> decl = new java.util.LinkedHashMap<>();
        for (int i = 0; i < nDecl; i++)
            if (tracked(dRes[i]))
                decl.merge(dRes[i], 1 << codeOf(i), (a, b) -> a | b);
        Map<Integer, Boolean> ok = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : decl.entrySet()) {
            int st = 0, ac = 0;
            boolean w = false;
            for (int c = 0; c < Sync.NAME.length; c++)
                if ((e.getValue() & (1 << c)) != 0) {
                    st |= Sync.STAGE[c];
                    ac |= Sync.ACCESS[c];
                    w |= Sync.isWrite(c);
                }
            boolean good = check.access(e.getKey(), st, ac, w, what);
            ok.put(e.getKey(), good);
            if (!good)
                violation("hazard: " + check.last);
        }
        for (int i = 0; i < nOldFlag; i++) {
            int img = oldFlag[i];
            if (tracked(img) && Boolean.FALSE.equals(ok.get(img)))
                violation(what + ": the old tracker would put a barrier before image " + img + "; the compiled schedule does not cover it");
        }
        if (s != null && s.idx >= 0 && s.res != null && !isolating) {
            boolean same = decl.size() == s.res.length;
            for (int k = 0; same && k < s.res.length; k++)
                same = Integer.valueOf(s.codes[k]).equals(decl.get(s.res[k]));
            if (!same)
                violation("drift: " + what + " declared " + describe(decl) + " but was compiled with " + describe(s));
        }
    }

    private static String describe(Map<Integer, Integer> m) {
        StringBuilder sb = new StringBuilder("[");
        m.forEach((r, c) -> sb.append(sb.length() > 1 ? " " : "").append(r).append(':').append(codes(c)));
        return sb.append(']').toString();
    }

    private static String describe(Site s) {
        Map<Integer, Integer> m = new java.util.LinkedHashMap<>();
        for (int k = 0; k < s.res.length; k++)
            m.put(s.res[k], s.codes[k]);
        return describe(m);
    }

    private static String codes(int mask) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < Sync.NAME.length; c++)
            if ((mask & (1 << c)) != 0)
                sb.append(sb.length() == 0 ? "" : "+").append(Sync.NAME[c]);
        return sb.toString();
    }

    public int debugViolations;
    public final List<String> debugMessages = new ArrayList<>();

    /** debug violations of every plan in this JVM (the lavapipe harnesses report and fail on it) */
    public static final java.util.concurrent.atomic.AtomicInteger DEBUG_VIOLATIONS_ALL = new java.util.concurrent.atomic.AtomicInteger();
    public static final List<String> DEBUG_SAMPLE = java.util.Collections.synchronizedList(new ArrayList<>());

    private void violation(String m) {
        debugViolations++;
        DEBUG_VIOLATIONS_ALL.incrementAndGet();
        if (DEBUG_SAMPLE.size() < 16)
            DEBUG_SAMPLE.add(m);
        if (debugMessages.size() < 64)
            debugMessages.add(m);
        if (gpu instanceof RecordingBackend r) // every recording-backend harness already asserts no violations
            r.violations.add("sync debug: " + m);
    }

    /** a full memory barrier (ALL_COMMANDS, MEMORY_WRITE -> MEMORY_READ|WRITE): the fallback */
    void full() {
        gpu.barrier(-1, "ANY", "ANY");
        frameFull++;
        totalFull++;
        if (debug)
            check.full();
    }

    /** every site and the schedule are dropped (executor closed) */
    void reset() {
        byId.clear();
        sched = new Site[0];
        endPrevMust = -1;
        dirty = false;
        needFence = true;
        inFrame = false;
        cur = null;
        lastIdx = -1;
        if (debug)
            check.full();
    }

    int sites() {
        return sched.length;
    }

    // ================================================================================================ compile

    /** per resource lattice: ws/wa pending write stages/accesses, rs read stages since, vc read classes it is visible to */
    static final class State {
        final int[] ws, wa, rs, vc;

        State(int n) {
            ws = new int[n];
            wa = new int[n];
            rs = new int[n];
            vc = new int[n];
            java.util.Arrays.fill(vc, Sync.R_ALL);
        }

        State(State o) {
            ws = o.ws.clone();
            wa = o.wa.clone();
            rs = o.rs.clone();
            vc = o.vc.clone();
        }

        State join(State o) {
            State r = new State(this);
            for (int i = 0; i < ws.length; i++) {
                r.ws[i] |= o.ws[i];
                r.wa[i] |= o.wa[i];
                r.rs[i] |= o.rs[i];
                r.vc[i] &= o.vc[i];
            }
            return r;
        }

        boolean same(State o) {
            return java.util.Arrays.equals(ws, o.ws) && java.util.Arrays.equals(wa, o.wa) && java.util.Arrays.equals(rs, o.rs)
                    && java.util.Arrays.equals(vc, o.vc);
        }
    }

    /** resource ids of the compiled schedule (index = lattice slot) */
    private int[] slotRes = new int[0];

    void compile() {
        List<Site> l = new ArrayList<>();
        for (Site s : byId.values())
            if (s.res != null && (s.flags & OPAQUE) == 0)
                l.add(s);
        l.sort((a, b) -> a.order != b.order ? Long.compare(a.order, b.order) : Long.compare(a.id, b.id));
        for (Site s : byId.values())
            s.idx = -1;
        sched = l.toArray(new Site[0]);
        Map<Integer, Integer> slot = new HashMap<>();
        int lastMust = -1;
        for (int i = 0; i < sched.length; i++) {
            Site s = sched[i];
            s.idx = i;
            if (s.region != 0) {
                if (i > 0 && sched[i - 1].region == s.region && sched[i - 1].order == s.order)
                    s.regionStart = sched[i - 1].regionStart;
                else
                    s.regionStart = i;
                for (int k = s.regionStart; k <= i; k++)
                    sched[k].regionEnd = i;
                s.flags &= ~MUST;
            }
            s.prevMust = lastMust;
            if ((s.flags & MUST) != 0)
                lastMust = i;
            s.slots = new int[s.res.length];
            for (int k = 0; k < s.res.length; k++)
                s.slots[k] = slot.computeIfAbsent(s.res[k], x -> slot.size());
        }
        endPrevMust = lastMust;
        slotRes = new int[slot.size()];
        slot.forEach((r, i) -> slotRes[i] = r);
        State start = new State(slot.size());
        int iter = 0;
        State end;
        while (true) {
            end = run(start);
            iter++;
            State next = noCarryForTest ? new State(slot.size()) : start.join(end);
            if (next.same(start) || iter >= 16)
                break;
            start = next;
        }
        compiles++;
        int nb = 0, ni = 0;
        for (Site s : sched)
            if (s.barrier != null) {
                nb++;
                ni += s.barrier.images().length + s.barrier.buffers().length;
            }
        lastCompile = sched.length + " sites, " + slot.size() + " resources, " + nb + " barriers (" + ni + " image/buffer barriers), "
                + iter + " frame iterations" + (noCarryForTest ? " (no cross-frame carry: test)" : "");
    }

    /** one frame over the schedule from {@code start}; sets every site's barrier; returns the state at the frame's end */
    private State run(State start) {
        State s = start;
        int i = 0;
        while (i < sched.length) {
            Site site = sched[i];
            if (site.region != 0) {
                int j = i;
                while (j + 1 < sched.length && sched[j + 1].regionStart == site.regionStart && sched[j + 1].region == site.region)
                    j++;
                s = region(i, j, s);
                i = j + 1;
                continue;
            }
            State in = s;
            if ((site.flags & REPEAT) != 0)
                for (int guard = 0; guard < 32; guard++) {
                    State n = in.join(apply(site, barrierFor(site, in), in));
                    if (n.same(in))
                        break;
                    in = n;
                }
            site.barrier = barrierFor(site, in);
            State out = apply(site, site.barrier, in);
            s = (site.flags & MUST) != 0 ? out : s.join(out);
            i++;
        }
        return s;
    }

    /**
     * a region (members in any order and number, each may not run). Members whose resources no other member touches
     * are INDEPENDENT: their barriers against the state before the region are merged into one ENTRY barrier, emitted by
     * whichever member runs first in a frame (the frame begin's clears/uploads/copies: one barrier instead of one per
     * command). The others get their own barrier from the region's fixed point.
     */
    private State region(int i, int j, State in) {
        Map<Integer, Integer> users = new HashMap<>();
        for (int k = i; k <= j; k++)
            for (int r : sched[k].slots)
                users.merge(r, 1, Integer::sum);
        List<Site> indep = new ArrayList<>(), dep = new ArrayList<>();
        for (int k = i; k <= j; k++) {
            boolean alone = (sched[k].flags & ONCE) != 0; // a member that may repeat needs a barrier against itself
            for (int r : sched[k].slots)
                alone &= users.get(r) == 1;
            (alone ? indep : dep).add(sched[k]);
        }
        // the entry barrier: every independent member's barrier against the state before the region, merged
        int src = 0, dst = 0;
        List<int[]> ent = new ArrayList<>(); // {res, srcAccess, dstAccess}
        for (Site m : indep) {
            GpuBackend.Barrier b = barrierFor(m, in);
            m.barrier = null;
            if (b == null)
                continue;
            src |= b.srcStages();
            dst |= b.dstStages();
            for (int e = 0; e < b.images().length; e++)
                ent.add(new int[]{b.images()[e], b.imageSrc()[e], b.imageDst()[e]});
            for (int e = 0; e < b.buffers().length; e++)
                ent.add(new int[]{Sync.BUF | b.buffers()[e], b.bufferSrc()[e], b.bufferDst()[e]});
        }
        GpuBackend.Barrier entry = src == 0 ? null : merged(src, dst, ent);
        for (int k = i; k <= j; k++)
            sched[k].entry = entry;
        State r = in;
        if (entry != null) { // what the entry barrier makes visible (it is emitted only when a member runs: joined below)
            r = new State(in);
            for (int[] e : ent) {
                int slot = slotOf(e[0]);
                r.vc[slot] |= Sync.classesCovered(dst, e[2]);
            }
        }
        State after = r;
        for (Site m : indep)
            after = after.join(apply(m, null, r));
        for (int guard = 0; guard < 32; guard++) {
            State n = after;
            for (Site m : dep)
                n = n.join(apply(m, barrierFor(m, after), after));
            if (n.same(after))
                break;
            after = n;
        }
        for (Site m : dep)
            m.barrier = barrierFor(m, after);
        return in.join(after);
    }

    private int slotOf(int res) {
        for (int k = 0; k < slotRes.length; k++)
            if (slotRes[k] == res)
                return k;
        throw new IllegalStateException("resource " + res + " not in the schedule");
    }

    private static GpuBackend.Barrier merged(int src, int dst, List<int[]> ent) {
        int ni = 0, nb = 0;
        for (int[] e : ent)
            if ((e[0] & Sync.BUF) != 0)
                nb++;
            else
                ni++;
        int[] im = new int[ni], is = new int[ni], id = new int[ni], bu = new int[nb], bs = new int[nb], bd = new int[nb];
        ni = nb = 0;
        for (int[] e : ent)
            if ((e[0] & Sync.BUF) != 0) {
                bu[nb] = e[0] & ~Sync.BUF;
                bs[nb] = e[1];
                bd[nb++] = e[2];
            } else {
                im[ni] = e[0];
                is[ni] = e[1];
                id[ni++] = e[2];
            }
        return new GpuBackend.Barrier(src, dst, im, is, id, bu, bs, bd);
    }

    private static int stages(int mask) {
        int st = 0;
        for (int c = 0; c < Sync.NAME.length; c++)
            if ((mask & (1 << c)) != 0)
                st |= Sync.STAGE[c];
        return st;
    }

    private static int access(int mask) {
        int a = 0;
        for (int c = 0; c < Sync.NAME.length; c++)
            if ((mask & (1 << c)) != 0)
                a |= Sync.ACCESS[c];
        return a;
    }

    private static int writeAccess(int mask) {
        int a = 0;
        for (int c = 0; c < Sync.NAME.length; c++)
            if ((mask & (1 << c)) != 0)
                a |= Sync.WRITE_ACCESS[c];
        return a;
    }

    private static int readClasses(int mask) {
        int a = 0;
        for (int c = 0; c < Sync.NAME.length; c++)
            if ((mask & (1 << c)) != 0)
                a |= Sync.READ_CLASS[c];
        return a;
    }

    /** the one barrier site {@code s} needs in state {@code st} (null = none) */
    private GpuBackend.Barrier barrierFor(Site s, State st) {
        int src = 0, dst = 0, n = 0;
        int[] ent = new int[s.slots.length], eSrc = new int[s.slots.length], eDst = new int[s.slots.length];
        for (int k = 0; k < s.slots.length; k++) {
            int r = s.slots[k], m = s.codes[k];
            int S = stages(m), A = access(m);
            boolean write = writeAccess(m) != 0;
            boolean need, mem;
            if (write) {
                need = st.ws[r] != 0 || st.rs[r] != 0;
                mem = st.ws[r] != 0;
                if (need)
                    src |= st.ws[r] | st.rs[r];
            } else {
                need = st.ws[r] != 0 && (readClasses(m) & ~st.vc[r]) != 0;
                mem = need;
                if (need)
                    src |= st.ws[r];
            }
            if (need)
                dst |= S;
            if (mem) {
                ent[n] = k;
                eSrc[n] = st.wa[r];
                eDst[n++] = A;
            }
        }
        if (src == 0)
            return null;
        int ni = 0, nb = 0;
        for (int e = 0; e < n; e++)
            if ((slotRes[s.slots[ent[e]]] & Sync.BUF) != 0)
                nb++;
            else
                ni++;
        int[] im = new int[ni], is = new int[ni], id = new int[ni], bu = new int[nb], bs = new int[nb], bd = new int[nb];
        ni = nb = 0;
        for (int e = 0; e < n; e++) {
            int res = slotRes[s.slots[ent[e]]];
            if ((res & Sync.BUF) != 0) {
                bu[nb] = res & ~Sync.BUF;
                bs[nb] = eSrc[e];
                bd[nb++] = eDst[e];
            } else {
                im[ni] = res;
                is[ni] = eSrc[e];
                id[ni++] = eDst[e];
            }
        }
        return new GpuBackend.Barrier(src, dst, im, is, id, bu, bs, bd);
    }

    /** the state after site {@code s} ran with barrier {@code b} before it */
    private State apply(Site s, GpuBackend.Barrier b, State st) {
        State o = new State(st);
        if (b != null) {
            for (int k = 0; k < s.slots.length; k++) {
                int r = s.slots[k], res = slotRes[r];
                int da = memDst(b, res);
                if (da != 0)
                    o.vc[r] |= Sync.classesCovered(b.dstStages(), da);
            }
        }
        for (int k = 0; k < s.slots.length; k++) {
            int r = s.slots[k], m = s.codes[k];
            int wa = writeAccess(m);
            if (wa != 0) {
                o.ws[r] = stages(m);
                o.wa[r] = wa;
                o.rs[r] = 0;
                o.vc[r] = 0;
            } else {
                o.rs[r] |= stages(m);
            }
        }
        return o;
    }

    private static int memDst(GpuBackend.Barrier b, int res) {
        if ((res & Sync.BUF) != 0) {
            for (int i = 0; i < b.buffers().length; i++)
                if (b.buffers()[i] == (res & ~Sync.BUF))
                    return b.bufferDst()[i];
        } else {
            for (int i = 0; i < b.images().length; i++)
                if (b.images()[i] == res)
                    return b.imageDst()[i];
        }
        return 0;
    }

    // ================================================================================================ report

    /** the compiled schedule as text (debug dumps / tests): one line per site with its barrier */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Site s : sched) {
            StringBuilder sb = new StringBuilder();
            sb.append('#').append(s.idx).append(' ').append(s.name());
            if (s.region != 0)
                sb.append(" region ").append(s.region);
            if ((s.flags & MUST) != 0)
                sb.append(" must");
            if ((s.flags & REPEAT) != 0)
                sb.append(" repeat");
            sb.append(" ").append(describe(s));
            GpuBackend.Barrier b = s.barrier;
            if (b != null) {
                sb.append(" <- barrier ").append(Sync.stages(b.srcStages())).append(" -> ").append(Sync.stages(b.dstStages()));
                for (int i = 0; i < b.images().length; i++)
                    sb.append(" img ").append(b.images()[i]).append(' ').append(Integer.toHexString(b.imageSrc()[i])).append("->")
                            .append(Integer.toHexString(b.imageDst()[i]));
                for (int i = 0; i < b.buffers().length; i++)
                    sb.append(" buf ").append(b.buffers()[i]);
            }
            out.add(sb.toString());
        }
        return out;
    }
}
