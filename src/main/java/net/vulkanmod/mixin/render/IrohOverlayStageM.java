package net.vulkanmod.mixin.render;

import com.llamalad7.mixinextras.sugar.Local;
import dev.elemore.iroh.api.FrameStage;
import dev.elemore.iroh.apiimpl.FrameHooks;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iroh P1-13a (api 3.1): FrameStage.OVERLAY, just before GameRenderer.render's final GuiGraphics.flush() (after the HUD,
 * the loading overlay or screen, the saving indicator and toasts). The GuiGraphics is vanilla's local for the frame.
 * Priority 1500 (third-party injectors at 1000 first).
 */
@Mixin(value = GameRenderer.class, priority = 1500)
public abstract class IrohOverlayStageM {
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;flush()V"))
    private void iroh$overlay(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci, @Local GuiGraphics guiGraphics) {
        FrameHooks.dispatch2d(FrameStage.OVERLAY, guiGraphics);
    }
}
