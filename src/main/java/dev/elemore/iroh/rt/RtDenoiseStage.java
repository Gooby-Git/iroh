package dev.elemore.iroh.rt;

/**
 * The RT core's seam to a denoiser. Card P6-0 builds NRD as {@code dev.elemore.iroh.rt.denoise.Denoiser} (in progress
 * on another branch, not merged when P5-0 was written); this interface is the minimal contract the RT passes need and
 * the adapter maps it 1:1 onto P6-0's slots:
 * <pre>
 *   viewZ            -> IN_VIEWZ                       (G-buffer z; linear, positive)
 *   normalRoughness  -> IN_NORMAL_ROUGHNESS             (oct normal + roughness, NRD packing done by the adapter)
 *   motion           -> IN_MV                           (G-buffer w is pixels prev-current: NRD wants uv prev-current
 *                                                        => motionVectorScale = 1/size; FSR wants pixels => scale 1)
 *   diffuse          -> IN_DIFF_RADIANCE_HITDIST        (sun + block light + GI irradiance, demodulated; w = GI hit distance)
 *   specular         -> IN_SPEC_RADIANCE_HITDIST        (reflections; w = reflection hit distance)
 *   penumbra (opt.)  -> IN_PENUMBRA for SIGMA           (not produced in v1: sun shadow is denoised inside "diffuse")
 * </pre>
 * The offline stand-in is temporal accumulation (iroh/rt/accum.comp). Buffers are opaque handles of the device in use.
 */
public interface RtDenoiseStage {
    record Inputs(Object viewZ, Object normalRoughness, Object motion, Object diffuse, Object specular, int width, int height, boolean reset) {}

    record Outputs(Object diffuse, Object specular) {}

    String name();

    Outputs denoise(long commandBuffer, Inputs in);
}
