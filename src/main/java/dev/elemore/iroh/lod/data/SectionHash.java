// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

/**
 * 64-bit content hash of a section's cells. Server and client compare these to decide what to send,
 * and the client skips remeshing when a section comes back unchanged, so it must be stable forever:
 * changing it makes every cache look stale once.
 */
public final class SectionHash {
    private static final long P1 = 0x9E3779B97F4A7C15L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;

    private SectionHash() {}

    public static long of(int[] cells) {
        long a = 0x243F6A8885A308D3L, b = 0x13198A2E03707344L;
        for (int i = 0; i < cells.length; i += 2) {
            a = Long.rotateLeft(a ^ (cells[i] & 0xFFFFFFFFL) * P1, 29) * P2;
            b = Long.rotateLeft(b ^ (cells[i + 1] & 0xFFFFFFFFL) * P2, 31) * P1;
        }
        return finish(a ^ Long.rotateLeft(b, 17) ^ cells.length);
    }

    private static final java.util.concurrent.ConcurrentHashMap<Integer, Long> UNIFORM = new java.util.concurrent.ConcurrentHashMap<>();

    /** Hash of a section where every cell is {@code cell}, without building the array (cached: a few distinct values per world). */
    public static long ofUniform(int cell) {
        Long h = UNIFORM.get(cell);
        if (h != null) return h;
        if (UNIFORM.size() > 4096) UNIFORM.clear();
        long v = computeUniform(cell);
        UNIFORM.put(cell, v);
        return v;
    }

    private static long computeUniform(int cell) {
        long a = 0x243F6A8885A308D3L, b = 0x13198A2E03707344L;
        long v = cell & 0xFFFFFFFFL;
        for (int i = 0; i < Section.VOLUME; i += 2) {
            a = Long.rotateLeft(a ^ v * P1, 29) * P2;
            b = Long.rotateLeft(b ^ v * P2, 31) * P1;
        }
        return finish(a ^ Long.rotateLeft(b, 17) ^ Section.VOLUME);
    }

    private static long finish(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
