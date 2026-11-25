package net.vulkanmod.render.chunk.cull;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * P2-7b (L5b): the block-aligned bounding box of one section layer's geometry, in section-local blocks, packed into one
 * 32-bit word for the GPU cull's metadata (terrain_cull.comp decodes it the same way).
 *
 * <p>Bits 0-14: lo.x, lo.y, lo.z (5 bits each, value + 8, range -8..23); bits 15-29: hi.x, hi.y, hi.z (value + 7,
 * range -7..24); bit 30 = present; bit 31 = overflow (geometry outside the representable range: the shader then uses
 * the compressed vertex format's whole range, -12..20). 0 = no geometry recorded (the shader uses that whole range too).
 * lo = floor(min), hi = ceil(max) of every vertex position: never smaller than the geometry.
 *
 * <p>The scan reads the compressed 16-byte terrain vertex (int16 x, y, z at +0, +2, +4; position = s / 2048 + 4, see
 * VertexBuilder.CompressedVertexBuilder: POS_OFFSET -4). Worker thread (UploadBuffer), pure, tested by terrainCullTest.
 */
public final class CullBounds {
    public static final int PRESENT = 1 << 30, OVERFLOW = 1 << 31;
    public static final int VERTEX_STRIDE = 16;
    /** the compressed format's whole position range (int16 / 2048 + 4) */
    public static final int RANGE_LO = -12, RANGE_HI = 20;

    private CullBounds() {}

    /** packs a box in blocks; OVERFLOW (with PRESENT) when it does not fit */
    public static int pack(int lx, int ly, int lz, int hx, int hy, int hz) {
        if (lx < -8 || ly < -8 || lz < -8 || hx > 24 || hy > 24 || hz > 24 || lx > 23 || ly > 23 || lz > 23 || hx < -7 || hy < -7 || hz < -7)
            return PRESENT | OVERFLOW;
        return PRESENT | (lx + 8) | (ly + 8) << 5 | (lz + 8) << 10 | (hx + 7) << 15 | (hy + 7) << 20 | (hz + 7) << 25;
    }

    /** {lo.x, lo.y, lo.z, hi.x, hi.y, hi.z} in blocks; the compressed format's whole range when absent or overflowing */
    public static int[] unpack(int w) {
        if ((w & PRESENT) == 0 || (w & OVERFLOW) != 0)
            return new int[]{RANGE_LO, RANGE_LO, RANGE_LO, RANGE_HI, RANGE_HI, RANGE_HI};
        return new int[]{(w & 31) - 8, (w >> 5 & 31) - 8, (w >> 10 & 31) - 8, (w >> 15 & 31) - 7, (w >> 20 & 31) - 7, (w >> 25 & 31) - 7};
    }

    /** union of two packed boxes (0 = none) */
    public static int union(int a, int b) {
        if ((a & PRESENT) == 0) return b;
        if ((b & PRESENT) == 0) return a;
        if (((a | b) & OVERFLOW) != 0) return PRESENT | OVERFLOW;
        int[] p = unpack(a), q = unpack(b);
        return pack(Math.min(p[0], q[0]), Math.min(p[1], q[1]), Math.min(p[2], q[2]), Math.max(p[3], q[3]), Math.max(p[4], q[4]), Math.max(p[5], q[5]));
    }

    /**
     * The box of the compressed vertices in {@code buffers} (null entries skipped; position .. limit of each), or 0 when
     * there are none.
     */
    public static int scan(ByteBuffer[] buffers) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (ByteBuffer b : buffers) {
            if (b == null) continue;
            long p = MemoryUtil.memAddress(b), end = p + (b.remaining() / VERTEX_STRIDE) * (long) VERTEX_STRIDE;
            for (; p < end; p += VERTEX_STRIDE) {
                int x = MemoryUtil.memGetShort(p), y = MemoryUtil.memGetShort(p + 2), z = MemoryUtil.memGetShort(p + 4);
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
                any = true;
            }
        }
        if (!any) return 0;
        // block = floor(s / 2048) + 4 for lo, ceil(s / 2048) + 4 for hi (arithmetic shift = floor)
        return pack((minX >> 11) + 4, (minY >> 11) + 4, (minZ >> 11) + 4,
                -((-maxX) >> 11) + 4, -((-maxY) >> 11) + 4, -((-maxZ) >> 11) + 4);
    }
}
