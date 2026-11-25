package dev.elemore.iroh.vktest;

import net.vulkanmod.render.chunk.cull.CullBounds;
import net.vulkanmod.render.chunk.cull.SectionCullData;
import net.vulkanmod.render.chunk.cull.TerrainCull;

/**
 * P2-7b: the Java reference of terrain_cull.comp (double precision): the same four tests on the same inputs, plus a
 * "borderline" flag where float rounding on the GPU may legitimately decide the other way (within a small margin of a
 * decisive comparison). terrainCullReplay compares the GPU's kept set with this model's (mismatches allowed only where
 * the model says borderline) and, independently, the images.
 */
final class TerrainCullModel {
    static final int BINS = TerrainCull.BINS, RINGS = TerrainCull.RINGS;
    static final double RING_BLOCKS = 16, BIN_W = 2 * Math.PI / BINS;

    final TerrainCull.Frame f;
    final SectionCullData data;
    final long[] horizon = new long[BINS * RINGS]; // unsigned 32-bit keys
    /** a (ring, bin) whose content float rounding on the GPU may change (occluder span, ring or interval at a boundary) */
    final boolean[] uncertain = new boolean[BINS * RINGS];
    /** set by the last {@link #visible} call */
    boolean borderline;

    TerrainCullModel(TerrainCull.Frame f, SectionCullData data) {
        this.f = f;
        this.data = data;
        if ((f.techniques & TerrainCull.T_HORIZON) != 0) buildHorizon();
    }

