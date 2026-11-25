package dev.elemore.iroh.telemetry;

import net.vulkanmod.config.Config;

/**
 * Telemetry core (card TEL-1): per-frame bookkeeping and the text of the overlay. Pure Java (the Minecraft and Vulkan
 * sides are {@link Sources} and {@link TelemetryGpu}), so it is unit tested offline. Render thread only.
 *
 * Cost rules: {@link #onFrame} is allocation-free and does O(1) work except at the sample rate (4 Hz), where the window
 * statistics are recomputed and the displayed Strings rebuilt (those few Strings are the only allocation). With the overlay
 * off and no recording it returns at its first statement.
 */
public final class Telemetry {
    private Telemetry() {}

    public enum Position { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public enum Colour {
        WHITE(0xFFFFFF), GREEN(0x55FF55), CYAN(0x55FFFF), YELLOW(0xFFFF55), ORANGE(0xFFAA00), PINK(0xFF77CC);
        public final int rgb;
        Colour(int rgb) { this.rgb = rgb; }
    }

    /** GLFW key codes (literal: GLFW_KEY_F9 = 298 ...). */
    public enum Hotkey {
        F9(298), F7(296), F12(301), PAUSE(284), SCROLL_LOCK(281), INSERT(260), HOME(268), END(269), NONE(-1);
        public final int key;
        Hotkey(int key) { this.key = key; }
    }

    /** One sample of everything the overlay and the CSV show. Negative = not available / not applicable. */
    public static final class Snapshot {
        public double fps, avgFps, low1Fps, low01Fps;
        public double frameMs = -1, cpuMs = -1, gpuMs = -1, gpuBusyPct = -1;
        public double vramUsedMiB = -1, vramBudgetMiB = -1, irohVramMiB = -1, heapUsedMiB = -1, heapMaxMiB = -1;
        public double pingMs = -1, tps = -1, mspt = -1, generatedFps = -1;
        public int chunksPending = -1, entities = -1, renderDistance = -1;
        public String renderingLevel = "Raster";
        public String fsrMode;
        public boolean lodOn;
        public double framerateCap = 240;
    }

    /** Fills the sources that live in the game (ping, TPS, memory, chunk builds ...); set by the overlay at start-up. */
    public interface Sources {
        void fill(Snapshot s, Config c);
    }

    public static final long SAMPLE_NS = 250_000_000L;
    /** test seam: the sample interval */
    static long sampleNs = SAMPLE_NS;
    /** frames kept for the graph and the per-frame sums */
    public static final int RING = 512;
    /** columns of the graph, newest on the right */
    public static final int COLS = 64;

    public static final FrameStats STATS = new FrameStats(4096, 10_000_000_000L);
    public static final Snapshot S = new Snapshot();
    public static volatile Sources SOURCES = (s, c) -> { };

    private static final long[] gFrame = new long[RING], gCpu = new long[RING], gGpu = new long[RING];
    private static int gHead, gCount;

    /** displayed text, rebuilt at the sample rate */
    public static final String[] LINES = new String[20];
    public static int lineCount;
    /** top of the graph in ms and the cap line in ms (0 = none), rebuilt at the sample rate */
    public static float graphMaxMs = 16.7f, capMs = 4.1667f;
    public static boolean graphHasGpu;

    private static final LineBuilder LB = new LineBuilder(200);
    private static boolean running;
    private static long lastSample, lastGen;
    private static long lastGenTime;

    /** Overlay or recording wants frame data. */
    public static boolean wanted(Config c) { return c.telemetryOverlay || CsvRecorder.active(); }

    public static Position position(Config c) { return parse(Position.class, c.telPosition, Position.TOP_LEFT); }

    public static Colour colour(Config c) { return parse(Colour.class, c.telColor, Colour.WHITE); }

    public static Hotkey hotkey(Config c) { return parse(Hotkey.class, c.telHotkey, Hotkey.F9); }

    private static <E extends Enum<E>> E parse(Class<E> t, String s, E def) {
        try { return Enum.valueOf(t, s); } catch (RuntimeException e) { return def; }
    }

    /** GPU timestamps are only worth recording when something shows them. */
    static boolean needsGpu(Config c) {
        return c.telemetryOverlay && (c.telGraph || c.telGpuBusy || c.telFrameTime) || CsvRecorder.active();
    }

