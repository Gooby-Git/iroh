package net.vulkanmod.render.chunk.graph;

/**
 * P2-6 (lever L3): the section visibility walk on a worker thread (switch {@code iroh.graph.async}, default off).
 *
 * <p>The worker runs {@link VisibilityWalk#walk} (frustum free) for the camera section; the render thread only filters
 * the newest result by the frame's frustum. Rules (proof in VisibilityWalk, test in GraphAsyncTest):
 * <ol>
 *   <li><b>Turning never makes a result stale.</b> The walk does not depend on the view direction, so a result is
 *       used as is at any rotation, 180-degree flicks included.</li>
 *   <li><b>A result is only used for the camera section and grid layout it was walked for</b> (key = camera slot,
 *       grid reposition count, max direction changes; a switch flip discards everything posted before it). Until the worker delivers a result for the
 *       current key, the caller runs SectionGraph's own synchronous walk (exactly today's behaviour), so a section
 *       crossing costs what it costs today and never shows a stale set.</li>
 *   <li><b>Openings are repaired before use.</b> Every section input change on the render thread goes through
 *       {@link #inputChanged}; an OPENING (visibility bit added, non-empty -> empty) is logged with a sequence number.
 *       A request carries the sequence number at posting; the worker starts after the post (lock hand-off), so it sees
 *       every logged change up to that number and possibly some later values. Before a result is used, every opening
 *       logged after its number is replayed with {@link VisibilityWalk#relax}: the result then includes every arrival
 *       the current graph allows. A CLOSING needs nothing (the old result is a superset) and only asks for a fresh
 *       walk to drop the extra sections, at most every {@link #TIGHTEN_INTERVAL_NS}.</li>
 *   <li>A log overflow, a worker error or a key change discards results (fall back to rule 2).</li>
 * </ol>
 * So whenever a result is used, its filtered set contains every section SectionGraph's synchronous walk would draw that
 * frame: no visible section is dropped, at any turn speed. Extra sections (reachable only through out-of-frustum
 * sections, or through faces the first-arrival rule ignores) are drawn too; the test reports how many.
 *
 * <p>Threads: {@link #prepare}, {@link #inputChanged}, {@link #front} on the render thread only; the worker touches
 * only the request it took and the buffer it walks into. Hand-offs under {@link #lock}.
 */
public final class AsyncVisibility {
    static final long TIGHTEN_INTERVAL_NS = 100_000_000L;
    static final int LOG_CAP = 1 << 16;

    /** The instance that receives the input-change hook (one section grid at a time). */
    private static volatile AsyncVisibility active;

    /** Render-thread hook from RenderSection's input setters. */
    public static void inputChanged(int slot, boolean opening) {
        AsyncVisibility a = active;
        if (a != null && a.on)
            a.onInput(slot, opening);
    }

    private final VisibilityWalk.Graph graph;
    private final int n;
    /** null: the test drives the walk with {@link #runPendingWalk()}. */
    private final boolean threaded;

    // ---- render thread only ----
    private boolean on;
    private VisibilityWalk.Result front;
    private long seq;
    private int[] logSlot = new int[256];
    private long[] logSeq = new long[256];
    private int logCount;
    /** Results posted with a sequence number below this are not trusted (log overflowed after they were posted). */
    private long trustedFromSeq;
    private boolean closedSinceRequest;
    private long lastRequestNs;
    private final int[] work, inWork;
    private int workStamp = 1;

    // ---- shared, guarded by lock ----
    private final Object lock = new Object();
    private Request pending, inFlight;
    private VisibilityWalk.Result ready, spare;
    private Thread worker;

    // ---- stats (render thread writes, except walk ns) ----
    public long served, fallbacks, adopted, discarded, relaxedSections, openings, closings, requests, overflows;
    private volatile long walks, walkNsTotal, walkErrors;

    private record Request(long key0, long key1, int start, int maxDc, long seq) {}

    public AsyncVisibility(VisibilityWalk.Graph graph, boolean threaded) {
        this.graph = graph;
        this.n = graph.size();
        this.threaded = threaded;
        this.work = new int[this.n];
        this.inWork = new int[this.n];
    }

    /** Makes this instance the hook target (the newest section grid). */
    public void activate() {
        active = this;
    }