    static double wrapPi(double a) {
        if (a > Math.PI) a -= 2 * Math.PI;
        if (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    static double[] span(double x0, double x1, double z0, double z1) {
        double ac = Math.atan2(0.5 * (z0 + z1), 0.5 * (x0 + x1));
        double d0 = wrapPi(Math.atan2(z0, x0) - ac), d1 = wrapPi(Math.atan2(z0, x1) - ac), d2 = wrapPi(Math.atan2(z1, x0) - ac), d3 = wrapPi(Math.atan2(z1, x1) - ac);
        return new double[]{ac + Math.min(Math.min(d0, d1), Math.min(d2, d3)), ac + Math.max(Math.max(d0, d1), Math.max(d2, d3))};
    }

    static double[] dist(double x0, double x1, double z0, double z1) {
        double nx = x0 > 0 ? x0 : (x1 < 0 ? -x1 : 0), nz = z0 > 0 ? z0 : (z1 < 0 ? -z1 : 0);
        double fx = Math.max(Math.abs(x0), Math.abs(x1)), fz = Math.max(Math.abs(z0), Math.abs(z1));
        return new double[]{Math.hypot(nx, nz), Math.hypot(fx, fz)};
    }

    void buildHorizon() {
        int R = f.horizonRadius, W = data.width, H = data.height;
        for (int dz = -R; dz <= R; dz++)
            for (int dx = -R; dx <= R; dx++) {
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) continue;
                int sx = f.camSecX + dx, sz = f.camSecZ + dz;
                double x0 = dx * 16 - (double) f.camFracX, z0 = dz * 16 - (double) f.camFracZ, x1 = x0 + 16, z1 = z0 + 16;
                double[] dd = dist(x0, x1, z0, z1);
                double dn = dd[0], df = dd[1];
                if (dn < 1) continue;
                int ring = (int) Math.ceil(df / RING_BLOCKS) - 1;
                boolean unsure = isNear(df / RING_BLOCKS, 1e-3);
                if (ring >= RINGS) continue;
                ring = Math.max(ring, 0);
                double[] sp = span(x0, x1, z0, z1);
                int bStart = (int) Math.ceil((sp[0] + 1e-3) / BIN_W), bEnd = (int) Math.floor((sp[1] - 1e-3) / BIN_W) - 1;
                // a span edge near a bin edge: the GPU may include one bin more or less
                boolean edge = isNear((sp[0] + 1e-3) / BIN_W, 1e-3) || isNear((sp[1] - 1e-3) / BIN_W, 1e-3);
                if (edge) {
                    for (int b = bStart - 1; b <= bEnd + 1; b++) for (int r = Math.max(ring - 1, 0); r < RINGS; r++) uncertain[r * BINS + Math.floorMod(b, BINS)] = true;
                }
                if (bEnd < bStart) continue;
                int xz = (sx & 0xFFF) << 8 | (sz & 0xFFF) << 20;
                int runStart = -1;
                for (int y = 0; y <= H; y++) {
                    boolean op = false;
                    if (y < H) {
                        int fl = data.word((Math.floorMod(sz, W) * H + y) * W + Math.floorMod(sx, W), 0);
                        op = (fl & 3) == 3 && (fl & 0xFFFFFF00) == xz;
                    }
                    if (op && runStart < 0) runStart = y;
                    if (!op && runStart >= 0) {
                        double Y0 = (data.minSecY + runStart - f.camSecY) * 16 - (double) f.camFracY + 0.0625;
                        double Y1 = (data.minSecY + y - f.camSecY) * 16 - (double) f.camFracY - 0.0625;
                        runStart = -1;
                        double tHigh = Math.min(Y1 / dn, Y1 / df), tLow = Math.max(Y0 / dn, Y0 / df);
                        if (tLow >= tHigh) continue;
                        double aH = Math.atan(tHigh) - 1e-4, aL = Math.atan(tLow) + 1e-4;
                        double fH = Math.floor((aH / Math.PI + 0.5) * 65535), fL = Math.ceil((aL / Math.PI + 0.5) * 65535);
                        if (fH < 0 || fL > 65535 || fL > fH) continue;
                        long key = ((long) fH << 16) | (65535 - (long) fL);
                        boolean qEdge = unsure || isNear(tLow - tHigh, 1e-6) || isNear((aH / Math.PI + 0.5) * 65535, 1e-2) || isNear((aL / Math.PI + 0.5) * 65535, 1e-2);
                        for (int b = bStart; b <= bEnd && b < bStart + TerrainCull.HB_MAX; b++) {
                            int i = ring * BINS + Math.floorMod(b, BINS);
                            horizon[i] = Math.max(horizon[i], key);
                            if (qEdge) for (int r = Math.max(ring - 1, 0); r < RINGS; r++) uncertain[r * BINS + Math.floorMod(b, BINS)] = true;
                        }
                    }
                }
            }
        for (int b = 0; b < BINS; b++) {
            long run = 0;
            boolean un = false;
            for (int r = 0; r < RINGS; r++) {
                run = Math.max(run, horizon[r * BINS + b]);
                horizon[r * BINS + b] = run;
                un |= uncertain[r * BINS + b];
                uncertain[r * BINS + b] = un;
            }
        }
    }

    /** v within eps of an integer (a floor/ceil that float rounding may move) */
    static boolean isNear(double v, double eps) {
        return Math.abs(v - Math.rint(v)) < eps;
    }

    private void near(double margin, double eps) {
        if (Math.abs(margin) < eps) borderline = true;
    }

    /** the shader's visible(): word = slot | facing << 28, fi = firstInstance, layer 0-2 */
    boolean visible(int word, int fi, int layer) {
        borderline = false;
        int slot = word & 0x0FFFFFFF, facing = word >>> 28;
        double sx = f.originRelX + (byte) fi * 16.0, sy = f.originRelY + (byte) (fi >> 16) * 16.0, sz = f.originRelZ + (byte) (fi >> 8) * 16.0;
        int[] b = CullBounds.unpack(data.word(slot, 1 + layer));
        double lx = sx + b[0] - 0.0625, ly = sy + b[1] - 0.0625, lz = sz + b[2] - 0.0625;
        double hx = sx + b[3] + 0.0625, hy = sy + b[4] + 0.0625, hz = sz + b[5] + 0.0625;
        int tech = f.techniques;
        if ((tech & TerrainCull.T_FACING) != 0 && facing < 6) {
            double v = switch (facing) { case 0 -> -lx; case 1 -> hx; case 2 -> -ly; case 3 -> hy; case 4 -> -lz; default -> hz; };
            near(v, 1e-3);
            boolean keep = facing % 2 == 0 ? v >= 0 : v > 0;
            if (!keep) return false;
        }
        if ((tech & TerrainCull.T_FRUSTUM) != 0 && outsideFrustum(lx, ly, lz, hx, hy, hz)) return false;
        if ((tech & TerrainCull.T_SCREEN) != 0 && screenEmpty(lx, ly, lz, hx, hy, hz)) return false;
        if ((tech & TerrainCull.T_HORIZON) != 0 && horizonHidden(lx, ly, lz, hx, hy, hz)) return false;
        return true;
    }

    double m(int col, int row) {
        return f.mvp[col * 4 + row];
    }

    boolean outsideFrustum(double lx, double ly, double lz, double hx, double hy, double hz) {
        for (int i = 0; i < 4; i++) {
            int r = i >> 1;
            double sg = (i & 1) == 0 ? 1 : -1;
            double px = m(0, 3) + sg * m(0, r), py = m(1, 3) + sg * m(1, r), pz = m(2, 3) + sg * m(2, r), pw = m(3, 3) + sg * m(3, r);
            double v = px * (px > 0 ? hx : lx) + py * (py > 0 ? hy : ly) + pz * (pz > 0 ? hz : lz) + pw;
            near(v, 1e-3 * (Math.abs(pw) + 1));
            if (v < 0) return true;
        }
        return false;
    }

    boolean screenEmpty(double lx, double ly, double lz, double hx, double hy, double hz) {
        double mnx = 1e30, mny = 1e30, mxx = -1e30, mxy = -1e30;
        for (int k = 0; k < 8; k++) {
            double x = (k & 1) != 0 ? hx : lx, y = (k & 2) != 0 ? hy : ly, z = (k & 4) != 0 ? hz : lz;
            double cx = m(0, 0) * x + m(1, 0) * y + m(2, 0) * z + m(3, 0);
            double cy = m(0, 1) * x + m(1, 1) * y + m(2, 1) * z + m(3, 1);
            double cw = m(0, 3) * x + m(1, 3) * y + m(2, 3) * z + m(3, 3);
            near(cw - 1e-3, 1e-4);
            if (cw <= 1e-3) return false;
            double sx = f.vpX + (cx / cw * 0.5 + 0.5) * f.vpW, sy = f.vpY + (cy / cw * 0.5 + 0.5) * f.vpH;
            mnx = Math.min(mnx, sx); mxx = Math.max(mxx, sx);
            mny = Math.min(mny, sy); mxy = Math.max(mxy, sy);
        }
        double mg = 0.0625;
        // decisive values: distance of the margins to the lattice
        for (double v : new double[]{mxx + mg - 0.5, mnx - mg - 0.5, mxy + mg - 0.5, mny - mg - 0.5})
            near(v - Math.rint(v), 1e-3);
        return Math.floor(mxx + mg - 0.5) < Math.ceil(mnx - mg - 0.5) || Math.floor(mxy + mg - 0.5) < Math.ceil(mny - mg - 0.5);
    }

    boolean horizonHidden(double lx, double ly, double lz, double hx, double hy, double hz) {
        if (lx <= 0 && hx >= 0 && lz <= 0 && hz >= 0) return false;
        double[] dd = dist(lx, hx, lz, hz);
        double dmin = dd[0], dmax = dd[1];
        near(dmin / RING_BLOCKS - Math.rint(dmin / RING_BLOCKS), 1e-3);
        int ring = (int) Math.floor(dmin / RING_BLOCKS) - 1;
        if (ring < 0) return false;
        ring = Math.min(ring, RINGS - 1);
        double aMax = Math.atan2(hy, hy >= 0 ? dmin : dmax), aMin = Math.atan2(ly, ly >= 0 ? dmax : dmin);
        double qMax = Math.ceil((aMax / Math.PI + 0.5) * 65535) + 1, qMin = Math.floor((aMin / Math.PI + 0.5) * 65535) - 1;
        double[] sp = span(lx, hx, lz, hz);
        int b0 = (int) Math.floor((sp[0] - 1e-3) / BIN_W), b1 = (int) Math.floor((sp[1] + 1e-3) / BIN_W);
        near((sp[0] - 1e-3) / BIN_W - Math.rint((sp[0] - 1e-3) / BIN_W), 1e-3);
        near((sp[1] + 1e-3) / BIN_W - Math.rint((sp[1] + 1e-3) / BIN_W), 1e-3);
        if (b1 - b0 >= 64) return false;
        for (int b = b0; b <= b1; b++) {
            long key = horizon[ring * BINS + Math.floorMod(b, BINS)];
            if (uncertain[ring * BINS + Math.floorMod(b, BINS)]) borderline = true;
            double qH = key >> 16, qL = 65535 - (key & 0xFFFF);
            near(qMin - qL, 3);
            near(qMax - qH, 3);
            if (!(qMin >= qL && qMax <= qH)) return false;
        }
        return true;
    }
}
