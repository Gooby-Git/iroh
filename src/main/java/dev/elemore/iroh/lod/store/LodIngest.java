// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.store;

import dev.elemore.iroh.lod.data.Cell;
import dev.elemore.iroh.lod.data.Section;
import dev.elemore.iroh.lod.data.SectionKey;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java reference of the native chunk ingest and write-back (iroh-native/lod/src/ingest.rs): the fallback where the native
 * library is missing, and the oracle the Java == Rust parity test compares against. Same buffer ({@link ChunkBuffer}),
 * same edits, same stores bytes. Thread-safe through the store's per-section locks.
 */
final class LodIngest {
    static final int MAX_HELD = 384;
    static final long HOLD_MS = 4000;

    private final LodStore store;
    private final Map<SectionKey, Entry> open = new HashMap<>();
    private final Set<Long> chunks = new HashSet<>();

    private static final class Entry {
        final int[] cells;
        final long since;
        boolean dirty;

        Entry(int[] cells, long since) {
            this.cells = cells;
            this.since = since;
        }
    }

    LodIngest(LodStore store) {
        this.store = store;
    }

    private static final class Part {
        int paletteOff = -1, paletteLen, indexOff, skyOff = -1, lightOff = -1, biomeOff = -1;
    }

    private static Part[] parse(ByteBuffer b, int sections, int length) {
        ByteBuffer d = b.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        d.limit(length);
        d.position(0);
        Part[] out = new Part[sections];
        try {
            for (int i = 0; i < sections; i++) {
                Part p = out[i] = new Part();
                int flags = d.get() & 0xFF;
                if ((flags & ChunkBuffer.BLOCKS) != 0) {
                    int n = d.getShort() & 0xFFFF;
                    if (n == 0 || n > 4096) throw new IllegalArgumentException("palette length " + n);
                    p.paletteLen = n;
                    p.paletteOff = d.position();
                    d.position(d.position() + 2 * n);
                    p.indexOff = d.position();
                    d.position(d.position() + 8192);
                }
                if ((flags & ChunkBuffer.SKY) != 0) { p.skyOff = d.position(); d.position(d.position() + 2048); }
                if ((flags & ChunkBuffer.LIGHT) != 0) { p.lightOff = d.position(); d.position(d.position() + 2048); }
                if ((flags & ChunkBuffer.BIOMES) != 0) { p.biomeOff = d.position(); d.position(d.position() + 64); }
            }
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException e) {
            throw new IllegalArgumentException("chunk buffer ends early or is malformed", e);
        }
        return out;
    }

    private static int nibble(ByteBuffer b, int off, int n) {
        return b.get(off + (n >> 1)) >> ((n & 1) << 2) & 15;
    }

    private static boolean allOpenAir(ByteBuffer b, Part p, int openSky) {
        if (p.paletteOff >= 0) return false;
        if (p.lightOff >= 0) for (int i = 0; i < 2048; i++) if (b.get(p.lightOff + i) != 0) return false;
        if (p.skyOff < 0) return openSky == 15;
        for (int i = 0; i < 2048; i++) if (b.get(p.skyOff + i) != (byte) 0xFF) return false;
        return true;
    }

    private static void fill(ByteBuffer b, Part p, int yOff, int[] dst, int qx, int qz, int openSky) {
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int n = y << 8 | z << 4 | x;
                    int s = p.skyOff < 0 ? openSky : nibble(b, p.skyOff, n);
                    int l = p.lightOff < 0 ? 0 : nibble(b, p.lightOff, n);
                    int biome = p.biomeOff < 0 ? 0 : b.get(p.biomeOff + ((y >> 2) << 4 | (z >> 2) << 2 | (x >> 2))) & 0xFF;
                    int block = 0;
                    if (p.paletteOff >= 0) {
                        int i = b.getShort(p.indexOff + 2 * n) & 0xFFFF;
                        block = i < p.paletteLen ? b.getShort(p.paletteOff + 2 * i) & 0xFFFF : 0;
                    }
                    dst[Section.index(qx + x, yOff + y, qz + z)] = Cell.of(block, s, l, biome);
                }
    }

    void ingest(int cx, int cz, boolean hasSky, int sections, ByteBuffer b, int length) {
        ByteBuffer d = b.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        Part[] parts = parse(b, sections, length);
        int openSky = hasSky ? 15 : 0;
        int sx = cx >> 1, sz = cz >> 1;
        int qx = (cx & 1) * 16, qz = (cz & 1) * 16;
        int n = parts.length;
        for (int sy = 0; sy * 2 < n && sy < 32; sy++) {
            int lo = sy * 2, hi = Math.min(lo + 1, n - 1);
            boolean empty = allOpenAir(d, parts[lo], openSky) && (hi == lo || allOpenAir(d, parts[hi], openSky));
            SectionKey key = new SectionKey(0, sx, sy, sz);
            synchronized (store.lockFor(key)) {
                Entry e;
                synchronized (this) { e = open.get(key); }
                if (e == null) {
                    int[] cells = store.get(key);
                    if (cells == null) {
                        if (empty) continue;
                        cells = Section.filled(Cell.of(0, openSky, 0, 0));
                    }
                    e = new Entry(cells, System.currentTimeMillis());
                    synchronized (this) { open.put(key, e); }
                }
                fill(d, parts[lo], 0, e.cells, qx, qz, openSky);
                if (hi != lo) fill(d, parts[hi], 16, e.cells, qx, qz, openSky);
                e.dirty = true;
            }
        }
        synchronized (this) { chunks.add((long) cx << 32 | (cz & 0xFFFFFFFFL)); }
        if (heldCount() > MAX_HELD) flush(false);
    }

    synchronized int heldCount() {
        return open.size();
    }

    synchronized boolean holdsChunk(int cx, int cz) {
        return chunks.contains((long) cx << 32 | (cz & 0xFFFFFFFFL));
    }

    /** @return the level-0 keys whose stored content changed (the store's listeners are told by the caller) */
    List<SectionKey> flush(boolean all) {
        long now = System.currentTimeMillis();
        List<SectionKey> keys = new ArrayList<>();
        synchronized (this) {
            if (all) keys.addAll(open.keySet());
            else {
                List<Map.Entry<SectionKey, Entry>> list = new ArrayList<>(open.entrySet());
                list.sort((x, y) -> Long.compare(x.getValue().since, y.getValue().since));
                int over = open.size() - MAX_HELD;
                for (var en : list) if (now - en.getValue().since >= HOLD_MS || over-- > 0) keys.add(en.getKey());
            }
        }
        List<SectionKey> changed = new ArrayList<>();
        for (SectionKey key : keys) {
            synchronized (store.lockFor(key)) {
                Entry e;
                synchronized (this) { e = open.remove(key); }
                if (e != null && e.dirty && store.put(key, e.cells)) changed.add(key);
            }
        }
        List<long[]> mark = new ArrayList<>();
        synchronized (this) {
            for (Long c : chunks.toArray(Long[]::new)) {
                int cx = (int) (c >> 32), cz = (int) (long) c;
                boolean held = false;
                if (!all) for (SectionKey k : open.keySet()) if (k.x() == cx >> 1 && k.z() == cz >> 1) { held = true; break; }
                if (!held) {
                    chunks.remove(c);
                    mark.add(new long[]{cx, cz});
                }
            }
        }
        for (long[] c : mark) store.markChunk((int) c[0], (int) c[1]);
        return changed;
    }
}
