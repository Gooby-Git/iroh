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

    Object createBuffer(long bytes, int usage);

    long bufferSize(Object buffer);

    /** Deferred under the device's frame rule (in game: the rc12 epoch rule). */
    void destroyBuffer(Object buffer);

    /** Copies {@code src} (position..limit) to {@code buffer} at {@code offset}; visible to later recorded work. */
    void upload(Object buffer, long offset, ByteBuffer src);

    /** set 0: {@code storageBuffers} storage buffers, then {@code sampledTextures} combined image samplers (api 4.1). */
    Object createComputePipeline(String name, ByteBuffer spirv, int storageBuffers, int sampledTextures, int pushBytes);

    /** {@code blend}: {@link #BLEND_NONE}, {@link #BLEND_ALPHA} or {@link #BLEND_UNDER} (ONE, SRC_ALPHA). */
    Object createDrawPipeline(String name, ByteBuffer vertexSpirv, ByteBuffer fragmentSpirv, int storageBuffers, int sampledTextures,
                              int pushBytes, int colorFormat, int depthFormat, int depthCompare, boolean depthWrite, int blend);

    int BLEND_NONE = 0, BLEND_ALPHA = 1, BLEND_UNDER = 2;

    void destroyPipeline(Object pipeline);

    Object createImage(String name, int width, int height, int format, boolean depth);

    void destroyImage(Object image);

    /** A global memory/execution barrier (stage and access masks are Vulkan's). */
    void barrier(int srcStages, int srcAccess, int dstStages, int dstAccess);

    /** {@code textures}: device textures (images, or an {@link External} resolved at bind time). */
    void dispatch(Object pipeline, Object[] buffers, Object[] textures, ByteBuffer push, int x, int y, int z);

    void dispatchIndirect(Object pipeline, Object[] buffers, Object[] textures, ByteBuffer push, Object indirect, long offset);

    /** Non-indexed draw inside a pass (full-screen passes: 3 vertices). */
    void draw(Object pipeline, Object[] buffers, Object[] textures, ByteBuffer push, int vertexCount, int instanceCount);

    void beginRendering(Object color, Object depth, float[] clearRgba, float clearDepth, boolean clear);

    void endRendering();

    void drawIndexedIndirectCount(Object pipeline, Object[] buffers, Object[] textures, ByteBuffer push, Object index, int indexType,
                                  Object commands, long commandOffset, Object count, long countOffset, int maxDraws, int stride);

    void drawIndexedIndirect(Object pipeline, Object[] buffers, Object[] textures, ByteBuffer push, Object index, int indexType,
                             Object commands, long commandOffset, int drawCount, int stride);

    // ---------------------------------------------------------------- api 4.1 (card V-M3)

    /** A texture Iroh owns (block atlas, lightmap), resolved when bound: in game Iroh's live image; offline a stand-in. */
    record External(String key) {}

    /** RGBA8 image with {@code levels} mips, filled from {@code texels} (level 0 first, tightly packed). */
    Object createTexture(String name, int width, int height, int levels, ByteBuffer texels, boolean linear);

    /** Offline stand-in for an {@link External} key (tests); in game unsupported. */
    void setStandIn(String key, Object texture);

    /** Copies texels between two images of equal size and format (barriers around it). */
    void copyImage(Object src, Object dst);

    /** Fills an image from host texels (RGBA8 / R32F / D32F, tightly packed). Offline only in 4.1. */
    void uploadImage(Object image, ByteBuffer texels);

    /** Copies the main depth into {@code dst} (DEPTH32F, window size). Game: Iroh's scene depth; offline: the stand-in. */
    void copyMainDepth(Object dst);

    /** Offline: images standing in for the main colour / depth (tests); in game unsupported. */
    void setMainStandIn(Object color, Object depth);

    /** Starts / ends drawing into the main target: game joins the bound main pass; offline a pass on the stand-ins. */
    void beginMainRendering();

    void endMainRendering();

    /** VkFormats of the main colour and depth targets (0 when unknown offline without stand-ins). */
    int mainColorFormat();

    int mainDepthFormat();

    /** Runs {@code work} where compute and mod passes may be recorded (game: the main pass ends and resumes after). */
    void runOutsideMainPass(Runnable work);

    /** Frames the device keeps in flight (data an earlier frame reads must not be overwritten: rotate per slot). */
    int framesInFlight();

    int frameSlot();

    /** Timestamp queries: records a reset of this slot's pool; false where not possible now (no support / in a pass). */
    boolean resetTimestamps();

    void writeTimestamp(int index);

    /** Nanoseconds of {@code count} queries of {@code slot}'s pool, or null while not available. */
    long[] readTimestamps(int slot, int count);

    int timestampCapacity();

    /** Offline only (tests / replay): RGBA8 or D32F texels of an image, after waiting for all recorded work. */
    ByteBuffer readback(Object image);

    /** Offline only: submit everything recorded so far and wait. */
    void flush();
}
