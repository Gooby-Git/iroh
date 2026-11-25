package net.vulkanmod.render.chunk.buffer;

import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.chunk.ChunkAreaManager;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.memory.buffer.IndirectBuffer;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.EnumMap;

import static org.lwjgl.vulkan.VK10.*;

public class DrawBuffers {
    public static final int VERTEX_SIZE = PipelineManager.terrainVertexFormat.getVertexSize();
    public static final int INDEX_SIZE = Short.BYTES;

    private static final int CMD_STRIDE = 32;

    private static final long cmdBufferPtr = MemoryUtil.nmemAlignedAlloc(CMD_STRIDE, (long) ChunkAreaManager.AREA_SIZE * QuadFacing.COUNT * CMD_STRIDE);

    private final int index;
    private final Vector3i origin;
    private final int minHeight;

    private boolean allocated = false;
    AreaBuffer indexBuffer;
    private final EnumMap<TerrainRenderType, AreaBuffer> vertexBuffers = new EnumMap<>(TerrainRenderType.class);

    long drawParamsPtr;
    final int[] sectionIndices = new int[512];
    final int[] masks = new int[512];

    /**
     * P2-7a (iroh.terrain.mdi): the world arena this area's sections live in (captured when the section grid was built;
     * null = VulkanMod's per-area buffers). With it the draw parameters keep their meaning (element offsets, counts) but
     * point into arena pages, and the entry's baseInstance slot holds its vertex PAGE + 1 (0 = owns nothing, the value
     * resetParameters writes): the world path computes firstInstance per frame, and the command writer then reads one
     * 16-byte entry per facing (TerrainCommandWriter.addSection). {@link #iPage} = page of each (layer, section)'s sorted
     * indices, {@link WorldArena#NONE} = none.
     */
    private final WorldArena world;
    private final byte[] iPage;
    /** P2-7a: the page this area's last upload of each layer (and of sorted indices) went to: tried first (WorldArena.upload) */
    private final byte[] prefPage = new byte[TerrainRenderType.VALUES.length + 1];

    static {
        if (DrawParametersBuffer.STRIDE != TerrainCommandWriter.PARAM_STRIDE || DrawParametersBuffer.FACINGS != TerrainCommandWriter.PARAM_FACINGS
                || DrawParametersBuffer.INDEX_COUNT_OFFSET != 0 || DrawParametersBuffer.FIRST_INDEX_OFFSET != 4 || DrawParametersBuffer.VERTEX_OFFSET_OFFSET != 8
                || DrawParametersBuffer.BASE_INSTANCE_OFFSET != TerrainCommandWriter.PARAM_PAGE_OFFSET)
            throw new IllegalStateException("P2-7a: TerrainCommandWriter reads the DrawParametersBuffer layout; keep them equal");
    }

    //Need ugly minHeight Parameter to fix custom world heights (exceeding 384 Blocks in total)
    public DrawBuffers(int index, Vector3i origin, int minHeight) {
        this.index = index;
        this.origin = origin;
        this.minHeight = minHeight;

        this.drawParamsPtr = DrawParametersBuffer.allocateBuffer();

        this.world = WorldArena.current();
        if (this.world != null) {
            this.iPage = new byte[DrawParametersBuffer.SECTIONS * TerrainRenderType.VALUES.length];
            java.util.Arrays.fill(this.iPage, WorldArena.NONE);
            java.util.Arrays.fill(this.prefPage, WorldArena.NONE);
        } else {
            this.iPage = null;
        }
    }

    /** P2-7a: this area's sections live in the world arena (draw with {@link #writeWorldCommands}) */
    public boolean worldMode() {
        return this.world != null;
    }

