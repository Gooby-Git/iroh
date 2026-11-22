package dev.elemore.iroh.shaderpack.runtime;

import java.util.Map;
import java.util.Set;

/**
 * What the game supplies to one pack frame (the executor's only window on the world). In game: Iroh's renderer
 * (camera, previous and shadow matrices, gbuffer passes, lightmap, atlas, UniformSupply + CustomEval values, time,
 * weather). Offline: a {@link FrameCapture} read from disk or a {@link SyntheticScene}.
 */
public interface PackFrameInputs {

    int width();

    int height();

    /** uniform values by name (standard, compat iroh_* and custom), each 1..16 doubles; missing names use the GLSL default */
    Map<String, double[]> uniforms();

    /**
     * Images the frame provides instead of running the passes that write them (a replay starts at a stage): names are
     * pack target names (colortex0, depthtex0, depthtex1, depthtex2, shadowtex0, shadowtex1, shadowcolor0 ...) or custom
     * texture sampler names. Values: rgba floats, 4 per texel (depth in .r).
     */
    Set<String> suppliedImages();

    float[] image(String name);

    /** extent of a supplied image: {w, h, d} */
    int[] extent(String name);

    /** the stage the executor starts at; passes before it are supplied ("deferred" for a post-chain replay, "begin" in game) */
    String startStage();

    double frameSeconds();
}
