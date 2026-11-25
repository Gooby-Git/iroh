package net.vulkanmod.mixin.render.frame;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.elemore.iroh.perf.FrameLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** P2-10 (remap = false: blaze3d RenderSystem keeps its Mojang member names in intermediary): RenderSystem.limitDisplayFPS stays the entry point (Proctor's LimiterMixin and TaskBudgetM wrap its call site); the body is {@link FrameLimiter}. */
@Mixin(RenderSystem.class)
public abstract class LimiterM {
    @Inject(method = "limitDisplayFPS(I)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void iroh$limit(int fps, CallbackInfo ci) {
        if (FrameLimiter.VANILLA) return;
        FrameLimiter.limit(fps);
        ci.cancel();
    }
}
