package net.vulkanmod.mixin.profiling.slow;

import dev.elemore.iroh.diag.SlowFrames;
import dev.elemore.iroh.diag.SlowFrames.Phase;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * perf/frame-attrib: frame boundaries and the runTick phases for {@code -Diroh.slowFrames}. Observe-only injects
 * (never cancel, require 0). Priority 900: the frame starts before Iroh's own runTick HEAD work (preInitFrame).
 */
@Mixin(value = Minecraft.class, priority = 900)
public abstract class SlowFramesMinecraftM {
    private static final String RUN_ALL = "Lnet/minecraft/client/Minecraft;runAllTasks()V";
    private static final String TICK = "Lnet/minecraft/client/Minecraft;tick()V";
    private static final String LIMIT = "Lcom/mojang/blaze3d/systems/RenderSystem;limitDisplayFPS(I)V";

    @Inject(method = "runTick", at = @At("HEAD"), require = 0)
    private void iroh$slowFrameStart(boolean tick, CallbackInfo ci) {
        if (!SlowFrames.ON) return;
        SlowFrames.frameStart();
        var wr = net.vulkanmod.render.chunk.WorldRenderer.getInstance();
        var td = wr != null ? wr.getTaskDispatcher() : null;
        if (td != null) {
            SlowFrames.set(SlowFrames.Counter.BUILDERS_BUSY, td.busyBuilders());
            SlowFrames.set(SlowFrames.Counter.PENDING_BUILDS, td.pendingTaskCount());
            SlowFrames.set(SlowFrames.Counter.PENDING_UPLOADS, td.pendingResultCount());
        }
    }

    @Inject(method = "runTick", at = @At("RETURN"), require = 0)
    private void iroh$slowFrameEnd(boolean tick, CallbackInfo ci) {
        if (SlowFrames.ON) SlowFrames.frameEnd();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = RUN_ALL), require = 0)
    private void iroh$tasksIn(boolean tick, CallbackInfo ci) {
        if (SlowFrames.ON) SlowFrames.enter(Phase.TASKS);
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = RUN_ALL, shift = At.Shift.AFTER), require = 0)
    private void iroh$tasksOut(boolean tick, CallbackInfo ci) {
        if (SlowFrames.ON) SlowFrames.exit(Phase.TASKS);
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = TICK), require = 0)
    private void iroh$tickIn(boolean tick, CallbackInfo ci) {
        if (!SlowFrames.ON) return;
        SlowFrames.count(SlowFrames.Counter.TICKS, 1);
        SlowFrames.enter(Phase.TICK);
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = TICK, shift = At.Shift.AFTER), require = 0)
    private void iroh$tickOut(boolean tick, CallbackInfo ci) {
        if (SlowFrames.ON) SlowFrames.exit(Phase.TICK);
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = LIMIT), require = 0)
    private void iroh$limitIn(boolean tick, CallbackInfo ci) {
        if (SlowFrames.ON) SlowFrames.enter(Phase.LIMITER);
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = LIMIT, shift = At.Shift.AFTER), require = 0)
    private void iroh$limitOut(boolean tick, CallbackInfo ci) {
        if (SlowFrames.ON) SlowFrames.exit(Phase.LIMITER);
    }

    @Inject(method = "destroy", at = @At("HEAD"), require = 0)
    private void iroh$slowFramesQuit(CallbackInfo ci) {
        if (SlowFrames.ON) SlowFrames.quit();
    }
}
