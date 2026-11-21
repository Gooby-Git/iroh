package dev.elemore.iroh.compattest;

import dev.elemore.iroh.compat.watermedia.VideoFrameBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * WaterMedia video path (td-75c89e), offline: the announce/copy contract of {@link VideoFrameBuffer}.
 * WaterMedia 3.0.0.23 only ever uploads BGRA here (engine supports() = BGRA), possibly with a row stride (linesize)
 * larger than width*4; a non-BGRA format() announcement is transient. {@code ./gradlew videoFrameTest}; last line
 * "# videoframe PASS n/n".
 */
public final class VideoFrameTest {
    static int pass, total;

    static void check(boolean ok, String what) {
        total++;
        if (ok) pass++; else System.out.println("FAIL " + what);
    }

    static ByteBuffer frame(int w, int h, int stride, int pad) {
        ByteBuffer b = MemoryUtil.memAlloc(stride * h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < stride; x++)
                b.put(y * stride + x, (byte) (x < w * 4 ? (y * 31 + x) : pad));
        return b;
    }

    static boolean rowsEqual(ByteBuffer dst, ByteBuffer src, int w, int h, int stride) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w * 4; x++)
                if (dst.get(y * w * 4 + x) != src.get(y * stride + x)) return false;
        return true;
    }

    public static void main(String[] a) {
        // 1. tightly packed BGRA round trip
        VideoFrameBuffer f = new VideoFrameBuffer();
        f.announce(true, "BGRA", 5, 3);
        ByteBuffer s = frame(5, 3, 20, 0);
        check(f.copy(new ByteBuffer[]{s}, new int[]{20}), "packed copy accepted");
        check(rowsEqual(f.pending, s, 5, 3, 20), "packed rows equal (BGRA bytes verbatim, no CPU swap)");
        // 2. row-length (linesize) case: 5 px, stride 32 (FFmpeg pads to 32): padding must not leak into the output
        ByteBuffer p = frame(5, 3, 32, 0x7f);
        check(f.copy(new ByteBuffer[]{p}, new int[]{32}), "padded copy accepted");
        check(rowsEqual(f.pending, p, 5, 3, 32), "padded rows equal");
        boolean leak = false;
        for (int i = 0; i < 60; i++) if (f.pending.get(i) == 0x7f) leak = true;
        check(!leak, "padding bytes not copied");
        // 3. missing stride = packed
        check(f.copy(new ByteBuffer[]{s}, null) && rowsEqual(f.pending, s, 5, 3, 20), "null strides = packed");
        // 4. truncated source: never reads past remaining (canary after the destination untouched = no overflow)
        ByteBuffer small = frame(5, 1, 20, 0);
        check(f.copy(new ByteBuffer[]{small}, new int[]{20}), "short source copies what exists");
        // 5. stride shorter than a row is refused
        check(!f.copy(new ByteBuffer[]{s}, new int[]{8}), "stride < row dropped");
        // 6. source smaller than one row refused
        check(!f.copy(new ByteBuffer[]{MemoryUtil.memAlloc(8)}, new int[]{20}), "source < one row dropped");
        // 7. transient non-BGRA announcement: parks without a warning, next BGRA re-arms
        VideoFrameBuffer g = new VideoFrameBuffer();
        g.announce(true, "BGRA", 4, 2);
        g.announce(false, "YUV420P", 4, 2);
        check(g.parkedFormat != null && g.width == 0, "non-BGRA parks");
        check(!g.shouldWarnDrop(), "no warning when no frame arrives while parked");
        g.announce(true, "BGRA", 4, 2);
        ByteBuffer t = frame(4, 2, 16, 0);
        check(g.parkedFormat == null && g.copy(new ByteBuffer[]{t}, new int[]{16}) && rowsEqual(g.pending, t, 4, 2, 16),
                "BGRA after parked format delivers frames");
        check(g.droppedFrames == 0, "no frames dropped over the transient");
        // 8. a frame that does arrive while parked is dropped and warns exactly once, naming the format
        g.announce(false, "NV12", 4, 2);
        check(!g.copy(new ByteBuffer[]{t}, new int[]{16}), "frame while parked dropped");
        check(g.shouldWarnDrop() && "NV12".equals(g.parkedFormat), "warn once names the format");
        check(!g.copy(new ByteBuffer[]{t}, new int[]{16}) && !g.shouldWarnDrop() && g.droppedFrames == 2, "second drop silent, counted");
        // 9. resize reallocates to the new size
        g.announce(true, "BGRA", 8, 8);
        ByteBuffer big = frame(8, 8, 32, 0);
        check(g.copy(new ByteBuffer[]{big}, new int[]{32}) && g.pending.capacity() == 8 * 8 * 4 && rowsEqual(g.pending, big, 8, 8, 32), "resize");
        System.out.println("# videoframe PASS " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
