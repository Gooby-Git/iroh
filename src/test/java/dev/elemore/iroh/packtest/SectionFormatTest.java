package dev.elemore.iroh.packtest;

import dev.elemore.iroh.shaderpack.mesh.WideTerrainVertex;
import dev.elemore.iroh.shaderpack.mesh.WideVertexBuilder;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import net.vulkanmod.render.vertex.VertexBuilder;
import org.lwjgl.system.MemoryUtil;

import java.util.Random;

/**
 * P3-6: the 36-byte pack section vertex through the REAL builder path (TerrainBufferBuilder.vertex ->
 * WideVertexBuilder) against the 16-byte compressed one (CompressedVertexBuilder) for the same quads: the first 16
 * bytes of every vertex are identical (PlaneSnap positions, light, uv0 x32768, colour), so the raster terrain shader
 * draws pack-format sections correctly with stride 36 and both formats put every vertex at the same position.
 * Also: stride, buffer size, the per-quad context fields land at their offsets. {@code ./gradlew sectionFormatTest}.
 */
public final class SectionFormatTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    public static void main(String[] args) {
        Random r = new Random(7);
        int quads = 2000;
        TerrainBufferBuilder narrow = new TerrainBufferBuilder(64, 16, new VertexBuilder.CompressedVertexBuilder());
        TerrainBufferBuilder wide = new TerrainBufferBuilder(64, 36, new WideVertexBuilder());
        int mismatch = 0, posMismatch = 0;
        WideVertexBuilder.QuadContext c = WideVertexBuilder.CONTEXT.get();
        for (int q = 0; q < quads; q++) {
            // block-ish quads with sub-block offsets, plane coordinates near block planes (PlaneSnap cases), section range
            float bx = r.nextInt(16), by = r.nextInt(16), bz = r.nextInt(16);
            float inset = r.nextInt(4) == 0 ? 1e-4f * r.nextInt(5) : r.nextInt(3) == 0 ? 0.0625f * r.nextInt(16) : 0;
            float[][] v = {{bx + inset, by + 1, bz}, {bx + inset, by, bz}, {bx + 1, by, bz + 1 - inset}, {bx + 1, by + 1, bz + 1}};
            c.sprite = new float[]{0.25f, 0.5f, 0.3125f, 0.5625f};
            c.blockOrigin = new float[]{bx, by, bz};
            c.normal = new float[]{0, 0, 1};
            c.emission = r.nextInt(16);
            c.blockId = r.nextInt(20000);
            c.renderType = r.nextInt(5);
            narrow.ensureCapacity();
            wide.ensureCapacity();
            for (int i = 0; i < 4; i++) {
                int color = r.nextInt();
                float u = 0.25f + 0.0625f * (i == 1 || i == 2 ? 1 : 0), vv = 0.5f + 0.0625f * (i >= 2 ? 1 : 0);
                int light = (r.nextInt(16) * 16) | (r.nextInt(16) * 16) << 16;
                narrow.vertex(v[i][0], v[i][1], v[i][2], color, u, vv, light, 0);
                wide.vertex(v[i][0], v[i][1], v[i][2], color, u, vv, light, 0);
            }
        }
        long a = narrow.getPtr(), b = wide.getPtr();
        for (int i = 0; i < quads * 4; i++) {
            for (int k = 0; k < 16; k++)
                if (MemoryUtil.memGetByte(a + i * 16L + k) != MemoryUtil.memGetByte(b + i * 36L + k)) {
                    mismatch++;
                    if (k < 6)
                        posMismatch++;
                    break;
                }
        }
        check("first 16 bytes identical for all " + quads * 4 + " vertices (" + mismatch + " differ, " + posMismatch + " in position)", mismatch == 0);
        check("buffer sizes 16 vs 36 per vertex", narrow.getBuffer().remaining() == quads * 64 && wide.getBuffer().remaining() == quads * 144);
        // last quad's context fields
        long last = b + (quads - 1) * 144L;
        check("block id at +32", (MemoryUtil.memGetShort(last + 32) & 0xFFFF) == (c.blockId & 0xFFFF));
        check("render type at +34", MemoryUtil.memGetByte(last + 34) == c.renderType);
        check("emission at +31", (MemoryUtil.memGetByte(last + 31) & 0xFF) == c.emission);
        check("normal z at +18 = 127", MemoryUtil.memGetByte(last + 18) == 127);
        check("uv0 on the x32768 grid", (MemoryUtil.memGetShort(last + 8) & 0xFFFF) == (int) (0.25f * 32768));
        check("UV0_TO_UNIT maps it back", Math.abs((MemoryUtil.memGetShort(last + 8) & 0xFFFF) / 65535.0 * WideTerrainVertex.UV0_TO_UNIT - 0.25) < 1e-4);
        System.out.println("# sectionformat " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
