// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.store;

import dev.elemore.iroh.lod.data.Cell;
import dev.elemore.iroh.lod.data.Section;
import dev.elemore.iroh.lod.data.SectionHash;
import dev.elemore.iroh.lod.data.SectionKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PackFileTest {
    @TempDir
    Path dir;

    static int[] random(Random r, int distinct) {
        int[] values = new int[distinct];
        for (int i = 0; i < distinct; i++) values[i] = r.nextInt();
        int[] cells = new int[Section.VOLUME];
        for (int i = 0; i < cells.length; i++) cells[i] = values[r.nextInt(distinct)];
        return cells;
    }

    @Test
    void sectionsRoundTripAndSurviveReopening() throws Exception {
        Path f = dir.resolve("L0/0.0.vpk");
        Random r = new Random(42);
        int[] a = random(r, 5), b = random(r, 3000);
        try (PackFile p = new PackFile(f, 0, 0, 0)) {
            assertNull(p.read(PackFile.entry(1, 2, 3)));
            assertTrue(p.write(PackFile.entry(1, 2, 3), a));
            assertTrue(p.write(PackFile.entry(15, 31, 15), b));
            assertTrue(p.write(PackFile.entry(0, 0, 0), Section.filled(Cell.OPEN_AIR)));
            assertFalse(p.write(PackFile.entry(1, 2, 3), a.clone()), "same content is not rewritten");
            assertEquals(SectionHash.of(a), p.hash(PackFile.entry(1, 2, 3)));
            p.markChunk(31, 0);
        }
        try (PackFile p = new PackFile(f, 0, 0, 0)) {
            assertArrayEquals(a, p.read(PackFile.entry(1, 2, 3)));
            assertArrayEquals(b, p.read(PackFile.entry(15, 31, 15)));
            assertArrayEquals(Section.filled(Cell.OPEN_AIR), p.read(PackFile.entry(0, 0, 0)));
            assertNull(p.readPackedEntry(PackFile.entry(0, 0, 0)), "uniform sections have no payload");
            assertEquals(SectionHash.ofUniform(Cell.OPEN_AIR), p.hash(PackFile.entry(0, 0, 0)));
            assertTrue(p.hasChunk(31, 0));
            assertFalse(p.hasChunk(0, 31));
            assertEquals(3, p.count());
        }
        assertThrows(java.io.IOException.class, () -> new PackFile(f, 1, 0, 0), "wrong level is refused");
    }

    @Test
    void overwritesReuseSpaceWithoutCorruption() throws Exception {
        Random r = new Random(7);
        int[][] expect = new int[64][];
        Path f = dir.resolve("L1/-1.2.vpk");
        try (PackFile p = new PackFile(f, 1, -1, 2)) {
            for (int round = 0; round < 6; round++)
                for (int i = 0; i < 64; i++) {
                    // sizes swing between tiny and large so freed runs get split and reused
                    int[] cells = random(r, (round + i) % 3 == 0 ? 2 + r.nextInt(4) : 200 + r.nextInt(30000));
                    p.write(PackFile.entry(i & 15, i >> 4, 7), cells);
                    expect[i] = cells;
                }
            for (int i = 0; i < 64; i++) assertArrayEquals(expect[i], p.read(PackFile.entry(i & 15, i >> 4, 7)), "entry " + i);
        }
        try (PackFile p = new PackFile(f, 1, -1, 2)) {
            for (int i = 0; i < 64; i++) assertArrayEquals(expect[i], p.read(PackFile.entry(i & 15, i >> 4, 7)), "reopened entry " + i);
        }
    }

    @Test
    void storeHandlesNegativeCoordinatesAndManyFiles() throws Exception {
        try (LodStore s = new LodStore(dir.resolve("store"))) {
            assertEquals(0, s.blocks.idOf("minecraft:air", 0));
            Random r = new Random(3);
            // more regions than the open-file LRU holds, so files get closed and reopened under the test
            int[][] keys = new int[120][];
            int[][] cells = new int[120][];
            for (int i = 0; i < 120; i++) {
                keys[i] = new int[]{(i - 60) * 17 - 1, i % 28, -(i * 13) - 1};
                cells[i] = random(r, 1 + i);
                assertTrue(s.put(new SectionKey(i % 4, keys[i][0], keys[i][1], keys[i][2]), cells[i]));
            }
            for (int i = 0; i < 120; i++)
                assertArrayEquals(dev.elemore.iroh.lod.data.Hidden.normalize(cells[i], s.blocks), s.get(new SectionKey(i % 4, keys[i][0], keys[i][1], keys[i][2])), "key " + i); // stored with hidden cells normalised
            assertNull(s.get(new SectionKey(0, 5, 40, 5)), "out of height range");
            assertFalse(s.put(new SectionKey(0, 5, -1, 5), cells[0]));
            s.markChunk(-1, -33);
            assertTrue(s.hasChunk(-1, -33));
            assertFalse(s.hasChunk(-2, -33));
            assertTrue(s.regions(0).size() > 0);
        }
    }

    /** Four writers filling the four quadrants of shared sections at once, the way four chunk ingests do. */
    @Test
    void concurrentQuadrantWritesAreNotLost() throws Exception {
        try (LodStore s = new LodStore(dir.resolve("race"))) {
            int sections = 40;
            Thread[] threads = new Thread[4];
            for (int q = 0; q < 4; q++) {
                int quad = q;
                threads[q] = new Thread(() -> {
                    for (int i = 0; i < sections; i++) {
                        SectionKey key = new SectionKey(0, i % 7, i % 5, i / 7);
                        synchronized (s.lockFor(key)) {
                            int[] cells = s.get(key);
                            if (cells == null) cells = Section.filled(Cell.OPEN_AIR);
                            for (int y = 0; y < 32; y++)
                                for (int z = 0; z < 16; z++)
                                    for (int x = 0; x < 16; x++)
                                        cells[Section.index((quad & 1) * 16 + x, y, (quad >> 1) * 16 + z)] = Cell.of(quad + 1, 0, 0, i);
                            s.put(key, cells);
                        }
                    }
                });
            }
            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();
            for (int i = 0; i < sections; i++) {
                int[] cells = s.get(new SectionKey(0, i % 7, i % 5, i / 7));
                for (int quad = 0; quad < 4; quad++)
                    assertEquals(Cell.of(quad + 1, 0, 0, i), cells[Section.index((quad & 1) * 16 + 3, 0, (quad >> 1) * 16 + 5)], "section " + i + " quadrant " + quad); // y 0: a section face, never normalised
            }
        }
    }

    /** A section copied as stored (what the network does) reads back identically and keeps its hash. */
    @Test
    void storedCopiesAreExact() throws Exception {
        Random r = new Random(9);
        int[] cells = random(r, 300);
        try (LodStore a = new LodStore(dir.resolve("a")); LodStore b = new LodStore(dir.resolve("b"))) {
            SectionKey k = new SectionKey(1, -5, 3, 9), u = new SectionKey(0, 2, 2, 2);
            a.put(k, cells);
            a.put(u, Section.filled(Cell.of(4, 0, 0, 1)));
            List<SectionKey> heard = new java.util.ArrayList<>();
            b.listeners.add(heard::add);
            assertTrue(b.putStored(k, a.getStored(k)));
            assertTrue(b.putStored(u, a.getStored(u)));
            assertFalse(b.putStored(k, a.getStored(k)), "same hash: no write");
            assertArrayEquals(dev.elemore.iroh.lod.data.Hidden.normalize(cells, a.blocks), b.get(k)); // the copy is exact; the store keeps hidden cells normalised
            assertArrayEquals(Section.filled(Cell.of(4, 0, 0, 1)), b.get(u));
            assertEquals(a.hash(k), b.hash(k));
            assertEquals(a.hash(u), b.hash(u));
            assertEquals(List.of(k, u), heard);
        }
    }

    /** A version-1 file (LZ4, 1 KiB sectors) upgrades in place: every entry and hash kept, hidden cells normalised, smaller. */
    @Test
    void versionOneUpgrades() throws Exception {
        Random r = new Random(11);
        java.nio.file.Path f = dir.resolve("L0/0.0.vpk");
        int[][] cells = new int[40][];
        long[] hashes = new long[40];
        try (PackFile p = new PackFile(f, 0, 0, 0)) {
            for (int i = 0; i < 40; i++) {
                cells[i] = random(r, 2 + i * 50);
                p.write(i, cells[i]);
                hashes[i] = p.hash(i);
            }
            p.write(100, Section.filled(Cell.of(7, 0, 0, 0)));
            p.markChunk(3, 4);
        }
        // turn it into what version 1 wrote: LZ4 payloads in 1 KiB sectors
        java.nio.file.Path v1 = dir.resolve("L0/1.0.vpk");
        writeVersionOne(f, v1, 1, 0);
        long before = java.nio.file.Files.size(v1);
        dev.elemore.iroh.lod.data.ClassTable solid = b -> dev.elemore.iroh.lod.data.BlockClass.SOLID;
        try (PackFile p = new PackFile(v1, 0, 1, 0)) {
            assertEquals(1, p.version());
            for (int i = 0; i < 40; i++) assertArrayEquals(cells[i], p.read(i), "v1 entry " + i);
            assertTrue(p.upgrade(solid));
            assertEquals(2, p.version());
            for (int i = 0; i < 40; i++) {
                assertArrayEquals(dev.elemore.iroh.lod.data.Hidden.normalize(cells[i], solid), p.read(i), "upgraded entry " + i);
                assertEquals(hashes[i], p.hash(i), "hash kept " + i);
            }
            assertArrayEquals(Section.filled(Cell.of(7, 0, 0, 0)), p.read(100));
            assertTrue(p.hasChunk(3, 4));
        }
        assertTrue(java.nio.file.Files.size(v1) < before, "smaller after the upgrade");
        try (PackFile p = new PackFile(v1, 0, 1, 0)) {
            assertEquals(2, p.version());
            assertEquals(hashes[5], p.hash(5));
        }
    }

    /** Builds a version-1 file from a version-2 one, the way the old code wrote it. */
    static void writeVersionOne(java.nio.file.Path from, java.nio.file.Path to, int rx, int rz) throws Exception {
        var lz4 = net.jpountz.lz4.LZ4Factory.fastestInstance().highCompressor();
        try (PackFile src = new PackFile(from, 0, 0, 0); var ch = java.nio.channels.FileChannel.open(to,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer head = java.nio.ByteBuffer.allocate(4096).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            head.putInt(0x3150_4B56).putInt(1).putInt(0).putInt(rx).putInt(rz);
            head.position(64);
            for (int cz = 0; cz < 32; cz++) for (int cx = 0; cx < 32; cx += 8) {
                int b = 0;
                for (int k = 0; k < 8; k++) if (src.hasChunk(cx + k, cz)) b |= 1 << k;
                head.put((byte) b);
            }
            head.clear();
            ch.write(head, 0);
            java.nio.ByteBuffer index = java.nio.ByteBuffer.allocate(PackFile.ENTRIES * 16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int sector = (4096 + PackFile.ENTRIES * 16) / 1024;
            for (int e = 0; e < PackFile.ENTRIES; e++) {
                PackFile.Stored st = src.readStored(e);
                if (st == null) { index.putInt(0).putInt(0).putLong(0); continue; }
                if (st.uniform()) { index.putInt(0).putInt(-1).putLong(st.cell() & 0xFFFFFFFFL); continue; }
                byte[] packed = PackFile.unwrap(st.payload());
                byte[] c = lz4.compress(packed);
                java.nio.ByteBuffer p = java.nio.ByteBuffer.allocate(4 + c.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                p.putInt(packed.length).put(c).flip();
                ch.write(p, (long) sector * 1024);
                index.putInt(sector).putInt(4 + c.length).putLong(st.hash());
                sector += (4 + c.length + 1023) / 1024;
            }
            index.flip();
            ch.write(index, 4096);
        }
    }
}
