package net.vulkanmod.render.chunk.graph;

/**
 * P2-6 (lever L3): the frustum-free section visibility walk used by the async graph, plus its incremental repair.
 * Clean room, written from SectionGraph (this repo) and Minecraft 1.21.1's own SectionOcclusionGraph behaviour.
 *
 * <p>Rules, kept identical to {@link SectionGraph}'s walk except for two deliberate differences:
 * <ul>
 *   <li>No frustum test: every direction is walked, so the result depends only on the camera SECTION and the graph,
 *       never on where the camera looks. The render thread applies the frustum per frame ({@code filter}).</li>
 *   <li>A section's exits are the union of its visibility rows over EVERY face it was entered through (vanilla 1.21.1
 *       {@code SectionOcclusionGraph.runUpdates} loops over all source directions); SectionGraph uses only the first
 *       arrival ({@code mainDir}). The union is what makes the walk monotone, see below.</li>
 * </ul>
 * Same as SectionGraph: breadth first from the camera section, a section never walks back towards the camera
 * ({@code directions}), {@code steps} as a byte, the direction-change count ({@code dc}: 0 inside 10 steps, else 127,
 * then the minimum over arrivals of parent dc + 1 when the parent turns and is not completely empty), and a section is
 * walked through ("processed") only while dc <= maxDirectionChanges.
 *
 * <p><b>Superset theorem</b> (the async graph's correctness argument; tested by GraphAsyncTest). Every move is away
 * from the camera on its axis, so all paths to a section have the same length (its level) and the same direction set:
 * {@code steps} and {@code directions} do not depend on the path, and the queue is level ordered, so every arrival at
 * a section happens before it is processed. By induction over levels, for the same camera section and a graph whose
 * per-section inputs (visibility rows 0..5, completely-empty flag) are at least as open: every section SectionGraph's
 * walk processes (any frustum) is processed here, with a superset of source faces and a dc no larger. So this walk
 * filtered by the frame's frustum is a superset of SectionGraph's walk for that frame. With SectionGraph's own
 * first-arrival rule the claim is false (which face arrives first depends on the frustum): that is why the hitches
 * graph cache failed its verify by up to 229 sections in block 7.
 *
 * <p>Monotone in the inputs too: opening a section (a visibility bit added, or non-empty -> empty) only adds arrivals
 * and lowers dc downstream, so {@link #relax} repairs a result in place, exactly, by propagating from the opened
 * section; closing a section leaves the old result a superset (extra sections, never missing ones).
 */
public final class VisibilityWalk {
    private VisibilityWalk() {}

    /** Read-only view of the section grid. Indices are stable grid slots; -1 = no neighbour. */
    public interface Graph {
        int size();

        /** Neighbour slot in direction {@code dir} (GraphDirections order), or -1. */
        int adj(int i, int dir);

        /** Bit d set when the neighbour in direction d exists (RenderSection.adjDirs). */
        byte adjDirs(int i);

        /** RenderSection.visibility: byte r = faces visible when entering through face r. */
        long vis(int i);

        boolean empty(int i);

        /**
         * Where the frame's frustum verdict for this section is found: ChunkArea.index * 64 + RenderSection.frustumIndex
         * (the area's 2x2x2-section sub-cube). Fixed between grid repositions, so the walk stores it per listed section
         * and the filter rejects out-of-frustum sections from one flat byte table without touching the section.
         */
        int cullKey(int i);
    }

    /** Visibility rows 0..5, low 6 bits each: the only bits the walk reads (rows 6/7 belong to SectionGraph's start node). */
    public static final long ROWS_MASK = 0x3F3F3F3F3F3FL;

    /** Whether replacing (oldVis, oldEmpty) by (newVis, newEmpty) can make more sections visible. */
    public static boolean opens(long oldVis, boolean oldEmpty, long newVis, boolean newEmpty) {
        return ((newVis & ~oldVis) & ROWS_MASK) != 0 || (newEmpty && !oldEmpty);
    }

    static int opposite(int d) {
        return (d & 1) != 0 ? d - 1 : d + 1;
    }

