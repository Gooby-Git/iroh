package dev.elemore.iroh.mixin.reload;

import dev.elemore.iroh.shader.ShaderFallbacks;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Optional;

/**
 * P1-2a: makes the end of every resource reload visible in the log. Vanilla swallows the throwable of a
 * failed recovery reload ({@code Minecraft.abortResourcePackRecovery} gets none), which hid why the pack
 * stayed on the splash. Logs only; the argument is passed on unchanged.
 */
@Mixin(LoadingOverlay.class)
public class LoadingOverlayM {

    @ModifyArg(method = "render", require = 0,
            at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
    private Object iroh$logReloadResult(Object result) {
        if (result instanceof Optional<?> opt) {
            if (opt.isPresent() && opt.get() instanceof Throwable t) {
                Initializer.LOGGER.error("Iroh: resource reload failed", t);
            } else if (opt.isEmpty()) {
                Initializer.LOGGER.info("Iroh: resource reload finished; shader fallbacks so far: {}",
                        ShaderFallbacks.fallbackShaders());
            }
        }
        return result;
    }
}
