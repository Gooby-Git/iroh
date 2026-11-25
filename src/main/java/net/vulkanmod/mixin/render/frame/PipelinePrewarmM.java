package net.vulkanmod.mixin.render.frame;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.vulkanmod.vulkan.shader.PipelinePrewarm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P2-11 (L8): the loading overlay closing = the resource reload (and so every ShaderInstance pipeline) is done: start the pre-warm worker
 * ({@code iroh.pipeline.prewarm}, default off: {@link PipelinePrewarm#kick} returns at one volatile read). Observe-only, never cancels.
 */
@Mixin(Minecraft.class)
public abstract class PipelinePrewarmM {
    @Inject(method = "setOverlay", at = @At("HEAD"), require = 0)
    private void iroh$prewarmAfterReload(Overlay overlay, CallbackInfo ci) {
        if (overlay == null) PipelinePrewarm.kick();
    }
}
