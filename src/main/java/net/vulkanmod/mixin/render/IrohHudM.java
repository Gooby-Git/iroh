package net.vulkanmod.mixin.render;

import dev.elemore.iroh.config.IrohOptions;
import dev.elemore.iroh.ui.FpsOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HUD options the pack took from Sodium / Sodium Extra: the vignette switch and the FPS counter.
 * Priority 1500 so pack mods' injectors at the default 1000 run first (SKILL.md mixin rules).
 */
@Mixin(value = Gui.class, priority = 1500)
public abstract class IrohHudM {
    @Shadow @Final private DebugScreenOverlay debugOverlay;

    /** enableVignette = false: vanilla's vignette (and its world border tint) is not drawn. */
    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void iroh$vignette(GuiGraphics guiGraphics, Entity entity, CallbackInfo ci) {
        if (!IrohOptions.vignette()) ci.cancel();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void iroh$fps(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        FpsOverlay.render(guiGraphics, this.debugOverlay.showDebugScreen());
    }
}
