// SPDX-License-Identifier: LGPL-3.0-only

package dev.elemore.iroh.lod.store;

import dev.elemore.iroh.lod.data.Cell;
import dev.elemore.iroh.lod.data.Section;
import dev.elemore.iroh.lod.data.SectionKey;
import dev.elemore.iroh.lod.nat.LodNativeLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Chunk ingest and read-into-buffer: the native path against the Java reference, byte for byte (VL-2). */
class IngestParityTest {
    @TempDir
    Path dir;

    @BeforeAll
    static void needsNative() {
        boolean ok = LodNativeLoader.load();
        if (!ok && Boolean.getBoolean("iroh.lod.nativeRequired")) fail("native backend required but not loaded: " + LodNativeLoader.unavailableReason());
        assumeTrue(ok, "native backend not built: " + LodNativeLoader.unavailableReason());
    }

    static LodStore store(Path root, boolean nativeStore) throws Exception {
        String old = System.getProperty("iroh.lod.nativeStore");
        System.setProperty("iroh.lod.nativeStore", Boolean.toString(nativeStore));
        try {
            LodStore st = new LodStore(root);
            assertEquals(nativeStore, st.isNative());
            String[] names = {"stone", "dirt", "grass", "water", "short_grass", "oak_log", "deepslate", "glow", "lava", "sand"};
            int[] cls = {3, 3, 3, 2, 1, 3, 3, 3, 2, 3};
            for (int i = 0; i < names.length; i++) st.blocks.idOf("minecraft:" + names[i], cls[i]);
            return st;
        } finally {
            if (old == null) System.clearProperty("iroh.lod.nativeStore");
            else System.setProperty("iroh.lod.nativeStore", old);
        }
    }

    /** A terrain-like chunk of {@code n} 16-tall sections; every section kind (air-only, sky-only, full) appears. */
    static ChunkBuffer chunk(Random r, int n, int base) {
        ChunkBuffer cb = new ChunkBuffer(n);
        int[] surface = new int[256];
        for (int i = 0; i < 256; i++) surface[i] = 20 + (int) (14 * Math.sin((i & 15) * 0.3 + base) + 8 * Math.cos((i >> 4) * 0.25)) + r.nextInt(3);
        for (int s = 0; s < n; s++) {
            int kind = s * 16 > 48 ? 0 : s == 5 ? 1 : 2;
            if (kind == 0) { // empty air above the terrain: sky light only, all 15
                byte[] sky = new byte[2048];
                java.util.Arrays.fill(sky, (byte) 0xFF);
                cb.addSection(null, null, sky, null, null);
                continue;
            }
            if (kind == 1) { // air with some block light (lamps floating): must be kept
                byte[] sky = new byte[2048], light = new byte[2048];
                java.util.Arrays.fill(sky, (byte) 0xFF);
                light[r.nextInt(2048)] = 0x3C;
                cb.addSection(null, null, sky, light, null);
                continue;
            }
            int[] palette = {0, 1 + r.nextInt(2), 2, 3, 4, 5, 6, 7};
            short[] idx = new short[4096];
            byte[] sky = new byte[2048], light = new byte[2048], biomes = new byte[64];
            for (int i = 0; i < 4096; i++) {
                int y = s * 16 + (i >> 8), col = i & 255, h = surface[col];
                int p = y < h - 4 ? 1 : y < h ? 1 + r.nextInt(2) * (y > h - 2 ? 1 : 0) + 1 : y == h ? 2 : y < 30 && y > h ? 3 : 0;
                if (p == 4 && r.nextInt(8) != 0) p = 0;
                if (y > h && r.nextInt(300) == 0) p = 7;
                idx[i] = (short) Math.min(p, palette.length - 1);
                int sk = y > h ? 15 : 0, bl = idx[i] == 7 ? 12 : 0;
                sky[i >> 1] |= (byte) (sk << ((i & 1) << 2));
                light[i >> 1] |= (byte) (bl << ((i & 1) << 2));
            }
            for (int i = 0; i < 64; i++) biomes[i] = (byte) ((i + base) % 9);
            cb.addSection(palette, idx, sky, light, biomes);
        }
        return cb;
    }

    static List<int[]> coords() {
        List<int[]> l = new ArrayList<>();
        for (int cx = -3; cx <= 2; cx++) for (int cz = -2; cz <= 3; cz++) l.add(new int[]{cx, cz});
        l.add(new int[]{1000, -1001});
        return l;
    }

