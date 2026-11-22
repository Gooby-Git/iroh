package dev.elemore.iroh.shaderpack.graph;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.elemore.iroh.shaderpack.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Uniform supply plan (P3-2 item 3, input to P3-3): for every standard uniform, where Iroh gets the value, how often it
 * changes (FRAME: once per frame at the frame boundary, never on read; PASS: per pass, e.g. viewWidth under a scaled
 * pass; DRAW: per draw call, e.g. entityId), and whether Iroh has it today (EXISTING) or needs new renderer work (NEW).
 * Smoothed values and custom uniforms advance once per frame (Vitrail lesson: per-read smoothing converges at a speed
 * that depends on the pack's pass count).
 */
public final class UniformSupply {

    public enum Cadence { FRAME, PASS, DRAW }

    public enum Work { EXISTING, NEW }

    public record Row(String source, Cadence cadence, Work work) {}

    public static final Map<String, Row> TABLE = new TreeMap<>();

    private static void r(String source, Cadence c, Work w, String... names) {
        for (String n : names)
            TABLE.put(n, new Row(source, c, w));
    }

    static {
        Cadence F = Cadence.FRAME, P = Cadence.PASS, D = Cadence.DRAW;
        Work E = Work.EXISTING, N = Work.NEW;
        r("ClientLevel.getDayTime() % 24000", F, E, "worldTime");
        r("ClientLevel.getDayTime() / 24000", F, E, "worldDay");
        r("ClientLevel.getMoonPhase()", F, E, "moonPhase");
        r("Iroh frame counter (mod 720720, OptiFine wrap)", F, E, "frameCounter");
        r("frame duration, quantised to 1 ms, paused while the game is paused", F, E, "frameTime");
        r("sum of frameTime (wraps at 3600 s)", F, E, "frameTimeCounter");
        r("ClientLevel.getTimeOfDay(partialTick) (celestial angle)", F, E, "sunAngle", "shadowAngle");
        r("ClientLevel.getRainLevel(partialTick)", F, E, "rainStrength");
        r("ClientLevel.getThunderLevel(partialTick)", F, E, "thunderStrength");
        r("rainStrength smoothed: wetnessHalflife rise / constant fall (Iris quirk kept)", F, N, "wetness");
        r("Camera.getPosition()", F, E, "cameraPosition", "eyePosition");
        r("Camera position split into int + fraction", F, E, "cameraPositionInt", "cameraPositionFract");
        r("previous frame's camera position (kept at the frame boundary)", F, N, "previousCameraPosition", "previousCameraPositionInt", "previousCameraPositionFract");
        r("player eye position relative to the camera", F, E, "relativeEyePosition");
        r("player look vector", F, E, "playerLookVector");
        r("Camera.getPosition().y", F, E, "eyeAltitude");
        r("light at the eye block (block, sky) x 16", F, E, "eyeBrightness");
        r("eyeBrightness smoothed over eyeBrightnessHalflife", F, N, "eyeBrightnessSmooth");
        r("player.isEyeInFluid (water 1, lava 2, powder snow 3)", F, E, "isEyeInWater");
        r("night vision / blindness / darkness effect scales", F, E, "nightVision", "blindness", "darknessFactor", "darknessLightFactor");
        r("Options.gamma", F, E, "screenBrightness");
        r("player mood (ambient cave sound progress)", F, E, "playerMood");
        r("Options.hideGui", F, E, "hideGUI");
        r("boss bar present", F, E, "bossBattle");
        r("item.properties id of the main/off-hand item", F, E, "heldItemId", "heldItemId2", "currentRenderedItemId");
        r("IrohApi.heldLightSource(player, hand) (providers: elemore trinket lantern), else the held block's light emission", F, N,
                "heldBlockLightValue", "heldBlockLightValue2");
        r("biome at the eye (id from the Iris BIOME_* table), category, precipitation, temperature, downfall", F, E,
                "biome", "biome_category", "biome_precipitation", "temperature", "rainfall");
        r("level.getMinBuildHeight / getHeight / logicalHeight / hasCeiling / hasSkyLight", F, E,
                "bedrockLevel", "heightLimit", "logicalHeightLimit", "hasCeiling", "hasSkylight");
        r("dimension ambient light", F, E, "ambientLight");
        r("cloud height option (Tempest: its fair-weather base)", F, E, "cloudHeight");
        r("End flash (not in 1.21.1: always 0)", F, E, "endFlashIntensity");
        r("player state flags", F, E, "is_sneaking", "is_sprinting", "is_hurt", "is_invisible", "is_burning", "is_on_ground",
                "firstPersonCamera", "isSpectator");
        r("player health / air / food / armour", F, E, "currentPlayerHealth", "maxPlayerHealth", "currentPlayerAir", "maxPlayerAir",
                "currentPlayerHunger", "maxPlayerHunger", "currentPlayerArmor", "maxPlayerArmor");
        r("date and time of the machine", F, E, "currentDate", "currentTime", "currentYearTime");
        r("lightning bolt entity position (w = 1 when present)", F, E, "lightningBoltPosition");
        r("render size of the pass's target (scale.* and size.buffer.*)", P, E, "viewWidth", "viewHeight", "aspectRatio");
        r("GameRenderer near plane (0.05) / render distance far plane", F, E, "near", "far");
        r("block atlas size", F, E, "atlasSize");
        r("fog colour / start / end / density / mode / shape after FogRenderer.setupFog", P, E, "fogColor", "fogStart", "fogEnd",
                "fogDensity", "fogMode", "fogShape");
        r("sky colour (ClientLevel.getSkyColor)", F, E, "skyColor");
        r("sun / moon / shadow light / up direction in view space", F, E, "sunPosition", "moonPosition", "shadowLightPosition", "upPosition");
        r("world view and projection of the frame (GL convention, legacy depth)", F, E, "gbufferModelView", "gbufferModelViewInverse",
                "gbufferProjection", "gbufferProjectionInverse");
        r("previous frame's view and projection (kept at the frame boundary)", F, N, "gbufferPreviousModelView", "gbufferPreviousProjection");
        r("shadow pass light view and orthographic projection (shadowDistance, sunPathRotation, shadowIntervalSize snapping) + its frustum for culling", F, N,
                "shadowModelView", "shadowModelViewInverse", "shadowProjection", "shadowProjectionInverse");
        r("draw state: model-view / projection / texture matrix / normal matrix of the draw", D, E, "modelViewMatrix", "modelViewMatrixInverse",
                "projectionMatrix", "projectionMatrixInverse", "textureMatrix", "normalMatrix", "iroh_ModelViewMatrix", "iroh_ProjectionMatrix",
                "iroh_ModelViewProjectionMatrix", "iroh_ModelViewMatrixInverse", "iroh_ProjectionMatrixInverse",
                "iroh_ModelViewProjectionMatrixInverse", "iroh_NormalMatrix", "iroh_TextureMatrix", "iroh_TextureMatrixInverse",
                "iroh_ModelViewMatrixTranspose", "iroh_ProjectionMatrixTranspose", "iroh_ModelViewMatrixInverseTranspose", "iroh_NormalScale");
        r("section origin relative to the camera", D, E, "chunkOffset", "modelOffset");
        r("gl_Fog emulation from the fog state", P, E, "iroh_Fog");
        r("entity.properties id of the entity being drawn", D, N, "entityId");
        r("block.properties id of the block entity being drawn", D, N, "blockEntityId");
        r("entity hurt / creeper flash overlay colour", D, N, "entityColor");
        r("render stage of the draw (MC_RENDER_STAGE_*)", D, N, "renderStage");
        r("alpha test reference of the draw (alphaTest.* or vanilla)", D, E, "alphaTestRef");
        r("blend function of the draw", D, E, "blendFunc");
        r("ColorModulator of the draw", D, E, "colorModulator");
        r("sprite bounds of the draw (entities)", D, N, "spriteBounds");
        r("instance id (countInstances)", D, N, "instanceId");
        r("centre-screen depth smoothed on the GPU (1-texel image, centerDepthHalflife)", F, N, "centerDepthSmooth");
        r("Vista contract (Phase 9): camera, LOD ranges, fade, projections", F, N, "vistaCamBlock", "vistaCamFrac", "vistaMinY",
                "vistaRenderDistance", "vistaNearPlane", "vistaFarPlane", "vistaBand", "vistaCamChunk", "vistaFadeMs", "vistaFadeNow",
                "vistaLevelCount", "vistaLevelStart", "vistaLevelEnd", "vistaLoadedRadius", "vistaLodNearFar", "vistaProjection",
                "vistaProjectionInverse", "vistaPreviousProjection", "vistaRealDepthOn", "vistaRealNearFar", "vistaRealRadius");
        r("Distant Horizons (unsupported under Iroh: stays zero)", F, N, "dhProjection", "dhProjectionInverse", "dhPreviousProjection",
                "dhNearPlane", "dhFarPlane", "dhRenderDistance");
    }

    public static final class Report {
        public final Map<String, Object> requested = new TreeMap<>();
        public final List<String> unsupplied = new ArrayList<>();
        public final List<Object> custom = new ArrayList<>();
        public final List<Object> dynamicTextures = new ArrayList<>();
        public final List<String> newWork = new ArrayList<>();
        public List<String> evalOrder = List.of();
        public Map<String, String> evalDropped = Map.of();

        public Object toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("requested", requested);
            m.put("unsupplied", unsupplied);
            m.put("custom", custom);
            m.put("dynamicTextures", dynamicTextures);
            m.put("newRendererWork", newWork);
            m.put("customEvaluation", "once per frame after the engine values, in dependency order (CustomEval); smooth() state per id or call site");
            m.put("customOrder", evalOrder);
            m.put("customDropped", new TreeMap<>(evalDropped));
            return m;
        }

        public String toText() {
            StringBuilder sb = new StringBuilder("-- uniform supply (" + requested.size() + " requested)\n");
            requested.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
            sb.append("  unsupplied: ").append(unsupplied).append('\n');
            sb.append("  new renderer work: ").append(newWork).append('\n');
            sb.append("  dynamic textures (a sampling pack makes IrohTextures.hasConsumers() true): ").append(dynamicTextures).append('\n');
            return sb.toString();
        }
    }

    public static Report report(PackData d) {
        Report rep = new Report();
        JsonObject usage = d.pack.getAsJsonObject("uniformUsage");
        java.util.Set<String> work = new java.util.TreeSet<>();
        for (Map.Entry<String, JsonElement> e : usage.entrySet()) {
            JsonObject u = e.getValue().getAsJsonObject();
            String cls = u.get("class").getAsString();
            if (cls.startsWith("custom:"))
                continue;
            Row row = TABLE.get(e.getKey());
            if (row == null) {
                rep.unsupplied.add(e.getKey() + " (" + u.get("type").getAsString() + ", " + cls + "): reads zero or its GLSL default");
                continue;
            }
            rep.requested.put(e.getKey(), Json.obj("type", u.get("type").getAsString(), "units", u.get("units").getAsInt(),
                    "source", row.source(), "cadence", row.cadence().name(), "work", row.work().name()));
            if (row.work() == Work.NEW)
                work.add(row.source());
        }
        for (JsonElement e : d.pack.getAsJsonArray("customUniforms")) {
            JsonObject c = e.getAsJsonObject();
            rep.custom.add(Json.obj("name", c.get("name").getAsString(), "type", c.get("type").getAsString(),
                    "kind", c.get("kind").getAsString(), "errors", c.get("errors").toString()));
        }
        for (Map.Entry<String, JsonElement> e : d.properties().getAsJsonObject("customTextures").entrySet()) {
            String v = e.getValue().getAsString();
            if (v.contains(":dynamic/"))
                rep.dynamicTextures.add(Json.obj("sampler", e.getKey(), "texture", v,
                        "supply", "IrohTextures dynamic texture; the pack's sampler registers a consumer (DynamicTextures.addConsumer) when the plan binds it"));
        }
        if (!rep.dynamicTextures.isEmpty())
            work.add("bind IrohTextures dynamic textures as pack custom textures (consumer registration)");
        CustomEval ev = new CustomEval(d.pack.getAsJsonArray("customUniforms"));
        rep.evalOrder = ev.order();
        rep.evalDropped = ev.dropped;
        boolean images = d.properties().getAsJsonObject("images").size() > 0;
        if (images)
            work.add("custom images (image.*): allocate, clear per frame, bind as storage + sampler (voxel light volume in Navidson)");
        rep.newWork.addAll(work);
        return rep;
    }
}
