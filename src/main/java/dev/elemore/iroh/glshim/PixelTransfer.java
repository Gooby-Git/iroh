package dev.elemore.iroh.glshim;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.system.MemoryUtil.*;

/**
 * GL pixel transfer rules for client memory and pixel buffers (P1-2 slice 2, td-834bea), written from the GL 4.6
 * specification section 8.4 (unpack) and 18.2 (pack): format + type decode, row length 0 meaning "width", row
 * stride rounded up to the alignment when the element size is below it, skip rows / skip pixels, packed
 * {@code UNSIGNED_INT_8_8_8_8} (first component in the most significant byte of a native-endian int) and its
 * {@code _REV} form. Pure CPU, no Vulkan, no GL: unit-tested headless by glNetTest.
 *
 * <p>Iroh's images are stored as {@link Dst} layouts (one byte per channel). Upload: any supported (format, type)
 * -> tightly packed {@link Dst}. Download: {@link Dst} -> any supported (format, type) with pack state.
 */
public final class PixelTransfer {
    private PixelTransfer() {}

    // formats
    public static final int GL_RED = 0x1903, GL_GREEN = 0x1904, GL_BLUE = 0x1905, GL_ALPHA = 0x1906, GL_RGB = 0x1907,
            GL_RGBA = 0x1908, GL_LUMINANCE = 0x1909, GL_LUMINANCE_ALPHA = 0x190A, GL_BGR = 0x80E0, GL_BGRA = 0x80E1,
            GL_RG = 0x8227, GL_DEPTH_COMPONENT = 0x1902, GL_RED_INTEGER = 0x8D94, GL_RGBA_INTEGER = 0x8D99,
            GL_BGRA_INTEGER = 0x8D9B, GL_RG_INTEGER = 0x8228;
    // types
    public static final int GL_BYTE = 0x1400, GL_UNSIGNED_BYTE = 0x1401, GL_SHORT = 0x1402, GL_UNSIGNED_SHORT = 0x1403,
            GL_INT = 0x1404, GL_UNSIGNED_INT = 0x1405, GL_FLOAT = 0x1406, GL_HALF_FLOAT = 0x140B,
            GL_UNSIGNED_INT_8_8_8_8 = 0x8035, GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367,
            GL_UNSIGNED_SHORT_5_6_5 = 0x8363, GL_UNSIGNED_SHORT_4_4_4_4 = 0x8033, GL_UNSIGNED_SHORT_5_5_5_1 = 0x8034,
            GL_UNSIGNED_INT_2_10_10_10_REV = 0x8368;

    /** Iroh image byte layouts (Vulkan R8G8B8A8, B8G8R8A8, R8, R8G8, and 32-bit float depth). */
    public enum Dst {
        RGBA8(4), BGRA8(4), R8(1), RG8(2), D32F(4);
        public final int bytes;

        Dst(int bytes) {
            this.bytes = bytes;
        }
    }

    /** Components per pixel of a client format, or -1 if unsupported. */
    public static int components(int format) {
        return switch (format) {
            case GL_RED, GL_GREEN, GL_BLUE, GL_ALPHA, GL_LUMINANCE, GL_DEPTH_COMPONENT, GL_RED_INTEGER -> 1;
            case GL_RG, GL_LUMINANCE_ALPHA, GL_RG_INTEGER -> 2;
            case GL_RGB, GL_BGR -> 3;
            case GL_RGBA, GL_BGRA, GL_RGBA_INTEGER, GL_BGRA_INTEGER -> 4;
            default -> -1;
        };
    }

    /** True for packed types: one element holds the whole pixel. */
    public static boolean packed(int type) {
        return switch (type) {
            case GL_UNSIGNED_INT_8_8_8_8, GL_UNSIGNED_INT_8_8_8_8_REV, GL_UNSIGNED_SHORT_5_6_5, GL_UNSIGNED_SHORT_4_4_4_4,
                 GL_UNSIGNED_SHORT_5_5_5_1, GL_UNSIGNED_INT_2_10_10_10_REV -> true;
            default -> false;
        };
    }