    /** A new section grid replaces the old one: the old instance stops taking hooks and its worker exits. */
    public static void retire() {
        AsyncVisibility a = active;
        if (a != null) {
            active = null;
            synchronized (a.lock) {
                a.pending = null;
                a.lock.notifyAll();
            }
        }
    }

    public boolean isOn() {
        return this.on;
    }

    /** Switch flip (render thread): drops every result, log and request; off also lets the worker thread exit. */
    public void setOn(boolean on) {
        if (on) active = this;
        else if (active == this) active = null;
        this.on = on;
        this.front = null;
        this.logCount = 0;
        this.trustedFromSeq = ++this.seq; // anything posted before the flip is discarded on arrival
        synchronized (this.lock) {
            this.pending = null;
            this.ready = null;
            this.lock.notifyAll(); // off: the worker sees active != this and exits
        }
    }

    private void onInput(int slot, boolean opening) {
        if (!opening) {
            this.closedSinceRequest = true;
            this.closings++;
            return;
        }
        this.openings++;
        if (this.logCount == LOG_CAP) {
            // nothing posted before now can be repaired: start over from a fresh walk
            this.overflows++;
            this.logCount = 0;
            this.front = null;
            this.trustedFromSeq = ++this.seq;
            return;
        }
        if (this.logCount == this.logSlot.length) {
            this.logSlot = java.util.Arrays.copyOf(this.logSlot, this.logCount * 2);
            this.logSeq = java.util.Arrays.copyOf(this.logSeq, this.logCount * 2);
        }
        this.logSlot[this.logCount] = slot;
        this.logSeq[this.logCount] = ++this.seq;
        this.logCount++;
    }

    /**
     * Render thread, once per graph update. Adopts a finished result for this key, repairs it with the openings logged
     * since its walk, and posts a request when needed. Returns true when {@link #front()} may be used this frame.
     */
    public boolean prepare(int startSlot, long layoutKey, int maxDc, long nowNs) {
        long key0 = ((long) startSlot << 32) | (maxDc & 0xFFFFFFFFL);
        long key1 = layoutKey;

        VisibilityWalk.Result got;
        long outstandingSeq = Long.MAX_VALUE;
        boolean outstandingForKey = false;
        synchronized (this.lock) {
            got = this.ready;
            this.ready = null;
            Request p = this.pending, f = this.inFlight;
            if (p != null) {
                outstandingForKey = p.key0 == key0 && p.key1 == key1 && p.seq >= this.trustedFromSeq;
                outstandingSeq = p.seq;
            }
            if (f != null) {
                outstandingForKey |= f.key0 == key0 && f.key1 == key1 && f.seq >= this.trustedFromSeq;
                outstandingSeq = Math.min(outstandingSeq, f.seq);
            }
        }
        if (got != null) {
            if (got.key0 == key0 && got.key1 == key1 && got.seq >= this.trustedFromSeq) {
                recycle(this.front);
                this.front = got;
                got.appliedSeq = got.seq;
                this.adopted++;
            } else {
                recycle(got);
                this.discarded++;
            }
        }
        if (this.front != null && (this.front.key0 != key0 || this.front.key1 != key1)) {
            recycle(this.front);
            this.front = null;
        }

        if (this.front != null)
            replayOpenings(this.front);
        trimLog(this.front != null ? this.front.appliedSeq : Long.MAX_VALUE, outstandingSeq);

        // requests: a walk for this key when there is no result, or a tightening walk after closings
        boolean want = this.front == null ? !outstandingForKey
                : this.closedSinceRequest && outstandingSeq == Long.MAX_VALUE && nowNs - this.lastRequestNs >= TIGHTEN_INTERVAL_NS;
        if (want) {
            post(new Request(key0, key1, startSlot, maxDc, this.seq));
            this.closedSinceRequest = false;
            this.lastRequestNs = nowNs;
        }

        if (this.front == null) {
            this.fallbacks++;
            return false;
        }
        this.served++;
        return true;
    }

    /** The result to filter this frame (valid after {@link #prepare} returned true), in level order. */
    public VisibilityWalk.Result front() {
        return this.front;
    }

    /** GraphAsyncTest negative control only: skip the opening repair (must make the oracle fail). */
    static boolean testSkipReplay;

