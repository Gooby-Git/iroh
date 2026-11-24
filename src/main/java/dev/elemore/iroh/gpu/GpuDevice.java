package dev.elemore.iroh.gpu;

import java.nio.ByteBuffer;

/**
 * Iroh api 4 (card V-M1): the Vulkan operations behind IrohBuffers / IrohCompute / IrohTargets /
 * IrohDraw.multiDrawIndirectCount. Two implementations: {@link HeadlessGpuDevice} (lavapipe, offline tests: the test bed
 * for Vista's port) and {@code IrohGpuDevice} (the game's renderer). {@link GpuCore} owns validation, owners, budgets
 * and hazards; a device only records. Objects are device-private ({@code Object}); {@link GpuCore} never looks inside.
 * No Minecraft classes here (the offline exerciser links this package alone).
 */
public interface GpuDevice {
    String name();

    /** vkCmdDrawIndexedIndirectCount usable (Vulkan 1.2 drawIndirectCount enabled on the device). */
    boolean supportsDrawIndirectCount();

    /** The memory cap owners' shares are taken from (MemoryGuard's cap in game; the device cap offline). */
    long capBytes();

    /** True where compute and off-screen passes may be recorded now (outside the main render pass). */
    boolean canRecordOutsideMainPass();

    /** UPLOAD-SYNC: true while a frame command buffer is recording (game: between beginFrame and endFrame). Default true. */
    default boolean frameRecording() { return true; }

    /** UPLOAD-SYNC: frames the device keeps in flight (game: the frame slots). Default 1 (offline: every flush waits). */
    default int framesInFlight() { return 1; }

    /**
     * UPLOAD-SYNC: true when a copy can be recorded into the frame command buffer NOW (a frame records, no render pass
     * bound). No side effects. Default false: the pre-frame path only.
     */
    default boolean inFrameCopyPossible() { return false; }

    /** UPLOAD-SYNC IN-STREAM upload: the copy recorded into the frame command buffer here (only where inFrameCopyPossible). */
    default void uploadInFrame(Object buffer, long offset, java.nio.ByteBuffer src) { throw new UnsupportedOperationException("in-frame upload"); }

    Object createBuffer(long bytes, int usage);

    long bufferSize(Object buffer);

    /** Deferred under the device's frame rule (in game: the rc12 epoch rule). */
    void destroyBuffer(Object buffer);

    /** Copies {@code src} (position..limit) to {@code buffer} at {@code offset}; visible to later recorded work. */
    /**
     * PRE-FRAME upload: a batch that runs BEFORE all GPU work of the next frame submit (game: UploadManager, submitted at
     * endFrame on the transfer family, the frame waits on it). Not ordered against work already recorded in the frame nor
     * against frames in flight: {@link GpuCore#upload} picks it only where neither can see it (UPLOAD-SYNC).
     */
    void upload(Object buffer, long offset, ByteBuffer src);

    Object createComputePipeline(String name, ByteBuffer spirv, int storageBuffers, int pushBytes);

    Object createDrawPipeline(String name, ByteBuffer vertexSpirv, ByteBuffer fragmentSpirv, int storageBuffers, int sampledTextures,
                              int pushBytes, int colorFormat, int depthFormat, int depthCompare, boolean depthWrite, boolean blend);

    void destroyPipeline(Object pipeline);

    Object createImage(String name, int width, int height, int format, boolean depth);

    void destroyImage(Object image);

    /** A global memory/execution barrier (stage and access masks are Vulkan's). */
    void barrier(int srcStages, int srcAccess, int dstStages, int dstAccess);

    void dispatch(Object pipeline, Object[] buffers, ByteBuffer push, int x, int y, int z);

    void dispatchIndirect(Object pipeline, Object[] buffers, ByteBuffer push, Object indirect, long offset);

    void beginRendering(Object color, Object depth, float[] clearRgba, float clearDepth, boolean clear);

    void endRendering();

    void drawIndexedIndirectCount(Object pipeline, Object[] buffers, Object[] textures, ByteBuffer push, Object index, int indexType,
                                  Object commands, long commandOffset, Object count, long countOffset, int maxDraws, int stride);

    void drawIndexedIndirect(Object pipeline, Object[] buffers, Object[] textures, ByteBuffer push, Object index, int indexType,
                             Object commands, long commandOffset, int drawCount, int stride);

    /** Offline only (tests / replay): RGBA8 or D32F texels of an image, after waiting for all recorded work. */
    ByteBuffer readback(Object image);

    /** Offline only: submit everything recorded so far and wait. */
    void flush();
}
