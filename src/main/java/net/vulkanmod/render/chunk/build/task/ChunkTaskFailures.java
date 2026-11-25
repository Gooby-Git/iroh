package net.vulkanmod.render.chunk.build.task;

import net.vulkanmod.Initializer;

import java.util.HashMap;
import java.util.Map;

/**
 * R-P213BC (RQ-P213-b): what a builder thread does when a chunk task throws, so the thread survives. The failure is
 * logged with the section position (full stack for the first occurrence of a class + message, a one-line count at 10,
 * 100, 1000, ... after that), and the section is marked dirty again for at most {@link #MAX_RETRIES} attempts: a mesh
 * that throws every time is then dropped (it keeps what it had) instead of re-queueing on every graph update.
 * Thread-safe; builders call {@link #taskFailed} concurrently.
 */
final class ChunkTaskFailures {
    static final int MAX_RETRIES = 2;
    private static final int MAX_TRACKED = 4096;

    private final Map<String, int[]> signatures = new HashMap<>();
    private final Map<Long, int[]> sections = new HashMap<>();
    /** Tests read these. */
    volatile int logged, retried, dropped;
    private volatile boolean any;

    void taskFailed(ChunkTask task, Throwable t) {
        long key = task.sectionKey();
        this.any = true;
        int attempt;
        synchronized (this.sections) {
            if (this.sections.size() >= MAX_TRACKED)
                this.sections.clear();
            attempt = ++this.sections.computeIfAbsent(key, k -> new int[1])[0];
        }

        String sig = task.name() + "|" + t.getClass().getName() + "|" + t.getMessage();
        int count;
        synchronized (this.signatures) {
            if (this.signatures.size() >= MAX_TRACKED)
                this.signatures.clear();
            count = ++this.signatures.computeIfAbsent(sig, k -> new int[1])[0];
        }
        if (isPowerOfTen(count)) {
            this.logged++;
            if (count == 1)
                Initializer.LOGGER.error("Iroh: chunk task {} failed at section {} (builder thread kept alive)", task.name(), task.describeSection(), t);
            else
                Initializer.LOGGER.error("Iroh: chunk task {} failed again at section {}: {} x {}: {}", task.name(), task.describeSection(),
                        count, t.getClass().getName(), t.getMessage());
        }

        if (attempt <= MAX_RETRIES) {
            this.retried++;
            try {
                task.markForRebuild();
            } catch (Throwable ignored) {
                // marking is best effort: the render side may be gone (world unloading)
            }
        } else {
            this.dropped++;
        }
    }

    /** Called when a section's mesh finished fine: its failure count starts over. */
    void succeeded(ChunkTask task) {
        if (!this.any)
            return; // nothing ever failed: no lock on the hot path
        synchronized (this.sections) {
            this.sections.remove(task.sectionKey());
        }
    }

    static boolean isPowerOfTen(int n) {
        while (n >= 10 && n % 10 == 0)
            n /= 10;
        return n == 1;
    }
}
