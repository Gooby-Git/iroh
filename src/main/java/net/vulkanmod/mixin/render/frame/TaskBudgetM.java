package net.vulkanmod.mixin.render.frame;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.elemore.iroh.diag.SlowFrames;
import dev.elemore.iroh.perf.TaskBudget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * perf/hitches: the per-frame task drain of Minecraft.runTick goes through {@link TaskBudget} (in order, time budgeted,
 * hard latency bound). Only this call site: every other runAllTasks caller (disconnect, managed blocks, loading) still
 * drains fully. Off with {@code -Diroh.tasks.budget=false}. Also times the drain for -Diroh.slowFrames.
 */
@Mixin(value = Minecraft.class, priority = 900)
public abstract class TaskBudgetM {
    @Unique private static TaskBudget iroh$budget;
    @Unique private static long iroh$sleepStart;
    private static final String LIMIT = "Lcom/mojang/blaze3d/systems/RenderSystem;limitDisplayFPS(I)V";

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = LIMIT), require = 0)
    private void iroh$sleepIn(boolean tick, CallbackInfo ci) {
        if (TaskBudget.ENABLED) iroh$sleepStart = System.nanoTime();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = LIMIT, shift = At.Shift.AFTER), require = 0)
    private void iroh$sleepOut(boolean tick, CallbackInfo ci) {
        if (TaskBudget.ENABLED && iroh$budget != null) iroh$budget.idle(System.nanoTime() - iroh$sleepStart);
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    private void iroh$budgetFrameStart(boolean tick, CallbackInfo ci) {
        if (TaskBudget.ENABLED) {
            if (iroh$budget == null) iroh$budget = TaskBudget.forGame();
            iroh$budget.frameStart();
        }
    }

    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"))
    private void iroh$budgetedTasks(Minecraft mc, Operation<Void> original) {
        if (SlowFrames.ON) SlowFrames.enter(SlowFrames.Phase.TASKS);
        try {
            if (!TaskBudget.ENABLED || iroh$budget == null) {
                original.call(mc);
                return;
            }
            // full drain whenever a budget must not apply: no world, a screen or overlay is open, paused, no player
            boolean full = mc.level == null || mc.player == null || mc.screen != null || mc.getOverlay() != null || mc.isPaused();
            if (full) {
                original.call(mc);
                return;
            }
            iroh$budget.drain(mc::pollTask, mc::getPendingTasksCount, false, TaskBudget.targetNs(mc.options.enableVsync().get() ? 0 : mc.options.framerateLimit().get()));
            if (SlowFrames.ON) SlowFrames.count(SlowFrames.Counter.DEFERRED_TASKS, iroh$budget.lastLeft);
        } finally {
            if (SlowFrames.ON) SlowFrames.exit(SlowFrames.Phase.TASKS);
        }
    }
}
