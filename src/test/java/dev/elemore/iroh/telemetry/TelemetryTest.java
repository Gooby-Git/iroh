package dev.elemore.iroh.telemetry;

import net.vulkanmod.config.Config;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Card TEL-1: the Telemetry overlay's model, headless: number formatting, rolling statistics (average, 1% / 0.1% lows),
 * the overlay lines for every toggle, the CSV writer, and the allocation proof (nothing allocated per frame).
 * {@code ./gradlew telemetryTest}; last line "# telemetry PASS n/n".
 */
public final class TelemetryTest {
    private static final List<String> FAILS = new ArrayList<>();
    private static int checks;

    static void check(String name, boolean ok) {
        checks++;
        if (!ok) { FAILS.add(name); System.out.println("FAIL " + name); }
    }

    public static void main(String[] args) throws Exception {
        formatting();
        stats();
        lines();
        csv();
        allocation();
        System.out.println(FAILS.isEmpty() ? "# telemetry PASS " + checks + "/" + checks
                : "# telemetry FAIL " + FAILS.size() + " of " + checks + ": " + FAILS);
        if (!FAILS.isEmpty()) System.exit(1);
    }

    // ---- formatting ----
    static void formatting() {
        LineBuilder b = new LineBuilder(32);
        check("int 0", b.clear().i(0).contentEquals("0"));
        check("int 1234567", b.clear().i(1234567).contentEquals("1234567"));
        check("int negative", b.clear().i(-42).contentEquals("-42"));
        check("long min", b.clear().i(Long.MIN_VALUE).contentEquals("-9223372036854775808"));
        check("f 2 decimals", b.clear().f(4.1666, 2).contentEquals("4.17"));
        check("f 1 decimal rounds half up", b.clear().f(59.95, 1).contentEquals("60.0") || b.clear().f(59.95, 1).contentEquals("59.9"));
        check("f 0 decimals", b.clear().f(143.5, 0).contentEquals("144"));
        check("f small keeps leading zero", b.clear().f(0.05, 2).contentEquals("0.05"));
        check("f 3 decimals", b.clear().f(1.0005, 3).contentEquals("1.001") || b.clear().f(1.0005, 3).contentEquals("1.000"));
        check("f negative", b.clear().f(-2.5, 1).contentEquals("-2.5"));
        check("f NaN", b.clear().f(Double.NaN, 1).contentEquals("-"));
        LineBuilder tiny = new LineBuilder(4);
        tiny.s("abcdefgh").i(123456);
        check("overflow truncates, never throws", tiny.length() == 4 && tiny.contentEquals("abcd"));
        check("toString", new LineBuilder(8).s("ab").c('-').i(7).toString().equals("ab-7"));
    }