    /** Size in bytes of one element (a component, or the whole pixel for packed types); -1 if unsupported. */
    public static int elementSize(int type) {
        return switch (type) {
            case GL_BYTE, GL_UNSIGNED_BYTE -> 1;
            case GL_SHORT, GL_UNSIGNED_SHORT, GL_HALF_FLOAT, GL_UNSIGNED_SHORT_5_6_5, GL_UNSIGNED_SHORT_4_4_4_4,
                 GL_UNSIGNED_SHORT_5_5_5_1 -> 2;
            case GL_INT, GL_UNSIGNED_INT, GL_FLOAT, GL_UNSIGNED_INT_8_8_8_8, GL_UNSIGNED_INT_8_8_8_8_REV,
                 GL_UNSIGNED_INT_2_10_10_10_REV -> 4;
            default -> -1;
        };
    }

    /** Bytes per pixel in client memory, or -1 if the combination is unsupported. */
    public static int bytesPerPixel(int format, int type) {
        int c = components(format), e = elementSize(type);
        if (c < 0 || e < 0) return -1;
        return packed(type) ? e : c * e;
    }

    /**
     * Distance in bytes between rows in client memory (GL 8.4.4.1): with {@code rowLength} 0 the row has
     * {@code width} pixels; when the element size is smaller than the alignment the row is padded to it.
     */
    public static long rowStride(int width, int rowLength, int alignment, int format, int type) {
        int n = rowLength > 0 ? rowLength : width;
        int bpp = bytesPerPixel(format, type);
        long raw = (long) n * bpp;
        int s = elementSize(type);
        if (alignment <= 1 || s >= alignment) return raw;
        return (raw + alignment - 1) / alignment * alignment;
    }

    /** Byte offset of the first pixel read/written: skipRows full rows plus skipPixels pixels. */
    public static long skipOffset(int width, int rowLength, int alignment, int skipRows, int skipPixels, int format, int type) {
        return (long) skipRows * rowStride(width, rowLength, alignment, format, type)
               + (long) skipPixels * bytesPerPixel(format, type);
    }

    /**
     * rc6 (td-fcd844): bytes an upload of {@code width x height} reads from client memory with this unpack state
     * (skip + (height-1) rows + one row of width pixels), or -1 for an unsupported format/type or negative state. Every
     * upload compares this with the real allocation before copying.
     */
    public static long sourceNeed(int width, int height, int rowLength, int alignment, int skipRows, int skipPixels, int format, int type) {
        int bpp = bytesPerPixel(format, type);
        long stride = rowStride(width, rowLength, alignment, format, type);
        if (bpp <= 0 || stride <= 0 || width <= 0 || height <= 0 || skipRows < 0 || skipPixels < 0) return -1;
        if (rowLength > 0 && skipPixels + width > rowLength) return -1;
        return skipOffset(width, rowLength, alignment, skipRows, skipPixels, format, type) + stride * (height - 1) + (long) width * bpp;
    }

    /** True when client data in (format, type) is byte-identical to {@code dst} (memcpy per row). */
    public static boolean identical(int format, int type, Dst dst) {
        return switch (dst) {
            case RGBA8 -> format == GL_RGBA && (type == GL_UNSIGNED_BYTE || type == GL_UNSIGNED_INT_8_8_8_8_REV);
            case BGRA8 -> format == GL_BGRA && (type == GL_UNSIGNED_BYTE || type == GL_UNSIGNED_INT_8_8_8_8_REV);
            case R8 -> (format == GL_RED || format == GL_LUMINANCE || format == GL_ALPHA) && type == GL_UNSIGNED_BYTE;
            case RG8 -> format == GL_RG && type == GL_UNSIGNED_BYTE;
            case D32F -> format == GL_DEPTH_COMPONENT && type == GL_FLOAT;
        };
    }

    // ------------------------------------------------------------------ element codecs (value in 0..255, or float)

    private static float elementToFloat(long p, int type) {
        return switch (type) {
            case GL_UNSIGNED_BYTE -> (memGetByte(p) & 0xFF) / 255f;
            case GL_BYTE -> Math.max(memGetByte(p) / 127f, -1f);
            case GL_UNSIGNED_SHORT -> (memGetShort(p) & 0xFFFF) / 65535f;
            case GL_SHORT -> Math.max(memGetShort(p) / 32767f, -1f);
            case GL_UNSIGNED_INT -> (float) ((memGetInt(p) & 0xFFFFFFFFL) / 4294967295.0);
            case GL_INT -> Math.max(memGetInt(p) / 2147483647f, -1f);
            case GL_FLOAT -> memGetFloat(p);
            case GL_HALF_FLOAT -> halfToFloat(memGetShort(p));
            default -> 0f;
        };
    }

