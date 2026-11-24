package dev.elemore.iroh.rt.game;

import dev.elemore.iroh.memory.MemoryShares;
import dev.elemore.iroh.rt.RtMemory;

import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * P5-2: RT's device memory as MemoryGuard owner {@value RtMemory#OWNER}. INT-4: its ceiling comes from the one share
 * table ({@link MemoryShares.Owner#RT}: 25% of Iroh's cap, at most 1536 MiB, {@code -Diroh.rt.capMiB}) and is read
 * LIVE (Iroh's cap moves with the driver budget). A reservation is refused when it would pass that ceiling, when
 * MemoryGuard refuses optional memory (budget pressure from EVERYTHING on the device: the game, Vista, other apps), or
 * while the guard has flagged the owner for runaway growth. Refusal is normal: the build waits or the residency radius
 * shrinks; nothing ever forces the driver (SYSTEM SAFETY rule 9).
 */
public final class RtGuardMemory implements RtMemory {
    /** the absolute ceiling (the share table's RT row; 1536 MiB unless -Diroh.rt.capMiB) */
    public static final long DEFAULT_CAP = MemoryShares.Owner.RT.absolute;
    private final LongSupplier cap;
    private long used;
    /** the guard check (MemoryGuard in game; a lambda offline) */
    private final LongPredicate guard;

    public RtGuardMemory(long cap, LongPredicate guard) {
        this(() -> cap, guard);
    }

    public RtGuardMemory(LongSupplier cap, LongPredicate guard) {
        this.cap = cap;
        this.guard = guard;
    }

    /** In game: the share table's RT ceiling of Iroh's live cap; guard = MemoryGuard.allowOptional + growth flag. */
    public static RtGuardMemory forGame() {
        return new RtGuardMemory(() -> MemoryShares.Owner.RT.ceiling(dev.elemore.iroh.memory.MemoryGuard.BUDGET.cap()),
                b -> dev.elemore.iroh.memory.MemoryGuard.allowOptional(b) && !dev.elemore.iroh.memory.MemoryGuard.refuseNewImage(OWNER));
    }

    @Override public long capBytes() { return cap.getAsLong(); }

    @Override public synchronized long usedBytes() { return used; }

    @Override
    public synchronized boolean reserve(long bytes) {
        if (used + bytes > cap.getAsLong() || !guard.test(bytes)) return false;
        used += bytes;
        return true;
    }

    @Override
    public synchronized void release(long bytes) {
        used = Math.max(0, used - bytes);
    }

    @Override
    public synchronized void charge(long bytes) {
        used += Math.max(0, bytes);
    }
}
