package net.vulkanmod.mixin.render.target;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.vulkanmod.gl.VkGlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iroh (rc12, td-598433) diagnostics for RenderTarget buffer lifetimes, observe-only (HEAD injects that never cancel,
 * so every other mod's injectors into createBuffers / destroyBuffers / resize keep working):
 * <ul>
 * <li>resize storm: a target resized more than 30 times within 60 s logs ONE warning with the caller stack;</li>
 * <li>orphaned buffers: createBuffers on a target whose previous colour/depth textures were never deleted (no
 *     destroyBuffers in between, or a destroyBuffers that did not reach the GL texture delete) logs ONE warning naming
 *     the target class, the stale GL ids and the stack: the exact place where window-sized images start to leak.</li>
 * </ul>
 * Cost: a few field reads per resize/create (rare calls).
 */
@Mixin(value = RenderTarget.class, priority = 1500)
public abstract class RenderTargetLifecycleM {
    @Shadow protected int depthBufferId;
    @Shadow protected int colorTextureId;

    @Unique private long iroh$windowStart;
    @Unique private int iroh$resizes;
    @Unique private boolean iroh$stormLogged;
    @Unique private int iroh$lastColor = -1, iroh$lastDepth = -1;
    @Unique private boolean iroh$created;
    @Unique private static boolean iroh$orphanLogged;

    @Inject(method = "resize", at = @At("HEAD"))
    private void iroh$countResize(int width, int height, boolean clearError, CallbackInfo ci) {
        long now = System.nanoTime();
        if (now - this.iroh$windowStart > 60_000_000_000L) {
            this.iroh$windowStart = now;
            this.iroh$resizes = 0;
        }
        if (++this.iroh$resizes > 30 && !this.iroh$stormLogged) {
            this.iroh$stormLogged = true;
            net.vulkanmod.Initializer.LOGGER.warn("[iroh] resize storm: {} {}x{} resized {} times within 60 s by {}",
                    this.getClass().getName(), width, height, this.iroh$resizes, dev.elemore.iroh.diag.EmuStats.callerMod(),
                    new Throwable("resized from"));
        }
    }

    @Inject(method = "createBuffers", at = @At("HEAD"))
    private void iroh$checkOrphans(int width, int height, boolean clearError, CallbackInfo ci) {
        if (this.iroh$created && !iroh$orphanLogged) {
            boolean colorAlive = this.iroh$lastColor > 0 && VkGlTexture.exists(this.iroh$lastColor);
            boolean depthAlive = this.iroh$lastDepth > 0 && VkGlTexture.exists(this.iroh$lastDepth);
            if (colorAlive || depthAlive) {
                iroh$orphanLogged = true;
                net.vulkanmod.Initializer.LOGGER.warn("[iroh] RenderTarget {} recreated while its previous textures still exist (colour {} {}, depth {} {}); "
                                + "destroyBuffers did not run or did not delete them. Caller {} (logged once)",
                        this.getClass().getName(), this.iroh$lastColor, colorAlive ? "alive" : "deleted", this.iroh$lastDepth,
                        depthAlive ? "alive" : "deleted", dev.elemore.iroh.diag.EmuStats.callerMod(), new Throwable("createBuffers from"));
            }
        }
    }

    @Inject(method = "createBuffers", at = @At("RETURN"))
    private void iroh$remember(int width, int height, boolean clearError, CallbackInfo ci) {
        this.iroh$created = true;
        this.iroh$lastColor = this.colorTextureId;
        this.iroh$lastDepth = this.depthBufferId;
    }
}
