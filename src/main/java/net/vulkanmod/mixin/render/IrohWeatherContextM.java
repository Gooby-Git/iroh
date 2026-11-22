package net.vulkanmod.mixin.render;

import dev.elemore.iroh.shaderpack.runtime.DrawIds;
import dev.elemore.iroh.shaderpack.runtime.Substitution;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iroh P3-11: observe-only weather context for the world pass. Rain and snow are drawn with the PARTICLE core shader,
 * so the shader name alone cannot tell gbuffers_weather from gbuffers_textured; draws inside renderSnowAndRain are class
 * weather. Nothing cancelled; require = 0; if a HEAD cancel skips RETURN, the world pass resets the flag at END.
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
public abstract class IrohWeatherContextM {
    @Inject(method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V", at = @At("HEAD"), require = 0)
    private void iroh$weatherHead(LightTexture lt, float partial, double x, double y, double z, CallbackInfo ci) {
        if ((Substitution.HOOK & Substitution.WORLD) != 0)
            DrawIds.weather = true;
    }

    @Inject(method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V", at = @At("RETURN"), require = 0)
    private void iroh$weatherReturn(LightTexture lt, float partial, double x, double y, double z, CallbackInfo ci) {
        DrawIds.weather = false;
    }
}
