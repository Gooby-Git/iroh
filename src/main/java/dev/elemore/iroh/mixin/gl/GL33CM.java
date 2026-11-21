package dev.elemore.iroh.mixin.gl;

import dev.elemore.iroh.glshim.GlQueries;
import dev.elemore.iroh.glshim.GlShim;
import org.lwjgl.opengl.GL33C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P1-10 (Reflex AntiLag): array overloads of the GL 3.3 timer-query reads and GL 3.2 glGetInteger64v (LWJGL defines
 * glGetInteger64v in GL32C; GL33C re-declares nothing, so GL32CM covers it). They bypass the GlNet table (see GL11CM).
 */
@Mixin(value = GL33C.class, remap = false)
public class GL33CM {

    @Inject(method = "glGetQueryObjecti64v(II[J)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohGetQueryObjecti64vArray(int id, int pname, long[] params, CallbackInfo ci) {
        if (!GlShim.noContext()) return;
        params[0] = GlQueries.object(id, pname);
        ci.cancel();
    }

    @Inject(method = "glGetQueryObjectui64v(II[J)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohGetQueryObjectui64vArray(int id, int pname, long[] params, CallbackInfo ci) {
        if (!GlShim.noContext()) return;
        params[0] = GlQueries.object(id, pname);
        ci.cancel();
    }
}
