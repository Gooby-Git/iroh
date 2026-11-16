package dev.elemore.iroh.mixin.gl;

import dev.elemore.iroh.glshim.GlShim;
import dev.elemore.iroh.glshim.GlStrings;
import org.lwjgl.opengl.GL11C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Core-profile GL11 entry points that mods call directly and that would abort the JVM with
 * "No context is current" under Iroh. Boot path only (P1-1, P1-1b); the full shim is P1-2.
 * GL11.glGetString / glGetError delegate here, so both are covered.
 *
 * Injected at HEAD instead of overwritten, and answering only when the thread has no real GL context: a mod that
 * created its own GLFW context on a helper thread (DreamDisplays, WaterMedia) keeps talking to the real driver.
 */
@Mixin(value = GL11C.class, remap = false)
public class GL11CM {

    // first seen from ImmediatelyFast.windowInit (vendor workaround check), then ResourcefulLib / AsyncParticles
    @Inject(method = "glGetString", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohGlGetString(int name, CallbackInfoReturnable<String> cir) {
        if (GlShim.noContext()) cir.setReturnValue(GlStrings.get(name));
    }

    // ImmediatelyFast's MixinGL11 (when its error-check switch is on), XaeroWorldMap, WaterMedia
    @Inject(method = "glGetError", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohGlGetError(CallbackInfoReturnable<Integer> cir) {
        if (GlShim.noContext()) {
            GlShim.note("glGetError()", 0);
            cir.setReturnValue(0);
        }
    }
}
