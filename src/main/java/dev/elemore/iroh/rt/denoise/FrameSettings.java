package dev.elemore.iroh.rt.denoise;

/**
 * Per-frame data shared by all denoisers (NRD CommonSettings). Matrices: column-major float[16], vectors are columns, NON
 * jittered; worldToView must include the camera delta if the world coordinate system moves with the camera.
 * Motion vectors: {@code mv = IN_MV * motionVectorScale + motionVectorBias}, {@code uvPrev = uv + mv.xy}: i.e. previous minus
 * current, in UV units (NOTE the opposite sign and units of FSR's pixel-unit current-to-previous vectors: see docs/P6-PLAN.md).
 */
public final class FrameSettings {
    public float[] viewToClip = new float[16], viewToClipPrev = new float[16], worldToView = new float[16], worldToViewPrev = new float[16];
    public float[] motionVectorScale = {1, 1, 0}, motionVectorBias = new float[3];
    public float[] jitter = new float[2], jitterPrev = new float[2];          // [-0.5, 0.5] pixels, sampleUv = pixelUv + jitter
    public int[] resourceSize = new int[2], resourceSizePrev = new int[2], rectSize = new int[2], rectSizePrev = new int[2];
    public float viewZScale = 1f, timeDeltaMs = 0f, denoisingRange = 500000f, disocclusionThreshold = 0.01f, disocclusionThresholdAlt = 0.05f, splitScreen = 0f;
    public int frameIndex;                       // +1 every frame, in sync with checkerboard mode
    public boolean reset;                        // RESTART for this frame (history cleared)
    public boolean motionVectorsWorldSpace, historyConfidence, disocclusionMix, validation;
}
