package net.vulkanmod.mixin.chunk;

import dev.elemore.iroh.perf.PerfSwitches;
import net.minecraft.client.renderer.LevelRenderer;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P2-4 (perf/streaming, {@code iroh.chunk.bulkDirty}, default off): chunk and light data arriving call
 * LevelRenderer.setSectionDirtyWithNeighbors once per section (24 per chunk, more per light packet); vanilla's body is
 * 27 calls of setSectionDirty, each a section mark plus a region-cache drop (block 9: up to 891k marks in one frame).
 * With the switch on, Iroh marks the 3x3x3 block in one pass ({@link WorldRenderer#setSectionDirtyWithNeighbors}).
 * HEAD cancel at priority 1500 (Iroh rule); no pack mixin targets this method or setSectionDirty (injector_scan +
 * refmap scan 2026-11-24; elemore-core hooks ClientLevel.setSectionDirtyWithNeighbors, which still runs).
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
public abstract class BulkDirtyM {
    @Inject(method = "setSectionDirtyWithNeighbors(III)V", at = @At("HEAD"), cancellable = true)
    private void iroh$bulkDirty(int x, int y, int z, CallbackInfo ci) {
        if (!PerfSwitches.bulkDirtyOn) return;
        WorldRenderer wr = WorldRenderer.getInstance();
        if (wr == null || wr.getSectionGrid() == null) return;
        wr.setSectionDirtyWithNeighbors(x, y, z);
        ci.cancel();
    }
}
