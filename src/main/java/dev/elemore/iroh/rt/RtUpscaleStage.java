package dev.elemore.iroh.rt;

/**
 * The RT core's seam to the temporal upscaler (card P4-2a's {@code dev.elemore.iroh.upscale.Upscaler}: FSR 3.1 on
 * Linux, FSR 4 / DLSS later). Order (docs/P5-DESIGN.md section 11): RT passes -> denoise -> compose + fog (linear HDR,
 * render size) -> THIS -> tone map, grade, bloom, UI at display size. Inputs: composed colour (pre-exposure 1, HDR flag),
 * depth, motion (pixels, previous - current), reactive mask from depth disocclusion, jitter of this frame.
 */
public interface RtUpscaleStage {
    record Inputs(Object color, Object depth, Object motion, Object reactive, int renderW, int renderH, float jitterX, float jitterY,
                  float frameTimeMs, boolean reset) {}

    /** Render size for a display size (the preset's scale). */
    int[] renderSize(int displayW, int displayH, float scale);

    /** Jitter in pixels for {@code frameIndex} ([-0.5, 0.5]; the G-buffer projection is offset by it). */
    float[] jitter(long frameIndex, int renderW, int displayW);

    void upscale(long commandBuffer, Inputs in, Object output);
}
