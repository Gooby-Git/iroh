// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.store;

import dev.elemore.iroh.lod.data.Hidden;
import dev.elemore.iroh.lod.data.PackedSection;
import dev.elemore.iroh.lod.data.Section;
import dev.elemore.iroh.lod.data.SectionHash;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;

/**
 * One LOD pack file: 16 x 16 columns of sections at one level, up to 32 sections tall (DESIGN.md section 4).
 *
 * <pre>
 *   bytes 0-4095        header: magic, version, level, region x/z; at byte 64 a 1024-bit chunk-presence map (level 0 only)
 *   bytes 4096-135167   index, 8192 entries of 16 bytes: u32 first sector, u32 length, u64 hash
 *   then                payloads in sectors (version 1: 1 KiB, version 2: 256 bytes): u32 packed length, then the
 *                       packed section, compressed. Bit 31 of the length set: Deflate (version 2 writes); clear: LZ4
 *                       (version 1, and what an older server still sends). Both decode in either file version.
 * </pre>
 * Version 2 (2026-11-04) also stores sections with their hidden cells normalised ({@link Hidden}); hashes are still those
 * of the cells as given. Measured on the user's world: about 40% smaller than LZ4 high compression. A version-1 file is
 * rewritten as version 2 in the background ({@link #upgrade}), keeping every hash, so clients and servers stay in sync.
 * Index entry kinds: length 0 = never seen; length -1 = uniform, the cell word sits in the low half of the hash
 * field (all-air and fully buried sections cost no sectors); otherwise a payload.
 * A payload is written before its index entry, so a crash leaves at worst the old section, never a torn one.
 * Reads hold the lock too: a write may reuse the sectors a reader was about to read.
 */
public final class PackFile implements AutoCloseable {
    public static final int COLUMNS = 16;
    public static final int HEIGHT = 32;
    public static final int ENTRIES = COLUMNS * COLUMNS * HEIGHT;
    static final int HEADER_BYTES = 4096;
    static final int INDEX_BYTES = ENTRIES * 16;
    static final int SECTOR_V1 = 1024, SECTOR_V2 = 256;
    static final int MAGIC = 0x3150_4B56; // "VKP1" little-endian
    static final int VERSION = 2;
    static final int DEFLATED = 0x8000_0000;
    static final int PRESENCE_AT = 64;
    static final int UNIFORM = -1;

    private static final LZ4FastDecompressor UNLZ4 = LZ4Factory.fastestInstance().fastDecompressor();
    /** Deflate level for live writes (0.5 ms a section) and for background upgrades (smaller, ~9x slower). */
    static final int LEVEL_LIVE = 6, LEVEL_UPGRADE = 9;
    private static final ThreadLocal<java.util.zip.Inflater> INFLATER = ThreadLocal.withInitial(java.util.zip.Inflater::new);

    private final Path path;
    private final int level, rx, rz;
    private FileChannel ch;
    private int version, sectorSize, dataStart;
    /** Set while {@link #upgrade} runs: entries written meanwhile, to copy over at the end. */
    private volatile boolean upgrading;
    private final BitSet touched = new BitSet();
    private final int[] sector = new int[ENTRIES];
    private final int[] length = new int[ENTRIES];
    private final long[] hash = new long[ENTRIES];
    private final byte[] presence = new byte[128];
    private final BitSet used = new BitSet();

    public PackFile(Path path, int level, int rx, int rz) throws IOException {
        this.path = path;
        this.level = level;
        this.rx = rx;
        this.rz = rz;
        Files.createDirectories(path.getParent());
        load();
    }

    private void load() throws IOException {
        boolean fresh = !Files.exists(path) || Files.size(path) < HEADER_BYTES + INDEX_BYTES;
        ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        java.util.Arrays.fill(sector, 0);
        java.util.Arrays.fill(length, 0);
        java.util.Arrays.fill(hash, 0);
        java.util.Arrays.fill(presence, (byte) 0);
        used.clear();
        if (fresh) {
            setVersion(VERSION);
            ByteBuffer head = le(HEADER_BYTES);
            head.putInt(MAGIC).putInt(VERSION).putInt(level).putInt(rx).putInt(rz);
            head.clear();
            writeFully(head, 0);
            writeFully(le(INDEX_BYTES), HEADER_BYTES);
        } else {
            ByteBuffer head = le(HEADER_BYTES);
            readFully(head, 0);
            head.flip();
            int magic = head.getInt(), v = head.getInt(), lv = head.getInt(), x = head.getInt(), z = head.getInt();
            if (magic != MAGIC || v < 1 || v > VERSION || lv != level || x != rx || z != rz)
                throw new IOException("not a LOD pack file for L" + level + " " + rx + "," + rz + ": " + path);
            setVersion(v);
            head.position(PRESENCE_AT);
            head.get(presence);
            ByteBuffer index = le(INDEX_BYTES);
            readFully(index, HEADER_BYTES);
            index.flip();
            for (int i = 0; i < ENTRIES; i++) {
                sector[i] = index.getInt();
                length[i] = index.getInt();
                hash[i] = index.getLong();
                if (length[i] > 0) used.set(sector[i], sector[i] + sectorsFor(length[i]));
            }
        }
    }

