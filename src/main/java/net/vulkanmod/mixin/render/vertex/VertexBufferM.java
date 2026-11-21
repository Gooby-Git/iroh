package net.vulkanmod.mixin.render.vertex;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.ShaderInstance;
import net.vulkanmod.render.VBO;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VertexBuffer.class)
public class VertexBufferM {

    private VBO vbo;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void constructor(VertexBuffer.Usage usage, CallbackInfo ci) {
        vbo = new VBO(usage);
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenBuffers()I"))
    private int doNothing() {
        return 0;
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenVertexArrays()I"))
    private int doNothing2() {
        return 0;
    }

    /**
     * @author
     */
    @Overwrite
    public void bind() {}

    /**
     * @author
     */
    @Overwrite
    public static void unbind() {}

    // upload and uploadIndexBuffer are cancelled at HEAD instead of @Overwritten so the vanilla body stays in the
    // class: GPUBooster (dsa.VertexBufferMixin @Redirect on uploadVertexBuffer / uploadIndexBuffer, require 1) and
    // ImmediatelyFast (fast_buffer_upload.MixinVertexBuffer @Redirect on RenderSystem.glBufferData) aim their
    // injectors at calls inside these two methods and would otherwise abort the boot (P1-1b, tools/injector_scan.py).
    // The vanilla body is dead code: ci.cancel() always runs first.
    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private void irohUpload(MeshData meshData, CallbackInfo ci) {
        vbo.upload(meshData);
        ci.cancel();
    }

    @Inject(method = "uploadIndexBuffer(Lcom/mojang/blaze3d/vertex/ByteBufferBuilder$Result;)V", at = @At("HEAD"), cancellable = true)
    private void irohUploadIndexBuffer(ByteBufferBuilder.Result result, CallbackInfo ci) {
        vbo.uploadIndexBuffer(result.byteBuffer());
        ci.cancel();
    }

    /**
     * @author
     */
    @Overwrite
    public void drawWithShader(Matrix4f viewMatrix, Matrix4f projectionMatrix, ShaderInstance shader) {
        if (dev.elemore.iroh.diag.EmuStats.ON) dev.elemore.iroh.diag.EmuStats.hit("VertexBuffer.drawWithShader");
        vbo.drawWithShader(viewMatrix, projectionMatrix, shader);
    }

    /**
     * @author
     */
    @Overwrite
    public void draw() {
        if (dev.elemore.iroh.diag.EmuStats.ON) dev.elemore.iroh.diag.EmuStats.hit("VertexBuffer.draw");
        vbo.draw();
    }

    /**
     * @author
     */
    @Overwrite
    public void close() {
        if (dev.elemore.iroh.diag.EmuStats.ON) dev.elemore.iroh.diag.EmuStats.hit("VertexBuffer.close");
        vbo.close();
    }
}
