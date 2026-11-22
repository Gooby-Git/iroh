package dev.elemore.iroh.memory;

import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * INT-4: THE table of who may take how much of Iroh's device-memory cap ({@link MemoryBudget#cap()}: min(50% of VRAM,
 * 6 GiB) or the configured value, further bounded by the driver's budget). Pure Java (memorySharesTest).
 * <p>
 * Two limits apply to every OPTIONAL owner: its own ceiling from this table, AND the guard's total check
 * ({@link MemoryGuard#allowOptional}: nothing optional is allocated once Iroh as a whole would pass its cap). The base
 * renderer (terrain arena, atlases, mod textures, staging ring, the main targets) is essential: it has no ceiling of
 * its own and is bounded only by the cap and the out-of-memory exit. So a share is a CEILING, never a reservation.
 * <table>
 *   <caption>shares of Iroh's cap (6 GiB auto cap on a 16 GiB card in brackets)</caption>
 *   <tr><th>owner</th><th>ceiling</th><th>enforced at</th></tr>
 *   <tr><td>base renderer</td><td>none (essential)</td><td>MemoryBudget.allow(essential), OOM exit</td></tr>
 *   <tr><td>each api-4 owner (Vista)</td><td>40% ({@code iroh.gpu.ownerShare}) [2.4 GiB]</td><td>GpuCore per owner</td></tr>
 *   <tr><td>shader pack images ("pack")</td><td>35% [2.1 GiB]</td><td>IrohVkBackend.allocImage</td></tr>
 *   <tr><td>ray tracing ("iroh-rt")</td><td>25%, at most 1536 MiB ({@code iroh.rt.capMiB}) [1.5 GiB]</td><td>RtGuardMemory</td></tr>
 *   <tr><td>upscaler context (FSR, outside VMA)</td><td>10% per context [0.6 GiB]</td><td>FsrAdapter before create</td></tr>
 * </table>
 * The Look switch makes "pack" and "iroh-rt" mutually exclusive (MainPassArbiter rule 8), so the optional ceilings that
 * can be live together are at most one api-4 owner + max(pack, rt) + FSR = 85% (the guard's WARN line): the base
 * renderer keeps at least 15% before any optional owner can be refused by its own ceiling, and the total check refuses
 * optional memory first whenever the sum would pass the cap. A second api-4 owner is bounded by its own 40% and the
 * total check only (api-4 owners are counted by GpuCore, which runs offline without MemoryGuard: residual, see INT-4).
 */
public final class MemoryShares {
    private MemoryShares() {}

    public enum Owner {
        API4_EACH("api-4 owner (each)", Double.parseDouble(System.getProperty("iroh.gpu.ownerShare", "0.40")), Long.MAX_VALUE),
        PACK("pack", 0.35, Long.MAX_VALUE),
        RT("iroh-rt", 0.25, Long.getLong("iroh.rt.capMiB", 1536) << 20),
        FSR("upscaler", 0.10, Long.MAX_VALUE);

        public final String id;
        public final double fraction;
        /** absolute ceiling on top of the fraction (Long.MAX_VALUE = none) */
        public final long absolute;

        Owner(String id, double fraction, long absolute) {
            this.id = id;
            this.fraction = fraction;
            this.absolute = absolute;
        }

        /** this owner's ceiling for an Iroh cap of {@code capBytes} (0 when the cap is not known yet) */
        public long ceiling(long capBytes) {
            if (capBytes <= 0) return 0;
            return Math.min((long) (capBytes * fraction), absolute);
        }
    }

    /** the WARN fraction the live optional ceilings must stay under (rule in the class javadoc) */
    public static final double OPTIONAL_LIMIT = MemoryBudget.WARN_FRACTION;

    /** sum of the ceilings that can be live together: one api-4 owner + (pack XOR rt, by the Look) + FSR */
    public static long worstCaseOptional(long capBytes, boolean lookRt) {
        return Owner.API4_EACH.ceiling(capBytes) + (lookRt ? Owner.RT : Owner.PACK).ceiling(capBytes) + Owner.FSR.ceiling(capBytes);
    }

    /** One owner's running count against its ceiling and the guard's total check. Thread-safe. */
    public static final class Account {
        public final Owner owner;
        private final LongSupplier cap;
        private final LongPredicate total;
        private long held;
        private int refusals;
        private String lastRefusal;

        /**
         * @param cap   Iroh's cap now (game: MemoryGuard.BUDGET::cap)
         * @param total the guard's total check for optional memory (game: MemoryGuard::allowOptional)
         */
        public Account(Owner owner, LongSupplier cap, LongPredicate total) {
            this.owner = owner;
            this.cap = cap;
            this.total = total;
        }

        /** In game: Iroh's cap and the guard's total check. */
        public static Account game(Owner owner) {
            return new Account(owner, MemoryGuard.BUDGET::cap, MemoryGuard::allowOptional);
        }

        public long ceiling() {
            return owner.ceiling(cap.getAsLong());
        }

        /** Reserve {@code bytes}: false (and nothing held) when the owner's ceiling or Iroh's total would be passed. */
        public synchronized boolean reserve(long bytes) {
            long c = ceiling();
            if (held + bytes > c) {
                refusals++;
                lastRefusal = owner.id + ": " + (bytes >> 20) + " MiB would pass its share " + (c >> 20) + " MiB (holds " + (held >> 20) + " MiB)";
                return false;
            }
            if (!total.test(bytes)) {
                refusals++;
                lastRefusal = owner.id + ": " + (bytes >> 20) + " MiB refused by the memory guard (Iroh's cap)";
                return false;
            }
            held += bytes;
            return true;
        }

        public synchronized void release(long bytes) {
            held = Math.max(0, held - bytes);
        }

        public synchronized long held() {
            return held;
        }

        public synchronized int refusals() {
            return refusals;
        }

        public synchronized String lastRefusal() {
            return lastRefusal;
        }
    }
}
