package dev.elemore.iroh.perf;

/**
 * Iroh (perf/hitches): when a section upload (new or changed geometry) forces a full visibility walk with the cached
 * graph. Emptiness changes are picked up by every filter pass; only CONNECTIVITY (a section that became open lets the
 * walk reach sections behind it) needs the walk. While streaming, uploads land every frame, so the walk is limited to
 * one per {@code iroh.graph.refreshMs} (100 ms), except right after a player edit (the hole the player just dug must
 * show at once: {@code iroh.graph.editWindowMs}, 250 ms). Camera section changes are handled by SectionGraph itself.
 */
public final class GraphRefresh {
    static final long REFRESH_NS = Long.getLong("iroh.graph.refreshMs", 100L) * 1_000_000L;
    static final long EDIT_WINDOW_NS = Long.getLong("iroh.graph.editWindowMs", 250L) * 1_000_000L;

    private GraphRefresh() {}

    /** Pure rule (tested). Times in ns; {@code lastFullNs} / {@code lastEditNs} -1 = never. */
    public static boolean refreshNow(boolean structureDirty, long nowNs, long lastFullNs, long lastEditNs) {
        return refreshNow(structureDirty, nowNs, lastFullNs, lastEditNs, REFRESH_NS, EDIT_WINDOW_NS);
    }

    public static boolean refreshNow(boolean structureDirty, long nowNs, long lastFullNs, long lastEditNs, long refreshNs, long editWindowNs) {
        if (!structureDirty) return false;
        if (lastFullNs < 0) return true;
        if (lastEditNs >= 0 && nowNs - lastEditNs <= editWindowNs) return true;
        return nowNs - lastFullNs >= refreshNs;
    }
}
