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
 * Iroh P1-13a: the HUD frame stages of dev.elemore.iroh.api.IrohFrame. BEFORE_HUD at Gui.render HEAD, AFTER_HUD at its
 * TAIL; the GUI batch is flushed before and after each hook so hook geometry lands in order. Priority 1500: other mods'
 * Gui.render injectors (1000) run first. Gui.render runs whenever a level is rendered, also with hideGui (vanilla skips
 * only the HUD layers), so these stages fire during F1 and cutscenes; not on the title screen.
 */
@Mixin(value = Gui.class, priority = 1500)
public abstract class IrohFrameStagesM {
    @Inject(method = "render", at = @At("HEAD"))
    private void iroh$beforeHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        FrameHooks.dispatch2d(FrameStage.BEFORE_HUD, guiGraphics);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void iroh$afterHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        FrameHooks.dispatch2d(FrameStage.AFTER_HUD, guiGraphics);
    }
}
