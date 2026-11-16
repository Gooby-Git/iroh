package net.vulkanmod.mixin.texture.update;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.mixin.texture.image.NativeImageAccessor;
import net.vulkanmod.render.texture.ImageUploadHelper;
import net.vulkanmod.vulkan.queue.CommandPool;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightTexture.class)
public class MLightTexture {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private GameRenderer renderer;

    @Shadow private boolean updateLightTexture;
    @Shadow private float blockLightRedFlicker;

    @Shadow @Final private DynamicTexture lightTexture;
    @Shadow @Final private NativeImage lightPixels;


    /**
     * @author
     * @reason
     */
    @Overwrite
    public void turnOnLightLayer() {
        RenderSystem.setShaderTexture(2, this.lightTexture.getId());
    }

    // P1-1b: the vanilla CPU lightmap loop is left intact (it was cancelled before) so other mods' injectors into it
    // keep working: Enderscape (light colour, End lighting, gamma: ModifyArgs / WrapOperation on Vector3f.set / lerp),
    // Origins/apoli (night vision strength). Iroh only adds the Vulkan image-layout transition after the upload.
    private static final String UPLOAD = "Lnet/minecraft/client/renderer/texture/DynamicTexture;upload()V";

    @Inject(method = "updateLightTexture", at = @At(value = "INVOKE", target = UPLOAD))
    private void irohBeforeUpload(float f, CallbackInfo ci) {
        ImageUploadHelper.INSTANCE.getOrStartCommandBuffer();
    }

    @Inject(method = "updateLightTexture", at = @At(value = "INVOKE", target = UPLOAD, shift = At.Shift.AFTER))
    private void irohAfterUpload(float f, CallbackInfo ci) {
        CommandPool.CommandBuffer commandBuffer = ImageUploadHelper.INSTANCE.getOrStartCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkGlTexture.getTexture(this.lightTexture.getId()).getVulkanImage().readOnlyLayout(stack, commandBuffer.getHandle());
        }
        ImageUploadHelper.INSTANCE.submitCommands();
    }
}
