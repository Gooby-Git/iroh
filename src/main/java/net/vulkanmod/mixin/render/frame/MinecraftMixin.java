package net.vulkanmod.mixin.render.frame;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Shadow public boolean noRender;

    @Inject(method = "runTick", at = @At(value = "HEAD"))
    private void preFrameOps(boolean bl, CallbackInfo ci) {
        try {
            Renderer.getInstance().preInitFrame();
        } catch (dev.elemore.iroh.memory.MemoryGuard.IrohOutOfMemory e) {
            // rc8: logged by the guard; the world is closed at the next frame start. Never rethrown into a crash loop.
        }
    }

    /** rc8: an out-of-memory inside the frame abandons that frame instead of crashing the game. */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "runTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void iroh$guardRender(net.minecraft.client.renderer.GameRenderer instance, net.minecraft.client.DeltaTracker deltaTracker, boolean b,
                                  com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        try {
            original.call(instance, deltaTracker, b);
        } catch (dev.elemore.iroh.memory.MemoryGuard.IrohOutOfMemory e) {
            // logged once by the guard; the frame is abandoned
        }
    }

    // Main target (framebuffer) ops
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void beginRender(int i, boolean bl) {
        RenderSystem.clear(i, bl);
        try {
            Renderer.getInstance().beginFrame();
        } catch (dev.elemore.iroh.memory.MemoryGuard.IrohOutOfMemory e) {
            // rc8: logged by the guard; see preFrameOps
        }
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At(value = "RETURN"))
    private void beginRender2(CallbackInfo ci) {
        Renderer.getInstance().beginFrame();
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V"))
    private void redirectMainTarget1(RenderTarget instance, boolean bl) {
        Renderer.getInstance().getMainPass().mainTargetBindWrite();
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;unbindWrite()V"))
    private void redirectMainTarget2(RenderTarget instance) {
        Renderer.getInstance().getMainPass().mainTargetUnbindWrite();
    }

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private void removeBlit(RenderTarget instance, int i, int j) {
    }


    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;yield()V"))
    private void removeThreadYield() {
    }

    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void limitWhenMinimized(CallbackInfoReturnable<Integer> cir) {
        if (this.noRender)
            cir.setReturnValue(10);
    }
}
