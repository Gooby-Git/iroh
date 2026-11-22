package dev.elemore.iroh.diag;

import java.util.Map;
import java.util.TreeMap;

/**
 * INT-2 first-launch safety net: ONE log line at renderer init that proves the state of the build: every Iroh feature
 * switch (the runtime registry: pack, perf, upscale) with its value, every other {@code -Diroh.*} property that is set,
 * the device feature bits the logical device was created with, the api version and whether api 4's device exists,
 * where each optional native library would load from (nothing is loaded for the report), and any feature that already
 * failed. Reading it in the first in-game log answers "is this jar inert?" without a second run.
 * Line format (one line, sections separated by " | "):
 * {@code Iroh self-check: inert=<yes|NO> | switches{k=v,..} | props{k=v,..} | device{..} | api 4.1 api4=<state> api41=<on|withdrawn> | natives{fsr=..,nrd=..} | failures{..}}
 * (INT-3: switches include iroh.pack.world.split / iroh.pack.world.substitute from the pack registry.)
 */
public final class StartupReport {
    private StartupReport() {}

    /** pure: the line from its parts (tested offline) */
    public static String build(Map<String, String> switches, Map<String, String> defaults, Map<String, String> otherProps,
                               String device, String api, Map<String, String> natives, Map<String, String> failures) {
        StringBuilder nonDefault = new StringBuilder();
        for (var e : switches.entrySet()) {
            String d = defaults.get(e.getKey());
            if (d != null && !d.equals(e.getValue())) nonDefault.append(nonDefault.length() == 0 ? "" : ",").append(e.getKey());
        }
        boolean inert = nonDefault.length() == 0 && otherProps.isEmpty() && failures.isEmpty();
        return "Iroh self-check: inert=" + (inert ? "yes" : "NO (non-default: " + (nonDefault.length() == 0 ? "-" : nonDefault)
                + (otherProps.isEmpty() ? "" : "; props " + otherProps.keySet()) + (failures.isEmpty() ? "" : "; failures " + failures.keySet()) + ")")
                + " | switches" + compact(switches)
                + " | props" + compact(otherProps)
                + " | device{" + device + "}"
                + " | " + api
                + " | natives" + compact(natives)
                + " | failures" + compact(failures);
    }

    static String compact(Map<String, String> m) {
        StringBuilder b = new StringBuilder("{");
        for (var e : new TreeMap<>(m).entrySet())
            b.append(b.length() == 1 ? "" : ", ").append(e.getKey()).append('=').append(e.getValue());
        return b.append('}').toString();
    }

    /** properties that configure Iroh but are not runtime switches (slow frames, validation, imageTrace, test seams...) */
    static Map<String, String> otherIrohProps(Map<String, String> switches) {
        Map<String, String> out = new TreeMap<>();
        for (String k : System.getProperties().stringPropertyNames())
            if (k.startsWith("iroh.") && !switches.containsKey(k)) out.put(k, System.getProperty(k));
        return out;
    }

    /** the real line (renderer init, after the device and api 4's lazy installer exist). Never throws. */
    public static String line() {
        try {
            FeatureFailures.inject("startupReport");
            dev.elemore.iroh.shaderpack.runtime.PackSwitches.registerAll();
            dev.elemore.iroh.perf.PerfSwitches.registerAll();
            dev.elemore.iroh.temporal.UpscaleSwitches.registerAll();
            Map<String, String> switches = new TreeMap<>(), defaults = new TreeMap<>();
            for (var e : dev.elemore.iroh.debug.DebugSwitches.switches().entrySet()) {
                switches.put(e.getKey(), dev.elemore.iroh.debug.DebugSwitches.getOr(e.getKey(), e.getValue().defaultValue()));
                defaults.put(e.getKey(), e.getValue().defaultValue());
            }
            // read-only values published by the renderer are not switches a user set
            switches.keySet().removeIf(k -> k.equals("iroh.pack.gpuTimes") // INT-3: + the P3-10/P3-11 report keys
                    || k.equals(dev.elemore.iroh.shaderpack.runtime.PackSwitches.WORLD_REPORT)
                    || k.equals(dev.elemore.iroh.shaderpack.runtime.PackSwitches.SUBST_REPORT));
            Map<String, String> natives = new TreeMap<>();
            natives.put("fsr", dev.elemore.iroh.upscale.fsr.NativeLoader.locate());
            natives.put("nrd", dev.elemore.iroh.rt.denoise.nrd.NrdLoader.locate());
            String api = "api " + dev.elemore.iroh.api.IrohApi.API_VERSION + "." + dev.elemore.iroh.api.IrohApi.API_MINOR
                    + " api4=" + dev.elemore.iroh.gpu.game.GpuLifecycle.state()
                    + " api41=" + (dev.elemore.iroh.gpu.GpuCore.api41() ? "on" : "withdrawn")
                    + " slowFrames=" + (dev.elemore.iroh.diag.SlowFrames.ON ? "on" : "off")
                    + " upscaleDeviceFeatures=" + (dev.elemore.iroh.temporal.DeviceCaps.requested ? "requested" : "not requested")
                    + dev.elemore.iroh.rt.game.RtStartup.apiPart(); // P5-2: empty unless RT was requested at launch
            return build(switches, defaults, otherIrohProps(switches), net.vulkanmod.vulkan.device.DeviceManager.enabledFeatures,
                    api, natives, FeatureFailures.snapshot());
        } catch (Throwable t) {
            return "Iroh self-check: unavailable (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        }
    }
}
