package dev.elemore.iroh.rttest;

import dev.elemore.iroh.rt.*;
import dev.elemore.iroh.rt.game.RtGameBridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * P5-0 RT seams without a device ({@code ./gradlew rtSeamTest}): settings and activation, INERT when off (no
 * listener, no memory, no device extensions, and no class outside dev.elemore.iroh.rt references the RT package),
 * the BLAS build queue (budgets, latest version wins, resets, memory refusal, residency radius), the memory model
 * table for docs/P5-PLAN.md, presets. Last line "# rtseam PASS n/n".
 */
public final class RtSeamTest {
    static int pass, fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    static SectionGeometry sec(int x, int y, int z, int quads, long version) {
        return new SectionGeometry(x, y, z, 0, 0, 16, new int[]{quads, 0, 0}, new long[3], new float[0], version);
    }

    static final RtCapabilities RT = new RtCapabilities(true, true, true, false, false, 16L << 30);

    public static void main(String[] a) throws IOException {
        // ---- settings
        check("default look is Navidson, RT inactive", RtSettings.DEFAULT.look() == RtLook.NAVIDSON && !RtSettings.DEFAULT.active(RT));
        check("look RT + dev gate + capable device -> active", RtSettings.from(Map.of("iroh.rt", "true", "iroh.rt.look", "ray_traced")).active(RT));
        check("look RT without the dev gate -> inactive", !RtSettings.from(Map.of("iroh.rt.look", "RAY_TRACED")).active(RT));
        check("look RT on a device without ray query -> inactive",
                !RtSettings.from(Map.of("iroh.rt", "true", "iroh.rt.look", "RAY_TRACED")).active(new RtCapabilities(true, false, true, false, false, 0)));
        check("bad values fall back to defaults", RtSettings.from(Map.of("iroh.rt.look", "sparkles", "iroh.rt.preset", "x")).equals(RtSettings.DEFAULT));

        // ---- inert when off
        RtMemory.Counter mem = new RtMemory.Counter(1L << 30);
        int[] registrations = {0};
        RtRuntime off = RtRuntime.create(RtSettings.from(System.getProperties()), RT, mem, RtMemoryModel.radvAssumed(), f -> registrations[0]++);
        check("runtime is INERT with default settings", off == RtRuntime.INERT && !off.active());
        int[] listeners = {0};
        check("game bridge registers no section listener when inert", RtGameBridge.install(off, l -> listeners[0]++) == null && listeners[0] == 0);
        off.feed().sectionBuilt(sec(0, 0, 0, 1000, 1));
        check("inert: no feed registration, no memory touched, no frame work, no device extensions",
                registrations[0] == 0 && mem.reserveCalls == 0 && off.frame(0, 0, 0) == 0 && off.deviceExtensions().length == 0);
        check("outside dev.elemore.iroh.rt only the pinned hook sites reference the RT core, and only its hook classes (scan of compiled main classes)", outsideReferences().isEmpty());

        RtRuntime on = RtRuntime.create(RtSettings.from(Map.of("iroh.rt", "true", "iroh.rt.look", "RAY_TRACED")), RT, mem, RtMemoryModel.radvAssumed(),
                f -> registrations[0]++);
        check("active runtime registers its feed once and requests the RT extensions", on.active() && registrations[0] == 1 && on.deviceExtensions().length == 3);
        check("active game bridge registers one listener", RtGameBridge.install(on, l -> listeners[0]++) != null && listeners[0] == 1);

        // ---- BLAS queue
        RtMemoryModel model = RtMemoryModel.radvAssumed();
        RtMemory.Counter m2 = new RtMemory.Counter(64L << 20);
        BlasBuildQueue q = new BlasBuildQueue(m2, model);
        q.maxBuilds = 4;
        q.maxTriangles = 1_000_000;
        q.radius = 4;
        q.camera(0, 0, 0);
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) q.sectionBuilt(sec(x, 0, z, 1000, 1));
        List<BlasBuildQueue.Pending> f1 = q.take();
        boolean nearest = f1.stream().allMatch(p -> Math.abs(p.g().sx()) + Math.abs(p.g().sz()) <= 1);
        check("per-frame build count cap (4) and nearest first", f1.size() == 4 && nearest);
        int frames = 1;
        while (q.pendingCount() > 0 && frames < 1000) {
            int n = q.take().size();
            frames++;
            if (n == 0) break;
        }
        check("only sections within the residency radius are built (" + q.residentCount() + " == 81)", q.residentCount() == 81);
        check("sections outside the radius stay queued, not dropped (" + q.pendingCount() + " == 88)", q.pendingCount() == 169 - 81);
        long used = m2.usedBytes();
        check("memory reserved per built BLAS (" + used + " == 81 x " + model.blasBytes(1000) + ")", used == 81 * model.blasBytes(1000));
        q.sectionBuilt(sec(0, 0, 0, 2000, 3));
        q.sectionBuilt(sec(0, 0, 0, 500, 2)); // stale version arrives late
        List<BlasBuildQueue.Pending> f2 = q.take();
        check("latest version wins (rebuild has 2000 quads)", f2.size() == 1 && f2.get(0).g().totalQuads() == 2000);
        List<BlasBuildQueue.Resident> freed = q.drainFrees();
        check("the replaced BLAS is freed after the rebuild", freed.size() == 1 && freed.get(0).version() == 1);
        q.sectionReset(1, 0, 1);
        check("a reset frees the resident BLAS and its memory", !q.isResident(1, 0, 1) && q.drainFrees().size() == 1
                && m2.usedBytes() == 79 * model.blasBytes(1000) + model.blasBytes(2000));
        q.sectionBuilt(sec(2, 0, 2, 0, 9));
        check("an empty rebuild evicts", !q.isResident(2, 0, 2) && q.drainFrees().size() == 1);
        RtMemory.Counter tiny = new RtMemory.Counter(3 * model.blasBytes(1000));
        BlasBuildQueue q2 = new BlasBuildQueue(tiny, model);
        q2.camera(0, 0, 0);
        for (int x = 0; x < 5; x++) q2.sectionBuilt(sec(x, 0, 0, 1000, 1));
        int built = q2.take().size();
        check("memory refusal: 3 fit, 2 wait in the queue (" + built + ", refused " + q2.refusedThisFrame + ", pending " + q2.pendingCount() + ")",
                built == 3 && q2.refusedThisFrame == 2 && q2.pendingCount() == 2);
        BlasBuildQueue q3 = new BlasBuildQueue(new RtMemory.Counter(1L << 30), model);
        q3.maxTriangles = 5000;
        q3.camera(0, 0, 0);
        for (int x = 0; x < 5; x++) q3.sectionBuilt(sec(x, 0, 0, 1000, 1));
        check("triangle budget per frame (2 x 2000 tris fit in 5000)", q3.take().size() == 2);
        q3.clear();
        check("clear (world unload) frees everything", q3.residentCount() == 0 && q3.pendingCount() == 0 && q3.drainFrees().size() == 2);