    static TreeSet<SectionKey> keysOf(int cx, int cz, int n) {
        TreeSet<SectionKey> k = new TreeSet<>(java.util.Comparator.comparingLong(SectionKey::pack));
        for (int sy = 0; sy < 32 && sy * 2 < n; sy++) k.add(new SectionKey(0, cx >> 1, sy, cz >> 1));
        return k;
    }

    void ingestAll(LodStore st, long seed, boolean flushBetween) {
        Random r = new Random(seed);
        int i = 0;
        for (int[] c : coords()) {
            ChunkBuffer cb = chunk(r, 8, i++);
            st.ingestChunk(c[0], c[1], true, cb);
            if (flushBetween && i % 7 == 0) st.flushHeld(false); // too young to write: must change nothing visible
        }
    }

    @Test
    void nativeIngestEqualsJavaReference() throws Exception {
        try (LodStore java = store(dir.resolve("java"), false); LodStore rust = store(dir.resolve("rust"), true)) {
            ingestAll(java, 11, true);
            ingestAll(rust, 11, true);
            assertEquals(java.heldCount(), rust.heldCount(), "same sections held");
            assertTrue(java.holdsChunk(0, 0) && rust.holdsChunk(0, 0));
            assertFalse(rust.hasChunk(0, 0), "nothing is marked before the flush");
            List<SectionKey> a = java.flushHeld(true), b = rust.flushHeld(true);
            assertEquals(a.size(), b.size(), "same number of changed sections");
            assertEquals(new java.util.HashSet<>(a), new java.util.HashSet<>(b));
            assertEquals(0, rust.heldCount());
            assertFalse(rust.holdsChunk(0, 0));
            int compared = 0, uniform = 0;
            for (int[] c : coords()) {
                assertEquals(java.hasChunk(c[0], c[1]), rust.hasChunk(c[0], c[1]));
                assertTrue(rust.hasChunk(c[0], c[1]), "chunk marked after the flush");
                for (SectionKey k : keysOf(c[0], c[1], 8)) {
                    assertEquals(java.has(k), rust.has(k), "presence " + k);
                    if (!java.has(k)) continue;
                    assertEquals(java.hash(k), rust.hash(k), "hash " + k);
                    assertArrayEquals(java.get(k), rust.get(k), "cells " + k);
                    assertArrayEquals(java.getPackedOrUniform(k), rust.getPackedOrUniform(k), "packed " + k);
                    assertEquals(java.skyLit(k), rust.skyLit(k), "skyLit " + k);
                    if (java.uniformValue(k) != null) uniform++;
                    compared++;
                }
            }
            assertTrue(compared > 40, "enough sections to mean something: " + compared);
            // the air-only top sections were never stored
            assertFalse(rust.has(new SectionKey(0, 0, 5, 0)) && java.get(new SectionKey(0, 0, 5, 0)) == null);
            // a second identical ingest writes nothing
            ingestAll(java, 11, false);
            ingestAll(rust, 11, false);
            assertTrue(java.flushHeld(true).isEmpty());
            assertTrue(rust.flushHeld(true).isEmpty());
            // a changed chunk rewrites exactly its section on both
            Random r = new Random(99);
            ChunkBuffer cb = chunk(r, 8, 77);
            java.ingestChunk(0, 0, true, cb);
            rust.ingestChunk(0, 0, true, cb);
            List<SectionKey> ja = java.flushHeld(true), ra = rust.flushHeld(true);
            assertFalse(ja.isEmpty());
            assertEquals(new java.util.HashSet<>(ja), new java.util.HashSet<>(ra));
            for (SectionKey k : ja) {
                assertEquals(java.hash(k), rust.hash(k));
                assertArrayEquals(java.getPackedOrUniform(k), rust.getPackedOrUniform(k));
            }
        }
    }

