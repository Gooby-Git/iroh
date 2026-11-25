// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

/**
 * Storage-only clean-up of cells nothing can ever show (2026-11-04, to shrink the store). A cell that is solid and has a
 * solid cell on all six sides inside its section has no face in any mesh (faces are only made against open cells), is never
 * "exposed" to the downsampler, and its light is zero. Its exact block only matters when a coarser level picks the most
 * common solid of eight children none of which is exposed, and that coarse cell is itself shown, which is rare and far away.
 * Underground such cells hold most of a section's variety (stone kinds, ores, dirt pockets), so they become the section's
 * most common hidden block, with no light, keeping their biome. Cells on the section's faces are never touched: their
 * outside neighbours live in another section. The hash of a section is still taken from its cells before this.
 */
public final class Hidden {
    private Hidden() {}

    /** @return {@code cells} itself when nothing is hidden, otherwise a normalised copy */
    public static int[] normalize(int[] cells, ClassTable classes) {
        if (cells.length != Section.VOLUME || Section.uniformValue(cells) != null) return cells; // uniform: stored as one word anyway
        int size = Section.SIZE;
        boolean[] solid = new boolean[cells.length];
        int last = -1;
        boolean lastSolid = false;
        for (int i = 0; i < cells.length; i++) {
            int b = Cell.block(cells[i]);
            if (b != last) {
                last = b;
                lastSolid = classes.classOf(b) == BlockClass.SOLID;
            }
            solid[i] = lastSolid;
        }
        int dy = size * size, dz = size;
        boolean[] hidden = new boolean[cells.length];
        int[] counts = new int[65536];
        int any = 0, top = -1;
        for (int y = 1; y < size - 1; y++)
            for (int z = 1; z < size - 1; z++)
                for (int x = 1; x < size - 1; x++) {
                    int i = Section.index(x, y, z);
                    if (!solid[i] || !solid[i - 1] || !solid[i + 1] || !solid[i - dz] || !solid[i + dz] || !solid[i - dy] || !solid[i + dy]) continue;
                    hidden[i] = true;
                    any++;
                    int b = Cell.block(cells[i]);
                    if (++counts[b] > (top < 0 ? 0 : counts[top]) || counts[b] == counts[top] && b < top) top = b;
                }
        if (any == 0) return cells;
        int[] out = cells.clone();
        for (int i = 0; i < cells.length; i++)
            if (hidden[i]) out[i] = top | cells[i] & 0xFF000000;
        return out;
    }
}