        // ---- frame data layout
        check("RtFrameData packs to the shader's 608-byte block", new RtFrameData().write(ByteBuffer.allocate(608)).position() == RtFrameData.BYTES);

        // ---- presets
        float last = 0;
        boolean mono = true;
        for (RtPreset p : RtPreset.values()) {
            float r = p.expectedRaysPerPixel(0.52f, 0.46f, 0.76f, 0.02f);
            System.out.printf(Locale.ROOT, "  preset %-11s rays/px %.2f  M=%d  render scale %.1f%n", p, r, p.lightCandidates, p.renderScale);
            mono &= r >= last;
            last = r;
        }
        check("presets order by cost (rays per pixel never falls from LOW to PATH_TRACED)", mono);
        System.out.println("  ESTIMATED frame cost (Radiance datum, UNVERIFIED) at the worst measured rays/px:");
        for (RtPreset p : RtPreset.values()) {
            long px1707 = 1707L * 960, pxOut = Math.round(2560 / p.renderScale) * Math.round(1440 / p.renderScale);
            System.out.printf(Locale.ROOT, "  preset %-11s %.2f rays/px: at 1707x960 ~%.1f ms; at its own render size for 2560x1440 output (%d px) ~%.1f ms%n",
                    p, p.measuredWorstRaysPerPixel(), RtPreset.estimateMs(p.measuredWorstRaysPerPixel(), px1707), pxOut, RtPreset.estimateMs(p.measuredWorstRaysPerPixel(), pxOut));
        }
        check("ULTRA renders at native resolution and costs at least HIGH", RtPreset.ULTRA.renderScale == 1.0f && RtPreset.ULTRA.measuredWorstRaysPerPixel() >= RtPreset.HIGH.measuredWorstRaysPerPixel());

