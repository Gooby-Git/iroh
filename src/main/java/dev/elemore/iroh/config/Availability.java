package dev.elemore.iroh.config;

import dev.elemore.iroh.ui.CapabilityReport;
import net.minecraft.network.chat.Component;

/**
 * Availability predicates behind the greyed-out lines on the Shaders tab. Each returns null when usable, otherwise the
 * reason shown next to the option. Later phases flip the matching {@code *_IMPLEMENTED} switch when the feature lands;
 * nothing else on the screen changes. A missing capability reports the hardware reason first.
 */
public final class Availability {
    private Availability() {}

    public static volatile boolean SHADER_PACKS_IMPLEMENTED = false;
    public static volatile boolean UPSCALER_IMPLEMENTED = false;
    public static volatile boolean RAY_TRACING_IMPLEMENTED = false;
    public static volatile boolean FRAME_GENERATION_IMPLEMENTED = false;
    /**
     * Fabulous graphics (td-75c969, P1-10): vanilla's transparency post chain on Iroh's off-screen targets. On unless
     * {@code -Diroh.fabulous=false}. Vanilla itself drops back to Fancy (with its warning screen) if the chain fails to load.
     */
    public static final boolean FABULOUS_IMPLEMENTED = !"false".equals(System.getProperty("iroh.fabulous"));

    public static Component fabulous() {
        return FABULOUS_IMPLEMENTED ? null : Component.translatable("iroh.reason.fabulous");
    }

    public static Component shaderPacks() {
        return SHADER_PACKS_IMPLEMENTED ? null : Component.translatable("iroh.reason.notYet");
    }

    public static Component upscaler() {
        return UPSCALER_IMPLEMENTED ? null : Component.translatable("iroh.reason.notYet");
    }

    public static Component rayTracing() {
        CapabilityReport r = CapabilityReport.get();
        if (!(r.has("rayTracingPipeline") && r.has("accelerationStructure")) && !(r.has("rayQuery") && r.has("accelerationStructure")))
            return Component.translatable("iroh.reason.needsRt");
        return RAY_TRACING_IMPLEMENTED ? null : Component.translatable("iroh.reason.notYet");
    }

    public static Component frameGeneration() {
        return FRAME_GENERATION_IMPLEMENTED ? null : Component.translatable("iroh.reason.notYet");
    }
}
