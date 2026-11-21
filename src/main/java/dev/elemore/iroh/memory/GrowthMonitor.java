package dev.elemore.iroh.memory;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Iroh (rc9, td-598433): catches runaway growth before an external watchdog has to. Pure Java, fed once a second by
 * {@link MemoryGuard}; tested headless (memoryBudgetTest).
 * <ul>
 * <li>Memory rate: after the first 2 minutes of a world, Iroh's device memory growing by more than 1 GiB within 30 s
 *     is a spike (reported once per world).</li>
 * <li>Owner rate: one owner (creator + calling mod) whose live image count grows by more than 500 within 60 s is
 *     flagged; flagged owners are switched to "reuse or refuse" by the caller (no image for a NEW texture id).</li>
 * </ul>
 */
public final class GrowthMonitor {
    public static final long WARMUP_NS = 120_000_000_000L, MEM_WINDOW_NS = 30_000_000_000L, OWNER_WINDOW_NS = 60_000_000_000L;
    public static final long MEM_LIMIT = 1L << 30;
    public static final int OWNER_LIMIT = 500;

    private record Sample(long t, long bytes, Map<String, Integer> owners) {}

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final Set<String> flagged = new HashSet<>();
    private long worldStart = -1;
    private boolean spikeReported;

    /** Result of one sample: a memory spike (bytes grown) and owners newly flagged (with their growth). */
    public record Events(long spikeBytes, Map<String, Integer> newlyFlagged) {
        public boolean any() { return spikeBytes > 0 || !newlyFlagged.isEmpty(); }
    }

    public synchronized void worldStarted(long now) {
        worldStart = now;
        samples.clear();
        flagged.clear();
        spikeReported = false;
    }

    public synchronized void worldEnded() {
        worldStart = -1;
        samples.clear();
        flagged.clear();
        spikeReported = false;
    }

    public synchronized boolean isFlagged(String owner) {
        return flagged.contains(owner);
    }

    public synchronized Set<String> flagged() {
        return new HashSet<>(flagged);
    }

    public synchronized Events sample(long now, long irohBytes, Map<String, Integer> ownerCounts) {
        samples.addLast(new Sample(now, irohBytes, new HashMap<>(ownerCounts)));
        while (!samples.isEmpty() && now - samples.peekFirst().t() > OWNER_WINDOW_NS) samples.pollFirst();
        long spike = 0;
        Map<String, Integer> newly = new HashMap<>();
        if (worldStart < 0) return new Events(0, newly);
        boolean warm = now - worldStart >= WARMUP_NS;
        // memory: the oldest sample within the last 30 s
        if (warm && !spikeReported) {
            for (Sample s : samples) {
                if (now - s.t() <= MEM_WINDOW_NS) {
                    if (irohBytes - s.bytes() > MEM_LIMIT) { spike = irohBytes - s.bytes(); spikeReported = true; }
                    break;
                }
            }
        }
        // owners: growth against the oldest sample in the 60 s window (no warm-up: a world load creates textures in
        // bursts, but not 500 of one owner AND keep them while growing; the window catches steady leaks)
        Sample oldest = samples.peekFirst();
        if (oldest != null && oldest != samples.peekLast()) {
            for (var e : ownerCounts.entrySet()) {
                Integer b0 = oldest.owners().get(e.getKey());
                if (b0 == null) continue; // an owner new to the window (a resource reload, a new screen) is not growth yet
                int before = b0;
                int grown = e.getValue() - before;
                if (grown > OWNER_LIMIT && warm && flagged.add(e.getKey())) newly.put(e.getKey(), grown);
            }
        }
        return new Events(spike, newly);
    }
}
