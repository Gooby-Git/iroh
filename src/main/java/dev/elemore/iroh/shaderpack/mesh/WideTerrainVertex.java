package dev.elemore.iroh.shaderpack.mesh;

import net.vulkanmod.render.vertex.PlaneSnap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The PACK-path terrain vertex (RQ-5 spec, P3-2 GeometryNeeds.WIDE_TERRAIN; 36 bytes). Used only while Navidson is
 * active; the raster path keeps the 16-byte compressed vertex (net.vulkanmod.render.vertex.VertexBuilder), so the
 * x2.25 section memory applies only with the pack on. Pure packing, no renderer state: the mesher integration point is
 * BlockRenderer.bufferQuad (see knowledge/iris-loader.md "P3-3 integration checklist").
 * <pre>
 * +0  int16 x3  position, 1/2048 block, -4 block offset, PlaneSnap rounding (identical to the 16-byte format)
 * +6  uint8 x2  lightmap coordinate block, sky (0..240 as vanilla packs it; gl_TextureMatrix[1] maps it, Iris)
 * +8  unorm16x2 uv0
 * +12 RGBA8     colour: tint x face shade; separateAo -> alpha = AO, else AO multiplied into rgb (alpha = 1)
 * +16 snorm8x3  normal (+ pad)
 * +20 snorm8x4  tangent xyz + handedness (at_tangent.w)
 * +24 unorm16x2 mc_midTexCoord (sprite centre)
 * +28 int8 x3   at_midBlock = round((block centre - UNSNAPPED vertex) x 64), uint8 light emission (at_midBlock.w)
 * +32 uint16    mc_Entity.x block id (block.properties), uint8 mc_Entity.y render type, uint8 pad
 * </pre>
 */
public final class WideTerrainVertex {

    public static final int BYTES = 36;
    /** gl_TextureMatrix[0] scale for uv0 read as UNORM16 (stored x32768) */
    public static final double UV0_TO_UNIT = 65535.0 / 32768.0;
    public static final int QUAD_BYTES = 4 * BYTES;
    static final float POS_OFFSET = -4.0f;

    /**
     * One quad as the mesher has it: section-relative float positions (UNSNAPPED), per-vertex colour (ARGB tint x shade,
     * AO not applied), per-vertex AO 0..1, uv 0..1, vanilla packed light (block | sky << 16 in 0..240 each), face normal,
     * sprite bounds (u0, v0, u1, v1), the block's section-relative min corner, its light emission, block.properties id
     * and render type.
     */
    public record Quad(float[] pos, int[] argb, float[] ao, float[] uv, int[] light, float[] normal, float[] sprite,
                       float[] blockOrigin, int emission, int blockId, int renderType) {}

    private WideTerrainVertex() {}

