package dev.elemore.iroh.upscale;

/**
 * FSR-Q7: the Voxel-Aware reactive / transparency-and-composition mask. Class by WHICH draw phase wrote the pixel's
 * depth, read from three depth snapshots the renderer already takes or now takes once more:
 * <pre>
 *   opaque copy (AFTER_ENTITIES)  od      terrain solid / cutout, entities, block entities (+ lava, fire: solid layer)
 *   phase copy  (AFTER_TRANSLUCENT) ph    + translucent terrain (water, stained glass, ice), tripwire, particles
 *   scene depth (before the hand)  sd     + clouds, weather
 * </pre>
 * band A  sd == ph &lt; od   translucent terrain, tripwire, particles: reactive = min(maxA, scaleA * colourDiff), T&amp;C tcA
 * band B  sd &lt; ph &lt; od  clouds, weather:                        reactive = min(maxB, scaleB * colourDiff), T&amp;C tcB
 * other   sd == od         a draw after the copy that wrote no depth (outline, GUI-in-world): reactive = scaleOther * diff
 * Without the phase copy every pixel in front of the opaque depth is band A. This class is the CPU twin of
 * {@code temporal/velocity.comp} (F_VOXEL): the harness (FsrMetrics) uses it to build masks. NOT covered (needs a per-fragment
 * source, see knowledge/fsr.md G.9): animated atlas sprites in the opaque pass (lava, fire) and foliage in the terrain pass.
 */
public final class VoxelMask {
    private VoxelMask() {}

    public enum Cls { OTHER, BAND_A, BAND_B }

    /** The class values; DEFAULT is the data-picked set (knowledge/fsr.md G.9). */
    public record Params(float scaleA, float maxA, float tcA, float scaleB, float maxB, float tcB, float scaleOther) {
        public static final Params DEFAULT = new Params(2f, 0.9f, 0.8f, 8f, 0.9f, 0f, 2f);
    }

    /** depths are 0..1 standard Z (smaller = nearer); ph NaN = no phase copy */
    public static Cls classify(float od, float ph, float sd) {
        boolean front = sd < od - 1e-7f;
        if (!front)
            return Cls.OTHER;
        if (!Float.isNaN(ph) && sd < ph - 1e-7f)
            return Cls.BAND_B;
        return Cls.BAND_A;
    }

    public static float reactive(Params p, Cls c, float diff) {
        return switch (c) {
            case BAND_A -> Math.min(p.maxA(), p.scaleA() * diff);
            case BAND_B -> Math.min(p.maxB(), p.scaleB() * diff);
            case OTHER -> Math.min(0.9f, p.scaleOther() * diff);
        };
    }

    public static float tc(Params p, Cls c) {
        return switch (c) {
            case BAND_A -> p.tcA();
            case BAND_B -> p.tcB();
            case OTHER -> 0f;
        };
    }
}
