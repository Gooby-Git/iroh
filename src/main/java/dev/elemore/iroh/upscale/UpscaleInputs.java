package dev.elemore.iroh.upscale;

/**
 * Everything one upscale dispatch needs. Conventions (identical for FSR 3.1, FSR 4 and DLSS):
 * <ul>
 * <li>colour: render resolution, jittered, linear HDR or SDR as declared at create; layout SHADER_READ_ONLY.</li>
 * <li>depth: render resolution, 32-bit float, sampled; layout SHADER_READ_ONLY; standard 0..1 unless the context was created inverted.</li>
 * <li>motion vectors: render resolution, R16G16_SFLOAT, vector from a pixel's CURRENT position to its position in the
 *     PREVIOUS frame, in units such that {@code mv * motionVectorScale} is pixels of the render image; UNJITTERED.</li>
 * <li>exposure (1x1 R32_FLOAT) and reactive / transparency&composition masks (R8_UNORM) optional.</li>
 * <li>output: display resolution, STORAGE usage, layout GENERAL.</li>
 * </ul>
 */
public final class UpscaleInputs {
    public long commandBuffer;
    public ImageRef color = ImageRef.NONE, depth = ImageRef.NONE, motionVectors = ImageRef.NONE, exposure = ImageRef.NONE,
            reactive = ImageRef.NONE, transparency = ImageRef.NONE, output = ImageRef.NONE;
    public float jitterX, jitterY;                 // pixels, from JitterSequence for this frame
    public float motionVectorScaleX = 1f, motionVectorScaleY = 1f;
    public int renderWidth, renderHeight, displayWidth, displayHeight;
    public boolean enableSharpening;
    public float sharpness;                        // 0..1
    public float frameTimeMs;
    public float preExposure = 1f;
    public float cameraNear, cameraFar, cameraFovYRadians;
    public float viewSpaceToMeters = 1f;
    public boolean reset;                          // first frame after a camera cut / resize / world change
    public boolean debugView;
}
