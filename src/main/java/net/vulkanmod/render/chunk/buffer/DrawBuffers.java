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

    //Need ugly minHeight Parameter to fix custom world heights (exceeding 384 Blocks in total)
    public DrawBuffers(int index, Vector3i origin, int minHeight) {
        this.index = index;
        this.origin = origin;
        this.minHeight = minHeight;

        this.drawParamsPtr = DrawParametersBuffer.allocateBuffer();
    }

    public void upload(RenderSection section, UploadBuffer buffer, TerrainRenderType renderType) {
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
