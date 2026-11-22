package net.vulkanmod.mixin.profiling.slow;

import dev.elemore.iroh.diag.SlowFrames;
import dev.elemore.iroh.diag.SlowFrames.Phase;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * perf/hitches: the "render other" remainder of perf/frame-attrib split further: the client light engine
 * (pollLightUpdates + runLightUpdates, fed by chunk/light packets) and MultiBufferSource flushes (entity and block-entity
 * vertex data drawn). Observe-only, require 0.
 */
@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class SlowFramesLevel2M {
    private static final String RL = "renderLevel";
    private static final String POLL = "Lnet/minecraft/client/multiplayer/ClientLevel;pollLightUpdates()V";
    private static final String RUN = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I";
    private static final String END_TYPE = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch(Lnet/minecraft/client/renderer/RenderType;)V";
    private static final String END_ALL = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V";
    private static final String END_LAST = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endLastBatch()V";

    @Inject(method = RL, at = @At(value = "INVOKE", target = POLL), require = 0)
    private void iroh$lightIn(CallbackInfo ci) { if (SlowFrames.ON) SlowFrames.enter(Phase.LIGHT); }

    @Inject(method = RL, at = @At(value = "INVOKE", target = RUN, shift = At.Shift.AFTER), require = 0)
    private void iroh$lightOut(CallbackInfo ci) { if (SlowFrames.ON) SlowFrames.exit(Phase.LIGHT); }

    @Inject(method = RL, at = {@At(value = "INVOKE", target = END_TYPE), @At(value = "INVOKE", target = END_ALL),
            @At(value = "INVOKE", target = END_LAST)}, require = 0)
    private void iroh$flushIn(CallbackInfo ci) { if (SlowFrames.ON) SlowFrames.enter(Phase.BUFFER_FLUSH); }

    @Inject(method = RL, at = {@At(value = "INVOKE", target = END_TYPE, shift = At.Shift.AFTER),
            @At(value = "INVOKE", target = END_ALL, shift = At.Shift.AFTER),
            @At(value = "INVOKE", target = END_LAST, shift = At.Shift.AFTER)}, require = 0)
    private void iroh$flushOut(CallbackInfo ci) { if (SlowFrames.ON) SlowFrames.exit(Phase.BUFFER_FLUSH); }
}
