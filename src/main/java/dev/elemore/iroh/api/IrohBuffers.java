package dev.elemore.iroh.api;

import dev.elemore.iroh.gpu.GpuCore;

import java.nio.ByteBuffer;

/**
 * api 4 (card V-M1): device-local GPU buffers owned by a mod. Capability {@link Capability#GPU_BUFFERS}.
 * <ul>
 *   <li>{@link #create}: owner = your mod id; bytes a multiple of 4, at most 1 GiB; returns NULL when the owner's share
 *       of Iroh's memory cap ({@code iroh.gpu.ownerShare}, default 40 %) would be exceeded ({@link #lastRefusal} says
 *       why). Never an out-of-memory crash.</li>
 *   <li>{@link #upload}: render thread; the data is copied at the call. PROGRAM ORDER (UPLOAD-SYNC): visible to GPU work
 *       recorded after the call, never to work recorded before it, never to frames still in flight. From a frame stage
 *       with no pass bound (BEFORE_MAIN_PASS, between IrohTargets passes) it is recorded in the frame; between frames it
 *       lands before the next frame. Exception (api 4.0 cannot break a pass): INSIDE a render pass (an IrohTargets pass,
 *       or a world stage) it lands before the frame's work, so there write only bytes nothing earlier in this frame or a
 *       frame in flight reads (per-frame copies). Sizes and offsets in 4-byte units.</li>
 *   <li>{@link GpuBuffer#close}: freed once no frame in flight uses it (the rc12 epoch rule). Iroh closes what an owner
 *       still holds at world unload, when its frame hook is removed after repeated failures, and at shutdown.</li>
 * </ul>
 * Sparse buffers / device addresses are not in v1 (capabilities SPARSE_BUFFERS / DEVICE_ADDRESS stay absent).
 */
public final class IrohBuffers {
    public static final int STORAGE = GpuCore.STORAGE, INDIRECT = GpuCore.INDIRECT, INDEX = GpuCore.INDEX, VERTEX = GpuCore.VERTEX;

    private IrohBuffers() {
    }

    public static GpuBuffer create(String owner, String name, long bytes, int usage) {
        return GpuCore.get().createBuffer(owner, name, bytes, usage);
    }

    public static void upload(GpuBuffer buffer, long offset, ByteBuffer data) {
        GpuCore.get().upload(buffer, offset, data);
    }

    /** Why the last create returned null. */
    public static String lastRefusal() {
        return GpuCore.get().lastRefusal();
    }

    /** Bytes {@code owner} may hold in buffers and targets together. */
    public static long ownerShareBytes() {
        return GpuCore.get().ownerShareBytes();
    }

    public static long ownerBytes(String owner) {
        return GpuCore.get().ownerBytes(owner);
    }
}
