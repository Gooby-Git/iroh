// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.nat;

import java.io.IOException;

/**
 * JNI surface of libiroh_lod (iroh-native/lod/src/jni.rs): the LOD store, codec and thread priority. Do not call before
 * {@link LodNativeLoader#load()} succeeded. Calls are per section, per chunk or per batch, never per block. IO errors
 * arrive as IOException, native panics as IllegalStateException.
 */
public final class LodNative {
    /** Bumped together with {@code iroh_lod::ABI} whenever a JNI signature changes. */
    public static final int ABI = 4;

    private LodNative() {}

    public static native int abi();
    /** 1 when the library was built with the private {@code seed} feature (then {@link LodSeedNative} works), else 0. */
    public static native int hasSeed();
    /** Sets the calling thread's priority: 0 normal, 1 low, 2 idle. Returns 0, or a negative code when refused. */
    public static native int threadPriority(int level);

    // ---------------------------------------------------------------- store
    public static native long storeOpen(String root) throws IOException;
    public static native void storeClose(long h) throws IOException;
    public static native void storeSetClass(long h, int id, int cls);
    public static native void storeSetClasses(long h, byte[] classes, int count);
    /** Cells into {@code out} (32768); false when never seen. */
    public static native boolean storeGet(long h, long key, int[] out) throws IOException;
    /** Packed bytes; with {@code uniformToo} a uniform section comes back as a one-entry packed section. */
    public static native byte[] storeGetPacked(long h, long key, boolean uniformToo) throws IOException;
    public static native long storeHash(long h, long key) throws IOException;
    public static native boolean storeHas(long h, long key) throws IOException;
    /** 1 when some cell has sky light, 0 when none, -1 when never stored. */
    public static native int storeSkyLit(long h, long key) throws IOException;
    /** Bit 32 set when uniform, the cell word in the low half; 0 otherwise. */
    public static native long storeUniform(long h, long key) throws IOException;
    public static native boolean storePut(long h, long key, int[] cells) throws IOException;
    /** {@code [kind 1 uniform | 2 payload][hash u64 LE][cell u32 LE | payload]}, or null. */
    public static native byte[] storeGetStored(long h, long key) throws IOException;
    public static native boolean storePutStored(long h, long key, long hash, boolean uniform, int cell, byte[] payload) throws IOException;
    public static native boolean storeHasChunk(long h, int cx, int cz) throws IOException;
    public static native void storeMarkChunk(long h, int cx, int cz) throws IOException;
    public static native void storeFlush(long h) throws IOException;
    public static native int storeVersion(long h, int level, int rx, int rz) throws IOException;
    public static native boolean storeUpgrade(long h, int level, int rx, int rz) throws IOException;
    /** Rebuilds these parents (one level) from their children; returns the keys whose content changed. */
    public static native long[] storeCascade(long h, long[] keys) throws IOException;

    // ---------------------------------------------------------------- codec (also the parity-test entry points)
    public static native long hashCells(int[] cells);
    public static native long hashUniform(int cell);
    public static native byte[] encodePacked(int[] cells);
    public static native int[] hiddenNormalize(int[] cells, byte[] classes);
    public static native int[] downsample(int[] kids, int present, byte[] classes);
}
