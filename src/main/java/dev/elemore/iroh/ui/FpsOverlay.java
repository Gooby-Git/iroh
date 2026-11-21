package dev.elemore.iroh.ui;

import dev.elemore.iroh.config.IrohOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Arrays;

/**
 * The FPS counter the pack had from Sodium Extra (show_fps, extended, top left, no text contrast). Layout measured from the
 * OpenGL baseline shots (gl-s2, GUI scale 3): white text with the normal drop shadow, 2 GUI pixels in from the left and
 * top edge, drawn over the HUD, hidden while F3 is open. Text: "N FPS (avg. A / 1% low L)". Anything the baseline shows
 * after "1% low" is hidden behind the boss bar text in every shot, so the rest of Sodium Extra's line is UNVERIFIED and
 * not reproduced.
 */
public final class FpsOverlay {
    private FpsOverlay() {}

    private static final int WINDOW_FRAMES = 600;
    private static final long WINDOW_NANOS = 5_000_000_000L;

    private static final long[] times = new long[WINDOW_FRAMES]; // frame durations in ns, ring buffer
    private static final long[] stamps = new long[WINDOW_FRAMES];
    private static int head, count;
    private static long last;
    private static final long[] scratch = new long[WINDOW_FRAMES];

    /** Call once per rendered frame (the HUD draw is that point). */
    public static void render(GuiGraphics g, boolean debugScreenOpen) {
        long now = System.nanoTime();
        if (last != 0) {
            times[head] = now - last;
            stamps[head] = now;
            head = (head + 1) % WINDOW_FRAMES;
            if (count < WINDOW_FRAMES) count++;
        }
        last = now;

        Minecraft mc = Minecraft.getInstance();
        if (!IrohOptions.showFps() || mc.options.hideGui || debugScreenOpen) return;

        g.drawString(mc.font, text(mc.getFps(), now), 2, 2, 0xFFFFFF, true);
    }

    static String text(int fps, long now) {
        int n = 0;
        long sum = 0;
        for (int i = 0; i < count; i++) {
            int idx = (head - 1 - i + WINDOW_FRAMES) % WINDOW_FRAMES;
            if (now - stamps[idx] > WINDOW_NANOS) break;
            scratch[n++] = times[idx];
            sum += times[idx];
        }
        if (n == 0) return fps + " FPS";
        int avg = (int) Math.round(1e9 * n / sum);
        Arrays.sort(scratch, 0, n);
        // 1% low: the frame rate of the slowest 1% of frames (at least one frame)
        int worst = Math.max(1, n / 100);
        long worstSum = 0;
        for (int i = n - worst; i < n; i++) worstSum += scratch[i];
        int low = (int) Math.round(1e9 * worst / worstSum);
        return fps + " FPS (avg. " + avg + " / 1% low " + low + ")";
    }
}
