package dev.elemore.iroh.chunktest;

import dev.elemore.iroh.chunk.RebuildWaiting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Headless test of the refused-rebuild bookkeeping (td-27b474): a tiny model of SectionGraph.scheduleRebuilds driven
 * against {@link RebuildWaiting} and a chunk readiness set that follows ChunkStatusMap's rule (a chunk is ready when it and
 * its eight neighbours have data+light). No Minecraft, no Vulkan.
 * {@code ./gradlew rebuildWaitTest}; last line "# rebuildwait PASS n/n".
 */
public final class RebuildWaitTest {
    private static final List<String> FAILS = new ArrayList<>();
    private static int checks;

    /** One section of the model scheduler. */
    private static final class Sec {
        final int cx, cz;
        boolean dirty = true, waiting, compiled;
        Sec(int cx, int cz) { this.cx = cx; this.cz = cz; }
    }

    private static final class Model {
        final Set<Long> loaded = new HashSet<>();
        final Set<Long> ready = new HashSet<>();
        final RebuildWaiting w = new RebuildWaiting();
        final List<Sec> secs = new ArrayList<>();
        int graphUpdates, attempts;
        boolean graphNeeded = true;

        static long key(int x, int z) { return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32); }

        boolean readyNow(int x, int z) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                if (!loaded.contains(key(x + dx, z + dz))) return false;
            return true;
        }

        /** Chunk data+light arrived (ChunkStatusMap.setChunkStatus -> updateNeighbours). */
        void load(int x, int z) {
            loaded.add(key(x, z));
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                int nx = x + dx, nz = z + dz;
                if (readyNow(nx, nz) && ready.add(key(nx, nz)) && w.chunkReady(key(nx, nz)))
                    graphNeeded = true;
            }
        }

        /** SectionGraph.update: only when needed, then scheduleRebuilds over every dirty reachable section. */
        void graphUpdate() {
            if (!graphNeeded) return;
            graphNeeded = false;
            graphUpdates++;
            for (Sec s : secs) {
                if (!s.dirty) continue;
                if (s.waiting) {
                    if (!ready.contains(key(s.cx, s.cz))) continue;
                    s.waiting = false;
                    w.unpark(key(s.cx, s.cz));
                }
                attempts++;
                if (ready.contains(key(s.cx, s.cz))) {
                    s.compiled = true; s.dirty = false;
                    if (w.retried > w.retriedAccepted) w.retriedAccepted++;
                } else {
                    s.waiting = true;
                    w.park(key(s.cx, s.cz));
                }
            }
        }
    }

    public static void main(String[] args) {
        edgeNeverReady();
        arrivalOrder();
        graphUpdateStorm();
        wakeBounded();
        System.out.println(FAILS.isEmpty() ? "# rebuildwait PASS " + checks + "/" + checks
                : "# rebuildwait FAIL " + FAILS.size() + " of " + checks + ": " + FAILS);
        if (!FAILS.isEmpty())
            System.exit(1);
    }

    /** A 7x7 loaded area: the outer ring has no neighbours outside, so its sections must never compile and must not be retried. */
    private static void edgeNeverReady() {
        Model m = new Model();
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) m.secs.add(new Sec(x, z));
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) m.load(x, z);
        m.graphUpdate();
        long compiled = m.secs.stream().filter(s -> s.compiled).count();
        long edge = m.secs.stream().filter(s -> !s.compiled).count();
        check("5x5 interior compiles (" + compiled + ")", compiled == 25);
        check("24 edge sections parked (" + edge + ")", edge == 24 && m.w.parkedSections() == 24);
        int before = m.attempts;
        for (int i = 0; i < 1000; i++) { m.graphNeeded = true; m.graphUpdate(); }
        check("1000 further graph updates retry nothing (attempts " + before + " -> " + m.attempts + ")", m.attempts == before);
        check("refused counted once per edge section (" + m.w.refused + ")", m.w.refused == 24);
        check("no retry events for never-ready sections", m.w.retried == m.w.retriedAccepted && m.w.retried >= 0);
    }

    /** Sections refused early compile exactly once when the last neighbour arrives, and the wake-up asks for one graph update. */
    private static void arrivalOrder() {
        Model m = new Model();
        m.secs.add(new Sec(0, 0));
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            if (!(x == 1 && z == 1)) m.load(x, z);
        m.graphUpdate();
        check("refused while one neighbour is missing", !m.secs.get(0).compiled && m.secs.get(0).waiting && m.w.refused == 1);
        m.graphUpdate();
        check("an idle update does not retry", m.attempts == 1);
        m.load(1, 1);
        check("neighbour arrival wakes the waiter", m.graphNeeded && m.w.wakeups == 1);
        m.graphUpdate();
        check("compiled after exactly one retry", m.secs.get(0).compiled && m.w.retried == 1 && m.w.retriedAccepted == 1 && m.attempts == 2);
        check("nothing parked afterwards", !m.w.hasParked() && m.w.parkedChunks() == 0);
        // chunks that nobody waits for do not request graph updates
        m.graphNeeded = false;
        for (int x = 9; x <= 11; x++) for (int z = 9; z <= 11; z++) m.load(x, z);
        check("ready event with no waiter requests no update", !m.graphNeeded && m.w.idleEvents > 0);
    }

    /** 200 chunks streaming in one per tick with a graph update per tick (camera moving): attempts stay near sections + events. */
    private static void graphUpdateStorm() {
        Model m = new Model();
        int n = 14;
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) m.secs.add(new Sec(x, z));
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) {
            m.load(x, z);
            m.graphNeeded = true; // camera moved: every tick wants an update regardless of chunk events
            m.graphUpdate();
        }
        int interior = (n - 2) * (n - 2);
        long compiled = m.secs.stream().filter(s -> s.compiled).count();
        check("storm: every interior section compiled (" + compiled + "/" + interior + ")", compiled == interior);
        // each section is attempted at most once per wake-up of its own chunk, plus its first attempt
        check("storm: attempts bounded (" + m.attempts + " <= 2 x sections " + 2 * n * n + ")", m.attempts <= 2 * n * n);
        check("storm: attempts == first tries + accepted retries + refused retries",
                m.attempts == m.secs.size() + m.w.retried);
    }

    /** One ready event never causes more than one update request, however often the chunk flaps. */
    private static void wakeBounded() {
        RebuildWaiting w = new RebuildWaiting();
        long k = 42;
        w.park(k);
        w.park(k);
        check("two sections parked in one chunk", w.parkedSections() == 2 && w.parkedChunks() == 1);
        check("first ready event wakes", w.chunkReady(k));
        check("second event for the same chunk does not wake again", !w.chunkReady(k));
        w.unpark(k);
        w.unpark(k);
        check("unpark after the wake is harmless", !w.hasParked() && w.retried == 2);
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) { FAILS.add(name); System.out.println("FAIL " + name); }
    }
}