    /** Union of the exit faces over every entry face in {@code src} (bit d = entered moving in direction d). */
    static int exitsFor(long vis, int src) {
        int exits = 0;
        for (int d = 0; d < 6; d++)
            if ((src & (1 << d)) != 0)
                exits |= (int) (vis >>> (opposite(d) << 3)) & 0x3F;
        return exits;
    }

    /** One walk result. Arrays are indexed by grid slot; membership by stamp, so no clearing between walks. */
    public static final class Result {
        final int n;
        final int[] reached;   // == stamp: reached this walk
        final int[] listed;    // == stamp: processed (in order or added)
        final byte[] src, dirs, steps, dc;
        /** Graph.cullKey of each processed section (indexed by slot). */
        public final int[] cull;
        /** Processed sections in level order. */
        public final int[] order;
        public int count;
        /**
         * Every section the walk reached (queued), processed or not, BFS order then relax additions. SectionGraph stamps
         * them with setLastFrame: entity and particle culling (EntityRendererM, SingleQuadParticleM) read that stamp.
         */
        public final int[] reachedList;
        public int reachedCount;
        /** Sections processed by {@link #relax} since the last {@link #mergeAdded}, unsorted. */
        final int[] added;
        int addedCount;
        int stamp;
        int start = -1;
        int maxDc;

        // set by AsyncVisibility
        long key0, key1;
        long seq;        // opening counter value the walk's graph reads are guaranteed to include
        long appliedSeq; // openings replayed into this result up to here
        long walkNs;

        public Result(int n) {
            this.n = n;
            this.reached = new int[n];
            this.listed = new int[n];
            this.src = new byte[n];
            this.dirs = new byte[n];
            this.steps = new byte[n];
            this.dc = new byte[n];
            this.cull = new int[n];
            this.order = new int[n];
            this.reachedList = new int[n];
            this.added = new int[n];
        }

        public boolean isProcessed(int i) {
            return this.listed[i] == this.stamp;
        }

        public int start() {
            return this.start;
        }
    }

    /** Full walk from {@code start}; the BFS queue is {@link Result#reachedList}. */
    public static void walk(Graph g, int start, int maxDc, Result r) {
        int[] queue = r.reachedList;
        int stamp = ++r.stamp;
        if (stamp == 0) { // wrapped: clear once every 2^32 walks
            java.util.Arrays.fill(r.reached, 0);
            java.util.Arrays.fill(r.listed, 0);
            stamp = r.stamp = 1;
        }
        r.count = 0;
        r.addedCount = 0;
        r.start = start;
        r.maxDc = maxDc;

        r.reached[start] = stamp;
        r.src[start] = (byte) 0x80; // SectionGraph.initFirstNode: sourceDirs = 1 << 7
        r.dirs[start] = (byte) 0xFF;
        r.steps[start] = 0;
        r.dc[start] = 0;

        int head = 0, tail = 0;
        queue[tail++] = start;
        while (head < tail) {
            int i = queue[head++];
            if (r.dc[i] > maxDc)
                continue;
            r.listed[i] = stamp;
            r.order[r.count++] = i;
            r.cull[i] = g.cullKey(i);

            int exits = (i == start ? 0x3F : exitsFor(g.vis(i), r.src[i])) & r.dirs[i] & g.adjDirs(i);
            if (exits == 0)
                continue;
            boolean empty = g.empty(i);
            for (int d = 0; d < 6; d++) {
                if ((exits & (1 << d)) == 0)
                    continue;
                int j = g.adj(i, d);
                if (j < 0)
                    continue;
                if (r.reached[j] != stamp) {
                    reach(r, i, j, d, stamp);
                    queue[tail++] = j;
                } else {
                    r.src[j] |= (byte) (1 << d);
                }
                lowerDc(r, i, j, d, empty);
            }
        }
        r.reachedCount = tail;
    }

    private static void reach(Result r, int i, int j, int d, int stamp) {
        r.reached[j] = stamp;
        byte st = (byte) (r.steps[i] + 1); // byte like RenderSection.steps (wraps past 127 exactly as SectionGraph does)
        r.steps[j] = st;
        r.dc[j] = (byte) (st < 10 ? 0 : 127);
        r.src[j] = (byte) (1 << d);
        r.dirs[j] = (byte) (r.dirs[i] & ~(1 << opposite(d)));
    }

