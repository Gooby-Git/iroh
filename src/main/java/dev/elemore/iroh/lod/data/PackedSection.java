// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The packed form of a section: a local palette of cell words plus a bit-packed index per cell.
 * These exact bytes are stored on disk and sent over the wire (both wrapped in Deflate, or LZ4 in older stores) and uploaded to VRAM unwrapped,
 * so the GPU unpacks a cell with one 32-bit load and a shift. Little-endian throughout.
 *
 * <pre>
 *   u8  version (1)
 *   u8  bits per index: 0, 1, 2, 4, 8 or 16 (always divides 32, so no index straddles a word)
 *   u16 reserved (0)
 *   u32 palette size n
 *   u32 palette[n]            cell words, in order of first appearance
 *   u32 words[32768 * bits / 32]
 * </pre>
 */
public final class PackedSection {
    public static final int VERSION = 1;
    public static final int HEADER = 8;

    private PackedSection() {}

    public static int bitsFor(int paletteSize) {
        if (paletteSize <= 1) return 0;
        if (paletteSize <= 2) return 1;
        if (paletteSize <= 4) return 2;
        if (paletteSize <= 16) return 4;
        if (paletteSize <= 256) return 8;
        return 16;
    }

    public static byte[] encode(int[] cells) {
        if (cells.length != Section.VOLUME) throw new IllegalArgumentException("section needs " + Section.VOLUME + " cells");
        IntIndex palette = new IntIndex();
        short[] idx = new short[Section.VOLUME];
        for (int i = 0; i < Section.VOLUME; i++) idx[i] = (short) palette.indexOf(cells[i]);
        int n = palette.size();
        int bits = bitsFor(n);
        int words = Section.VOLUME * bits / 32;
        ByteBuffer out = ByteBuffer.allocate(HEADER + 4 * n + 4 * words).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) VERSION).put((byte) bits).putShort((short) 0).putInt(n);
        for (int i = 0; i < n; i++) out.putInt(palette.valueAt(i));
        if (bits > 0) {
            int perWord = 32 / bits;
            for (int w = 0; w < words; w++) {
                int word = 0, base = w * perWord;
                for (int k = 0; k < perWord; k++) word |= (idx[base + k] & 0xFFFF) << (k * bits);
                out.putInt(word);
            }
        }
        return out.array();
    }

    /** A section where every cell is {@code cell}: 12 bytes. */
    public static byte[] uniform(int cell) {
        return ByteBuffer.allocate(HEADER + 4).order(ByteOrder.LITTLE_ENDIAN).put((byte) VERSION).put((byte) 0).putShort((short) 0)
                .putInt(1).putInt(cell).array();
    }

    public static int[] decode(byte[] data) {
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int version = in.get() & 0xFF;
        if (version != VERSION) throw new IllegalArgumentException("unknown packed section version " + version);
        int bits = in.get() & 0xFF;
        in.getShort();
        int n = in.getInt();
        if (n < 1 || n > Section.VOLUME || bits != bitsFor(n)) throw new IllegalArgumentException("bad palette " + n + "/" + bits);
        int[] palette = new int[n];
        for (int i = 0; i < n; i++) palette[i] = in.getInt();
        int[] cells = new int[Section.VOLUME];
        if (bits == 0) {
            java.util.Arrays.fill(cells, palette[0]);
            return cells;
        }
        int perWord = 32 / bits, mask = (1 << bits) - 1, words = Section.VOLUME * bits / 32;
        for (int w = 0; w < words; w++) {
            int word = in.getInt(), base = w * perWord;
            for (int k = 0; k < perWord; k++) {
                int p = word >>> (k * bits) & mask;
                if (p >= n) throw new IllegalArgumentException("palette index " + p + " out of " + n);
                cells[base + k] = palette[p];
            }
        }
        return cells;
    }

    /** Open-addressing int to dense-index map, in insertion order; cheaper than boxing 32768 Integers. */
    static final class IntIndex {
        private int[] keys = new int[64];
        private int[] slots = new int[64];
        private int[] values = new int[16];
        private int size;

        int indexOf(int key) {
            int mask = keys.length - 1;
            int h = mix(key) & mask;
            while (true) {
                int s = slots[h];
                if (s == 0) break;
                if (keys[h] == key) return s - 1;
                h = (h + 1) & mask;
            }
            if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2);
            values[size] = key;
            keys[h] = key;
            slots[h] = ++size;
            if (size * 2 > keys.length) grow();
            return size - 1;
        }

        int size() { return size; }
        int valueAt(int i) { return values[i]; }

        private void grow() {
            int[] oldKeys = keys, oldSlots = slots;
            keys = new int[oldKeys.length * 2];
            slots = new int[oldKeys.length * 2];
            int mask = keys.length - 1;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldSlots[i] == 0) continue;
                int h = mix(oldKeys[i]) & mask;
                while (slots[h] != 0) h = (h + 1) & mask;
                keys[h] = oldKeys[i];
                slots[h] = oldSlots[i];
            }
        }

        private static int mix(int x) {
            x *= 0x9E3779B1;
            return x ^ (x >>> 15);
        }
    }
}
