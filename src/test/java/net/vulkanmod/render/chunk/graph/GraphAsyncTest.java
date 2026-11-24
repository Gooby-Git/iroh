package net.vulkanmod.render.chunk.graph;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * P2-6 (L3) offline oracle: the async visibility graph against SectionGraph's synchronous walk.
 * ./gradlew graphAsyncTest ; last line "# graphasync PASS n/n".
 *
 * <p>World: a generated section grid (terrain, overhangs, caves) at 8^3 cells per section; per-section visibility
 * by flood fill (RenderSection.visibility layout: byte r = faces seen entering through face r), completely-empty
 * flag. Streaming: every section starts uncompiled (visibility 0, empty) and compiles nearest first over the frames;
 * edits dig and fill cells near the camera (openings and closings). The "sync walk" is {@link LegacyWalk}, a line by
 * line mirror of SectionGraph.updateRenderChunks with frustum pruning (first-arrival rule, byte steps).
 *
 * <p>Recorded camera paths (fixed seeds): stand-spin (fast turns up to 180 degrees per frame), glide (0.3 / 1.5 /
 * 4 blocks per frame with flicks), cave, hop (teleports). Worker latency 0, 1 and 3 frames, a racing mode in which
 * uploads land in the middle of the worker's walk, and a real worker thread.
 *
 * <p>Checks: every frame AsyncVisibility serves, its frustum-filtered set contains the sync walk's set (0 missing).
 * Exactness: the worker's result equals a synchronous run of the same walk; relax after openings equals a fresh walk.
 * Negative controls (must FAIL to show the oracle bites): the old graph cache rule (frustum-free walk with the
 * first-arrival rule) and the async graph without opening repair.
 */
public final class GraphAsyncTest {
    static int pass, fail;

