package net.vulkanmod.mixin.render;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iroh (rc5, td-1cb256): vanilla's main loop catches every OutOfMemoryError, calls emergencySave() (which HALTS the
 * integrated server: the world closes and looks like a disconnect) and only logs the error afterwards. LWJGL reports a full
 * thread MemoryStack ("Out of stack space.") and failed native allocations as OutOfMemoryError too. Log it at ERROR with
 * the stack BEFORE vanilla acts, so it is the first line of the log tail. Nothing is swallowed: vanilla's handling runs
 * unchanged. Priority 1500 (others' injectors at 1000 first).
 */
@Mixin(value = Minecraft.class, priority = 1500)
public abstract class IrohOomLogM {
    @Inject(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;emergencySave()V"))
    private void iroh$logOom(CallbackInfo ci, @Local OutOfMemoryError error) {
        String msg = String.valueOf(error.getMessage());
        boolean nativeSide = msg.contains("stack space") || msg.contains("off-heap") || msg.contains("native")
                || error.getClass() != OutOfMemoryError.class;
        net.vulkanmod.Initializer.LOGGER.error("Iroh: OutOfMemoryError on the render thread ({}){}; vanilla now runs emergencySave, "
                        + "which halts the integrated server (the world closes)", msg,
                nativeSide ? " [LWJGL / native allocation, not the Java heap]" : "", error);
        dev.elemore.iroh.diag.RenderWatchdog.noteFatal("OutOfMemoryError: " + msg);
    }
}
