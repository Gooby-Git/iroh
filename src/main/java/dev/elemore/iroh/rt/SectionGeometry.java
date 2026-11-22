package dev.elemore.iroh.rt;

/**
 * One meshed 16^3 section as the RT core needs it, taken from the RASTER path (no re-meshing): where its vertices
 * live (the area buffer's device address + byte offset; 16- or 36-byte vertices, positions first), quads per bucket
 * (0 opaque, 1 alpha-tested, 2 translucent), the emissive quads found at mesh time, and a build version (latest
 * wins). Minecraft-free: the game adapter fills it from VulkanMod's DrawBuffers / AreaBuffer segments.
 */
public record SectionGeometry(int sx, int sy, int sz, long vertexAddress, long vertexOffset, int vertexStride, int[] bucketQuads,
                              long[] bucketOffsets, float[] emissiveQuads, long version) {
    public static final int OPAQUE = 0, CUTOUT = 1, TRANSLUCENT = 2;

    public int totalQuads() {
        return bucketQuads[0] + bucketQuads[1] + bucketQuads[2];
    }

    public boolean empty() {
        return totalQuads() == 0;
    }

    public long key() {
        return key(sx, sy, sz);
    }

    public static long key(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFF);
    }
}
