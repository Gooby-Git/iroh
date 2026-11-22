package dev.elemore.iroh.upscale;

/** Texture LOD bias for samplers used while jittered rendering at a reduced resolution: log2(render/display) - 1. */
public final class MipBias {
    private MipBias() {}

    public static float forSizes(int renderW, int displayW) {
        return (float) (Math.log((double) renderW / displayW) / Math.log(2.0)) - 1.0f;
    }

    public static float forMode(QualityMode m) {
        return (float) (-(Math.log(m.ratio) / Math.log(2.0))) - 1.0f;
    }
}
