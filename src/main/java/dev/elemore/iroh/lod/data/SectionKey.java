// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

/**
 * Where a section sits: its level and its section coordinates at that level.
 * {@code y} counts sections up from the dimension's minimum build height, so it is never negative.
 */
public record SectionKey(int level, int x, int y, int z) {
    /** 64 bits: level (3), y (6), x (27, signed), z (27, signed), one spare. Level-0 x/z reach +-67 million sections. */
    public long pack() {
        return (long) level << 61 | (long) (y & 63) << 55 | (x & 0x7FFFFFFL) << 27 | z & 0x7FFFFFFL;
    }

    public static SectionKey unpack(long v) {
        int x = (int) (v >>> 27 & 0x7FFFFFF), z = (int) (v & 0x7FFFFFF);
        return new SectionKey((int) (v >>> 61), x << 5 >> 5, (int) (v >>> 55 & 63), z << 5 >> 5);
    }

    /** Keys from a protocol 1-2 server: level (2), y (6), x (28, signed), z (28, signed). */
    public static SectionKey unpackLegacy(long v) {
        int x = (int) (v >>> 28 & 0xFFFFFFF), z = (int) (v & 0xFFFFFFF);
        return new SectionKey((int) (v >>> 62), x << 4 >> 4, (int) (v >>> 56 & 63), z << 4 >> 4);
    }

    /** A key as a server speaking {@code protocol} packed it. */
    public static SectionKey unpack(long v, int protocol) {
        return protocol < 3 ? unpackLegacy(v) : unpack(v);
    }

    public SectionKey parent() {
        return new SectionKey(level + 1, x >> 1, y >> 1, z >> 1);
    }

    /** Child number 0-7 of this section inside its parent: bit 0 x, bit 1 z, bit 2 y. */
    public int octant() {
        return (y & 1) << 2 | (z & 1) << 1 | (x & 1);
    }

    public SectionKey child(int octant) {
        return new SectionKey(level - 1, x << 1 | (octant & 1), y << 1 | (octant >> 2 & 1), z << 1 | (octant >> 1 & 1));
    }
}
