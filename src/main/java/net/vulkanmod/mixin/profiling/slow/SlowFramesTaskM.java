package net.vulkanmod.mixin.profiling.slow;

import dev.elemore.iroh.diag.SlowFrames;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * perf/frame-attrib: each task the render thread runs from its executor queue (packet handlers, recordRenderCall,
 * Minecraft.execute): count and the slowest one (named only when its frame is logged). SlowFrames ignores calls from
 * other threads (the server's event loop is the same class).
 */
@Mixin(BlockableEventLoop.class)
public abstract class SlowFramesTaskM {
    @Unique private static long iroh$taskStart;
    @Unique private static int iroh$taskDepth;

    @Inject(method = "doRunTask", at = @At("HEAD"), require = 0)
    private void iroh$taskIn(Runnable task, CallbackInfo ci) {
        if (SlowFrames.ON && (Object) this instanceof net.minecraft.client.Minecraft && iroh$taskDepth++ == 0)
            iroh$taskStart = SlowFrames.now();
    }

    @Inject(method = "doRunTask", at = @At("RETURN"), require = 0)
    private void iroh$taskOut(Runnable task, CallbackInfo ci) {
        if (SlowFrames.ON && (Object) this instanceof net.minecraft.client.Minecraft && --iroh$taskDepth == 0)
            SlowFrames.task(task, SlowFrames.now() - iroh$taskStart);
    }
}