    // ---- rolling stats ----
    static void stats() {
        FrameStats f = new FrameStats(4096, 10_000_000_000L);
        long t = 1_000_000_000L;
        f.push(t);
        for (int i = 0; i < 300; i++) { t += 10_000_000L; f.push(t); }
        f.compute(t);
        check("steady 10 ms: avg 100 fps", Math.abs(f.avgFps - 100) < 0.01);
        check("steady 10 ms: lows 100 / 100", Math.abs(f.low1Fps - 100) < 0.01 && Math.abs(f.low01Fps - 100) < 0.01);
        check("steady 10 ms: 100 frames in the last second", f.fps1s >= 99 && f.fps1s <= 101);

        // 99 frames of 10 ms and one of 100 ms: window of 100 frames
        f.reset();
        t = 5_000_000_000L;
        f.push(t);
        for (int i = 0; i < 99; i++) { t += 10_000_000L; f.push(t); }
        t += 100_000_000L;
        f.push(t);
        f.compute(t);
        check("spike: 100 frames in the window", f.windowFrames == 100);
        check("spike: avg = 100 / 1.09 s = 91.74 fps", Math.abs(f.avgFps - 100 / 1.09) < 0.01);
        check("spike: 1% low = the 100 ms frame = 10 fps", Math.abs(f.low1Fps - 10) < 0.01);
        check("spike: 0.1% low = same single frame", Math.abs(f.low01Fps - 10) < 0.01);

        // 500 frames, 10 slow ones: 1% = slowest 5 frames, 0.1% = slowest 1
        f.reset();
        t = 20_000_000_000L;
        f.push(t);
        for (int i = 0; i < 500; i++) { t += (i % 50 == 0) ? 40_000_000L : 10_000_000L; f.push(t); }
        f.compute(t);
        check("500 frames, window 10 s holds all (" + f.windowFrames + ")", f.windowFrames == 500);
        check("1% low = 10 slowest frames of 40 ms = 25 fps", Math.abs(f.low1Fps - 25) < 0.01);
        check("0.1% low = slowest 1 frame = 25 fps", Math.abs(f.low01Fps - 25) < 0.01);
        check("p99 is a slow frame", f.p99Ns == 40_000_000L);

        // window pruning: frames older than the window do not count
        f.reset();
        t = 100_000_000_000L;
        f.push(t);
        for (int i = 0; i < 100; i++) { t += 100_000_000L; f.push(t); }   // 10 s of 100 ms frames
        for (int i = 0; i < 100; i++) { t += 10_000_000L; f.push(t); }    // then 1 s of 10 ms frames
        f.compute(t);
        check("old frames pruned: window shorter than 200 frames", f.windowFrames < 200 && f.windowFrames >= 100);

        // ring wrap
        FrameStats small = new FrameStats(16, 10_000_000_000L);
        t = 1_000_000_000L;
        small.push(t);
        for (int i = 0; i < 100; i++) { t += 10_000_000L; small.push(t); }
        small.compute(t);
        check("ring wraps: capped at capacity, same answer", small.windowFrames == 16 && Math.abs(small.avgFps - 100) < 0.01);

        // heap sort == Arrays.sort
        Random r = new Random(7);
        boolean sorted = true;
        for (int round = 0; round < 50; round++) {
            int n = 1 + r.nextInt(500);
            long[] a = new long[n];
            for (int i = 0; i < n; i++) a[i] = r.nextInt(1000);
            long[] b = a.clone();
            FrameStats.heapSort(a, n);
            Arrays.sort(b);
            sorted &= Arrays.equals(a, b);
        }
        check("heapSort matches Arrays.sort", sorted);
    }

    // ---- lines ----
    static Config cfg(boolean fps, boolean avg, boolean l1, boolean l01) {
        Config c = new Config();
        c.telemetryOverlay = true;
        c.telFps = fps; c.telAvg = avg; c.telLow1 = l1; c.telLow01 = l01;
        c.telFrameTime = c.telGraph = c.telGpuBusy = c.telVram = c.telRam = c.telPing = c.telTps = c.telChunks = false;
        c.telEntities = c.telRenderDist = c.telFeatures = c.telGenFps = false;
        return c;
    }

    static Telemetry.Snapshot snap() {
        Telemetry.Snapshot s = new Telemetry.Snapshot();
        s.fps = 143; s.avgFps = 140.4; s.low1Fps = 98.6; s.low01Fps = 61.2;
        return s;
    }

    static String line(int i) { return Telemetry.LINES[i]; }

