package dev.elemore.iroh.rt.game;

import dev.elemore.iroh.rt.RtMemory;

/**
 * P5-2: RT's device memory as MemoryGuard owner {@value RtMemory#OWNER}: its own cap (default 1.5 GiB,
 * {@code -Diroh.rt.capMiB}) on top of the guard's rules. A reservation is refused when it would pass the cap, when
 * MemoryGuard refuses optional memory (budget pressure from EVERYTHING on the device: the game, the pack, other apps), or
 * while the guard has flagged the owner for runaway growth. Refusal is normal: the build waits or the residency radius
 * shrinks; nothing ever forces the driver (SYSTEM SAFETY rule 9).
 */
public final class RtGuardMemory implements RtMemory {
    public static final long DEFAULT_CAP = 1536L << 20;
    private final long cap;
    private long used;
    /** the guard check (MemoryGuard in game; a lambda offline) */
    private final java.util.function.LongPredicate guard;

    public RtGuardMemory(long cap, java.util.function.LongPredicate guard) {
        this.cap = cap;
        this.guard = guard;
    }

    /** In game: cap from -Diroh.rt.capMiB (default 1536), guard = MemoryGuard.allowOptional + growth flag. */
    public static RtGuardMemory forGame() {
        long cap = Long.getLong("iroh.rt.capMiB", DEFAULT_CAP >> 20) << 20;
        return new RtGuardMemory(cap, b -> dev.elemore.iroh.memory.MemoryGuard.allowOptional(b)
                && !dev.elemore.iroh.memory.MemoryGuard.refuseNewImage(OWNER));
    }

    @Override public long capBytes() { return cap; }

    @Override public synchronized long usedBytes() { return used; }

    @Override
    public synchronized boolean reserve(long bytes) {
        if (used + bytes > cap || !guard.test(bytes)) return false;
        used += bytes;
        return true;
    }

    @Override
    public synchronized void release(long bytes) {
        used = Math.max(0, used - bytes);
    }
}
