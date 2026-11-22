package dev.elemore.iroh.api;

import dev.elemore.iroh.gpu.GpuCore;

import java.nio.ByteBuffer;

/**
 * api 4 (card V-M1): compute pipelines and dispatches. Capability {@link Capability#COMPUTE}.
 * <p>
 * Pipelines: GLSL 450 for Vulkan, {@code set = 0}, storage buffers at bindings 0..n-1, one push-constant block of up to
 * 128 bytes. Dispatches are recorded outside the main render pass: from {@link FrameStage#BEFORE_MAIN_PASS} in game.
 * Each binding declares its access; Iroh turns the declared reads/writes into barriers (a buffer written by a dispatch
 * or an upload is visible to the next declared reader).
 */
public final class IrohCompute {
    private IrohCompute() {
    }

    public enum Access { READ, WRITE, READ_WRITE }

    /** One storage-buffer binding with its declared access. */
    public record Binding(GpuBuffer buffer, Access access) {
        public static Binding read(GpuBuffer b) { return new Binding(b, Access.READ); }
        public static Binding write(GpuBuffer b) { return new Binding(b, Access.WRITE); }
        public static Binding readWrite(GpuBuffer b) { return new Binding(b, Access.READ_WRITE); }
    }

    public static ComputePipelineHandle pipeline(String owner, String name, String glsl450, int storageBuffers, int pushBytes) {
        return GpuCore.get().computePipeline(owner, name, glsl450, storageBuffers, pushBytes);
    }

    /** api 4.1: plus {@code sampledTextures} combined image samplers at bindings n.. (after the storage buffers). */
    public static ComputePipelineHandle pipeline(String owner, String name, String glsl450, int storageBuffers, int sampledTextures, int pushBytes) {
        return GpuCore.get().computePipeline(owner, name, glsl450, storageBuffers, sampledTextures, pushBytes);
    }

    /** api 4.1: with sampled textures (targets written by a pass are made visible first). */
    public static void dispatch(ComputePipelineHandle pipeline, Binding[] bindings, GpuTexture[] textures, ByteBuffer push, int x, int y, int z) {
        GpuCore.get().dispatch(pipeline, bindings, textures, push, x, y, z);
    }

    public static void dispatchIndirect(ComputePipelineHandle pipeline, Binding[] bindings, GpuTexture[] textures, ByteBuffer push, GpuBuffer args, long offset) {
        GpuCore.get().dispatchIndirect(pipeline, bindings, textures, push, args, offset);
    }

    public static void dispatch(ComputePipelineHandle pipeline, Binding[] bindings, ByteBuffer push, int x, int y, int z) {
        GpuCore.get().dispatch(pipeline, bindings, push, x, y, z);
    }

    /** Group counts from {@code args} at {@code offset} (3 uints; buffer with INDIRECT usage). */
    public static void dispatchIndirect(ComputePipelineHandle pipeline, Binding[] bindings, ByteBuffer push, GpuBuffer args, long offset) {
        GpuCore.get().dispatchIndirect(pipeline, bindings, push, args, offset);
    }
}
