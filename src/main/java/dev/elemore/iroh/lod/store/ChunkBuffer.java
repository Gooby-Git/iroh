// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.store;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The one-chunk hand-over buffer of {@link LodStore#ingestChunk}: what Java copies out of a loaded chunk so the rest
 * (cells, hidden-cell normalisation, hashing, Deflate, write) can run in Rust. Layout, little endian, one block per
 * 16-tall chunk section from the dimension's bottom (same text as iroh-native/lod/src/ingest.rs):
 * <pre>
 *   u8  flags        1 blocks, 2 sky light, 4 block light, 8 biomes
 *   [1] u16 palette length n (1..4096), u16 palette[n] (LOD block ids), u16 index[4096] (y&lt;&lt;8 | z&lt;&lt;4 | x)
 *   [2] u8 sky[2048]      nibbles, cell n in byte n/2, low nibble first
 *   [4] u8 light[2048]    same
 *   [8] u8 biome[64]      4x4x4 quarter cells, index (y&gt;&gt;2)&lt;&lt;4 | (z&gt;&gt;2)&lt;&lt;2 | (x&gt;&gt;2)
 * </pre>
 * A missing blocks part means air; missing sky means open sky when the dimension has a sky, else dark; missing block
 * light means 0; missing biomes means biome 0.
 */
public final class ChunkBuffer {
    public static final int BLOCKS = 1, SKY = 2, LIGHT = 4, BIOMES = 8;
    /** Largest section: flags + palette header + a 4096-entry palette + indices + both nibble arrays + biomes. */
    public static final int MAX_SECTION = 1 + 2 + 2 * 4096 + 8192 + 2048 + 2048 + 64;

    private final ByteBuffer buf;
    private int sections;

    /** A direct buffer big enough for {@code maxSections} full sections (reuse one per worker thread). */
    public ChunkBuffer(int maxSections) {
        this.buf = ByteBuffer.allocateDirect(maxSections * MAX_SECTION).order(ByteOrder.LITTLE_ENDIAN);
    }

    public ChunkBuffer clear() {
        buf.clear();
        sections = 0;
        return this;
    }

    /**
     * Appends one chunk section.
     * @param palette  LOD block ids, or null for an air-only section without a blocks part
     * @param indices  4096 palette indices (y&lt;&lt;8 | z&lt;&lt;4 | x), required with {@code palette}
     * @param sky      2048 nibble bytes or null; {@code light} the same; {@code biomes} 64 ids or null
     */
    public ChunkBuffer addSection(int[] palette, short[] indices, byte[] sky, byte[] light, byte[] biomes) {
        int flags = (palette != null ? BLOCKS : 0) | (sky != null ? SKY : 0) | (light != null ? LIGHT : 0) | (biomes != null ? BIOMES : 0);
        buf.put((byte) flags);
        if (palette != null) {
            if (palette.length == 0 || palette.length > 4096 || indices.length != 4096) throw new IllegalArgumentException("bad palette section");
            buf.putShort((short) palette.length);
            for (int id : palette) buf.putShort((short) id);
            for (short i : indices) buf.putShort(i);
        }
        if (sky != null) buf.put(sky, 0, 2048);
        if (light != null) buf.put(light, 0, 2048);
        if (biomes != null) buf.put(biomes, 0, 64);
        sections++;
        return this;
    }

    public ByteBuffer buffer() {
        return buf;
    }

    public int length() {
        return buf.position();
    }

    public int sections() {
        return sections;
    }
}
