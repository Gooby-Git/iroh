package dev.elemore.iroh.compat.watermedia;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * CPU half of {@link IrohVideoTexture}: no GPU, no GL, testable offline (videoFrameTest). Not thread safe; the owner
 * synchronizes.
 *
 * <p>Format contract (WaterMedia 3.0.0.23 FFMediaPlayer, read from bytecode, td-75c89e): the engine only answers
 * supports(BGRA), so FFMediaPlayer's main path converts every frame with swscale to BGRA and calls
 * {@code format(BGRA, w, h, 8)} before the first upload. But its quality-switch / reopen path calls
 * {@code format(nativeFormat, ...)} directly WITHOUT consulting supports() (YUV420P etc. straight from the codec), then
 * the main path sees {@code lastFormat != BGRA} and announces BGRA again before any frame is uploaded. So a non-BGRA
 * announcement is a transient state, not lost frames: {@link #announce} just parks the buffer, and a warning is
 * justified only if a frame actually arrives while parked ({@link #copy} returns false).
 */
public final class VideoFrameBuffer {
    /** Pixel store of the packed BGRA copy: width*4 bytes per row, no padding. */
    public ByteBuffer pending;
    public int width, height;
    /** Name of the last announced non-BGRA format while parked, else null. */
    public String parkedFormat;
    public int droppedFrames;
    private boolean warned;

    /** @return true when this call changed the buffer layout (callers must drop a stale dirty flag). */
    public boolean announce(boolean bgra, String name, int w, int h) {
        if (!bgra) {
            parkedFormat = name;
            width = height = 0;
            return true;
        }
        parkedFormat = null;
        if (w == width && h == height) return false;
        width = w;
        height = h;
        if (pending != null) MemoryUtil.memFree(pending);
        pending = w > 0 && h > 0 ? MemoryUtil.memAlloc(w * h * 4) : null;
        return true;
    }

    /**
     * Copy plane 0 (BGRA, {@code strides[0]} bytes per row, 0 or missing = tightly packed) into {@link #pending}. Never
     * reads past {@code src.remaining()} and never writes past width*height*4.
     * @return true when a frame was stored; false when it was dropped (parked non-BGRA format, no allocation, a stride
     *         shorter than a row, or no rows readable): counted in {@link #droppedFrames}.
     */
    public boolean copy(ByteBuffer[] planes, int[] strides) {
        if (planes == null || planes.length == 0 || planes[0] == null) return false;
        if (pending == null || width <= 0 || height <= 0) {
            droppedFrames++;
            return false;
        }
        ByteBuffer src = planes[0];
        int row = width * 4;
        int stride = strides != null && strides.length > 0 && strides[0] > 0 ? strides[0] : row;
        if (stride < row) {
            droppedFrames++;
            return false;
        }
        long srcAddr = MemoryUtil.memAddress(src);
        long dstAddr = MemoryUtil.memAddress(pending);
        long avail = src.remaining();
        if (avail < row) {
            droppedFrames++;
            return false;
        }
        if (stride == row) {
            MemoryUtil.memCopy(srcAddr, dstAddr, Math.min(avail, (long) row * height));
        } else {
            for (int y = 0; y < height && (long) y * stride + row <= avail; y++)
                MemoryUtil.memCopy(srcAddr + (long) y * stride, dstAddr + (long) y * row, row);
        }
        return true;
    }

    /** True exactly once, the first time a frame was dropped while a non-BGRA format was announced. */
    public boolean shouldWarnDrop() {
        if (warned || droppedFrames == 0 || parkedFormat == null) return false;
        warned = true;
        return true;
    }

    public void free() {
        if (pending != null) {
            MemoryUtil.memFree(pending);
            pending = null;
        }
    }
}
