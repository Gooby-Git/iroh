package dev.elemore.iroh.rttest;

import dev.elemore.iroh.rt.*;
import dev.elemore.iroh.rt.game.*;

import java.lang.management.ManagementFactory;
import java.util.*;

import static dev.elemore.iroh.rttest.RtSeamTest.check;

/**
 * P5-2 game adapter, offline (no device, no game): inert when off, fault seam, Look arbiter, lifecycle under owner
 * iroh-rt with a fake backend (enable, sections arriving and leaving with the per-frame budget, resize, world unload,
 * disable: nothing leaked), memory refusal. Part of ./gradlew rtSeamTest.
 */
public final class RtAdapterTest {
    /** Fake GPU side: counts live objects and bytes like VkRtBackend would (64 B/triangle BLAS, 64 B/px targets). */
    static final class Fake implements RtSession.Backend {
        final Map<Long, Long> blas = new HashMap<>();
        long next = 1, targetBytes, tlasBytes;
        int tlasBuilds, lastTlas;

        @Override public long buildBlas(SectionGeometry g, List<RtSectionSource.Range> r) {
            long b = 0;
            for (RtSectionSource.Range x : r) b += x.quads() * 128L;
            blas.put(next, b);
            return next++;
        }
        @Override public void destroyBlas(long h) { if (blas.remove(h) == null) throw new IllegalStateException("double free " + h); }
        @Override public void buildTlas(long[] h, int[][] s, int n) {
            for (int i = 0; i < n; i++) if (!blas.containsKey(h[i])) throw new IllegalStateException("TLAS names a freed BLAS " + h[i]);
            tlasBuilds++;
            lastTlas = n;
            tlasBytes = n * 128L;
        }
        @Override public void targets(int w, int h) { targetBytes = (long) w * h * 64; }
        @Override public int liveObjects() { return blas.size() + (targetBytes > 0 ? 1 : 0) + (lastTlas > 0 ? 1 : 0); }
        @Override public long liveBytes() { long b = targetBytes + (lastTlas > 0 ? tlasBytes : 0); for (long x : blas.values()) b += x; return b; }
        @Override public void close() { blas.clear(); targetBytes = 0; lastTlas = 0; }
    }

    /** Fake world: a set of built sections with quads; ranges live at a fake address. */
    static final class World implements RtSectionSource {
        final Map<Long, int[]> sections = new HashMap<>();
        @Override public List<Range> ranges(int sx, int sy, int sz) {
            int[] q = sections.get(SectionGeometry.key(sx, sy, sz));
            if (q == null) return List.of();
            List<Range> r = new ArrayList<>();
            for (int b = 0; b < 3; b++) if (q[b] > 0) r.add(new Range(b, 0x10000L * (b + 1), 0, 16, q[b]));
            return r;
        }
        @Override public int[] quads(int sx, int sy, int sz) { return sections.get(SectionGeometry.key(sx, sy, sz)); }
    }

