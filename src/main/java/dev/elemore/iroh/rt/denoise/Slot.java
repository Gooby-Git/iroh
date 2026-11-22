package dev.elemore.iroh.rt.denoise;

/** Denoiser resource slots. The ordinals of the NRD-related ones equal nrd::ResourceType (checked against the library in a test). */
public enum Slot {
    IN_MV, IN_NORMAL_ROUGHNESS, IN_VIEWZ, IN_DIFF_CONFIDENCE, IN_SPEC_CONFIDENCE, IN_DISOCCLUSION_THRESHOLD_MIX,
    IN_DIFF_RADIANCE_HITDIST, IN_SPEC_RADIANCE_HITDIST, IN_DIFF_HITDIST, IN_SPEC_HITDIST, IN_DIFF_DIRECTION_HITDIST,
    IN_DIFF_SH0, IN_DIFF_SH1, IN_SPEC_SH0, IN_SPEC_SH1, IN_PENUMBRA, IN_TRANSLUCENCY, IN_SIGNAL,
    OUT_DIFF_RADIANCE_HITDIST, OUT_SPEC_RADIANCE_HITDIST, OUT_DIFF_SH0, OUT_DIFF_SH1, OUT_SPEC_SH0, OUT_SPEC_SH1,
    OUT_DIFF_HITDIST, OUT_SPEC_HITDIST, OUT_DIFF_DIRECTION_HITDIST, OUT_SHADOW_TRANSLUCENCY, OUT_SIGNAL, OUT_VALIDATION,
    TRANSIENT_POOL, PERMANENT_POOL;

    public boolean isInput() {
        return ordinal() <= IN_SIGNAL.ordinal();
    }

    public boolean isOutput() {
        return ordinal() >= OUT_DIFF_RADIANCE_HITDIST.ordinal() && ordinal() <= OUT_VALIDATION.ordinal();
    }
}
