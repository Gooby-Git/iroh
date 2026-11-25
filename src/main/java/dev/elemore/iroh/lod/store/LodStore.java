// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.store;

import dev.elemore.iroh.lod.data.BlockClass;
import dev.elemore.iroh.lod.data.IdTable;
import dev.elemore.iroh.lod.data.SectionKey;
import dev.elemore.iroh.lod.nat.LodNative;
import dev.elemore.iroh.lod.nat.LodNativeLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * All LOD sections of one dimension: {@code <root>/L<level>/<rx>.<rz>.vpk}, plus the dimension's block and biome
 * tables. Thread-safe; open pack files are kept in a small LRU.
 */
public final class LodStore implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger("Iroh");
    private static final int OPEN_FILES = 48;
    public static final int BIOME_OVERFLOW = 255;

    private final Path root;
    public final IdTable blocks;
    public final IdTable biomes;
    private final LinkedHashMap<Long, PackFile> open = new LinkedHashMap<>(64, 0.75f, true);
    private final Object[] sectionLocks = new Object[1024];
    /** Told about every section whose stored content changed (any thread). The client uses it to reload what it shows. */
    public final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<SectionKey>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Handle of the native store (docs/RUST_PLAN.md R2), 0 when the Java pack files are used. Same files and format
     * either way; the native one compresses with libdeflate and runs the cascade without crossing into Java per section.
     */
    private final long nat;

    public LodStore(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
        this.blocks = new IdTable(root.resolve("blocks.dat"), 65536, 65535);
        this.biomes = new IdTable(root.resolve("biomes.dat"), BIOME_OVERFLOW, BIOME_OVERFLOW);
        if (blocks.size() == 0) blocks.idOf("minecraft:air", BlockClass.AIR); // id 0 is always air
        for (int i = 0; i < sectionLocks.length; i++) sectionLocks[i] = new Object();
        long h = 0;
        if (LodNativeLoader.load() && !"false".equals(System.getProperty("iroh.lod.nativeStore"))) {
            h = LodNative.storeOpen(root.toAbsolutePath().toString());
            long hh = h;
            blocks.mirrorClasses((id, cls) -> LodNative.storeSetClass(hh, id, cls), (bytes, n) -> LodNative.storeSetClasses(hh, bytes, n));
        }
        this.nat = h;
    }

    /** Whether this store runs on the native backend. */
    public boolean isNative() {
        return nat != 0;
    }

    @FunctionalInterface
    private interface NatOp<T> {
        T run() throws IOException;
    }

    private static <T> T nat(NatOp<T> op) {
        try {
            return op.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Lock for a read-modify-write of one section. Four chunks share a level-0 section, and two workers filling
     * different quadrants of it at once would otherwise each write back the other's stale quadrant.
     */
    public Object lockFor(SectionKey k) {
        int h = k.hashCode();
        return sectionLocks[(h ^ h >>> 16) & (sectionLocks.length - 1)];
    }

    private volatile Thread upgrader;
    private volatile boolean closed;

    /**
     * Rewrites this store's version-1 pack files as version 2 (DESIGN.md section 4), one at a time on one minimum-priority
     * thread, starting 20 s after the call. Each file keeps working while it is rewritten. Safe to call more than once.
     */
    public synchronized void startUpgrade() {
        if (upgrader != null || closed) return;
        upgrader = new Thread(() -> {
            try {
                if (nat != 0) LodNative.threadPriority(2); // idle; best effort, the Java fallback just runs at normal priority
                Thread.sleep(20_000);
                int done = 0;
                long before = 0, after = 0;
                for (int level = 0; level <= dev.elemore.iroh.lod.data.Section.MAX_LEVEL && !closed; level++)
                    for (int[] r : regions(level)) {
                        if (closed) break;
                        Path p = fileFor(root, level, r[0], r[1]);
                        long size = Files.size(p);
                        int lv = level;
                        boolean up = nat != 0
                                ? nat(() -> !closed && LodNative.storeVersion(nat, lv, r[0], r[1]) == 1 && LodNative.storeUpgrade(nat, lv, r[0], r[1]))
                                : with(level, r[0], r[1], false, false, f -> !closed && f.version() < 2 && f.upgrade(blocks));
                        if (up) {
                            done++;
                            before += size;
                            after += Files.size(p);
                        }
                    }
                if (done > 0) LOG.info("Distant Terrain: rewrote {} LOD files of {} in the smaller format: {} MB -> {} MB",
                        done, root, before >> 20, after >> 20);
            } catch (InterruptedException ignored) {
            } catch (RuntimeException | IOException e) {
                if (!closed) LOG.warn("Distant Terrain: LOD file upgrade stopped; it carries on next start", e);
            }
        }, "iroh-lod-upgrade");
        upgrader.setDaemon(true);
        upgrader.start();
    }

    /** True once {@link #close()} has run: background work stops. */
    public boolean closed() {
        return closed;
    }

    public Path root() {
        return root;
    }

    public static Path fileFor(Path root, int level, int rx, int rz) {
        return root.resolve("L" + level).resolve(rx + "." + rz + ".vpk");
    }

    private PackFile file(int level, int rx, int rz, boolean create) throws IOException {
        long id = (long) level << 56 | (rx & 0xFFFFFFFL) << 28 | rz & 0xFFFFFFFL;
        synchronized (open) {
            PackFile f = open.get(id);
            if (f != null) return f;
            Path path = fileFor(root, level, rx, rz);
            if (!create && !Files.exists(path)) return null;
            f = new PackFile(path, level, rx, rz);
            open.put(id, f);
            if (open.size() > OPEN_FILES) {
                var it = open.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Long, PackFile> eldest = it.next();
                    if (eldest.getValue().upgrading()) continue; // being rewritten in the background: not closed under it
                    it.remove();
                    eldest.getValue().flush();
                    eldest.getValue().close();
                    break;
                }
            }
            return f;
        }
    }

    @FunctionalInterface
    private interface FileOp<T> {
        T apply(PackFile f) throws IOException;
    }

    /**
     * Runs an operation on a pack file. The LRU may close a file another thread just got hold of;
     * that thread then sees a closed channel and simply retries on a freshly opened copy.
     */
    private <T> T with(int level, int rx, int rz, boolean create, T absent, FileOp<T> op) {
        for (int attempt = 0; ; attempt++) {
            try {
                PackFile f = file(level, rx, rz, create);
                return f == null ? absent : op.apply(f);
            } catch (java.nio.channels.ClosedChannelException e) {
                if (attempt >= 3) throw new UncheckedIOException(e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static int entry(SectionKey k) {
        return PackFile.entry(k.x() & 15, k.y(), k.z() & 15);
    }

    public static boolean inRange(SectionKey k) {
        return k.y() >= 0 && k.y() < PackFile.HEIGHT;
    }

    /** Cells of a section, or {@code null} when it was never seen. */
    public int[] get(SectionKey k) {
        if (!inRange(k)) return null;
        if (nat != 0) return nat(() -> {
            int[] out = new int[dev.elemore.iroh.lod.data.Section.VOLUME];
            return LodNative.storeGet(nat, k.pack(), out) ? out : null;
        });
        return with(k.level(), k.x() >> 4, k.z() >> 4, false, null, f -> f.read(entry(k)));
    }

    public byte[] getPacked(SectionKey k) {
        if (!inRange(k)) return null;
        if (nat != 0) return nat(() -> LodNative.storeGetPacked(nat, k.pack(), false));
        return with(k.level(), k.x() >> 4, k.z() >> 4, false, null, f -> f.readPackedEntry(entry(k)));
    }

    public long hash(SectionKey k) {
        if (!inRange(k)) return 0;
        if (nat != 0) return nat(() -> LodNative.storeHash(nat, k.pack()));
        return with(k.level(), k.x() >> 4, k.z() >> 4, false, 0L, f -> f.hash(entry(k)));
    }

    /** @return true when the stored content changed */
    public boolean put(SectionKey k, int[] cells) {
        if (!inRange(k)) return false;
        litCache.remove(k.pack());
        boolean changed = nat != 0 ? nat(() -> LodNative.storePut(nat, k.pack(), cells))
                : with(k.level(), k.x() >> 4, k.z() >> 4, true, false, f -> f.write(entry(k), cells, blocks));
        if (changed) for (var l : listeners) l.accept(k);
        return changed;
    }

    /**
     * Packed bytes of a section, ready for the GPU; a uniform section comes back as a one-entry packed section.
     * {@code null} when it was never seen.
     */
    public byte[] getPackedOrUniform(SectionKey k) {
        if (!inRange(k)) return null;
        if (nat != 0) return nat(() -> LodNative.storeGetPacked(nat, k.pack(), true));
        return with(k.level(), k.x() >> 4, k.z() >> 4, false, null, f -> {
            int e = entry(k);
            Integer u = f.uniformValue(e);
            if (u != null) return dev.elemore.iroh.lod.data.PackedSection.uniform(u);
            return f.readPackedEntry(e);
        });
    }

    public PackFile.Stored getStored(SectionKey k) {
        if (!inRange(k)) return null;
        if (nat != 0) return nat(() -> {
            byte[] b = LodNative.storeGetStored(nat, k.pack());
            if (b == null) return null;
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            long h = bb.getLong(1);
            if (b[0] == 1) return new PackFile.Stored(h, true, bb.getInt(9), null);
            return new PackFile.Stored(h, false, 0, java.util.Arrays.copyOfRange(b, 9, b.length));
        });
        return with(k.level(), k.x() >> 4, k.z() >> 4, false, null, f -> f.readStored(entry(k)));
    }

    /** @return true when the stored content changed */
    public boolean putStored(SectionKey k, PackFile.Stored st) {
        if (!inRange(k)) return false;
        litCache.remove(k.pack());
        boolean changed = nat != 0 ? nat(() -> LodNative.storePutStored(nat, k.pack(), st.hash(), st.uniform(), st.cell(), st.payload()))
                : with(k.level(), k.x() >> 4, k.z() >> 4, true, false, f -> f.writeStored(entry(k), st));
        if (changed) for (var l : listeners) l.accept(k);
        return changed;
    }

    /** Sky-lit flags by packed key (cleared on writes, capped): reading them costs a payload read. */
    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> litCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int LIT_CACHE_MAX = 600_000;

    /**
     * Whether some cell of the section has sky light, i.e. part of it is open to the sky: the world's top layer (ground,
     * water, plants, cave mouths). Buried rock and underground biomes (Alex's Caves) have none. False when never stored.
     */
    public boolean skyLit(SectionKey k) {
        if (!inRange(k)) return false;
        long key = k.pack();
        Boolean c = litCache.get(key);
        if (c != null) return c;
        boolean lit;
        if (nat != 0) lit = nat(() -> LodNative.storeSkyLit(nat, key)) == 1;
        else {
            Integer u = uniformValue(k);
            if (u != null) lit = dev.elemore.iroh.lod.data.Cell.sky(u) > 0;
            else {
                byte[] b = getPacked(k);
                lit = b != null && paletteSkyLit(b);
            }
        }
        if (litCache.size() > LIT_CACHE_MAX) litCache.clear();
        litCache.put(key, lit);
        return lit;
    }

    private static boolean paletteSkyLit(byte[] b) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int n = bb.getInt(4);
        for (int i = 0; i < n; i++) if (dev.elemore.iroh.lod.data.Cell.sky(bb.getInt(8 + 4 * i)) > 0) return true;
        return false;
    }

    /**
     * The stored sections of one column in loading order, split in two: {@code surface} gets the sky-lit ones top-down,
     * with the ones right under them and beside them (cliff faces), and {@code below} the rest (buried and underground
     * sections), top-down too. Callers load every column's surface before anyone's underground.
     */
    public void surfaceSplit(int level, int x, int z, java.util.function.Consumer<SectionKey> surface, java.util.function.Consumer<SectionKey> below) {
        for (int y = PackFile.HEIGHT - 1; y >= 0; y--) {
            SectionKey k = new SectionKey(level, x, y, z);
            if (!has(k)) continue;
            boolean first = skyLit(k) || skyLit(new SectionKey(level, x, y + 1, z))
                    || skyLit(new SectionKey(level, x + 1, y, z)) || skyLit(new SectionKey(level, x - 1, y, z))
                    || skyLit(new SectionKey(level, x, y, z + 1)) || skyLit(new SectionKey(level, x, y, z - 1));
            (first ? surface : below).accept(k);
        }
    }

    /** Whether the section was ever stored: an in-memory index check, cheap enough to call per candidate. */
    public boolean has(SectionKey k) {
        if (!inRange(k)) return false;
        if (nat != 0) return nat(() -> LodNative.storeHas(nat, k.pack()));
        return with(k.level(), k.x() >> 4, k.z() >> 4, false, false, f -> f.has(entry(k)));
    }

    /** The cell word of a uniform section, else {@code null}. */
    public Integer uniformValue(SectionKey k) {
        if (!inRange(k)) return null;
        if (nat != 0) return nat(() -> {
            long v = LodNative.storeUniform(nat, k.pack());
            return (v & 1L << 32) != 0 ? Integer.valueOf((int) v) : null;
        });
        return with(k.level(), k.x() >> 4, k.z() >> 4, false, null, f -> f.uniformValue(entry(k)));
    }

    /**
     * Packed bytes of a section straight into a direct buffer (the upload slice): bytes {@code [offset, offset+capacity)}
     * of {@code dst}, no heap copy on the native path. Uniform sections come back as a one-entry packed section.
     * @return the length; -1 when never stored; {@code -(needed) - 2} when {@code capacity} is too small (nothing written)
     */
    public int getPackedInto(SectionKey k, java.nio.ByteBuffer dst, int offset, int capacity) {
        if (!inRange(k)) return -1;
        if (nat != 0) return nat(() -> LodNative.storeGetPackedInto(nat, k.pack(), true, dst, offset, capacity));
        byte[] b = getPackedOrUniform(k);
        if (b == null) return -1;
        if (b.length > capacity) return -b.length - 2;
        dst.duplicate().position(offset).put(b);
        return b.length;
    }

    private final LodIngest javaIngest = new LodIngest(this);

    /**
     * One loaded chunk (see {@link ChunkBuffer}) into level-0 sections. The sections are held for a few seconds so the
     * four chunks that share one are compressed once; call {@link #flushHeld} regularly (and with {@code all} on close).
     * Same result on the native and the Java path.
     */
    public void ingestChunk(int cx, int cz, boolean hasSky, ChunkBuffer chunk) {
        if (nat != 0) {
            nat(() -> {
                LodNative.storeIngestChunk(nat, cx, cz, hasSky, chunk.sections(), chunk.buffer(), chunk.length());
                return null;
            });
            return;
        }
        javaIngest.ingest(cx, cz, hasSky, chunk.sections(), chunk.buffer(), chunk.length());
    }

    /** Writes held ingest sections (all, or those past their hold time). @return the level-0 keys whose content changed */
    public List<SectionKey> flushHeld(boolean all) {
        if (nat == 0) return javaIngest.flush(all);
        long[] ks = nat(() -> LodNative.storeFlushHeld(nat, all));
        List<SectionKey> out = new ArrayList<>(ks.length);
        for (long v : ks) {
            SectionKey k = SectionKey.unpack(v);
            litCache.remove(v);
            out.add(k);
            for (var l : listeners) l.accept(k);
        }
        return out;
    }

    /** Level-0 sections held by the ingest write-back. */
    public int heldCount() {
        return nat != 0 ? LodNative.storeHeldCount(nat) : javaIngest.heldCount();
    }

    /** True for a chunk ingested but not yet marked in the store (so {@link #hasChunk} may still say no). */
    public boolean holdsChunk(int cx, int cz) {
        return nat != 0 ? LodNative.storeHoldsChunk(nat, cx, cz) : javaIngest.holdsChunk(cx, cz);
    }

    /** Whether chunk (cx, cz) has been ingested into level 0. One level-0 file covers 32 x 32 chunks. */
    public boolean hasChunk(int cx, int cz) {
        if (nat != 0) return nat(() -> LodNative.storeHasChunk(nat, cx, cz));
        return with(0, cx >> 5, cz >> 5, false, false, f -> f.hasChunk(cx & 31, cz & 31));
    }

    public void markChunk(int cx, int cz) {
        if (nat != 0) {
            nat(() -> {
                LodNative.storeMarkChunk(nat, cx, cz);
                return null;
            });
            return;
        }
        with(0, cx >> 5, cz >> 5, true, null, f -> {
            f.markChunk(cx & 31, cz & 31);
            return null;
        });
    }

    /**
     * Native store only: rebuilds these parents (all one level) from their children in one call and tells the listeners
     * about each one that changed. @return the changed keys, or null when this store is not native
     */
    public List<SectionKey> cascade(List<SectionKey> keys) {
        if (nat == 0) return null;
        long[] packed = new long[keys.size()];
        for (int i = 0; i < packed.length; i++) packed[i] = keys.get(i).pack();
        long[] changed = nat(() -> LodNative.storeCascade(nat, packed));
        List<SectionKey> out = new ArrayList<>(changed.length);
        for (long v : changed) {
            SectionKey k = SectionKey.unpack(v);
            litCache.remove(v);
            out.add(k);
            for (var l : listeners) l.accept(k);
        }
        return out;
    }

    /** Region coordinates {rx, rz} of every pack file at a level. */
    public List<int[]> regions(int level) {
        List<int[]> out = new ArrayList<>();
        Path dir = root.resolve("L" + level);
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(p -> {
                String n = p.getFileName().toString();
                if (!n.endsWith(".vpk")) return;
                String[] xz = n.substring(0, n.length() - 4).split("\\.");
                out.add(new int[]{Integer.parseInt(xz[0]), Integer.parseInt(xz[1])});
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    public void flush() {
        if (nat != 0) {
            nat(() -> {
                LodNative.storeFlush(nat);
                return null;
            });
            return;
        }
        synchronized (open) {
            for (PackFile f : open.values()) {
                try {
                    f.flush();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        try { flushHeld(true); } catch (RuntimeException e) { LOG.warn("Distant Terrain: could not write held sections on close", e); }
        Thread u = upgrader;
        if (u != null) u.interrupt();
        if (nat != 0) LodNative.storeClose(nat);
        synchronized (open) {
            for (PackFile f : open.values()) {
                f.flush();
                f.close();
            }
            open.clear();
        }
        blocks.close();
        biomes.close();
    }
}