    /** Per rendered frame (the HUD draw is that point). {@code cpuNs} is the render-thread work time of this frame or -1. */
    public static void onFrame(long now, long cpuNs, long gpuNs, Config c) {
        if (!(c.telemetryOverlay || CsvRecorder.active())) {
            if (running) stopRunning();
            return;
        }
        if (!running) {
            running = true;
            STATS.reset();
            gHead = gCount = 0;
            lastSample = 0;
        }
        STATS.push(now);
        long frame = STATS.lastFrameNs();
        if (frame > 0) {
            gFrame[gHead] = frame;
            gCpu[gHead] = cpuNs;
            gGpu[gHead] = gpuNs;
            gHead++;
            if (gHead == RING) gHead = 0;
            if (gCount < RING) gCount++;
            if (CsvRecorder.active())
                CsvRecorder.row(now, frame / 1e6, cpuNs < 0 ? -1 : cpuNs / 1e6, gpuNs < 0 ? -1 : gpuNs / 1e6, S);
        }
        if (now - lastSample >= sampleNs) {
            lastSample = now;
            sample(now, c);
        }
    }

    private static void stopRunning() {
        running = false;
        lineCount = 0;
    }

    // ---- ring access for the graph (0 = newest) ----

    public static int graphFrames() { return Math.min(gCount, COLS); }

    public static long graphFrameNs(int back) { return gFrame[idx(back)]; }

    public static long graphCpuNs(int back) { return gCpu[idx(back)]; }

    public static long graphGpuNs(int back) { return gGpu[idx(back)]; }

    private static int idx(int back) {
        int i = gHead - 1 - back;
        return i < 0 ? i + RING : i;
    }

    // ---- sampling ----

    static void sample(long now, Config c) {
        STATS.compute(now);
        Snapshot s = S;
        s.fps = STATS.fps1s;
        s.avgFps = STATS.avgFps;
        s.low1Fps = STATS.low1Fps;
        s.low01Fps = STATS.low01Fps;

        // per-frame sums over the last ~0.5 s of the ring
        long sumF = 0, sumC = 0, sumG = 0;
        int nC = 0, nG = 0, nF = 0;
        double maxMs = 0;
        for (int i = 0; i < gCount && sumF < 500_000_000L; i++) {
            int k = idx(i);
            sumF += gFrame[k];
            nF++;
            if (gCpu[k] >= 0) { sumC += gCpu[k]; nC++; }
            if (gGpu[k] >= 0) { sumG += gGpu[k]; nG++; }
        }
        s.frameMs = nF == 0 ? -1 : sumF / 1e6 / nF;
        s.cpuMs = nC == 0 ? -1 : sumC / 1e6 / nC;
        s.gpuMs = nG == 0 ? -1 : sumG / 1e6 / nG;
        s.gpuBusyPct = nG == 0 ? -1 : Math.min(100.0, 100.0 * sumG / Math.max(1, sumGWindow(nG)));
        graphHasGpu = nG > 0;

        int cols = graphFrames();
        for (int i = 0; i < cols; i++) maxMs = Math.max(maxMs, gFrame[idx(i)] / 1e6);
        graphMaxMs = maxMs <= 8.34 ? 8.34f : maxMs <= 16.7 ? 16.7f : maxMs <= 33.4 ? 33.4f : maxMs <= 66.7 ? 66.7f : 133.4f;

        // frame generation: generated frames per second (-1 while none are generated)
        long gen = TelemetryHooks.generatedFrames();
        s.generatedFps = lastGenTime != 0 && gen > lastGen && now > lastGenTime ? (gen - lastGen) * 1e9 / (now - lastGenTime) : -1;
        lastGen = gen;
        lastGenTime = now;

        String lvl = TelemetryHooks.renderingLevel;
        s.renderingLevel = lvl == null ? "Raster" : lvl;
        s.lodOn = TelemetryHooks.lodOn;
        s.fsrMode = null;
        SOURCES.fill(s, c);
        capMs = s.framerateCap > 0 && s.framerateCap < 260 ? (float) (1000.0 / s.framerateCap) : 4.1667f;
        buildLines(s, c);
    }

    /** frame time of the frames that have a GPU value: a busy ratio must compare like with like */
    private static long sumGWindow(int nG) {
        long t = 0;
        int seen = 0;
        for (int i = 0; i < gCount && seen < nG; i++) {
            int k = idx(i);
            if (gGpu[k] >= 0) { t += gFrame[k]; seen++; }
        }
        return t;
    }

