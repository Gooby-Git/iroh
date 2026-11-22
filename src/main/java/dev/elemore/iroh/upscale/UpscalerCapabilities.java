package dev.elemore.iroh.upscale;

import java.util.Set;

/** What an upscaler backend can do on this machine, and what it needs from the renderer. */
public record UpscalerCapabilities(String name, String version, boolean available, String unavailableReason,
                                   Set<Input> requiredInputs, Set<Input> optionalInputs, boolean supportsHdrInput,
                                   boolean supportsInvertedDepth, boolean needsJitter, boolean fp16) {
    public enum Input { COLOR, DEPTH, MOTION_VECTORS, EXPOSURE, REACTIVE_MASK, TRANSPARENCY_MASK }

    public static UpscalerCapabilities unavailable(String name, String reason) {
        return new UpscalerCapabilities(name, "", false, reason, Set.of(), Set.of(), false, false, true, false);
    }
}
