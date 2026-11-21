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
        check("auto cap on 16 GiB = 8 GiB (min(60%, 8 GiB))", b.cap() == 8 * GiB && b.state() == MemoryBudget.State.OK);
        check("auto cap on 8 GiB = 60% (4.8 GiB)", MemoryBudget.autoCap(8 * GiB) == (long) (8 * GiB * 0.6));

        b.setConfiguredCapMiB(4096);
        check("configured cap 4096 MiB", b.cap() == 4096 * MiB);

        // driver budget: 14 GiB budget, process (incl. Iroh 1 GiB) uses 12 GiB -> headroom 2 GiB - 1.5 GiB reserve
        b.setConfiguredCapMiB(0);
        b.sample(heap, 14 * GiB, 12 * GiB, 1 * GiB);
        check("driver budget bounds the cap: iroh 1 GiB + (14 - 12 - 1.5) GiB = 1.5 GiB (" + b.cap() / MiB + " MiB)", b.cap() == 1536 * MiB);
        check("1 GiB of 1.5 GiB cap = 67%: OK", b.state() == MemoryBudget.State.OK);

        MemoryBudget.State t = b.sample(heap, 0, 0, (long) (7 * GiB));
        check("7 GiB of 8 GiB = 87.5%: WARN transition", t == MemoryBudget.State.WARN && b.state() == MemoryBudget.State.WARN);
        check("WARN reported once per episode", b.takeWarn() && !b.takeWarn());
        check("WARN: optional allocations that stay under the cap are allowed", b.allow(256 * MiB, false));
        check("WARN: an optional allocation crossing the cap is refused", !b.allow(2 * GiB, false));

        b.sample(heap, 0, 0, 8 * GiB);
        check("at the cap: CAPPED", b.state() == MemoryBudget.State.CAPPED);
        check("CAPPED: optional refused, essential still tried", !b.allow(1 * MiB, false) && b.allow(64 * MiB, true));

        b.sample(heap, 0, 0, 2 * GiB);
        check("usage drops: back to OK, next WARN episode reported again", b.state() == MemoryBudget.State.OK);
        b.sample(heap, 0, 0, (long) (7.5 * GiB));
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

        System.out.println("# memorybudget " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