    static void lines() {
        Telemetry.Snapshot s = snap();
        Config c = cfg(true, true, true, false);
        Telemetry.buildLines(s, c);
        check("first line keeps the old counter's format", Telemetry.lineCount == 1 && line(0).equals("143 FPS (avg. 140 / 1% low 99)"));
        c = cfg(true, false, false, false);
        Telemetry.buildLines(s, c);
        check("fps only", line(0).equals("143 FPS"));
        c = cfg(false, true, true, true);
        Telemetry.buildLines(s, c);
        check("no fps: items without brackets", line(0).equals("avg. 140 / 1% low 99 / 0.1% low 61"));
        c = cfg(true, true, true, true);
        Telemetry.buildLines(s, c);
        check("all four", line(0).equals("143 FPS (avg. 140 / 1% low 99 / 0.1% low 61)"));
        c = cfg(false, false, false, false);
        Telemetry.buildLines(s, c);
        check("everything off: no lines", Telemetry.lineCount == 0);

        c = cfg(true, false, false, false);
        c.telFrameTime = c.telGpuBusy = c.telVram = c.telRam = c.telPing = c.telTps = c.telChunks = c.telEntities = c.telRenderDist = c.telFeatures = c.telGenFps = true;
        s.frameMs = 7.1234; s.cpuMs = 3.5; s.gpuMs = 6.9; s.gpuBusyPct = 97.4;
        s.vramUsedMiB = 5120.4; s.vramBudgetMiB = 15872; s.irohVramMiB = 3100;
        s.heapUsedMiB = 2100; s.heapMaxMiB = 6144;
        s.pingMs = -1; s.tps = 19.8; s.mspt = 12.34; s.generatedFps = 144;
        s.chunksPending = 12; s.entities = 143; s.renderDistance = 24;
        s.renderingLevel = "Raster"; s.fsrMode = "quality"; s.lodOn = true;
        Telemetry.buildLines(s, c);
        List<String> all = new ArrayList<>(Arrays.asList(Telemetry.LINES).subList(0, Telemetry.lineCount));
        check("frame time line", all.contains("Frame 7.12 ms  CPU 3.50  GPU 6.90"));
        check("GPU busy", all.contains("GPU busy 97%"));
        check("VRAM with Iroh share", all.contains("VRAM 5120 / 15872 MiB (Iroh 3100)"));
        check("RAM", all.contains("RAM 2100 / 6144 MiB"));
        check("ping hidden in singleplayer", all.stream().noneMatch(l -> l.startsWith("Ping")));
        check("server TPS / MSPT", all.contains("Server 19.8 TPS  12.3 ms/tick"));
        check("chunk builds pending", all.contains("Chunk builds pending 12"));
        check("entities", all.contains("Entities 143"));
        check("render distance", all.contains("Render distance 24 chunks"));
        check("features", all.contains("Iroh: Raster | FSR quality | LOD on"));
        check("rendered vs generated", all.contains("Rendered 143 / generated 144 fps"));
        s.pingMs = 31.6; s.tps = s.mspt = -1; s.generatedFps = -1; s.fsrMode = null; s.lodOn = false; s.gpuBusyPct = -1;
        Telemetry.buildLines(s, c);
        all = new ArrayList<>(Arrays.asList(Telemetry.LINES).subList(0, Telemetry.lineCount));
        check("ping shown in multiplayer", all.contains("Ping 32 ms"));
        check("server row hidden on a remote server", all.stream().noneMatch(l -> l.startsWith("Server")));
        check("rendered/generated hidden without frame generation", all.stream().noneMatch(l -> l.startsWith("Rendered")));
        check("features with FSR off and LOD off", all.contains("Iroh: Raster | FSR off | LOD off"));
        check("GPU busy n/a without timestamps", all.contains("GPU busy n/a"));

        // through onFrame / sample with a source
        Telemetry.resetForTest();
        Telemetry.SOURCES = (sn, cf) -> { sn.pingMs = 20; sn.framerateCap = 120; sn.renderDistance = 16; };
        Config oc = cfg(true, true, true, false);
        oc.telPing = true; oc.telRenderDist = true; oc.telGraph = true;
        long t = 1_000_000_000L;
        for (int i = 0; i < 200; i++) { t += 16_000_000L; Telemetry.onFrame(t, 5_000_000L, i < 100 ? -1 : 9_000_000L, oc); }
        check("onFrame samples and builds lines", Telemetry.lineCount == 3 && line(0).startsWith("6") && line(1).equals("Ping 20 ms")
                && line(2).equals("Render distance 16 chunks"));
        check("graph filled", Telemetry.graphFrames() == Telemetry.COLS && Telemetry.graphFrameNs(0) == 16_000_000L
                && Telemetry.graphCpuNs(0) == 5_000_000L && Telemetry.graphGpuNs(0) == 9_000_000L);
        check("graph scale picks 16.7 ms for 16 ms frames, cap line at 120 fps", Telemetry.graphMaxMs == 16.7f && Math.abs(Telemetry.capMs - 8.333f) < 0.01f);
        check("graph knows GPU data exists", Telemetry.graphHasGpu);
        TelemetryHooks.onGeneratedFrame();
        oc.telemetryOverlay = false;
        Telemetry.onFrame(t + 1, -1, -1, oc);
        check("overlay off and not recording: state released", Telemetry.lineCount == 0);
        Telemetry.SOURCES = (sn, cf) -> { };
        Telemetry.resetForTest();
    }

