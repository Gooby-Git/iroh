package dev.elemore.iroh.uploadtest;

import dev.elemore.iroh.memory.MemoryBudget;
import dev.elemore.iroh.memory.MemoryShares;
import dev.elemore.iroh.memory.MemoryShares.Owner;
import dev.elemore.iroh.rt.game.RtGuardMemory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * INT-4: the one table of who may take how much of Iroh's device-memory cap (MemoryShares), headless. Checks the
 * numbers, that the owners that can be live together stay under the WARN line, that each Account refuses at its
 * ceiling AND at the guard's total, that RT's ceiling follows Iroh's live cap, and (source checks) that every optional
 * owner is wired to the table. {@code ./gradlew memorySharesTest}; last line {@code # memoryshares PASS n/n}.
 */
public final class MemorySharesTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    static final long MiB = 1L << 20, GiB = 1L << 30;

    public static void main(String[] args) throws Exception {
        long cap16 = MemoryBudget.autoCap(16 * GiB);   // the RX 9070 XT: 6 GiB
        long cap8 = MemoryBudget.autoCap(8 * GiB);     // an 8 GiB card: 4 GiB
        System.out.println("  share table at Iroh cap " + (cap16 / MiB) + " MiB (16 GiB card) / " + (cap8 / MiB) + " MiB (8 GiB card):");
        for (Owner o : Owner.values())
            System.out.printf("    %-22s %4.0f%%  %6d MiB / %6d MiB%n", o.id, o.fraction * 100, o.ceiling(cap16) / MiB, o.ceiling(cap8) / MiB);

        // the numbers
        check("RT ceiling on the 16 GiB card = 1536 MiB (25% of 6 GiB = the P5-2 owner cap)", Owner.RT.ceiling(cap16) == 1536 * MiB);
        check("RT ceiling on an 8 GiB card = 1024 MiB (25% of 4 GiB, below the absolute 1536)", Owner.RT.ceiling(cap8) == 1024 * MiB);
        check("RT ceiling never above 1536 MiB even with a large configured cap (12 GiB)", Owner.RT.ceiling(12 * GiB) == 1536 * MiB);
        check("api-4 owner share = GpuCore.OWNER_SHARE (one source, 40% default)", Owner.API4_EACH.fraction == dev.elemore.iroh.gpu.GpuCore.OWNER_SHARE
                && Owner.API4_EACH.fraction == 0.40);
        check("pack share = ResourcePlan.PACK_SHARE (35%, the loader's planning number)",
                Owner.PACK.fraction == dev.elemore.iroh.shaderpack.graph.ResourcePlan.PACK_SHARE);
        check("unknown cap (0, before the first sample) -> every ceiling 0 (refuse, never guess)", Owner.RT.ceiling(0) == 0 && Owner.PACK.ceiling(0) == 0);

        // what can be live together: one api-4 owner (Vista) + (pack XOR rt by the Look) + FSR <= WARN line of the cap
        long withRt = MemoryShares.worstCaseOptional(cap16, true), withPack = MemoryShares.worstCaseOptional(cap16, false);
        check("Look RT: Vista 40% + RT + FSR = " + withRt / MiB + " MiB <= 85% of the cap (" + (long) (cap16 * 0.85) / MiB + ")",
                withRt <= (long) (cap16 * MemoryShares.OPTIONAL_LIMIT));
        check("Look Navidson: Vista 40% + pack 35% + FSR 10% = " + withPack / MiB + " MiB <= 85% of the cap",
                withPack <= (long) (cap16 * MemoryShares.OPTIONAL_LIMIT) + 1);
        check("8 GiB card, both looks: under 85% too", MemoryShares.worstCaseOptional(cap8, true) <= (long) (cap8 * 0.85) + 1
                && MemoryShares.worstCaseOptional(cap8, false) <= (long) (cap8 * 0.85) + 1);

        // Account: ceiling, guard total, release, refusal reasons
        long[] cap = {cap16};
        boolean[] guardOk = {true};
        MemoryShares.Account pack = new MemoryShares.Account(Owner.PACK, () -> cap[0], b -> guardOk[0]);
        boolean a1 = pack.reserve(2000 * MiB), a2 = pack.reserve(200 * MiB);
        check("pack: 2000 MiB fits its 2150 MiB share, +200 MiB would pass it -> refused, nothing held for it ("
                + pack.lastRefusal() + ")", a1 && !a2 && pack.held() == 2000 * MiB && pack.lastRefusal().contains("share"));
        pack.release(2000 * MiB);
        guardOk[0] = false;
        boolean a3 = pack.reserve(10 * MiB);
        check("pack: inside its share but the guard's total says no -> refused (" + pack.lastRefusal() + ")",
                !a3 && pack.held() == 0 && pack.lastRefusal().contains("guard") && pack.refusals() == 2);
        guardOk[0] = true;
        pack.release(1 << 30);
        check("release never goes below zero", pack.held() == 0);

        // RT follows the LIVE cap (the driver budget can shrink Iroh's cap mid-session)
        RtGuardMemory rt = new RtGuardMemory(() -> Owner.RT.ceiling(cap[0]), b -> guardOk[0]);
        boolean r1 = rt.reserve(1400 * MiB);
        cap[0] = 4 * GiB;   // driver budget dropped: Iroh's cap 6 -> 4 GiB, RT ceiling 1536 -> 1024 MiB
        boolean r2 = rt.reserve(1 * MiB);
        rt.release(1400 * MiB);
        boolean r3 = rt.reserve(1000 * MiB);
        check("iroh-rt: ceiling read live: 1400 MiB taken at 6 GiB, nothing more once the cap fell to 4 GiB, 1000 MiB fits after release",
                r1 && !r2 && r3 && rt.capBytes() == 1024 * MiB);
        guardOk[0] = false;
        check("iroh-rt: the guard's total refuses even inside the ceiling", !rt.reserve(1 * MiB));
        guardOk[0] = true;
        check("RtGuardMemory.DEFAULT_CAP is the table's absolute RT ceiling", RtGuardMemory.DEFAULT_CAP == Owner.RT.absolute);

        // wiring (source checks: the game owners need Vulkan)
        String vk = Files.readString(Path.of("src/main/java/dev/elemore/iroh/shaderpack/runtime/IrohVkBackend.java"));
        int alloc = vk.indexOf("protected Object[] allocImage("), rel = vk.indexOf("protected void releaseImage(");
        check("pack images: reserved in the PACK share before allocating, released on failure and on free",
                vk.indexOf("PACK_MEMORY.reserve(bytes)", alloc) > alloc && vk.indexOf("PACK_MEMORY.release(bytes)", alloc) > alloc
                        && vk.indexOf("PACK_MEMORY.release(held)", rel) > rel && vk.contains("MemoryShares.Owner.PACK"));
        String rg = Files.readString(Path.of("src/main/java/dev/elemore/iroh/rt/game/RtGuardMemory.java"));
        check("iroh-rt in game: table ceiling of MemoryGuard's live cap + allowOptional + growth flag",
                rg.contains("MemoryShares.Owner.RT.ceiling(dev.elemore.iroh.memory.MemoryGuard.BUDGET.cap())") && rg.contains("allowOptional(b)")
                        && rg.contains("refuseNewImage(OWNER)"));
        String fsr = Files.readString(Path.of("src/main/java/dev/elemore/iroh/temporal/FsrAdapter.java"));
        check("FSR context: table ceiling + allowOptional before create", fsr.contains("MemoryShares.Owner.FSR.ceiling(MemoryGuard.BUDGET.cap())")
                && fsr.contains("MemoryGuard.allowOptional(bytes)"));
        String core = Files.readString(Path.of("src/main/java/dev/elemore/iroh/gpu/GpuCore.java"));
        check("api-4 owners: GpuCore's share comes from the table", core.contains("MemoryShares.Owner.API4_EACH.fraction"));

        System.out.println("# memoryshares " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
