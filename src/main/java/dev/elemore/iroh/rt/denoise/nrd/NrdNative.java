package dev.elemore.iroh.rt.denoise.nrd;

import java.nio.ByteBuffer;

/**
 * JNI surface of libiroh_nrd (iroh-native/nrd/src/iroh_nrd_jni.cpp; NVIDIA NRD v4.18.0 stock). NRD makes no graphics API calls:
 * this only creates the instance and exchanges plain structs. THREAD RULE: one instance per thread at a time (the render
 * thread). Direct buffers returned by {@link #instanceDesc}/{@link #dispatches} are valid until the next call of the same
 * kind on that instance or {@link #destroy}.
 */
public final class NrdNative {
    private NrdNative() {}

    public static native String version();
    public static native int commonStructSize();
    /** {samplerOffset, textureOffset, constantBufferOffset, storageOffset, major, minor, build, normalEncoding, roughnessEncoding, supportedNum, supported...}. */
    public static native int[] libraryDesc();
    public static native long create(int[] denoiserEnumValues);
    public static native void destroy(long instance);
    public static native ByteBuffer instanceDesc(long instance);
    public static native ByteBuffer spirv(long instance, int pipeline);
    public static native void setCommon(long instance, ByteBuffer commonStruct);
    /** kind: 0 REBLUR, 1 RELAX, 2 SIGMA, 3 none; kv = {key, value, ...} (keys in {@link NrdSettings}). */
    public static native void setDenoiser(long instance, int identifier, int kind, float[] kv);
    public static native ByteBuffer dispatches(long instance, int[] identifiers);
    public static native String resourceTypeName(int type);
    public static native String denoiserName(int denoiser);
}
