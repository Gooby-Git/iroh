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
        int s = Math.max(0, Math.min(100, c.upscaleSharpness));
        DebugSwitches.set(UpscaleSwitches.SHARPNESS, s == 0 ? "off" : String.format(Locale.ROOT, "%.2f", s / 100f));
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

    public static Component qualityReason(Config c) {
        return parse(c.upscaler) == Upscaler.OFF ? Component.translatable("iroh.reason.upscalerOff") : null;
    }

    public static Component sharpnessReason(Config c) {
        return parse(c.upscaler) != Upscaler.FSR3 ? Component.translatable("iroh.reason.fsrOnly") : null;
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
