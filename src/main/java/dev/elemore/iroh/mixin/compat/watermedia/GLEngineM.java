package dev.elemore.iroh.mixin.compat.watermedia;

import dev.elemore.iroh.compat.watermedia.IrohVideoTexture;
import dev.elemore.iroh.glshim.GlShim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.watermedia.api.media.engines.GFXEngine;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

/**
 * WaterMedia 3.x {@code GLEngine} under Iroh (P1-2, blocker A: the FancyMenu title video aborted the JVM in
 * {@code GLEngine$Env.restore} -> glActiveTexture). When the engine is created on a thread without a GL context (every
 * Minecraft thread under Iroh), its public methods are taken over by {@link IrohVideoTexture}: BGRA frames only
 * (FFMediaPlayer then lets FFmpeg's swscale convert), uploaded into a Vulkan image whose GL id {@code texture()}
 * returns. An engine created on a thread that owns a real context (a mod's own GL window) keeps WaterMedia's code.
 * Only loaded when the mod {@code watermedia} is present (IrohCompatPlugin). WaterMedia's jar is not modified.
 */
@Pseudo
@Mixin(targets = "org.watermedia.api.media.engines.GLEngine", remap = false)
public abstract class GLEngineM extends GFXEngine {

    @Unique
    private IrohVideoTexture iroh$video;

    @Inject(method = "<init>(Ljava/lang/Thread;Ljava/util/concurrent/Executor;)V", at = @At("TAIL"), remap = false)
    private void iroh$init(Thread renderThread, Executor renderThreadEx, CallbackInfo ci) {
        if (GlShim.noContext()) this.iroh$video = new IrohVideoTexture();
    }

    @Inject(method = "supports(Lorg/watermedia/api/util/PixelFormat;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void iroh$supports(PixelFormat fmt, CallbackInfoReturnable<Boolean> cir) {
        if (this.iroh$video != null) cir.setReturnValue(fmt == PixelFormat.BGRA);
    }

    @Inject(method = "format(Lorg/watermedia/api/util/PixelFormat;III)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void iroh$format(PixelFormat fmt, int width, int height, int bits, CallbackInfo ci) {
        if (this.iroh$video == null) return;
        this.format = fmt;
        this.width = width;
        this.height = height;
        this.bits = bits;
        this.iroh$video.format(fmt == PixelFormat.BGRA, String.valueOf(fmt), width, height);
        ci.cancel();
    }

    @Inject(method = "upload([Ljava/nio/ByteBuffer;[I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void iroh$upload(ByteBuffer[] buffers, int[] strides, CallbackInfo ci) {
        if (this.iroh$video == null) return;
        this.iroh$video.upload(buffers, strides);
        ci.cancel();
    }

    @Inject(method = "texture()J", at = @At("HEAD"), cancellable = true, remap = false)
    private void iroh$texture(CallbackInfoReturnable<Long> cir) {
        if (this.iroh$video != null) cir.setReturnValue(this.iroh$video.textureId());
    }

    @Inject(method = "preload()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void iroh$preloadQuery(CallbackInfoReturnable<Boolean> cir) {
        if (this.iroh$video != null) cir.setReturnValue(false);
    }

    @Inject(method = "preload([Ljava/nio/ByteBuffer;I)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void iroh$preload(ByteBuffer[] frames, int count, CallbackInfoReturnable<Boolean> cir) {
        if (this.iroh$video != null) cir.setReturnValue(false);
    }

    @Inject(method = "frame(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void iroh$frame(int index, CallbackInfo ci) {
        if (this.iroh$video != null) ci.cancel();
    }

    @Inject(method = "release()V", at = @At("HEAD"), cancellable = true, remap = false)
    private void iroh$release(CallbackInfo ci) {
        if (this.iroh$video == null) return;
        this.iroh$video.release();
        ci.cancel();
    }
}
