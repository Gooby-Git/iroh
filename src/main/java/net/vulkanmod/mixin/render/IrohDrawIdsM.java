package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.elemore.iroh.shaderpack.runtime.DrawIds;
import dev.elemore.iroh.shaderpack.runtime.Substitution;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iroh P3-11: observe-only entity ids for the world pass (DrawIds). HEAD sets, RETURN resets; nothing is cancelled or
 * changed. require = 0: a mod replacing the method only loses the ids. One volatile read when the pack is off.
 */
@Mixin(value = EntityRenderDispatcher.class, priority = 1500)
public abstract class IrohDrawIdsM {
    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), require = 0)
    private void iroh$entityIdHead(Entity entity, double x, double y, double z, float yaw, float partial, PoseStack poseStack,
                                   MultiBufferSource buffers, int light, CallbackInfo ci) {
        if ((Substitution.HOOK & Substitution.WORLD) != 0)
            DrawIds.entity(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    entity instanceof LivingEntity le && (le.hurtTime > 0 || le.deathTime > 0));
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("RETURN"), require = 0)
    private void iroh$entityIdReturn(Entity entity, double x, double y, double z, float yaw, float partial, PoseStack poseStack,
                                     MultiBufferSource buffers, int light, CallbackInfo ci) {
        if ((Substitution.HOOK & Substitution.WORLD) != 0)
            DrawIds.reset();
    }
}
