package dev.elemore.iroh.mixin.gl;

import dev.elemore.iroh.glshim.GlQueries;
import dev.elemore.iroh.glshim.GlShim;
import net.vulkanmod.gl.VkGlBuffer;
import org.lwjgl.opengl.GL15C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Array overloads of buffer name management (they bypass the GlNet table, see GL11CM). */
@Mixin(value = GL15C.class, remap = false)
public class GL15CM {

    @Inject(method = "glGenBuffers([I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohGenBuffersArray(int[] buffers, CallbackInfo ci) {
        if (!GlShim.noContext()) return;
        for (int k = 0; k < buffers.length; k++) buffers[k] = VkGlBuffer.glGenBuffers();
        ci.cancel();
    }

    @Inject(method = "glDeleteBuffers([I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohDeleteBuffersArray(int[] buffers, CallbackInfo ci) {
        if (!GlShim.noContext()) return;
        for (int b : buffers) VkGlBuffer.glDeleteBuffers(b);
        ci.cancel();
    }

    // ---- query objects (P1-10, Reflex AntiLag): array overloads of GlQueries

    @Inject(method = "glGenQueries([I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohGenQueriesArray(int[] ids, CallbackInfo ci) {
        if (!GlShim.noContext()) return;
        for (int k = 0; k < ids.length; k++) ids[k] = GlQueries.gen();
        ci.cancel();
    }

    @Inject(method = "glDeleteQueries([I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohDeleteQueriesArray(int[] ids, CallbackInfo ci) {
        if (!GlShim.noContext()) return;
        for (int id : ids) GlQueries.delete(id);
        ci.cancel();
    }

    @Inject(method = "glGetQueryiv(II[I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohGetQueryivArray(int target, int pname, int[] params, CallbackInfo ci) {
        if (!GlShim.noContext()) return;
        params[0] = GlQueries.queryiv(target, pname);
        ci.cancel();
    }

    @Inject(method = "glGetQueryObjectiv(II[I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohGetQueryObjectivArray(int id, int pname, int[] params, CallbackInfo ci) {
        if (!GlShim.noContext()) return;
        params[0] = (int) GlQueries.object(id, pname);
        ci.cancel();
    }

    @Inject(method = "glGetQueryObjectuiv(II[I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void irohGetQueryObjectuivArray(int id, int pname, int[] params, CallbackInfo ci) {
        if (!GlShim.noContext()) return;
        params[0] = (int) GlQueries.object(id, pname);
        ci.cancel();
    }
}
