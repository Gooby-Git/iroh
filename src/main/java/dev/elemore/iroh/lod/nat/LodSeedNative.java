// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.nat;

import java.io.IOException;

/**
 * Seed worldgen entry points of libiroh_lod. They exist only in builds with the private {@code seed} cargo feature
 * (Minecraft-derived Rust ports, pending a clean-room rewrite); check {@link LodNative#hasSeed()} first. The default and
 * public library does not export these symbols, and callers fall back to the Java seed path.
 */
public final class LodSeedNative {
    private LodSeedNative() {}

    /** A density graph from the Java exporter; throws when the native side refuses it. */
    public static native long graphOpen(byte[] graph) throws IOException;
    public static native void graphClose(long h);
    /** A root's value at a point, evaluated directly (no noise chunk): parity tests. */
    public static native double graphEval(long h, int root, int x, int y, int z);
    /** One column's terrain search; see {@code seed/jni_seed.rs} for {@code p} and {@code out}. @return density evaluations made */
    public static native long seedColumn(long graph, int[] p, int[] out) throws IOException;
}
