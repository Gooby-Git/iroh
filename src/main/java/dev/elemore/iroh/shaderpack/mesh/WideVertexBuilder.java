package dev.elemore.iroh.shaderpack.mesh;

import net.vulkanmod.render.vertex.VertexBuilder;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The PACK-path terrain {@link VertexBuilder} (P3-5): writes {@link WideTerrainVertex} (36 bytes) through the same
 * {@code vertex(ptr, ...)} calls BlockRenderer.bufferQuad makes for the 16-byte format. The fields the 16-byte call
 * does not carry (sprite bounds, block origin, emission, block id, render type, face normal) come from a per-thread
 * {@link QuadContext} that the mesher sets once per quad before its four vertex calls; the four vertices are buffered
 * and the quad is written on the fourth (the tangent needs all four). Colour arrives as Iroh packs it (RGBA bytes, AO
 * and shade already multiplied): the wide vertex is written with separateAo=false, as Iris does when separateAo is off.
 * NOT wired into the mesher yet (knowledge/iris-loader.md section 8: the format switch is a restart-only switch).
 */
public final class WideVertexBuilder implements VertexBuilder {

    /** per quad, set by the mesher before the four vertex calls */
    public static final class QuadContext {
        public float[] sprite = {0, 0, 1, 1};
        public float[] blockOrigin = new float[3];
        public float[] normal = {0, 1, 0};
        public int emission, blockId = -1, renderType;
        // the quad being assembled
        final float[] pos = new float[12], uv = new float[8];
        final int[] argb = new int[4], light = new int[4];
        int n;
        long first;
    }

    public static final ThreadLocal<QuadContext> CONTEXT = ThreadLocal.withInitial(QuadContext::new);

    @Override
    public void vertex(long ptr, float x, float y, float z, int color, float u, float v, int light, int packedNormal) {
        QuadContext c = CONTEXT.get();
        if (c.n == 0)
            c.first = ptr;
        int i = c.n;
        c.pos[3 * i] = x;
        c.pos[3 * i + 1] = y;
        c.pos[3 * i + 2] = z;
        c.uv[2 * i] = u;
        c.uv[2 * i + 1] = v;
        // Iroh's terrain colour int is RGBA in memory order (little-endian 0xAABBGGRR): to ARGB
        int r = color & 0xFF, g = (color >>> 8) & 0xFF, b = (color >>> 16) & 0xFF, a = (color >>> 24) & 0xFF;
        c.argb[i] = a << 24 | r << 16 | g << 8 | b;
        c.light[i] = light;
        if (++c.n < 4)
            return;
        c.n = 0;
        WideTerrainVertex.Quad q = new WideTerrainVertex.Quad(c.pos.clone(), c.argb.clone(), new float[]{1, 1, 1, 1}, c.uv.clone(),
                c.light.clone(), c.normal.clone(), c.sprite.clone(), c.blockOrigin.clone(), c.emission, c.blockId, c.renderType);
        ByteBuffer dst = MemoryUtil.memByteBuffer(c.first, WideTerrainVertex.QUAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        WideTerrainVertex.writeQuad(dst, 0, q, false);
    }

    @Override
    public void position(long ptr, float x, float y, float z) {
        // per-field writes are not used by the terrain mesher for this format
    }

    @Override
    public void color(long ptr, int color) {
    }

    @Override
    public void uv(long ptr, float u, float v) {
    }

    @Override
    public void light(long ptr, int light) {
    }

    @Override
    public void normal(long ptr, int normal) {
    }

    @Override
    public int getStride() {
        return WideTerrainVertex.BYTES;
    }
}