    static boolean check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
        return ok;
    }

    public static void main(String[] args) {
        exactness();
        negativeControls();
        for (long seed : new long[]{11, 23}) {
            for (String path : new String[]{"stand-spin", "glide-0.3", "glide-1.5", "glide-4", "cave", "hop"}) {
                for (int latency : new int[]{0, 1, 3}) {
                    Stats st = simulate(seed, 16, path, latency, false, false, false);
                    check(String.format("%s seed %d latency %d: 0 missing (visible %d, reached %d) on %d served frames (fallback %d, extra %.1f%%, relaxed %d, openings %d)",
                            path, seed, latency, st.missing, st.missingReached, st.served, st.fallback, st.extraPct(), st.relaxed, st.openings),
                            st.missing == 0 && st.missingReached == 0 && st.served > 0);
                }
                Stats race = simulate(seed, 16, path, 1, true, false, false);
                check(String.format("%s seed %d racing uploads mid-walk: 0 missing (visible %d, reached %d) on %d served frames (fallback %d, extra %.1f%%)",
                        path, seed, race.missing, race.missingReached, race.served, race.fallback, race.extraPct()),
                        race.missing == 0 && race.missingReached == 0 && race.served > 0);
            }
        }
        threaded();
        timing();
        System.out.println("# graphasync " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ world

    /** Section grid with generated terrain. Index = (z * H + y) * W + x, like SectionGrid.getChunkIndex. */
    static final class World implements VisibilityWalk.Graph {
        static final int C = 8; // cells per section edge
        final int W, H;
        final long[] vis;
        final boolean[] empty;
        final long[] realVis;
        final boolean[] realEmpty;
        final boolean[] compiled;
        final boolean[][] cells; // per section C^3, true = solid
        final int[] surfaceCell; // per column (cell x, z): surface height in cells
        /** reads before the hook fires (racing mode), -1 = off */
        int hookEvery = -1, reads;
        Runnable hook;

        World(int W, int H, long seed) {
            this.W = W;
            this.H = H;
            int n = W * H * W;
            this.vis = new long[n];
            this.empty = new boolean[n];
            this.realVis = new long[n];
            this.realEmpty = new boolean[n];
            this.compiled = new boolean[n];
            this.cells = new boolean[n][];
            Arrays.fill(this.empty, true);
            int cw = W * C, ch = H * C;
            SplittableRandom rnd = new SplittableRandom(seed);
            double[] ax = new double[6], az = new double[6], px = new double[6], pz = new double[6];
            for (int k = 0; k < 6; k++) {
                ax[k] = 0.01 + rnd.nextDouble() * 0.08;
                az[k] = 0.01 + rnd.nextDouble() * 0.08;
                px[k] = rnd.nextDouble() * 7;
                pz[k] = rnd.nextDouble() * 7;
            }
            this.surfaceCell = new int[cw * cw];
            for (int x = 0; x < cw; x++)
                for (int z = 0; z < cw; z++) {
                    double h = 0;
                    for (int k = 0; k < 6; k++) h += Math.sin(x * ax[k] + px[k]) * Math.cos(z * az[k] + pz[k]) / (k + 1);
                    this.surfaceCell[x * cw + z] = (int) Math.max(4, Math.min(ch - 6, ch * 0.45 + h * ch * 0.18));
                }
            double[] sx = table(cw, rnd), sy = table(ch, rnd), sz = table(cw, rnd), sxz = table(2 * cw, rnd);
            for (int i = 0; i < n; i++) this.cells[i] = new boolean[C * C * C];
            for (int x = 0; x < cw; x++)
                for (int z = 0; z < cw; z++) {
                    int surf = this.surfaceCell[x * cw + z];
                    boolean overhang = sxz[x + z] > 0.8;
                    for (int y = 0; y < ch; y++) {
                        boolean solid = y < surf || (overhang && y > surf + 3 && y < surf + 6);
                        if (solid && y > 1 && sx[x] + sy[y] + sz[z] + sxz[x + z] > 1.6)
                            solid = false; // caves
                        if (solid) setCell(x, y, z, true);
                    }
                }
            for (int i = 0; i < n; i++) recompute(i);
        }

        private static double[] table(int len, SplittableRandom rnd) {
            double f = 0.15 + rnd.nextDouble() * 0.25, p = rnd.nextDouble() * 6;
            double[] t = new double[len];
            for (int i = 0; i < len; i++) t[i] = Math.sin(i * f + p) * 0.6 + Math.sin(i * f * 2.3 + p * 1.7) * 0.4;
            return t;
        }

        int index(int x, int y, int z) {
            return (z * this.H + y) * this.W + x;
        }

        void setCell(int x, int y, int z, boolean solid) {
            int i = index(x / C, y / C, z / C);
            this.cells[i][((x % C) * C + (y % C)) * C + (z % C)] = solid;
        }

        boolean cell(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= this.W * C || y >= this.H * C || z >= this.W * C) return false;
            int i = index(x / C, y / C, z / C);
            return this.cells[i][((x % C) * C + (y % C)) * C + (z % C)];
        }

        /** Flood fill: the section's visibility and emptiness as a builder would produce them. */
        void recompute(int i) {
            boolean[] c = this.cells[i];
            boolean any = false;
            for (boolean b : c) any |= b;
            this.realEmpty[i] = !any;
            int[] comp = new int[C * C * C];
            int[] stack = new int[C * C * C];
            long v = 0;
            int label = 0;
            for (int s = 0; s < c.length; s++) {
                if (c[s] || comp[s] != 0) continue;
                label++;
                int top = 0, faces = 0;
                stack[top++] = s;
                comp[s] = label;
                while (top > 0) {
                    int p = stack[--top];
                    int x = p / (C * C), y = (p / C) % C, z = p % C;
                    if (y == 0) faces |= 1 << GraphDirections.DOWN;
                    if (y == C - 1) faces |= 1 << GraphDirections.UP;
                    if (z == 0) faces |= 1 << GraphDirections.NORTH;
                    if (z == C - 1) faces |= 1 << GraphDirections.SOUTH;
                    if (x == 0) faces |= 1 << GraphDirections.WEST;
                    if (x == C - 1) faces |= 1 << GraphDirections.EAST;
                    int[] nb = {x > 0 ? p - C * C : -1, x < C - 1 ? p + C * C : -1, y > 0 ? p - C : -1, y < C - 1 ? p + C : -1,
                            z > 0 ? p - 1 : -1, z < C - 1 ? p + 1 : -1};
                    for (int q : nb) {
                        if (q < 0 || c[q] || comp[q] != 0) continue;
                        comp[q] = label;
                        stack[top++] = q;
                    }
                }
                for (int r = 0; r < 6; r++)
                    if ((faces & (1 << r)) != 0)
                        v |= ((long) faces & 0x3F) << (r << 3);
            }
            this.realVis[i] = v;
        }

        /** Upload on the render thread: what CompileResult.updateSection does through RenderSection's setters. */
        void upload(int i) {
            this.compiled[i] = true;
            long oldV = this.vis[i];
            boolean oldE = this.empty[i];
            this.vis[i] = this.realVis[i];
            if (oldV != this.vis[i]) AsyncVisibility.inputChanged(i, VisibilityWalk.opens(oldV, false, this.vis[i], false));
            this.empty[i] = this.realEmpty[i];
            if (oldE != this.empty[i]) AsyncVisibility.inputChanged(i, this.empty[i]);
        }

        @Override
        public int size() {
            return this.vis.length;
        }

        @Override
        public int adj(int i, int dir) {
            int x = i % this.W, y = (i / this.W) % this.H, z = i / (this.W * this.H);
            switch (dir) {
                case GraphDirections.DOWN: return y > 0 ? i - this.W : -1;
                case GraphDirections.UP: return y < this.H - 1 ? i + this.W : -1;
                case GraphDirections.NORTH: return z > 0 ? i - this.W * this.H : -1;
                case GraphDirections.SOUTH: return z < this.W - 1 ? i + this.W * this.H : -1;
                case GraphDirections.WEST: return x > 0 ? i - 1 : -1;
                default: return x < this.W - 1 ? i + 1 : -1;
            }
        }

        @Override
        public byte adjDirs(int i) {
            int m = 0;
            for (int d = 0; d < 6; d++) if (adj(i, d) >= 0) m |= 1 << d;
            return (byte) m;
        }

        @Override
        public long vis(int i) {
            if (this.hookEvery > 0 && ++this.reads % this.hookEvery == 0 && this.hook != null) this.hook.run();
            return this.vis[i];
        }

        @Override
        public boolean empty(int i) {
            return this.empty[i];
        }

        int areasX() { return (this.W + 7) >> 3; }
        int areasY() { return (this.H + 7) >> 3; }

        /** ChunkArea of 8^3 sections, sub-cube of 2^3 sections (ChunkArea.getFrustumIndex bit layout). */
        @Override
        public int cullKey(int i) {
            int x = i % this.W, y = (i / this.W) % this.H, z = i / (this.W * this.H);
            int area = ((z >> 3) * areasY() + (y >> 3)) * areasX() + (x >> 3);
            int dx = x & 7, dy = y & 7, dz = z & 7;
            int sub = ((dx >> 2) << 5) | ((dy >> 2) << 4) | ((dz >> 2) << 3) | (((dx >> 1) & 1) << 2) | (((dy >> 1) & 1) << 1) | ((dz >> 1) & 1);
            return (area << 6) | sub;
        }
    }

    // ------------------------------------------------------------------ sync walk mirror

    /** Line by line mirror of SectionGraph.update's full walk (updateRenderChunks / addNode / initFirstNode). */
    static final class LegacyWalk {
        final World w;
        final short[] lastFrame;
        final byte[] mainDir, sourceDirs, directions, steps, directionChanges;
        short frame;
        final int[] queue;
        final boolean prune;
        /** RQ-P26-b measurement: leave through faces seen from ANY entry face (vanilla), still frustum pruned. */
        boolean union;

        LegacyWalk(World w, boolean prune) {
            this.w = w;
            int n = w.size();
            this.lastFrame = new short[n];
            Arrays.fill(this.lastFrame, (short) -1);
            this.mainDir = new byte[n];
            this.sourceDirs = new byte[n];
            this.directions = new byte[n];
            this.steps = new byte[n];
            this.directionChanges = new byte[n];
            this.queue = new int[n];
            this.prune = prune;
        }

        /** Visible set (processed and in frustum) as a boolean mask; returns the count. */
        int run(int start, int maxDc, Frustum f, boolean[] out) {
            Arrays.fill(out, false);
            this.frame++;
            int head = 0, tail = 0, count = 0;
            // initFirstNode
            this.mainDir[start] = 7;
            this.sourceDirs[start] = (byte) (1 << 7);
            this.directions[start] = (byte) 0xFF;
            this.lastFrame[start] = this.frame;
            this.directionChanges[start] = 0;
            this.steps[start] = 0;
            this.queue[tail++] = start;
            while (head < tail) {
                int i = this.queue[head++];
                boolean inFrustum = f.contains(this.w, i);
                if (!inFrustum && this.prune) continue;
                if (this.directionChanges[i] > maxDc) continue;
                if (inFrustum) {
                    out[i] = true;
                    count++;
                }
                // getVisibilityDirs: row of the face opposite mainDir; the start node's row 6 is all faces (initVisibility)
                int row = this.mainDir[i] == 7 ? 0x3F : this.union ? VisibilityWalk.exitsFor(this.w.vis[i], this.sourceDirs[i])
                        : (int) (this.w.vis[i] >> (VisibilityWalk.opposite(this.mainDir[i]) << 3)) & 0xFF;
                byte dirs = (byte) (row & this.directions[i]);
                dirs &= this.w.adjDirs(i);
                for (int d : new int[]{GraphDirections.DOWN, GraphDirections.UP, GraphDirections.NORTH, GraphDirections.SOUTH,
                        GraphDirections.WEST, GraphDirections.EAST}) {
                    if ((dirs & (1 << d)) == 0) continue;
                    int j = this.w.adj(i, d);
                    // addNode
                    if (this.lastFrame[j] != this.frame) {
                        this.lastFrame[j] = this.frame;
                        this.mainDir[j] = (byte) d;
                        this.sourceDirs[j] = (byte) (1 << d);
                        byte st = (byte) (this.steps[i] + 1);
                        this.directionChanges[j] = (byte) (st < 10 ? 0 : 127);
                        this.steps[j] = st;
                        this.directions[j] = (byte) (this.directions[i] & ~(1 << VisibilityWalk.opposite(d)));
                        this.queue[tail++] = j;
                    }
                    this.sourceDirs[j] |= (byte) (1 << d);
                    boolean increase = (this.sourceDirs[i] & 1 << d) == 0 && !this.w.empty[i];
                    byte dc = increase ? (byte) (this.directionChanges[i] + 1) : this.directionChanges[i];
                    this.directionChanges[j] = dc < this.directionChanges[j] ? dc : this.directionChanges[j];
                }
            }
            return count;
        }
    }

    // ------------------------------------------------------------------ frustum / camera

    /** Camera frustum with SectionGraph's culling layout: per-frame area / sub-cube verdicts, per-section test on INTERSECT. */
    static final class Frustum {
        final FrustumIntersection fi = new FrustumIntersection();
        byte[] flat = new byte[0];

        Frustum set(World w, double x, double y, double z, float yawDeg, float pitchDeg, float far) {
            // SectionGraph: offsetToFullyIncludeCameraCube(8) moves the apex back so the camera's cube is inside
            double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
            double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
            double back = 8 * Math.sqrt(3) + 1;
            Matrix4f m = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, far)
                    .rotateX((float) pitch).rotateY((float) (yaw + Math.PI))
                    .translate((float) -(x - fx * back), (float) -(y - fy * back), (float) -(z - fz * back));
            this.fi.set(m);
            prepare(w);
            return this;
        }

        /** SectionGrid.updateFrustumVisibility: area box, then 2^3 halves, then 2^3 quarters of each half. */
        void prepare(World w) {
            int ax = w.areasX(), ay = w.areasY();
            if (this.flat.length != ax * ay * ax * 64) this.flat = new byte[ax * ay * ax * 64];
            for (int az = 0; az < ax; az++)
                for (int yy = 0; yy < ay; yy++)
                    for (int xx = 0; xx < ax; xx++) {
                        int base = ((az * ay + yy) * ax + xx) << 6;
                        float ox = xx * 128, oy = yy * 128, oz = az * 128;
                        int res = this.fi.intersectAab(ox, oy, oz, ox + 128, oy + 128, oz + 128);
                        if (res != FrustumIntersection.INTERSECT) {
                            Arrays.fill(this.flat, base, base + 64, (byte) res);
                            continue;
                        }
                        for (int h = 0; h < 8; h++) {
                            int x1 = h >> 2, y1 = (h >> 1) & 1, z1 = h & 1;
                            float hx = ox + x1 * 64, hy = oy + y1 * 64, hz = oz + z1 * 64;
                            int hb = base + (x1 << 5) + (y1 << 4) + (z1 << 3);
                            int r2 = this.fi.intersectAab(hx, hy, hz, hx + 64, hy + 64, hz + 64);
                            for (int q = 0; q < 8; q++) {
                                int x2 = q >> 2, y2 = (q >> 1) & 1, z2 = q & 1;
                                this.flat[hb + q] = (byte) (r2 != FrustumIntersection.INTERSECT ? r2
                                        : this.fi.intersectAab(hx + x2 * 32, hy + y2 * 32, hz + z2 * 32, hx + x2 * 32 + 32, hy + y2 * 32 + 32, hz + z2 * 32 + 32));
                            }
                        }
                    }
        }

        /** SectionGraph.notInFrustum, negated. */
        boolean contains(World w, int i) {
            return containsKeyed(w, i, w.cullKey(i));
        }

        boolean containsKeyed(World w, int i, int key) {
            byte res = this.flat[key];
            if (res > FrustumIntersection.INTERSECT) return false;
            if (res == FrustumIntersection.INTERSECT) {
                int x = i % w.W, y = (i / w.W) % w.H, z = i / (w.W * w.H);
                return this.fi.testAab(x * 16, y * 16, z * 16, x * 16 + 16, y * 16 + 16, z * 16 + 16);
            }
            return true;
        }
    }

    static final class Stats {
        long served, fallback, missing, legacyTotal, extra, relaxed, openings, missingReached;

        double extraPct() {
            return this.legacyTotal == 0 ? 0 : 100.0 * this.extra / this.legacyTotal;
        }
    }

    record Cam(double x, double y, double z, float yaw, float pitch) {}

    /** Recorded camera path, deterministic from the seed. Positions in blocks. */
    static List<Cam> path(World w, String name, long seed) {
        SplittableRandom r = new SplittableRandom(seed * 31 + name.hashCode());
        List<Cam> out = new ArrayList<>();
        int cw = w.W * World.C;
        double cx = w.W * 8, cz = w.W * 8;
        double surfY = w.surfaceCell[(cw / 2) * cw + cw / 2] * 2;
        switch (name) {
            case "stand-spin" -> {
                float yaw = 0;
                for (int f = 0; f < 300; f++) {
                    yaw += new float[]{170, -90, 45, 180, -135, 10, 0, 90}[f % 8] + (float) r.nextDouble(-5, 5);
                    out.add(new Cam(cx + 0.5, surfY + 3.5, cz + 0.5, yaw, (float) r.nextDouble(-80, 80)));
                }
            }
            case "glide-0.3", "glide-1.5", "glide-4" -> {
                double v = Double.parseDouble(name.substring(6));
                double x = w.W * 3, z = w.W * 3, y = surfY + 30;
                float yaw = -45;
                for (int f = 0; f < 300; f++) {
                    float look = yaw + (float) (Math.sin(f * 0.07) * 60) + (f % 50 == 49 ? 180 : 0);
                    x += v * 0.7071;
                    z += v * 0.7071;
                    y += Math.sin(f * 0.05) * v * 0.3;
                    x = Math.min(x, w.W * 16 - 20);
                    z = Math.min(z, w.W * 16 - 20);
                    out.add(new Cam(x, y, z, look, (float) (Math.sin(f * 0.03) * 30)));
                }
            }
            case "cave" -> {
                // walk through air cells under the surface near the centre
                int px = cw / 2, pz = cw / 2, py = -1;
                for (int y = 8; y < w.surfaceCell[px * cw + pz] - 2; y++)
                    if (!w.cell(px, y, pz)) { py = y; break; }
                if (py < 0) py = w.surfaceCell[px * cw + pz] - 3;
                float yaw = 0;
                for (int f = 0; f < 300; f++) {
                    int d = r.nextInt(6);
                    int nx = px + (d == 0 ? 1 : d == 1 ? -1 : 0), ny = py + (d == 2 ? 1 : d == 3 ? -1 : 0), nz = pz + (d == 4 ? 1 : d == 5 ? -1 : 0);
                    if (!w.cell(nx, ny, nz) && nx > 8 && nz > 8 && nx < cw - 8 && nz < cw - 8 && ny > 1) { px = nx; py = ny; pz = nz; }
                    yaw += (float) r.nextDouble(-120, 120);
                    out.add(new Cam(px * 2 + 1, py * 2 + 1, pz * 2 + 1, yaw, (float) r.nextDouble(-60, 60)));
                }
            }
            case "hop" -> {
                double x = cx, z = cz;
                for (int f = 0; f < 300; f++) {
                    if (f % 20 == 0) {
                        x = cx + r.nextDouble(-w.W * 4, w.W * 4);
                        z = cz + r.nextDouble(-w.W * 4, w.W * 4);
                    }
                    int col = Math.min(cw - 1, Math.max(0, (int) (x / 2))) * cw + Math.min(cw - 1, Math.max(0, (int) (z / 2)));
                    double y = w.surfaceCell[col] * 2 + 2 + Math.abs(Math.sin(f * 0.3)) * 6;
                    out.add(new Cam(x, y, z, (float) (f * 23 % 360), (float) r.nextDouble(-50, 50)));
                }
            }
            default -> throw new IllegalArgumentException(name);
        }
        return out;
    }

    /**
     * One recorded path. latency = frames between a request and its walk; racing = uploads of the next frame land in
     * the middle of the walk; skipReplay / mainDirCache = negative controls.
     */
    static Stats simulate(long seed, int rd, String pathName, int latency, boolean racing, boolean skipReplay, boolean mainDirCache) {
        int W = 2 * rd + 1, H = 16;
        World w = new World(W, H, seed);
        List<Cam> cams = path(w, pathName, seed);
        SplittableRandom r = new SplittableRandom(seed ^ 0x5EED);
        AsyncVisibility av = new AsyncVisibility(w, false);
        av.activate();
        av.setOn(true);
        AsyncVisibility.testSkipReplay = skipReplay;
        LegacyWalk legacy = new LegacyWalk(w, true);
        LegacyWalk cacheWalk = mainDirCache ? new LegacyWalk(w, false) : null;
        Frustum f = new Frustum();
        boolean[] want = new boolean[w.size()], got = new boolean[w.size()];
        int maxDc = 1; // advCulling 2 (default)
        Stats st = new Stats();
        // uncompiled sections in order of distance to the path start: streaming compiles nearest first
        Integer[] byDist = new Integer[w.size()];
        for (int i = 0; i < byDist.length; i++) byDist[i] = i;
        Cam c0 = cams.get(0);
        Arrays.sort(byDist, (a, b) -> Double.compare(dist2(w, a, c0), dist2(w, b, c0)));
        int[] next = {0};
        int postedFrame = -1;
        long reposition = 0;
        int lastSx = Integer.MIN_VALUE, lastSz = Integer.MIN_VALUE;
        long openingsBefore = av.openings;
        List<Runnable> nextFrameEvents = new ArrayList<>();
        for (int frame = 0; frame < cams.size(); frame++) {
            // events of this frame (some may already have run inside a racing walk)
            for (Runnable e : nextFrameEvents) e.run();
            nextFrameEvents.clear();
            List<Runnable> events = new ArrayList<>();
            int compiles = frame < 150 ? 40 + r.nextInt(80) : r.nextInt(6);
            for (int k = 0; k < compiles && next[0] < byDist.length; k++) {
                int i = byDist[next[0]++];
                events.add(() -> w.upload(i));
            }
            Cam cam = cams.get(frame);
            if (frame % 5 == 2) { // edit: dig or fill a few cells near the camera, then the section re-uploads
                boolean dig = r.nextBoolean();
                int bx = (int) (cam.x / 2) + r.nextInt(-12, 13), by = (int) (cam.y / 2) + r.nextInt(-12, 13), bz = (int) (cam.z / 2) + r.nextInt(-12, 13);
                events.add(() -> {
                    int cw = w.W * World.C, ch = w.H * World.C;
                    for (int dx = 0; dx < 4; dx++)
                        for (int dy = 0; dy < 4; dy++)
                            for (int dz = 0; dz < 4; dz++) {
                                int x = bx + dx, y = by + dy, z = bz + dz;
                                if (x >= 0 && y >= 0 && z >= 0 && x < cw && y < ch && z < cw) w.setCell(x, y, z, !dig);
                            }
                    int i = w.index(Math.min(w.W - 1, Math.max(0, bx / World.C)), Math.min(w.H - 1, Math.max(0, by / World.C)), Math.min(w.W - 1, Math.max(0, bz / World.C)));
                    w.recompute(i);
                    if (w.compiled[i]) w.upload(i);
                });
            }
            for (Runnable e : events) e.run();

            int sx = (int) Math.floor(cam.x / 16), sy = (int) Math.floor(cam.y / 16), sz = (int) Math.floor(cam.z / 16);
            if (sx != lastSx || sz != lastSz) { reposition++; lastSx = sx; lastSz = sz; } // SectionGrid.repositionCamera
            if (sx < 0 || sz < 0 || sx >= w.W || sz >= w.W || sy < 0 || sy >= w.H) continue; // outside: sync multi-start walk
            int start = w.index(sx, sy, sz);
            f.set(w, cam.x, cam.y, cam.z, cam.yaw, cam.pitch, rd * 16 + 64);
            int nLegacy = legacy.run(start, maxDc, f, want);

            boolean served;
            if (mainDirCache) {
                // the hitches graph cache: frustum-free walk with the first-arrival rule, filtered by the frustum
                cacheWalk.run(start, maxDc, f, got);
                served = true;
            } else {
                served = av.prepare(start, reposition, maxDc, frame * 4_000_000L);
                if (served) {
                    Arrays.fill(got, false);
                    VisibilityWalk.Result fr = av.front();
                    // entity/particle culling stamp (setLastFrame): every section the sync walk reached must be reached
                    boolean[] reachedA = new boolean[w.size()];
                    for (int k = 0; k < fr.reachedCount; k++) reachedA[fr.reachedList[k]] = true;
                    for (int i = 0; i < w.size(); i++)
                        if (legacy.lastFrame[i] == legacy.frame && !reachedA[i]) st.missingReached++;
                    for (int k = 0; k < fr.count; k++) {
                        int i = fr.order[k];
                        if (f.containsKeyed(w, i, fr.cull[i])) got[i] = true;
                    }
                }
            }
            if (served) {
                st.served++;
                st.legacyTotal += nLegacy;
                for (int i = 0; i < want.length; i++) {
                    if (want[i] && !got[i]) st.missing++;
                    if (got[i] && !want[i]) st.extra++;
                }
            } else {
                st.fallback++;
            }
            if (!mainDirCache) {
                // the worker: walk the pending request `latency` frames after it was posted
                if (postedFrame < 0) postedFrame = frame;
                if (frame - postedFrame >= latency) {
                    if (racing) {
                        // the next frame's uploads run during the walk (the render thread racing the worker)
                        List<Runnable> early = new ArrayList<>();
                        int k2 = 0;
                        while (k2 < 30 && next[0] < byDist.length) {
                            int i = byDist[next[0]++];
                            early.add(() -> w.upload(i));
                            k2++;
                        }
                        int[] at = {0};
                        w.hook = () -> { if (at[0] < early.size()) early.get(at[0]++).run(); };
                        w.hookEvery = 37;
                        av.runPendingWalk();
                        w.hookEvery = -1;
                        w.hook = null;
                        for (int k = at[0]; k < early.size(); k++) nextFrameEvents.add(early.get(k));
                    } else {
                        av.runPendingWalk();
                    }
                    postedFrame = -1;
                }
            }
        }
        AsyncVisibility.testSkipReplay = false;
        st.relaxed = av.relaxedSections;
        st.openings = av.openings - openingsBefore;
        av.setOn(false);
        return st;
    }

    static double dist2(World w, int i, Cam c) {
        int x = i % w.W, y = (i / w.W) % w.H, z = i / (w.W * w.H);
        double dx = x * 16 + 8 - c.x, dy = y * 16 + 8 - c.y, dz = z * 16 + 8 - c.z;
        return dx * dx + dy * dy + dz * dz;
    }

    // ------------------------------------------------------------------ exactness

    static void compiledAll(World w) {
        for (int i = 0; i < w.size(); i++) {
            w.compiled[i] = true;
            w.vis[i] = w.realVis[i];
            w.empty[i] = w.realEmpty[i];
        }
    }

    static void exactness() {
        World w = new World(25, 16, 5);
        compiledAll(w);
        int start = w.index(12, 8, 12);
        // 1. the worker's result equals a synchronous run of the same walk (order identical)
        AsyncVisibility av = new AsyncVisibility(w, false);
        av.activate();
        av.setOn(true);
        av.prepare(start, 1, 1, 0);
        av.runPendingWalk();
        boolean served = av.prepare(start, 1, 1, 1);
        VisibilityWalk.Result sync = new VisibilityWalk.Result(w.size());
        VisibilityWalk.walk(w, start, 1, sync);
        VisibilityWalk.Result fr = av.front();
        check("worker result == synchronous walk, same order (" + sync.count + " sections)", served && fr.count == sync.count
                && Arrays.equals(Arrays.copyOf(fr.order, fr.count), Arrays.copyOf(sync.order, sync.count)));

        // 2. superset of the sync walk at many view directions, from one result (turning never needs a new walk)
        LegacyWalk legacy = new LegacyWalk(w, true);
        Frustum f = new Frustum();
        boolean[] want = new boolean[w.size()];
        long missing = 0, extra = 0, total = 0;
        for (int yaw = 0; yaw < 360; yaw += 15)
            for (int pitch = -90; pitch <= 90; pitch += 30) {
                f.set(w, 12 * 16 + 8, 8 * 16 + 8, 12 * 16 + 8, yaw, pitch, 25 * 16);
                total += legacy.run(start, 1, f, want);
                for (int i = 0; i < want.length; i++) {
                    boolean g = fr.isProcessed(i) && f.contains(w, i);
                    if (want[i] && !g) missing++;
                    if (g && !want[i]) extra++;
                }
            }
        check(String.format("one result, 168 view directions: 0 missing vs sync walk (extra %.1f%%)", 100.0 * extra / total), missing == 0);
        // RQ-P26-b: how much of that extra is the exit rule alone (both frustum pruned)
        for (int[] spot : new int[][]{{12, 8, 12}, {12, 4, 12}, {6, 5, 18}}) {
            int st = w.index(spot[0], spot[1], spot[2]);
            LegacyWalk uni = new LegacyWalk(w, true);
            uni.union = true;
            boolean[] wantU = new boolean[w.size()];
            long base = 0, more = 0, fewer = 0;
            for (int yaw = 0; yaw < 360; yaw += 15)
                for (int pitch = -90; pitch <= 90; pitch += 30) {
                    f.set(w, spot[0] * 16 + 8, spot[1] * 16 + 8, spot[2] * 16 + 8, yaw, pitch, 25 * 16);
                    base += legacy.run(st, 1, f, want);
                    uni.run(st, 1, f, wantU);
                    for (int i = 0; i < want.length; i++) {
                        if (wantU[i] && !want[i]) more++;
                        if (want[i] && !wantU[i]) fewer++;
                    }
                }
            System.out.printf("info RQ-P26-b section (%d,%d,%d) %s: union exit rule (pruned) draws +%.2f%% sections vs first-arrival rule, %d fewer%n",
                    spot[0], spot[1], spot[2], w.empty[st] ? "air" : "terrain", 100.0 * more / Math.max(1, base), fewer);
        }

        // 3. relax after openings == fresh walk (as sets, and per-section state); closings leave a superset
        SplittableRandom r = new SplittableRandom(99);
        VisibilityWalk.Result rel = new VisibilityWalk.Result(w.size());
        int[] work = new int[w.size()], inWork = new int[w.size()];
        VisibilityWalk.walk(w, start, 1, rel);
        int ws = 1;
        boolean exact = true, sorted = true;
        for (int round = 0; round < 40; round++) {
            for (int k = 0; k < 25; k++) {
                int i = r.nextInt(w.size());
                long old = w.vis[i];
                w.vis[i] |= 1L << (r.nextInt(6) * 8 + r.nextInt(6)); // open a face pair
                if (r.nextInt(4) == 0) w.empty[i] = true;
                if (old != w.vis[i] || w.empty[i]) VisibilityWalk.relax(w, rel, i, work, inWork, ws += 2);
            }
            VisibilityWalk.mergeAdded(rel);
            VisibilityWalk.Result fresh = new VisibilityWalk.Result(w.size());
            VisibilityWalk.walk(w, start, 1, fresh);
            for (int i = 0; i < w.size(); i++) {
                if (rel.isProcessed(i) != fresh.isProcessed(i)) exact = false;
                else if (fresh.isProcessed(i) && (rel.src[i] != fresh.src[i] || rel.dc[i] != fresh.dc[i])) exact = false;
            }
            for (int k = 1; k < rel.count; k++) if (VisibilityWalk.level(rel, rel.order[k]) < VisibilityWalk.level(rel, rel.order[k - 1])) sorted = false;
            if (rel.count != fresh.count) exact = false;
        }
        check("relax after 1000 random openings == fresh walk (sets, source faces, dc)", exact);
        check("merged order stays in level order", sorted);
        // closings: old result is a superset of a fresh walk
        for (int k = 0; k < 400; k++) {
            int i = r.nextInt(w.size());
            w.vis[i] &= ~(1L << (r.nextInt(6) * 8 + r.nextInt(6)));
            w.empty[i] = false;
        }
        VisibilityWalk.Result fresh = new VisibilityWalk.Result(w.size());
        VisibilityWalk.walk(w, start, 1, fresh);
        boolean superset = true;
        for (int i = 0; i < w.size(); i++) if (fresh.isProcessed(i) && !rel.isProcessed(i)) superset = false;
        check("after 400 closings the old result is a superset of a fresh walk", superset);
        av.setOn(false);
    }

    static void negativeControls() {
        long cacheMissing = 0, noReplayMissing = 0;
        for (long seed : new long[]{11, 23})
            for (String p : new String[]{"stand-spin", "glide-1.5", "cave"}) {
                cacheMissing += simulate(seed, 16, p, 0, false, false, true).missing;
                noReplayMissing += simulate(seed, 16, p, 3, false, true, false).missing;
            }
        check("negative control: old graph-cache rule (first-arrival, frustum free) misses sections (" + cacheMissing + ")", cacheMissing > 0);
        check("negative control: async without opening repair misses sections (" + noReplayMissing + ")", noReplayMissing > 0);
    }

    // ------------------------------------------------------------------ real thread

    static void threaded() {
        World w = new World(33, 16, 41);
        List<Cam> cams = path(w, "glide-1.5", 41);
        AsyncVisibility av = new AsyncVisibility(w, true);
        av.activate();
        av.setOn(true);
        LegacyWalk legacy = new LegacyWalk(w, true);
        Frustum f = new Frustum();
        boolean[] want = new boolean[w.size()];
        Integer[] order = new Integer[w.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Cam c0 = cams.get(0);
        Arrays.sort(order, (a, b) -> Double.compare(dist2(w, a, c0), dist2(w, b, c0)));
        int next = 0;
        long served = 0, missing = 0;
        long reposition = 0;
        int lastSx = Integer.MIN_VALUE, lastSz = Integer.MIN_VALUE;
        for (int rep = 0; rep < 3; rep++)
            for (int frame = 0; frame < cams.size(); frame++) {
                for (int k = 0; k < 60 && next < order.length; k++) w.upload(order[next++]);
                Cam cam = cams.get(frame);
                int sx = (int) Math.floor(cam.x / 16), sy = (int) Math.floor(cam.y / 16), sz = (int) Math.floor(cam.z / 16);
                if (sx != lastSx || sz != lastSz) { reposition++; lastSx = sx; lastSz = sz; }
                if (sy < 0 || sy >= w.H) continue;
                int start = w.index(sx, sy, sz);
                f.set(w, cam.x, cam.y, cam.z, cam.yaw, cam.pitch, 16 * 16 + 64);
                legacy.run(start, 1, f, want);
                if (av.prepare(start, reposition, 1, System.nanoTime())) {
                    served++;
                    VisibilityWalk.Result fr = av.front();
                    boolean[] got = new boolean[w.size()];
                    for (int k = 0; k < fr.count; k++) got[fr.order[k]] = true;
                    for (int i = 0; i < want.length; i++) if (want[i] && !got[i]) missing++;
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        check("real worker thread, 900 frames of glide with streaming: 0 missing on " + served + " served frames; " + av.stats(),
                missing == 0 && served > 0);
        av.setOn(false);
    }

    // ------------------------------------------------------------------ timing proxy

    static void timing() {
        for (int rd : new int[]{24, 32}) {
            World w = new World(2 * rd + 1, 24, 7);
            compiledAll(w);
            int c = rd;
            int start = w.index(c, w.surfaceCell[(c * 8 + 4) * w.W * 8 + c * 8 + 4] / 8 + 1, c);
            LegacyWalk legacy = new LegacyWalk(w, true);
            Frustum f = new Frustum();
            boolean[] want = new boolean[w.size()];
            VisibilityWalk.Result res = new VisibilityWalk.Result(w.size());
            double cx = c * 16 + 8, cy = (start / w.W % w.H) * 16 + 8, cz = c * 16 + 8;
            long[] tl = new long[200], tf = new long[200], tw = new long[50];
            int vis = 0, filt = 0;
            for (int it = 0; it < 400; it++) { // warm-up and measure
                f.set(w, cx, cy, cz, it * 37 % 360, (it * 13 % 120) - 60, rd * 16 + 64);
                long t0 = System.nanoTime();
                vis = legacy.run(start, 1, f, want);
                long t1 = System.nanoTime();
                if (it % 8 == 0) VisibilityWalk.walk(w, start, 1, res);
                long t2 = System.nanoTime();
                filt = 0;
                for (int k = 0; k < res.count; k++) {
                    int i = res.order[k];
                    if (f.containsKeyed(w, i, res.cull[i])) filt++;
                }
                long t3 = System.nanoTime();
                if (it >= 200) {
                    tl[it - 200] = t1 - t0;
                    tf[it - 200] = t3 - t2;
                    if (it % 8 == 0) tw[(it - 200) / 8] = t2 - t1;
                }
            }
            Arrays.sort(tl);
            Arrays.sort(tf);
            long[] tw2 = Arrays.copyOf(tw, 25);
            Arrays.sort(tw2);
            System.out.printf("info RD%d (%d sections): sync walk median %.3f ms (%d visible); render-thread filter median %.3f ms (%d of %d listed); worker walk median %.3f ms%n",
                    rd, w.size(), tl[100] / 1e6, vis, tf[100] / 1e6, filt, res.count, tw2[12] / 1e6);
        }
    }
}
