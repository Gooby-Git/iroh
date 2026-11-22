package dev.elemore.iroh.rt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P5-1: the vanilla material rules of the RT look, Minecraft-free (keys are block registry paths such as
 * {@code "minecraft:torch"}; the game adapter feeds {@code BuiltInRegistries.BLOCK.getKey}). Used by the offline scenes and,
 * in game, by the per-sprite material table (docs/P5-DESIGN.md section 8).
 * <p>
 * Emission: the vanilla LIGHT LEVEL (game behaviour, public) sets the power; the COLOUR is Iroh's own choice per block
 * family (not Complementary's table). Radiance of an emissive surface = colour x {@link #EMISSIVE_RADIANCE_AT_15} x
 * (level / 15)^2 x (6 / emissive area of the model, capped): a torch (small model) then delivers about the power of a
 * full emissive cube of the same light level instead of a tiny fraction of it. Blocks that emit but are not listed get
 * {@link #DEFAULT_LIGHT_COLOUR} (modded light blocks glow with no data).
 * <p>
 * Roughness: everything is Lambert (roughness 1, F0 0.04, no specular ray) unless a smooth rule matches. Specular rays
 * are gated by the preset's roughness limit. Rain wetness is applied in the shaders (rt_look.glsl), not here.
 */
public final class RtMaterialRules {
    private RtMaterialRules() {}

    /** Emission: linear colour (max channel 1) and the vanilla light level. */
    public record Emission(float r, float g, float b, int level) {}

    /** Surface: roughness (GGX alpha = r^2), F0, metal (F0 from albedo), water, transmissive (glass). */
    public record Surface(float roughness, float f0, boolean metal, boolean water, boolean glass) {
        public boolean specular(float roughnessLimit) {
            return water || roughness <= roughnessLimit;
        }
    }

    /** Radiance of an emissive cube face at light level 15 (scene-linear units of the RT core; sun irradiance ~3). */
    public static final float EMISSIVE_RADIANCE_AT_15 = 8.0f;
    public static final float MAX_SMALL_MODEL_BOOST = 40f;
    public static final Emission DEFAULT_LIGHT_COLOUR = new Emission(1.00f, 0.78f, 0.55f, 0);
    public static final Surface LAMBERT = new Surface(1f, 0.04f, false, false, false);

    static final Map<String, Emission> EMISSION = new LinkedHashMap<>();

    static void e(float r, float g, float b, int level, String... ids) {
        for (String id : ids) EMISSION.put("minecraft:" + id, new Emission(r, g, b, level));
    }

    static {
        // warm flame family
        e(1.00f, 0.62f, 0.30f, 14, "torch", "wall_torch");
        e(1.00f, 0.66f, 0.36f, 15, "lantern", "campfire", "fire", "jack_o_lantern");
        e(1.00f, 0.55f, 0.22f, 13, "furnace", "blast_furnace", "smoker");      // lit state only (adapter checks LIT)
        e(1.00f, 0.66f, 0.38f, 3, "candle", "white_candle", "orange_candle", "candle_cake");   // per lit candle, x count
        // lava / magma
        e(1.00f, 0.42f, 0.10f, 15, "lava");
        e(1.00f, 0.38f, 0.08f, 3, "magma_block");
        // soul family
        e(0.35f, 0.85f, 1.00f, 10, "soul_torch", "soul_wall_torch", "soul_lantern", "soul_campfire", "soul_fire");
        // glowstone / shroomlight / lamps
        e(1.00f, 0.80f, 0.52f, 15, "glowstone");
        e(1.00f, 0.60f, 0.32f, 15, "shroomlight");
        e(1.00f, 0.74f, 0.44f, 15, "redstone_lamp");                            // lit state only
        e(0.80f, 0.95f, 1.00f, 15, "sea_lantern", "beacon", "conduit");
        e(1.00f, 0.92f, 0.60f, 15, "ochre_froglight");
        e(0.75f, 1.00f, 0.70f, 15, "verdant_froglight");
        e(0.95f, 0.70f, 1.00f, 15, "pearlescent_froglight");
        e(0.95f, 0.92f, 1.00f, 14, "end_rod");
        // redstone
        e(1.00f, 0.12f, 0.06f, 7, "redstone_torch", "redstone_wall_torch");
        e(1.00f, 0.15f, 0.08f, 9, "redstone_ore", "deepslate_redstone_ore");  // lit state only
        // purple / magic
        e(0.62f, 0.25f, 1.00f, 11, "nether_portal");
        e(0.55f, 0.20f, 1.00f, 10, "crying_obsidian");
        e(0.62f, 0.25f, 1.00f, 15, "respawn_anchor");                           // charged
        e(0.78f, 0.55f, 1.00f, 5, "amethyst_cluster");
        e(0.78f, 0.55f, 1.00f, 4, "large_amethyst_bud");
        e(0.60f, 0.40f, 1.00f, 7, "enchanting_table", "ender_chest");
        // greens / cyans
        e(0.70f, 1.00f, 0.85f, 7, "glow_lichen");
        e(0.30f, 0.90f, 0.85f, 6, "sculk_catalyst");
        e(0.55f, 1.00f, 0.80f, 1, "sculk_sensor", "calibrated_sculk_sensor");
        e(1.00f, 0.80f, 0.40f, 1, "brewing_stand", "dragon_egg");
        e(0.60f, 1.00f, 0.55f, 10, "sea_pickle");                               // 4 pickles, wet
        e(0.95f, 0.55f, 0.30f, 15, "cave_vines", "cave_vines_plant");          // with berries
    }

    /** Emission of a block id, or null; {@code engineLevel} > 0 = the game says it emits (unlisted -> default colour). */
    public static Emission emission(String id, int engineLevel) {
        Emission e = EMISSION.get(id);
        if (e != null) return engineLevel > 0 && engineLevel != e.level() ? new Emission(e.r(), e.g(), e.b(), engineLevel) : e;
        if (engineLevel > 0) return new Emission(DEFAULT_LIGHT_COLOUR.r(), DEFAULT_LIGHT_COLOUR.g(), DEFAULT_LIGHT_COLOUR.b(), engineLevel);
        return null;
    }

    /** Radiance (rgb) of the emissive quads of a model whose emissive faces cover {@code emissiveArea} square blocks. */
    public static float[] radiance(Emission e, float emissiveArea) {
        float l = e.level() / 15f;
        float boost = Math.min(MAX_SMALL_MODEL_BOOST, Math.max(1f, 6f / Math.max(emissiveArea, 1e-3f)));
        float k = EMISSIVE_RADIANCE_AT_15 * l * l * boost;
        return new float[]{e.r() * k, e.g() * k, e.b() * k};
    }

    public static int listedEmitters() {
        return EMISSION.size();
    }

    /** Smooth-block rules by registry path (first match wins). */
    public static Surface surface(String id) {
        String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (p.equals("water") || p.equals("bubble_column")) return new Surface(0f, 0.02f, false, true, false);
        if (p.contains("glass")) return new Surface(0.05f, 0.04f, false, false, true);
        if (p.equals("ice") || p.equals("packed_ice") || p.equals("blue_ice")) return new Surface(0.08f, 0.02f, false, false, false);
        if (p.equals("iron_block") || p.equals("gold_block") || p.equals("netherite_block") || p.equals("copper_block") || p.equals("cut_copper")
                || p.startsWith("waxed_copper") || p.equals("iron_bars") || p.equals("chain") || p.contains("anvil"))
            return new Surface(0.30f, 0.56f, true, false, false);
        if (p.endsWith("glazed_terracotta")) return new Surface(0.25f, 0.04f, false, false, false);
        if (p.startsWith("polished_") || p.equals("quartz_block") || p.startsWith("smooth_quartz") || p.equals("obsidian") || p.equals("crying_obsidian")
                || p.startsWith("quartz_") || p.equals("calcite") || p.contains("prismarine_bricks"))
            return new Surface(0.30f, 0.04f, false, false, false);
        if (p.startsWith("smooth_") || p.endsWith("_concrete") || p.equals("purpur_block") || p.equals("end_stone_bricks"))
            return new Surface(0.38f, 0.04f, false, false, false);
        return LAMBERT;
    }
}
