package dev.elemore.iroh.mixin.gl;

import dev.elemore.iroh.glshim.GlCaps;
import dev.elemore.iroh.glshim.GlNet;
import dev.elemore.iroh.glshim.GlShim;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.FunctionProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <ul>
 *   <li>{@code GL.create(FunctionProvider)} TAIL (runs inside GL's static initializer): install the GL safety net
 *       ({@link GlNet}) right after LWJGL built its "function missing -> abort" table.</li>
 *   <li>{@code GL.getCapabilities()} throws IllegalStateException on a thread that never made a context current,
 *       which is every thread under Iroh. GPUBooster reads it in a RenderSystem.recordRenderCall, XaeroWorldMap in
 *       MapLimiter, AsyncParticles in Backends.&lt;clinit&gt;. Answer with an all-false GLCapabilities so they take
 *       their fallback path. A real capabilities object (own context on a helper thread) is returned untouched.</li>
 *   <li>{@code GL.getICD()} (array overloads): on a thread without a context, the real libGL entry points, which are
 *       no-ops then, instead of the all-false object whose zero addresses make LWJGL throw.</li>
 * </ul>
 */
@Mixin(value = GL.class, remap = false)
public class GLM {

    @Inject(method = "create(Lorg/lwjgl/system/FunctionProvider;)V", at = @At("TAIL"), remap = false)
    private static void irohInstallNet(FunctionProvider provider, CallbackInfo ci) {
        GlNet.install();
    }

    @Inject(method = "getICD", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohIcd(CallbackInfoReturnable<GLCapabilities> cir) {
        if (!GlShim.noContext()) return;
        GLCapabilities icd = GlNet.icdCapabilities();
        if (icd != null) cir.setReturnValue(icd);
    }

    @Inject(method = "checkCapabilities", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohNoCapabilities(GLCapabilities caps, CallbackInfoReturnable<GLCapabilities> cir) {
        if (caps != null) return;
        GLCapabilities empty = GlCaps.empty();
        if (empty == null) return;
        GlShim.note("GL.getCapabilities()", "all-false GLCapabilities");
        cir.setReturnValue(empty);
    }
}