    private static void floatToElement(long p, int type, float v) {
        float c = Math.min(Math.max(v, 0f), 1f);
        switch (type) {
            case GL_UNSIGNED_BYTE -> memPutByte(p, (byte) Math.round(c * 255f));
            case GL_BYTE -> memPutByte(p, (byte) Math.round(Math.min(Math.max(v, -1f), 1f) * 127f));
            case GL_UNSIGNED_SHORT -> memPutShort(p, (short) Math.round(c * 65535f));
            case GL_SHORT -> memPutShort(p, (short) Math.round(Math.min(Math.max(v, -1f), 1f) * 32767f));
            case GL_UNSIGNED_INT -> memPutInt(p, (int) Math.round(c * 4294967295.0));
            case GL_INT -> memPutInt(p, Math.round(Math.min(Math.max(v, -1f), 1f) * 2147483647f));
            case GL_FLOAT -> memPutFloat(p, v);
            case GL_HALF_FLOAT -> memPutShort(p, floatToHalf(v));
            default -> {}
        }
    }

    static float halfToFloat(short h) {
        int s = (h >> 15) & 1, e = (h >> 10) & 0x1F, m = h & 0x3FF;
        float v;
        if (e == 0) v = m / 1024f * (float) Math.pow(2, -14);
        else if (e == 31) v = m == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        else v = (1 + m / 1024f) * (float) Math.pow(2, e - 15);
        return s == 1 ? -v : v;
    }

    static short floatToHalf(float f) {
        int bits = Float.floatToIntBits(f);
        int s = (bits >>> 16) & 0x8000;
        int e = ((bits >>> 23) & 0xFF) - 127 + 15;
        int m = bits & 0x7FFFFF;
        if (e <= 0) return (short) s;
        if (e >= 31) return (short) (s | 0x7C00);
        return (short) (s | (e << 10) | (m >>> 13));
    }

    /**
     * Reads pixel at {@code p} into rgba[0..3] (0..1 floats, GL's conversion to RGBA: missing colour = 0, missing
     * alpha = 1, luminance -> r=g=b). Returns false for an unsupported (format, type).
     */
    static boolean readPixel(long p, int format, int type, float[] rgba, float[] c) {
        int n = components(format);
        if (n < 0) return false;
        if (packed(type)) {
            switch (type) {
                case GL_UNSIGNED_INT_8_8_8_8 -> {
                    int v = memGetInt(p);
                    c[0] = (v >>> 24) / 255f; c[1] = ((v >>> 16) & 0xFF) / 255f; c[2] = ((v >>> 8) & 0xFF) / 255f; c[3] = (v & 0xFF) / 255f;
                }
                case GL_UNSIGNED_INT_8_8_8_8_REV -> {
                    int v = memGetInt(p);
                    c[0] = (v & 0xFF) / 255f; c[1] = ((v >>> 8) & 0xFF) / 255f; c[2] = ((v >>> 16) & 0xFF) / 255f; c[3] = (v >>> 24) / 255f;
                }
                case GL_UNSIGNED_SHORT_5_6_5 -> {
                    int v = memGetShort(p) & 0xFFFF;
                    c[0] = (v >>> 11) / 31f; c[1] = ((v >>> 5) & 0x3F) / 63f; c[2] = (v & 0x1F) / 31f; c[3] = 1f;
                }
                case GL_UNSIGNED_SHORT_4_4_4_4 -> {
                    int v = memGetShort(p) & 0xFFFF;
                    c[0] = (v >>> 12) / 15f; c[1] = ((v >>> 8) & 0xF) / 15f; c[2] = ((v >>> 4) & 0xF) / 15f; c[3] = (v & 0xF) / 15f;
                }
                case GL_UNSIGNED_SHORT_5_5_5_1 -> {
                    int v = memGetShort(p) & 0xFFFF;
                    c[0] = (v >>> 11) / 31f; c[1] = ((v >>> 6) & 0x1F) / 31f; c[2] = ((v >>> 1) & 0x1F) / 31f; c[3] = v & 1;
                }
                case GL_UNSIGNED_INT_2_10_10_10_REV -> {
                    int v = memGetInt(p);
                    c[0] = (v & 0x3FF) / 1023f; c[1] = ((v >>> 10) & 0x3FF) / 1023f; c[2] = ((v >>> 20) & 0x3FF) / 1023f; c[3] = (v >>> 30) / 3f;
                }
                default -> { return false; }
            }
        } else {
            int e = elementSize(type);
            if (e < 0) return false;
            for (int k = 0; k < n; k++) c[k] = elementToFloat(p + (long) k * e, type);
        }
        mapComponents(format, c, rgba);
        return true;
    }

