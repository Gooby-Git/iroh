package dev.elemore.iroh.telemetry;

/**
 * Rolling frame-time statistics over a time window (card TEL-1): average fps, 1% and 0.1% lows, frames in the last second.
 * Pure (no Minecraft / Vulkan), fixed arrays, no allocation after construction: {@link #push} is O(1), {@link #compute}
 * copies the window into a scratch array and heap-sorts it in place (called at the overlay's sample rate, not per frame).
 * Lows are "the frame rate of the slowest N% of frames" (mean frame time of those frames), at least one frame; this is
 * the definition the old Sodium Extra style counter used, widened to 0.1%.
 */
public final class FrameStats {
    private final long[] dur, stamp, scratch;
    private final long windowNs;
    private int head, count;
    private long last;

    public double avgFps, low1Fps, low01Fps;
    /** frames finished within the last second */
    public int fps1s;
    /** frames in the window used by the last compute */
    public int windowFrames;
    /** 99th percentile frame time of the window, ns */
    public long p99Ns;

    public FrameStats(int capacity, long windowNs) {
        this.dur = new long[capacity];
        this.stamp = new long[capacity];
        this.scratch = new long[capacity];
        this.windowNs = windowNs;
    }

    public void reset() {
        head = count = 0;
        last = 0;
        avgFps = low1Fps = low01Fps = 0;
        fps1s = windowFrames = 0;
        p99Ns = 0;
    }

    /** Call once per rendered frame with System.nanoTime(). */
    public void push(long now) {
        if (last != 0) {
            dur[head] = now - last;
            stamp[head] = now;
            head++;
            if (head == dur.length) head = 0;
            if (count < dur.length) count++;
        }
        last = now;
    }

    /** Duration of the newest frame in ns, 0 before the second push. */
    public long lastFrameNs() {
        return count == 0 ? 0 : dur[(head - 1 + dur.length) % dur.length];
    }

    public void compute(long now) {
        int n = 0, m = 0;
        long sum = 0;
        int cap = dur.length;
        for (int i = 0; i < count; i++) {
            int idx = head - 1 - i;
            if (idx < 0) idx += cap;
            long age = now - stamp[idx];
            if (age > windowNs) break;
            scratch[n++] = dur[idx];
            sum += dur[idx];
            if (age <= 1_000_000_000L) m++;
        }
        fps1s = m;
        windowFrames = n;
        if (n == 0) {
            avgFps = low1Fps = low01Fps = 0;
            p99Ns = 0;
            return;
        }
        avgFps = 1e9 * n / sum;
        heapSort(scratch, n);
        low1Fps = lowOf(n, Math.max(1, n / 100));
        low01Fps = lowOf(n, Math.max(1, n / 1000));
        p99Ns = scratch[Math.min(n - 1, (int) (n * 0.99))];
    }

    /** fps of the {@code worst} slowest frames (scratch is sorted ascending) */
    private double lowOf(int n, int worst) {
        long s = 0;
        for (int i = n - worst; i < n; i++) s += scratch[i];
        return 1e9 * worst / s;
    }

    /** In-place ascending heap sort of a[0, n): no allocation (Arrays.sort may allocate merge buffers). */
    static void heapSort(long[] a, int n) {
        for (int i = n / 2 - 1; i >= 0; i--) sift(a, i, n);
        for (int end = n - 1; end > 0; end--) {
            long t = a[0];
            a[0] = a[end];
            a[end] = t;
            sift(a, 0, end);
        }
    }

    private static void sift(long[] a, int i, int n) {
        long v = a[i];
        while (true) {
            int c = 2 * i + 1;
            if (c >= n) break;
            if (c + 1 < n && a[c + 1] > a[c]) c++;
            if (a[c] <= v) break;
            a[i] = a[c];
            i = c;
        }
        a[i] = v;
    }
}
