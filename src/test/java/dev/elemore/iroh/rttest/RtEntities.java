package dev.elemore.iroh.rttest;

import dev.elemore.iroh.rt.vk.RtAccel;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * P5-3 offline entities: rigid model parts meshed ONCE in Iroh's 16-byte vertex (part-local positions, pivot at the
 * origin), posed every frame by a row-major 3x4 transform, exactly as docs/P5-DESIGN.md section 14 plans for vanilla
 * ModelParts. Part models: 0 crate (the "moving box"), 1 head, 2 body, 3 left arm, 4 right arm, 5 left leg, 6 right leg
 * (a zombie-like six-part "mob" in Minecraft's proportions: 8x8x8 head, 8x12x4 body, 4x12x4 limbs, pixels / 16).
 * The walk: constant speed along a straight path, body yaw along it, limbs swinging +-0.7 rad (legs opposite to arms).
 */
final class RtEntities {
    static final int T_SKIN = 24, T_SHIRT = 25, T_PANTS = 26, T_CRATE = 27;
    static final int CRATE = 0, HEAD = 1, BODY = 2, ARM_L = 3, ARM_R = 4, LEG_L = 5, LEG_R = 6, PARTS = 7;

    /** Part-local box (blocks, relative to the pivot) and its atlas tile. */
    record Box(float x0, float y0, float z0, float x1, float y1, float z1, int tile) {}

    static final Box[] BOXES = {
            new Box(-0.4f, 0f, -0.4f, 0.4f, 0.8f, 0.4f, T_CRATE),
            new Box(-0.25f, 0f, -0.25f, 0.25f, 0.5f, 0.25f, T_SKIN),
            new Box(-0.25f, 0f, -0.125f, 0.25f, 0.75f, 0.125f, T_SHIRT),
            new Box(-0.125f, -0.625f, -0.125f, 0.125f, 0.125f, 0.125f, T_SKIN),
            new Box(-0.125f, -0.625f, -0.125f, 0.125f, 0.125f, 0.125f, T_SKIN),
            new Box(-0.125f, -0.75f, -0.125f, 0.125f, 0f, 0.125f, T_PANTS),
            new Box(-0.125f, -0.75f, -0.125f, 0.125f, 0f, 0.125f, T_PANTS)};
    /** Pivot of each mob part in the mob's model space (feet at the origin, facing +z). */
    static final float[][] PIVOT = {{0, 0, 0}, {0, 1.5f, 0}, {0, 0.75f, 0}, {-0.375f, 1.375f, 0}, {0.375f, 1.375f, 0}, {-0.125f, 0.75f, 0}, {0.125f, 0.75f, 0}};

    final ByteBuffer[] mesh = new ByteBuffer[PARTS];
    final int[] quads = new int[PARTS];

    /** One posed part instance: part model, current and previous 3x4 (row-major), TLAS mask, entity id. */
    record Inst(int part, float[] cur, float[] prev, int mask, int entity) {}

    /** A walk: start, end (world, feet), frames to walk it, plus whether a crate slides along {@code crate0 -> crate1}. */
    record Walk(float[] from, float[] to, int frames, float[] crate0, float[] crate1) {}

    RtEntities() {
        for (int p = 0; p < PARTS; p++) {
            Box b = BOXES[p];
            ByteBuffer out = ByteBuffer.allocate(6 * 4 * 16).order(ByteOrder.LITTLE_ENDIAN);
            for (int f = 0; f < 6; f++) RtScene.putQuad(out, RtScene.faceCorners(f, b.x0, b.y0, b.z0, b.x1, b.y1, b.z1), b.tile, 0xFFFFFFFF, 0xF0F0);
            out.flip();
            mesh[p] = out;
            quads[p] = 6;
        }
    }

    static float[] rows(Matrix4f m) {
        return new float[]{m.m00(), m.m10(), m.m20(), m.m30(), m.m01(), m.m11(), m.m21(), m.m31(), m.m02(), m.m12(), m.m22(), m.m32()};
    }

    /** Mob part poses at walk parameter {@code s} in [0, 1] (position along the path, limb phase from distance walked). */
    static float[][] mobPose(Walk w, float s) {
        float dx = w.to[0] - w.from[0], dz = w.to[2] - w.from[2];
        float x = w.from[0] + dx * s, y = w.from[1] + (w.to[1] - w.from[1]) * s, z = w.from[2] + dz * s;
        float yaw = (float) Math.atan2(dx, dz);
        float dist = (float) Math.hypot(dx, dz) * s;
        float swing = (float) Math.sin(dist * 2.6f) * 0.7f;
        float[] rot = {0, (float) Math.sin(dist * 1.3f) * 0.15f, 0, -swing, swing, swing, -swing};
        float[][] out = new float[PARTS][];
        for (int p = HEAD; p < PARTS; p++) {
            Matrix4f m = new Matrix4f().translate(x, y, z).rotateY(yaw).translate(PIVOT[p][0], PIVOT[p][1], PIVOT[p][2]);
            if (p == HEAD) m.rotateY(rot[p]);
            else m.rotateX(rot[p]);
            out[p] = rows(m);
        }
        return out;
    }

    static float[] cratePose(Walk w, float s) {
        float x = w.crate0[0] + (w.crate1[0] - w.crate0[0]) * s, y = w.crate0[1] + (w.crate1[1] - w.crate0[1]) * s, z = w.crate0[2] + (w.crate1[2] - w.crate0[2]) * s;
        return rows(new Matrix4f().translate(x, y, z).rotateY(s * 1.2f));
    }

    /**
     * Instances of frame {@code k} (previous = frame {@code kPrev}, or the current pose when {@code objectMotion} is off:
     * camera-only motion vectors, the "before" case). Mask: SOLID|ENTITY.
     */
    static List<Inst> frame(Walk w, int k, int kPrev, boolean objectMotion) {
        float s = Math.min(1f, k / (float) Math.max(1, w.frames - 1)), sp = Math.min(1f, kPrev / (float) Math.max(1, w.frames - 1));
        if (!objectMotion) sp = s;
        List<Inst> l = new ArrayList<>();
        int mask = RtAccel.MASK_SOLID | RtAccel.MASK_ENTITY;
        float[][] cur = mobPose(w, s), prev = mobPose(w, sp);
        for (int p = HEAD; p < PARTS; p++) l.add(new Inst(p, cur[p], prev[p], mask, 1));
        if (w.crate0 != null) l.add(new Inst(CRATE, cratePose(w, s), cratePose(w, sp), mask, 2));
        return l;
    }

    /** World-space triangles of the posed instances, decoded from the SAME quantised 16-byte part meshes (CPU reference). */
    RtScene.Tris triangles(List<Inst> insts) {
        int total = 0;
        for (Inst in : insts) total += quads[in.part] * 2;
        RtScene.Tris t = new RtScene.Tris();
        t.p = new float[total * 9];
        t.uv = new float[total * 6];
        t.tile = new int[total];
        t.tint = new int[total];
        t.translucent = new boolean[total];
        int k = 0;
        int[][] tri = {{0, 1, 2}, {2, 3, 0}};
        for (Inst in : insts) {
            ByteBuffer b = mesh[in.part];
            float[] m = in.cur;
            for (int q = 0; q < quads[in.part]; q++)
                for (int[] tr : tri) {
                    for (int c = 0; c < 3; c++) {
                        int o = (q * 4 + tr[c]) * 16;
                        double lx = RtScene.decodePos(b.getShort(o)), ly = RtScene.decodePos(b.getShort(o + 2)), lz = RtScene.decodePos(b.getShort(o + 4));
                        for (int a = 0; a < 3; a++) t.p[k * 9 + c * 3 + a] = (float) (m[a * 4] * lx + m[a * 4 + 1] * ly + m[a * 4 + 2] * lz + m[a * 4 + 3]);
                        t.uv[k * 6 + c * 2] = (b.getShort(o + 8) & 0xFFFF) / 32768f;
                        t.uv[k * 6 + c * 2 + 1] = (b.getShort(o + 10) & 0xFFFF) / 32768f;
                    }
                    t.tile[k] = RtScene.tileOf(t.uv, k);
                    t.tint[k] = b.getInt(q * 64 + 12);
                    k++;
                }
        }
        t.n = k;
        return t;
    }

    /** The atlas tiles of the entity textures (written into the shared test atlas; unused by terrain). */
    static void paint(int[] atlas) {
        for (int t = T_SKIN; t <= T_CRATE; t++)
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++) {
                    float n = ((RtScene.hash(x, y, t) & 0xFFFF) / 65535f) - 0.5f, r, g, b;
                    switch (t) {
                        case T_SKIN -> { r = 0.36f + n * 0.08f; g = 0.55f + n * 0.08f; b = 0.32f; }
                        case T_SHIRT -> { r = 0.15f; g = 0.55f + n * 0.06f; b = 0.62f + n * 0.06f; }
                        case T_PANTS -> { r = 0.26f; g = 0.22f + n * 0.04f; b = 0.55f + n * 0.06f; }
                        default -> { float c = (x == 0 || y == 0 || x == 15 || y == 15 || x == y || x == 15 - y) ? 0.65f : 1f; r = (0.72f + n * 0.08f) * c; g = (0.52f + n * 0.06f) * c; b = 0.30f * c; }
                    }
                    atlas[((t / RtScene.GRID) * 16 + y) * RtScene.ATLAS + (t % RtScene.GRID) * 16 + x] = RtScene.rgba(r, g, b, 1);
                }
    }
}
