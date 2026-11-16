package dev.elemore.iroh.mixin.gl;

import dev.elemore.iroh.glshim.GlShim;
import dev.elemore.iroh.glshim.GlState;
import dev.elemore.iroh.glshim.GlStrings;
import net.vulkanmod.gl.VkGlTexture;
import org.lwjgl.opengl.GL11C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * GL11C entry points that the native-level safety net ({@code GlNet}) cannot answer:
 * <ul>
 *   <li>glGetString: the Java wrapper turns the native's char* into a String; Iroh answers with Java strings.</li>
 *   <li>array overloads ({@code int[]}, {@code float[]}): LWJGL calls them through {@code GL.getICD()} and
 *       {@code JNI.callPV}, not through the per-thread table, so the net never sees them. Without a context they
 *       would reach libGL's no-op entry points and return nothing; these answer from {@link GlState} instead.</li>
 * </ul>
 * Every inject is HEAD + cancellable on a non-native method (vanilla body kept for threads with a real context:
 * DreamDisplays, WaterMedia on their own threads), and answers only when no context is current.
 * GL11 delegates to GL11C, so callers of either are covered. glGetError moved to the net (P1-2): it is a native,
 * and the old @Overwrite also answered 0 to threads with a real context.
 */
@Mixin(value = GL11C.class, remap = false)
public class GL11CM {

    // first seen from ImmediatelyFast.windowInit (vendor workaround check), then ResourcefulLib / AsyncParticles
    @Inject(method = "glGetString", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohGlGetString(int name, CallbackInfoReturnable<String> cir) {
        if (GlShim.noContext()) cir.setReturnValue(GlStrings.get(name));
    }

    @Inject(method = "glGetIntegerv(I[I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohGetIntegervArray(int pname, int[] params, CallbackInfo ci) {
        if (!GlShim.noContext()) return;
        GlState.getIntegerv(pname, params);
        ci.cancel();
    }

    @Inject(method = "glGetFloatv(I[F)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohGetFloatvArray(int pname, float[] params, CallbackInfo ci) {
        if (!GlShim.noContext()) return;
        GlState.getFloatv(pname, params);
        ci.cancel();
    }

    @Inject(method = "glGenTextures([I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohGenTexturesArray(int[] textures, CallbackInfo ci) {
        if (!GlShim.noContext()) return;
        for (int k = 0; k < textures.length; k++) textures[k] = VkGlTexture.genTextureId();
        ci.cancel();
    }

    @Inject(method = "glDeleteTextures([I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohDeleteTexturesArray(int[] textures, CallbackInfo ci) {
        if (!GlShim.noContext()) return;
        for (int t : textures) VkGlTexture.glDeleteTextures(t);
        ci.cancel();
    }
}
