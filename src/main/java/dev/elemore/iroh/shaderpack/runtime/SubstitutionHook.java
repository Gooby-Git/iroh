package dev.elemore.iroh.shaderpack.runtime;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.elemore.iroh.debug.DebugSwitches;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineState;

import java.util.HashSet;
import java.util.Set;

/**
 * Game-side glue for {@link Substitution} (P3-10 observe) and the P3-11 world pass ({@link WorldPass}). Loaded only
 * once {@link Substitution#HOOK} is non-zero: with the pack off the renderer pays one volatile read per bind and one
 * per draw and never touches this class.
 */
public final class SubstitutionHook {

    private SubstitutionHook() {}

    private static volatile Set<String> rasterMods = Set.of();
    private static volatile String rasterModsSource = "";
    /** the live world pass (render thread), null outside a world-pass frame */
    static volatile WorldPass active;

    static Set<String> rasterMods() {
        String src = DebugSwitches.getOr(PackSwitches.RASTER_MODS, "");
        if (!src.equals(rasterModsSource)) {
            Set<String> s = new HashSet<>();
            for (String m : src.split(","))
                if (!m.isBlank())
                    s.add(m.trim());
            rasterMods = s;
            rasterModsSource = src;
        }
        return rasterMods;
    }

    public static void observe(String shader, VertexFormat format) {
        Substitution.observe(shader, format == null ? null : format.getElementAttributeNames(),
                dev.elemore.iroh.apiimpl.FrameHooks.context().inWorld(), rasterMods());
    }

    /** Renderer.bindGraphicsPipeline: true = the pack pipeline is bound instead (the caller skips the vanilla bind) */
    public static boolean bind(GraphicsPipeline pipeline) {
        int h = Substitution.HOOK;
        if ((h & Substitution.OBS) != 0)
            observe(pipeline.name, pipeline.getVertexFormat());
        WorldPass w = active;
        if (w == null || !net.vulkanmod.vulkan.Renderer.isRecording())
            return false;
        try {
            VertexFormat f = pipeline.getVertexFormat();
            PipelineState.BlendInfo b = PipelineState.blendInfo;
            // rain/snow use the particle shader: inside renderSnowAndRain the class is weather (separate decision key)
            boolean weather = DrawIds.weather;
            return w.onBind(weather ? WEATHER_KEYS.computeIfAbsent(pipeline, k -> new Object()) : pipeline,
                    weather ? "iroh:weather" : pipeline.name, f == null ? null : f.getElementAttributeNames(), VRenderSystem.topology,
                    VRenderSystem.depthTest, VRenderSystem.depthMask, VRenderSystem.cull, b.enabled, b.srcRgbFactor, b.dstRgbFactor,
                    b.srcAlphaFactor, b.dstAlphaFactor, dev.elemore.iroh.apiimpl.FrameHooks.context().inWorld());
        } catch (RuntimeException e) { // never into vanilla rendering: the world pass turns itself off, this draw is raster
            w.failNow("bind " + pipeline.name + ": " + e);
            return false;
        }
    }

    private static final java.util.Map<Object, Object> WEATHER_KEYS = new java.util.IdentityHashMap<>();

    /**
     * Renderer.uploadAndBindUBOs: true = a substituted draw follows and its descriptor sets are bound (skip the vanilla
     * ones). On a failure the world pass turns off and the vanilla pipeline is bound again, so the draw stays raster.
     */
    public static boolean beforeDraw(net.vulkanmod.vulkan.shader.Pipeline pipeline) {
        WorldPass w = active;
        if (w == null)
            return false;
        try {
            return w.beforeDraw();
        } catch (RuntimeException e) {
            w.failNow("draw: " + e);
            if (pipeline instanceof GraphicsPipeline gp)
                net.vulkanmod.vulkan.Renderer.getInstance().bindGraphicsPipeline(gp); // now raster (the world pass failed)
            return false;
        }
    }

    /** a render pass begin/end or an out-of-pass recording of Iroh's: close the world pass's attachments first */
    public static void closeForForeign() {
        WorldPass w = active;
        if (w != null)
            w.closeForForeign();
    }
}