    public static void writeQuad(ByteBuffer dst, int off, Quad q, boolean separateAo) {
        ByteBuffer b = dst.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        byte[] tangent = tangent(q);
        short midU = unorm16((q.sprite()[0] + q.sprite()[2]) * 0.5f);
        short midV = unorm16((q.sprite()[1] + q.sprite()[3]) * 0.5f);
        for (int i = 0; i < 4; i++) {
            int o = off + i * BYTES;
            float x = q.pos()[3 * i], y = q.pos()[3 * i + 1], z = q.pos()[3 * i + 2];
            b.putShort(o, snapPos(x));
            b.putShort(o + 2, snapPos(y));
            b.putShort(o + 4, snapPos(z));
            int light = q.light()[i];
            b.put(o + 6, (byte) (light & 0xFF));
            b.put(o + 7, (byte) ((light >>> 16) & 0xFF));
            // P3-6: uv0 on the compressed format's x32768 grid (the raster fallback reads these 16 bytes as-is); the
            // pack pipeline reads it as UNORM16 and gl_TextureMatrix[0] scales by 65535/32768 (UV0_TO_UNIT)
            b.putShort(o + 8, (short) (q.uv()[2 * i] * 32768f));
            b.putShort(o + 10, (short) (q.uv()[2 * i + 1] * 32768f));
            int c = q.argb()[i];
            float ao = q.ao()[i];
            float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, bl = (c & 0xFF) / 255f;
            if (!separateAo) {
                r *= ao;
                g *= ao;
                bl *= ao;
            }
            b.put(o + 12, u8(r)).put(o + 13, u8(g)).put(o + 14, u8(bl)).put(o + 15, u8(separateAo ? ao : ((c >>> 24) & 0xFF) / 255f));
            b.put(o + 16, s8(q.normal()[0])).put(o + 17, s8(q.normal()[1])).put(o + 18, s8(q.normal()[2])).put(o + 19, (byte) 0);
            b.put(o + 20, tangent[0]).put(o + 21, tangent[1]).put(o + 22, tangent[2]).put(o + 23, tangent[3]);
            b.putShort(o + 24, midU).putShort(o + 26, midV);
            for (int a = 0; a < 3; a++) {
                float centre = q.blockOrigin()[a] + 0.5f;
                int m = Math.round((centre - q.pos()[3 * i + a]) * 64f);
                b.put(o + 28 + a, (byte) Math.max(-127, Math.min(127, m)));
            }
            b.put(o + 31, (byte) Math.max(0, Math.min(255, q.emission())));
            b.putShort(o + 32, (short) (q.blockId() & 0xFFFF));
            b.put(o + 34, (byte) q.renderType()).put(o + 35, (byte) 0);
        }
    }

    /** same rounding as the 16-byte format (rc10 PlaneSnap) */
    public static short snapPos(float v) {
        return (short) Math.round(PlaneSnap.snap(v) * PlaneSnap.STEPS + POS_OFFSET * PlaneSnap.STEPS);
    }

    public static float unpackPos(short s) {
        return s / PlaneSnap.STEPS - POS_OFFSET;
    }

    /** per-quad tangent from positions and uvs (vertices 0, 1, 2), snorm8 xyz + handedness */
    static byte[] tangent(Quad q) {
        float[] p = q.pos(), t = q.uv();
        float e1x = p[3] - p[0], e1y = p[4] - p[1], e1z = p[5] - p[2];
        float e2x = p[6] - p[0], e2y = p[7] - p[1], e2z = p[8] - p[2];
        float du1 = t[2] - t[0], dv1 = t[3] - t[1], du2 = t[4] - t[0], dv2 = t[5] - t[1];
        float det = du1 * dv2 - du2 * dv1;
        float f = Math.abs(det) < 1e-12f ? 0 : 1 / det;
        float tx = f * (dv2 * e1x - dv1 * e2x), ty = f * (dv2 * e1y - dv1 * e2y), tz = f * (dv2 * e1z - dv1 * e2z);
        float bx = f * (du1 * e2x - du2 * e1x), by = f * (du1 * e2y - du2 * e1y), bz = f * (du1 * e2z - du2 * e1z);
        float l = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (l < 1e-12f) {
            // degenerate uv: any vector perpendicular to the normal
            float[] n = q.normal();
            tx = Math.abs(n[1]) > 0.9f ? 1 : 0;
            ty = 0;
            tz = Math.abs(n[1]) > 0.9f ? 0 : 1;
            l = 1;
        }
        tx /= l;
        ty /= l;
        tz /= l;
        float[] n = q.normal();
        float cx = n[1] * tz - n[2] * ty, cy = n[2] * tx - n[0] * tz, cz = n[0] * ty - n[1] * tx;
        float w = (cx * bx + cy * by + cz * bz) < 0 ? -1 : 1;
        return new byte[]{s8(tx), s8(ty), s8(tz), s8(w)};
    }

    static byte u8(float v) {
        return (byte) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    static byte s8(float v) {
        return (byte) Math.round(Math.max(-1, Math.min(1, v)) * 127);
    }

    static short unorm16(float v) {
        return (short) Math.round(Math.max(0, Math.min(1, v)) * 65535);
    }
}
