package dev.elemore.iroh.config;

/**
 * Iroh (rc14, td-f57b86): what the "distance fog off" option ({@code fogDistanceChunks = -1}, migrated from the pack's
 * Sodium Extra {@code fog_settings.atmospheric.distance_chunks = -1}) may change, as a pure function so it can be
 * tested without the game.
 * <p>
 * Reference = the OpenGL pack (Sodium Extra 0.9.3 MixinFogRenderer, read with javap): at the end of
 * {@code FogRenderer.setupFog} it removes the fog ONLY when the camera is in no fluid, no mob-effect fog function is
 * active (blindness, darkness), the boss bar does not ask for world fog ({@code BossHealthOverlay.shouldCreateWorldFog},
 * the Ender Dragon / Wither fight), and the mode is TERRAIN. The sky fog, cloud fog and every special fog stay vanilla.
 * The thick fog of a foggy dimension ({@code DimensionSpecialEffects.isFoggyAt}: the Nether) IS removed there when no
 * boss asks for fog; Iroh matches that.
 * <p>
 * Iroh adds one guard the GL pack only has by mixin order: the fog is removed only while it still holds vanilla's own
 * values for that distance; a mod that set its own fog earlier in setupFog (biome fog, custom fluids) keeps it.
 * Mods injecting later than Iroh (Enderscape, priority 2000) run after this anyway.
 * <p>
 * {@link #vanilla} writes out vanilla 1.21.1 FogRenderer.setupFog (decision order and formulae) for the test.
 */
public final class FogDecision {
    public enum Mode { SKY, TERRAIN }
    public enum Fluid { NONE, WATER, LAVA, POWDER_SNOW }
    public enum Effect { NONE, BLINDNESS, DARKNESS }
    /** Same order as com.mojang.blaze3d.shaders.FogShape (index 0 sphere, 1 cylinder). */
    public enum Shape { SPHERE, CYLINDER }

    public record Fog(float start, float end, Shape shape) {}

    /** "No fog": what Sodium Extra and Iroh write (Float.MAX_VALUE for start and end). */
    public static final float OFF = Float.MAX_VALUE;

    private FogDecision() {}

    /** Inputs vanilla's setupFog reads. {@code effectValue}: blindness = remaining duration in ticks (-1 infinite), darkness = blend factor. */
    public record Camera(Mode mode, Fluid fluid, boolean spectator, boolean fireResistance, Effect effect, float effectValue,
                         float distance, boolean thickFog, float waterVision, boolean closerWaterFog) {}

    /** Vanilla 1.21.1 FogRenderer.setupFog: start, end, shape. */
    public static Fog vanilla(Camera c) {
        float d = c.distance;
        if (c.fluid == Fluid.LAVA) {
            if (c.spectator) return new Fog(-8.0F, d * 0.5F, Shape.SPHERE);
            if (c.fireResistance) return new Fog(0.0F, 5.0F, Shape.SPHERE);
            return new Fog(0.25F, 1.0F, Shape.SPHERE);
        }
        if (c.fluid == Fluid.POWDER_SNOW) {
            if (c.spectator) return new Fog(-8.0F, d * 0.5F, Shape.SPHERE);
            return new Fog(0.0F, 2.0F, Shape.SPHERE);
        }
        if (c.effect == Effect.BLINDNESS) {
            float f = c.effectValue < 0 ? 5.0F : lerp(Math.min(1.0F, c.effectValue / 20.0F), d, 5.0F);
            return c.mode == Mode.SKY ? new Fog(0.0F, f * 0.8F, Shape.SPHERE) : new Fog(f * 0.25F, f, Shape.SPHERE);
        }
        if (c.effect == Effect.DARKNESS) {
            float f = lerp(c.effectValue, d, 15.0F);
            return new Fog(c.mode == Mode.SKY ? 0.0F : f * 0.75F, f, Shape.SPHERE);
        }
        if (c.fluid == Fluid.WATER) {
            float end = 96.0F * Math.max(0.25F, c.waterVision);
            if (c.closerWaterFog) end *= 0.85F;
            if (end > d) return new Fog(-8.0F, d, Shape.CYLINDER);
            return new Fog(-8.0F, end, Shape.SPHERE);
        }
        if (c.thickFog) return new Fog(d * 0.05F, Math.min(d, 192.0F) * 0.5F, Shape.SPHERE);
        if (c.mode == Mode.SKY) return new Fog(0.0F, d, Shape.CYLINDER);
        float margin = clamp(d / 10.0F, 4.0F, 64.0F);
        return new Fog(d - margin, d, Shape.CYLINDER);
    }

    /**
     * True when the option removes the fog that is currently set ({@code current}, after vanilla and earlier mods).
     * {@code bossWorldFog}: the boss bar asks for world fog. {@code thickFog} is setupFog's own argument
     * (isFoggyAt || bossWorldFog).
     */
    public static boolean removesFog(int option, Mode mode, Fluid fluid, Effect effect, boolean bossWorldFog,
                                     float distance, boolean thickFog, Fog current) {
        if (option != -1) return false;
        if (mode != Mode.TERRAIN || fluid != Fluid.NONE || effect != Effect.NONE || bossWorldFog) return false;
        // only vanilla's own distance fog (atmospheric, or the dimension's thick fog), not a mod's
        Fog v = vanilla(new Camera(Mode.TERRAIN, Fluid.NONE, false, false, Effect.NONE, 0, distance, thickFog, 0, false));
        return close(current.start, v.start) && close(current.end, v.end) && current.shape == v.shape;
    }

    /** What the option leaves: OFF/OFF when it removes the fog, else {@code current} unchanged. */
    public static Fog apply(int option, Mode mode, Fluid fluid, Effect effect, boolean bossWorldFog, float distance,
                            boolean thickFog, Fog current) {
        return removesFog(option, mode, fluid, effect, bossWorldFog, distance, thickFog, current)
                ? new Fog(OFF, OFF, current.shape) : current;
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) <= 1e-3F * Math.max(1.0F, Math.abs(b));
    }

    static float lerp(float delta, float a, float b) {
        return a + delta * (b - a);
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
