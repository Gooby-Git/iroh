package dev.elemore.iroh.shaderpack.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-pass GPU time accounting for the pack path (G1 item 4), free of Vulkan: the backend hands in one frame slot's
 * timestamp pairs when the slot comes round again (fence passed: results ready, never waited for). Averages over a
 * window; {@link #line()} is one profile line, also published to IrohDebug as {@code iroh.pack.gpuTimes}.
 */
public final class PackGpuTimes {

    private final Map<String, double[]> sum = new LinkedHashMap<>(); // pass -> {ms, samples}
    private int frames;
    private String last = "no samples yet";

    /** one frame slot's pairs: names[i] spans ticks[2i]..ticks[2i+1]; available[i] false = skip that pair */
    public void addFrame(List<String> names, long[] ticks, boolean[] available, double periodNs) {
        boolean any = false;
        for (int i = 0; i < names.size(); i++) {
            if (!available[2 * i] || !available[2 * i + 1] || ticks[2 * i + 1] < ticks[2 * i])
                continue;
            double ms = (ticks[2 * i + 1] - ticks[2 * i]) * periodNs / 1e6;
            double[] a = sum.computeIfAbsent(names.get(i), k -> new double[2]);
            a[0] += ms;
            a[1]++;
            any = true;
        }
        if (any)
            frames++;
    }

    public int frames() {
        return frames;
    }

    /** closes the window: "pack gpu ms/frame total=x pass=y ..." (passes in frame order) and starts a new one */
    public String roll() {
        if (frames == 0)
            return last;
        StringBuilder sb = new StringBuilder();
        double total = 0;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, double[]> e : sum.entrySet()) {
            double avg = e.getValue()[0] / frames;
            total += avg;
            parts.add(e.getKey() + "=" + String.format("%.3f", avg));
        }
        sb.append("pack gpu ms/frame (").append(frames).append(" frames) total=").append(String.format("%.3f", total));
        for (String p : parts)
            sb.append(' ').append(p);
        sum.clear();
        frames = 0;
        last = sb.toString();
        return last;
    }

    public String last() {
        return last;
    }
}
