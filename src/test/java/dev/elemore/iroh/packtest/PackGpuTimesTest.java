package dev.elemore.iroh.packtest;

import dev.elemore.iroh.debug.DebugSwitches;
import dev.elemore.iroh.shaderpack.runtime.PackGpuTimes;
import dev.elemore.iroh.shaderpack.runtime.PackSwitches;

import java.util.List;

/** G1 item 4: per-pass GPU time accounting. {@code ./gradlew packGpuTimesTest}; last line {@code # gputimes PASS n/n}. */
public final class PackGpuTimesTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    public static void main(String[] args) {
        PackGpuTimes t = new PackGpuTimes();
        List<String> names = List.of("deferred1", "composite7", "final");
        boolean[] all = {true, true, true, true, true, true};
        // period 1 ns: ticks are ns. deferred1 0.5 ms, composite7 0.25 ms, final 0.1 ms
        t.addFrame(names, new long[]{0, 500_000, 600_000, 850_000, 900_000, 1_000_000}, all, 1.0);
        t.addFrame(names, new long[]{0, 700_000, 700_000, 950_000, 1_000_000, 1_100_000}, all, 1.0);
        check("two frames", t.frames() == 2);
        String line = t.roll();
        check("averages: " + line, line.contains("deferred1=0.600") && line.contains("composite7=0.250") && line.contains("final=0.100")
                && line.contains("total=0.950") && line.contains("(2 frames)"));
        check("frame order kept", line.indexOf("deferred1") < line.indexOf("composite7") && line.indexOf("composite7") < line.indexOf("final"));
        check("window reset", t.frames() == 0 && t.roll().equals(line));
        boolean[] some = {true, true, false, true, true, true};
        t.addFrame(names, new long[]{0, 1_000_000, 0, 5, 10, 20}, some, 1.0);
        String l2 = t.roll();
        check("unavailable pair skipped: " + l2, !l2.contains("composite7") && l2.contains("deferred1=1.000"));
        t.addFrame(names, new long[]{10, 5, 0, 0, 0, 0}, new boolean[]{true, true, false, false, false, false}, 1.0);
        check("wrapped/negative pair ignored", t.frames() == 0);
        PackSwitches.registerAll();
        check("published default", DebugSwitches.get(PackSwitches.GPU_TIMES).equals("no samples yet"));
        DebugSwitches.publish(PackSwitches.GPU_TIMES, line);
        check("published value readable", DebugSwitches.get(PackSwitches.GPU_TIMES).equals(line));
        boolean threw = false;
        try { DebugSwitches.set(PackSwitches.GPU_TIMES, "x"); } catch (IllegalArgumentException e) { threw = true; }
        check("read-only", threw);
        System.out.println("# gputimes " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
