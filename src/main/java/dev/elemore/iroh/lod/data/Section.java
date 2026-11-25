// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

/**
 * A cube of 32 x 32 x 32 cells. At level L each cell covers 2^L blocks per axis.
 * Cells are indexed y-major: {@code (y << 10) | (z << 5) | x}, the order the GPU unpacks them in.
 */
public final class Section {
    public static final int SIZE = 32;
    public static final int SHIFT = 5;
    public static final int VOLUME = SIZE * SIZE * SIZE;
    public static final int MAX_LEVEL = 4;

    private Section() {}

    public static int index(int x, int y, int z) {
        return y << 10 | z << 5 | x;
    }

    /** Blocks covered by one section edge at this level. */
    public static int span(int level) {
        return SIZE << level;
    }

    public static int[] filled(int cell) {
        int[] cells = new int[VOLUME];
        java.util.Arrays.fill(cells, cell);
        return cells;
    }

    /** The single value of a uniform section, or {@code null} when the cells differ. */
    public static Integer uniformValue(int[] cells) {
        int first = cells[0];
        for (int i = 1; i < cells.length; i++) if (cells[i] != first) return null;
        return first;
    }
}
