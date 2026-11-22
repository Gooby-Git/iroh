package dev.elemore.iroh.temporal;

import dev.elemore.iroh.debug.DebugSwitches;
import dev.elemore.iroh.debug.DebugSwitches.Switch;

/**
 * The upscaler-input switches (card P4-1a). ALL default OFF / inert: with {@code iroh.upscale.mode} unset the jar
 * behaves like rc14 (proved by {@code ./gradlew upscaleDefaultOffTest}). Every switch is read once per frame at
 * {@code GameRenderer.render} HEAD (TemporalFrame.beginFrame), so IrohDebug.set takes effect on the next frame,
 * except {@link #FEATURES}, which acts at device creation (restart).
 */
public final class UpscaleSwitches {

    /** off | native | quality | balanced | performance | ultra | custom */
    public static final String MODE = "iroh.upscale.mode";
    /** display/render ratio for mode=custom, 1..4 */
    public static final String SCALE = "iroh.upscale.scale";
    /** auto | fsr | bilinear (auto = FSR 3.1 when available, else bilinear) */
    public static final String UPSCALER = "iroh.upscale.upscaler";
    /** off | 0..1: FSR sharpening (RCAS) */
    public static final String SHARPNESS = "iroh.upscale.sharpness";
    /** auto | on | off: projection jitter (auto = only when the upscaler is temporal; the bilinear path never jitters) */
    public static final String JITTER = "iroh.upscale.jitter";
    /** auto | off | float: texture LOD bias in the world phase (auto = FSR's log2(render/display) - 1 when jittering, else 0) */
    public static final String MIP_BIAS = "iroh.upscale.mipBias";
    /** auto | on | off: camera velocity pass (auto = when the upscaler or the debug view needs it) */
    public static final String VELOCITY = "iroh.upscale.velocity";
    /** on | off: map display-size viewports/scissors given by mods during the world phase to the render target */
    public static final String VIEWPORT_FIX = "iroh.upscale.viewportFix";
    /** off | on | auto: enable the FSR device features at device creation (auto = when iroh.upscale.mode is set at launch) */
    public static final String FEATURES = "iroh.upscale.features";
    /** off | velocity | reactive | depth | jitter */
    public static final String DEBUG_VIEW = "iroh.debug.view";

    private static volatile boolean registered;

    private UpscaleSwitches() {}

    public static void registerAll() {
        if (registered)
            return;
        synchronized (UpscaleSwitches.class) {
            if (registered)
                return;
            DebugSwitches.register(new Switch(MODE, "off", "upscaler quality mode: " + ScaleMode.keys() + " (off = rc14 path, one window-size scene target)",
                    v -> ScaleMode.parse(v) == null ? "expected one of " + ScaleMode.keys() : null, v -> "ok"));
            DebugSwitches.register(new Switch(SCALE, "1.5", "display/render ratio for mode=custom (1..4)",
                    v -> {
                        try {
                            float f = Float.parseFloat(v);
                            return f >= 1f && f <= 4f ? null : "expected 1..4";
                        } catch (NumberFormatException e) {
                            return "expected a number 1..4";
                        }
                    }, v -> "ok"));
            DebugSwitches.register(new Switch(UPSCALER, "auto", "auto | fsr | bilinear (auto = FSR 3.1 when available, else bilinear)",
                    DebugSwitches.oneOf("auto", "fsr", "bilinear"), v -> "ok"));
            DebugSwitches.register(new Switch(SHARPNESS, "off", "FSR RCAS sharpening: off | 0..1",
                    v -> {
                        if (v.equals("off"))
                            return null;
                        try {
                            float f = Float.parseFloat(v);
                            return f >= 0f && f <= 1f ? null : "expected off or 0..1";
                        } catch (NumberFormatException e) {
                            return "expected off or 0..1";
                        }
                    }, v -> "ok"));
            DebugSwitches.register(new Switch(JITTER, "auto", "projection jitter: auto (temporal upscaler only) | on | off",
                    DebugSwitches.oneOf("auto", "on", "off"), v -> "ok"));
            DebugSwitches.register(new Switch(MIP_BIAS, "auto", "world texture LOD bias: auto | off | <float>",
                    v -> {
                        if (v.equals("auto") || v.equals("off"))
                            return null;
                        try {
                            float f = Float.parseFloat(v);
                            return f >= -4f && f <= 4f ? null : "expected -4..4";
                        } catch (NumberFormatException e) {
                            return "expected auto, off or a number";
                        }
                    }, v -> "ok"));
            DebugSwitches.register(new Switch(VELOCITY, "auto", "camera velocity pass: auto | on | off",
                    DebugSwitches.oneOf("auto", "on", "off"), v -> "ok"));
            DebugSwitches.register(new Switch(VIEWPORT_FIX, "on", "map display-size viewports of mods to the render target in the world phase",
                    DebugSwitches.BOOL, v -> "ok"));
            DebugSwitches.register(new Switch(FEATURES, "auto", "FSR device features at device creation: auto | on | off (restart)",
                    DebugSwitches.oneOf("auto", "on", "off"), v -> "restart"));
            DebugSwitches.register(new Switch(DEBUG_VIEW, "off", "debug view of an upscaler input: off | velocity | reactive | depth | jitter (needs iroh.upscale.mode)",
                    DebugSwitches.oneOf("off", "velocity", "reactive", "depth", "jitter"), v -> "ok"));
            registered = true;
        }
    }

    /** The mode as given by the switch or the -D property; never registers anything (safe before game init). */
    public static ScaleMode mode() {
        ScaleMode m = ScaleMode.parse(DebugSwitches.getOr(MODE, "off"));
        return m == null ? ScaleMode.OFF : m;
    }
}
