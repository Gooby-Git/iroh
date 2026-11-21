package dev.elemore.iroh.mixin.gl;

import dev.elemore.iroh.glshim.GlQueries;
import dev.elemore.iroh.glshim.GlShim;
import org.lwjgl.opengl.GL32C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** P1-10 (Reflex AntiLag): {@code glGetInteger64v(int, long[])} bypasses the GlNet table (see GL11CM). */
@Mixin(value = GL32C.class, remap = false)
public class GL32CM {

    @Inject(method = "glGetInteger64v(I[J)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohGetInteger64vArray(int pname, long[] params, CallbackInfo ci) {
        if (!GlShim.noContext()) return;
        params[0] = GlQueries.integer64(pname);
        ci.cancel();
    }
}
