package dev.elemore.iroh.shaderpack.runtime;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * B-3B: the compiled barrier schedule ({@link SyncPlan}) against the independent checker ({@link SyncCheck}), no Vulkan.
 * <ul>
 *   <li>hand cases: read after a colour write gets COLOR_OUT -> VERTEX|FRAGMENT with COLOR_WRITE -> SHADER_READ on that
 *       image only; a second reader in the same stages gets none; write after read is execution only; a frame's tail
 *       write is ordered against the next frame's first access (cross-frame carry); a skipped MUST site is caught.</li>
 *   <li>property: random plans (8 images incl. depth, 2 buffers; ordered, MAY, MUST, REPEAT and REGION sites; sites
 *       first seen in later frames) run for 40 frames with random skips, repeats and region orders: the checker must
 *       find no hazard; steady-state frames must emit no full barrier.</li>
 *   <li>negative controls: the same plans compiled without the cross-frame carry, and with every compiled barrier
 *       dropped, must produce hazards (the checker sees them).</li>
 * </ul>
 * {@code ./gradlew syncPlanTest}; last line {@code # syncplan PASS n/n}.
 */
public final class SyncPlanTest {

    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok)
            pass++;
        else
            System.out.println("FAIL " + what);
    }

    /** a backend that only routes barriers into the oracle */
    static final class Fake implements GpuBackend {
        final SyncCheck oracle = new SyncCheck(8);
        final List<Barrier> masked = new ArrayList<>();
        int full;
        boolean dropMasked;
        final Set<Integer> depth;

        Fake(Set<Integer> depth) {
            this.depth = depth;
        }

        public String name() { return "fake"; }
        public int createImage(ImageDesc d) { return 0; }
        public void upload(int image, float[] rgba) {}
        public int createBuffer(String name, long bytes) { return 0; }
        public int createVertexBuffer(String name, java.nio.ByteBuffer data) { return 0; }
        public void freeBuffer(int buffer) {}
        public void drawGeometry(GeoDraw d) {}
        public int createPipeline(PipelineDesc d) { return 0; }
        public void beginFrame() {}
        public void barrier(int image, String from, String to) { full++; oracle.full(); }
        public void barrier(Barrier b) { masked.add(b); if (!dropMasked) oracle.barrier(b); }
        public void clear(int image, float[] rgba) {}
        public void generateMips(int image) {}
        public void copy(int src, int dst) {}
        public void draw(Draw d) {}
        public void dispatch(Dispatch d) {}
        public void endFrame() {}
        public float[] readback(int image) { return null; }
        public void blit(int src, int dst) {}
        public void freePipeline(int pipeline) {}
        public void freeImage(int image) {}
        public ImageDesc desc(int image) {
            return new ImageDesc("i" + image, depth.contains(image) ? "D32_SFLOAT" : "R8G8B8A8_UNORM", 1, 1, 1, 1, Set.of());
        }
        public void close() {}
    }

    record Acc(int res, String kind, int code) {}

    static final class S {
        long id, order;
        int region, flags, pipe, firstFrame;
        List<Acc> acc = new ArrayList<>();
    }

    static final String[][] KINDS = { // per pipe: {kind, code}
            {"ATTACHMENT_WRITE", "SAMPLED_READ", "STORAGE_WRITE"},   // draw
            {"SAMPLED_READ", "STORAGE_WRITE"},                     // dispatch
            {"TRANSFER_READ", "TRANSFER_WRITE"},                   // xfer
            {"TRANSFER_WRITE"}};                                   // mips

    static List<S> randomPlan(Random r, Set<Integer> depth) {
        List<S> l = new ArrayList<>();
        int n = 6 + r.nextInt(14);
        long order = 16;
        int region = 0;
        for (int i = 0; i < n; i++) {
            S s = new S();
            if (region == 0 && r.nextInt(6) == 0) { // open a region of 2..4 members
                region = 10 + i;
                order += 16;
            }
            s.region = region;
            if (region != 0) {
                s.order = order;
                s.id = (1L << 60) | ((long) region << 32) | i;
                if (region % 2 == 0)
                    s.flags = SyncPlan.ONCE; // run-once region (the frame begin's shape)
                if (r.nextInt(3) == 0)
                    region = 0;
            } else {
                order += 1 + r.nextInt(3);
                s.order = s.id = order;
                int f = r.nextInt(6);
                s.flags = f == 0 ? SyncPlan.MUST : f == 1 ? SyncPlan.REPEAT : f == 2 ? SyncPlan.MUST : 0;
            }
            s.pipe = r.nextInt(4);
            s.firstFrame = r.nextInt(5) == 0 ? 1 + r.nextInt(8) : 0;
            int na = 1 + r.nextInt(3);
            Set<Integer> used = new java.util.HashSet<>();
            for (int k = 0; k < na; k++) {
                boolean buf = s.pipe <= 1 && r.nextInt(5) == 0;
                int res = buf ? Sync.BUF | r.nextInt(2) : r.nextInt(8);
                if (!used.add(res))
                    continue;
                String[] ks = KINDS[s.pipe];
                String kind = buf ? "STORAGE_WRITE" : ks[r.nextInt(ks.length)];
                if (kind.equals("ATTACHMENT_WRITE") && depth.contains(res) && s.acc.stream().anyMatch(a -> a.kind().equals("ATTACHMENT_WRITE") && depth.contains(a.res())))
                    kind = "SAMPLED_READ";
                if (!kind.equals("ATTACHMENT_WRITE") && !buf && depth.contains(res) && s.pipe != 2)
                    kind = "SAMPLED_READ";
                s.acc.add(new Acc(res, kind, Sync.code(kind, s.pipe == 3 ? Sync.PIPE_MIPS : s.pipe, depth.contains(res))));
            }
            l.add(s);
        }
        return l;
    }

    /** runs a plan for {@code frames} frames; returns {hazards, full barriers in steady frames, masked barriers} */
    static int[] run(List<S> plan, long seed, int frames, boolean noCarry, boolean drop, Set<Integer> depth) {
        Random r = new Random(seed);
        Fake gpu = new Fake(depth);
        BitSet owned = new BitSet();
        owned.set(0, 8);
        SyncPlan sp = new SyncPlan(gpu, owned, false, new ArrayList<>());
        sp.noCarryForTest = noCarry;
        int steadyFull = 0;
        for (int f = 0; f < frames; f++) {
            sp.frameBegin();
            int fullBefore = gpu.full;
            if (f >= 12)
                gpu.dropMasked = drop;
            int i = 0;
            while (i < plan.size()) {
                S s = plan.get(i);
                if (s.region != 0) {
                    int j = i;
                    while (j + 1 < plan.size() && plan.get(j + 1).region == s.region)
                        j++;
                    List<S> seq = new ArrayList<>();
                    for (int k = i; k <= j; k++)
                        for (int c = (plan.get(k).flags & SyncPlan.ONCE) != 0 ? r.nextInt(2) : r.nextInt(3); c > 0; c--)
                            seq.add(plan.get(k));
                    java.util.Collections.shuffle(seq, r);
                    for (S m : seq)
                        exec(sp, gpu, m, f);
                    i = j + 1;
                    continue;
                }
                boolean runs = (s.flags & SyncPlan.MUST) != 0 ? r.nextInt(25) != 0 : r.nextInt(10) < 6;
                if (runs) {
                    int times = (s.flags & SyncPlan.REPEAT) != 0 ? 1 + r.nextInt(3) : 1;
                    for (int t = 0; t < times; t++)
                        exec(sp, gpu, s, f);
                }
                i++;
            }
            sp.frameEnd();
            if (f >= 12)
                steadyFull += gpu.full - fullBefore;
        }
        return new int[]{gpu.oracle.count, steadyFull, gpu.masked.size()};
    }

    static void exec(SyncPlan sp, Fake gpu, S s, int frame) {
        if (frame < s.firstFrame)
            return;
        sp.at(s.id, s.order, s.region, s.flags, s.pipe == 3 ? Sync.PIPE_MIPS : s.pipe, "s" + Long.toHexString(s.order));
        for (Acc a : s.acc)
            if (sp.declaring())
                sp.decl(a.res(), a.kind());
        // the command: what it really does (merged per resource, as a command does)
        Map<Integer, Integer> m = new java.util.LinkedHashMap<>();
        for (Acc a : s.acc)
            m.merge(a.res(), 1 << a.code(), (x, y) -> x | y);
        m.forEach((res, mask) -> {
            int st = 0, ac = 0;
            boolean w = false;
            for (int c = 0; c < 10; c++)
                if ((mask & (1 << c)) != 0) {
                    st |= Sync.STAGE[c];
                    ac |= Sync.ACCESS[c];
                    w |= Sync.isWrite(c);
                }
            gpu.oracle.access(res, st, ac, w, "site " + Long.toHexString(s.order));
        });
        sp.after();
    }

    public static void main(String[] args) {
        Set<Integer> depth = Set.of(6, 7);
        // ---- hand cases
        {
            Fake gpu = new Fake(depth);
            BitSet owned = new BitSet();
            owned.set(0, 8);
            SyncPlan sp = new SyncPlan(gpu, owned, false, new ArrayList<>());
            Runnable frame = () -> {
                sp.frameBegin();
                sp.at(16, 16, 0, SyncPlan.MUST, Sync.PIPE_DRAW, "A");
                if (sp.declaring()) sp.decl(0, "ATTACHMENT_WRITE");
                gpu.oracle.access(0, Sync.COLOR, "A");
                sp.after();
                sp.at(17, 17, 0, SyncPlan.MUST, Sync.PIPE_DRAW, "B");
                if (sp.declaring()) { sp.decl(0, "SAMPLED_READ"); sp.decl(1, "ATTACHMENT_WRITE"); }
                gpu.oracle.access(0, Sync.GFX_R, "B");
                gpu.oracle.access(1, Sync.COLOR, "B");
                sp.after();
                sp.at(18, 18, 0, SyncPlan.MUST, Sync.PIPE_DRAW, "C");
                if (sp.declaring()) { sp.decl(0, "SAMPLED_READ"); sp.decl(1, "SAMPLED_READ"); sp.decl(2, "ATTACHMENT_WRITE"); }
                gpu.oracle.access(0, Sync.GFX_R, "C");
                gpu.oracle.access(1, Sync.GFX_R, "C");
                gpu.oracle.access(2, Sync.COLOR, "C");
                sp.after();
                sp.at(19, 19, 0, SyncPlan.MUST, Sync.PIPE_XFER, "D");
                if (sp.declaring()) { sp.decl(2, "TRANSFER_READ"); sp.decl(0, "TRANSFER_WRITE"); }
                gpu.oracle.access(2, Sync.XFER_R, "D");
                gpu.oracle.access(0, Sync.XFER_W, "D");
                sp.after();
                sp.frameEnd();
            };
            for (int f = 0; f < 4; f++)
                frame.run();
            List<String> d = sp.describe();
            System.out.println("# schedule:");
            d.forEach(x -> System.out.println("    " + x));
            check("4 sites compiled (" + sp.lastCompile + ")", sp.sites() == 4);
            check("B: image 0 COLOR_WRITE -> SHADER_READ for the fragment family: " + d.get(1),
                    d.get(1).contains("COLOR_OUT") && d.get(1).contains("img 0 100->20"));
            check("C: image 0 already visible to the fragment family, image 1 needs its barrier: " + d.get(2),
                    d.get(2).contains("img 1 100->20") && !d.get(2).contains("img 0"));
            check("D: transfer write over image 0 waits for its readers and its colour write, read of 2 = memory: " + d.get(3),
                    d.get(3).contains("VERTEX|FRAGMENT|COLOR_OUT -> TRANSFER") && d.get(3).contains("img 2 100->800") && d.get(3).contains("img 0 100->1000"));
            check("A: next frame's colour write over D's transfer write of image 0 (cross-frame carry): " + d.get(0),
                    d.get(0).contains("barrier TRANSFER -> COLOR_OUT img 0 1000->180"));
            check("hand plan: no hazard in 4 frames (" + gpu.oracle.violations + ")", gpu.oracle.count == 0);
            check("steady frames: 4 masked barriers per frame, fences only on compile frames (full " + gpu.full + ")", gpu.full <= 2 + 2 * 4);
        }
        // ---- a skipped MUST site: the next site runs isolated, the site is demoted and the plan recompiled
        {
            Fake gpu = new Fake(depth);
            BitSet owned = new BitSet();
            owned.set(0, 8);
            List<String> problems = new ArrayList<>();
            SyncPlan sp = new SyncPlan(gpu, owned, false, problems);
            int[] hz = new int[1];
            for (int f = 0; f < 6; f++) {
                sp.frameBegin();
                sp.at(16, 16, 0, SyncPlan.MUST, Sync.PIPE_DRAW, "W");
                if (sp.declaring()) sp.decl(0, "ATTACHMENT_WRITE");
                gpu.oracle.access(0, Sync.COLOR, "W");
                sp.after();
                if (f != 3) { // M reads 0 in the fragment stages (its barrier makes 0 visible there); skipped once
                    sp.at(17, 17, 0, SyncPlan.MUST, Sync.PIPE_DRAW, "M");
                    if (sp.declaring()) { sp.decl(0, "SAMPLED_READ"); sp.decl(1, "ATTACHMENT_WRITE"); }
                    gpu.oracle.access(0, Sync.GFX_R, "M");
                    gpu.oracle.access(1, Sync.COLOR, "M");
                    sp.after();
                }
                sp.at(18, 18, 0, 0, Sync.PIPE_DRAW, "R");
                if (sp.declaring()) { sp.decl(0, "SAMPLED_READ"); sp.decl(2, "ATTACHMENT_WRITE"); }
                gpu.oracle.access(0, Sync.GFX_R, "R");
                gpu.oracle.access(2, Sync.COLOR, "R");
                sp.after();
                sp.frameEnd();
            }
            hz[0] = gpu.oracle.count;
            check("skipped MUST: no hazard (" + gpu.oracle.violations + ")", hz[0] == 0);
            check("skipped MUST: demoted and recompiled (" + sp.compiles + " compiles, " + sp.lastCompile + ")", sp.compiles >= 2
                    && sp.describe().get(1).contains("M@") && !sp.describe().get(1).contains("must"));
        }
        // ---- property
        Random rr = new Random(20261124);
        int plans = 400, hazards = 0, steadyFull = 0, noCarryHz = 0, dropHz = 0, masked = 0;
        String first = null;
        for (int t = 0; t < plans; t++) {
            List<S> plan = randomPlan(rr, depth);
            long seed = rr.nextLong();
            int[] a = run(plan, seed, 40, false, false, depth);
            hazards += a[0];
            steadyFull += a[1];
            masked += a[2];
            if (a[0] > 0 && first == null)
                first = "plan " + t;
            noCarryHz += run(plan, seed, 40, true, false, depth)[0];
            dropHz += run(plan, seed, 40, false, true, depth)[0];
        }
        check("property: " + plans + " random plans x 40 frames: no hazard (" + hazards + (first != null ? ", first in " + first : "") + ")", hazards == 0);
        System.out.println("# syncplan property: " + masked + " masked barriers, " + steadyFull + " full barriers in steady frames (skips of MUST sites refence)");
        check("negative control: compiled without the cross-frame carry -> hazards (" + noCarryHz + ")", noCarryHz > 0);
        check("negative control: compiled barriers dropped -> hazards (" + dropHz + ")", dropHz > 0);
        System.out.println("# syncplan " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