    private void replayOpenings(VisibilityWalk.Result r) {
        if (testSkipReplay) {
            r.appliedSeq = this.seq;
            return;
        }
        for (int k = 0; k < this.logCount; k++) {
            if (this.logSeq[k] <= r.appliedSeq)
                continue;
            int ws = (this.workStamp += 2);
            this.relaxedSections += VisibilityWalk.relax(this.graph, r, this.logSlot[k], this.work, this.inWork, ws);
        }
        r.appliedSeq = this.seq;
        VisibilityWalk.mergeAdded(r);
    }

    /** Drops log entries every holder of the log already includes. */
    private void trimLog(long frontSeq, long outstandingSeq) {
        long keepAbove = Math.min(frontSeq, outstandingSeq);
        if (keepAbove == Long.MAX_VALUE) {
            this.logCount = 0;
            return;
        }
        int w = 0;
        for (int k = 0; k < this.logCount; k++) {
            if (this.logSeq[k] > keepAbove) {
                this.logSlot[w] = this.logSlot[k];
                this.logSeq[w] = this.logSeq[k];
                w++;
            }
        }
        this.logCount = w;
    }

    private void recycle(VisibilityWalk.Result r) {
        if (r == null) return;
        synchronized (this.lock) {
            if (this.spare == null) this.spare = r;
        }
    }

    private void post(Request q) {
        this.requests++;
        synchronized (this.lock) {
            this.pending = q; // latest wins: an older request not yet started is dropped
            if (this.threaded) {
                if (this.worker == null || !this.worker.isAlive()) {
                    this.worker = new Thread(this::workerLoop, "Iroh-VisGraph");
                    this.worker.setDaemon(true);
                    this.worker.start();
                }
                this.lock.notifyAll();
            }
        }
    }

    private void workerLoop() {
        while (true) {
            Request q;
            synchronized (this.lock) {
                long idleSince = System.nanoTime();
                while (this.pending == null) {
                    // a newer grid took over, the switch is off, or nothing asked for 10 s (title screen): exit,
                    // post() starts a new worker when needed
                    if (active != this || System.nanoTime() - idleSince > 10_000_000_000L) {
                        this.worker = null;
                        return;
                    }
                    try {
                        this.lock.wait(1000);
                    } catch (InterruptedException e) {
                        this.worker = null;
                        return;
                    }
                }
                q = this.pending;
                this.pending = null;
                this.inFlight = q;
            }
            walkInto(q);
        }
    }

    /** Test hook (non-threaded mode): runs the pending request now, as the worker would. Returns false if none. */
    boolean runPendingWalk() {
        Request q;
        synchronized (this.lock) {
            q = this.pending;
            if (q == null) return false;
            this.pending = null;
            this.inFlight = q;
        }
        walkInto(q);
        return true;
    }

    private void walkInto(Request q) {
        VisibilityWalk.Result buf = null;
        boolean ok = false;
        long t0 = System.nanoTime();
        try {
            synchronized (this.lock) {
                buf = this.spare;
                this.spare = null;
            }
            if (buf == null) buf = new VisibilityWalk.Result(this.n);
            VisibilityWalk.walk(this.graph, q.start, q.maxDc, buf);
            ok = true;
        } catch (Throwable e) {
            // RuntimeException: the grid was repositioned under the walk (key no longer matches anyway);
            // an Error (OOM) must not leave inFlight set, or no request would ever be posted again
            this.walkErrors++;
            if (buf == null) {
                synchronized (this.lock) {
                    this.inFlight = null;
                }
                if (e instanceof Error err) throw err;
                return;
            }
        }
        long ns = System.nanoTime() - t0;
        buf.key0 = q.key0;
        buf.key1 = q.key1;
        buf.seq = q.seq;
        buf.walkNs = ns;
        synchronized (this.lock) {
            this.inFlight = null;
            if (ok) {
                if (this.ready != null && this.spare == null) this.spare = this.ready;
                this.ready = buf;
                this.walks++;
                this.walkNsTotal += ns;
            } else if (this.spare == null) {
                this.spare = buf;
            }
        }
    }

    public String stats() {
        long w = this.walks;
        return String.format("served=%d fallback=%d adopted=%d discarded=%d requests=%d walks=%d walkMsAvg=%.2f walkErrors=%d"
                        + " openings=%d closings=%d relaxed=%d logOverflows=%d",
                this.served, this.fallbacks, this.adopted, this.discarded, this.requests, w,
                w == 0 ? 0.0 : this.walkNsTotal / 1e6 / w, this.walkErrors, this.openings, this.closings,
                this.relaxedSections, this.overflows);
    }
}
