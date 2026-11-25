// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.store;

import dev.elemore.iroh.lod.data.SectionKey;
import dev.elemore.iroh.lod.nat.LodNative;
import dev.elemore.iroh.lod.nat.LodNativeLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Java vs native store speed: {@code ./gradlew test --tests '*NativeBench' -Diroh.lod.bench=true}. */
class NativeBench {
    @TempDir
    Path dir;

    @Test
    void bench() throws Exception {
        assumeTrue(Boolean.getBoolean("iroh.lod.bench") || "true".equals(System.getenv("IROH_LOD_BENCH")));
        assumeTrue(LodNativeLoader.load());
        Random r = new Random(1);
        List<int[]> secs = new ArrayList<>();
        for (int i = 0; i < 64; i++) secs.add(NativeParityTest.terrain(r, 1 + (i % 12) * 20));
        for (int round = 0; round < 3; round++)
            for (boolean nat : new boolean[]{false, true}) run(nat, secs, round);
    }

    private void run(boolean nat, List<int[]> secs, int round) throws Exception {
        NativeParityTest t = new NativeParityTest();
        t.dir = dir;
        Path root = dir.resolve((nat ? "n" : "j") + round);
        java.lang.reflect.Method m = NativeParityTest.class.getDeclaredMethod("store", Path.class, boolean.class);
        m.setAccessible(true);
        try (LodStore st = (LodStore) m.invoke(t, root, nat)) {
            int n = 0;
            long t0 = System.nanoTime();
            List<SectionKey> parents = new ArrayList<>();
            for (int px = 0; px < 8; px++)
                for (int pz = 0; pz < 8; pz++) {
                    SectionKey p = new SectionKey(1, px, 2, pz);
                    parents.add(p);
                    for (int o = 0; o < 8; o++) st.put(p.child(o), secs.get(n++ % secs.size()));
                }
            long t1 = System.nanoTime();
            for (int rep = 0; rep < 2; rep++)
                for (SectionKey p : parents) for (int o = 0; o < 8; o++) st.get(p.child(o));
            long t2 = System.nanoTime();
            if (nat) st.cascade(parents);
            else {
                dev.elemore.iroh.lod.data.Downsampler ds = new dev.elemore.iroh.lod.data.Downsampler(st.blocks);
                for (SectionKey p : parents) {
                    int[][] kids = new int[8][];
                    for (int o = 0; o < 8; o++) kids[o] = st.get(p.child(o));
                    st.put(p, ds.downsample(kids));
                }
            }
            long t3 = System.nanoTime();
            System.out.printf("[bench r%d] %-6s put %.3f ms/section, get %.3f ms/section, cascade %.3f ms/parent, file %d KiB%n", round,
                    nat ? "native" : "java", (t1 - t0) / 1e6 / 512, (t2 - t1) / 1e6 / 1024, (t3 - t2) / 1e6 / 64,
                    java.nio.file.Files.walk(root).filter(java.nio.file.Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum() >> 10);
        }
    }
}
