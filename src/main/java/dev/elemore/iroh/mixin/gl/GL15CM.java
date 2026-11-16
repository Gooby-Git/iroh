package dev.elemore.iroh.mixin.gl;

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
}
