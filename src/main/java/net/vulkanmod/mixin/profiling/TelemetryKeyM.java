package net.vulkanmod.mixin.profiling;

import dev.elemore.iroh.config.IrohOptions;
import dev.elemore.iroh.telemetry.Telemetry;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Telemetry overlay hotkey (card TEL-1): toggles the overlay's master switch from the world (not while a screen is open, so
 * it never types into a text field). The key is the Telemetry tab's "Toggle key" option; the press is not consumed.
 * Priority 1500: pack mods' injectors at the default 1000 run first.
 */
@Mixin(value = KeyboardHandler.class, priority = 1500)
public class TelemetryKeyM {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void iroh$telemetryKey(long window, int key, int scancode, int action, int mods, CallbackInfo ci) {
        if (action != 1) return; // GLFW_PRESS
        var c = IrohOptions.config();
        int bound = Telemetry.hotkey(c).key;
        if (bound < 0 || key != bound) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        c.telemetryOverlay = !c.telemetryOverlay;
        IrohOptions.save();
    }
}
