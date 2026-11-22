package dev.elemore.iroh.api;

import dev.elemore.iroh.debug.DebugSwitches;

import java.nio.file.Path;

/**
 * Runtime switches for in-session A/B tests (Proctor calls these by reflection: knowledge/proctor.md "IrohDebug").
 * Keys are the {@code iroh.<area>.<name>} property names. {@link #set} needs {@code -Dproctor=1} or
 * {@code -Diroh.debug=true} (else it refuses); {@link #get} always answers. Unknown keys throw IllegalArgumentException.
 * Returns: {@code ok}, {@code rebuild} (a rebuild was scheduled), {@code restart} (stored for the next launch only,
 * nothing applied), or a detail string.
 */
public final class IrohDebug {

    private IrohDebug() {}

    static boolean allowed() {
        return "1".equals(System.getProperty("proctor")) || Boolean.getBoolean("iroh.debug");
    }

    public static String set(String key, String value) {
        if (!allowed())
            return "refused: runtime switches need -Dproctor=1 or -Diroh.debug=true";
        dev.elemore.iroh.shaderpack.runtime.PackSwitches.registerAll();
        return DebugSwitches.set(key, value);
    }

    public static String get(String key) {
        dev.elemore.iroh.shaderpack.runtime.PackSwitches.registerAll();
        if (dev.elemore.iroh.shaderpack.runtime.PackSwitches.SUBST_REPORT.equals(key))
            DebugSwitches.publish(key, dev.elemore.iroh.shaderpack.runtime.Substitution.report());
        return DebugSwitches.get(key);
    }

    /** every registered switch and its value, one per line */
    public static String list() {
        dev.elemore.iroh.shaderpack.runtime.PackSwitches.registerAll();
        StringBuilder sb = new StringBuilder();
        DebugSwitches.switches().forEach((k, s) -> sb.append(k).append('=').append(DebugSwitches.getOr(k, s.defaultValue()))
                .append("  # ").append(s.help()).append('\n'));
        return sb.toString();
    }

    /**
     * Writes ONE {@code .irohframe} capture of the next pack frame into {@code dir} (async readback; bounded 512 MiB;
     * refused while another capture is in progress or when the pack path is not running). Returns {@code ok} when
     * scheduled, or the reason it was refused. The file layout is FrameCapture's (frame.json + raw float images).
     */
    public static String capturePackFrame(Path dir) {
        if (!allowed())
            return "refused: needs -Dproctor=1 or -Diroh.debug=true";
        return dev.elemore.iroh.shaderpack.runtime.PackRuntime.requestCapture(dir);
    }
}
