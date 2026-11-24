package dev.elemore.iroh.shaderpack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The macros Iroh defines for every pack source and for the properties files (OptiFine "Standard Macros",
 * Iris additions). Packs gate whole features on these, so what Iroh reports is a decision, recorded here and in
 * knowledge/iris-loader.md "P3-1 front end":
 * <ul>
 *   <li>{@code MC_VERSION 12101}, {@code MC_GL_VERSION 460}, {@code MC_GLSL_VERSION 460}: what the translated
 *       code really is (GLSL 4.60 semantics through SPIR-V).</li>
 *   <li>{@code IS_IRIS} and {@code IRIS_VERSION 10814}: packs (Complementary) refuse to run or show "install
 *       Iris" overlays without them, and select Iris-only code paths (Iris 1.8.14 is what the OpenGL pack runs).
 *       Iroh behaves as Iris does for every pack-visible contract, so claiming Iris is accurate in effect.</li>
 *   <li>{@code IRIS_FEATURE_*} only for features Iroh will serve (see {@link #SERVED_FEATURES}); a feature
 *       flag defined but not served sends every program down a branch that cannot work.</li>
 *   <li>vendor / renderer / OS from the {@link Env} (the device at run time; the harness takes options).</li>
 *   <li>{@code MC_RENDER_STAGE_*} constants: values in the order of the OptiFine list, NONE = 0.</li>
 *   <li>No {@code DISTANT_HORIZONS}: Iroh does not support DH (DECISIONS #13). {@code VISTA} only when Vista
 *       is present (the {@link Env} says so).</li>
 * </ul>
 */
public final class StandardMacros {

    /** Iris feature flags Iroh serves (P3 plan). Not served: see {@link #KNOWN_FEATURES}. */
    public static final Set<String> SERVED_FEATURES = Set.of("SEPARATE_HARDWARE_SAMPLERS", "PER_BUFFER_BLENDING",
            "COMPUTE_SHADERS", "ENTITY_TRANSLUCENT", "SSBO", "CUSTOM_IMAGES", "HIGHER_SHADOWCOLOR",
            "BLOCK_EMISSION_ATTRIBUTE");

    /**
     * Every flag the public Iris pack-format docs define (I-FLAGS, iris-loader.md section 19), served or not. A required
     * flag outside SERVED_FEATURES is a hard error either way; the split only changes the message and pack.json
     * ("unknown" = no such Iris flag, probably a typo). Not served, deliberately: CAN_DISABLE_WEATHER (weather is drawn
     * by its class program with a fallback walk, a pack cannot switch it off yet; UNVERIFIED what Iris needs here),
     * REVERSED_CULLING, FADE_VARIABLE (no chunk fade), TEXTURE_FILTERING, TESSELLATION_SHADERS (no tess pipeline).
     */
    public static final Set<String> KNOWN_FEATURES = Set.of("BLOCK_EMISSION_ATTRIBUTE", "CAN_DISABLE_WEATHER",
            "COMPUTE_SHADERS", "ENTITY_TRANSLUCENT", "PER_BUFFER_BLENDING", "REVERSED_CULLING", "CUSTOM_IMAGES",
            "HIGHER_SHADOWCOLOR", "SEPARATE_HARDWARE_SAMPLERS", "SSBO", "TESSELLATION_SHADERS", "TESSELATION_SHADERS",
            "FADE_VARIABLE", "TEXTURE_FILTERING");

    /** why a flag is refused: null = served */
    public static String featureProblem(String f, Set<String> served) {
        if (served.contains(f))
            return null;
        return KNOWN_FEATURES.contains(f) ? "an Iris feature flag Iroh does not serve" : "not an Iris feature flag";
    }

    public static final List<String> RENDER_STAGES = List.of("NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON",
            "STARS", "VOID", "TERRAIN_SOLID", "TERRAIN_CUTOUT_MIPPED", "TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES",
            "DESTROY", "OUTLINE", "DEBUG", "HAND_SOLID", "TERRAIN_TRANSLUCENT", "TRIPWIRE", "PARTICLES", "CLOUDS",
            "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT");

    /** GL extensions a 4.60 core driver exposes that packs test with #ifdef; all are core in 4.60 / Vulkan. */
    public static final List<String> EXTENSION_MACROS = List.of("GL_ARB_shader_image_load_store",
            "GL_ARB_shader_storage_buffer_object", "GL_ARB_compute_shader", "GL_ARB_gpu_shader5",
            "GL_ARB_shading_language_packing", "GL_ARB_explicit_attrib_location", "GL_ARB_shader_texture_lod",
            "GL_ARB_texture_gather", "GL_ARB_shader_bit_encoding", "GL_ARB_separate_shader_objects",
            "GL_ARB_conservative_depth", "GL_ARB_derivative_control", "GL_ARB_texture_query_levels",
            "GL_ARB_texture_query_lod", "GL_ARB_shading_language_420pack", "GL_ARB_arrays_of_arrays",
            "GL_ARB_shader_atomic_counters", "GL_ARB_texture_cube_map_array", "GL_ARB_gpu_shader_fp64",
            "GL_ARB_shader_draw_parameters", "GL_ARB_enhanced_layouts", "GL_EXT_gpu_shader4",
            "GL_KHR_shader_subgroup_basic", "GL_KHR_shader_subgroup_vote", "GL_KHR_shader_subgroup_arithmetic",
            "GL_KHR_shader_subgroup_ballot", "GL_KHR_shader_subgroup_shuffle");

    /** What the macros describe. */
    public record Env(String vendor, String renderer, String os, boolean vista, boolean labPbr, Set<String> features) {
        public static Env defaults() {
            return new Env("AMD", "RADEON", "LINUX", true, true, SERVED_FEATURES);
        }
    }

    private StandardMacros() {}

    /** Macros for GLSL sources and for the properties files (Cpp-ready: name -> body). */
    public static Map<String, String> of(Env env) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("MC_VERSION", "12101");
        m.put("MC_GL_VERSION", "460");
        m.put("MC_GLSL_VERSION", "460");
        m.put("MC_OS_" + env.os(), "");
        m.put("MC_GL_VENDOR_" + env.vendor(), "");
        if (env.vendor().equals("AMD"))
            m.put("MC_GL_VENDOR_ATI", "");
        m.put("MC_GL_RENDERER_" + env.renderer(), "");
        m.put("MC_FXAA_LEVEL", "0");
        m.put("MC_NORMAL_MAP", "");
        m.put("MC_SPECULAR_MAP", "");
        m.put("MC_RENDER_QUALITY", "1.0");
        m.put("MC_SHADOW_QUALITY", "1.0");
        m.put("MC_HAND_DEPTH", "0.125");
        if (env.labPbr()) {
            m.put("MC_TEXTURE_FORMAT_LAB_PBR", "");
            m.put("MC_TEXTURE_FORMAT_LAB_PBR_1_3", "");
        }
        for (int i = 0; i < RENDER_STAGES.size(); i++)
            m.put("MC_RENDER_STAGE_" + RENDER_STAGES.get(i), String.valueOf(i));
        m.put("IS_IRIS", "");
        m.put("IRIS_VERSION", "10814"); // the Iris the OpenGL pack runs: iris-fabric-1.8.14-beta.1+mc1.21.1
        for (String f : new java.util.TreeSet<>(env.features())) // sorted: Set.of order is random per JVM
            m.put("IRIS_FEATURE_" + f, "");
        // Distant Horizons material classes (DH public API enum order, UNVERIFIED against Iris): Navidson's
        // dh_* and vista_* programs compare against them; Vista writes them in its vertex half.
        String[] dh = {"UNKNOWN", "LEAVES", "STONE", "WOOD", "METAL", "DIRT", "LAVA", "DEEPSLATE", "SNOW", "SAND",
                "TERRACOTTA", "NETHER_STONE", "WATER", "GRASS", "AIR", "ILLUMINATED"};
        for (int i = 0; i < dh.length; i++)
            m.put("DH_BLOCK_" + dh[i], String.valueOf(i));
        if (env.vista())
            m.put("VISTA", "");
        return m;
    }

    /** Extension macros the GLSL compiler defines before the first line (not visible to properties files). */
    public static Map<String, String> glslOnly() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String e : EXTENSION_MACROS)
            m.put(e, "1");
        return m;
    }
}