    private void setVersion(int v) {
        version = v;
        sectorSize = v == 1 ? SECTOR_V1 : SECTOR_V2;
        dataStart = (HEADER_BYTES + INDEX_BYTES) / sectorSize;
    }

    public synchronized int version() {
        return version;
    }

    public boolean upgrading() {
        return upgrading;
    }

    public static int entry(int lx, int ly, int lz) {
        if ((lx | lz) >>> 4 != 0 || ly >>> 5 != 0) throw new IndexOutOfBoundsException(lx + "," + ly + "," + lz);
        return (ly * COLUMNS + lz) * COLUMNS + lx;
    }

    public synchronized boolean has(int entry) {
        return length[entry] != 0;
    }

    /** Content hash, 0 when the section was never seen. */
    public synchronized long hash(int entry) {
        if (length[entry] == UNIFORM) return SectionHash.ofUniform((int) hash[entry]);
        return length[entry] == 0 ? 0 : hash[entry];
    }

    /** The section's cells, or {@code null} when it was never seen. */
    public int[] read(int entry) throws IOException {
        byte[] packed;
        synchronized (this) {
            int len = length[entry];
            if (len == 0) return null;
            if (len == UNIFORM) return Section.filled((int) hash[entry]);
            packed = readPacked(sector[entry], len);
        }
        return PackedSection.decode(packed);
    }

    /** The packed bytes (what the GPU and the network take), or {@code null} when never seen or uniform. */
    public synchronized byte[] readPackedEntry(int entry) throws IOException {
        return length[entry] > 0 ? readPacked(sector[entry], length[entry]) : null;
    }

    private byte[] readPacked(int at, int len) throws IOException {
        ByteBuffer buf = le(len);
        readFully(buf, (long) at * sectorSize);
        return unwrap(buf.array());
    }

    /** A stored payload (u32 length + LZ4 or Deflate) back to the packed section. */
    public static byte[] unwrap(byte[] payload) throws IOException {
        int head = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        int raw = head & ~DEFLATED;
        byte[] packed = new byte[raw];
        if ((head & DEFLATED) == 0) {
            UNLZ4.decompress(payload, 4, packed, 0, raw);
            return packed;
        }
        java.util.zip.Inflater inf = INFLATER.get();
        inf.reset();
        inf.setInput(payload, 4, payload.length - 4);
        try {
            int n = 0;
            while (n < raw) {
                int k = inf.inflate(packed, n, raw - n);
                if (k == 0 && (inf.finished() || inf.needsInput())) break;
                n += k;
            }
            if (n != raw) throw new IOException("LOD payload inflates to " + n + " bytes, not " + raw);
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("bad LOD payload", e);
        }
        return packed;
    }

