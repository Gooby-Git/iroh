package dev.elemore.iroh.upscale;

/**
 * Texture LOD bias for samplers used while jittered rendering at a reduced resolution.
 *
 * <p>{@link #forSizes} is AMD's formula {@code log2(render/display) - 1}. FSR-Q5 adds the pixel-art rule (the full -1 makes
 * 16x16 pixel-art textures shimmer, see knowledge/fsr.md G.8; the harness FsrMipMetrics picked the rule), a per-frame {@link #resolve} that follows the CURRENT render
 * size (render scale, dynamic resolution), quantization so a sliding size creates a bounded set of samplers, and the cutout
 * clamp (alpha-tested pipelines keep their edges).
 */
public final class MipBias {
    private MipBias() {}

    /** how an auto bias is derived */
    public enum Mode {
        /** half the AMD bias: {@code (log2(render/display) - 1) / 2} (measured best RMSE and least shimmer, G.8) */
        PIXEL_ART,
        /** AMD's log2(render/display) - 1 */
        FORMULA,
        /** no bias */
        OFF;

        public static Mode parse(String s) {
            return switch (s) {
                case "formula" -> FORMULA;
                case "off" -> OFF;
                default -> PIXEL_ART;
            };
        }
    }

    /** pixel-art bias = formula x this. The plan's "formula + 0.5" measured the same at 1x/1.5x but shimmers more at 2x+. */
    public static final float PIXEL_ART_SCALE = 0.5f;
    /** the biggest negative bias a manual slider / formula may ask for: -5 */
    public static final float FLOOR = -5f;
    /** cutout (alpha-tested) pipelines: foliage, fences, bars sample at this bias */
    public static final float CUTOUT_BIAS = 0f;
    /** bias step: a sliding render size creates at most 81 distinct samplers over -5..0 */
    public static final float STEP = 1f / 16f;

    public static float forSizes(int renderW, int displayW) {
        return (float) (Math.log((double) renderW / displayW) / Math.log(2.0)) - 1.0f;
    }

    public static float forMode(QualityMode m) {
        return (float) (-(Math.log(m.ratio) / Math.log(2.0))) - 1.0f;
    }

    /** the auto bias of a mode for the current sizes, quantized, in [FLOOR, 0]. */
    public static float resolve(Mode mode, int renderW, int displayW) {
        if (mode == Mode.OFF || renderW <= 0 || displayW <= 0)
            return 0f;
        float f = forSizes(renderW, displayW);
        return quantize(mode == Mode.PIXEL_ART ? f * PIXEL_ART_SCALE : f);
    }

    public static float quantize(float bias) {
        float b = Math.max(FLOOR, Math.min(0f, bias));
        return Math.round(b / STEP) * STEP;
    }

    /** alpha-tested samplers: the bias clamped toward 0 */
    public static float cutout(float bias) {
        return Math.max(bias, CUTOUT_BIAS);
    }

    /**
     * An alpha-tested pipeline: core cutout shaders (rendertype_cutout, entity_cutout, ...) except the mipped ones, and the
     * terrain pipeline while it draws a pass with alpha cutoff 0.1 (CUTOUT, TRIPWIRE). With uniqueOpaqueLayer (the default)
     * leaves share the cutout_mipped / solid pass (alpha cutoff 0.5) with opaque blocks, so they are NOT clamped (G.8).
     */
    public static boolean isCutoutPipeline(String name, float alphaCutout) {
        if (name == null)
            return false;
        if (name.equals("terrain") || name.equals("terrain_early_z"))
            return alphaCutout > 0.05f && alphaCutout < 0.2f;
        return name.contains("cutout") && !name.contains("mipped");
    }
}
