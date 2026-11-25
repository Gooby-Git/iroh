package dev.elemore.iroh.telemetry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * "Record to CSV" (card TEL-1): one row per rendered frame with the frame times and every telemetry counter, for
 * benchmarks. Rows are formatted into a reused byte buffer and written to a buffered stream: no allocation per row.
 * Columns that do not apply (no GPU timestamps, singleplayer ping ...) are left empty. Render thread only.
 */
public final class CsvRecorder {
    private CsvRecorder() {}

    public static final String HEADER = "time_ms,frame_ms,cpu_ms,gpu_ms,fps,avg_fps,low1_fps,low01_fps,gpu_busy_pct,"
            + "vram_used_mib,vram_budget_mib,iroh_vram_mib,heap_used_mib,heap_max_mib,ping_ms,tps,mspt,chunk_builds_pending,"
            + "entities,render_distance,generated_fps";

    private static FileChannel out;
    /** write buffer, filled by the rows and written when full or every 2 s (a plain OutputStream wraps a ByteBuffer per write) */
    private static final ByteBuffer buf = ByteBuffer.allocateDirect(1 << 16);
    private static Path file;
    private static long startNs, lastFlush;
    private static final byte[] row = new byte[512];
    private static int n;
    private static long rows;
    private static Thread hook;

    public static boolean active() { return out != null; }

    public static Path file() { return file; }

    public static long rows() { return rows; }

    /** Starts a recording in {@code dir}; returns the file, or null when it could not be created. */
    public static synchronized Path start(Path dir, long nowNs) {
        stop();
        try {
            Files.createDirectories(dir);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
            Path f = dir.resolve("iroh-telemetry-" + stamp + ".csv");
            int k = 1;
            while (Files.exists(f)) f = dir.resolve("iroh-telemetry-" + stamp + "-" + k++ + ".csv");
            out = FileChannel.open(f, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            buf.clear();
            file = f;
            startNs = nowNs;
            lastFlush = nowNs;
            rows = 0;
            byte[] h = (HEADER + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            buf.put(h);
            drain();
            if (hook == null) {
                hook = new Thread(CsvRecorder::stop, "iroh-telemetry-csv-flush");
                Runtime.getRuntime().addShutdownHook(hook);
            }
            return f;
        } catch (IOException e) {
            out = null;
            file = null;
            return null;
        }
    }

    public static synchronized void stop() {
        if (out == null) return;
        try {
            drain();
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }

    /** One row; values < 0 (or NaN) are written as empty fields. */
    public static void row(long nowNs, double frameMs, double cpuMs, double gpuMs, Telemetry.Snapshot s) {
        if (out == null) return;
        n = 0;
        num((nowNs - startNs) / 1e6, 3);
        num(frameMs, 3); num(cpuMs, 3); num(gpuMs, 3);
        num(s.fps, 0); num(s.avgFps, 1); num(s.low1Fps, 1); num(s.low01Fps, 1); num(s.gpuBusyPct, 1);
        num(s.vramUsedMiB, 0); num(s.vramBudgetMiB, 0); num(s.irohVramMiB, 0);
        num(s.heapUsedMiB, 0); num(s.heapMaxMiB, 0);
        num(s.pingMs, 0); num(s.tps, 1); num(s.mspt, 1);
        num(s.chunksPending, 0); num(s.entities, 0); num(s.renderDistance, 0);
        num(s.generatedFps, 1);
        row[n - 1] = '\n';
        try {
            if (buf.remaining() < n) drain();
            buf.put(row, 0, n);
            rows++;
            if (nowNs - lastFlush > 2_000_000_000L) {
                drain();
                lastFlush = nowNs;
            }
        } catch (IOException e) {
            out = null; // disk full / removed: stop recording, the overlay shows REC off
        }
    }

    private static void drain() throws IOException {
        buf.flip();
        while (buf.hasRemaining()) out.write(buf);
        buf.clear();
    }

    /** value, then ',' ; negative / NaN = empty field */
    private static void num(double v, int decimals) {
        if (!(v >= 0) || n + 24 > row.length) {
            if (n + 1 <= row.length) row[n++] = ',';
            return;
        }
        long scale = decimals == 0 ? 1 : decimals == 1 ? 10 : decimals == 2 ? 100 : 1000;
        long scaled = (long) (v * scale + 0.5);
        long ip = scaled / scale;
        int digits = 1;
        for (long t = ip; t >= 10; t /= 10) digits++;
        int p = n + digits;
        do { row[--p] = (byte) ('0' + (int) (ip % 10)); ip /= 10; } while (ip != 0);
        n += digits;
        if (decimals > 0) {
            row[n++] = '.';
            long frac = scaled % scale;
            for (long d = scale / 10; d > 0; d /= 10) row[n++] = (byte) ('0' + (int) (frac / d % 10));
        }
        row[n++] = ',';
    }
}
