package net.vulkanmod.mixin.render.clouds;

import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.server.packs.resources.ResourceManager;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.sky.CloudRenderer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelRenderer.class, priority = 1500)
public abstract class LevelRendererM {

    @Shadow private int ticks;
    // Iroh (rc3, td-154ba3): vanilla 1.21.1 LevelRenderer.close() never closes its sky / star / dark-sky / cloud vertex
    // buffers (GL frees them with the context). Under Vulkan they were the 4 VkBuffers vkDestroyDevice reported leaked
    // (three created at boot, handles 0xcd-0xcf, plus one created later: Iroh's cloud VBO).
    @Shadow private @Nullable com.mojang.blaze3d.vertex.VertexBuffer starBuffer;
    @Shadow private @Nullable com.mojang.blaze3d.vertex.VertexBuffer skyBuffer;
    @Shadow private @Nullable com.mojang.blaze3d.vertex.VertexBuffer darkBuffer;
    @Shadow private @Nullable com.mojang.blaze3d.vertex.VertexBuffer cloudBuffer;
    @Shadow private @Nullable ClientLevel level;

    @Unique
    private CloudRenderer cloudRenderer;

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    public void renderClouds(PoseStack poseStack, Matrix4f modelView, Matrix4f projection, float partialTicks, double camX, double camY, double camZ, CallbackInfo ci) {
        // Iroh P1-13a: IrohSky. A suppressor or a claiming override replaces the clouds; owner mods no longer need a
        // priority race against this injector (third-party mixins keep the priority rule).
        if (dev.elemore.iroh.apiimpl.SkyHooks.cloudsSuppressed(this.level) || dev.elemore.iroh.apiimpl.SkyHooks.drawCloudOverride()) {
            Profiler.getMainProfiler().pop();
            ci.cancel();
            return;
        }
        if (this.cloudRenderer == null) {
            this.cloudRenderer = new CloudRenderer();
        }

        this.cloudRenderer.renderClouds(this.level, poseStack, modelView, projection, this.ticks, partialTicks, camX, camY, camZ);
        Profiler.getMainProfiler().pop();
        ci.cancel();
    }

    /** Iroh P1-13a: IrohSky.overrideWeather; the first override that draws replaces vanilla rain and snow. */
    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void iroh$weatherOverride(LightTexture lightTexture, float partialTick, double camX, double camY, double camZ, CallbackInfo ci) {
        if (dev.elemore.iroh.apiimpl.SkyHooks.drawWeatherOverride())
            ci.cancel();
    }

    @Inject(method = "allChanged", at = @At("RETURN"))
    private void onAllChanged(CallbackInfo ci) {
        if (this.cloudRenderer != null) {
            this.cloudRenderer.resetBuffer();
        }
    }

    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void onReload(ResourceManager resourceManager, CallbackInfo ci) {
        if (this.cloudRenderer != null) {
            this.cloudRenderer.loadTexture();
        }
    }


    /** Iroh (rc3, td-154ba3): free what vanilla's close() leaves; MinecraftMixin.close2 -> Vulkan.cleanUp frees them. */
    @Inject(method = "close", at = @At("TAIL"))
    private void iroh$closeSkyAndClouds(CallbackInfo ci) {
        for (com.mojang.blaze3d.vertex.VertexBuffer vb : new com.mojang.blaze3d.vertex.VertexBuffer[]{
                this.starBuffer, this.skyBuffer, this.darkBuffer, this.cloudBuffer}) {
            if (vb != null) vb.close();
        }
        this.starBuffer = this.skyBuffer = this.darkBuffer = this.cloudBuffer = null;
        if (this.cloudRenderer != null) {
            this.cloudRenderer.resetBuffer();
        }
    }
}
