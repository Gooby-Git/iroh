// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.store;

import dev.elemore.iroh.lod.data.BlockClass;
import dev.elemore.iroh.lod.data.Cell;
import dev.elemore.iroh.lod.data.ClassTable;
import dev.elemore.iroh.lod.data.Downsampler;
import dev.elemore.iroh.lod.data.Hidden;
import dev.elemore.iroh.lod.data.PackedSection;
import dev.elemore.iroh.lod.data.Section;
import dev.elemore.iroh.lod.data.SectionHash;
import dev.elemore.iroh.lod.data.SectionKey;
import dev.elemore.iroh.lod.nat.LodNative;
import dev.elemore.iroh.lod.nat.LodNativeLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The native backend against the Java reference: the same input must give the same output, bit for bit, and the two
 * stores must read each other's files (docs/RUST_PLAN.md section 8).
 */
class NativeParityTest {
    @TempDir
    Path dir;

    /** Ids 0-3 air/decor/fluid/solid kinds, the rest solid except a few decor and fluid ids. */
    static final byte[] CLASSES = new byte[600];
    static final ClassTable TABLE = b -> b < CLASSES.length ? CLASSES[b] : BlockClass.SOLID;

    static {
        for (int i = 0; i < CLASSES.length; i++) CLASSES[i] = (byte) BlockClass.SOLID;
        CLASSES[0] = BlockClass.AIR;
        CLASSES[1] = BlockClass.DECOR;
        CLASSES[2] = BlockClass.FLUID;
        for (int i = 10; i < 20; i++) CLASSES[i] = BlockClass.DECOR;
        for (int i = 20; i < 25; i++) CLASSES[i] = BlockClass.FLUID;
    }

    @BeforeAll
    static void needsNative() {
        boolean ok = LodNativeLoader.load();
        if (!ok && Boolean.getBoolean("iroh.lod.nativeRequired")) fail("native backend required but not loaded: " + LodNativeLoader.unavailableReason());
        assumeTrue(ok, "native backend not built: " + LodNativeLoader.unavailableReason());
    }

    /** Terrain-like: stone below a wavy surface, a grass/dirt top, water in hollows, plants and lights on top. */
    static int[] terrain(Random r, int kinds) {
        int[] c = new int[Section.VOLUME];
        int biome = r.nextInt(40);
        for (int z = 0; z < 32; z++)
            for (int x = 0; x < 32; x++) {
                int h = 8 + (int) (10 * Math.sin((x + r.nextInt(2)) * 0.2) + 6 * Math.cos(z * 0.15)) + r.nextInt(3);
                for (int y = 0; y < 32; y++) {
                    int block, sky = 0, bl = 0;
                    if (y < h - 3) block = 30 + r.nextInt(kinds); // stone kinds, ores (hidden-cell fodder)
                    else if (y < h) block = 26; // dirt
                    else if (y == h) block = 27; // grass
                    else if (y < 16) { block = 21; sky = 12; } // water
                    else if (y == h + 1 && r.nextInt(5) == 0) { block = 10 + r.nextInt(10); sky = 15; bl = r.nextInt(3) == 0 ? 14 : 0; }
                    else { block = 0; sky = 15; }
                    c[Section.index(x, y, z)] = Cell.of(block, sky, bl, biome + (x >> 4));
                }
            }
        return c;
    }

    static int[] noise(Random r, int palette) {
        int[] c = new int[Section.VOLUME];
        for (int i = 0; i < c.length; i++) c[i] = Cell.of(r.nextInt(palette), r.nextInt(16), r.nextInt(16), r.nextInt(4));
        return c;
    }

