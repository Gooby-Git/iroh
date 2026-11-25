package dev.elemore.iroh.upscale;

/**
 * FSR-Q6 / FSR-Q7: where the upscaler's reactive mask comes from. {@code IROH}: the own colour-difference compute in the
 * velocity pass (the pre-Q6 behaviour). {@code SDK}: FSR's ffxFsr3UpscalerContextGenerateReactiveMask (Auto-Generated, luminance /
 * alpha delta between the opaque copy and the final colour). {@code VOXEL}: Voxel-Aware Custom Mask ({@link VoxelMask}).
 * Switch {@code iroh.upscale.reactiveMode}.
 */
public enum ReactiveMode {
    IROH("iroh"), SDK("sdk"), VOXEL("voxel");

    public final String key;

    ReactiveMode(String key) {
        this.key = key;
    }

    public static ReactiveMode parse(String s) {
        if (s != null)
            for (ReactiveMode m : values())
                if (m.key.equals(s))
                    return m;
        return DEFAULT;
    }

    /** the default picked from the ghost metrics (knowledge/fsr.md G.9) */
    public static final ReactiveMode DEFAULT = IROH;

    public static String keys() {
        return "iroh | sdk | voxel";
    }
}
