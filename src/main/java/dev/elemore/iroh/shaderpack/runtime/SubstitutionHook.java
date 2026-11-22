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
        if (w == null)
            return false;
        VertexFormat f = pipeline.getVertexFormat();
        PipelineState.BlendInfo b = PipelineState.blendInfo;
        return w.onBind(pipeline, pipeline.name, f == null ? null : f.getElementAttributeNames(), VRenderSystem.topology,
                VRenderSystem.depthTest, VRenderSystem.depthMask, VRenderSystem.cull, b.enabled, b.srcRgbFactor, b.dstRgbFactor,
                b.srcAlphaFactor, b.dstAlphaFactor, dev.elemore.iroh.apiimpl.FrameHooks.context().inWorld());
    }

    /** Renderer.uploadAndBindUBOs: true = a substituted draw follows and its descriptor sets are bound (skip the vanilla ones) */
    public static boolean beforeDraw() {
        WorldPass w = active;
        return w != null && w.beforeDraw();
    }

    /** a render pass begin/end or an out-of-pass recording of Iroh's: close the world pass's attachments first */
    public static void closeForForeign() {
        WorldPass w = active;
        if (w != null)
            w.closeForForeign();
    }
}
