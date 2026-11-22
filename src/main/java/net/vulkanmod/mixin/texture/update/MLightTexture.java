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
        // rc14 (td-5fd20c): vanilla sets GL_LINEAR min/mag here every time (the upload left it NEAREST); the base dropped
        // that. Full sky light (uv 240/256) lies on the texel 14/15 boundary, so LINEAR gives their blend: without it the
        // pack's terrain was 12% brighter than OpenGL (Alex's Caves replaces updateLightTexture, so the upload-side
        // restore below never ran there).
        VkGlTexture tex = VkGlTexture.getTexture(this.lightTexture.getId());
        if (tex != null && tex.getVulkanImage() != null)
            tex.getVulkanImage().updateTextureSampler(true, false, false);
    }

    // P1-1b: the vanilla CPU lightmap loop is left intact (it was cancelled before) so other mods' injectors into it
    // keep working: Enderscape (light colour, End lighting, gamma: ModifyArgs / WrapOperation on Vector3f.set / lerp),
    // Origins/apoli (night vision strength). Iroh only adds the Vulkan image-layout transition after the upload.
    private static final String UPLOAD = "Lnet/minecraft/client/renderer/texture/DynamicTexture;upload()V";

    /**
     * rc14 (td-5fd20c): remember the lightmap's GL id so EVERY upload of it keeps linear filtering (MNativeImage._upload),
     * also when another mod replaces the vanilla loop: Alex's Caves HEAD-cancels updateLightTexture (biome ambient light
     * colouring, default on) and uploads the DynamicTexture itself, so the injects below never ran, the upload left the
     * lightmap NEAREST, and full-sky-light terrain sampled texel 15 instead of the 14/15 blend: +12% brighter than GL.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void irohRememberLightmap(GameRenderer renderer, Minecraft minecraft, CallbackInfo ci) {
        dev.elemore.iroh.compat.LightmapFilter.lightmapTextureId = this.lightTexture.getId();
    }

    @Inject(method = "updateLightTexture", at = @At(value = "INVOKE", target = UPLOAD))
    private void irohBeforeUpload(float f, CallbackInfo ci) {
        ImageUploadHelper.INSTANCE.getOrStartCommandBuffer();
    }

    @Inject(method = "updateLightTexture", at = @At(value = "INVOKE", target = UPLOAD, shift = At.Shift.AFTER))
    private void irohAfterUpload(float f, CallbackInfo ci) {
        CommandPool.CommandBuffer commandBuffer = ImageUploadHelper.INSTANCE.getOrStartCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var image = VkGlTexture.getTexture(this.lightTexture.getId()).getVulkanImage();
            // P1-8b: LightTexture calls setFilter(true, true), but MNativeImage._upload resets the sampler to the
            // upload call's blur=false (nearest) on every upload. Vanilla terrain shaders do a filtered lookup
            // between lightmap texels (td-ad8c2f), so restore linear filtering after each upload.
            image.updateTextureSampler(true, false, false);
            image.readOnlyLayout(stack, commandBuffer.getHandle());
        }
        ImageUploadHelper.INSTANCE.submitCommands();
    }
}
