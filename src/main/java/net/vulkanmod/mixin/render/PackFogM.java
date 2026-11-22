package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.elemore.iroh.config.FogDecision;
import dev.elemore.iroh.config.IrohOptions;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the distance fog option ({@code fogDistanceChunks}, -1 = off) after vanilla (and earlier mods) set the fog.
 * rc14 (td-f57b86): the decision is {@link FogDecision#removesFog}: like the OpenGL pack's Sodium Extra, only the
 * terrain distance fog goes; boss world fog (dragon / wither bar), blindness, darkness, fluids, the sky and mod fog stay.
 * Before rc14 every terrain fog without fluid/effect was removed, also the dragon fight's boss fog.
 */
@Mixin(value = FogRenderer.class, priority = 1500)
public abstract class PackFogM {
    @Inject(method = "setupFog", at = @At("TAIL"), require = 0)
    private static void iroh$packFog(Camera camera, FogRenderer.FogMode mode, float distance, boolean foggy, float partialTick, CallbackInfo ci) {
        int option = IrohOptions.fogDistanceChunks();
        if (option != -1 || mode != FogRenderer.FogMode.FOG_TERRAIN) return;
        FogDecision.Fluid fluid = switch (camera.getFluidInCamera()) {
            case NONE -> FogDecision.Fluid.NONE;
            case WATER -> FogDecision.Fluid.WATER;
            case LAVA -> FogDecision.Fluid.LAVA;
            case POWDER_SNOW -> FogDecision.Fluid.POWDER_SNOW;
        };
        FogDecision.Effect effect = FogDecision.Effect.NONE;
        if (camera.getEntity() instanceof LivingEntity e) {
            // vanilla's MOB_EFFECT_FOG order: blindness, then darkness (isEnabled = hasEffect)
            if (e.hasEffect(MobEffects.BLINDNESS)) effect = FogDecision.Effect.BLINDNESS;
            else if (e.hasEffect(MobEffects.DARKNESS)) effect = FogDecision.Effect.DARKNESS;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean bossFog = mc.gui != null && mc.gui.getBossOverlay().shouldCreateWorldFog();
        FogDecision.Fog current = new FogDecision.Fog(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
                RenderSystem.getShaderFogShape() == FogShape.CYLINDER ? FogDecision.Shape.CYLINDER : FogDecision.Shape.SPHERE);
        if (!FogDecision.removesFog(option, FogDecision.Mode.TERRAIN, fluid, effect, bossFog, distance, foggy, current)) return;
        RenderSystem.setShaderFogStart(FogDecision.OFF);
        RenderSystem.setShaderFogEnd(FogDecision.OFF);
    }
}
