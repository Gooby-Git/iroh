package dev.elemore.iroh.chunk;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Bookkeeping for rebuilds the section scheduler refused (td-27b474). A rebuild is refused for exactly one reason: the
 * section's chunk is not render-ready (it, or one of its eight neighbour chunks, is missing data or light; see
 * ChunkStatusMap). Such a section stays dirty and is parked here, keyed by chunk. It is retried only when that chunk
 * turns render-ready, never per graph update and never per frame; sections at the edge of the loaded area whose
 * neighbours never arrive therefore stay parked, cost nothing, and are the vanilla behaviour (vanilla also never builds
 * a section without all neighbour chunks).
 *
 * <p>Pure Java (no Minecraft, no Vulkan) so {@code ./gradlew rebuildWaitTest} can run it headless. Thread safe: the
 * chunk status is updated from packet handling, the scheduler runs on the render thread.
 */
public final class RebuildWaiting {
    private final Long2IntOpenHashMap parked = new Long2IntOpenHashMap();

    // counters for the [iroh-rebuild-stats] line (read from the render thread; approximate across threads)
    /** Sections parked because their chunk was not render-ready ("refused, waiting for neighbour"). */
    public volatile long refused;
    /** Parked sections the scheduler tried again because their chunk turned ready ("retried"). */
    public volatile long retried;
    /** Retries that were accepted by the dispatcher. */
    public volatile long retriedAccepted;
    /** Chunk-ready events that found parked sections and asked for a graph update. */
    public volatile long wakeups;
    /** Chunk-ready events nobody was waiting for (no graph update requested). */
    public volatile long idleEvents;

    public RebuildWaiting() {
        this.parked.defaultReturnValue(0);
    }

    /** A section of {@code chunkKey} was refused and is now parked. */
    public synchronized void park(long chunkKey) {
        this.parked.addTo(chunkKey, 1);
        this.refused++;
    }

    /** A parked section of {@code chunkKey} is leaving the parked state for a retry. */
    public synchronized void unpark(long chunkKey) {
        int n = this.parked.get(chunkKey);
        if (n <= 1)
            this.parked.remove(chunkKey);
        else
            this.parked.put(chunkKey, n - 1);
        this.retried++;
    }

    /**
     * {@code chunkKey} just turned render-ready. Returns true when sections were waiting for it, i.e. the caller must
     * schedule one graph update; false means nothing to do. Never loops: one event, at most one update request. The chunk's
     * entry is dropped (a retry that is refused again parks again; a section that is never reached keeps its flag and retries
     * when the graph reaches it).
     */
    public synchronized boolean chunkReady(long chunkKey) {
        if (this.parked.remove(chunkKey) > 0) {
            // the key is dropped now; each parked section re-checks readiness itself when the graph reaches it and parks again if needed
            this.wakeups++;
            return true;
        }
        this.idleEvents++;
        return false;
    }

    public synchronized boolean hasParked() {
        return !this.parked.isEmpty();
    }

    public synchronized int parkedSections() {
        int n = 0;
        for (int v : this.parked.values())
            n += v;
        return n;
    }

    public synchronized int parkedChunks() {
        return this.parked.size();
    }

    public synchronized boolean isChunkParked(long chunkKey) {
        return this.parked.containsKey(chunkKey);
    }
}
