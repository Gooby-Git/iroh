package dev.elemore.iroh.api;

import dev.elemore.iroh.gpu.GpuCore;

import java.nio.ByteBuffer;

/**
 * api 4 (card V-M1): device-local GPU buffers owned by a mod. Capability {@link Capability#GPU_BUFFERS}.
 * <ul>
 *   <li>{@link #create}: owner = your mod id; bytes a multiple of 4, at most 1 GiB; returns NULL when the owner's share
 *       of Iroh's memory cap ({@code iroh.gpu.ownerShare}, default 40 %) would be exceeded ({@link #lastRefusal} says
 *       why). Never an out-of-memory crash.</li>
 *   <li>{@link #upload}: render thread; copied through Iroh's staging ring, visible to work recorded later in the frame
 *       (in game: before this frame's commands run). Sizes and offsets in 4-byte units.</li>
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

    /**
     * api 4.1: frames the device keeps in flight. An upload lands BEFORE the current frame's GPU work (in game Iroh submits
     * a frame's uploads ahead of the frame, possibly on another queue), so never upload into bytes an earlier frame in
     * flight may still read: keep per-frame copies indexed by {@link #frameSlot()} (uniform blocks, job lists), or delay
     * reuse by this many frames. Clears or writes that must happen between two GPU passes of one frame need a compute
     * pass, not an upload.
     */
    public static int framesInFlight() {
        return GpuCore.get().framesInFlight();
    }

    /** api 4.1: the frame slot being recorded (0 .. framesInFlight - 1). */
    public static int frameSlot() {
        return GpuCore.get().frameSlot();
    }
}
