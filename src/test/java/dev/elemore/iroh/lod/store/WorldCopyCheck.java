// SPDX-License-Identifier: LGPL-3.0-only

package dev.elemore.iroh.lod.store;

import dev.elemore.iroh.lod.data.SectionHash;
import dev.elemore.iroh.lod.data.SectionKey;
import dev.elemore.iroh.lod.nat.LodNativeLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * VL-2 check against real data: every LOD store under a directory (point it at a READ-ONLY REFLINK COPY of an owner
 * world's {@code vista/} or {@code vista-cache/}, never the saves themselves). For every stored section the Java and
 * the native store must agree on presence, index hash, decoded cells, packed bytes and sky flag, and every section
 * must decode; chunk-presence bits must agree. The index hash is the hash of the cells as they were put, before the
 * hidden-cell normalisation, so it equals the hash of the decoded cells only for sections that had nothing to normalise;
 * both counts are reported. Usage: {@code -Diroh.lod.native=<so> WorldCopyCheck <dir>...}; last line "# lodworld PASS|FAIL".
 */
public final class WorldCopyCheck {
    static long sections, hashEqual, hashNormalised, uniform, failures;

    static void fail(String what) {
        if (failures++ < 25) System.out.println("FAIL " + what);
    }

    public static void main(String[] args) throws Exception {
        if (!LodNativeLoader.load()) {
            System.out.println("native library not loaded: " + LodNativeLoader.unavailableReason());
            System.out.println("# lodworld FAIL (no native)");
            System.exit(1);
        }
        List<Path> roots = new ArrayList<>();
        for (String a : args)
            try (Stream<Path> s = Files.walk(Path.of(a))) {
                s.filter(p -> p.getFileName().toString().equals("L0") && Files.isDirectory(p)).forEach(p -> roots.add(p.getParent()));
            }
        long t0 = System.nanoTime();
        for (Path root : roots) check(root);
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.printf("stores %d, sections %d (uniform %d), index hash == recomputed %d, source had hidden cells %d, failures %d, %.1f s%n",
                roots.size(), sections, uniform, hashEqual, hashNormalised, failures, sec);
        System.out.println("# lodworld " + (failures == 0 && sections > 0 ? "PASS " : "FAIL ") + sections + " sections");
        System.exit(failures == 0 && sections > 0 ? 0 : 1);
    }

    static LodStore open(Path root, boolean nat) throws Exception {
        System.setProperty("iroh.lod.nativeStore", Boolean.toString(nat));
        return new LodStore(root);
    }

    static void check(Path root) throws Exception {
        long before = sections;
        try (LodStore java = open(root, false); LodStore rust = open(root, true)) {
            for (int level = 0; level <= 4; level++)
                for (int[] r : java.regions(level))
                    for (int lz = 0; lz < 16; lz++)
                        for (int lx = 0; lx < 16; lx++)
                            for (int y = 0; y < 32; y++) {
                                SectionKey k = new SectionKey(level, r[0] * 16 + lx, y, r[1] * 16 + lz);
                                boolean has = java.has(k);
                                if (has != rust.has(k)) { fail("presence differs " + k + " in " + root); continue; }
                                if (!has) continue;
                                sections++;
                                try {
                                    int[] a = java.get(k), b = rust.get(k);
                                    if (a == null || b == null) { fail("does not decode " + k + " in " + root); continue; }
                                    if (!Arrays.equals(a, b)) fail("cells differ " + k + " in " + root);
                                    long ih = java.hash(k);
                                    if (ih != rust.hash(k)) fail("index hash differs " + k + " in " + root);
                                    if (ih == SectionHash.of(a)) hashEqual++; else hashNormalised++;
                                    if (java.uniformValue(k) != null) uniform++;
                                    if (!Arrays.equals(java.getPackedOrUniform(k), rust.getPackedOrUniform(k))) fail("packed bytes differ " + k + " in " + root);
                                    if (java.skyLit(k) != rust.skyLit(k)) fail("skyLit differs " + k + " in " + root);
                                } catch (RuntimeException e) {
                                    fail("exception " + e + " at " + k + " in " + root);
                                }
                            }
            for (int[] r : java.regions(0))
                for (int cz = 0; cz < 32; cz++)
                    for (int cx = 0; cx < 32; cx++) {
                        int x = r[0] * 32 + cx, z = r[1] * 32 + cz;
                        if (java.hasChunk(x, z) != rust.hasChunk(x, z)) fail("chunk presence differs " + x + "," + z + " in " + root);
                    }
        }
        System.out.println((sections - before) + " sections in " + root);
    }
}
