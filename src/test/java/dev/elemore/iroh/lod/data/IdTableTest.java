// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IdTableTest {
    @TempDir
    Path dir;

    @Test
    void idsSurviveReopening() throws Exception {
        Path f = dir.resolve("blocks.dat");
        IdTable t = new IdTable(f, 100, 99);
        assertEquals(0, t.idOf("minecraft:air", BlockClass.AIR));
        assertEquals(1, t.idOf("minecraft:oak_log[axis=y]", BlockClass.SOLID));
        assertEquals(2, t.idOf("minecraft:water[level=0]", BlockClass.FLUID));
        assertEquals(1, t.idOf("minecraft:oak_log[axis=y]", BlockClass.SOLID));
        t.close();
        IdTable again = new IdTable(f, 100, 99);
        assertEquals(3, again.size());
        assertEquals("minecraft:water[level=0]", again.nameOf(2));
        assertEquals(BlockClass.FLUID, again.classOf(2));
        assertEquals(3, again.idOf("minecraft:dandelion", BlockClass.DECOR));
        assertEquals(BlockClass.SOLID, again.classOf(500), "unknown ids count as solid");
        again.close();
    }

    @Test
    void fullTableReturnsTheOverflowId() throws Exception {
        IdTable t = new IdTable(dir.resolve("biomes.dat"), 2, 255);
        assertEquals(0, t.idOf("a", 0));
        assertEquals(1, t.idOf("b", 0));
        assertEquals(255, t.idOf("c", 0));
        assertEquals(1, t.idOf("b", 0));
        t.close();
    }
}
