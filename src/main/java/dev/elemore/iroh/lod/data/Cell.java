// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

/**
 * One LOD cell as a 32-bit word (DESIGN.md section 4):
 * bits 0-15 block-state id, 16-19 sky light, 20-23 block light, 24-31 biome id.
 * The same word is what the GPU reads, so nothing here may change without the shaders changing with it.
 */
public final class Cell {
    public static final int BLOCK_MASK = 0xFFFF;
    public static final int AIR = 0;
    /** Open air under the sky: block 0, sky light 15, no block light, biome 0. */
    public static final int OPEN_AIR = 15 << 16;

    private Cell() {}

    public static int of(int block, int sky, int blockLight, int biome) {
        return (block & 0xFFFF) | (sky & 15) << 16 | (blockLight & 15) << 20 | (biome & 0xFF) << 24;
    }

    public static int block(int cell) { return cell & 0xFFFF; }
    public static int sky(int cell) { return cell >>> 16 & 15; }
    public static int blockLight(int cell) { return cell >>> 20 & 15; }
    public static int biome(int cell) { return cell >>> 24; }
}