    static void run() {
        System.out.println("  -- P5-2 game adapter (offline)");
        // ---- inert when off: no property set
        String[] keep = {"iroh.rt", "iroh.rt.look", RtFailures.PROPERTY};
        Map<String, String> saved = new HashMap<>();
        for (String k : keep) { if (System.getProperty(k) != null) saved.put(k, System.getProperty(k)); System.clearProperty(k); }
        RtSwitch.resetForTest();
        RtDeviceFeatures.resetForTest();
        RtFailures.resetForTest();
        Set<String> base = Set.of("VK_KHR_swapchain");
        long tail = 0x1234;
        boolean inert = RtDeviceFeatures.chain(null, null, null, tail) == tail && RtDeviceFeatures.extensions(base) == base
                && RtVma.allocatorFlags() == 0 && RtVma.arenaUsage() == 0 && RtStartup.apiPart().isEmpty() && RtDeviceFeatures.selfCheckBits().isEmpty()
                && !RtSwitch.ON && !RtMeshMode.tintOnly;
        RtDeviceFeatures.deviceCreated();
        RtFrameHooks.worldUnloaded();
        check("adapter INERT when off: device chain/extension set returned unchanged (same objects, no Vulkan call: null device accepted), VMA/arena flags 0, self-check adds nothing, hooks no-op",
                inert && !RtSwitch.ON && RtFrameHooks.lastStats() == null);
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long sink = offPath(200_000);   // warm-up (class init, JIT)
        long a0 = mx.getCurrentThreadAllocatedBytes();
        sink += offPath(1_000_000);
        long alloc = mx.getCurrentThreadAllocatedBytes() - a0;
        check("off-path hooks allocate nothing measurable (" + alloc + " B over 1e6 calls each, < 16 KB) and change nothing (" + sink + " == 0)", alloc < 16_384 && sink == 0);

        // ---- requested, fault seam: the chain is left as it was, one line, RT off
        System.setProperty("iroh.rt", "true");
        System.setProperty("iroh.rt.look", "RAY_TRACED");
        System.setProperty(RtFailures.PROPERTY, "rt");
        RtSwitch.resetForTest();
        RtFailures.resetForTest(); // INT-4: the seam is FeatureFailures' parsed set: re-read it
        List<String> lines = new ArrayList<>();
        RtFailures.sink = (step, t) -> { String l = "rt failed at " + step + ": " + t.getMessage() + "; " + RtFailures.FALLBACK; lines.add(l); return l; };
        long r = RtDeviceFeatures.chain(null, null, null, tail);
        RtDeviceFeatures.deviceCreated();
        check("fault seam -Diroh.test.failInit=rt: requested but injected -> chain unchanged, no extensions, RT off, exactly one failure line, self-check says failed",
                r == tail && RtDeviceFeatures.extensions(base) == base && !RtSwitch.ON && lines.size() == 1 && RtFailures.reason() != null
                        && RtStartup.apiPart().equals(" rt=failed") && RtDeviceFeatures.selfCheckBits().contains("failed"));
        System.out.println("    failure line: " + lines.get(0));
        System.clearProperty(RtFailures.PROPERTY);
        RtFailures.resetForTest();
        RtSwitch.resetForTest();
        check("iroh.json look=RAY_TRACED counts only with the dev gate; a launch property wins",
                RtSwitch.requestedAtLaunch() && withConfig("NAVIDSON", "RAY_TRACED") && !withConfig(null, null));
        for (String k : keep) System.clearProperty(k);
        saved.forEach(System::setProperty);
        RtSwitch.resetForTest();

        // ---- look arbiter
        check("Look arbiter: RT and pack mutually exclusive; RT selected but not running -> raster (never the pack); pack failed -> raster",
                RtLookArbiter.resolve(true, true, true) == RtLookArbiter.Active.RT && RtLookArbiter.resolve(true, false, true) == RtLookArbiter.Active.RASTER
                        && RtLookArbiter.resolve(false, true, true) == RtLookArbiter.Active.PACK && RtLookArbiter.resolve(false, false, false) == RtLookArbiter.Active.RASTER
                        && RtLookArbiter.rowDisabledReason(false, false) != null && RtLookArbiter.rowDisabledReason(true, false).contains("restart")
                        && RtLookArbiter.rowDisabledReason(true, true) == null);

        // ---- lifecycle + recorded "sections arriving and leaving" under owner iroh-rt
        Fake fake = new Fake();
        World world = new World();
        RtGuardMemory mem = new RtGuardMemory(RtGuardMemory.DEFAULT_CAP, b -> true);
        RtSession s = new RtSession(RtSettings.DEFAULT, mem, RtMemoryModel.radvAssumed(), world, fake);
        RtGameBridge bridge = new RtGameBridge(s.queue, world);
        boolean en = s.enable(1707, 960);
        long tgt = RtSession.targetBytes(1707, 960);
        check("enable at 1707x960 reserves the targets under iroh-rt (" + mem.usedBytes() / 1048576 + " MiB = 80 B/px)", en && mem.usedBytes() == tgt);
        Random rnd = new Random(7);
        int maxBuilt = 0, frames = 0, overBudget = 0, camX = 0;
        long maxTris = 0;
        StringBuilder rec = new StringBuilder();
        // 60 frames: the camera walks +x one section every 6 frames; sections stream in around it (25 per frame at most,
        // arriving like the chunk builder hands them over) and leave behind it; 10% of arrivals are rebuilds of resident ones
        Set<Long> live = new HashSet<>();
        for (int f = 0; f < 60; f++, frames++) {
            if (f % 6 == 0) camX++;
            for (int k = 0; k < 25; k++) {
                int sx = camX + rnd.nextInt(21) - 10, sy = rnd.nextInt(8) - 4, sz = rnd.nextInt(21) - 10;
                int[] q = {300 + rnd.nextInt(3000), rnd.nextInt(400), rnd.nextInt(100)};
                world.sections.put(SectionGeometry.key(sx, sy, sz), q);
                live.add(SectionGeometry.key(sx, sy, sz));
                bridge.sectionBuilt(sx, sy, sz, false);
            }
            for (Iterator<Long> it = live.iterator(); it.hasNext(); ) {   // unload sections > 12 behind
                long key = it.next();
                int sx = (int) (key >> 42);
                if (sx < camX - 12) {
                    it.remove();
                    world.sections.remove(key);
                    int sz = (int) ((key >> 20) & 0x3FFFFF), sy = (int) (key & 0xFFFFF);
                    if (sz >= 1 << 21) sz -= 1 << 22;
                    if (sy >= 1 << 19) sy -= 1 << 20;
                    bridge.sectionReset(sx, sy, sz);
                }
            }
            RtSession.FrameStats st = s.frame(camX, 0, 0);
            maxBuilt = Math.max(maxBuilt, st.built());
            maxTris = Math.max(maxTris, st.triangles());
            if (st.built() > s.queue.maxBuilds || (st.built() > 1 && st.triangles() > s.queue.maxTriangles)) overBudget++;
            if (f % 10 == 9) rec.append(String.format(Locale.ROOT, "    frame %2d cam sx %2d: built %d (%6d tris), resident %4d, pending %4d, freed %3d, iroh-rt %6.1f MiB, TLAS %d instances%n",
                    f, camX, st.built(), st.triangles(), st.resident(), st.pending(), st.freed(), st.usedBytes() / 1048576.0, fake.lastTlas));
        }
        System.out.print(rec);
        check("recorded run (60 frames, 1500 arrivals, departures behind the camera): per-frame budget held every frame (max " + maxBuilt + " builds, " + maxTris + " tris; caps "
                + s.queue.maxBuilds + " / " + s.queue.maxTriangles + "), TLAS built every frame over live BLASes only", overBudget == 0 && fake.tlasBuilds == frames);
        check("residency radius 8 chunks (independent of render distance)", s.queue.radius == 8);
        boolean resized = s.resize(2560, 1440);
        check("resize to 2560x1440 swaps the targets (iroh-rt holds BLAS + new targets only)", resized && fake.targetBytes == 2560L * 1440 * 64
                && mem.usedBytes() == RtSession.targetBytes(2560, 1440) + residentBytes(s));
        s.worldUnloaded();
        check("world unload frees every BLAS (resident 0, backend BLAS 0, iroh-rt = targets only)", s.queue.residentCount() == 0 && fake.blas.isEmpty()
                && mem.usedBytes() == RtSession.targetBytes(2560, 1440));
        for (int f = 0; f < 5; f++) { for (int k = 0; k < 10; k++) { world.sections.put(SectionGeometry.key(k, 0, f), new int[]{1000, 0, 0}); bridge.sectionBuilt(k, 0, f, false); } s.frame(0, 0, 0); }
        s.disable();
        check("disable after a second world: nothing leaked under owner iroh-rt (" + mem.usedBytes() + " B, backend objects " + fake.liveObjects() + ", bytes " + fake.liveBytes() + ")",
                mem.usedBytes() == 0 && fake.liveObjects() == 0 && fake.liveBytes() == 0);

        // ---- guard refusal: never forced, the session waits
        long[] budget = {RtSession.targetBytes(320, 180) + 3 * RtMemoryModel.radvAssumed().blasBytes(1000)};
        RtGuardMemory tight = new RtGuardMemory(RtGuardMemory.DEFAULT_CAP, b -> b <= budget[0]);
        Fake f2 = new Fake();
        World w2 = new World();
        RtSession s2 = new RtSession(RtSettings.DEFAULT, new RtMemory() {
            long used;
            @Override public long capBytes() { return tight.capBytes(); }
            @Override public long usedBytes() { return used; }
            @Override public boolean reserve(long b) { if (used + b > budget[0]) return false; used += b; return true; }
            @Override public void release(long b) { used -= b; }
            @Override public void charge(long b) { used += b; }
        }, RtMemoryModel.radvAssumed(), w2, f2);
        s2.enable(320, 180);
        RtGameBridge b2 = new RtGameBridge(s2.queue, w2);
        for (int k = 0; k < 6; k++) { w2.sections.put(SectionGeometry.key(k, 0, 0), new int[]{1000, 0, 0}); b2.sectionBuilt(k, 0, 0, false); }
        RtSession.FrameStats st = s2.frame(0, 0, 0);
        check("guard refusal (MemoryGuard.allowOptional false / cap): 3 built, 3 wait queued, nothing forced (" + st.built() + " built, " + st.refused() + " refused, " + st.pending() + " pending)",
                st.built() == 3 && st.refused() == 3 && st.pending() == 3);
        s2.disable();
        check("vanished section between event and build is dropped, not built from a stale address", vanished());
    }