    /** Client-order components -> RGBA. */
    private static void mapComponents(int format, float[] c, float[] o) {
        switch (format) {
            case GL_RGBA, GL_RGBA_INTEGER -> { o[0] = c[0]; o[1] = c[1]; o[2] = c[2]; o[3] = c[3]; }
            case GL_BGRA, GL_BGRA_INTEGER -> { o[0] = c[2]; o[1] = c[1]; o[2] = c[0]; o[3] = c[3]; }
            case GL_RGB -> { o[0] = c[0]; o[1] = c[1]; o[2] = c[2]; o[3] = 1; }
            case GL_BGR -> { o[0] = c[2]; o[1] = c[1]; o[2] = c[0]; o[3] = 1; }
            case GL_RG, GL_RG_INTEGER -> { o[0] = c[0]; o[1] = c[1]; o[2] = 0; o[3] = 1; }
            case GL_RED, GL_RED_INTEGER, GL_DEPTH_COMPONENT -> { o[0] = c[0]; o[1] = 0; o[2] = 0; o[3] = 1; }
            case GL_GREEN -> { o[0] = 0; o[1] = c[0]; o[2] = 0; o[3] = 1; }
            case GL_BLUE -> { o[0] = 0; o[1] = 0; o[2] = c[0]; o[3] = 1; }
            case GL_ALPHA -> { o[0] = 0; o[1] = 0; o[2] = 0; o[3] = c[0]; }
            case GL_LUMINANCE -> { o[0] = c[0]; o[1] = c[0]; o[2] = c[0]; o[3] = 1; }
            case GL_LUMINANCE_ALPHA -> { o[0] = c[0]; o[1] = c[0]; o[2] = c[0]; o[3] = c[1]; }
            default -> { o[0] = o[1] = o[2] = 0; o[3] = 1; }
        }
    }

    /** RGBA -> client pixel at {@code p} (pack: GL 18.2, luminance = red for a pack of a colour image). */
    static boolean writePixel(long p, int format, int type, float[] rgba, float[] c) {
        int n = components(format);
        if (n < 0) return false;
        c[0] = c[1] = c[2] = 0;
        c[3] = 1;
        switch (format) {
            case GL_RGBA, GL_RGBA_INTEGER -> { c[0] = rgba[0]; c[1] = rgba[1]; c[2] = rgba[2]; c[3] = rgba[3]; }
            case GL_BGRA, GL_BGRA_INTEGER -> { c[0] = rgba[2]; c[1] = rgba[1]; c[2] = rgba[0]; c[3] = rgba[3]; }
            case GL_RGB -> { c[0] = rgba[0]; c[1] = rgba[1]; c[2] = rgba[2]; }
            case GL_BGR -> { c[0] = rgba[2]; c[1] = rgba[1]; c[2] = rgba[0]; }
            case GL_RG, GL_RG_INTEGER -> { c[0] = rgba[0]; c[1] = rgba[1]; }
            case GL_RED, GL_RED_INTEGER, GL_LUMINANCE, GL_DEPTH_COMPONENT -> c[0] = rgba[0];
            case GL_GREEN -> c[0] = rgba[1];
            case GL_BLUE -> c[0] = rgba[2];
            case GL_ALPHA -> c[0] = rgba[3];
            case GL_LUMINANCE_ALPHA -> { c[0] = rgba[0]; c[1] = rgba[3]; }
            default -> { return false; }
        }
        if (packed(type)) {
            switch (type) {
                case GL_UNSIGNED_INT_8_8_8_8 -> memPutInt(p, (b8(c[0]) << 24) | (b8(c[1]) << 16) | (b8(c[2]) << 8) | b8(c[3]));
                case GL_UNSIGNED_INT_8_8_8_8_REV -> memPutInt(p, b8(c[0]) | (b8(c[1]) << 8) | (b8(c[2]) << 16) | (b8(c[3]) << 24));
                case GL_UNSIGNED_SHORT_5_6_5 -> memPutShort(p, (short) ((Math.round(clamp01(c[0]) * 31) << 11)
                        | (Math.round(clamp01(c[1]) * 63) << 5) | Math.round(clamp01(c[2]) * 31)));
                default -> { return false; }
            }
            return true;
        }
        int e = elementSize(type);
        if (e < 0) return false;
        for (int k = 0; k < n; k++) floatToElement(p + (long) k * e, type, c[k]);
        return true;
    }

