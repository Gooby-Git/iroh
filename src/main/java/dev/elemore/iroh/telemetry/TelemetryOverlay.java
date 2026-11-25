package dev.elemore.iroh.telemetry;

import dev.elemore.iroh.config.IrohOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.vulkanmod.config.Config;

/**
 * The on-screen Telemetry overlay (card TEL-1; replaces the old FPS counter, whose "N FPS (avg. A / 1% low L)" line is the
 * first line when those three items are on). Drawn at the end of the HUD, hidden with F1 and while F3 is open. The
 * per-frame work is {@link Telemetry#onFrame} (allocation-free) plus the draw calls; text is only rebuilt at 4 Hz.
 */
public final class TelemetryOverlay {
    private TelemetryOverlay() {}

    static final int GRAPH_W = Telemetry.COLS * 3, GRAPH_H = 40, PAD = 2, LINE_H = 10;
    private static boolean installed, gpuWasOn;
    private static final int[] lineW = new int[Telemetry.LINES.length];
    private static String[] shownLines = new String[Telemetry.LINES.length];
    private static int maxTextW;

    /** Call once per rendered frame (the HUD draw is that point). */
    public static void render(GuiGraphics g, boolean debugScreenOpen) {
        Config c = IrohOptions.config();
        if (!installed) {
            Telemetry.SOURCES = new McSources();
            installed = true;
        }
        if (!Telemetry.wanted(c)) {
            if (gpuWasOn) { TelemetryGpu.ON = false; TelemetryGpu.clear(); gpuWasOn = false; }
            Telemetry.onFrame(0, -1, -1, c); // resets its state once
            return;
        }
        long now = System.nanoTime();
        long cpuStart = TelemetryGpu.consumeCpuStart();
        long cpu = TelemetryGpu.ON && cpuStart != 0 ? now - cpuStart : -1;
        long gpu = TelemetryGpu.ON ? TelemetryGpu.lastGpuNs() : -1;
        Telemetry.onFrame(now, cpu, gpu, c);
        boolean gpuNow = Telemetry.needsGpu(c);
        if (gpuWasOn && !gpuNow) TelemetryGpu.clear();
        TelemetryGpu.ON = gpuNow;
        gpuWasOn = gpuNow;

        Minecraft mc = Minecraft.getInstance();
        if (!c.telemetryOverlay || mc.options.hideGui || debugScreenOpen || Telemetry.lineCount == 0 && !c.telGraph) return;
        draw(g, mc.font, c);
    }

    private static void measure(Font font) {
        // widths change only when the sampled text does
        boolean same = true;
        for (int i = 0; i < Telemetry.lineCount; i++) if (shownLines[i] != Telemetry.LINES[i]) { same = false; break; }
        if (same) return;
        maxTextW = 0;
        for (int i = 0; i < Telemetry.lineCount; i++) {
            shownLines[i] = Telemetry.LINES[i];
            lineW[i] = font.width(shownLines[i]);
            maxTextW = Math.max(maxTextW, lineW[i]);
        }
    }

    private static void draw(GuiGraphics g, Font font, Config c) {
        measure(font);
        float scale = Math.max(50, Math.min(200, c.telScale)) / 100f;
        int alpha = Math.max(10, Math.min(100, c.telOpacity)) * 255 / 100;
        int rgb = Telemetry.colour(c).rgb;
        int textCol = alpha << 24 | rgb;
        boolean graph = c.telGraph;
        int lines = Telemetry.lineCount;
        int w = Math.max(maxTextW, graph ? GRAPH_W : 0);
        int h = lines * LINE_H + (graph ? GRAPH_H + PAD : 0);
        float sw = (w + PAD) * scale, sh = (h + PAD) * scale;
        int gw = g.guiWidth(), gh = g.guiHeight();
        Telemetry.Position pos = Telemetry.position(c);
        float x = pos == Telemetry.Position.TOP_LEFT || pos == Telemetry.Position.BOTTOM_LEFT ? PAD : gw - sw;
        float y = pos == Telemetry.Position.TOP_LEFT || pos == Telemetry.Position.TOP_RIGHT ? PAD : gh - sh;

        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1f);
        boolean right = pos == Telemetry.Position.TOP_RIGHT || pos == Telemetry.Position.BOTTOM_RIGHT;
        for (int i = 0; i < lines; i++)
            g.drawString(font, shownLines[i], right ? w - lineW[i] : 0, i * LINE_H, textCol, true);
        if (graph) drawGraph(g, 0, lines * LINE_H + PAD, alpha);
        pose.popPose();
    }

    private static void drawGraph(GuiGraphics g, int x0, int y0, int alpha) {
        int n = Telemetry.graphFrames();
        float maxMs = Telemetry.graphMaxMs;
        int bg = (alpha / 2) << 24;
        g.fill(x0, y0, x0 + GRAPH_W, y0 + GRAPH_H, bg);
        int bottom = y0 + GRAPH_H;
        int dim = (alpha / 3) << 24;
        int cpuCol = alpha << 24 | 0xFFAA00, gpuCol = alpha << 24 | 0x55FFFF;
        for (int i = 0; i < n; i++) {
            int col = Telemetry.COLS - 1 - i;
            int x = x0 + col * 3;
            double ms = Telemetry.graphFrameNs(i) / 1e6;
            int hh = px(ms, maxMs);
            if (hh > 0) g.fill(x, bottom - hh, x + 3, bottom, dim | (ms <= 16.7 ? 0x55FF55 : ms <= 33.4 ? 0xFFFF55 : 0xFF5555));
            long cpu = Telemetry.graphCpuNs(i), gpu = Telemetry.graphGpuNs(i);
            if (cpu >= 0) { int ch = px(cpu / 1e6, maxMs); if (ch > 0) g.fill(x, bottom - ch, x + 1, bottom, cpuCol); }
            if (gpu >= 0) { int gh = px(gpu / 1e6, maxMs); if (gh > 0) g.fill(x + 1, bottom - gh, x + 2, bottom, gpuCol); }
        }
        // 60 fps guide and the frame cap line
        int guide = px(16.667, maxMs);
        if (guide < GRAPH_H) g.fill(x0, bottom - guide, x0 + GRAPH_W, bottom - guide + 1, alpha / 3 << 24 | 0xFFFFFF);
        int cap = px(Telemetry.capMs, maxMs);
        if (cap > 0 && cap < GRAPH_H) g.fill(x0, bottom - cap, x0 + GRAPH_W, bottom - cap + 1, alpha << 24 | 0xFF5555);
    }

    private static int px(double ms, float maxMs) {
        return (int) Math.min(GRAPH_H, ms / maxMs * GRAPH_H);
    }
}
