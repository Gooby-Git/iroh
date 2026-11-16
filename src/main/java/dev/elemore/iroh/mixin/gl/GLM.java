package dev.elemore.iroh.mixin.gl;

import dev.elemore.iroh.glshim.GlCaps;
import dev.elemore.iroh.glshim.GlShim;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * GL.getCapabilities() throws IllegalStateException on a thread that never made a context current, which is every
 * thread under Iroh. GPUBooster reads it in a RenderSystem.recordRenderCall (replayed in the first frame), XaeroWorldMap
 * in MapLimiter on the render tick, AsyncParticles in Backends.&lt;clinit&gt;. Answer with an all-false GLCapabilities
 * so they take their fallback path. A real capabilities object (own context on a helper thread) is returned untouched.
 */
@Mixin(value = GL.class, remap = false)
public class GLM {

    @Inject(method = "checkCapabilities", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohNoCapabilities(GLCapabilities caps, CallbackInfoReturnable<GLCapabilities> cir) {
        if (caps != null) return;
        GLCapabilities empty = GlCaps.empty();
        if (empty == null) return;
        GlShim.note("GL.getCapabilities()", "all-false GLCapabilities");
        cir.setReturnValue(empty);
    }
}
