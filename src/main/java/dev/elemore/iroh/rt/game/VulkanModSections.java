package dev.elemore.iroh.rt.game;

import dev.elemore.iroh.rt.SectionGeometry;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.AreaBuffer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.buffer.DrawParametersBuffer;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK12.vkGetBufferDeviceAddress;

/**
 * P5-2: {@link RtSectionSource} over VulkanMod's chunk storage (game only; UNVERIFIED in game). A section's quads per
 * render type and quad facing are runs in that render type's VERTEX AreaBuffer of the section's ChunkArea
 * (DrawParametersBuffer: vertexOffset in vertices, indexCount = quads x 6). Buckets: SOLID -> OPAQUE, CUTOUT /
 * CUTOUT_MIPPED -> CUTOUT, TRANSLUCENT / TRIPWIRE -> TRANSLUCENT. Arena buffers carry SHADER_DEVICE_ADDRESS only when RT
 * was enabled at launch ({@link RtVma#arenaUsage}).
 */
public final class VulkanModSections implements RtSectionSource {
    static int bucket(TerrainRenderType t) {
        return switch (t) {
            case SOLID -> SectionGeometry.OPAQUE;
            case CUTOUT, CUTOUT_MIPPED -> SectionGeometry.CUTOUT;
            default -> SectionGeometry.TRANSLUCENT;
        };
    }

    static RenderSection section(int sx, int sy, int sz) {
        WorldRenderer wr = WorldRenderer.getInstance();
        if (wr == null || wr.getSectionGrid() == null) return null;
        RenderSection s = wr.getSectionGrid().getSectionAtBlockPos(sx << 4, sy << 4, sz << 4);
        if (s == null || s.xOffset != sx << 4 || s.yOffset != sy << 4 || s.zOffset != sz << 4 || !s.isCompiled()) return null;
        return s;
    }

    @Override
    public int[] quads(int sx, int sy, int sz) {
        RenderSection s = section(sx, sy, sz);
        if (s == null) return null;
        int[] q = new int[3];
        long base = s.getChunkArea().getDrawBuffers().getDrawParamsPtr();
        for (TerrainRenderType t : TerrainRenderType.VALUES)
            for (int f = 0; f < QuadFacing.COUNT; f++)
                q[bucket(t)] += DrawParametersBuffer.getIndexCount(DrawParametersBuffer.getParamsPtr(base, s.inAreaIndex, t.ordinal(), f)) / 6;
        return q;
    }

    @Override
    public List<Range> ranges(int sx, int sy, int sz) {
        RenderSection s = section(sx, sy, sz);
        List<Range> out = new ArrayList<>();
        if (s == null) return out;
        DrawBuffers db = s.getChunkArea().getDrawBuffers();
        long base = db.getDrawParamsPtr();
        try (MemoryStack st = MemoryStack.stackPush()) {
            for (TerrainRenderType t : TerrainRenderType.VALUES) {
                AreaBuffer ab = db.getAreaBuffer(t);
                if (ab == null) continue;
                long addr = vkGetBufferDeviceAddress(net.vulkanmod.vulkan.device.DeviceManager.vkDevice,
                        VkBufferDeviceAddressInfo.calloc(st).sType$Default().buffer(ab.getId()));
                for (int f = 0; f < QuadFacing.COUNT; f++) {
                    long p = DrawParametersBuffer.getParamsPtr(base, s.inAreaIndex, t.ordinal(), f);
                    int quads = DrawParametersBuffer.getIndexCount(p) / 6;
                    if (quads > 0)
                        out.add(new Range(bucket(t), addr, (long) DrawParametersBuffer.getVertexOffset(p) * DrawBuffers.VERTEX_SIZE, DrawBuffers.VERTEX_SIZE, quads));
                }
            }
        }
        return out;
    }
}
