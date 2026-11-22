package dev.elemore.iroh.rt;

import java.util.Locale;
import java.util.Map;

/**
 * RT settings: the look switch, the preset, and a development gate. RT is ACTIVE only when the user picked
 * {@link RtLook#RAY_TRACED}, the device supports it, and (until RT ships) {@code -Diroh.rt=true} is set. Everything else
 * leaves the RT core inert: no device features requested, no listeners, no allocations (RtSeamTest proves it).
 * In-game storage: the Iroh section of video settings (config/iroh.json keys {@code look}, {@code rtPreset}); not wired
 * in P5-0.
 */
public record RtSettings(RtLook look, RtPreset preset, boolean devGate) {
    public static final RtSettings DEFAULT = new RtSettings(RtLook.NAVIDSON, RtPreset.MEDIUM, false);

    /** From string properties (system properties or config): iroh.rt, iroh.rt.look, iroh.rt.preset. Bad values -> defaults. */
    public static RtSettings from(Map<?, ?> p) {
        RtLook look = RtLook.NAVIDSON;
        RtPreset preset = RtPreset.MEDIUM;
        try {
            Object l = p.get("iroh.rt.look");
            if (l != null) look = RtLook.valueOf(l.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
        }
        try {
            Object s = p.get("iroh.rt.preset");
            if (s != null) preset = RtPreset.valueOf(s.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
        }
        return new RtSettings(look, preset, "true".equals(String.valueOf(p.get("iroh.rt"))));
    }

    public boolean active(RtCapabilities caps) {
        return devGate && look == RtLook.RAY_TRACED && caps != null && caps.supported();
    }
}
