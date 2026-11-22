package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.elemore.iroh.shaderpack.runtime.DrawIds;
import dev.elemore.iroh.shaderpack.runtime.Substitution;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Iroh P3-11: observe-only block entity ids for the world pass (DrawIds); require = 0, nothing cancelled. */
@Mixin(value = BlockEntityRenderDispatcher.class, priority = 1500)
public abstract class IrohBlockEntityIdsM {
    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("HEAD"), require = 0)
    private void iroh$blockEntityIdHead(BlockEntity be, float partial, PoseStack poseStack, MultiBufferSource buffers, CallbackInfo ci) {
        if ((Substitution.HOOK & Substitution.WORLD) != 0)
            DrawIds.blockEntity(BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString());
    }

    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("RETURN"), require = 0)
    private void iroh$blockEntityIdReturn(BlockEntity be, float partial, PoseStack poseStack, MultiBufferSource buffers, CallbackInfo ci) {
        if ((Substitution.HOOK & Substitution.WORLD) != 0)
            DrawIds.reset();
    }
}
