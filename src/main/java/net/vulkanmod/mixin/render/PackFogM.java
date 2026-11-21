package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.elemore.iroh.config.IrohOptions;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the terrain fog option ({@code fogDistanceChunks}, -1 = off) after vanilla (and other mods) set the fog.
 * Replaces the PackFogSetting stopgap: the pack's Sodium Extra value is migrated into config/iroh.json on first run.
 */
@Mixin(value = FogRenderer.class, priority = 1500)
public abstract class PackFogM {
    @Inject(method = "setupFog", at = @At("TAIL"), require = 0)
    private static void iroh$packFog(Camera camera, FogRenderer.FogMode mode, float distance, boolean foggy, float partialTick, CallbackInfo ci) {
        if (mode != FogRenderer.FogMode.FOG_TERRAIN || camera.getFluidInCamera() != FogType.NONE) return;
        if (camera.getEntity() instanceof LivingEntity e && (e.hasEffect(MobEffects.BLINDNESS) || e.hasEffect(MobEffects.DARKNESS))) return;
        if (IrohOptions.fogDistanceChunks() != -1) return;
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
    }
}
