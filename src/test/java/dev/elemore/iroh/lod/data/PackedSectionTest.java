// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PackedSectionTest {
    private static int[] random(int distinct, long seed) {
        Random r = new Random(seed);
        int[] values = new int[distinct];
        for (int i = 0; i < distinct; i++) values[i] = r.nextInt();
        int[] cells = new int[Section.VOLUME];
        for (int i = 0; i < cells.length; i++) cells[i] = values[i < distinct ? i : r.nextInt(distinct)];
        return cells;
    }

    @Test
    void roundTripsEveryPaletteWidth() {
        int[][] cases = {{1, 0}, {2, 1}, {3, 2}, {4, 2}, {5, 4}, {16, 4}, {17, 8}, {256, 8}, {257, 16}, {5000, 16}, {Section.VOLUME, 16}};
        for (int[] c : cases) {
            int[] cells = random(c[0], c[0]);
            byte[] packed = PackedSection.encode(cells);
            assertEquals(c[1], packed[1], "bits for palette " + c[0]);
            assertEquals(PackedSection.HEADER + 4 * c[0] + Section.VOLUME * c[1] / 8, packed.length, "size for palette " + c[0]);
            assertArrayEquals(cells, PackedSection.decode(packed), "palette " + c[0]);
        }
    }

    @Test
    void paletteKeepsFirstAppearanceOrder() {
        int[] cells = Section.filled(7);
        cells[5] = 3;
        cells[9] = 11;
        byte[] packed = PackedSection.encode(cells);
        var in = java.nio.ByteBuffer.wrap(packed).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        in.position(8);
        assertEquals(7, in.getInt());
        assertEquals(3, in.getInt());
        assertEquals(11, in.getInt());
    }

    @Test
    void rejectsCorruptData() {
        byte[] packed = PackedSection.encode(random(3, 1));
        packed[0] = 9;
        assertThrows(IllegalArgumentException.class, () -> PackedSection.decode(packed));
        byte[] bad = PackedSection.encode(random(3, 1));
        bad[PackedSection.HEADER + 12] = (byte) 0xFF; // first word: index 3 in a 3-entry palette
        assertThrows(IllegalArgumentException.class, () -> PackedSection.decode(bad));
    }
}
