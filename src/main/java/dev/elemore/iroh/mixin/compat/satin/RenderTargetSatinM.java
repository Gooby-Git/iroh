package dev.elemore.iroh.mixin.compat.satin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.elemore.iroh.compat.SatinStillDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iroh (rc13, td-598433): Satin 2.0.0 gives every RenderTarget with depth an extra "still depth" texture
 * ({@code satin$stillDepthTexture}, created by its inject in createBuffers) and releases it from an inject INSIDE the
 * vanilla destroyBuffers body (at the depthBufferId read). GPUBooster 1.3.1's FramebufferMixin.deleteRBON HEAD-cancels
 * destroyBuffers and deletes only the vanilla ids, so Satin's release never runs: one window-sized depth texture leaks
 * per target per resize (11 PostChain targets in the pack: +11 images per window resize). The same pair leaks GL
 * textures under OpenGL too; it is not an Iroh bug, but Iroh pays for it in device memory.
 * <p>
 * Fix (third-party jars untouched): release Satin's texture ourselves, before anyone can cancel.
 * <ul>
 *   <li>destroyBuffers HEAD at priority 900: Mixin resolves HEAD for all mixins on the original bytecode and inserts in
 *       apply order, so this runs before GPUBooster's cancel (priority 1000). It sets the field to -1, so Satin's own
 *       release (when nothing cancels) sees nothing to do: never a double delete.</li>
 *   <li>createBuffers HEAD (backup, order-independent: GPUBooster's createBuffers HEAD does not cancel): a still-depth
 *       texture alive at that point is about to be overwritten by Satin's create inject, so it is released first.</li>
 * </ul>
 * Applied only when Satin is loaded (IrohCompatPlugin: package {@code compat.satin}).
 */
@Mixin(value = RenderTarget.class, priority = 900)
public abstract class RenderTargetSatinM {

    @Inject(method = "destroyBuffers", at = @At("HEAD"))
    private void iroh$releaseStillDepthOnDestroy(CallbackInfo ci) {
        SatinStillDepth.release(this, "destroyBuffers");
    }

    @Inject(method = "createBuffers", at = @At("HEAD"))
    private void iroh$releaseStillDepthOnCreate(int width, int height, boolean clearError, CallbackInfo ci) {
        SatinStillDepth.release(this, "createBuffers");
    }
}