    static void buildLines(Snapshot s, Config c) {
        int n = 0;
        LineBuilder b = LB;
        // line 1: the old counter's format, "N FPS (avg. A / 1% low L)", with the extra lows appended
        b.clear();
        if (c.telFps) b.i(Math.round(s.fps)).s(" FPS");
        boolean extra = c.telAvg || c.telLow1 || c.telLow01;
        if (extra) {
            boolean paren = c.telFps;
            if (paren) b.s(" (");
            boolean first = true;
            if (c.telAvg) { b.s("avg. ").i(Math.round(s.avgFps)); first = false; }
            if (c.telLow1) { if (!first) b.s(" / "); b.s("1% low ").i(Math.round(s.low1Fps)); first = false; }
            if (c.telLow01) { if (!first) b.s(" / "); b.s("0.1% low ").i(Math.round(s.low01Fps)); }
            if (paren) b.c(')');
        }
        if (b.length() > 0) LINES[n++] = b.toString();
        if (c.telGenFps && s.generatedFps >= 0) {
            b.clear().s("Rendered ").i(Math.round(s.fps)).s(" / generated ").i(Math.round(s.generatedFps)).s(" fps");
            LINES[n++] = b.toString();
        }
        if (c.telFrameTime && s.frameMs >= 0) {
            b.clear().s("Frame ").f(s.frameMs, 2).s(" ms");
            if (s.cpuMs >= 0) b.s("  CPU ").f(s.cpuMs, 2);
            if (s.gpuMs >= 0) b.s("  GPU ").f(s.gpuMs, 2);
            LINES[n++] = b.toString();
        }
        if (c.telGpuBusy) {
            b.clear().s("GPU busy ");
            if (s.gpuBusyPct >= 0) b.i(Math.round(s.gpuBusyPct)).c('%'); else b.s("n/a");
            LINES[n++] = b.toString();
        }
        if (c.telVram) {
            b.clear().s("VRAM ");
            if (s.vramUsedMiB >= 0) {
                b.i(Math.round(s.vramUsedMiB));
                if (s.vramBudgetMiB > 0) b.s(" / ").i(Math.round(s.vramBudgetMiB));
                b.s(" MiB");
                if (s.irohVramMiB >= 0) b.s(" (Iroh ").i(Math.round(s.irohVramMiB)).c(')');
            } else b.s("n/a");
            LINES[n++] = b.toString();
        }
        if (c.telRam && s.heapUsedMiB >= 0) {
            b.clear().s("RAM ").i(Math.round(s.heapUsedMiB)).s(" / ").i(Math.round(s.heapMaxMiB)).s(" MiB");
            LINES[n++] = b.toString();
        }
        if (c.telPing && s.pingMs >= 0) {
            b.clear().s("Ping ").i(Math.round(s.pingMs)).s(" ms");
            LINES[n++] = b.toString();
        }
        if (c.telTps && s.tps >= 0) {
            b.clear().s("Server ").f(s.tps, 1).s(" TPS  ").f(s.mspt, 1).s(" ms/tick");
            LINES[n++] = b.toString();
        }
        if (c.telChunks && s.chunksPending >= 0) {
            b.clear().s("Chunk builds pending ").i(s.chunksPending);
            LINES[n++] = b.toString();
        }
        if (c.telEntities && s.entities >= 0) {
            b.clear().s("Entities ").i(s.entities);
            LINES[n++] = b.toString();
        }
        if (c.telRenderDist && s.renderDistance >= 0) {
            b.clear().s("Render distance ").i(s.renderDistance).s(" chunks");
            LINES[n++] = b.toString();
        }
        if (c.telFeatures) {
            b.clear().s("Iroh: ").s(s.renderingLevel).s(" | FSR ").s(s.fsrMode == null ? "off" : s.fsrMode)
                    .s(" | LOD ").s(s.lodOn ? "on" : "off");
            LINES[n++] = b.toString();
        }
        if (CsvRecorder.active()) {
            b.clear().s("REC ").i(CsvRecorder.rows()).s(" rows");
            LINES[n++] = b.toString();
        }
        lineCount = n;
    }

    /** Test seam: forget everything (keeps SOURCES). */
    public static void resetForTest() {
        running = false;
        STATS.reset();
        gHead = gCount = 0;
        lineCount = 0;
        lastSample = lastGen = lastGenTime = 0;
        sampleNs = SAMPLE_NS;
        TelemetryHooks.renderingLevel = null;
    }
}
