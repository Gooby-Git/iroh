// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DownsamplerTest {
    static final int AIR = 0, STONE = 1, GRASS = 2, DIRT = 3, WATER = 4, FLOWER = 5, SAND = 6, TORCH = 7;
    static final ClassTable CLASSES = b -> switch (b) {
        case AIR -> BlockClass.AIR;
        case WATER -> BlockClass.FLUID;
        case FLOWER, TORCH -> BlockClass.DECOR;
        default -> BlockClass.SOLID;
    };

    interface World {
        int cell(int x, int y, int z);
    }

    /** Splits a 64^3 fine world into the eight children of one parent. */
    static int[][] children(World w) {
        int[][] kids = new int[8][Section.VOLUME];
        for (int o = 0; o < 8; o++)
            for (int y = 0; y < 32; y++)
                for (int z = 0; z < 32; z++)
                    for (int x = 0; x < 32; x++)
                        kids[o][Section.index(x, y, z)] = w.cell((o & 1) * 32 + x, (o >> 2 & 1) * 32 + y, (o >> 1 & 1) * 32 + z);
        return kids;
    }

    static int[] down(World w) {
        return new Downsampler(CLASSES).downsample(children(w));
    }

    static int block(int[] parent, int x, int y, int z) {
        return Cell.block(parent[Section.index(x, y, z)]);
    }

    static int air() {
        return Cell.of(AIR, 15, 0, 0);
    }

    static int solid(int b) {
        return Cell.of(b, 0, 0, 0);
    }

    @Test
    void buriedRockStaysRock() {
        int[] p = down((x, y, z) -> solid(STONE));
        for (int c : p) assertEquals(STONE, Cell.block(c));
    }

    @Test
    void surfaceKeepsTheTopBlock() {
        int[] p = down((x, y, z) -> y < 21 ? solid(DIRT) : y == 21 ? solid(GRASS) : air());
        assertEquals(GRASS, block(p, 7, 10, 7), "the cell holding the grass layer");
        assertEquals(DIRT, block(p, 7, 9, 7), "below the surface");
        assertEquals(AIR, block(p, 7, 11, 7), "above the surface");
    }

    @Test
    void thinWallsSurvive() {
        for (int wallX : new int[]{10, 11}) {
            int[] p = down((x, y, z) -> x == wallX ? solid(STONE) : air());
            for (int y = 0; y < 32; y++) assertEquals(STONE, block(p, 5, y, 9), "wall at x=" + wallX + ", y " + y);
            assertEquals(AIR, block(p, 4, 3, 9));
            assertEquals(AIR, block(p, 6, 3, 9));
        }
    }

    @Test
    void floatingBlockSurvives() {
        int[] p = down((x, y, z) -> x == 30 && y == 30 && z == 30 ? solid(STONE) : air());
        assertEquals(STONE, block(p, 15, 15, 15));
        assertEquals(AIR, block(p, 14, 15, 15));
    }

    @Test
    void lakesKeepTheirWater() {
        int[] deep = down((x, y, z) -> y < 20 ? solid(SAND) : y < 22 ? Cell.of(WATER, 13, 0, 0) : air());
        assertEquals(WATER, block(deep, 8, 10, 8), "two-deep water");
        assertEquals(SAND, block(deep, 8, 9, 8), "lake bed under water is not exposed");
        int[] shallow = down((x, y, z) -> y < 21 ? solid(SAND) : y == 21 ? Cell.of(WATER, 14, 0, 0) : air());
        assertEquals(WATER, block(shallow, 8, 10, 8), "one-deep water over sand: fluids tie solids and win");
    }

    @Test
    void decorationDoesNotCountAsGround() {
        int[] p = down((x, y, z) -> y < 22 ? solid(GRASS) : y == 22 ? Cell.of(FLOWER, 15, 0, 0) : air());
        assertEquals(AIR, block(p, 3, 11, 3), "flowers alone are air at distance");
        assertEquals(GRASS, block(p, 3, 10, 3), "grass under flowers is exposed");
    }

    @Test
    void lightIsKept() {
        int[] p = down((x, y, z) -> x == 0 && y == 22 && z == 0 ? Cell.of(TORCH, 15, 14, 0)
                : y < 22 ? solid(STONE) : Cell.of(AIR, 15, Math.max(0, 13 - x - z), 0));
        int c = p[Section.index(0, 11, 0)];
        assertEquals(14, Cell.blockLight(c), "brightest child");
        assertEquals(15, Cell.sky(c));
        assertEquals(0, Cell.sky(p[Section.index(5, 5, 5)]), "buried rock has no sky light");
    }

    @Test
    void missingChildrenAreOpenAir() {
        int[] p = new Downsampler(CLASSES).downsample(new int[8][]);
        for (int c : p) assertEquals(Cell.OPEN_AIR, c);
    }

    @Test
    void tiesGoToTheLowerId() {
        int[] p = down((x, y, z) -> solid((x & 1) == 0 ? SAND : STONE));
        assertEquals(STONE, block(p, 10, 10, 10));
        assertEquals(2, Downsampler.mostCommon(new int[]{5, 2, 5, 2}, 4));
        assertEquals(5, Downsampler.mostCommon(new int[]{5, 2, 5, 9}, 4));
    }

    @Test
    void biomeFollowsTheGroundNotTheAir() {
        int[] p = down((x, y, z) -> y < 21 ? Cell.of(DIRT, 0, 0, 7) : Cell.of(AIR, 15, 0, 3));
        // fine y 20 (dirt, biome 7) and 21 (air, biome 3): four of each, the ground's biome wins
        assertEquals(7, Cell.biome(p[Section.index(4, 10, 4)]));
        assertEquals(3, Cell.biome(p[Section.index(4, 20, 4)]));
    }
}
