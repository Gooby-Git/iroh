package dev.elemore.iroh.rt.denoise;

import java.util.EnumSet;
import java.util.Set;

import static dev.elemore.iroh.rt.denoise.Slot.*;

/**
 * What a denoiser instance denoises. {@code nrdValue} is the nrd::Denoiser enum value (-1 = not NRD). The slot sets are the
 * required (non-optional) inputs and the outputs from NRD's own header (NRDDescs.h), plus the always-required IN_MV /
 * IN_NORMAL_ROUGHNESS / IN_VIEWZ (SIGMA needs IN_MV only with stabilisation).
 */
public enum DenoiserType {
    REBLUR_DIFFUSE(0, EnumSet.of(IN_DIFF_RADIANCE_HITDIST), EnumSet.of(OUT_DIFF_RADIANCE_HITDIST)),
    REBLUR_SPECULAR(3, EnumSet.of(IN_SPEC_RADIANCE_HITDIST), EnumSet.of(OUT_SPEC_RADIANCE_HITDIST)),
    REBLUR_DIFFUSE_SPECULAR(6, EnumSet.of(IN_DIFF_RADIANCE_HITDIST, IN_SPEC_RADIANCE_HITDIST), EnumSet.of(OUT_DIFF_RADIANCE_HITDIST, OUT_SPEC_RADIANCE_HITDIST)),
    RELAX_DIFFUSE(10, EnumSet.of(IN_DIFF_RADIANCE_HITDIST), EnumSet.of(OUT_DIFF_RADIANCE_HITDIST)),
    RELAX_SPECULAR(12, EnumSet.of(IN_SPEC_RADIANCE_HITDIST), EnumSet.of(OUT_SPEC_RADIANCE_HITDIST)),
    RELAX_DIFFUSE_SPECULAR(14, EnumSet.of(IN_DIFF_RADIANCE_HITDIST, IN_SPEC_RADIANCE_HITDIST), EnumSet.of(OUT_DIFF_RADIANCE_HITDIST, OUT_SPEC_RADIANCE_HITDIST)),
    SIGMA_SHADOW(16, EnumSet.of(IN_PENUMBRA), EnumSet.of(OUT_SHADOW_TRANSLUCENCY)),
    /** Iroh's own minimal temporal accumulation (no NRD): diffuse radiance only. */
    TEMPORAL_ACCUMULATION(-1, EnumSet.of(IN_DIFF_RADIANCE_HITDIST), EnumSet.of(OUT_DIFF_RADIANCE_HITDIST)),
    /** Iroh's own SVGF-family denoiser ({@code SvgfDenoiser}): outputs are always RGBA16F (rgb = signal, a = history length). */
    IROH_DIFFUSE(-1, EnumSet.of(IN_DIFF_RADIANCE_HITDIST), EnumSet.of(OUT_DIFF_RADIANCE_HITDIST)),
    IROH_SPECULAR(-1, EnumSet.of(IN_SPEC_RADIANCE_HITDIST), EnumSet.of(OUT_SPEC_RADIANCE_HITDIST)),
    IROH_SHADOW(-1, EnumSet.of(IN_PENUMBRA), EnumSet.of(OUT_SHADOW_TRANSLUCENCY));

    public final int nrdValue;
    private final Set<Slot> inputs, outputs;

    DenoiserType(int nrdValue, Set<Slot> inputs, Set<Slot> outputs) {
        this.nrdValue = nrdValue;
        this.inputs = inputs;
        this.outputs = outputs;
    }

    public Set<Slot> noisyInputs() {
        return inputs;
    }

    public Set<Slot> outputs() {
        return outputs;
    }

    /** Guides every non-SIGMA denoiser needs. */
    public Set<Slot> guides() {
        return this == SIGMA_SHADOW ? EnumSet.of(IN_NORMAL_ROUGHNESS, IN_VIEWZ) : EnumSet.of(IN_MV, IN_NORMAL_ROUGHNESS, IN_VIEWZ);
    }
}