    public void upload(RenderSection section, UploadBuffer buffer, TerrainRenderType renderType) {
        if (this.world != null) {
            uploadWorld(section, buffer, renderType);
            return;
        }
        var vertexBuffers = buffer.getVertexBuffers();

        if (buffer.indexOnly) {
            long paramsPtr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, section.inAreaIndex, renderType.ordinal(), QuadFacing.UNDEFINED.ordinal());

            int firstIndex = DrawParametersBuffer.getFirstIndex(paramsPtr);
            int indexCount = DrawParametersBuffer.getIndexCount(paramsPtr);

            int oldOffset = indexCount > 0 ? firstIndex : -1;
            AreaBuffer.Segment segment = this.indexBuffer.upload(buffer.getIndexBuffer(), oldOffset, paramsPtr);
            firstIndex = segment.offset / INDEX_SIZE;

            DrawParametersBuffer.setFirstIndex(paramsPtr, firstIndex);

            buffer.release();
            return;
        }

        for (int i = 0; i < QuadFacing.COUNT; i++) {
            long paramPtr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, section.inAreaIndex, renderType.ordinal(), i);

            int vertexOffset = DrawParametersBuffer.getVertexOffset(paramPtr);
            int firstIndex = 0;
            int indexCount = 0;

            var vertexBuffer = vertexBuffers[i];
            int vertexCount = 0;

            if (vertexBuffer != null) {
                AreaBuffer.Segment segment = this.getAreaBufferOrAlloc(renderType).upload(vertexBuffer, vertexOffset, paramPtr);
                vertexOffset = segment.offset / VERTEX_SIZE;

                int baseInstance = encodeSectionOffset(section.xOffset(), section.yOffset(), section.zOffset());
                DrawParametersBuffer.setBaseInstance(paramPtr, baseInstance);

                vertexCount = vertexBuffer.limit() / VERTEX_SIZE;
                indexCount = vertexCount * 6 / 4;
            }

            if (i == QuadFacing.UNDEFINED.ordinal() && !buffer.autoIndices) {
                if (this.indexBuffer == null) {
                    this.indexBuffer = new AreaBuffer(AreaBuffer.Usage.INDEX, 60000, INDEX_SIZE);
                }

                int oldOffset = DrawParametersBuffer.getIndexCount(paramPtr) > 0 ? DrawParametersBuffer.getFirstIndex(paramPtr) : -1;
                AreaBuffer.Segment segment = this.indexBuffer.upload(buffer.getIndexBuffer(), oldOffset, paramPtr);
                firstIndex = segment.offset / INDEX_SIZE;
            } else {
                Renderer.getDrawer().getQuadsIndexBuffer().checkCapacity(vertexCount);
            }

