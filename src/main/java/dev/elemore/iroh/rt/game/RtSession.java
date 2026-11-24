package dev.elemore.iroh.rt.game;

import dev.elemore.iroh.rt.*;

import java.util.*;

/**
 * P5-2: the game adapter's core, Minecraft-free (offline-tested with a fake backend in RtSeamTest; the game uses
 * {@link VkRtBackend}). Owns the BLAS queue (fed by the section listener), the residency of section BLASes, the
 * render-size RT targets, and the per-frame TLAS. All device memory goes through one {@link RtMemory} (owner
 * {@value RtMemory#OWNER}): BLAS estimates are reserved by the queue, targets here; everything is released on world
 * unload, resize (targets) and disable, so the owner returns to 0 (the lifecycle test checks it).
 * Render thread only.
 */
public final class RtSession {
    /** What the session needs from the GPU side. Handles are opaque non-zero longs. */
    public interface Backend {
        /** records a BLAS build of {@code ranges}; returns the handle, or 0 when nothing could be built */
        long buildBlas(SectionGeometry g, List<RtSectionSource.Range> ranges);
        /** destroys a BLAS after the frames that may use it (the backend defers by frames in flight) */
        void destroyBlas(long handle);
        /** records this frame's TLAS over the resident BLASes; {@code sections[i]} = {sx, sy, sz} of {@code blas[i]} */
        void buildTlas(long[] blas, int[][] sections, int count);
        /** (re)creates the render-size RT targets (G-buffer attachment + RT buffers); w = h = 0 frees them */
        void targets(int w, int h);
        /** live objects (BLAS + TLAS + targets) and bytes: 0/0 after {@link #close} and a flush */
        int liveObjects();
        long liveBytes();
        void close();
        /** R1-REV: the real device bytes of a built BLAS handle (storage), or -1 when unknown (the estimate stays) */
        default long blasBytes(long handle) {
            return -1;
        }
    }

    /** Per-frame numbers (the recorded "sections arriving and leaving" run prints them). */
    public record FrameStats(int built, long triangles, int resident, int pending, int refused, int freed, long usedBytes) {}

    public final BlasBuildQueue queue;
    private final RtMemory memory;
    private final RtSectionSource source;
    private final Backend backend;
    private final Map<BlasBuildQueue.Resident, Long> handles = new HashMap<>();
    private final Map<Long, int[]> where = new HashMap<>();
    private long targetBytes;
    private int w, h;
    private boolean enabled;

    public RtSession(RtSettings settings, RtMemory memory, RtMemoryModel model, RtSectionSource source, Backend backend) {
        this.memory = memory;
        this.source = source;
        this.backend = backend;
        this.queue = new BlasBuildQueue(memory, model);
        this.queue.radius = RtLimits.residency();   // chunks, independent of render distance (P5-PLAN 4); R1-HANG safe mode: 2
        RtPreset p = settings == null ? RtPreset.MEDIUM : settings.preset();
        this.queue.maxBuilds = RtLimits.maxBuilds(p);         // R1-HANG: the per-submit work bound (one frame = one command buffer)
        this.queue.maxTriangles = RtLimits.maxTriangles(p);
    }

    /** Bytes of the render-size RT targets: G-buffer attachment (16 B/px) + RT working buffers (~64 B/px, P5-PLAN 4). */
    public static long targetBytes(int w, int h) {
        return (long) w * h * (RtGBuffer.BYTES_PER_PIXEL + 64);
    }

    /** Start (or restart) at render size w x h. Returns false when the targets were refused (memory): RT stays off. */
    public boolean enable(int w, int h) {
        if (enabled) return true;
        if (!sizeTargets(w, h)) return false;
        enabled = true;
        return true;
    }

    private boolean sizeTargets(int nw, int nh) {
        if (targetBytes > 0) {
            backend.targets(0, 0);
            memory.release(targetBytes);
            targetBytes = 0;
        }
        long b = targetBytes(nw, nh);
        if (!memory.reserve(b)) return false;
        targetBytes = b;
        w = nw;
        h = nh;
        backend.targets(nw, nh);
        return true;
    }

    /** Render size changed (window resize or render scale): targets only; BLASes are size-independent. */
    public boolean resize(int nw, int nh) {
        if (!enabled || (nw == w && nh == h)) return true;
        return sizeTargets(nw, nh);
    }

    /** Once per frame, before the RT passes: frees, this frame's builds within budget, the TLAS. */
    public FrameStats frame(int camSx, int camSy, int camSz) {
        if (!enabled) return new FrameStats(0, 0, 0, 0, 0, 0, memory.usedBytes());
        int freed = drainFrees();
        queue.camera(camSx, camSy, camSz);
        int built = 0;
        long tris = 0;
        for (BlasBuildQueue.Pending p : queue.take()) {
            SectionGeometry g = p.g();
            List<RtSectionSource.Range> r = source.ranges(g.sx(), g.sy(), g.sz());
            long hd = r.isEmpty() ? 0 : backend.buildBlas(g, r);
            BlasBuildQueue.Resident res = new BlasBuildQueue.Resident(g.key(), p.bytes(), g.version());
            if (hd == 0) {                       // section vanished between the event and the build: drop it
                queue.sectionReset(g.sx(), g.sy(), g.sz());
                continue;
            }
            res = queue.reconcile(res, backend.blasBytes(hd));   // R1-REV: the owner counts the real BLAS size, not the model's
            handles.put(res, hd);
            where.put(hd, new int[]{g.sx(), g.sy(), g.sz()});
            built++;
            tris += g.totalQuads() * 2L;
        }
        freed += drainFrees();
        long[] blas = new long[handles.size()];
        int[][] sec = new int[handles.size()][];
        int n = 0;
        for (long hd : handles.values()) {
            blas[n] = hd;
            sec[n++] = where.get(hd);
        }
        backend.buildTlas(blas, sec, n);
        return new FrameStats(built, tris, queue.residentCount(), queue.pendingCount(), queue.refusedThisFrame, freed, memory.usedBytes());
    }

    private int drainFrees() {
        int n = 0;
        for (BlasBuildQueue.Resident r : queue.drainFrees()) {
            Long hd = handles.remove(r);
            if (hd != null) {
                where.remove(hd);
                backend.destroyBlas(hd);
                n++;
            }
        }
        return n;
    }

    /** World unload / dimension change: every BLAS goes, targets stay (same window). */
    public void worldUnloaded() {
        queue.clear();
        drainFrees();
        backend.buildTlas(new long[0], new int[0][], 0);
    }

    /** RT switched off (Look switch, failure, shutdown): everything is released; the owner returns to 0. */
    public void disable() {
        // R1-REV (review 5cb221b #2): each step runs even when an earlier one throws (a failed frame left the backend
        // half-built); the first failure is rethrown after the rest released what it could
        enabled = false;
        try {
            worldUnloaded();
        } finally {
            try {
                if (targetBytes > 0) {
                    long b = targetBytes;
                    targetBytes = 0;
                    memory.release(b);
                    backend.targets(0, 0);
                }
            } finally {
                backend.close();
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public long usedBytes() {
        return memory.usedBytes();
    }
}