    @Test
    void ingestedStoresReopenIdentically() throws Exception {
        Path jr = dir.resolve("a"), rr = dir.resolve("b");
        try (LodStore java = store(jr, false); LodStore rust = store(rr, true)) {
            ingestAll(java, 5, false);
            ingestAll(rust, 5, false);
        } // close flushes the held sections
        try (LodStore java = store(jr, false); LodStore rust = store(rr, true)) {
            int n = 0;
            for (int[] c : coords()) {
                assertTrue(java.hasChunk(c[0], c[1]) && rust.hasChunk(c[0], c[1]), "marked on close");
                for (SectionKey k : keysOf(c[0], c[1], 8)) {
                    assertEquals(java.hash(k), rust.hash(k));
                    // each side reads the other's files
                    assertArrayEquals(java.get(k), rust.get(k));
                    n++;
                }
            }
            assertTrue(n > 0);
        }
        // raw file bytes agree too: same sections, same Deflate, same layout
        Path a = jr.resolve("L0"), b = rr.resolve("L0");
        try (var fa = java.nio.file.Files.list(a)) {
            for (Path f : (Iterable<Path>) fa::iterator) {
                LodStoreFileView sa = new LodStoreFileView(f), sb = new LodStoreFileView(b.resolve(f.getFileName()));
                assertEquals(sa.entries(), sb.entries(), "entries of " + f.getFileName());
            }
        }
    }

    /** Entry hashes of one pack file, read back through the Java PackFile: layout-independent content comparison. */
    static final class LodStoreFileView {
        final Path f;
        LodStoreFileView(Path f) { this.f = f; }
        List<Long> entries() throws Exception {
            String[] parts = f.getFileName().toString().replace(".vpk", "").split("\\.");
            int rx = Integer.parseInt(parts[0]), rz = Integer.parseInt(parts[1]);
            List<Long> out = new ArrayList<>();
            try (PackFile pf = new PackFile(f, 0, rx, rz)) {
                for (int e = 0; e < 16 * 16 * 32; e++) out.add(pf.has(e) ? pf.hash(e) : 0L);
            }
            return out;
        }
    }

    @Test
    void readIntoBuffer() throws Exception {
        try (LodStore java = store(dir.resolve("j"), false); LodStore rust = store(dir.resolve("r"), true)) {
            ingestAll(java, 3, false);
            ingestAll(rust, 3, false);
            java.flushHeld(true);
            rust.flushHeld(true);
            // a uniform section too
            SectionKey uni = new SectionKey(2, 5, 3, -9);
            int[] u = Section.filled(Cell.of(3, 15, 0, 4));
            java.put(uni, u);
            rust.put(uni, u);
            ByteBuffer jb = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN), rb = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
            int checked = 0;
            for (int[] c : coords())
                for (SectionKey k : keysOf(c[0], c[1], 8)) {
                    int jn = java.getPackedInto(k, jb, 64, 1 << 19), rn = rust.getPackedInto(k, rb, 64, 1 << 19);
                    assertEquals(jn, rn, "length " + k);
                    if (rn < 0) { assertEquals(-1, rn); continue; }
                    byte[] ja = new byte[jn], ra = new byte[rn], expect = rust.getPackedOrUniform(k);
                    jb.get(64, ja);
                    rb.get(64, ra);
                    assertArrayEquals(expect, ra, "bytes " + k);
                    assertArrayEquals(ja, ra);
                    checked++;
                }
            assertTrue(checked > 40);
            int n = rust.getPackedInto(uni, rb, 0, 1 << 10);
            assertEquals(12, n, "a uniform section is a one-entry packed section");
            // too small: the needed size comes back, nothing was written past the capacity
            SectionKey big = keysOf(coords().get(5)[0], coords().get(5)[1], 8).first();
            int need = rust.getPackedOrUniform(big).length;
            rb.put(0, new byte[16]);
            int r = rust.getPackedInto(big, rb, 0, 16);
            assertEquals(-need - 2, r);
            assertEquals(-need - 2, java.getPackedInto(big, jb, 0, 16));
            assertEquals(-1, rust.getPackedInto(new SectionKey(0, 999, 0, 999), rb, 0, 100));
        }
    }

    @Test
    void badBuffersAreRefused() throws Exception {
        try (LodStore rust = store(dir.resolve("bad"), true); LodStore java = store(dir.resolve("badj"), false)) {
            ChunkBuffer cb = chunk(new Random(1), 4, 0);
            cb.buffer().position(cb.length() - 5); // claims 4 sections but is cut short
            assertThrows(RuntimeException.class, () -> rust.ingestChunk(0, 0, true, cb));
            assertThrows(RuntimeException.class, () -> java.ingestChunk(0, 0, true, cb));
            assertEquals(0, rust.heldCount());
            assertEquals(0, java.heldCount());
        }
    }
}
