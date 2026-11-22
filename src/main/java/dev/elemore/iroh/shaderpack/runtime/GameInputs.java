package dev.elemore.iroh.shaderpack.runtime;

import com.google.gson.JsonArray;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.elemore.iroh.api.FrameContext;
import dev.elemore.iroh.shaderpack.UniformCatalog;
import dev.elemore.iroh.shaderpack.graph.CustomEval;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Post-mode frame inputs from the running game (P3-4): the uniform rows of UniformSupply that Iroh has today (camera,
 * matrices + previous frame's, time, weather, eye light, fog/sky colour, player effects, screen) plus the pack's custom
 * uniforms through {@link CustomEval} once per frame; the raster scene colour / depth as copy sources; flat material
 * defaults as clear values. Rows marked NEW in UniformSupply that need new renderer work (shadow matrices, id maps,
 * renderStage, held light, Vista) are not supplied here: they read zero / their GLSL default.
 * UNVERIFIED until the game runs it.
 */
final class GameInputs implements PackFrameInputs {

    private final Map<String, double[]> u = new HashMap<>();
    private Map<String, Integer> copies = Map.of();
    private Map<String, float[]> clears = Map.of();
    private int w, h;
    private double dt;
    // state across frames
    private double[] prevView, prevProj, prevCam;
    private long frameCounter;
    private double frameTimeCounter;
    private long lastNanos;
    private CustomEval custom;
    private JsonArray customSource;

    void setCustom(JsonArray decls) {
        if (decls != customSource) {
            customSource = decls;
            custom = new CustomEval(decls);
        }
    }

    void update(FrameContext ctx, int width, int height, Map<String, Integer> copySources, Map<String, float[]> clearValues,
                double sunPathRotationDeg) {
        w = width;
        h = height;
        copies = copySources;
        clears = clearValues;
        long now = System.nanoTime();
        dt = lastNanos == 0 ? 1 / 60.0 : Math.min(0.25, (now - lastNanos) / 1e9);
        dt = Math.round(dt * 1000) / 1000.0; // quantised to the millisecond (Vitrail lesson)
        lastNanos = now;
        frameCounter = (frameCounter + 1) % 720720;
        frameTimeCounter = (frameTimeCounter + dt) % 3600;
        u.clear();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        float pt = ctx.partialTick();
        double[] view = mat(new Matrix4f(ctx.viewMatrix()));
        double[] proj = mat(new Matrix4f(ctx.projectionMatrix()));
        u.put("gbufferModelView", view);
        u.put("gbufferModelViewInverse", mat(new Matrix4f(ctx.viewMatrix()).invert()));
        u.put("gbufferProjection", proj);
        u.put("gbufferProjectionInverse", mat(new Matrix4f(ctx.projectionMatrix()).invert()));
        u.put("gbufferPreviousModelView", prevView != null ? prevView : view);
        u.put("gbufferPreviousProjection", prevProj != null ? prevProj : proj);
        double[] cam = {ctx.cameraPos().x(), ctx.cameraPos().y(), ctx.cameraPos().z()};
        u.put("cameraPosition", cam);
        u.put("previousCameraPosition", prevCam != null ? prevCam : cam);
        u.put("cameraPositionInt", new double[]{Math.floor(cam[0]), Math.floor(cam[1]), Math.floor(cam[2])});
        u.put("cameraPositionFract", new double[]{cam[0] - Math.floor(cam[0]), cam[1] - Math.floor(cam[1]), cam[2] - Math.floor(cam[2])});
        double[] pc = prevCam != null ? prevCam : cam;
        u.put("previousCameraPositionInt", new double[]{Math.floor(pc[0]), Math.floor(pc[1]), Math.floor(pc[2])});
        u.put("previousCameraPositionFract", new double[]{pc[0] - Math.floor(pc[0]), pc[1] - Math.floor(pc[1]), pc[2] - Math.floor(pc[2])});
        u.put("eyePosition", cam);
        u.put("relativeEyePosition", new double[3]);
        prevView = view;
        prevProj = proj;
        prevCam = cam;
        u.put("viewWidth", new double[]{w});
        u.put("viewHeight", new double[]{h});
        u.put("aspectRatio", new double[]{(double) w / h});
        u.put("near", new double[]{0.05});
        u.put("far", new double[]{mc.options.getEffectiveRenderDistance() * 16.0});
        u.put("frameCounter", new double[]{frameCounter});
        u.put("frameTime", new double[]{dt});
        u.put("frameTimeCounter", new double[]{frameTimeCounter});
        if (level != null) {
            long day = level.getDayTime();
            u.put("worldTime", new double[]{day % 24000});
            u.put("worldDay", new double[]{day / 24000});
            u.put("moonPhase", new double[]{level.getMoonPhase()});
            u.put("rainStrength", new double[]{level.getRainLevel(pt)});
            u.put("thunderStrength", new double[]{level.getThunderLevel(pt)});
            u.put("wetness", new double[]{level.getRainLevel(pt)}); // smoothing (wetnessHalflife) is NEW work: raw for now
            double celestial = level.getTimeOfDay(pt);
            double sunAngle = celestial < 0.75 ? celestial + 0.25 : celestial - 0.75;
            u.put("sunAngle", new double[]{sunAngle});
            u.put("shadowAngle", new double[]{sunAngle < 0.5 ? sunAngle : sunAngle - 0.5});
            // sun direction: vanilla's celestial rotation (Y -90, X celestial*360), tilted by sunPathRotation (Z), into view space
            Matrix4f m = new Matrix4f(ctx.viewMatrix()).rotateY((float) Math.toRadians(-90)).rotateZ((float) Math.toRadians(sunPathRotationDeg))
                    .rotateX((float) (celestial * Math.PI * 2));
            org.joml.Vector4f sun = m.transform(new org.joml.Vector4f(0, 100, 0, 0));
            u.put("sunPosition", new double[]{sun.x, sun.y, sun.z});
            u.put("moonPosition", new double[]{-sun.x, -sun.y, -sun.z});
            boolean day0 = sunAngle < 0.5;
            u.put("shadowLightPosition", day0 ? new double[]{sun.x, sun.y, sun.z} : new double[]{-sun.x, -sun.y, -sun.z});
            org.joml.Vector4f up = new Matrix4f(ctx.viewMatrix()).transform(new org.joml.Vector4f(0, 100, 0, 0));
            u.put("upPosition", new double[]{up.x, up.y, up.z});
            Vec3 sky = level.getSkyColor(new Vec3(cam[0], cam[1], cam[2]), pt);
            u.put("skyColor", new double[]{sky.x, sky.y, sky.z});
            BlockPos eye = mc.gameRenderer.getMainCamera().getBlockPosition();
            u.put("eyeBrightness", new double[]{level.getBrightness(LightLayer.BLOCK, eye) * 16, level.getBrightness(LightLayer.SKY, eye) * 16});
            u.put("eyeBrightnessSmooth", u.get("eyeBrightness"));
            u.put("eyeAltitude", new double[]{cam[1]});
            FogType fog = mc.gameRenderer.getMainCamera().getFluidInCamera();
            u.put("isEyeInWater", new double[]{fog == FogType.WATER ? 1 : fog == FogType.LAVA ? 2 : fog == FogType.POWDER_SNOW ? 3 : 0});
            u.put("bedrockLevel", new double[]{level.getMinBuildHeight()});
            u.put("heightLimit", new double[]{level.getHeight()});
            u.put("hasSkylight", new double[]{level.dimensionType().hasSkyLight() ? 1 : 0});
            u.put("hasCeiling", new double[]{level.dimensionType().hasCeiling() ? 1 : 0});
        }
        if (mc.player != null) {
            u.put("nightVision", new double[]{mc.player.hasEffect(MobEffects.NIGHT_VISION) ? 1 : 0});
            u.put("blindness", new double[]{mc.player.hasEffect(MobEffects.BLINDNESS) ? 1 : 0});
            u.put("is_sneaking", new double[]{mc.player.isShiftKeyDown() ? 1 : 0});
            u.put("is_sprinting", new double[]{mc.player.isSprinting() ? 1 : 0});
            u.put("currentPlayerHealth", new double[]{mc.player.getHealth() / Math.max(1, mc.player.getMaxHealth())});
        }
        float[] fc = RenderSystem.getShaderFogColor();
        u.put("fogColor", new double[]{fc[0], fc[1], fc[2]});
        u.put("fogStart", new double[]{RenderSystem.getShaderFogStart()});
        u.put("fogEnd", new double[]{RenderSystem.getShaderFogEnd()});
        u.put("screenBrightness", new double[]{mc.options.gamma().get()});
        u.put("hideGUI", new double[]{mc.options.hideGui ? 1 : 0});
        u.put("cloudHeight", new double[]{192});
        if (custom != null) {
            CustomEval.Env env = name -> {
                double[] v = u.get(name);
                if (v != null)
                    return new CustomEval.Value(v.clone(), false);
                if (name.startsWith("BIOME_") || name.startsWith("CAT_"))
                    return CustomEval.Value.of(-1); // biome table is NEW work (UniformSupply): never matches
                UniformCatalog.Std s = UniformCatalog.STANDARD.get(name);
                int n = s == null ? 1 : switch (s.type()) {
                    case "vec2", "ivec2" -> 2;
                    case "vec3", "ivec3" -> 3;
                    case "vec4", "ivec4" -> 4;
                    case "mat4" -> 16;
                    default -> 1;
                };
                return new CustomEval.Value(new double[n], false);
            };
            try {
                custom.evaluate(env, dt).forEach((k, v) -> u.put(k, v.v()));
            } catch (RuntimeException e) {
                // a custom uniform that cannot evaluate keeps its GLSL default (logged once by the caller's frame log)
            }
        }
    }

    static double[] mat(Matrix4f m) {
        float[] f = new float[16];
        m.get(f);
        double[] d = new double[16];
        for (int i = 0; i < 16; i++)
            d[i] = f[i];
        return d;
    }

    @Override
    public int width() {
        return w;
    }

    @Override
    public int height() {
        return h;
    }

    @Override
    public Map<String, double[]> uniforms() {
        return u;
    }

    @Override
    public Set<String> suppliedImages() {
        return Set.of();
    }

    @Override
    public float[] image(String name) {
        return null;
    }

    @Override
    public int[] extent(String name) {
        return null;
    }

    @Override
    public String startStage() {
        return "deferred";
    }

    @Override
    public double frameSeconds() {
        return dt;
    }

    @Override
    public Map<String, Integer> copySources() {
        return copies;
    }

    @Override
    public Map<String, float[]> clearValues() {
        return clears;
    }

    Map<String, double[]> uniformSnapshot() {
        return new LinkedHashMap<>(u);
    }
}
