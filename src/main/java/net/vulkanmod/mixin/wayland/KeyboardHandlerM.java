package net.vulkanmod.mixin.wayland;

import net.minecraft.client.KeyboardHandler;
import net.vulkanmod.config.Platform;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Upstream VulkanMod 85a3734 (tmpod), adapted to 1.21.1's {@code charTyped(long window, int codepoint, int modifiers)}.
 * On native Wayland GLFW delivers the unmodified character of a Ctrl+key combination as a char event, so Ctrl+V
 * pasted and then typed "v", Ctrl+A selected all and replaced it with "a". GLFW on X11 sends no char event for these
 * combinations; this drops it the same way. Priority 1500: other mods' HEAD injectors
 * into charTyped run first (Iroh's rule for HEAD-cancel mixins).
 */
@Mixin(value = KeyboardHandler.class, priority = 1500)
public class KeyboardHandlerM {

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void iroh$dropCtrlCharOnWayland(long window, int codepoint, int modifiers, CallbackInfo ci) {
        if (Platform.isWayLand() && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            ci.cancel();
        }
    }
}
