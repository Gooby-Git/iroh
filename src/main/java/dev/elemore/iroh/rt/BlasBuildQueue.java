package dev.elemore.iroh.rt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The BLAS build queue (Minecraft-free; render thread). Implements {@link RtGeometryFeed}: built sections are queued
 * (latest version wins, a reset cancels the pending build and frees the resident BLAS), and {@link #take} hands out
 * the builds for this frame within a per-frame budget: at most {@code maxBuilds} sections and {@code maxTriangles}
 * triangles (a time proxy: build cost is linear in triangles), nearest the camera first, sections outside the
 * residency radius never built. Memory goes through {@link RtMemory} (owner "iroh-rt"): a refused reservation leaves
 * the section queued (retried next frame), it is never forced.
 */
public final class BlasBuildQueue implements RtGeometryFeed {
    public record Pending(SectionGeometry g, long bytes) {}

    /** A section with a BLAS (or one being built this frame). */
    public record Resident(long key, long bytes, long version) {}

    private final RtMemory memory;
    private final RtMemoryModel model;
    private final Map<Long, SectionGeometry> pending = new HashMap<>();
    private final Map<Long, Resident> resident = new HashMap<>();
    /** R1-REV: the geometry each resident was built from, re-queued when the camera leaves it behind (see {@link #camera}) */
    private final Map<Long, SectionGeometry> builtFrom = new HashMap<>();
    private final List<Resident> toFree = new ArrayList<>();
    public int maxBuilds = 8, maxTriangles = 200_000;
    /** Residency radius in sections (3D, Chebyshev horizontally, half that vertically). */
    public int radius = 12;
    /** R1-REV: a resident is evicted only past radius + this (a camera on a section edge does not rebuild a slab per step) */
    public static final int EVICT_MARGIN = 1;
    private int camX, camY, camZ;
    private boolean camSet;
    public int refusedThisFrame;

    public BlasBuildQueue(RtMemory memory, RtMemoryModel model) {
        this.memory = memory;
        this.model = model;
    }

    /**
     * The camera section for this frame. R1-REV (review 5cb221b #1): when it moved, residents farther than the residency
     * radius + {@link #EVICT_MARGIN} are evicted (BLAS freed by the next {@link #drainFrees}, memory released) and their
     * geometry goes back to the pending queue (rebuilt if the camera returns). Before this, a resident left only on a
     * section reset (render distance, never vertical), so residency grew with every section ever visited.
     */
    public void camera(int sx, int sy, int sz) {
        boolean moved = !camSet || sx != camX || sy != camY || sz != camZ;
        camX = sx;
        camY = sy;
        camZ = sz;
        camSet = true;
        if (!moved || resident.isEmpty()) return;
        for (Resident r : new ArrayList<>(resident.values())) {
            SectionGeometry g = builtFrom.get(r.key());
            if (g == null || within(g.sx(), g.sy(), g.sz(), EVICT_MARGIN)) continue;
            resident.remove(r.key());
            builtFrom.remove(r.key());
            toFree.add(r);
            pending.merge(r.key(), g, (queued, built) -> queued.version() >= built.version() ? queued : built);   // newest wins
        }
    }

    private boolean inRange(SectionGeometry g) {
        return within(g.sx(), g.sy(), g.sz(), 0);
    }

    /** within the residency box grown by {@code margin} sections (Chebyshev horizontally, half the radius (min 2) vertically) */
    private boolean within(int sx, int sy, int sz, int margin) {
        return Math.max(Math.abs(sx - camX), Math.abs(sz - camZ)) <= radius + margin && Math.abs(sy - camY) <= Math.max(2, radius / 2) + margin;
    }

    /** Most residents this queue can hold at {@code radius}: the residency box grown by {@link #EVICT_MARGIN}. */
    public static long maxResident(int radius) {
        long h = 2L * (radius + EVICT_MARGIN) + 1, v = 2L * (Math.max(2, radius / 2) + EVICT_MARGIN) + 1;
        return h * h * v;
    }

    private int distance(SectionGeometry g) {
        int dx = g.sx() - camX, dy = g.sy() - camY, dz = g.sz() - camZ;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public void sectionBuilt(SectionGeometry g) {
        SectionGeometry old = pending.get(g.key());
        if (old != null && old.version() > g.version()) return; // stale
        if (g.empty()) {
            pending.remove(g.key());
            evict(g.key());
            return;
        }
        pending.put(g.key(), g);
    }

    @Override
    public void sectionReset(int sx, int sy, int sz) {
        long k = SectionGeometry.key(sx, sy, sz);
        pending.remove(k);
        evict(k);
    }

    @Override
    public void clear() {
        pending.clear();
        for (Resident r : new ArrayList<>(resident.values())) evict(r.key());
    }

    private void evict(long key) {
        builtFrom.remove(key);
        Resident r = resident.remove(key);
        if (r != null) toFree.add(r);
    }

    /** Builds for this frame (caller records them, then {@link #built}). Memory for each is reserved here. */
    public List<Pending> take() {
        refusedThisFrame = 0;
        List<SectionGeometry> order = new ArrayList<>(pending.values());
        order.removeIf(g -> !inRange(g));
        order.sort((a, b) -> Integer.compare(distance(a), distance(b)));
        List<Pending> out = new ArrayList<>();
        long tris = 0;
        for (SectionGeometry g : order) {
            if (out.size() >= maxBuilds) break;
            long t = g.totalQuads() * 2L;
            if (!out.isEmpty() && tris + t > maxTriangles) break;
            long bytes = model.blasBytes(g.totalQuads());
            if (!memory.reserve(bytes)) {           // the old BLAS (if any) stays until the new one replaces it
                refusedThisFrame++;
                continue;
            }
            pending.remove(g.key());
            Resident old = resident.put(g.key(), new Resident(g.key(), bytes, g.version()));
            builtFrom.put(g.key(), g);
            if (old != null) toFree.add(old);
            out.add(new Pending(g, bytes));
            tris += t;
        }
        return out;
    }

    /**
     * R1-REV (review #7): the BLAS of {@code r} was built and really takes {@code actualBytes}; the reservation follows the
     * real size (the model is an estimate). Growth is charged even past the cap: the memory exists, later reservations
     * are refused until it is released. Returns the record that now stands for the resident (release uses its bytes).
     */
    public Resident reconcile(Resident r, long actualBytes) {
        if (actualBytes < 0 || actualBytes == r.bytes() || !r.equals(resident.get(r.key()))) return r;
        if (actualBytes > r.bytes()) memory.charge(actualBytes - r.bytes());
        else memory.release(r.bytes() - actualBytes);
        Resident n = new Resident(r.key(), actualBytes, r.version());
        resident.put(r.key(), n);
        return n;
    }

    /** BLASes to destroy (after the GPU finished frames that used them); their memory is released here. */
    public List<Resident> drainFrees() {
        List<Resident> r = new ArrayList<>(toFree);
        toFree.clear();
        for (Resident x : r) memory.release(x.bytes());
        return r;
    }

    public int pendingCount() {
        return pending.size();
    }

    public int residentCount() {
        return resident.size();
    }

    /** RT-1: keys of the resident sections (SectionGeometry.key), a snapshot, for the world capture. */
    public long[] residentKeys() {
        return resident.keySet().stream().mapToLong(Long::longValue).toArray();
    }

    public boolean isResident(int sx, int sy, int sz) {
        return resident.containsKey(SectionGeometry.key(sx, sy, sz));
    }
}
