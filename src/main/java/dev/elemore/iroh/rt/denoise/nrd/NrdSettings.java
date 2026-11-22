package dev.elemore.iroh.rt.denoise.nrd;

import java.util.Map;

/** Named denoiser settings -> the shim's integer keys (everything not named keeps NRD's default). */
public final class NrdSettings {
    private NrdSettings() {}

    static final String[] REBLUR = {"hitDistA", "hitDistB", "hitDistC", "maxAccumulatedFrames", "maxFastAccumulatedFrames", "maxStabilizedFrames", "historyFixFrames",
            "diffusePrepassBlurRadius", "specularPrepassBlurRadius", "minBlurRadius", "maxBlurRadius", "lobeAngleFraction", "roughnessFraction", "planeDistanceSensitivity",
            "checkerboardMode", "hitDistanceReconstructionMode", "antiFirefly", "antilagLuminanceSigmaScale", "antilagLuminanceSensitivity", "fastHistoryClampingSigmaScale",
            "returnHistoryLength"};
    static final String[] RELAX = {"diffuseMaxAccumulatedFrames", "specularMaxAccumulatedFrames", "diffuseMaxFastAccumulatedFrames", "specularMaxFastAccumulatedFrames", "historyFixFrames",
            "diffusePrepassBlurRadius", "specularPrepassBlurRadius", "diffusePhiLuminance", "specularPhiLuminance", "lobeAngleFraction", "roughnessFraction", "atrousIterations",
            "depthThreshold", "checkerboardMode", "hitDistanceReconstructionMode", "antiFirefly", "roughnessEdgeStopping", "luminanceEdgeStoppingRelaxation",
            "normalEdgeStoppingRelaxation", "roughnessEdgeStoppingRelaxation", "antilagAccelerationAmount", "antilagResetAmount"};
    static final String[] SIGMA = {"lightDirX", "lightDirY", "lightDirZ", "planeDistanceSensitivity", "maxStabilizedFrames", "checkerboardMode"};

    /** kind: 0 REBLUR, 1 RELAX, 2 SIGMA. */
    public static float[] toKeyValues(int kind, Map<String, Float> settings) {
        String[] names = kind == 0 ? REBLUR : kind == 1 ? RELAX : SIGMA;
        float[] kv = new float[settings.size() * 2];
        int i = 0;
        for (Map.Entry<String, Float> e : settings.entrySet()) {
            int key = -1;
            for (int k = 0; k < names.length; k++) if (names[k].equals(e.getKey())) key = k;
            if (key < 0) throw new IllegalArgumentException("unknown " + (kind == 0 ? "REBLUR" : kind == 1 ? "RELAX" : "SIGMA") + " setting '" + e.getKey() + "'");
            kv[i++] = key;
            kv[i++] = e.getValue();
        }
        return kv;
    }
}
