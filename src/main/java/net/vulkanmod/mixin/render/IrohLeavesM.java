package net.vulkanmod.mixin.render;

import dev.elemore.iroh.config.IrohOptions;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Leaves quality override. Vanilla calls setFancy(Minecraft.useFancyGraphics()) whenever the Graphics setting changes
 * and on level reload; with leavesQuality FANCY or FAST the argument is replaced, DEFAULT leaves it alone. It only edits
 * the argument, so every other mod's setFancy hook still runs.
 */
@Mixin(value = ItemBlockRenderTypes.class, priority = 1500)
public abstract class IrohLeavesM {
    @ModifyVariable(method = "setFancy", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static boolean iroh$leaves(boolean vanillaFancy) {
        return IrohOptions.leavesFancy(vanillaFancy);
    }
}
