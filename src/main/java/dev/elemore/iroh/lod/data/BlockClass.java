// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

/**
 * How a block state counts when cells are merged and meshed. Kept as a byte per block id
 * ({@link ClassTable}) so the downsampler and the GPU mesher never look at Minecraft objects.
 */
public final class BlockClass {
    /** Air, cave air, void air. */
    public static final int AIR = 0;
    /** No collision and not a surface: flowers, grass, torches, rails. Shapes count them as air; their light is kept. */
    public static final int DECOR = 1;
    /** Water and lava, plus the plants that only exist in water (kelp, seagrass, bubble columns). */
    public static final int FLUID = 2;
    /** Everything with a body: stone, logs, leaves, glass, stairs, snow layers. */
    public static final int SOLID = 3;

    private BlockClass() {}

    public static boolean open(int cls) {
        return cls <= DECOR;
    }
}
