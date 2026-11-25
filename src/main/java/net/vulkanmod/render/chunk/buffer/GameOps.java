package net.vulkanmod.render.chunk.buffer;

import dev.elemore.iroh.diag.SlowFrames;
import dev.elemore.iroh.perf.AreaArena;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.VertexBuffer;

import java.nio.ByteBuffer;

/** P2-4: the game's {@link AreaBuffer.Ops} (VulkanMod's behaviour when iroh.area.arena is off). */
final class GameOps implements AreaBuffer.Ops {
    @Override
    public boolean arena() {
        return AreaArena.on();
    }

    @Override
    public long arenaSize(long minBytes) {
        return AreaArena.sizeFor(minBytes);
    }

    @Override
    public Buffer allocate(int usage, long bytes, boolean arena) {
        int syncBefore = AreaArena.syncAllocs;
        Buffer b;
        if (arena)
            b = AreaArena.take(bytes);
        else if (usage == AreaBuffer.Usage.VERTEX.usage)
            b = new VertexBuffer((int) bytes, MemoryTypes.GPU_MEM, dev.elemore.iroh.rt.game.RtVma.arenaUsage()); // P5-2: 0 unless RT
        else
            b = new IndexBuffer((int) bytes, MemoryTypes.GPU_MEM);
        // render-thread device buffer creations for area buffers (pool hits do not count)
        if (SlowFrames.ON && (!arena || AreaArena.syncAllocs != syncBefore))
            SlowFrames.count(SlowFrames.Counter.AREA_ALLOCS, 1);
        return b;
    }

    @Override
    public void retire(Buffer b) {
        if (b instanceof AreaArena.ArenaBuffer) AreaArena.recycle(b);
        else b.scheduleFree();
    }

    @Override
    public void recordUpload(Buffer dst, long dstOffset, long size, ByteBuffer src) {
        UploadManager.INSTANCE.recordUpload(dst, dstOffset, size, src);
    }

    @Override
    public void copyAll(Buffer src, Buffer dst) {
        UploadManager.INSTANCE.copyBuffer(src, 0, dst, 0, Math.min(src.getBufferSize(), dst.getBufferSize()));
    }

    @Override
    public void copyRegions(Buffer src, Buffer dst, long[] offsets, long[] sizes, int n) {
        UploadManager.INSTANCE.copyRegions(src, dst, offsets, sizes, n);
    }

    @Override
    public void deferSegmentFree(AreaBuffer a, int elementOffset) {
        MemoryManager.getInstance().addToFreeSegment(a, elementOffset);
    }
}
