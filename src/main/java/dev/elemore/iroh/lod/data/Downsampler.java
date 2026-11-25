// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

/**
 * Builds one level-(L+1) section from the eight level-L sections it covers (DESIGN.md section 4).
 * Each parent cell takes the 2 x 2 x 2 children under it and keeps what a viewer would see from outside:
 *
 * <ol>
 *   <li>An <em>exposed</em> solid child (open air or decoration above it or beside it) wins: the highest one,
 *       then the most common block among those. Thin walls, roofs and canopies survive at distance.
 *       Water next to a solid does not expose it, so lake beds stay under their lakes.</li>
 *   <li>Otherwise a fluid wins if at least half the children are open, or if fluids are at least as many as solids.</li>
 *   <li>Otherwise, with at least half open, the cell is air.</li>
 *   <li>Otherwise the most common solid.</li>
 * </ol>
 * Sky light is the brightest non-solid child, block light the brightest child (lit windows survive), and the
 * biome the most common among the non-open children (all of them when every child is open). Ties always go to
 * the lower id, so the server and the GPU version agree bit for bit.
 *
 * <p>Neighbours outside the eight children are unknown and count as open: a section edge errs towards
 * keeping a surface rather than losing one.</p>
 */
public final class Downsampler {
    private final ClassTable classes;
    private final int[][] kids = new int[8][];
    private final int[] cell = new int[8], cls = new int[8], fy = new int[8];
    private final int[] cand = new int[8];

    public Downsampler(ClassTable classes) {
        this.classes = classes;
    }

    /**
     * @param children the eight child sections by {@link SectionKey#octant()}; {@code null} is open air
     * @return the parent's cells
     */
    public int[] downsample(int[][] children) {
        for (int k = 0; k < 8; k++) kids[k] = children[k];
        int[] out = new int[Section.VOLUME];
        for (int py = 0; py < Section.SIZE; py++)
            for (int pz = 0; pz < Section.SIZE; pz++)
                for (int px = 0; px < Section.SIZE; px++)
                    out[Section.index(px, py, pz)] = merge(px * 2, py * 2, pz * 2);
        return out;
    }

    private int merge(int bx, int by, int bz) {
        int open = 0, fluids = 0, solids = 0, exposedTop = -1;
        for (int k = 0; k < 8; k++) {
            int x = bx + (k & 1), y = by + (k >> 2 & 1), z = bz + (k >> 1 & 1);
            int c = fine(x, y, z);
            int t = classes.classOf(Cell.block(c));
            cell[k] = c;
            cls[k] = t;
            fy[k] = -1;
            if (BlockClass.open(t)) open++;
            else if (t == BlockClass.FLUID) fluids++;
            else {
                solids++;
                if (exposed(x, y, z)) {
                    fy[k] = y;
                    if (y > exposedTop) exposedTop = y;
                }
            }
        }

        int block;
        if (exposedTop >= 0) {
            int n = 0;
            for (int k = 0; k < 8; k++) if (fy[k] == exposedTop) cand[n++] = Cell.block(cell[k]);
            block = mostCommon(cand, n);
        } else if (fluids > 0 && (open >= 4 || fluids >= solids)) {
            block = mostCommonOfClass(BlockClass.FLUID);
        } else if (open >= 4) {
            block = Cell.AIR;
        } else {
            block = mostCommonOfClass(BlockClass.SOLID);
        }

        int sky = 0, light = 0;
        for (int k = 0; k < 8; k++) {
            if (cls[k] != BlockClass.SOLID) sky = Math.max(sky, Cell.sky(cell[k]));
            light = Math.max(light, Cell.blockLight(cell[k]));
        }
        int nb = 0;
        for (int k = 0; k < 8; k++) if (!BlockClass.open(cls[k])) cand[nb++] = Cell.biome(cell[k]);
        if (nb == 0) for (int k = 0; k < 8; k++) cand[nb++] = Cell.biome(cell[k]);
        int biome = mostCommon(cand, nb);
        return Cell.of(block, sky, light, biome);
    }

    private int mostCommonOfClass(int wanted) {
        int n = 0;
        for (int k = 0; k < 8; k++) if (cls[k] == wanted) cand[n++] = Cell.block(cell[k]);
        return mostCommon(cand, n);
    }

    /** Most frequent value of {@code v[0..n)}, ties to the lower value. */
    static int mostCommon(int[] v, int n) {
        int best = v[0], bestCount = 0;
        for (int i = 0; i < n; i++) {
            int count = 0;
            for (int j = 0; j < n; j++) if (v[j] == v[i]) count++;
            if (count > bestCount || count == bestCount && v[i] < best) {
                best = v[i];
                bestCount = count;
            }
        }
        return best;
    }

    /** True when the solid at fine (x, y, z) has open space above it or on any side. */
    private boolean exposed(int x, int y, int z) {
        return openAt(x, y + 1, z) || openAt(x + 1, y, z) || openAt(x - 1, y, z) || openAt(x, y, z + 1) || openAt(x, y, z - 1);
    }

    private boolean openAt(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= 64 || y >= 64 || z >= 64) return true;
        return BlockClass.open(classes.classOf(Cell.block(fine(x, y, z))));
    }

    /** Cell at fine coordinates 0..63 across the eight children. */
    private int fine(int x, int y, int z) {
        int[] child = kids[(y >> 5) << 2 | (z >> 5) << 1 | (x >> 5)];
        return child == null ? Cell.OPEN_AIR : child[Section.index(x & 31, y & 31, z & 31)];
    }
}
