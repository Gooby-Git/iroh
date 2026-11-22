package dev.elemore.iroh.temporal;

import org.joml.Matrix4d;
import org.joml.Matrix4dc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Card P4-1b: per-object motion for the velocity pass, "only where something moved". Pure (no game, no Vulkan):
 * TemporalRuntime feeds it the rendered entities each frame ({@link #submit}: key = entity id and part, interpolated
 * position, body yaw, world box), and {@link #build} packs at most {@link VelocityPass#MAX_OBJECTS} boxes, nearest first,
 * of objects that MOVED since the previous frame (rigid model: previous position + yaw change about the vertical axis
 * through the position) into the velocity pass's object list. Objects that did not move are left out (camera-only
 * reprojection is exact for them). An object seen for the first time (no history: spawned, entered render distance)
 * gets camera-only motion and the "new" T&C value (fallback). History entries not seen for 2 frames are dropped.
 * <p>Rigid motion covers walking/flying mobs, players in third person, item entities, falling blocks, minecarts, boats,
 * arrows and other projectiles; limb/head animation and block-entity animation (chest lids, pistons) are NOT motion
 * here: they get the object T&C value (limbs) or nothing (block entities: future work).
 * <p>Matrix: a pixel's current camera-relative position {@code rel} maps to the previous clip position by
 * {@code toPrevClip = PR_prev * T(prevPos - camPrev) * Ry(yawCur - yawPrev) * T(camCur - curPos)} (Minecraft renders
 * living entities with {@code Ry(180 - bodyYaw)}, so prevLocal = Ry(bodyCur - bodyPrev) * curLocal).
 */
public final class ObjectMotion {
    /** move thresholds below which an object counts as static */
    public static final double EPS_POS = 1e-4, EPS_YAW_DEG = 0.01;
    /** boxes are inflated so limbs, held items and the interpolation slop stay inside */
    public static final double INFLATE = 0.15;

    private record State(double x, double y, double z, double yaw, long frame) {}

    private record Cand(long key, double x, double y, double z, double yaw, double[] lo, double[] hi, State prev) {}

    private final Map<Long, State> history = new HashMap<>();
    private final List<Cand> cands = new ArrayList<>();
    private long frame;

    public float tcMoving = 0.3f, tcNew = 0.5f;

    public void beginFrame(long frameIndex) {
        this.frame = frameIndex;
        cands.clear();
    }

    /** One rendered object this frame. {@code lo/hi}: world-space box (entity bounding box at the interpolated position). */
    public void submit(long key, double x, double y, double z, double yawDeg, double[] lo, double[] hi) {
        State prev = history.get(key);
        if (prev != null && prev.frame != frame - 1)
            prev = null; // not seen last frame: no usable history
        history.put(key, new State(x, y, z, yawDeg, frame));
        cands.add(new Cand(key, x, y, z, yawDeg, lo, hi, prev));
    }

    /**
     * Packs the objects into {@code f.objects}/{@code f.objectCount}. Without a previous camera ({@code prevPR == null})
     * nothing is packed. Also evicts stale history.
     */
    public void build(double[] camCur, double[] camPrev, Matrix4dc prevPR, VelocityPass.Frame f) {
        history.values().removeIf(s -> s.frame < frame - 1);
        f.objectCount = 0;
        if (prevPR == null || cands.isEmpty())
            return;
        cands.sort((a, b) -> Double.compare(dist2(a, camCur), dist2(b, camCur)));
        if (f.objects.length < VelocityPass.MAX_OBJECTS * VelocityPass.OBJECT_FLOATS)
            f.objects = new float[VelocityPass.MAX_OBJECTS * VelocityPass.OBJECT_FLOATS];
        Matrix4d m = new Matrix4d(), camOnly = new Matrix4d(prevPR).translate(camCur[0] - camPrev[0], camCur[1] - camPrev[1], camCur[2] - camPrev[2]);
        int n = 0;
        for (Cand c : cands) {
            if (n >= VelocityPass.MAX_OBJECTS)
                break;
            float tc;
            if (c.prev == null) {
                m.set(camOnly);
                tc = tcNew;
            } else {
                double dx = c.x - c.prev.x, dy = c.y - c.prev.y, dz = c.z - c.prev.z, dyaw = c.yaw - c.prev.yaw;
                if (Math.abs(dx) < EPS_POS && Math.abs(dy) < EPS_POS && Math.abs(dz) < EPS_POS && Math.abs(dyaw) < EPS_YAW_DEG)
                    continue; // static: the camera reprojection is exact
                toPrevClip(prevPR, camCur, camPrev, c.prev.x, c.prev.y, c.prev.z, c.prev.yaw, c.x, c.y, c.z, c.yaw, m);
                tc = tcMoving;
            }
            int o = n * VelocityPass.OBJECT_FLOATS;
            for (int a = 0; a < 3; a++) {
                f.objects[o + a] = (float) (c.lo[a] - camCur[a] - INFLATE);
                f.objects[o + 4 + a] = (float) (c.hi[a] - camCur[a] + INFLATE);
            }
            f.objects[o + 3] = tc;
            f.objects[o + 7] = 0f;
            for (int col = 0; col < 4; col++)
                for (int row = 0; row < 4; row++)
                    f.objects[o + 8 + col * 4 + row] = (float) m.get(col, row);
            n++;
        }
        f.objectCount = n;
    }

    /** {@code PR_prev * T(prevPos - camPrev) * Ry(yawCur - yawPrev) * T(camCur - curPos)} */
    public static Matrix4d toPrevClip(Matrix4dc prevPR, double[] camCur, double[] camPrev,
                                      double px, double py, double pz, double pyaw,
                                      double cx, double cy, double cz, double cyaw, Matrix4d dst) {
        return dst.set(prevPR)
                .translate(px - camPrev[0], py - camPrev[1], pz - camPrev[2])
                .rotateY(Math.toRadians(cyaw - pyaw))
                .translate(camCur[0] - cx, camCur[1] - cy, camCur[2] - cz);
    }

    private static double dist2(Cand c, double[] cam) {
        double dx = c.x - cam[0], dy = c.y - cam[1], dz = c.z - cam[2];
        return dx * dx + dy * dy + dz * dz;
    }

    public int historySize() {
        return history.size();
    }
}
