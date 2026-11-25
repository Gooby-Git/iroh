// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

import dev.elemore.iroh.lod.store.LodStore;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * One chunk's surface for a map (DESIGN.md section 12): per column, the blocks from the top down to the first solid one
 * (so water keeps its floor and plants their ground), at most {@link #MAX_DEPTH}, plus the surface biome. This is what
 * Xaero's World Map needs and nothing more; a server sends it for areas a client has no level-0 sections for.
 * <p>
 * Encoding: 256 columns in z-major order (index z * 16 + x), each: varint biome, byte count, then count times
 * (varint y above the dimension's minimum, varint block id). Block and biome ids are the store's own.
 */
public final class MapColumns {
    public static final int MAX_DEPTH = 8;

    private MapColumns() {}

    /** Null when the store has none of this chunk. {@code sectionsY} level-0 sections stack from the dimension's min height. */
    public static byte[] of(LodStore store, int cx, int cz, int sectionsY) {
        if (!store.hasChunk(cx, cz)) return null;
        int sx = cx >> 1, sz = cz >> 1, ox = (cx & 1) * 16, oz = (cz & 1) * 16;
        int[][] col = new int[sectionsY][];
        boolean any = false;
        for (int y = 0; y < sectionsY; y++) {
            col[y] = store.get(new SectionKey(0, sx, y, sz));
            any |= col[y] != null;
        }
        if (!any) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        int[] ys = new int[MAX_DEPTH], ids = new int[MAX_DEPTH];
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int n = 0, biome = 0;
                for (int y = sectionsY * 32 - 1; y >= 0 && n < MAX_DEPTH; y--) {
                    int[] cells = col[y >> 5];
                    if (cells == null) continue;
                    int cell = cells[((y & 31) << 10) | ((z + oz) << 5) | (x + ox)];
                    int block = Cell.block(cell);
                    if (block == Cell.AIR) continue;
                    int cls = store.blocks.classOf(block);
                    if (cls == BlockClass.AIR) continue;
                    if (n == 0) biome = Cell.biome(cell);
                    ys[n] = y;
                    ids[n] = block;
                    n++;
                    if (cls == BlockClass.SOLID) break;
                }
                varint(out, biome);
                out.write(n);
                for (int i = 0; i < n; i++) {
                    varint(out, ys[i]);
                    varint(out, ids[i]);
                }
            }
        return out.toByteArray();
    }

    /**
     * A chunk's map columns from level {@code level} of a store whose column there is complete (seed LODs: the finest
     * level generated so far). A level-L cell is 2^L blocks wide, so each of its block columns repeats the cell's; a
     * block's height is the top of its cell. {@code cache} holds sections between neighbouring chunks of one build.
     */
    public static byte[] ofLevel(LodStore store, int cx, int cz, int sectionsY, int level, java.util.Map<SectionKey, int[]> cache) {
        if (level == 0) return ofColumn(store, cx, cz, sectionsY, cache);
        int tall = (sectionsY + (1 << level) - 1) >> level, rows = tall * 32, top = sectionsY * 32 - 1;
        int sx = (cx * 16) >> (5 + level), sz = (cz * 16) >> (5 + level);
        int[][] col = new int[tall][];
        boolean any = false;
        for (int y = 0; y < tall; y++) {
            SectionKey k = new SectionKey(level, sx, y, sz);
            col[y] = cache.computeIfAbsent(k, store::get);
            any |= col[y] != null;
        }
        if (!any) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        int[] ys = new int[MAX_DEPTH], ids = new int[MAX_DEPTH];
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int lx = ((cx * 16 + x) >> level) & 31, lz = ((cz * 16 + z) >> level) & 31;
                int n = 0, biome = 0;
                for (int r = rows - 1; r >= 0 && n < MAX_DEPTH; r--) {
                    int[] cells = col[r >> 5];
                    if (cells == null) continue;
                    int cell = cells[((r & 31) << 10) | (lz << 5) | lx];
                    int block = Cell.block(cell);
                    if (block == Cell.AIR) continue;
                    int cls = store.blocks.classOf(block);
                    if (cls == BlockClass.AIR) continue;
                    if (n == 0) biome = Cell.biome(cell);
                    ys[n] = Math.min(top, (r << level) + (1 << level) - 1);
                    ids[n] = block;
                    n++;
                    if (cls == BlockClass.SOLID) break;
                }
                varint(out, biome);
                out.write(n);
                for (int i = 0; i < n; i++) {
                    varint(out, ys[i]);
                    varint(out, ids[i]);
                }
            }
        return out.toByteArray();
    }

    /** Level 0 without the chunk-ingested check (the caller knows the column is complete). */
    private static byte[] ofColumn(LodStore store, int cx, int cz, int sectionsY, java.util.Map<SectionKey, int[]> cache) {
        int sx = cx >> 1, sz = cz >> 1, ox = (cx & 1) * 16, oz = (cz & 1) * 16;
        int[][] col = new int[sectionsY][];
        boolean any = false;
        for (int y = 0; y < sectionsY; y++) {
            col[y] = cache.computeIfAbsent(new SectionKey(0, sx, y, sz), store::get);
            any |= col[y] != null;
        }
        if (!any) return null;
        return encode(store, col, sectionsY, ox, oz);
    }

    private static byte[] encode(LodStore store, int[][] col, int sectionsY, int ox, int oz) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        int[] ys = new int[MAX_DEPTH], ids = new int[MAX_DEPTH];
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int n = 0, biome = 0;
                for (int y = sectionsY * 32 - 1; y >= 0 && n < MAX_DEPTH; y--) {
                    int[] cells = col[y >> 5];
                    if (cells == null) continue;
                    int cell = cells[((y & 31) << 10) | ((z + oz) << 5) | (x + ox)];
                    int block = Cell.block(cell);
                    if (block == Cell.AIR) continue;
                    int cls = store.blocks.classOf(block);
                    if (cls == BlockClass.AIR) continue;
                    if (n == 0) biome = Cell.biome(cell);
                    ys[n] = y;
                    ids[n] = block;
                    n++;
                    if (cls == BlockClass.SOLID) break;
                }
                varint(out, biome);
                out.write(n);
                for (int i = 0; i < n; i++) {
                    varint(out, ys[i]);
                    varint(out, ids[i]);
                }
            }
        return out.toByteArray();
    }

    /** Calls {@code sink} for every stored block of a chunk's columns, top first, and {@code biomes[z * 16 + x]}. */
    public static void read(byte[] data, int[] biomes, BlockSink sink) {
        ByteBuffer b = ByteBuffer.wrap(data);
        for (int i = 0; i < 256; i++) {
            biomes[i] = varint(b);
            int n = b.get() & 0xFF;
            for (int k = 0; k < n; k++) {
                int y = varint(b), id = varint(b);
                sink.accept(i & 15, y, i >> 4, id, k == 0);
            }
        }
    }

    public interface BlockSink {
        void accept(int x, int y, int z, int block, boolean surface);
    }

    static void varint(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    static int varint(ByteBuffer b) {
        int v = 0, shift = 0, x;
        do {
            x = b.get() & 0xFF;
            v |= (x & 0x7F) << shift;
            shift += 7;
        } while ((x & 0x80) != 0);
        return v;
    }
}
