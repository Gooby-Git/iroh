package dev.elemore.iroh.temporal;

import dev.elemore.iroh.debug.DebugSwitches;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.Config;

import java.util.Locale;

/**
 * Card P4-1b: the upscaler rows of the Iroh section in the video settings (owner decision "Settings UI": no shader
 * selection screen; one section holding upscaler, quality, sharpening, frame generation and the RT / Navidson look
 * switch). The rows are stored in iroh.json and turned into the runtime switches here, so the settings, the -D
 * properties and IrohDebug all drive ONE code path. A launch property {@code -Diroh.upscale.mode} wins over the
 * settings (Proctor and A/B runs), and the settings never touch the switches while it is set.
 */
public final class UpscaleSettings {
    private UpscaleSettings() {}

    public enum Upscaler { OFF, FSR3, FSR4, DLSS }

    public enum Quality {
        NATIVE_AA(ScaleMode.NATIVE_AA), QUALITY(ScaleMode.QUALITY), BALANCED(ScaleMode.BALANCED),
        PERFORMANCE(ScaleMode.PERFORMANCE), ULTRA_PERFORMANCE(ScaleMode.ULTRA_PERFORMANCE);
        public final ScaleMode mode;
        Quality(ScaleMode m) { this.mode = m; }
    }

    /** FSR-Q5 Mip LOD bias rule row */
    public enum MipMode {
        PIXEL_ART("pixelart"), FORMULA("formula"), MANUAL("pixelart"), OFF("off");
        public final String key;
        MipMode(String key) { this.key = key; }
    }

    static MipMode parseMip(String s) {
        try {
            return MipMode.valueOf(s);
        } catch (RuntimeException e) {
            return MipMode.PIXEL_ART;
        }
    }

    /** the settings would start the upscaler: enable the device features at device creation */
    public static boolean wantsDeviceFeatures(Config c) {
        return parse(c.upscaler) == Upscaler.FSR3;
    }

    /** iroh.json -> switches. Off: mode off. FSR 3.1: the quality mode + upscaler fsr + sharpness. */
    public static void apply(Config c) {
        if (System.getProperty(UpscaleSwitches.MODE) != null)
            return; // a launch property owns the switches
        UpscaleSwitches.registerAll();
        Upscaler u = parse(c.upscaler);
        if (u != Upscaler.FSR3) {
            DebugSwitches.set(UpscaleSwitches.MODE, "off");
            return;
        }
        Quality q;
        try {
            q = Quality.valueOf(c.upscaleQuality);
        } catch (RuntimeException e) {
            q = Quality.QUALITY;
        }
        DebugSwitches.set(UpscaleSwitches.UPSCALER, "fsr");
        // FSR-Q4: below 0 = Auto (the runtime reads Rcas.forRatio for the current mode), 0 = off
        int s = Math.min(100, c.upscaleSharpness);
        DebugSwitches.set(UpscaleSwitches.SHARPNESS, s < 0 ? "auto" : s == 0 ? "off" : String.format(Locale.ROOT, "%.2f", s / 100f));
        // FSR-Q5: mip bias rule; MANUAL pins the float, the others let the runtime follow the current render size
        MipMode mm = parseMip(c.upscaleMipMode);
        DebugSwitches.set(UpscaleSwitches.MIP_MODE, mm.key);
        DebugSwitches.set(UpscaleSwitches.MIP_BIAS, mm == MipMode.MANUAL
                ? String.format(Locale.ROOT, "%.2f", Math.max(-50, Math.min(0, c.upscaleMipBias)) / 10f) : "auto");
        DebugSwitches.set(UpscaleSwitches.MODE, q.mode.key);
    }

    static Upscaler parse(String s) {
        try {
            return Upscaler.valueOf(s);
        } catch (RuntimeException e) {
            return Upscaler.OFF;
        }
    }

    /** Why a choice of the Upscaler row cannot be picked, or null. */
    public static Component upscalerReason(Upscaler u) {
        if (u != Upscaler.OFF && packWins())
            return Component.translatable("iroh.reason.packWins"); // INT-2: same rule as TemporalRuntime.onRenderHead
        return switch (u) {
            case OFF -> null;
            case FSR3 -> {
                if (!dev.elemore.iroh.upscale.fsr.NativeLoader.load())
                    yield Component.translatable("iroh.reason.needsNativeLibrary");
                yield null;
            }
            case FSR4 -> Component.translatable(isWindows() ? "iroh.reason.notYet" : "iroh.reason.fsr4Windows");
            case DLSS -> Component.translatable(nvidia() ? "iroh.reason.notYet" : "iroh.reason.needsNvidia");
        };
    }

    /**
     * INT-2: the Navidson pack path wins over render scale until the pack's own upscaler cut lands (card P4-8): while
     * the pack path is on (or asked for at launch) the upscaler choices are greyed with that reason.
     */
    public static boolean packWins() {
        if (dev.elemore.iroh.shaderpack.runtime.PackRuntime.active())
            return true;
        String v = System.getProperty(dev.elemore.iroh.shaderpack.runtime.PackSwitches.ENABLED);
        return "true".equals(v) || "on".equals(v);
    }

    public static Component qualityReason(Config c) {
        return parse(c.upscaler) == Upscaler.OFF ? Component.translatable("iroh.reason.upscalerOff") : null;
    }

    public static Component sharpnessReason(Config c) {
        return parse(c.upscaler) != Upscaler.FSR3 ? Component.translatable("iroh.reason.fsrOnly") : null;
    }

    /** FSR-Q5: the slider only drives the bias in Manual mode. */
    public static Component mipBiasReason(Config c) {
        Component r = sharpnessReason(c);
        return r != null ? r : parseMip(c.upscaleMipMode) != MipMode.MANUAL ? Component.translatable("iroh.reason.mipManualOnly") : null;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static boolean nvidia() {
        try {
            var d = net.vulkanmod.vulkan.device.DeviceManager.device;
            return d != null && d.deviceName != null && d.deviceName.toUpperCase(Locale.ROOT).contains("NVIDIA");
        } catch (Throwable t) {
            return false;
        }
    }
}
