package net.vulkanmod.mixin.vertex;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.vulkanmod.interfaces.ExtendedVertexBuilder;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OutlineBufferSource.EntityOutlineGenerator.class)
public class EntityOutlineGeneratorM implements ExtendedVertexBuilder {

    @Shadow @Final private int color;

    @Unique private ExtendedVertexBuilder extDelegate;
    @Unique private boolean canUseFastVertex = false;
    /** The outline colour in the fast path's packed RGBA order (the record holds ARGB32). */
    @Unique private int packedOutlineColor;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void getExtBuilder(VertexConsumer vertexConsumer, int i, int j, int k, int l, CallbackInfo ci) {
        if (vertexConsumer instanceof ExtendedVertexBuilder) {
            this.extDelegate = (ExtendedVertexBuilder) vertexConsumer;
            this.canUseFastVertex = true;
            this.packedOutlineColor = ColorUtil.RGBA.fromArgb32(this.color);
        }
    }

    @Override
    public boolean canUseFastVertex() {
        return this.canUseFastVertex;
    }

    @Override
    public void vertex(float x, float y, float z, int packedColor, float u, float v, int overlay, int light, int packedNormal) {
        // Upstream VulkanMod cc5f05e: the outline takes the generator's colour (team colour), like vanilla's addVertex,
        // never the model's vertex colour (the base forwarded packedColor, so outlines came out in the entity's tint).
        this.extDelegate.vertex(x, y, z, this.packedOutlineColor, u, v, overlay, light, packedNormal);
    }
}
