// SPDX-License-Identifier: LGPL-3.0-only
package dev.elemore.iroh.lod.nat;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * VL-1 headless test of libiroh_lod through Iroh's real loader. Needs -Diroh.lod.native=<file> (Linux .so; the Windows DLL
 * is covered by build-windows.sh under Wine). A public library must report no seed; with -Diroh.lod.expectSeed=true the
 * private one must. Last line "# lodnative PASS n/n" or FAIL.
 */
public class LodNativeTest {
    static int pass, total;

    static void check(boolean ok, String what) {
        total++;
        if (ok) pass++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static long key(int level, int x, int y, int z) {   // iroh_lod::cell::Key::pack
        return ((long) level << 61) | ((long) (y & 63) << 55) | (((long) x & 0x7FFFFFFL) << 27) | ((long) z & 0x7FFFFFFL);
    }

    static int[] section(int seed) {
        int[] c = new int[32768];
        int r = seed * 0x9E3779B1 | 1;
        for (int i = 0; i < c.length; i++) {
            r ^= r << 13; r ^= r >>> 17; r ^= r << 5;
            int y = i >> 10;
            c[i] = y < 12 ? (30 + Integer.remainderUnsigned(r, 40)) | (2 << 24) : y < 14 ? 27 | (2 << 24) : (15 << 16) | (2 << 24);
        }
        return c;
    }

    public static void main(String[] a) throws Exception {
        boolean loaded = LodNativeLoader.load();
        check(loaded, "loader loads (reason=[" + LodNativeLoader.unavailableReason() + "])");
        if (!loaded) { System.out.println("# lodnative FAIL " + pass + "/" + total); System.exit(1); }
        check(LodNative.abi() == 5 && LodNative.ABI == 5, "ABI is 5");
        boolean wantSeed = Boolean.getBoolean("iroh.lod.expectSeed");
        check(LodNativeLoader.hasSeed() == wantSeed, "hasSeed == " + wantSeed);
        if (!wantSeed) {
            boolean missing = false;
            try { LodSeedNative.graphClose(0); } catch (UnsatisfiedLinkError e) { missing = true; }
            check(missing, "public build: LodSeedNative symbols absent (UnsatisfiedLinkError)");
        }
        int[] s0 = section(1);
        check(LodNative.hashCells(s0) == LodNative.hashCells(s0.clone()) && LodNative.hashCells(s0) != LodNative.hashCells(section(2)), "hashCells stable and content-sensitive");
        byte[] packed = LodNative.encodePacked(s0);
        check(packed != null && packed.length > 0 && packed.length < 32768 * 4, "encodePacked compresses (" + (packed == null ? -1 : packed.length) + " bytes)");
        check(LodNative.hashUniform(15 << 16) == LodNative.hashCells(uniform(15 << 16)), "hashUniform == hashCells of a uniform section");

        Path dir = Files.createTempDirectory("iroh-lod-native-test");
        long h = LodNative.storeOpen(dir.toString());
        long[] keys = new long[12];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = key(0, i * 3 - 10, i % 32, -i * 2);
            check(LodNative.storePut(h, keys[i], section(i)), "put " + i);
        }
        check(!LodNative.storePut(h, keys[0], section(0)), "same content is not rewritten");
        LodNative.storeMarkChunk(h, -3, 70);
        LodNative.storeClose(h);
        h = LodNative.storeOpen(dir.toString());
        boolean same = true;
        int[] out = new int[32768];
        for (int i = 0; i < keys.length; i++) {
            same &= LodNative.storeGet(h, keys[i], out) && LodNative.storeHash(h, keys[i]) == LodNative.hashCells(section(i));
        }
        check(same, "reopen: all sections read back, stored hashes equal the input hashes");
        check(LodNative.storeHasChunk(h, -3, 70) && !LodNative.storeHasChunk(h, -3, 71), "chunk marks persist");
        long[] parents = new long[keys.length];
        for (int i = 0; i < keys.length; i++) parents[i] = key(1, (i * 3 - 10) >> 1, (i % 32) >> 1, (-i * 2) >> 1);
        check(LodNative.storeCascade(h, parents).length > 0, "cascade builds parents");
        check(LodNative.storeCascade(h, parents).length == 0, "second cascade changes nothing");
        LodNative.storeClose(h);
        check(LodNative.threadPriority(1) >= -4 && LodNative.threadPriority(0) >= -4, "threadPriority returns a status code");
        boolean threw = false;
        try { LodNative.storeGet(0, 1L, out); } catch (IllegalStateException e) { threw = true; }
        check(threw, "null handle: panic becomes IllegalStateException, JVM survives");
        System.out.println("# lodnative " + (pass == total ? "PASS " : "FAIL ") + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }

    static int[] uniform(int cell) {
        int[] c = new int[32768];
        java.util.Arrays.fill(c, cell);
        return c;
    }
}
