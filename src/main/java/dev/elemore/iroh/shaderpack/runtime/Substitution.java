package dev.elemore.iroh.shaderpack.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P3-10 (S) program substitution, decision layer (pure, tested offline): which pack geometry class a world-pass draw
 * belongs to (by the core shader of the bound pipeline, then by its vertex format), which vanilla layout its vertex
 * format maps to, what stays raster, and the permutation key of the pack pipeline variant.
 * <p>In game this runs in OBSERVE mode for now (no rendering change): {@link #observe} counts what the world pass
 * would substitute, published as IrohDebug {@code iroh.pack.substitution}; binding the pack pipelines needs the MRT
 * world pass (next step, iris-loader.md section 13).
 */
public final class Substitution {

    private Substitution() {}

    /** core shader name (vanilla "rendertype_*" names, mod "ns:name") -> geometry class; null = not a world draw we map */
    public static String classFor(String shader, List<String> elements) {
        String s = shader == null ? "" : shader;
        int colon = s.indexOf(':');
        String base = colon >= 0 ? s.substring(colon + 1) : s;
        switch (base) {
            case "rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped", "rendertype_translucent", "rendertype_tripwire",
                 "terrain", "terrain_early_z" -> {
                return null; // terrain: section lists (P3-6), never substituted per draw
            }
            case "position", "position_color" -> {
                return "sky_basic"; // sky disc, stars, sunrise fan (in the world pass)
            }
            case "position_tex", "position_tex_color", "rendertype_end_portal", "rendertype_end_gateway" -> {
                return "sky_textured";
            }
            case "rendertype_entity_solid", "rendertype_entity_cutout", "rendertype_entity_cutout_no_cull",
                 "rendertype_entity_cutout_no_cull_z_offset", "rendertype_entity_smooth_cutout", "rendertype_armor_cutout_no_cull",
                 "rendertype_entity_decal", "rendertype_entity_no_outline", "rendertype_entity_shadow", "rendertype_item_entity_translucent_cull",
                 "rendertype_entity_alpha" -> {
                return "entities";
            }
            case "rendertype_entity_translucent", "rendertype_entity_translucent_cull", "rendertype_entity_translucent_emissive" -> {
                return "entities_translucent";
            }
            case "rendertype_eyes" -> {
                return "spider_eyes";
            }
            case "rendertype_armor_glint", "rendertype_armor_entity_glint", "rendertype_glint", "rendertype_glint_translucent",
                 "rendertype_entity_glint", "rendertype_entity_glint_direct" -> {
                return "armor_glint";
            }
            case "rendertype_lines", "rendertype_line_strip" -> {
                return "outline";
            }
            case "rendertype_crumbling" -> {
                return "damaged_block";
            }
            case "rendertype_beacon_beam" -> {
                return "beacon_beam";
            }
            case "rendertype_lightning" -> {
                return "lightning";
            }
            case "particle" -> {
                return "particles_opaque";
            }
            case "rendertype_clouds", "clouds" -> {
                return "clouds";
            }
            case "weather" -> { // "iroh:weather": the particle shader inside renderSnowAndRain (P3-11)
                return "weather";
            }
            case "rendertype_world_border" -> {
                return "world_border";
            }
            case "rendertype_energy_swirl", "rendertype_leash", "rendertype_water_mask" -> {
                return "entities";
            }
            default -> {
            }
        }
        // unknown (mod) shader: nearest by vertex format
        if (elements == null)
            return null;
        if (elements.contains("Normal") && elements.contains("UV2"))
            return "entities";
        if (elements.contains("UV2") && elements.contains("UV0"))
            return "particles_opaque"; // textured_lit
        if (elements.contains("UV0"))
            return "world_border"; // gbuffers_textured_lit family (textured, unlit)
        return null;
    }

    /** GUI/HUD/screen draws and the given mod namespaces stay raster */
    public static boolean rasterOnly(String shader, boolean inWorldPass, Set<String> rasterMods) {
        if (!inWorldPass)
            return true;
        String s = shader == null ? "" : shader;
        if (s.startsWith("rendertype_gui") || s.startsWith("rendertype_text") && !inWorldPass || s.equals("blit_screen") || s.startsWith("blit"))
            return true;
        int colon = s.indexOf(':');
        return colon > 0 && rasterMods.contains(s.substring(0, colon));
    }

    /** vanilla layout spec for a vertex format's element names, or null when an element cannot be placed */
    public static String layout(List<String> elements) {
        StringBuilder sb = new StringBuilder("vanilla:");
        for (int i = 0; i < elements.size(); i++) {
            String e = elements.get(i);
            switch (e) {
                case "Position", "Color", "UV0", "UV1", "UV2", "Normal", "Padding" -> {
                }
                default -> {
                    return null;
                }
            }
            sb.append(i == 0 ? "" : ",").append(e);
        }
        return sb.toString();
    }

    /** a pack pipeline variant: program x vertex layout x render state (blend/depth/cull/colour mask packed by Iroh) */
    public record Key(String program, String layout, long state) {}

    // ---- the renderer's ONE volatile read per bind and per draw (P3-11): bit OBS = observe, bit WORLD = world pass live
    public static final int OBS = 1, WORLD = 2;
    public static volatile int HOOK;
    /** observe mode (P3-10), kept as a plain mirror of HOOK bit OBS for readers */
    public static boolean OBSERVE;

    public static synchronized void setBit(int bit, boolean on) {
        HOOK = on ? HOOK | bit : HOOK & ~bit;
        OBSERVE = (HOOK & OBS) != 0;
    }
    private static final Map<String, long[]> SEEN = new ConcurrentHashMap<>();

    /** counts one bind: shader -> class/layout (or raster) */
    public static void observe(String shader, List<String> elements, boolean inWorld, Set<String> rasterMods) {
        String cls = rasterOnly(shader, inWorld, rasterMods) ? "raster" : classFor(shader, elements);
        String lay = elements == null ? "?" : layout(elements);
        SEEN.computeIfAbsent(shader + " -> " + (cls == null ? "unmapped" : cls) + " [" + (lay == null ? "unplaceable" : lay) + "]", k -> new long[1])[0]++;
    }

    public static String report() {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(SEEN).forEach((k, v) -> sb.append(k).append(" x").append(v[0]).append('\n'));
        return sb.toString();
    }

    public static void reset() {
        SEEN.clear();
    }
}
