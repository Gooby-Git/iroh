package net.vulkanmod.mixin.render.upscale;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.elemore.iroh.temporal.TemporalRuntime;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iroh P4-1a render scale: the world phase of a frame (renderLevel HEAD .. the main-target bindWrite right before the
 * GUI) renders at render size; GameRenderer.postEffect (world-space) is sized to render size. Every handler returns
 * at once with iroh.upscale.mode off. Priority 900: the world target is bound before pack mods' renderLevel HEAD
 * injectors (1000) run, so whatever they draw lands in the world picture.
 */
@Mixin(value = GameRenderer.class, priority = 900)
public abstract class IrohRenderScaleGameM {
    @Inject(method = "render", at = @At("HEAD"))
    private void iroh$scaleHead(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        TemporalRuntime.onRenderHead();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void iroh$scaleTail(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        TemporalRuntime.onRenderTail();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void iroh$worldBegin(DeltaTracker deltaTracker, CallbackInfo ci) {
        TemporalRuntime.onRenderLevelHead(deltaTracker.getGameTimeDeltaPartialTick(false));
    }

    /** the depth-only clear right before renderItemInHand: keep a copy of the scene depth for motion vectors / FSR */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
    private void iroh$beforeHandClear(DeltaTracker deltaTracker, CallbackInfo ci) {
        TemporalRuntime.onBeforeHandClear();
    }

    /** P4-1b: the hand's projection and bob model-view, for the hand's own motion vectors */
    @Inject(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    private void iroh$hand(Camera camera, float f, Matrix4f matrix4f, CallbackInfo ci, @Local PoseStack poseStack) {
        TemporalRuntime.onHand(poseStack);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V"))
    private void iroh$worldEnd(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        TemporalRuntime.onBeforeGui();
    }

    @ModifyArg(method = {"loadEffect", "resize"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PostChain;resize(II)V", ordinal = 0), index = 0)
    private int iroh$postEffectW(int w) {
        return TemporalRuntime.chainWidth(w);
    }

    @ModifyArg(method = {"loadEffect", "resize"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PostChain;resize(II)V", ordinal = 0), index = 1)
    private int iroh$postEffectH(int h) {
        return TemporalRuntime.chainHeight(h);
    }
}
