package dev.elemore.iroh.rt;

import java.util.Arrays;

/**
 * P5-3 per-section light grid (Minecraft-free; docs/P5-DESIGN.md section 15). Emissive quads are gathered at mesh time per
 * 16^3 section; every section cell keeps a candidate list of the lights in its 3x3x3 section neighbourhood, weighted by
 * power / (distance from the cell centre^2 + 4), as a per-cell CDF. The shader picks a candidate light from the receiver's
 * cell with probability {@code share} and from the global power CDF otherwise, and weights it by the MIXTURE pdf, so a
 * light outside the neighbourhood can still be chosen: there is no range cutoff and no bias, the grid only concentrates
 * candidates where they matter (the light touching a receiver is no longer picked 1 time in N).
 * <p>
 * Layout for the GPU: {@link #cells} = (first entry, count) per cell, cell index (y * gz + z) * gx + x; {@link #entLight} and
 * {@link #entCdf} = per entry the light index and the cell's cumulative probability, entries of a cell sorted by light index
 * (the shader binary-searches them to evaluate the mixture pdf of a globally picked light).
 * Updates: a section remesh changes its own emissive quads, which feed the 27 cells around it: {@link #rebuildAround}.
 */
public final class RtLightGrid {
    public final int gx, gy, gz;
    private final float[] centre;   // 3 per light
    private final float[] power;
    private int[][] lists;           // per cell: light indices (sorted)
    private float[][] cdfs;
    public int[] cells;
    public int[] entLight;
    public float[] entCdf;

    public RtLightGrid(int gx, int gy, int gz, float[] centres, float[] powers) {
        this.gx = gx;
        this.gy = gy;
        this.gz = gz;
        this.centre = centres;
        this.power = powers;
        lists = new int[gx * gy * gz][];
        cdfs = new float[gx * gy * gz][];
        for (int c = 0; c < lists.length; c++) buildCell(c);
        pack();
    }

    public int cellCount() {
        return gx * gy * gz;
    }

    private int cellOf(float x, float y, float z) {
        int cx = (int) Math.floor(x / 16), cy = (int) Math.floor(y / 16), cz = (int) Math.floor(z / 16);
        if (cx < 0 || cy < 0 || cz < 0 || cx >= gx || cy >= gy || cz >= gz) return -1;
        return (cy * gz + cz) * gx + cx;
    }

    private void buildCell(int c) {
        int cx = c % gx, cz = (c / gx) % gz, cy = c / (gx * gz);
        float mx = cx * 16 + 8, my = cy * 16 + 8, mz = cz * 16 + 8;
        int n = power.length;
        int[] idx = new int[n];
        float[] w = new float[n];
        int k = 0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int lc = cellOf(centre[i * 3], centre[i * 3 + 1], centre[i * 3 + 2]);
            if (lc < 0) continue;
            int lx = lc % gx, lz = (lc / gx) % gz, ly = lc / (gx * gz);
            if (Math.abs(lx - cx) > 1 || Math.abs(ly - cy) > 1 || Math.abs(lz - cz) > 1) continue;
            float dx = centre[i * 3] - mx, dy = centre[i * 3 + 1] - my, dz = centre[i * 3 + 2] - mz;
            float wt = power[i] / (dx * dx + dy * dy + dz * dz + 4f);
            if (!(wt > 0)) continue;
            idx[k] = i;
            w[k++] = wt;
            sum += wt;
        }
        lists[c] = Arrays.copyOf(idx, k);
        float[] cdf = new float[k];
        double acc = 0;
        for (int j = 0; j < k; j++) {
            acc += w[j] / sum;
            cdf[j] = j == k - 1 ? 1f : (float) acc;
        }
        cdfs[c] = cdf;
    }

    private void pack() {
        int total = 0;
        for (int[] l : lists) total += l.length;
        cells = new int[lists.length * 2];
        entLight = new int[total];
        entCdf = new float[total];
        int o = 0;
        for (int c = 0; c < lists.length; c++) {
            cells[c * 2] = o;
            cells[c * 2 + 1] = lists[c].length;
            System.arraycopy(lists[c], 0, entLight, o, lists[c].length);
            System.arraycopy(cdfs[c], 0, entCdf, o, cdfs[c].length);
            o += lists[c].length;
        }
    }

    /**
     * A section's emissive quads changed (remesh, a torch placed or broken): new light arrays, then only the 27 cells around
     * section (sx, sy, sz) are rebuilt and the GPU arrays repacked. Returns the number of cells rebuilt.
     */
    public int rebuildAround(int sx, int sy, int sz, float[] centres, float[] powers) {
        // light indices must stay stable: in game each section owns a fixed slot range of the light list (a removed quad
        // leaves a power-0 slot that is never picked; a full rebuild compacts), so a remesh never renumbers other lights
        if (centres.length != this.centre.length) throw new IllegalArgumentException("light count changed: rebuild the grid (indices moved)");
        System.arraycopy(centres, 0, this.centre, 0, centres.length);
        System.arraycopy(powers, 0, this.power, 0, powers.length);
        int n = 0;
        for (int y = sy - 1; y <= sy + 1; y++)
            for (int z = sz - 1; z <= sz + 1; z++)
                for (int x = sx - 1; x <= sx + 1; x++) {
                    if (x < 0 || y < 0 || z < 0 || x >= gx || y >= gy || z >= gz) continue;
                    buildCell((y * gz + z) * gx + x);
                    n++;
                }
        pack();
        return n;
    }

    /** Entries (bytes on the GPU: 16 per cell + 8 per entry). */
    public long gpuBytes() {
        return cellCount() * 16L + entLight.length * 8L;
    }
}