    static List<int[]> samples() {
        Random r = new Random(42);
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < 12; i++) out.add(terrain(r, 1 + i * 20));
        for (int p : new int[]{1, 2, 3, 4, 5, 16, 17, 255, 256, 257, 599}) out.add(noise(r, p));
        out.add(Section.filled(Cell.of(30, 0, 0, 3)));
        out.add(Section.filled(Cell.OPEN_AIR));
        return out;
    }

    @Test
    void hashesMatch() {
        for (int[] c : samples()) assertEquals(SectionHash.of(c), LodNative.hashCells(c));
        for (int v : new int[]{0, Cell.OPEN_AIR, -1, 0x12345678}) assertEquals(SectionHash.ofUniform(v), LodNative.hashUniform(v));
    }

    @Test
    void packedBytesMatch() {
        for (int[] c : samples()) assertArrayEquals(PackedSection.encode(c), LodNative.encodePacked(c));
    }

    @Test
    void hiddenCellsMatch() {
        for (int[] c : samples()) {
            int[] java = Hidden.normalize(c, TABLE);
            int[] rust = LodNative.hiddenNormalize(c, CLASSES);
            if (java == c) assertNull(rust, "nothing hidden in Java, something in Rust");
            else assertArrayEquals(java, rust);
        }
    }

    @Test
    void downsamplingMatches() {
        Random r = new Random(7);
        List<int[]> s = samples();
        Downsampler ds = new Downsampler(TABLE);
        for (int trial = 0; trial < 40; trial++) {
            int present = trial == 0 ? 0xFF : r.nextInt(256);
            if (present == 0) present = 1;
            int[][] kids = new int[8][];
            int[] flat = new int[8 * Section.VOLUME];
            for (int o = 0; o < 8; o++) {
                if ((present >> o & 1) == 0) continue;
                kids[o] = s.get(r.nextInt(s.size()));
                System.arraycopy(kids[o], 0, flat, o * Section.VOLUME, Section.VOLUME);
            }
            assertArrayEquals(ds.downsample(kids), LodNative.downsample(flat, present, CLASSES), "trial " + trial);
        }
    }

    LodStore store(Path root, boolean nativeStore) throws Exception {
        String old = System.getProperty("iroh.lod.nativeStore");
        System.setProperty("iroh.lod.nativeStore", Boolean.toString(nativeStore));
        try {
            LodStore st = new LodStore(root);
            assertEquals(nativeStore, st.isNative());
            for (int i = 1; i < CLASSES.length; i++) assertEquals(i, st.blocks.idOf("b" + i, CLASSES[i])); // id 0 is air
            return st;
        } finally {
            if (old == null) System.clearProperty("iroh.lod.nativeStore");
            else System.setProperty("iroh.lod.nativeStore", old);
        }
    }

    /** Java writes, Rust reads; Rust writes, Java reads. Hashes, cells, uniform entries and presence all agree. */
    @Test
    void storesReadEachOther() throws Exception {
        List<int[]> s = samples();
        Path root = dir.resolve("store");
        List<SectionKey> keys = new ArrayList<>();
        try (LodStore java = store(root, false)) {
            for (int i = 0; i < s.size(); i++) {
                SectionKey k = new SectionKey(i % 3, i * 7 - 40, i % 32, -i * 5);
                keys.add(k);
                assertTrue(java.put(k, s.get(i)));
            }
            java.markChunk(-3, 70);
        }
        try (LodStore rust = store(root, true)) {
            for (int i = 0; i < keys.size(); i++) {
                SectionKey k = keys.get(i);
                assertArrayEquals(Hidden.normalize(s.get(i), TABLE), rust.get(k), "Rust reads Java " + k);
                assertEquals(SectionHash.of(s.get(i)), rust.hash(k));
                assertFalse(rust.put(k, s.get(i)), "same content is not rewritten");
            }
            assertTrue(rust.hasChunk(-3, 70));
            assertFalse(rust.hasChunk(-3, 71));
            // Rust overwrites, shifted contents
            for (int i = 0; i < keys.size(); i++) assertTrue(rust.put(keys.get(i), s.get((i + 1) % s.size())) || s.get(i) == s.get((i + 1) % s.size()));
            rust.markChunk(5, 5);
        }
        try (LodStore java = store(root, false)) {
            for (int i = 0; i < keys.size(); i++) {
                int[] want = s.get((i + 1) % s.size());
                assertArrayEquals(Hidden.normalize(want, TABLE), java.get(keys.get(i)), "Java reads Rust " + keys.get(i));
                assertEquals(SectionHash.of(want), java.hash(keys.get(i)));
                PackFile.Stored st = java.getStored(keys.get(i));
                byte[] packed = java.getPackedOrUniform(keys.get(i));
                assertNotNull(packed);
                assertNotNull(st);
            }
            assertTrue(java.hasChunk(5, 5));
        }
        // stored copies cross unchanged (what the network does)
        try (LodStore rust = store(root, true); LodStore other = store(dir.resolve("copy"), false)) {
            for (SectionKey k : keys) {
                assertTrue(other.putStored(k, rust.getStored(k)));
                assertArrayEquals(rust.get(k), other.get(k));
                assertArrayEquals(rust.getPackedOrUniform(k), other.getPackedOrUniform(k));
                assertEquals(rust.uniformValue(k), other.uniformValue(k));
            }
        }
    }

    /** Surface-first ordering: the sky-lit layer and what touches it first, buried and deep cave sections after. */
    @Test
    void surfaceSplitPutsTheTopLayerFirst() throws Exception {
        for (boolean nat : new boolean[]{false, true}) {
            try (LodStore st = store(dir.resolve("split" + nat), nat)) {
                int stone = Cell.of(30, 0, 0, 1), air = Cell.of(0, 15, 0, 1), cave = Cell.of(0, 0, 0, 1);
                st.put(new SectionKey(0, 0, 9, 0), Section.filled(air));
                int[] surface = Section.filled(stone);
                for (int i = Section.index(0, 20, 0); i < Section.VOLUME; i++) surface[i] = air; // ground up to y 20, sky above
                st.put(new SectionKey(0, 0, 8, 0), surface);
                int[] pocket = Section.filled(stone);
                pocket[Section.index(5, 5, 5)] = cave;
                st.put(new SectionKey(0, 0, 7, 0), pocket);      // right under the surface: first (its top faces show)
                st.put(new SectionKey(0, 0, 6, 0), Section.filled(stone));
                int[] deep = Section.filled(cave);
                deep[0] = stone;
                st.put(new SectionKey(0, 0, 2, 0), deep);        // an underground biome's cavern: later
                st.put(new SectionKey(0, 1, 6, 0), Section.filled(stone)); // beside nothing lit: later
                st.put(new SectionKey(0, 1, 8, 0), Section.filled(stone)); // a cliff beside the lit section: first
                List<SectionKey> first = new ArrayList<>(), later = new ArrayList<>();
                st.surfaceSplit(0, 0, 0, first::add, later::add);
                st.surfaceSplit(0, 1, 0, first::add, later::add);
                assertEquals(List.of(new SectionKey(0, 0, 9, 0), new SectionKey(0, 0, 8, 0), new SectionKey(0, 0, 7, 0), new SectionKey(0, 1, 8, 0)), first, "native " + nat);
                assertEquals(List.of(new SectionKey(0, 0, 6, 0), new SectionKey(0, 0, 2, 0), new SectionKey(0, 1, 6, 0)), later, "native " + nat);
                // a write changes the answer
                st.put(new SectionKey(0, 0, 2, 0), Section.filled(air));
                assertTrue(st.skyLit(new SectionKey(0, 0, 2, 0)));
            }
        }
    }

    /** The native cascade gives the parents the Java one does. */
    @Test
    void cascadeMatches() throws Exception {
        Random r = new Random(3);
        List<int[]> s = samples();
        List<SectionKey> parents = new ArrayList<>();
        Path a = dir.resolve("a"), b = dir.resolve("b");
        try (LodStore java = store(a, false); LodStore rust = store(b, true)) {
            for (int px = -2; px < 2; px++)
                for (int pz = 0; pz < 2; pz++) {
                    SectionKey p = new SectionKey(1, px, 3, pz);
                    parents.add(p);
                    for (int o = 0; o < 8; o++) {
                        if (r.nextInt(4) == 0) continue;
                        int[] c = s.get(r.nextInt(s.size()));
                        java.put(p.child(o), c);
                        rust.put(p.child(o), c);
                    }
                }
            Downsampler ds = new Downsampler(java.blocks);
            for (SectionKey p : parents) {
                int[][] kids = new int[8][];
                boolean any = false;
                for (int o = 0; o < 8; o++) any |= (kids[o] = java.get(p.child(o))) != null;
                if (any) java.put(p, ds.downsample(kids));
            }
            List<SectionKey> changed = rust.cascade(parents);
            for (SectionKey p : parents) {
                assertArrayEquals(java.get(p), rust.get(p), "parent " + p);
                assertEquals(java.hash(p), rust.hash(p));
            }
            assertFalse(changed.isEmpty());
            assertTrue(rust.cascade(parents).isEmpty(), "a second cascade changes nothing");
        }
    }

    /** A version-1 file upgrades natively exactly as the Java upgrade does. */
    @Test
    void versionOneUpgradesNatively() throws Exception {
        Random r = new Random(11);
        Path root = dir.resolve("up");
        Path f = root.resolve("L0/0.0.vpk");
        List<int[]> s = samples();
        long[] hashes = new long[s.size()];
        try (PackFile p = new PackFile(f, 0, 0, 0)) {
            for (int i = 0; i < s.size(); i++) {
                p.write(i, s.get(i));
                hashes[i] = p.hash(i);
            }
            p.markChunk(3, 4);
        }
        PackFileTest.writeVersionOne(f, root.resolve("L0/1.0.vpk"), 1, 0);
        try (LodStore rust = store(root, true)) {
            for (int i = 0; i < s.size(); i++) assertArrayEquals(s.get(i), rust.get(new SectionKey(0, 16 + (i & 15), i >> 8, (i >> 4) & 15)), "v1 read " + i);
            assertEquals(1, LodNative.storeVersion(handle(rust), 0, 1, 0));
            assertTrue(LodNative.storeUpgrade(handle(rust), 0, 1, 0));
            assertEquals(2, LodNative.storeVersion(handle(rust), 0, 1, 0));
            for (int i = 0; i < s.size(); i++) {
                SectionKey k = new SectionKey(0, 16 + (i & 15), i >> 8, (i >> 4) & 15);
                assertArrayEquals(Hidden.normalize(s.get(i), TABLE), rust.get(k), "upgraded " + i);
                assertEquals(hashes[i], rust.hash(k));
            }
            assertTrue(rust.hasChunk(32 + 3, 4));
        }
        try (PackFile p = new PackFile(root.resolve("L0/1.0.vpk"), 0, 1, 0)) {
            assertEquals(2, p.version());
            for (int i = 0; i < s.size(); i++) assertEquals(hashes[i], p.hash(i));
        }
        r.nextInt();
    }

    private static long handle(LodStore st) throws Exception {
        var f = LodStore.class.getDeclaredField("nat");
        f.setAccessible(true);
        return f.getLong(st);
    }
}
