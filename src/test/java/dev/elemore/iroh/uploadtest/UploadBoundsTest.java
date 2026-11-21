package dev.elemore.iroh.uploadtest;

import dev.elemore.iroh.glshim.PixelTransfer;
import org.lwjgl.system.MemoryUtil;

import java.util.SplittableRandom;

import static dev.elemore.iroh.glshim.PixelTransfer.*;

/**
 * rc6 (td-fcd844): texture-upload source spans. NativeImage uploads now go through PixelTransfer with the image's own
 * format and tight rows; the base copied width*height*4 bytes whatever the image format, so Charta's 25x35 non-RGBA
 * card images (2625 / 875 bytes) were read for 3500 bytes: SIGSEGV in nmemcpy. Checks, with LWJGL natives:
 * 1. 60,000 random (format, size, sub-region, skip) cases: sourceNeed never exceeds the image allocation for an in-bounds
 *    region, and an out-of-bounds region is refused (-1 or > allocation);
 * 2. 5,000 real conversions from exact-size native sources (freed right after the call) into RGBA8 destinations with a
 *    64-byte canary: the canary is never written, converted pixels follow GL (RGB a=255, RED -> r,0,0,255);
 * 3. the Charta case: the old formula overruns, the new one does not.
 * {@code ./gradlew uploadBoundsTest}; last line "# uploadbounds PASS n/n".
 */
public final class UploadBoundsTest {
    private static int pass, fail;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    // NativeImage.Format: glFormat, components
    private static final int[][] FORMATS = {{GL_RGBA, 4}, {GL_RGB, 3}, {GL_RG, 2}, {GL_RED, 1}};
    private static final int UB = 0x1401; // GL_UNSIGNED_BYTE

    public static void main(String[] args) {
        SplittableRandom r = new SplittableRandom(42);

        // 1. span math
        int over = 0, refusedOk = 0, outCases = 0;
        for (int i = 0; i < 60_000; i++) {
            int[] f = FORMATS[r.nextInt(4)];
            int w = 1 + r.nextInt(300), h = 1 + r.nextInt(300);
            long alloc = (long) w * h * f[1];
            boolean inside = r.nextInt(5) != 0;
            int sx, sy, sw, sh;
            if (inside) {
                sx = r.nextInt(w); sy = r.nextInt(h); sw = 1 + r.nextInt(w - sx); sh = 1 + r.nextInt(h - sy);
            } else {
                sx = r.nextInt(w); sy = r.nextInt(h); sw = w - sx + 1 + r.nextInt(8); sh = 1 + r.nextInt(h - sy);
                if (r.nextBoolean()) { sw = 1 + r.nextInt(w - sx); sh = h - sy + 1 + r.nextInt(8); }
            }
            long need = PixelTransfer.sourceNeed(sw, sh, w, 1, sy, sx, f[0], UB);
            if (inside) {
                if (need < 0 || need > alloc) over++;
            } else {
                outCases++;
                if (need < 0 || need > alloc) refusedOk++;
            }
        }
        check("in-bounds regions never read past the image (" + over + " overruns)", over == 0);
        check("out-of-bounds regions refused (" + refusedOk + "/" + outCases + ")", refusedOk == outCases);

        // 2. real conversions with canary
        boolean canaryOk = true, valuesOk = true;
        for (int i = 0; i < 5_000; i++) {
            int[] f = FORMATS[r.nextInt(4)];
            int w = 1 + r.nextInt(64), h = 1 + r.nextInt(64);
            long alloc = (long) w * h * f[1];
            long src = MemoryUtil.nmemAlloc(alloc);
            for (long b = 0; b < alloc; b++) MemoryUtil.memPutByte(src + b, (byte) (b * 7 + 3));
            long need = PixelTransfer.sourceNeed(w, h, w, 1, 0, 0, f[0], UB);
            long dstSize = (long) w * h * 4;
            long dst = MemoryUtil.nmemAlloc(dstSize + 64);
            for (int c = 0; c < 64; c++) MemoryUtil.memPutByte(dst + dstSize + c, (byte) 0xA5);
            boolean ok = need == alloc && PixelTransfer.unpack(src, (long) w * f[1], w, h, f[0], UB, Dst.RGBA8, dst);
            MemoryUtil.nmemFree(src); // the caller frees right after the call: nothing may read it later
            if (!ok) valuesOk = false;
            for (int c = 0; c < 64; c++) if (MemoryUtil.memGetByte(dst + dstSize + c) != (byte) 0xA5) canaryOk = false;
            int p0r = MemoryUtil.memGetByte(dst) & 0xFF, p0g = MemoryUtil.memGetByte(dst + 1) & 0xFF,
                    p0b = MemoryUtil.memGetByte(dst + 2) & 0xFF, p0a = MemoryUtil.memGetByte(dst + 3) & 0xFF;
            switch (f[1]) {
                case 3 -> valuesOk &= p0r == 3 && p0g == 10 && p0b == 17 && p0a == 255;
                case 1 -> valuesOk &= p0r == 3 && p0g == 0 && p0b == 0 && p0a == 255;
                case 2 -> valuesOk &= p0r == 3 && p0g == 10 && p0b == 0 && p0a == 255;
                default -> valuesOk &= p0r == 3 && p0g == 10 && p0b == 17 && p0a == 24;
            }
            MemoryUtil.nmemFree(dst);
        }
        check("5,000 conversions from exact-size freed-after sources: destination canary intact", canaryOk);
        check("converted values follow GL (RGB a=255, RG -> r,g,0,255, RED -> r,0,0,255)", valuesOk);

        // 3. the crash case
        long oldRead = ((long) 25 * (35 - 1) + 25) * 4; // base: rowLength * (h-1) + w, times the TEXTURE's 4 bytes
        check("Charta 25x35 RGB card: old read " + oldRead + " B > image 2625 B (the SIGSEGV); new read "
                + PixelTransfer.sourceNeed(25, 35, 25, 1, 0, 0, GL_RGB, UB) + " B",
                oldRead > 2625 && PixelTransfer.sourceNeed(25, 35, 25, 1, 0, 0, GL_RGB, UB) == 2625
                        && PixelTransfer.sourceNeed(25, 35, 25, 1, 0, 0, GL_RED, UB) == 875);
        check("leftover unpack state cannot widen a read: skipPixels + width > rowLength refused",
                PixelTransfer.sourceNeed(20, 5, 25, 1, 0, 10, GL_RGBA, UB) == -1);

        System.out.println("# uploadbounds " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        if (fail != 0) System.exit(1);
    }
}
