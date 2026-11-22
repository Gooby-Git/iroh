package dev.elemore.iroh.api;

import dev.elemore.iroh.debug.DebugSwitches;

/**
 * Runtime switches for in-session A/B tests (Proctor calls these by reflection: knowledge/proctor.md "IrohDebug").
 * Keys are the {@code iroh.<area>.<name>} property names. {@link #set} needs {@code -Dproctor=1} or
 * {@code -Diroh.debug=true} (else it refuses); {@link #get} always answers. Unknown keys throw IllegalArgumentException.
 * Returns: {@code ok}, {@code rebuild} (a rebuild was scheduled), {@code restart} (stored for the next launch only,
 * nothing applied), or a detail string.
 * <p>integration/next: card/P3-4's class (f78830e) registering PerfSwitches (perf/hitches) and UpscaleSwitches (P4-1a/b);
 * the merge of card/P3-9 adds PackSwitches and capturePackFrame back.
 */
public final class IrohDebug {

    private IrohDebug() {}

    static boolean allowed() {
        return "1".equals(System.getProperty("proctor")) || Boolean.getBoolean("iroh.debug");
    }

    public static String set(String key, String value) {
        if (!allowed())
            return "refused: runtime switches need -Dproctor=1 or -Diroh.debug=true";
        dev.elemore.iroh.perf.PerfSwitches.registerAll();
        dev.elemore.iroh.temporal.UpscaleSwitches.registerAll();
        return DebugSwitches.set(key, value);
    }

    public static String get(String key) {
        dev.elemore.iroh.perf.PerfSwitches.registerAll();
        dev.elemore.iroh.temporal.UpscaleSwitches.registerAll();
        return DebugSwitches.get(key);
    }

    /** every registered switch and its value, one per line */
    public static String list() {
        dev.elemore.iroh.perf.PerfSwitches.registerAll();
        dev.elemore.iroh.temporal.UpscaleSwitches.registerAll();
        StringBuilder sb = new StringBuilder();
        DebugSwitches.switches().forEach((k, s) -> sb.append(k).append('=').append(DebugSwitches.getOr(k, s.defaultValue()))
                .append("  # ").append(s.help()).append('\n'));
        return sb.toString();
    }

}
