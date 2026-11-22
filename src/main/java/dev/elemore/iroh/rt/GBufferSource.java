package dev.elemore.iroh.rt;

/**
 * Where the RT core's primary surfaces come from (primary visibility is RASTERISED: docs/P5-DESIGN.md section 1).
 * RASTER = Iroh's terrain/entity raster path writing the packed layout below; PACK = Navidson's gbuffers programs
 * (the pack path) writing the same layout through a small adapter pass. One RGBA32UI texel + depth:
 * <pre>
 *   x = octahedral normal, unorm16 x2      y = albedo rgb8 | material/tile id << 24
 *   z = viewZ (float bits; 0 = sky)        w = motion half2, pixels, previous - current
 * </pre>
 * (rtReplay's gbuffer.frag is the reference writer; the game G-buffer should move to fp16 targets + a material id
 * target, see the design.) Implementations record into the frame's command buffer before the RT passes.
 */
public interface GBufferSource {
    enum Kind { RASTER, PACK }

    Kind kind();

    /** Render-resolution size of the G-buffer (the upscaler's input size). */
    int width();

    int height();

    /** Record the G-buffer for this frame; {@code jitterX/Y} in pixels (the upscaler's sequence). */
    void record(long commandBuffer, float jitterX, float jitterY);
}