    private static int b8(float v) {
        return Math.round(clamp01(v) * 255f);
    }

    private static float clamp01(float v) {
        return Math.min(Math.max(v, 0f), 1f);
    }

    // ------------------------------------------------------------------ whole-rectangle transfers

    /**
     * Unpack: {@code w x h} pixels from client memory at {@code src} (already offset to the first row read, i.e. skip
     * applied, rows {@code srcStride} apart) into a tightly packed {@link Dst} buffer at {@code dst}.
     * Returns false (dst untouched) for an unsupported (format, type).
     */
    public static boolean unpack(long src, long srcStride, int w, int h, int format, int type, Dst dst, long dstPtr) {
        int bpp = bytesPerPixel(format, type);
        if (bpp < 0) return false;
        long dstRow = (long) w * dst.bytes;
        if (identical(format, type, dst)) {
            for (int y = 0; y < h; y++)
                memCopy(src + y * srcStride, dstPtr + y * dstRow, dstRow);
            return true;
        }
        float[] rgba = new float[4], tmp = new float[4];
        for (int y = 0; y < h; y++) {
            long s = src + y * srcStride, d = dstPtr + y * dstRow;
            for (int x = 0; x < w; x++, s += bpp, d += dst.bytes) {
                if (!readPixel(s, format, type, rgba, tmp)) return false;
                storeDst(d, dst, rgba);
            }
        }
        return true;
    }

    /**
     * Pack: tightly packed {@link Dst} rows at {@code src} -> client memory at {@code dst} (first pixel written, skip
     * applied; rows {@code dstStride} apart) in (format, type).
     */
    public static boolean pack(long src, Dst srcLayout, int w, int h, int format, int type, long dst, long dstStride) {
        int bpp = bytesPerPixel(format, type);
        if (bpp < 0) return false;
        long srcRow = (long) w * srcLayout.bytes;
        if (identical(format, type, srcLayout)) {
            for (int y = 0; y < h; y++)
                memCopy(src + y * srcRow, dst + y * dstStride, srcRow);
            return true;
        }
        float[] rgba = new float[4], tmp = new float[4];
        for (int y = 0; y < h; y++) {
            long s = src + y * srcRow, d = dst + y * dstStride;
            for (int x = 0; x < w; x++, s += srcLayout.bytes, d += bpp) {
                loadDst(s, srcLayout, rgba);
                if (!writePixel(d, format, type, rgba, tmp)) return false;
            }
        }
        return true;
    }

    private static void storeDst(long d, Dst dst, float[] c) {
        switch (dst) {
            case RGBA8 -> { put8(d, c[0]); put8(d + 1, c[1]); put8(d + 2, c[2]); put8(d + 3, c[3]); }
            case BGRA8 -> { put8(d, c[2]); put8(d + 1, c[1]); put8(d + 2, c[0]); put8(d + 3, c[3]); }
            case R8 -> put8(d, c[0]);
            case RG8 -> { put8(d, c[0]); put8(d + 1, c[1]); }
            case D32F -> memPutFloat(d, c[0]);
        }
    }

    private static void loadDst(long s, Dst src, float[] c) {
        switch (src) {
            case RGBA8 -> { c[0] = get8(s); c[1] = get8(s + 1); c[2] = get8(s + 2); c[3] = get8(s + 3); }
            case BGRA8 -> { c[2] = get8(s); c[1] = get8(s + 1); c[0] = get8(s + 2); c[3] = get8(s + 3); }
            case R8 -> { c[0] = get8(s); c[1] = 0; c[2] = 0; c[3] = 1; }
            case RG8 -> { c[0] = get8(s); c[1] = get8(s + 1); c[2] = 0; c[3] = 1; }
            case D32F -> { c[0] = memGetFloat(s); c[1] = 0; c[2] = 0; c[3] = 1; }
        }
    }

    private static void put8(long p, float v) {
        memPutByte(p, (byte) Math.round(clamp01(v) * 255f));
    }

    private static float get8(long p) {
        return (memGetByte(p) & 0xFF) / 255f;
    }

    /** Convenience for tests: a malloc'd tight buffer of w*h*dst.bytes. Caller frees with MemoryUtil.memFree. */
    public static java.nio.ByteBuffer alloc(int w, int h, Dst dst) {
        return MemoryUtil.memAlloc(Math.max(1, w * h * dst.bytes));
    }
}