    /** SectionGraph.addNode's direction-change update for the arrival i -> j in direction d. True if dc[j] dropped. */
    private static boolean lowerDc(Result r, int i, int j, int d, boolean parentEmpty) {
        boolean increase = (r.src[i] & (1 << d)) == 0 && !parentEmpty;
        int ndc = r.dc[i] + (increase ? 1 : 0);
        if (ndc < r.dc[j]) {
            r.dc[j] = (byte) ndc;
            return true;
        }
        return false;
    }

    /**
     * Repairs {@code r} after the inputs of {@code section} opened (or after it was newly processed): propagates new
     * arrivals and lower dc outwards until nothing changes. Exact for openings (the result equals a fresh walk on the
     * new graph, as sets), a no-op for closings. Newly processed sections go to {@code added} (merge with
     * {@link #mergeAdded} before reading {@code order}). Returns the number of sections relaxed.
     *
     * @param work scratch of {@code g.size()} ints; {@code inWork} scratch of {@code g.size()} ints (stamp-tagged)
     */
    public static int relax(Graph g, Result r, int section, int[] work, int[] inWork, int workStamp) {
        int stamp = r.stamp;
        if (r.listed[section] != stamp)
            return 0; // not walked through: its inputs are never read
        int top = 0;
        work[top++] = section;
        inWork[section] = workStamp;
        int relaxed = 0;
        while (top > 0) {
            int i = work[--top];
            inWork[i] = workStamp - 1;
            relaxed++;
            int exits = (i == r.start ? 0x3F : exitsFor(g.vis(i), r.src[i])) & r.dirs[i] & g.adjDirs(i);
            if (exits == 0)
                continue;
            boolean empty = g.empty(i);
            for (int d = 0; d < 6; d++) {
                if ((exits & (1 << d)) == 0)
                    continue;
                int j = g.adj(i, d);
                if (j < 0)
                    continue;
                boolean changed;
                if (r.reached[j] != stamp) {
                    reach(r, i, j, d, stamp);
                    r.reachedList[r.reachedCount++] = j;
                    changed = true;
                } else {
                    changed = (r.src[j] & (1 << d)) == 0;
                    r.src[j] |= (byte) (1 << d);
                }
                changed |= lowerDc(r, i, j, d, empty);
                if (!changed || r.dc[j] > r.maxDc)
                    continue;
                if (r.listed[j] != stamp) {
                    r.listed[j] = stamp;
                    r.cull[j] = g.cullKey(j);
                    r.added[r.addedCount++] = j;
                }
                if (inWork[j] != workStamp) {
                    inWork[j] = workStamp;
                    work[top++] = j;
                }
            }
        }
        return relaxed;
    }

    /** Folds {@code added} into {@code order}, keeping level order (unsigned steps). */
    public static void mergeAdded(Result r) {
        int a = r.addedCount;
        if (a == 0)
            return;
        int[] added = r.added;
        // insertion sort is fine for the usual handful; fall back to a full sort for large repairs
        if (a > 64) {
            sortByLevel(r, added, a);
        } else {
            for (int x = 1; x < a; x++) {
                int v = added[x], lv = level(r, v), y = x - 1;
                while (y >= 0 && level(r, added[y]) > lv) {
                    added[y + 1] = added[y];
                    y--;
                }
                added[y + 1] = v;
            }
        }
        int[] order = r.order;
        int i = r.count - 1, k = a - 1, w = r.count + a - 1;
        while (k >= 0) {
            if (i >= 0 && level(r, order[i]) > level(r, added[k]))
                order[w--] = order[i--];
            else
                order[w--] = added[k--];
        }
        r.count += a;
        r.addedCount = 0;
    }

    static int level(Result r, int i) {
        return r.steps[i] & 0xFF;
    }

    private static void sortByLevel(Result r, int[] a, int len) {
        // counting sort on 0..255
        int[] counts = new int[257];
        for (int x = 0; x < len; x++) counts[level(r, a[x]) + 1]++;
        for (int x = 1; x < 257; x++) counts[x] += counts[x - 1];
        int[] tmp = new int[len];
        for (int x = 0; x < len; x++) tmp[counts[level(r, a[x])]++] = a[x];
        System.arraycopy(tmp, 0, a, 0, len);
    }
}