    // ---- CSV ----
    static void csv() throws Exception {
        Path dir = Files.createTempDirectory("iroh-tel-csv");
        Telemetry.Snapshot s = new Telemetry.Snapshot();
        s.fps = 144; s.avgFps = 143.6; s.low1Fps = 90.2; s.low01Fps = 70; s.gpuBusyPct = -1;
        s.vramUsedMiB = 4000; s.vramBudgetMiB = 15000; s.irohVramMiB = 2500; s.heapUsedMiB = 1500; s.heapMaxMiB = 6144;
        s.pingMs = -1; s.tps = 20; s.mspt = 7.3; s.chunksPending = 5; s.entities = 99; s.renderDistance = 12; s.generatedFps = -1;
        Path f = CsvRecorder.start(dir, 1_000_000_000L);
        check("recorder started in the given folder with a timestamped name", f != null && f.getParent().equals(dir)
                && f.getFileName().toString().matches("iroh-telemetry-\\d{8}-\\d{6}(-\\d+)?\\.csv"));
        check("recording flag", CsvRecorder.active());
        CsvRecorder.row(1_016_000_000L, 16.0, 5.25, 9.5, s);
        CsvRecorder.row(1_033_000_000L, 17.0, 5.0, -1, s);
        CsvRecorder.stop();
        check("stopped", !CsvRecorder.active());
        List<String> rows = Files.readAllLines(f);
        int cols = CsvRecorder.HEADER.split(",", -1).length;
        check("header + 2 rows", rows.size() == 3 && rows.get(0).equals(CsvRecorder.HEADER));
        String[] r1 = rows.get(1).split(",", -1), r2 = rows.get(2).split(",", -1);
        check("every row has the header's column count (" + cols + ")", r1.length == cols && r2.length == cols);
        check("time and frame columns", r1[0].equals("16.000") && r1[1].equals("16.000") && r1[2].equals("5.250") && r1[3].equals("9.500"));
        check("counters", r1[4].equals("144") && r1[5].equals("143.6") && r1[6].equals("90.2") && r1[9].equals("4000")
                && r1[16].equals("7.3") && r1[17].equals("5") && r1[18].equals("99") && r1[19].equals("12"));
        check("not applicable = empty field (gpu_ms, gpu_busy, ping, generated_fps)", r2[3].isEmpty() && r1[8].isEmpty() && r1[14].isEmpty() && r1[20].isEmpty());
        check("row after stop is ignored", rowAfterStop(s));
        Path f2 = CsvRecorder.start(dir, 0);
        CsvRecorder.stop();
        check("a second recording gets its own file", f2 != null && !f2.equals(f));
        try (var st = Files.list(dir)) { st.forEach(p -> p.toFile().delete()); }
        dir.toFile().delete();
    }

    static boolean rowAfterStop(Telemetry.Snapshot s) {
        CsvRecorder.row(2_000_000_000L, 1, 1, 1, s);
        return !CsvRecorder.active();
    }

