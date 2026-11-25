// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SectionHashTest {
    @Test
    void uniformShortcutMatchesFullHash() {
        for (int v : new int[]{0, Cell.OPEN_AIR, 0x12345678, -1}) assertEquals(SectionHash.of(Section.filled(v)), SectionHash.ofUniform(v));
    }

    @Test
    void anyCellChangeChangesTheHash() {
        int[] cells = Section.filled(Cell.OPEN_AIR);
        long base = SectionHash.of(cells);
        for (int i : new int[]{0, 1, 1023, 1024, Section.VOLUME - 1}) {
            cells[i] ^= 1;
            assertNotEquals(base, SectionHash.of(cells), "cell " + i);
            cells[i] ^= 1;
        }
        int[] swapped = Section.filled(Cell.OPEN_AIR);
        swapped[0] = 5;
        int[] other = Section.filled(Cell.OPEN_AIR);
        other[1] = 5;
        assertNotEquals(SectionHash.of(swapped), SectionHash.of(other));
    }

    /** Stored hashes must never change meaning; if this fails, every cache would look stale. */
    @Test
    void hashIsFrozen() {
        assertEquals(SectionHash.ofUniform(0), SectionHash.of(new int[Section.VOLUME]));
        assertEquals(FROZEN_AIR, SectionHash.ofUniform(Cell.OPEN_AIR));
    }

    static final long FROZEN_AIR = -7113584164081096652L; // recorded 2026-10-29 from the first build
}
