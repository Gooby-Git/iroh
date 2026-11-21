package net.vulkanmod.mixin.render;

import dev.elemore.iroh.api.FrameStage;
import dev.elemore.iroh.apiimpl.FrameHooks;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iroh P1-13a: the HUD frame stages of dev.elemore.iroh.api.IrohFrame. BEFORE_HUD at Gui.render HEAD (world finished and
 * composited, GUI matrices set); AFTER_HUD at its TAIL after flushing the GUI's batched draws so hook geometry lands on
 * top of the HUD. Priority 1500: other mods' Gui.render injectors (1000) run first. Not dispatched while hideGui skips
 * Gui.render (F1) or with no world (title screen): HUD stages are in-game only.
 */
@Mixin(value = Gui.class, priority = 1500)
public abstract class IrohFrameStagesM {
    @Inject(method = "render", at = @At("HEAD"))
    private void iroh$beforeHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (FrameHooks.count(FrameStage.BEFORE_HUD) != 0) {
            guiGraphics.flush();
            FrameHooks.dispatch(FrameStage.BEFORE_HUD);
        } else if (dev.elemore.iroh.apiimpl.FrameProfiler.ON) {
            FrameHooks.dispatch(FrameStage.BEFORE_HUD);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void iroh$afterHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (FrameHooks.count(FrameStage.AFTER_HUD) != 0) {
            guiGraphics.flush();
            FrameHooks.dispatch(FrameStage.AFTER_HUD);
        } else if (dev.elemore.iroh.apiimpl.FrameProfiler.ON) {
            FrameHooks.dispatch(FrameStage.AFTER_HUD);
        }
    }
}
