package net.vulkanmod.render.chunk.build;

import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.chunk.util.BufferUtil;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import net.vulkanmod.render.vertex.TerrainBuilder;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class UploadBuffer {
    public final int indexCount;
    public final boolean autoIndices;
    public final boolean indexOnly;
    /**
     * P2-7b: the layer's geometry box (CullBounds, 0 = none) for the GPU cull's metadata; scanned here on the worker from
     * the builder's heap buffers (never from write-combined staging) only while a SectionCullData exists.
     */
    public final int cullBounds;
    private final ByteBuffer[] vertexBuffers;
    private final ByteBuffer indexBuffer;
    /** perf/uploads: which buffers live in ring staging (written here, on the worker) instead of the native heap. */
    private final boolean[] vertexStaged;
    private boolean indexStaged, released;

    /** -Diroh.workerStaging=false keeps the base behaviour (heap copy here, staging copy on the render thread). */
    private static final boolean WORKER_STAGING = !"false".equals(System.getProperty("iroh.workerStaging"));

    /**
     * Worker thread: the mesh bytes go straight into a ring-staging reservation (the render thread then only records the
     * copy); when the ring is at its budget, a heap copy as before.
     */
    private static ByteBuffer stageOrClone(ByteBuffer src, boolean[] stagedOut, int idx) {
        var ring = WORKER_STAGING ? net.vulkanmod.vulkan.memory.buffer.StagingBuffer.ring() : null;
        int size = src.remaining();
        if (ring != null && size > 0) {
            var a = ring.reserveForWorker(size, 4); // rc8: workers may pin at most half the staging budget
            if (a != null) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(src), a.address, size);
                ring.track(a);
                stagedOut[idx] = true;
                return MemoryUtil.memByteBuffer(a.address, size);
            }
        }
        return BufferUtil.clone(src);
    }

    public UploadBuffer(TerrainBuilder terrainBuilder, TerrainBuilder.DrawState drawState) {
        this.indexCount = drawState.indexCount();
        this.autoIndices = drawState.sequentialIndex();
        this.indexOnly = drawState.indexOnly();

        boolean[] staged = new boolean[QuadFacing.COUNT + 1];
        int bounds = 0;
        if (!this.indexOnly) {
            this.vertexBuffers = new ByteBuffer[QuadFacing.COUNT];
            // only the compressed 16-byte terrain vertex is scanned (CullBounds decodes int16 / 2048 + 4)
            boolean scan = net.vulkanmod.render.chunk.cull.SectionCullData.current() != null 
                    && net.vulkanmod.render.vertex.CustomVertexFormat.compressedLayout(net.vulkanmod.render.PipelineManager.terrainVertexFormat);

            for (int i = 0; i < QuadFacing.COUNT; i++) {
                var bufferBuilder = terrainBuilder.getBufferBuilder(i);

                if (bufferBuilder.getVertices() > 0) {
                    ByteBuffer src = bufferBuilder.getBuffer();
                    if (scan && src.remaining() == bufferBuilder.getVertices() * net.vulkanmod.render.chunk.cull.CullBounds.VERTEX_STRIDE) // compressed format only
                        bounds = net.vulkanmod.render.chunk.cull.CullBounds.union(bounds, net.vulkanmod.render.chunk.cull.CullBounds.scan(new ByteBuffer[]{src}));
                    this.vertexBuffers[i] = stageOrClone(src, staged, i);
                }
            }
        }
        else {
            this.vertexBuffers = null;
        }
        this.vertexStaged = staged;
        this.cullBounds = bounds;

        if (!drawState.sequentialIndex()) {
            this.indexBuffer = stageOrClone(terrainBuilder.getIndexBuffer(), staged, QuadFacing.COUNT);
            this.indexStaged = staged[QuadFacing.COUNT];
        }
        else {
            this.indexBuffer = null;
        }
    }

    /** perf/uploads: total bytes this result uploads (budget accounting). */
    public long byteSize() {
        long n = indexBuffer != null ? indexBuffer.remaining() : 0;
        if (vertexBuffers != null)
            for (var b : vertexBuffers) if (b != null) n += b.remaining();
        return n;
    }

    public int indexCount() {
        return indexCount;
    }

    public ByteBuffer[] getVertexBuffers() {
        return vertexBuffers;
    }

    public ByteBuffer getIndexBuffer() {
        return indexBuffer;
    }

    /** Idempotent. Staged buffers already copied were consumed by the upload; others are cancelled (block reusable). */
    public void release() {
        if (released)
            return;
        released = true;
        if (vertexBuffers != null)
            for (int i = 0; i < vertexBuffers.length; i++) {
                var vertexBuffer = vertexBuffers[i];
                if (vertexBuffer != null)
                    free(vertexBuffer, vertexStaged[i]);
            }

        if (indexBuffer != null)
            free(indexBuffer, indexStaged);
    }

    private static void free(ByteBuffer b, boolean staged) {
        if (!staged) {
            MemoryUtil.memFree(b);
            return;
        }
        var ring = net.vulkanmod.vulkan.memory.buffer.StagingBuffer.ring();
        var a = ring == null ? null : ring.tracked(MemoryUtil.memAddress(b));
        if (a != null)
            ring.cancel(a); // never uploaded (section dropped): its block can be reused
    }
}
