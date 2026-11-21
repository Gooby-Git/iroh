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
    /** Refusals that found their chunk ready by the time they were parked (the lost-wake-up race, closed). */
    public volatile long lateReady;
    /** Chunk-ready events nobody was waiting for (no graph update requested). */
    public volatile long idleEvents;

    public RebuildWaiting() {
        this.parked.defaultReturnValue(0);
    }

    /**
     * A section of {@code chunkKey} was refused. Parks it unless the chunk turned ready since the refusal: the readiness
     * check runs under the same lock as {@link #chunkReady}, so a ready event is either seen here (not parked, returns
     * false: the caller must request a graph update, the section stays dirty and is simply tried again) or arrives
     * after this call and finds the section parked and wakes it. A section can therefore never be parked behind an event
     * that already fired (rc3v: waitingButChunkReady stuck above 0).
     */
    public synchronized boolean park(long chunkKey, java.util.function.BooleanSupplier chunkIsReady) {
        if (chunkIsReady.getAsBoolean()) {
            this.lateReady++;
            return false;
        }
        this.parked.addTo(chunkKey, 1);
        this.refused++;
        return true;
    }

    /** Park without the late check (tests of the counters only). */
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
     * entry stays while sections are parked under it (see the comment in the method).
     */
    public synchronized boolean chunkReady(long chunkKey) {
        if (this.parked.containsKey(chunkKey)) {
            // The key stays until every section parked under it has been retried (unpark) or relocated away (forget): the chunk
            // may flap (ready, unloaded, ready again) before the graph reaches the section, and that second event must wake it too.
            this.wakeups++;
            return true;
        }
        this.idleEvents++;
        return false;
    }

    /** A parked section was relocated by the section grid (RenderSection.reset): drop it without counting a retry. */
    public synchronized void forget(long chunkKey) {
        int n = this.parked.get(chunkKey);
        if (n <= 1)
            this.parked.remove(chunkKey);
        else
            this.parked.put(chunkKey, n - 1);
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