    /** A packed section as a stored payload: u32 length with bit 31 set, then Deflate. */
    static byte[] wrap(byte[] packed, int deflateLevel) {
        java.util.zip.Deflater d = new java.util.zip.Deflater(deflateLevel);
        try {
            d.setInput(packed);
            d.finish();
            byte[] out = new byte[4 + packed.length + packed.length / 64 + 64];
            int n = 4;
            while (!d.finished()) {
                if (n == out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                n += d.deflate(out, n, out.length - n);
            }
            ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(0, packed.length | DEFLATED);
            return java.util.Arrays.copyOf(out, n);
        } finally {
            d.end();
        }
    }

    /**
     * Stores a section.
     * @return false when the content is unchanged (same hash), so callers can skip the parents
     */
    public boolean write(int entry, int[] cells) throws IOException {
        return write(entry, cells, null);
    }

    /** Stores a section, its hidden cells normalised when {@code classes} is given ({@link Hidden}). */
    public boolean write(int entry, int[] cells, dev.elemore.iroh.lod.data.ClassTable classes) throws IOException {
        Integer uniform = Section.uniformValue(cells);
        long h = uniform != null ? SectionHash.ofUniform(uniform) : SectionHash.of(cells);
        synchronized (this) {
            if (length[entry] != 0 && hash(entry) == h) return false;
            if (uniform != null) {
                release(entry);
                setEntry(entry, 0, UNIFORM, uniform & 0xFFFFFFFFL);
                return true;
            }
        }
        byte[] out = wrap(PackedSection.encode(classes == null ? cells : Hidden.normalize(cells, classes)), LEVEL_LIVE);
        synchronized (this) {
            putPayload(entry, out, h);
        }
        return true;
    }

    private void putPayload(int entry, byte[] payload, long h) throws IOException {
        release(entry);
        int at = allocate(sectorsFor(payload.length));
        writeFully(ByteBuffer.wrap(payload), (long) at * sectorSize);
        setEntry(entry, at, payload.length, h);
    }

    /**
     * Background, once per version-1 file: rewrites it as version 2 (Deflate, 256-byte sectors, hidden cells normalised),
     * keeping every entry's hash. Other threads go on reading and writing this file meanwhile; what they write is copied
     * across at the end, under the lock, before the new file replaces the old one. The store doesn't close a file while
     * this runs. @return true when the file was upgraded
     */
    public boolean upgrade(dev.elemore.iroh.lod.data.ClassTable classes) throws IOException {
        synchronized (this) {
            if (version >= VERSION || upgrading || !ch.isOpen()) return false;
            upgrading = true;
            touched.clear();
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".v2tmp");
        try {
            Files.deleteIfExists(tmp);
            try (PackFile out = new PackFile(tmp, level, rx, rz)) {
                for (int e = 0; e < ENTRIES; e++) {
                    Stored st = readStored(e);
                    if (st == null) continue;
                    if (st.uniform()) out.writeStored(e, st);
                    else {
                        byte[] packed = PackedSection.encode(Hidden.normalize(PackedSection.decode(unwrap(st.payload())), classes));
                        synchronized (out) {
                            out.putPayload(e, wrap(packed, LEVEL_UPGRADE), st.hash());
                        }
                    }
                }
                synchronized (this) {
                    for (int e = touched.nextSetBit(0); e >= 0; e = touched.nextSetBit(e + 1)) {
                        Stored st = readStored(e);
                        if (st != null) out.writeStored(e, st);
                    }
                    out.copyPresence(presence);
                    out.flush();
                    out.close();
                    ch.force(true);
                    ch.close();
                    Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    load();
                    return true;
                }
            }
        } finally {
            synchronized (this) {
                upgrading = false;
                touched.clear();
            }
            Files.deleteIfExists(tmp);
        }
    }

    private synchronized void copyPresence(byte[] from) throws IOException {
        System.arraycopy(from, 0, presence, 0, presence.length);
        writeFully(ByteBuffer.wrap(presence), PRESENCE_AT);
    }

    /** A section as stored, for shipping without decoding: uniform (cell word) or payload (u32 length + LZ4). */
    public record Stored(long hash, boolean uniform, int cell, byte[] payload) {}

    public synchronized Stored readStored(int entry) throws IOException {
        int len = length[entry];
        if (len == 0) return null;
        if (len == UNIFORM) return new Stored(SectionHash.ofUniform((int) hash[entry]), true, (int) hash[entry], null);
        ByteBuffer buf = le(len);
        readFully(buf, (long) sector[entry] * sectorSize);
        return new Stored(hash[entry], false, 0, buf.array());
    }

    /** Stores a section exactly as another store had it. @return false when the content is unchanged */
    public synchronized boolean writeStored(int entry, Stored st) throws IOException {
        if (length[entry] != 0 && hash(entry) == st.hash()) return false;
        release(entry);
        if (st.uniform()) {
            setEntry(entry, 0, UNIFORM, st.cell() & 0xFFFFFFFFL);
            return true;
        }
        putPayload(entry, st.payload(), st.hash());
        return true;
    }

    public synchronized boolean hasChunk(int localChunkX, int localChunkZ) {
        int bit = localChunkZ * 32 + localChunkX;
        return (presence[bit >> 3] & 1 << (bit & 7)) != 0;
    }

    public synchronized void markChunk(int localChunkX, int localChunkZ) throws IOException {
        int bit = localChunkZ * 32 + localChunkX;
        if ((presence[bit >> 3] & 1 << (bit & 7)) != 0) return;
        presence[bit >> 3] |= (byte) (1 << (bit & 7));
        writeFully(ByteBuffer.wrap(presence, bit >> 3, 1), PRESENCE_AT + (bit >> 3));
    }

    /** The cell word of a uniform entry, or {@code null} when the entry is absent or has a payload. */
    public synchronized Integer uniformValue(int entry) {
        return length[entry] == UNIFORM ? (int) hash[entry] : null;
    }

    /** Number of entries that have been seen, for stats. */
    public synchronized int count() {
        int n = 0;
        for (int l : length) if (l != 0) n++;
        return n;
    }

    private void setEntry(int entry, int at, int len, long h) throws IOException {
        if (upgrading) touched.set(entry);
        sector[entry] = at;
        length[entry] = len;
        hash[entry] = h;
        ByteBuffer e = le(16);
        e.putInt(at).putInt(len).putLong(h).flip();
        writeFully(e, HEADER_BYTES + (long) entry * 16);
    }

    private void release(int entry) {
        if (length[entry] > 0) used.clear(sector[entry], sector[entry] + sectorsFor(length[entry]));
    }

    private int allocate(int need) {
        int at = dataStart;
        while (true) {
            at = used.nextClearBit(at);
            int end = used.nextSetBit(at);
            if (end < 0 || end - at >= need) break;
            at = end;
        }
        used.set(at, at + need);
        return at;
    }

    private int sectorsFor(int len) {
        return (len + sectorSize - 1) / sectorSize;
    }

    public void flush() throws IOException {
        ch.force(false);
    }

    @Override
    public synchronized void close() throws IOException {
        ch.close();
    }

    private void writeFully(ByteBuffer buf, long pos) throws IOException {
        while (buf.hasRemaining()) pos += ch.write(buf, pos);
    }

    private void readFully(ByteBuffer buf, long pos) throws IOException {
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) throw new IOException("LOD pack file ends early");
            pos += n;
        }
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
