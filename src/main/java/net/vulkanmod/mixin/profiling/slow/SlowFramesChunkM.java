package net.vulkanmod.mixin.profiling.slow;

import dev.elemore.iroh.diag.SlowFrames;
import dev.elemore.iroh.diag.SlowFrames.Phase;
import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** perf/hitches: the chunk-data part of a chunk packet (LevelChunk replace + block entities + load events). */
@Mixin(value = ClientChunkCache.class, priority = 900)
public abstract class SlowFramesChunkM {
    @Inject(method = "replaceWithPacketData", at = @At("HEAD"), require = 0)
    private void iroh$chunkIn(CallbackInfoReturnable<?> cir) {
        if (!SlowFrames.ON) return;
        SlowFrames.count(SlowFrames.Counter.CHUNKS, 1);
        SlowFrames.enter(Phase.CHUNK_PACKET);
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"), require = 0)
    private void iroh$chunkOut(CallbackInfoReturnable<?> cir) {
        if (SlowFrames.ON) SlowFrames.exit(Phase.CHUNK_PACKET);
    }
}