    static long offPath(int n) {
        long sink = 0;
        for (int i = 0; i < n; i++) {
            RtFrameHooks.worldUnloaded();
            sink += RtVma.arenaUsage() + RtVma.allocatorFlags() + (RtMeshMode.tintOnly ? 1 : 0);
            sink += RtDeviceFeatures.chain(null, null, null, i) == i ? 0 : 1;
        }
        return sink;
    }

    static long residentBytes(RtSession s) {
        return s.usedBytes() - RtSession.targetBytes(2560, 1440);
    }

    static boolean withConfig(String config, String prop) {
        RtSwitch.resetForTest();
        if (prop == null) System.clearProperty("iroh.rt.look"); else System.setProperty("iroh.rt.look", prop);
        RtSwitch.configLook = config;
        boolean r = RtSwitch.requestedAtLaunch();
        RtSwitch.resetForTest();
        if (config == null) { // no property, no config: NAVIDSON
            return r;
        }
        return r;
    }

    static boolean vanished() {
        Fake f = new Fake();
        World w = new World();
        RtSession s = new RtSession(RtSettings.DEFAULT, new RtGuardMemory(RtGuardMemory.DEFAULT_CAP, b -> true), RtMemoryModel.radvAssumed(), w, f);
        s.enable(64, 64);
        RtGameBridge b = new RtGameBridge(s.queue, w);
        w.sections.put(SectionGeometry.key(0, 0, 0), new int[]{100, 0, 0});
        b.sectionBuilt(0, 0, 0, false);
        w.sections.clear();                 // unloaded before the build ran (no reset event yet)
        RtSession.FrameStats st = s.frame(0, 0, 0);
        boolean ok = st.built() == 0 && f.blas.isEmpty() && s.queue.residentCount() == 0;
        s.disable();
        return ok && s.usedBytes() == 0;
    }
}