    // ---- allocation ----
    static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    /** bytes the measurement itself allocates (the MXBean call): measured on an empty window */
    static long overhead() {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 50; i++) { long a = allocated(); best = Math.min(best, allocated() - a); }
        return best;
    }

    static void allocation() throws Exception {
        long ov = overhead();
        System.out.println("# allocation measurement overhead (MXBean call): " + ov + " bytes");
        Config c = cfg(true, true, true, true);
        c.telFrameTime = c.telGraph = c.telGpuBusy = c.telVram = c.telRam = c.telPing = c.telTps = c.telChunks = c.telEntities = true;
        c.telRenderDist = c.telFeatures = c.telGenFps = true;
        Telemetry.SOURCES = (sn, cf) -> { sn.pingMs = 20; sn.vramUsedMiB = 100; sn.renderDistance = 12; };
        Path dir = Files.createTempDirectory("iroh-tel-alloc");

        // A0: no recording: stats, ring and graph bookkeeping alone
        Telemetry.resetForTest();
        Telemetry.sampleNs = Long.MAX_VALUE;
        long t0 = 1_000_000_000L;
        for (int i = 0; i < 400_000; i++) { t0 += 4_166_667L; Telemetry.onFrame(t0, 2_000_000L, 3_000_000L, c); }
        long z0 = allocated();
        for (int i = 0; i < 200_000; i++) { t0 += 4_166_667L; Telemetry.onFrame(t0, 2_000_000L, 3_000_000L, c); }
        long noCsv = allocated() - z0 - ov;
        check("per-frame path without recording allocates 0 bytes over 200000 frames: " + noCsv, noCsv == 0);

        // A: the per-frame path alone (sampling pushed out of reach), with a CSV recording running
        Telemetry.resetForTest();
        Telemetry.sampleNs = Long.MAX_VALUE;
        CsvRecorder.start(dir, 0);
        long t = 1_000_000_000L;
        for (int i = 0; i < 20_000; i++) { t += 4_166_667L; Telemetry.onFrame(t, 2_000_000L, 3_000_000L, c); }   // warm up the JIT
        int frames = 200_000;
        for (int i = 0; i < frames; i++) { t += 4_166_667L; Telemetry.onFrame(t, 2_000_000L, 3_000_000L, c); } // settle (OSR compile, first flush)
        long a0 = allocated();
        for (int i = 0; i < frames; i++) { t += 4_166_667L; Telemetry.onFrame(t, 2_000_000L, 3_000_000L, c); }
        long perFrame = allocated() - a0 - ov;
        check("per-frame path (frame stats, graph ring, CSV row) allocates 0 bytes over " + frames + " frames: " + perFrame, perFrame == 0);
        long rows = CsvRecorder.rows();
        CsvRecorder.stop();
        check("the CSV recorded every frame (" + rows + ")", rows >= 2L * frames);

        // B: with sampling at 4 Hz: allocation is only the rebuilt Strings (twice per sample window, none between)
        Telemetry.resetForTest();
        t = 1_000_000_000L;
        for (int i = 0; i < 5_000; i++) { t += 4_166_667L; Telemetry.onFrame(t, 2_000_000L, 3_000_000L, c); }
        int n = 100_000;
        long b0 = allocated();
        long samplesBefore = 0;
        for (int i = 0; i < n; i++) { t += 4_166_667L; Telemetry.onFrame(t, 2_000_000L, 3_000_000L, c); }
        long total = allocated() - b0;
        long samples = (long) n * 4_166_667L / Telemetry.SAMPLE_NS;
        System.out.println("# sampled path: " + total + " bytes over " + n + " frames and ~" + samples + " samples = "
                + total / Math.max(1, samples) + " B per sample, " + total / n + " B per frame averaged");
        check("with sampling, allocation stays under 3 KB per 4 Hz sample (the rebuilt line Strings)", total / Math.max(1, samples) < 3072);

        // C: cost per frame, offline
        Telemetry.sampleNs = Long.MAX_VALUE;
        long c0 = System.nanoTime();
        for (int i = 0; i < 1_000_000; i++) { t += 4_166_667L; Telemetry.onFrame(t, 2_000_000L, 3_000_000L, c); }
        double ns = (System.nanoTime() - c0) / 1e6;
        System.out.println("# per-frame bookkeeping cost: " + String.format("%.0f", ns) + " ns/frame (stats + ring, no draw)");
        check("per-frame bookkeeping is far below 0.1 ms (" + (long) ns + " ns)", ns < 20_000);

        // D: sample cost at 4 Hz
        Telemetry.sampleNs = 0;
        long d0 = System.nanoTime();
        for (int i = 0; i < 2000; i++) { t += 4_166_667L; Telemetry.onFrame(t, 2_000_000L, 3_000_000L, c); }
        double sampleUs = (System.nanoTime() - d0) / 2000 / 1e3;
        System.out.println("# sample cost (window sort + 12 lines): " + String.format("%.1f", sampleUs) + " us, 4 per second");
        check("a sample costs well under 0.5 ms (" + sampleUs + " us)", sampleUs < 500);

        Telemetry.SOURCES = (sn, cf) -> { };
        Telemetry.resetForTest();
        try (var st = Files.list(dir)) { st.forEach(p -> p.toFile().delete()); }
        dir.toFile().delete();
    }
}