            DrawParametersBuffer.setIndexCount(paramPtr, indexCount);
            DrawParametersBuffer.setFirstIndex(paramPtr, firstIndex);
            DrawParametersBuffer.setVertexOffset(paramPtr, vertexOffset);
        }

        buffer.release();
    }

    /**
     * P2-7a: {@link #upload} into the world arena, the same parameter updates (facing kept with indexCount 0 when its
     * bucket is empty, quad index capacity, sorted index only for the UNDEFINED facing). Old ranges are freed after the
     * frames in flight. Differences: a size-0 range owns nothing (NONE) and a sorted-index result for a layer that has no
     * vertex data any more is dropped (the per-area path stored it in a buffer nobody draws).
     */
    private void uploadWorld(RenderSection section, UploadBuffer buffer, TerrainRenderType renderType) {
        WorldArena w = this.world;
        int rt = renderType.ordinal();
        int ii = rt * DrawParametersBuffer.SECTIONS + section.inAreaIndex;
        if (buffer.indexOnly) {
            long paramsPtr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, section.inAreaIndex, rt, QuadFacing.UNDEFINED.ordinal());
            if (DrawParametersBuffer.getIndexCount(paramsPtr) > 0)
                DrawParametersBuffer.setFirstIndex(paramsPtr, uploadIndex(w, ii, buffer.getIndexBuffer()));
            buffer.release();
            return;
        }

        var vertexBuffers = buffer.getVertexBuffers();
        WorldArena.Pool pool = w.vertexPool(rt);
        int total = 0;
        for (var vb : vertexBuffers) if (vb != null) total += vb.remaining();
        if (total > 0) // the whole section layer in one page (WorldArena.Pool.pageFor), the area's page first
            this.prefPage[rt] = (byte) pool.pageFor(total, this.prefPage[rt]);
        for (int i = 0; i < QuadFacing.COUNT; i++) {
            long paramPtr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, section.inAreaIndex, rt, i);

            int vertexOffset = DrawParametersBuffer.getVertexOffset(paramPtr);
            int firstIndex = 0;
            int indexCount = 0;
            int vertexCount = 0;

            var vertexBuffer = vertexBuffers[i];
            if (vertexBuffer != null) {
                w.freeLater(pool, vPage(paramPtr), vertexOffset * VERTEX_SIZE);
                setVPage(paramPtr, rt, WorldArena.NONE);
                vertexOffset = -1;
                if (vertexBuffer.remaining() > 0) {
                    long a = w.upload(pool, vertexBuffer, this.prefPage[rt]);
                    setVPage(paramPtr, rt, this.prefPage[rt] = (byte) WorldArena.page(a));
                    vertexOffset = WorldArena.offset(a) / VERTEX_SIZE;
                }

                vertexCount = vertexBuffer.limit() / VERTEX_SIZE;
                indexCount = vertexCount * 6 / 4;
            }

            if (i == QuadFacing.UNDEFINED.ordinal() && !buffer.autoIndices) {
                firstIndex = uploadIndex(w, ii, buffer.getIndexBuffer());
            } else {
                if (i == QuadFacing.UNDEFINED.ordinal())
                    freeIndex(w, ii); // now drawn through the auto quad indices
                Renderer.getDrawer().getQuadsIndexBuffer().checkCapacity(vertexCount);
            }

            DrawParametersBuffer.setIndexCount(paramPtr, indexCount);
            DrawParametersBuffer.setFirstIndex(paramPtr, firstIndex);
            DrawParametersBuffer.setVertexOffset(paramPtr, vertexOffset);
        }
        var cull = net.vulkanmod.render.chunk.cull.SectionCullData.current(); // P2-7b: the layer's box for the GPU cull
        if (cull != null) cull.layerBounds(section.gridIndex, rt, buffer.cullBounds);

        buffer.release();
    }

    /** P2-7a: vertex ranges this area owns per layer: the world draw skips an area without the layer (as the per-area path skips an area without its buffer) */
    private final int[] layerRanges = new int[TerrainRenderType.VALUES.length];

    /** world mode: the vertex page of a parameter entry (NONE = owns nothing) */
    private static int vPage(long paramPtr) {
        return DrawParametersBuffer.getBaseInstance(paramPtr) - 1;
    }

    private void setVPage(long paramPtr, int rt, int page) {
        int old = vPage(paramPtr);
        if (old == page) return;
        if (old == WorldArena.NONE) this.layerRanges[rt]++;
        else if (page == WorldArena.NONE) this.layerRanges[rt]--;
        DrawParametersBuffer.setBaseInstance(paramPtr, page + 1);
    }

    /** P2-7a: whether this area has vertex data of the layer in the world arena */
    public boolean hasWorldLayer(TerrainRenderType renderType) {
        return this.layerRanges[renderType.ordinal()] > 0;
    }

    /** sorted indices of (layer, section) {@code ii} into the arena's index pool; returns firstIndex */
    private int uploadIndex(WorldArena w, int ii, ByteBuffer indices) {
        freeIndex(w, ii);
        if (indices == null || indices.remaining() == 0)
            return 0;
        int pi = this.prefPage.length - 1;
        long a = w.upload(w.indexPool(), indices, this.prefPage[pi]);
        this.iPage[ii] = this.prefPage[pi] = (byte) WorldArena.page(a);
        return WorldArena.offset(a) / INDEX_SIZE;
    }

    private void freeIndex(WorldArena w, int ii) {
        byte p = this.iPage[ii];
        if (p == WorldArena.NONE) return;
        long paramsPtr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, ii % DrawParametersBuffer.SECTIONS, ii / DrawParametersBuffer.SECTIONS, QuadFacing.UNDEFINED.ordinal());
        w.freeLater(w.indexPool(), p, DrawParametersBuffer.getFirstIndex(paramsPtr) * INDEX_SIZE);
        this.iPage[ii] = WorldArena.NONE;
    }

    /** P2-7a: free one layer of a section (RenderSection.resetDrawParameters(type)) and reset its parameters */
    public void resetLayerWorld(int inAreaIndex, TerrainRenderType renderType) {
        resetLayerWorld(inAreaIndex, renderType.ordinal());
    }

    /** P2-7b: {@link #resetLayerWorld(int, TerrainRenderType)} that also clears the layer's GPU-cull box (grid slot) */
    public void resetLayerWorld(int inAreaIndex, int gridIndex, TerrainRenderType renderType) {
        resetLayerWorld(inAreaIndex, renderType.ordinal());
        var cull = net.vulkanmod.render.chunk.cull.SectionCullData.current();
        if (cull != null) cull.layerBounds(gridIndex, renderType.ordinal(), 0);
    }

    private void resetLayerWorld(int inAreaIndex, int rt) {
        WorldArena w = this.world;
        int ii = rt * DrawParametersBuffer.SECTIONS + inAreaIndex;
        freeIndex(w, ii); // before the parameters (it reads firstIndex)
        WorldArena.Pool pool = w.vertexPool(rt);
        for (int i = 0; i < QuadFacing.COUNT; i++) {
            long ptr = DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, inAreaIndex, rt, i);
            w.freeLater(pool, vPage(ptr), DrawParametersBuffer.getVertexOffset(ptr) * VERTEX_SIZE);
            setVPage(ptr, rt, WorldArena.NONE);
            DrawParametersBuffer.resetParameters(ptr);
        }
    }

    /** P2-7a: free every layer of a section (RenderSection.reset: the section moved or was cleared) */
    public void resetSectionWorld(int inAreaIndex) {
        for (int rt = 0; rt < TerrainRenderType.VALUES.length; rt++)
            resetLayerWorld(inAreaIndex, rt);
    }

    /** P2-7b: {@link #resetSectionWorld(int)} that also clears the slot's GPU-cull metadata */
    public void resetSectionWorld(int inAreaIndex, int gridIndex) {
        resetSectionWorld(inAreaIndex);
        var cull = net.vulkanmod.render.chunk.cull.SectionCullData.current();
        if (cull != null) cull.sectionReset(gridIndex);
    }

    /** P2-7a: per-section firstInstance of the area being written (with {@link #sectionIndices} / {@link #masks}) */
    private final int[] worldFi = new int[512];

    /**
     * P2-7a: this area's part of a layer's world draw: the same sections, order, facing masks and skips as
     * {@link #buildDrawBatchesIndirect}, gathered first and then written by one TerrainCommandWriter.addArea call (one
     * command per non-empty facing bucket).
     */
    public void writeWorldCommands(Vec3 cameraPos, StaticQueue<RenderSection> queue, TerrainRenderType terrainRenderType, TerrainCommandWriter writer) {
        boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;
        boolean backFaceCulling = Initializer.CONFIG.backFaceCulling && !isTranslucent;
        int rt = terrainRenderType.ordinal();
        double cx = cameraPos.x, cy = cameraPos.y, cz = cameraPos.z;
        final int[] idx = this.sectionIndices, masks = this.masks, fis = this.worldFi;
        int count = 0;
        for (var iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
            final RenderSection section = iterator.next();
            int fi = writer.encode(section.xOffset >> 4, section.yOffset >> 4, section.zOffset >> 4);
            if (fi < 0)
                continue;
            idx[count] = section.inAreaIndex;
            masks[count] = backFaceCulling ? QuadFacing.facingMask(cx, cy, cz, section.xOffset, section.yOffset, section.zOffset) : 1 << 6;
            fis[count++] = fi;
        }
        long base = this.drawParamsPtr + ((long) rt * DrawParametersBuffer.SECTIONS * DrawParametersBuffer.FACINGS) * DrawParametersBuffer.STRIDE;
        writer.addArea(base, count, idx, masks, fis, this.iPage, rt * DrawParametersBuffer.SECTIONS);
    }

    /** P2-7b: grid slot of each section of the area being written */
    private final int[] worldSlot = new int[512];

    /**
     * P2-7b: {@link #writeWorldCommands} for a layer the GPU cull reads (TerrainCommandWriter.beginCull): the same
     * sections and order, each command with its cull word. {@code allFacings}: every non-empty facing bucket (the GPU
     * facing test replaces the CPU mask), else the CPU path's mask.
     */
    public void writeWorldCommandsCull(Vec3 cameraPos, StaticQueue<RenderSection> queue, TerrainRenderType terrainRenderType, TerrainCommandWriter writer, boolean allFacings) {
        boolean backFaceCulling = Initializer.CONFIG.backFaceCulling;
        int rt = terrainRenderType.ordinal();
        double cx = cameraPos.x, cy = cameraPos.y, cz = cameraPos.z;
        final int[] idx = this.sectionIndices, masks = this.masks, fis = this.worldFi, slots = this.worldSlot;
        int count = 0;
        for (var iterator = queue.iterator(false); iterator.hasNext(); ) {
            final RenderSection section = iterator.next();
            int fi = writer.encode(section.xOffset >> 4, section.yOffset >> 4, section.zOffset >> 4);
            if (fi < 0)
                continue;
            idx[count] = section.inAreaIndex;
            masks[count] = !backFaceCulling ? 1 << 6 : allFacings ? 0x7F : QuadFacing.facingMask(cx, cy, cz, section.xOffset, section.yOffset, section.zOffset);
            slots[count] = section.gridIndex;
            fis[count++] = fi;
        }
        long base = this.drawParamsPtr + ((long) rt * DrawParametersBuffer.SECTIONS * DrawParametersBuffer.FACINGS) * DrawParametersBuffer.STRIDE;
        writer.addAreaCull(base, count, idx, masks, fis, slots, this.iPage, rt * DrawParametersBuffer.SECTIONS);
    }

    private AreaBuffer getAreaBufferOrAlloc(TerrainRenderType renderType) {
        this.allocated = true;

        int initialSize = switch (renderType) {
            case SOLID, CUTOUT -> 100000;
            case CUTOUT_MIPPED -> 250000;
            case TRANSLUCENT, TRIPWIRE -> 60000;
        };

        return this.vertexBuffers.computeIfAbsent(
                renderType, renderType1 -> new AreaBuffer(AreaBuffer.Usage.VERTEX, initialSize, VERTEX_SIZE));
    }

    public AreaBuffer getAreaBuffer(TerrainRenderType r) {
        return this.vertexBuffers.get(r);
    }

    private boolean hasRenderType(TerrainRenderType r) {
        return this.vertexBuffers.containsKey(r);
    }

    private int encodeSectionOffset(int xOffset, int yOffset, int zOffset) {
        final int xOffset1 = (xOffset & 127);
        final int zOffset1 = (zOffset & 127);
        final int yOffset1 = (yOffset - this.minHeight & 127);
        return yOffset1 << 16 | zOffset1 << 8 | xOffset1;
    }

    // TODO: refactor
    public static final float POS_OFFSET = CustomVertexFormat.compressedLayout(PipelineManager.terrainVertexFormat) ? 4.0f : 0.0f;

    private void updateChunkAreaOrigin(VkCommandBuffer commandBuffer, Pipeline pipeline, double camX, double camY, double camZ, MemoryStack stack) {
        float xOffset = (float) ((this.origin.x) + POS_OFFSET - camX);
        float yOffset = (float) ((this.origin.y) + POS_OFFSET - camY);
        float zOffset = (float) ((this.origin.z) + POS_OFFSET - camZ);

        ByteBuffer byteBuffer = stack.malloc(12);

        byteBuffer.putFloat(0, xOffset);
        byteBuffer.putFloat(4, yOffset);
        byteBuffer.putFloat(8, zOffset);

        vkCmdPushConstants(commandBuffer, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, byteBuffer);
    }

    public void buildDrawBatchesIndirect(Vec3 cameraPos, IndirectBuffer indirectBuffer, StaticQueue<RenderSection> queue, TerrainRenderType terrainRenderType) {
        long bufferPtr = cmdBufferPtr;

        boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;
        boolean backFaceCulling = Initializer.CONFIG.backFaceCulling && !isTranslucent;

        int drawCount = 0;

        long drawParamsBasePtr = this.drawParamsPtr + (terrainRenderType.ordinal() * DrawParametersBuffer.SECTIONS * DrawParametersBuffer.FACINGS) * DrawParametersBuffer.STRIDE;
        final long facingsStride = DrawParametersBuffer.FACINGS * DrawParametersBuffer.STRIDE;

        int count = 0;
        if (backFaceCulling) {
            for (var iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
                final RenderSection section = iterator.next();

                sectionIndices[count] = section.inAreaIndex;
                masks[count] = getMask(cameraPos, section);
                count++;
            }

            long ptr = bufferPtr;

            for (int j = 0; j < count; ++j) {
                final int sectionIdx = sectionIndices[j];

                int mask = masks[j];

                long drawParamsBasePtr2 = drawParamsBasePtr + (sectionIdx * facingsStride);

                for (int i = 0; i < QuadFacing.COUNT; i++) {

                    if ((mask & 1 << i) == 0) {
                        drawParamsBasePtr2 += DrawParametersBuffer.STRIDE;
                        continue;
                    }

                    long drawParamsPtr = drawParamsBasePtr2;

                    final int indexCount = DrawParametersBuffer.getIndexCount(drawParamsPtr);
                    final int firstIndex = DrawParametersBuffer.getFirstIndex(drawParamsPtr);
                    final int vertexOffset = DrawParametersBuffer.getVertexOffset(drawParamsPtr);
                    final int baseInstance = DrawParametersBuffer.getBaseInstance(drawParamsPtr);

                    drawParamsBasePtr2 += DrawParametersBuffer.STRIDE;

                    if (indexCount <= 0) {
                        continue;
                    }

                    MemoryUtil.memPutInt(ptr, indexCount);
                    MemoryUtil.memPutInt(ptr + 4, 1);
                    MemoryUtil.memPutInt(ptr + 8, firstIndex);
                    MemoryUtil.memPutInt(ptr + 12, vertexOffset);
                    MemoryUtil.memPutInt(ptr + 16, baseInstance);

                    ptr += CMD_STRIDE;
                    drawCount++;
                }
            }

        }
        else {
            for (var iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
                final RenderSection section = iterator.next();

                sectionIndices[count] = section.inAreaIndex;
                count++;
            }

            final int facing = 6;
            final long facingOffset = facing * DrawParametersBuffer.STRIDE;
            drawParamsBasePtr += facingOffset;

            long ptr = bufferPtr;
            for (int i = 0; i < count; ++i) {
                int sectionIdx = sectionIndices[i];

                long drawParamsPtr = drawParamsBasePtr + (sectionIdx * facingsStride);

                final int indexCount = DrawParametersBuffer.getIndexCount(drawParamsPtr);
                final int firstIndex = DrawParametersBuffer.getFirstIndex(drawParamsPtr);
                final int vertexOffset = DrawParametersBuffer.getVertexOffset(drawParamsPtr);
                final int baseInstance = DrawParametersBuffer.getBaseInstance(drawParamsPtr);

                if (indexCount <= 0) {
                    continue;
                }

                MemoryUtil.memPutInt(ptr, indexCount);
                MemoryUtil.memPutInt(ptr + 4, 1);
                MemoryUtil.memPutInt(ptr + 8, firstIndex);
                MemoryUtil.memPutInt(ptr + 12, vertexOffset);
                MemoryUtil.memPutInt(ptr + 16, baseInstance);

                ptr += CMD_STRIDE;
                drawCount++;
            }
        }

        if (drawCount == 0) {
            return;
        }

        ByteBuffer byteBuffer = MemoryUtil.memByteBuffer(cmdBufferPtr, queue.size() * QuadFacing.COUNT * CMD_STRIDE);
        indirectBuffer.recordCopyCmd(byteBuffer.position(0));

        vkCmdDrawIndexedIndirect(Renderer.getCommandBuffer(), indirectBuffer.getId(), indirectBuffer.getOffset(), drawCount, CMD_STRIDE);
    }

    /**
     * Iroh P3-6 (FULL pack mode): writes the indexed-indirect commands for {@code count} sections (in-area indices
     * {@code idx}, facing masks {@code mask}; -1 = every facing bucket, for the shadow pass) of one layer into the
     * shared command scratch, exactly as buildDrawBatchesIndirect does, WITHOUT drawing. Returns the command count;
     * the caller copies {@code count * CMD_STRIDE} bytes from {@link #packCommandPtr()} into its indirect buffer.
     */
    public int writePackCommands(int count, int[] idx, int[] mask, TerrainRenderType terrainRenderType) {
        long ptr = cmdBufferPtr;
        long drawParamsBasePtr = this.drawParamsPtr + (terrainRenderType.ordinal() * DrawParametersBuffer.SECTIONS * DrawParametersBuffer.FACINGS) * DrawParametersBuffer.STRIDE;
        final long facingsStride = DrawParametersBuffer.FACINGS * DrawParametersBuffer.STRIDE;
        int drawCount = 0;
        for (int j = 0; j < count; ++j) {
            long p = drawParamsBasePtr + (idx[j] * facingsStride);
            int m = mask[j];
            for (int i = 0; i < QuadFacing.COUNT; i++, p += DrawParametersBuffer.STRIDE) {
                if ((m & 1 << i) == 0)
                    continue;
                final int indexCount = DrawParametersBuffer.getIndexCount(p);
                if (indexCount <= 0)
                    continue;
                MemoryUtil.memPutInt(ptr, indexCount);
                MemoryUtil.memPutInt(ptr + 4, 1);
                MemoryUtil.memPutInt(ptr + 8, DrawParametersBuffer.getFirstIndex(p));
                MemoryUtil.memPutInt(ptr + 12, DrawParametersBuffer.getVertexOffset(p));
                MemoryUtil.memPutInt(ptr + 16, DrawParametersBuffer.getBaseInstance(p));
                ptr += CMD_STRIDE;
                drawCount++;
            }
        }
        return drawCount;
    }

    /** the raster path's facing mask for a section (back-face culling of the per-facing buckets) */
    public int facingMask(Vec3 cameraPos, RenderSection section, boolean translucent) {
        if (translucent || !Initializer.CONFIG.backFaceCulling)
            return 1 << 6;
        return getMask(cameraPos, section);
    }

    public static long packCommandPtr() {
        return cmdBufferPtr;
    }

    public static int packCommandStride() {
        return CMD_STRIDE;
    }

    public Vector3i origin() {
        return origin;
    }

    public void buildDrawBatchesDirect(Vec3 cameraPos, StaticQueue<RenderSection> queue, TerrainRenderType terrainRenderType) {
        boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;
        boolean backFaceCulling = Initializer.CONFIG.backFaceCulling && !isTranslucent;

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        long drawParamsBasePtr = this.drawParamsPtr + (terrainRenderType.ordinal() * DrawParametersBuffer.SECTIONS * DrawParametersBuffer.FACINGS) * DrawParametersBuffer.STRIDE;
        final long facingsStride = DrawParametersBuffer.FACINGS * DrawParametersBuffer.STRIDE;

        int count = 0;
        if (backFaceCulling) {
            for (var iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
                final RenderSection section = iterator.next();

                sectionIndices[count] = section.inAreaIndex;
                masks[count] = getMask(cameraPos, section);
                count++;
            }

            for (int j = 0; j < count; ++j) {
                final int sectionIdx = sectionIndices[j];

                int mask = masks[j];

                long drawParamsBasePtr2 = drawParamsBasePtr + (sectionIdx * facingsStride);

                for (int i = 0; i < QuadFacing.COUNT; i++) {

                    if ((mask & 1 << i) == 0) {
                        drawParamsBasePtr2 += DrawParametersBuffer.STRIDE;
                        continue;
                    }

                    long drawParamsPtr = drawParamsBasePtr2;

                    final int indexCount = DrawParametersBuffer.getIndexCount(drawParamsPtr);
                    final int firstIndex = DrawParametersBuffer.getFirstIndex(drawParamsPtr);
                    final int vertexOffset = DrawParametersBuffer.getVertexOffset(drawParamsPtr);
                    final int baseInstance = DrawParametersBuffer.getBaseInstance(drawParamsPtr);

                    drawParamsBasePtr2 += DrawParametersBuffer.STRIDE;

                    if (indexCount <= 0) {
                        continue;
                    }

                    vkCmdDrawIndexed(commandBuffer, indexCount, 1, firstIndex, vertexOffset, baseInstance);
                }
            }

        }
        else {
            final int facing = 6;
            final long facingOffset = facing * DrawParametersBuffer.STRIDE;
            drawParamsBasePtr += facingOffset;

            for (var iterator = queue.iterator(isTranslucent); iterator.hasNext(); ) {
                final RenderSection section = iterator.next();

                sectionIndices[count] = section.inAreaIndex;
                count++;
            }

            for (int i = 0; i < count; ++i) {
                int sectionIdx = sectionIndices[i];

                long drawParamsPtr = drawParamsBasePtr + (sectionIdx * facingsStride);

                final int indexCount = DrawParametersBuffer.getIndexCount(drawParamsPtr);
                final int firstIndex = DrawParametersBuffer.getFirstIndex(drawParamsPtr);
                final int vertexOffset = DrawParametersBuffer.getVertexOffset(drawParamsPtr);
                final int baseInstance = DrawParametersBuffer.getBaseInstance(drawParamsPtr);

                if (indexCount <= 0) {
                    continue;
                }

                vkCmdDrawIndexed(commandBuffer, indexCount, 1, firstIndex, vertexOffset, baseInstance);
            }
        }
    }

    private int getMask(Vec3 camera, RenderSection section) {
        return QuadFacing.facingMask(camera.x, camera.y, camera.z, section.xOffset, section.yOffset, section.zOffset);
    }


    public void bindBuffers(VkCommandBuffer commandBuffer, Pipeline pipeline, TerrainRenderType terrainRenderType, double camX, double camY, double camZ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var vertexBuffer = getAreaBuffer(terrainRenderType);
            nvkCmdBindVertexBuffers(commandBuffer, 0, 1, stack.npointer(vertexBuffer.getId()), stack.npointer(0));
            updateChunkAreaOrigin(commandBuffer, pipeline, camX, camY, camZ, stack);
        }

        if (terrainRenderType == TerrainRenderType.TRANSLUCENT && this.indexBuffer != null) {
            vkCmdBindIndexBuffer(commandBuffer, this.indexBuffer.getId(), 0, VK_INDEX_TYPE_UINT16);
        }
    }

    public void releaseBuffers() {
        if (this.world != null) { // P2-7a: give back what the area's sections still own, parameters reset (nothing stale to draw)
            if (!this.world.isDead()) {
                for (int rt = 0; rt < TerrainRenderType.VALUES.length; rt++)
                    for (int sec = 0; sec < DrawParametersBuffer.SECTIONS; sec++)
                        if (ownsAny(rt, sec)) resetLayerWorld(sec, rt);
            }
            return;
        }
        if (!this.allocated)
            return;

        this.vertexBuffers.values().forEach(AreaBuffer::freeBuffer);
        this.vertexBuffers.clear();

        if (this.indexBuffer != null)
            this.indexBuffer.freeBuffer();
        this.indexBuffer = null;

        this.allocated = false;
    }

    public void free() {
        this.releaseBuffers();

        DrawParametersBuffer.freeBuffer(this.drawParamsPtr);
    }

    private boolean ownsAny(int rt, int sec) {
        int ii = rt * DrawParametersBuffer.SECTIONS + sec;
        if (this.iPage[ii] != WorldArena.NONE) return true;
        for (int i = 0; i < QuadFacing.COUNT; i++)
            if (vPage(DrawParametersBuffer.getParamsPtr(this.drawParamsPtr, sec, rt, i)) != WorldArena.NONE) return true;
        return false;
    }

    public boolean isAllocated() {
        return !this.vertexBuffers.isEmpty();
    }

    public EnumMap<TerrainRenderType, AreaBuffer> getVertexBuffers() {
        return vertexBuffers;
    }

    public AreaBuffer getIndexBuffer() {
        return indexBuffer;
    }

    public long getDrawParamsPtr() {
        return drawParamsPtr;
    }

}