        // ---- memory model table (docs/P5-PLAN.md)
        System.out.println("  memory model (RADV ASSUMED 64 B/triangle compacted; quads per non-empty section 1000 / 3000 / 6000; 8 non-empty sections per column):");
        for (int rd : new int[]{8, 12, 16, 24}) {
            long sections = RtMemoryModel.columns(rd) * 8;
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "    RD %2d: %6d sections:", rd, sections));
            for (int qps : new int[]{1000, 3000, 6000})
                sb.append(String.format(Locale.ROOT, "  %4d q/s -> %6.0f MiB", qps, model.total(sections, qps, 1 << 20, 1707L * 960, 64) / 1048576.0));
            System.out.println(sb);
        }
        System.out.printf(Locale.ROOT, "    per section: %d quads -> %.0f KiB BLAS (RADV assumed), %.0f KiB on lavapipe (measured 104 B/tri)%n", 3000,
                model.blasBytes(3000) / 1024.0, new RtMemoryModel(RtMemoryModel.LAVAPIPE_BYTES_PER_TRIANGLE, 0.4, 8).blasBytes(3000) / 1024.0);
        RtAdapterTest.run();
        System.out.println("# rtseam " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }

    /**
     * P5-2: the only RT classes code outside dev/elemore/iroh/rt/ may name (each entry point starts with the RtSwitch.ON or
     * RtDeviceFeatures.enabled read, or is a launch-time decision), plus the P6 denoiser package (its own feature).
     */
    static final Set<String> HOOK_CLASSES = Set.of("rt/game/RtDeviceFeatures", "rt/game/RtVma", "rt/game/RtFrameHooks", "rt/game/RtMeshMode",
            "rt/game/RtStartup", "rt/game/RtSwitch", "rt/game/RtFailures", "rt/game/RtLookArbiter");
    /** P5-2: the pinned hook sites (merge notes in knowledge/rt-iroh.md "Integration notes"); int-3 adds StartupReport, IrohOptions, the Iroh settings section. */
    static final Set<String> HOOK_SITES = Set.of("net/vulkanmod/vulkan/device/DeviceManager", "net/vulkanmod/vulkan/Vulkan",
            "net/vulkanmod/render/chunk/buffer/AreaBuffer", "net/vulkanmod/render/chunk/WorldRenderer",
            "net/vulkanmod/render/chunk/build/frapi/render/AbstractBlockRenderContext", "dev/elemore/iroh/gpu/game/IrohGpuDevice",
            "dev/elemore/iroh/diag/StartupReport", "dev/elemore/iroh/config/IrohOptions", "dev/elemore/iroh/ui/IrohSettingsSection");

    /** Main classes outside dev/elemore/iroh/rt/ naming an RT class that is not a hook class, or from a site not pinned. */
    static List<String> outsideReferences() throws IOException {
        Path root;
        try {
            root = Path.of(RtRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IOException(e);
        }
        java.util.regex.Pattern ref = java.util.regex.Pattern.compile("dev/elemore/iroh/(rt/[A-Za-z0-9_/$]+)");
        List<String> hits = new ArrayList<>();
        Set<String> sites = new TreeSet<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".class"))::iterator) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("dev/elemore/iroh/rt/")) continue;
                String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                java.util.regex.Matcher m = ref.matcher(text);
                String site = rel.replaceAll("(\\$[^/]*)?\\.class$", "");
                while (m.find()) {
                    String c = m.group(1).replaceAll("\\$.*", "");
                    if (c.startsWith("rt/denoise/")) continue;
                    if (!HOOK_CLASSES.contains(c) || !HOOK_SITES.contains(site)) hits.add(site + " -> " + c);
                    else sites.add(site);
                }
            }
        }
        System.out.println("  RT hook sites found: " + sites);
        if (!hits.isEmpty()) System.out.println("  references from outside: " + hits);
        return hits;
    }
}
