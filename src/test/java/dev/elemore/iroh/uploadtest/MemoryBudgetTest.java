package dev.elemore.iroh.uploadtest;

import dev.elemore.iroh.memory.MemoryBudget;

import static dev.elemore.iroh.memory.MemoryBudget.GiB;
import static dev.elemore.iroh.memory.MemoryBudget.MiB;

/**
 * rc8 (SYSTEM SAFETY rule 9): the device-memory policy state machine with fake numbers: default cap, configured cap,
 * driver-budget bound with the 1.5 GiB desktop reserve, WARN at 85% (once per episode), CAPPED refusing optional
 * allocations only, OUT_OF_MEMORY sticky (every allocation refused, no retry) until reset at world unload.
 * {@code ./gradlew memoryBudgetTest}; last line "# memorybudget PASS n/n".
 */
public final class MemoryBudgetTest {
    private static int pass, fail;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) {
        long heap = 16 * GiB;
        MemoryBudget b = new MemoryBudget(0);
        b.sample(heap, 0, 0, 1 * GiB);
        check("auto cap on 16 GiB = 6 GiB (min(50%, 6 GiB))", b.cap() == 6 * GiB && b.state() == MemoryBudget.State.OK);
        check("auto cap on 8 GiB = 50% (4 GiB)", MemoryBudget.autoCap(8 * GiB) == 4 * GiB);

        b.setConfiguredCapMiB(4096);
        check("configured cap 4096 MiB", b.cap() == 4096 * MiB);

        // driver budget: 14 GiB budget, process (incl. Iroh 1 GiB) uses 12 GiB -> headroom 2 GiB - 1.5 GiB reserve
        b.setConfiguredCapMiB(0);
        b.sample(heap, 14 * GiB, 12 * GiB, 1 * GiB);
        check("driver budget bounds the cap: iroh 1 GiB + (14 - 12 - 1.5) GiB = 1.5 GiB (" + b.cap() / MiB + " MiB)", b.cap() == 1536 * MiB);
        check("1 GiB of 1.5 GiB cap = 67%: OK", b.state() == MemoryBudget.State.OK);

        MemoryBudget.State t = b.sample(heap, 0, 0, (long) (5.3 * GiB));
        check("5.3 GiB of 6 GiB = 88%: WARN transition", t == MemoryBudget.State.WARN && b.state() == MemoryBudget.State.WARN);
        check("WARN reported once per episode", b.takeWarn() && !b.takeWarn());
        check("WARN: optional allocations that stay under the cap are allowed", b.allow(256 * MiB, false));
        check("WARN: an optional allocation crossing the cap is refused", !b.allow(2 * GiB, false));

        b.sample(heap, 0, 0, 6 * GiB);
        check("at the cap: CAPPED", b.state() == MemoryBudget.State.CAPPED);
        check("CAPPED: optional refused, essential still tried", !b.allow(1 * MiB, false) && b.allow(64 * MiB, true));

        b.sample(heap, 0, 0, 2 * GiB);
        check("usage drops: back to OK, next WARN episode reported again", b.state() == MemoryBudget.State.OK);
        b.sample(heap, 0, 0, (long) (5.5 * GiB));
        check("new episode warns again", b.takeWarn());

        check("driver OOM: first report true", b.outOfMemory());
        check("driver OOM: later reports false (logged once)", !b.outOfMemory());
        b.sample(heap, 0, 0, 1 * GiB);
        check("OOM is sticky across samples", b.state() == MemoryBudget.State.OUT_OF_MEMORY);
        check("OOM: every allocation refused, essential too (no retry against the driver)", !b.allow(1, true) && !b.allow(1, false));
        b.reset();
        check("world unloaded: reset to normal sampling", b.state() == MemoryBudget.State.OK && b.allow(1, true));

        MemoryBudget z = new MemoryBudget(0);
        z.sample(heap, 3 * GiB, 3 * GiB, 0);
        check("no headroom at all (budget exhausted by others): cap 0 -> CAPPED, optional refused",
                z.cap() == 0 && z.state() == MemoryBudget.State.CAPPED && !z.allow(1, false));

        // rc9 (td-598433): growth rules, replaying the rc8 pack run (1056 -> 3104 -> 5408 -> 7712 MiB at 30 s steps,
        // +600 'GlTexture depth by x' images per 30 s)
        dev.elemore.iroh.memory.GrowthMonitor g = new dev.elemore.iroh.memory.GrowthMonitor();
        long s0 = 1_000_000_000L, sec = 1_000_000_000L;
        g.worldStarted(s0);
        java.util.Map<String, Integer> owners = new java.util.HashMap<>();
        // first 2 minutes: a world load allocates fast; not a spike
        var early = g.sample(s0 + 20 * sec, 3 * GiB, owners);
        check("growth during the first 2 minutes of a world is not reported", !early.any());
        long tt = s0 + 200 * sec;
        long mem = 1056 * MiB;
        int depthImgs = 600;
        var none = g.sample(tt, mem, java.util.Map.of("GlTexture depth by x", depthImgs));
        check("steady world: nothing reported", !none.any());
        dev.elemore.iroh.memory.GrowthMonitor.Events ev = null;
        boolean spike = false, flagged = false;
        for (int step = 1; step <= 4; step++) {
            for (int k = 1; k <= 30; k++) {
                tt += sec;
                mem += (2304 * MiB) / 30;
                depthImgs += 20;
                ev = g.sample(tt, mem, java.util.Map.of("GlTexture depth by x", depthImgs, "Texture #", 1626));
                spike |= ev.spikeBytes() > 0;
                flagged |= ev.newlyFlagged().containsKey("GlTexture depth by x");
            }
        }
        check("rc8 replay: memory spike (>1 GiB in 30 s) reported", spike);
        check("rc8 replay: the growing owner is flagged (>500 images in 60 s), the steady one is not",
                flagged && g.isFlagged("GlTexture depth by x") && !g.isFlagged("Texture #"));
        var again = g.sample(tt + sec, mem + GiB, java.util.Map.of("GlTexture depth by x", depthImgs + 600));
        check("each rule reports once per world (no log flood)", !again.any());
        g.worldEnded();
        check("world end clears the flags", !g.isFlagged("GlTexture depth by x"));
        g.worldStarted(tt);
        var slow = g.sample(tt + 130 * sec, 2 * GiB, java.util.Map.of("a", 100));
        var slow2 = g.sample(tt + 160 * sec, 2 * GiB + 512 * MiB, java.util.Map.of("a", 400));
        check("slower growth (0.5 GiB / 30 s, +300 images / 30 s) is not flagged", !slow.any() && !slow2.any());

        System.out.println("# memorybudget " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
