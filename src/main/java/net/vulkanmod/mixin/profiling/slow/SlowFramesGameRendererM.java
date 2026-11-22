package net.vulkanmod.mixin.profiling.slow;

import dev.elemore.iroh.diag.SlowFrames;
import dev.elemore.iroh.diag.SlowFrames.Phase;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** perf/frame-attrib: GameRenderer.render (RENDER) and the HUD (GUI). Observe-only. */
@Mixin(value = GameRenderer.class, priority = 900)
public abstract class SlowFramesGameRendererM {
    private static final String GUI = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V";

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void iroh$renderIn(DeltaTracker dt, boolean b, CallbackInfo ci) {
        if (SlowFrames.ON) SlowFrames.enter(Phase.RENDER);
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void iroh$renderOut(DeltaTracker dt, boolean b, CallbackInfo ci) {
        if (SlowFrames.ON) SlowFrames.exit(Phase.RENDER);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = GUI), require = 0)
    private void iroh$guiIn(DeltaTracker dt, boolean b, CallbackInfo ci) {
        if (SlowFrames.ON) SlowFrames.enter(Phase.GUI);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = GUI, shift = At.Shift.AFTER), require = 0)
    private void iroh$guiOut(DeltaTracker dt, boolean b, CallbackInfo ci) {
        if (SlowFrames.ON) SlowFrames.exit(Phase.GUI);
    }
}
