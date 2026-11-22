package dev.elemore.iroh.rt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P5-3: which entities enter the TLAS this frame (Minecraft-free; the game adapter feeds it, rtSeamTest and rtEntities test it).
 * <p>
 * Design (docs/P5-DESIGN.md section 14): a vanilla entity model is rigid ModelParts posed by one matrix each, so every
 * (model, part) gets ONE small BLAS built once and each frame each admitted entity adds one TLAS instance per part with its
 * full 3x4 pose. Renderers that emit free-form vertices (true deformers) go to a capped REFIT set instead (one BLAS per
 * entity with ALLOW_UPDATE, refitted per frame, rebuilt every {@code refitRebuildEvery} frames). Everything not admitted is
 * raster-only: it receives RT light in the G-buffer but casts no RT shadow and is absent from reflections and GI.
 * <p>
 * Rule: candidates within {@code radius} blocks, nearest first, until {@code maxEntities} entities or {@code maxParts} TLAS
 * part instances; free-form ones also need a refit slot ({@code maxRefit}). Hysteresis: an entity admitted last frame
 * competes at 85% of its distance, so a mob walking along the radius or the part cap does not make its shadow blink.
 */
public final class RtEntityBudget {
    /** One entity this frame: stable id, distance to the camera (blocks), rigid part count, rigid (true) or free-form. */
    public record Candidate(int id, double distance, int parts, boolean rigid) {}

    /** Start values (measured cost basis in knowledge/rt-iroh.md "P5-3 entities"; RADV numbers UNVERIFIED). */
    public record Limits(double radius, int maxEntities, int maxParts, int maxRefit, int refitRebuildEvery) {
        public static final Limits DEFAULT = new Limits(48, 64, 512, 32, 30);
    }

    public static final class Selection {
        public final List<Candidate> rigid = new ArrayList<>(), refit = new ArrayList<>();
        public int parts, excludedByRadius, excludedByCap;

        /** TLAS instances this selection adds (rigid parts + one per refit entity). */
        public int instances() {
            return parts + refit.size();
        }
    }

    /** Bytes per admitted rigid part per frame: one TLAS instance (64 B) + the motion record (current + previous 3x4 = 96 B). */
    public static final int BYTES_PER_PART_FRAME = 64 + 96;
    public static final double HYSTERESIS = 0.85;

    private final Limits limits;
    private Set<Integer> admitted = new HashSet<>();

    public RtEntityBudget(Limits limits) {
        this.limits = limits;
    }

    public Selection select(List<Candidate> candidates) {
        Candidate[] c = candidates.toArray(new Candidate[0]);
        Set<Integer> prev = admitted;
        Arrays.sort(c, (a, b) -> Double.compare(key(a, prev), key(b, prev)));
        Selection s = new Selection();
        Set<Integer> now = new HashSet<>();
        for (Candidate e : c) {
            if (key(e, prev) > limits.radius) { s.excludedByRadius++; continue; }
            if (now.size() >= limits.maxEntities) { s.excludedByCap++; continue; }
            if (e.rigid) {
                if (s.parts + e.parts > limits.maxParts) { s.excludedByCap++; continue; }
                s.rigid.add(e);
                s.parts += e.parts;
            } else {
                if (s.refit.size() >= limits.maxRefit) { s.excludedByCap++; continue; }
                s.refit.add(e);
            }
            now.add(e.id);
        }
        admitted = now;
        return s;
    }

    private static double key(Candidate e, Set<Integer> prev) {
        return prev.contains(e.id) ? e.distance * HYSTERESIS : e.distance;
    }

    /** Free-form entity BLAS: refit this frame, or rebuild (age multiple of refitRebuildEvery, first frame included). */
    public boolean rebuildRefit(int ageFrames) {
        return ageFrames % limits.refitRebuildEvery == 0;
    }

    /**
     * Previous-frame part poses per entity, for motion vectors of the entity's own pixels (the G-buffer projects each
     * vertex through the current AND the previous pose). An entity seen for the first time gets prev = cur (no motion:
     * the denoiser and FSR treat it as a disocclusion anyway); entities gone for a frame are forgotten.
     */
    public static final class Motion {
        private Map<Integer, float[][]> last = new HashMap<>(), next = new HashMap<>();

        /** Returns the previous poses (or {@code cur} itself) and records {@code cur} for the next frame. */
        public float[][] previous(int id, float[][] cur) {
            float[][] p = last.get(id);
            next.put(id, cur);
            return p != null && p.length == cur.length ? p : cur;
        }

        /** End of frame: entities not seen this frame are dropped. */
        public void endFrame() {
            last = next;
            next = new HashMap<>();
        }

        public int tracked() {
            return last.size();
        }
    }
}
