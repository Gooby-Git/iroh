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
    private final List<Resident> toFree = new ArrayList<>();
    public int maxBuilds = 8, maxTriangles = 200_000;
    /** Residency radius in sections (3D, Chebyshev horizontally, half that vertically). */
    public int radius = 12;
    private int camX, camY, camZ;
    public int refusedThisFrame;

    public BlasBuildQueue(RtMemory memory, RtMemoryModel model) {
        this.memory = memory;
        this.model = model;
    }

    public void camera(int sx, int sy, int sz) {
        camX = sx;
        camY = sy;
        camZ = sz;
    }

    private boolean inRange(SectionGeometry g) {
        return Math.max(Math.abs(g.sx() - camX), Math.abs(g.sz() - camZ)) <= radius && Math.abs(g.sy() - camY) <= Math.max(2, radius / 2);
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
            if (old != null) toFree.add(old);
            out.add(new Pending(g, bytes));
            tris += t;
        }
        return out;
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

    public boolean isResident(int sx, int sy, int sz) {
        return resident.containsKey(SectionGeometry.key(sx, sy, sz));
    }
}
