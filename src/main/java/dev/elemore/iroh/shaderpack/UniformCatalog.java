package dev.elemore.iroh.shaderpack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standard OptiFine / Iris uniforms (names from the public OptiFine shaders.txt and the Iris feature doc,
 * digested in iris-loader.md 3.4), with their GLSL type and how hard each is for Iroh to supply (P3-3 input):
 * <ul>
 *   <li>TRIVIAL: read straight from game state (time, weather, player, camera position, screen size);</li>
 *   <li>RENDERER: needs data Iroh's renderer has per frame or per draw (current matrices, fog, sky colour,
 *       celestial positions, ids of what is being drawn);</li>
 *   <li>NEW_WORK: needs new renderer work (previous-frame matrices, shadow pass matrices, smoothed GPU
 *       readbacks such as centerDepthSmooth, LOD integration).</li>
 * </ul>
 * Only catalogued here; binding values is P3-3.
 */
public final class UniformCatalog {

    public enum Cost { TRIVIAL, RENDERER, NEW_WORK }

    public record Std(String type, Cost cost, String note) {}

    public static final Map<String, Std> STANDARD = new LinkedHashMap<>();

    private static void t(String type, Cost c, String note, String... names) {
        for (String n : names)
            STANDARD.put(n, new Std(type, c, note));
    }

    static {
        Cost T = Cost.TRIVIAL, R = Cost.RENDERER, N = Cost.NEW_WORK;
        t("int", T, "world/time", "worldTime", "worldDay", "moonPhase", "frameCounter", "isEyeInWater", "hideGUI",
                "heldItemId", "heldItemId2", "heldBlockLightValue", "heldBlockLightValue2", "bossBattle",
                "biome", "biome_category", "biome_precipitation", "currentRenderedItemId", "renderStage",
                "bedrockLevel", "heightLimit", "logicalHeightLimit", "currentColorSpace", "dhRenderDistance");
        t("float", T, "world/time/player", "frameTime", "frameTimeCounter", "sunAngle", "shadowAngle", "rainStrength",
                "wetness", "thunderStrength", "eyeAltitude", "nightVision", "blindness", "screenBrightness", "playerMood",
                "darknessFactor", "darknessLightFactor", "aspectRatio", "viewWidth", "viewHeight", "near", "far",
                "currentPlayerHealth", "maxPlayerHealth", "currentPlayerAir", "maxPlayerAir", "currentPlayerHunger",
                "maxPlayerHunger", "currentPlayerArmor", "maxPlayerArmor", "rainfall", "temperature", "ambientLight",
                "cloudHeight", "endFlashIntensity");
        t("bool", T, "player state", "firstPersonCamera", "isSpectator", "hasCeiling", "hasSkylight", "is_sneaking",
                "is_sprinting", "is_hurt", "is_invisible", "is_burning", "is_on_ground");
        t("vec3", T, "camera", "cameraPosition", "eyePosition", "relativeEyePosition", "playerLookVector",
                "cameraPositionFract", "previousCameraPositionFract");
        t("ivec3", T, "camera", "cameraPositionInt", "previousCameraPositionInt", "currentDate", "currentTime");
        t("ivec2", T, "lightmap at the eye", "eyeBrightness", "atlasSize", "currentYearTime");
        t("vec3", N, "kept from last frame", "previousCameraPosition");
        t("ivec2", N, "smoothed over frames", "eyeBrightnessSmooth");
        t("vec4", R, "draw state", "entityColor", "spriteBounds", "lightningBoltPosition");
        t("int", R, "draw state", "entityId", "blockEntityId", "fogMode", "fogShape", "instanceId");
        t("float", R, "fog / draw state", "fogStart", "fogEnd", "fogDensity", "alphaTestRef");
        t("vec3", R, "sky / fog colour", "fogColor", "skyColor");
        t("vec3", R, "celestial in view space", "sunPosition", "moonPosition", "shadowLightPosition", "upPosition");
        t("ivec4", R, "draw state", "blendFunc");
        t("vec4", R, "draw state", "colorModulator");
        t("mat4", R, "current matrices", "gbufferModelView", "gbufferModelViewInverse", "gbufferProjection",
                "gbufferProjectionInverse", "modelViewMatrix", "modelViewMatrixInverse", "projectionMatrix",
                "projectionMatrixInverse", "textureMatrix");
        t("mat3", R, "current matrices", "normalMatrix");
        t("vec3", R, "chunk offset", "chunkOffset", "modelOffset");
        t("mat4", N, "previous-frame matrices", "gbufferPreviousModelView", "gbufferPreviousProjection");
        t("mat4", N, "shadow pass matrices", "shadowModelView", "shadowModelViewInverse", "shadowProjection",
                "shadowProjectionInverse");
        t("float", N, "GPU readback smoothed", "centerDepthSmooth");
        t("mat4", N, "LOD (Distant Horizons; not served by Iroh)", "dhProjection", "dhProjectionInverse", "dhPreviousProjection");
        t("float", N, "LOD (Distant Horizons; not served by Iroh)", "dhNearPlane", "dhFarPlane");
        t("ivec3", N, "Vista LOD contract", "vistaCamBlock");
        t("vec3", N, "Vista LOD contract", "vistaCamFrac");
        t("float", N, "Vista LOD contract", "vistaMinY", "vistaNearPlane", "vistaFarPlane", "vistaRenderDistance");
        // compatibility-profile built-ins the translator turns into block members
        t("mat4", R, "compat built-in", "iroh_ModelViewMatrix", "iroh_ProjectionMatrix", "iroh_ModelViewProjectionMatrix",
                "iroh_ModelViewMatrixInverse", "iroh_ProjectionMatrixInverse", "iroh_ModelViewProjectionMatrixInverse",
                "iroh_ModelViewMatrixTranspose", "iroh_ProjectionMatrixTranspose", "iroh_ModelViewMatrixInverseTranspose",
                "iroh_TextureMatrix", "iroh_TextureMatrixInverse");
        t("mat3", R, "compat built-in", "iroh_NormalMatrix");
        t("float", R, "compat built-in", "iroh_NormalScale");
        t("IrohFogParameters", R, "compat built-in gl_Fog", "iroh_Fog");
    }

    /** Sampler names Iris serves (OptiFine unit table + Iris additions). */
    public static final java.util.Set<String> STANDARD_SAMPLERS = new java.util.LinkedHashSet<>();

    static {
        for (String s : new String[]{"gtexture", "tex", "texture", "lightmap", "normals", "specular", "shadow", "watershadow",
                "shadowtex0", "shadowtex1", "shadowtex0HW", "shadowtex1HW", "depthtex0", "depthtex1", "depthtex2", "gdepthtex",
                "noisetex", "gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4",
                "dhDepthTex", "dhDepthTex0", "dhDepthTex1", "vistaDepthTex0", "vistaDepthTex1"})
            STANDARD_SAMPLERS.add(s);
        for (int i = 0; i < 32; i++)
            STANDARD_SAMPLERS.add("colortex" + i);
        for (int i = 0; i < 8; i++)
            STANDARD_SAMPLERS.add("shadowcolor" + i);
        for (int i = 0; i < 8; i++)
            STANDARD_SAMPLERS.add("colorimg" + i);
        for (int i = 0; i < 8; i++)
            STANDARD_SAMPLERS.add("shadowcolorimg" + i);
    }
}
