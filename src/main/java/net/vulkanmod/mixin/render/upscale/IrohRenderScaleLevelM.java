package net.vulkanmod.mixin.render.upscale;

import dev.elemore.iroh.temporal.TemporalRuntime;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Iroh P4-1a render scale: LevelRenderer's PostChains (entity outline, Fabulous transparency) render in the world
 * phase and copy the main depth, so they are sized like the world target (render size) while upscaling is on.
 * Identity with iroh.upscale.mode off.
 */
@Mixin(LevelRenderer.class)
public abstract class IrohRenderScaleLevelM {
    @ModifyArg(method = {"initOutline", "initTransparency", "resize"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PostChain;resize(II)V"), index = 0)
    private int iroh$chainW(int w) {
        return TemporalRuntime.chainWidth(w);
    }

    @ModifyArg(method = {"initOutline", "initTransparency", "resize"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PostChain;resize(II)V"), index = 1)
    private int iroh$chainH(int h) {
        return TemporalRuntime.chainHeight(h);
    }
}
