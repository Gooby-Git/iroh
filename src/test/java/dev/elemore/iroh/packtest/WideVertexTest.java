package dev.elemore.iroh.packtest;

import dev.elemore.iroh.shaderpack.mesh.WideTerrainVertex;
import net.vulkanmod.render.vertex.PlaneSnap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The 36-byte PACK terrain vertex against hand-computed fields (P3-3 item 5). {@code ./gradlew wideVertexTest}; last
 * line {@code # widevertex PASS n/n}. Positions must match the 16-byte format's rounding exactly (PlaneSnap, rc10).
 */
public final class WideVertexTest {

    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok)
            pass++;
        else
            System.out.println("FAIL " + what);
    }

    /** the 16-byte format's position encoding, copied from VertexBuilder.CompressedVertexBuilder */
    static short compressed(float v) {
        return (short) Math.round(PlaneSnap.snap(v) * 2048.0f + (-4.0f * 2048.0f));
    }

    public static void main(String[] args) {
        // top face of the block at section-relative (3, 5, 7): y = 6, CCW seen from above
        float[] pos = {3, 6, 7, 3, 6, 8, 4, 6, 8, 4, 6, 7};
        float[] uv = {0.25f, 0.50f, 0.25f, 0.5625f, 0.3125f, 0.5625f, 0.3125f, 0.50f};
        int white = 0xFFFFFFFF;
        WideTerrainVertex.Quad q = new WideTerrainVertex.Quad(pos, new int[]{white, white, 0xFF808080, white},
                new float[]{1f, 0.5f, 0.75f, 0.2f}, uv, new int[]{240 | (176 << 16), 0, 48 | (240 << 16), 240 | (240 << 16)},
                new float[]{0, 1, 0}, new float[]{0.25f, 0.5f, 0.3125f, 0.5625f}, new float[]{3, 5, 7}, 14, 40001, 2);
        ByteBuffer b = ByteBuffer.allocate(WideTerrainVertex.QUAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        WideTerrainVertex.writeQuad(b, 0, q, true);
        check("quad is 144 bytes", WideTerrainVertex.QUAD_BYTES == 144 && WideTerrainVertex.BYTES == 36);
        for (int i = 0; i < 4; i++) {
            int o = i * 36;
            check("v" + i + " position = 16-byte format", b.getShort(o) == compressed(pos[3 * i]) && b.getShort(o + 2) == compressed(pos[3 * i + 1])
                    && b.getShort(o + 4) == compressed(pos[3 * i + 2]));
            check("v" + i + " position round trip", Math.abs(WideTerrainVertex.unpackPos(b.getShort(o + 2)) - 6f) < 1e-6);
        }
        check("light block/sky v0 = 240/176", (b.get(6) & 0xFF) == 240 && (b.get(7) & 0xFF) == 176);
        check("light v2 = 48/240", (b.get(2 * 36 + 6) & 0xFF) == 48 && (b.get(2 * 36 + 7) & 0xFF) == 240);
        check("uv0 v1 = 0.25, 0.5625", (b.getShort(36 + 8) & 0xFFFF) == Math.round(0.25f * 65535) && (b.getShort(36 + 10) & 0xFFFF) == Math.round(0.5625f * 65535));
        check("separateAo: alpha = AO 0.5", (b.get(36 + 15) & 0xFF) == 128 && (b.get(36 + 12) & 0xFF) == 255);
        check("tint kept in rgb (v2 grey 0x80)", (b.get(2 * 36 + 12) & 0xFF) == 0x80);
        check("normal +Y", b.get(16) == 0 && b.get(17) == 127 && b.get(18) == 0);
        // tangent: u grows along +x (v0 -> v3 has du > 0 along +x), so tangent = +X
        check("tangent +X", b.get(20) == 127 && b.get(21) == 0 && b.get(22) == 0);
        check("tangent handedness is +-127", Math.abs(b.get(23)) == 127);
        check("midTexCoord = sprite centre", (b.getShort(24) & 0xFFFF) == Math.round(0.28125f * 65535) && (b.getShort(26) & 0xFFFF) == Math.round(0.53125f * 65535));
        // block centre (3.5, 5.5, 7.5); v0 at (3, 6, 7): (0.5, -0.5, 0.5) x 64 = (32, -32, 32)
        check("midBlock v0 = (32, -32, 32)", b.get(28) == 32 && b.get(29) == -32 && b.get(30) == 32);
        check("midBlock v2 = (-32, -32, -32)", b.get(2 * 36 + 28) == -32 && b.get(2 * 36 + 29) == -32 && b.get(2 * 36 + 30) == -32);
        check("emission 14", b.get(31) == 14);
        check("block id 40001 as uint16", (b.getShort(32) & 0xFFFF) == 40001 && b.get(34) == 2);

        // without separateAo the AO goes into rgb and alpha keeps the colour's alpha
        ByteBuffer c = ByteBuffer.allocate(WideTerrainVertex.QUAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        WideTerrainVertex.writeQuad(c, 0, q, false);
        check("AO in rgb: v3 0.2 x 255", (c.get(3 * 36 + 12) & 0xFF) == 51 && (c.get(3 * 36 + 15) & 0xFF) == 255);

        // a sub-step face (powder snow inward face at 0.999875) must not land on the block plane, as in the 16-byte format
        float near = 0.999875f;
        short s = WideTerrainVertex.snapPos(near);
        check("sub-step coordinate keeps its side of the plane", s == compressed(near) && s != compressed(1.0f));

        // midBlock of a vertex computed from the UNSNAPPED position: 0.999875 in a block at 0 -> (0.5 - 0.999875) x 64 = -32
        float[] p2 = {near, 0, 0, near, 1, 0, near, 1, 1, near, 0, 1};
        WideTerrainVertex.Quad q2 = new WideTerrainVertex.Quad(p2, new int[]{white, white, white, white}, new float[]{1, 1, 1, 1},
                new float[]{0, 0, 0, 1, 1, 1, 1, 0}, new int[4], new float[]{-1, 0, 0}, new float[]{0, 0, 1, 1}, new float[]{0, 0, 0}, 0, 70000, 0);
        ByteBuffer d = ByteBuffer.allocate(WideTerrainVertex.QUAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        WideTerrainVertex.writeQuad(d, 0, q2, true);
        check("midBlock from the unsnapped position", d.get(28) == -32);
        check("block id over 65535 wraps (block.properties ids stay below 65536: Navidson max 32016)", (d.getShort(32) & 0xFFFF) == (70000 & 0xFFFF));
        builderMatchesWriter();
        System.out.println("# widevertex " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }

    /** P3-5: WideVertexBuilder through the mesher's vertex() calls == WideTerrainVertex.writeQuad (separateAo=false) */
    static void builderMatchesWriter() {
        dev.elemore.iroh.shaderpack.mesh.WideVertexBuilder b = new dev.elemore.iroh.shaderpack.mesh.WideVertexBuilder();
        var c = dev.elemore.iroh.shaderpack.mesh.WideVertexBuilder.CONTEXT.get();
        c.sprite = new float[]{0.25f, 0.5f, 0.3125f, 0.5625f};
        c.blockOrigin = new float[]{3, 7, 11};
        c.normal = new float[]{0, 0, -1};
        c.emission = 9;
        c.blockId = 10257;
        c.renderType = 1;
        float[] pos = {4, 8, 11, 4, 7, 11, 3, 7, 11, 3, 8, 11};
        float[] uv = {0.25f, 0.5f, 0.25f, 0.5625f, 0.3125f, 0.5625f, 0.3125f, 0.5f};
        int[] rgba = {0xFF4080C0, 0xFF102030, 0x80FFFFFF, 0xFF000000};
        int[] light = {0x00F00030, 0x00A00000, 0x000000F0, 0x00100010};
        long mem = org.lwjgl.system.MemoryUtil.nmemCalloc(1, 2 * dev.elemore.iroh.shaderpack.mesh.WideTerrainVertex.QUAD_BYTES);
        try {
            for (int i = 0; i < 4; i++)
                b.vertex(mem + (long) i * 36, pos[3 * i], pos[3 * i + 1], pos[3 * i + 2], rgba[i], uv[2 * i], uv[2 * i + 1], light[i], 0);
            int[] argb = new int[4];
            for (int i = 0; i < 4; i++) {
                int r = rgba[i] & 0xFF, g = (rgba[i] >>> 8) & 0xFF, bl = (rgba[i] >>> 16) & 0xFF, a = rgba[i] >>> 24;
                argb[i] = a << 24 | r << 16 | g << 8 | bl;
            }
            java.nio.ByteBuffer want = java.nio.ByteBuffer.allocate(144).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            dev.elemore.iroh.shaderpack.mesh.WideTerrainVertex.writeQuad(want, 0, new dev.elemore.iroh.shaderpack.mesh.WideTerrainVertex.Quad(
                    pos, argb, new float[]{1, 1, 1, 1}, uv, light, c.normal, c.sprite, c.blockOrigin, 9, 10257, 1), false);
            java.nio.ByteBuffer got = org.lwjgl.system.MemoryUtil.memByteBuffer(mem, 144);
            boolean same = true;
            for (int i = 0; i < 144; i++)
                same &= got.get(i) == want.get(i);
            check("WideVertexBuilder == writeQuad, byte for byte", same);
            check("WideVertexBuilder stride 36", b.getStride() == 36);
            // a second quad right after: the context restarts
            for (int i = 0; i < 4; i++)
                b.vertex(mem + 144 + (long) i * 36, pos[3 * i], pos[3 * i + 1], pos[3 * i + 2], rgba[i], uv[2 * i], uv[2 * i + 1], light[i], 0);
            java.nio.ByteBuffer got2 = org.lwjgl.system.MemoryUtil.memByteBuffer(mem + 144, 144);
            boolean same2 = true;
            for (int i = 0; i < 144; i++)
                same2 &= got2.get(i) == want.get(i);
            check("second quad written at its own pointer", same2);
        } finally {
            org.lwjgl.system.MemoryUtil.nmemFree(mem);
        }
    }
}
