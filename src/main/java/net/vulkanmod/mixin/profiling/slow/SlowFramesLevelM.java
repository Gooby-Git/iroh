package net.vulkanmod.mixin.profiling.slow;

import dev.elemore.iroh.diag.SlowFrames;
import dev.elemore.iroh.diag.SlowFrames.Phase;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * perf/frame-attrib: world phases at their call sites in renderLevel (before / after each call, so a callee cancelled
 * by another mixin is still closed). Observe-only, require 0.
 */
@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class SlowFramesLevelM {
    private static final String RL = "renderLevel";
    private static final String SKY = "Lnet/minecraft/client/renderer/LevelRenderer;renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V";
    private static final String CLOUDS = "Lnet/minecraft/client/renderer/LevelRenderer;renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDD)V";
    private static final String WEATHER = "Lnet/minecraft/client/renderer/LevelRenderer;renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V";
    private static final String PARTICLES = "Lnet/minecraft/client/particle/ParticleEngine;render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F)V";
    private static final String ENTITY = "Lnet/minecraft/client/renderer/LevelRenderer;renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V";

    @Inject(method = RL, at = @At(value = "INVOKE", target = SKY), require = 0)
    private void iroh$skyIn(CallbackInfo ci) { if (SlowFrames.ON) SlowFrames.enter(Phase.SKY); }

    @Inject(method = RL, at = @At(value = "INVOKE", target = SKY, shift = At.Shift.AFTER), require = 0)
    private void iroh$skyOut(CallbackInfo ci) { if (SlowFrames.ON) SlowFrames.exit(Phase.SKY); }

    @Inject(method = RL, at = @At(value = "INVOKE", target = CLOUDS), require = 0)
    private void iroh$cloudsIn(CallbackInfo ci) { if (SlowFrames.ON) SlowFrames.enter(Phase.CLOUDS); }

    @Inject(method = RL, at = @At(value = "INVOKE", target = CLOUDS, shift = At.Shift.AFTER), require = 0)
    private void iroh$cloudsOut(CallbackInfo ci) { if (SlowFrames.ON) SlowFrames.exit(Phase.CLOUDS); }

    @Inject(method = RL, at = @At(value = "INVOKE", target = WEATHER), require = 0)
    private void iroh$weatherIn(CallbackInfo ci) { if (SlowFrames.ON) SlowFrames.enter(Phase.WEATHER); }

    @Inject(method = RL, at = @At(value = "INVOKE", target = WEATHER, shift = At.Shift.AFTER), require = 0)
    private void iroh$weatherOut(CallbackInfo ci) { if (SlowFrames.ON) SlowFrames.exit(Phase.WEATHER); }

    @Inject(method = RL, at = @At(value = "INVOKE", target = PARTICLES), require = 0)
    private void iroh$particlesIn(CallbackInfo ci) { if (SlowFrames.ON) SlowFrames.enter(Phase.PARTICLES); }

    @Inject(method = RL, at = @At(value = "INVOKE", target = PARTICLES, shift = At.Shift.AFTER), require = 0)
    private void iroh$particlesOut(CallbackInfo ci) { if (SlowFrames.ON) SlowFrames.exit(Phase.PARTICLES); }

    @Inject(method = RL, at = @At(value = "INVOKE", target = ENTITY), require = 0)
    private void iroh$entityIn(CallbackInfo ci) {
        if (!SlowFrames.ON) return;
        SlowFrames.count(SlowFrames.Counter.ENTITIES, 1);
        SlowFrames.enter(Phase.ENTITIES);
    }

    @Inject(method = RL, at = @At(value = "INVOKE", target = ENTITY, shift = At.Shift.AFTER), require = 0)
    private void iroh$entityOut(CallbackInfo ci) { if (SlowFrames.ON) SlowFrames.exit(Phase.ENTITIES); }
}
