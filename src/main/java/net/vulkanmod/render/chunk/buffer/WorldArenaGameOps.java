package net.vulkanmod.render.chunk.buffer;

import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.VertexBuffer;

import java.nio.ByteBuffer;

/** P2-7a: the game's {@link WorldArena.Ops}: device-local Vertex/IndexBuffers, UploadManager copies, frame-slot deferred frees. */
public final class WorldArenaGameOps implements WorldArena.Ops {
    public static final WorldArenaGameOps INSTANCE = new WorldArenaGameOps();

    private WorldArenaGameOps() {}

    @Override
    public Object allocate(boolean index, long bytes) {
        AreaBuffer.bufferEvents++; // the intake budget does not learn from a section that created a device buffer (P2-4)
        return index ? new IndexBuffer((int) bytes, MemoryTypes.GPU_MEM) : new VertexBuffer((int) bytes, MemoryTypes.GPU_MEM);
    }

    @Override
    public long id(Object buffer) {
        return ((Buffer) buffer).getId();
    }

    @Override
    public void upload(Object dst, long dstOffset, ByteBuffer src) {
        UploadManager.INSTANCE.recordUpload((Buffer) dst, dstOffset, src.remaining(), src);
    }

    @Override
    public void retire(Object buffer) {
        ((Buffer) buffer).scheduleFree();
    }

    @Override
    public void later(Runnable r) {
        // same slot rule as AreaBuffer's segment frees (MemoryManager.addToFreeSegment): runs when this frame slot comes round
        MemoryManager.getInstance().addFrameOp(r);
    }
}
