package net.vulkanmod.render.chunk.buffer;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongRBTreeSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * P2-7a (L5a, switch {@code iroh.terrain.mdi}, default off): the WORLD terrain arena. Every section's vertex data of a
 * render type lives in a few large device buffers ("pages") of that layer's pool instead of one buffer per chunk area,
 * so a layer draws with one vertex-buffer bind and one multi-draw-indirect per page (TerrainCommandWriter) instead of
 * a bind + push constant + UBO bind + MDI per area.
 *
 * <p>Pages never grow and are never copied: a full pool opens a new page (each new page twice the previous one, up to
 * the pool's maximum; an upload larger than that gets a page of its own). An emptied page is retired once the
 * deferred frees ran (no frame in flight reads it), except the pool's last spare. So there is no whole-arena grow copy
 * and no transient double footprint (SYSTEM SAFETY: GPU memory), and GPU memory goes back as the player moves.
 *
 * <p>Allocator per page: free blocks in a size-ordered tree (best fit, O(log n)) plus start/end maps for coalescing;
 * a per-area AreaBuffer walks its whole segment list per upload, which a world-sized page could not afford. Pages are
 * tried lowest index first, so live data drifts to low pages and high pages empty out.
 *
 * <p>Threading: render thread only (uploads, frees, the frame's command writing). Frees are deferred through
 * {@link Ops#later} (frames in flight may still read the old range), as AreaBuffer's segment frees are.
 * Offline: WorldArenaTest drives it with a fake {@link Ops}; TerrainWorldReplay with real lavapipe buffers.
 */
public final class WorldArena {
    /** device side, so the allocator runs offline */
    public interface Ops {
        /** a device-local buffer of {@code bytes} with VERTEX (index=false) or INDEX usage */
        Object allocate(boolean index, long bytes);

        long id(Object buffer);

        /** record the copy of {@code src} (position..limit) to {@code dst} at {@code dstOffset} */
        void upload(Object dst, long dstOffset, ByteBuffer src);

        /** {@code buffer} is no longer referenced by new work; frames in flight may still read it */
        void retire(Object buffer);

        /** run {@code r} once the frames in flight recorded so far are done (MemoryManager frame op in game) */
        void later(Runnable r);
    }

    /** "no page" in the page-id arrays DrawBuffers keeps (byte) */
    public static final byte NONE = -1;
    /** at most this many pages per pool (page ids are bytes; one value is NONE) */
    public static final int MAX_PAGES = 120;

    /** P2-7a: the arena of the current section grid (set by WorldRenderer.allChanged; null = per-area path). Render thread. */
    private static volatile WorldArena current;

    public static WorldArena current() {
        return current;
    }

    public static void setCurrent(WorldArena a) {
        current = a;
    }

    private final Ops ops;
    final Pool[] vertex;
    final Pool index;
    private boolean dead;

    /** frees of a range that is not allocated (must stay 0; tests and memStats) */
    public long badFrees;

    /**
     * @param layers     render type count (TerrainRenderType.VALUES.length in game)
     * @param vertexSize terrain vertex size in bytes (alignment of vertex allocations)
     * @param firstPage  first page size per vertex layer in bytes; later pages double up to {@code maxPage}
     */
    public WorldArena(Ops ops, int layers, int vertexSize, long[] firstPage, long[] maxPage, long firstIndexPage, long maxIndexPage) {
        this.ops = ops;
        this.vertex = new Pool[layers];
        for (int i = 0; i < layers; i++)
            this.vertex[i] = new Pool(this, false, vertexSize, firstPage[i], maxPage[i]);
        this.index = new Pool(this, true, 2, firstIndexPage, maxIndexPage);
    }

    /**
     * The game's page sizes: SOLID, CUTOUT_MIPPED, CUTOUT, TRANSLUCENT, TRIPWIRE (TerrainRenderType order). With the
     * default unique opaque layer, solid terrain is meshed into CUTOUT_MIPPED (most of the world: about 1.5 GiB at RD32,
     * so 128 MiB pages = about 12 draws). Blended layers draw in exact order and start a new draw at every page change,
     * so their first page is large enough for normal worlds. For comparison the per-area path reserves 0.96-4 MiB per
     * area and layer up front (DrawBuffers.getAreaBufferOrAlloc), about 300 areas at RD32.
     * {@code -Diroh.terrain.mdi.pageScale=n} multiplies every size (tests, very high render distances).
     */
    public static WorldArena forGame(Ops ops, int layers, int vertexSize) {
        long mb = 1L << 20;
        long scale = Math.max(1, Long.getLong("iroh.terrain.mdi.pageScale", 1));
        long[] first = new long[layers], max = new long[layers];
        for (int i = 0; i < layers; i++) {
            long f = switch (i) { case 0, 1 -> 16 * mb; case 3 -> 32 * mb; case 4 -> mb; default -> 8 * mb; };
            long m = switch (i) { case 0, 1 -> 128 * mb; case 3 -> 64 * mb; case 4 -> 8 * mb; default -> 64 * mb; };
            first[i] = f * scale;
            max[i] = m * scale;
        }
        return new WorldArena(ops, layers, vertexSize, first, max, 8 * mb * scale, 32 * mb * scale);
    }

    public Pool vertexPool(int layer) {
        return this.vertex[layer];
    }

    public Pool indexPool() {
        return this.index;
    }

    public boolean isDead() {
        return this.dead;
    }

    /**
     * World unload / grid rebuild: every page is retired (frames in flight finish with them first); later frees are
     * no-ops. DrawBuffers of the old grid only reset their parameters after this.
     */
    public void retireAll() {
        if (this.dead) return;
        this.dead = true;
        for (Pool p : this.vertex) p.retireAll();
        this.index.retireAll();
    }

    /** upload {@code src} into {@code pool}; returns {@link #pack}(page, byte offset) */
    public long upload(Pool pool, ByteBuffer src) {
        return upload(pool, src, NONE);
    }

    /**
     * Same, trying {@code preferredPage} first (DrawBuffers passes the page its area used last for this layer): an
     * area's sections then share a page, so a blended layer, drawn area by area, changes page at most at area borders.
     */
    public long upload(Pool pool, ByteBuffer src, int preferredPage) {
        long a = pool.alloc(src.remaining(), preferredPage);
        this.ops.upload(pool.pages[page(a)].buffer, offset(a), src);
        return a;
    }

    /** free the range at (page, byte offset) once the frames in flight are done with it */
    public void freeLater(Pool pool, int page, int byteOffset) {
        if (this.dead || page < 0) return;
        this.ops.later(() -> {
            if (!this.dead) pool.free(page, byteOffset);
        });
    }

    public static long pack(int page, int offset) {
        return ((long) page << 32) | (offset & 0xFFFFFFFFL);
    }

    public static int page(long packed) {
        return (int) (packed >>> 32);
    }

    public static int offset(long packed) {
        return (int) packed;
    }

    public long bufferId(Pool pool, int page) {
        Page p = pool.pages[page];
        return p == null ? 0 : this.ops.id(p.buffer);
    }

    public Object buffer(Pool pool, int page) {
        Page p = pool.pages[page];
        return p == null ? null : p.buffer;
    }

    /** memStats / F3: pages, capacity and used MiB of every pool */
    public String stats() {
        long cap = this.index.capacity(), used = this.index.used();
        int pages = this.index.pageCount();
        for (Pool p : this.vertex) {
            cap += p.capacity();
            used += p.used();
            pages += p.pageCount();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("terrain mdi arena: ").append(pages).append(" pages, ").append(used >> 20).append('/').append(cap >> 20).append(" MiB");
        if (this.badFrees != 0) sb.append(", BAD FREES ").append(this.badFrees);
        return sb.toString();
    }

    /** one layer's pages (vertex data of one render type, or the translucent sorted indices) */
    public static final class Pool {
        final WorldArena arena;
        final boolean index;
        final int align;
        final long firstPage, maxPage;
        final Page[] pages = new Page[MAX_PAGES];
        int top; // pages[0..top) may be non-null
        long nextPageSize;
        /** stats */
        public long pagesCreated, pagesRetired, allocs, frees;

        Pool(WorldArena arena, boolean index, int align, long firstPage, long maxPage) {
            this.arena = arena;
            this.index = index;
            this.align = align;
            this.firstPage = roundUp(firstPage, align);
            this.maxPage = Math.max(this.firstPage, roundUp(maxPage, align));
            this.nextPageSize = this.firstPage;
        }

        static long roundUp(long v, int a) {
            return (v + a - 1) / a * a;
        }

        /**
         * A page with one free block of at least {@code bytes}: {@code preferred} if it has one, else the lowest such page,
         * else a new page. DrawBuffers asks with a section layer's TOTAL size, then allocates each facing with that page
         * preferred: best fit leaves the large block alone or takes from it, so every facing fits there and the section
         * layer stays in one page (a draw-run change at most between sections, never inside one).
         */
        public int pageFor(int bytes, int preferred) {
            if (preferred >= 0 && preferred < this.top && this.pages[preferred] != null && this.pages[preferred].largestFree() >= bytes)
                return preferred;
            for (int i = 0; i < this.top; i++)
                if (this.pages[i] != null && this.pages[i].largestFree() >= bytes) return i;
            return newPage(bytes);
        }

        /** best fit in the preferred page, else the lowest page that has room, else a new page; returns pack(page, byte offset) */
        long alloc(int bytes, int preferred) {
            // a size-0 upload owns nothing: DrawBuffers keeps NONE for it instead of allocating
            if (bytes <= 0 || bytes % this.align != 0)
                throw new IllegalArgumentException("bad allocation size " + bytes + " (element " + this.align + ")");
            this.allocs++;
            if (preferred >= 0 && preferred < this.top && this.pages[preferred] != null && this.pages[preferred].largestFree() >= bytes) {
                int off = this.pages[preferred].take(bytes);
                if (off >= 0) return pack(preferred, off);
            }
            for (int i = 0; i < this.top; i++) {
                Page p = this.pages[i];
                if (p == null || p.largestFree() < bytes) continue;
                int off = p.take(bytes);
                if (off >= 0) return pack(i, off);
            }
            int slot = newPage(bytes);
            int off = this.pages[slot].take(bytes);
            if (off < 0) throw new IllegalStateException("fresh page cannot hold " + bytes);
            return pack(slot, off);
        }

        private int newPage(int bytes) {
            int slot = -1;
            for (int i = 0; i < MAX_PAGES; i++)
                if (this.pages[i] == null) { slot = i; break; }
            if (slot < 0) throw new IllegalStateException("terrain arena: more than " + MAX_PAGES + " pages in one pool");
            long size = Math.max(this.nextPageSize, roundUp(Math.max(bytes, 1), this.align));
            if (size > Integer.MAX_VALUE) throw new IllegalStateException("terrain arena page too large: " + size);
            // near the slot limit pages get larger (never expected: 120 x 64 MiB per layer)
            if (slot >= MAX_PAGES / 2) size = Math.min(Integer.MAX_VALUE / this.align * this.align, size * 4);
            Object buffer = this.arena.ops.allocate(this.index, size);
            this.pages[slot] = new Page(buffer, (int) size);
            this.top = Math.max(this.top, slot + 1);
            if (size <= this.maxPage) this.nextPageSize = Math.min(this.maxPage, size * 2);
            this.pagesCreated++;
            return slot;
        }

        /** immediate free (the caller deferred it); an emptied page is retired unless it is the pool's last spare */
        void free(int page, int byteOffset) {
            Page p = page >= 0 && page < this.top ? this.pages[page] : null;
            if (p == null) {
                this.arena.badFrees++;
                return;
            }
            this.frees++;
            if (!p.give(byteOffset)) {
                this.arena.badFrees++;
                return;
            }
            if (p.used == 0 && emptyPages() > 1) {
                this.arena.ops.retire(p.buffer);
                this.pages[page] = null;
                while (this.top > 0 && this.pages[this.top - 1] == null) this.top--;
                this.pagesRetired++;
            }
        }

        private int emptyPages() {
            int n = 0;
            for (int i = 0; i < this.top; i++)
                if (this.pages[i] != null && this.pages[i].used == 0) n++;
            return n;
        }

        void retireAll() {
            for (int i = 0; i < this.top; i++)
                if (this.pages[i] != null) {
                    this.arena.ops.retire(this.pages[i].buffer);
                    this.pages[i] = null;
                }
            this.top = 0;
        }

        public int pageCount() {
            int n = 0;
            for (int i = 0; i < this.top; i++) if (this.pages[i] != null) n++;
            return n;
        }

        public long capacity() {
            long c = 0;
            for (int i = 0; i < this.top; i++) if (this.pages[i] != null) c += this.pages[i].capacity;
            return c;
        }

        public long used() {
            long c = 0;
            for (int i = 0; i < this.top; i++) if (this.pages[i] != null) c += this.pages[i].used;
            return c;
        }

        /** tests: allocator invariants of every page (empty list = consistent) */
        public List<String> errors() {
            List<String> e = new ArrayList<>();
            for (int i = 0; i < this.top; i++)
                if (this.pages[i] != null) for (String s : this.pages[i].errors()) e.add("page " + i + ": " + s);
            return e;
        }

        public int top() {
            return this.top;
        }

        public boolean hasPage(int i) {
            return i >= 0 && i < this.top && this.pages[i] != null;
        }
    }

    /** one device buffer with a best-fit free list */
    static final class Page {
        final Object buffer;
        final int capacity;
        int used;
        /** free blocks keyed (size << 32 | offset): ascending = best fit first, lowest offset on ties */
        private final LongRBTreeSet freeBySize = new LongRBTreeSet();
        private final Int2IntOpenHashMap freeStart = new Int2IntOpenHashMap(); // offset -> size
        private final Int2IntOpenHashMap freeEnd = new Int2IntOpenHashMap();   // end -> offset
        private final Int2IntOpenHashMap usedSize = new Int2IntOpenHashMap();  // offset -> size

        Page(Object buffer, int capacity) {
            this.buffer = buffer;
            this.capacity = capacity;
            this.freeStart.defaultReturnValue(-1);
            this.freeEnd.defaultReturnValue(-1);
            this.usedSize.defaultReturnValue(-1);
            addFree(0, capacity);
        }

        int largestFree() {
            return this.freeBySize.isEmpty() ? 0 : (int) (this.freeBySize.lastLong() >>> 32);
        }

        /** best fit; -1 when no block is large enough */
        int take(int bytes) {
            LongSortedSet tail = this.freeBySize.tailSet((long) bytes << 32);
            if (tail.isEmpty()) return -1;
            long key = tail.firstLong();
            int size = (int) (key >>> 32), off = (int) key;
            removeFree(off, size);
            if (size > bytes) addFree(off + bytes, size - bytes);
            this.usedSize.put(off, bytes);
            this.used += bytes;
            return off;
        }

        boolean give(int offset) {
            int size = this.usedSize.remove(offset);
            if (size < 0) return false;
            this.used -= size;
            int start = offset, end = offset + size;
            int prev = this.freeEnd.get(start);
            if (prev >= 0) {
                int prevSize = start - prev;
                removeFree(prev, prevSize);
                start = prev;
            }
            int nextSize = this.freeStart.get(end);
            if (nextSize >= 0) {
                removeFree(end, nextSize);
                end += nextSize;
            }
            addFree(start, end - start);
            return true;
        }

        private void addFree(int off, int size) {
            this.freeBySize.add(((long) size << 32) | (off & 0xFFFFFFFFL));
            this.freeStart.put(off, size);
            this.freeEnd.put(off + size, off);
        }

        private void removeFree(int off, int size) {
            this.freeBySize.remove(((long) size << 32) | (off & 0xFFFFFFFFL));
            this.freeStart.remove(off);
            this.freeEnd.remove(off + size);
        }

        List<String> errors() {
            List<String> e = new ArrayList<>();
            // every byte is in exactly one used or free block; no two free blocks touch
            int n = this.freeStart.size() + this.usedSize.size();
            int[] offs = new int[n], sizes = new int[n];
            boolean[] free = new boolean[n];
            int k = 0;
            for (var en : this.freeStart.int2IntEntrySet()) { offs[k] = en.getIntKey(); sizes[k] = en.getIntValue(); free[k++] = true; }
            for (var en : this.usedSize.int2IntEntrySet()) { offs[k] = en.getIntKey(); sizes[k] = en.getIntValue(); free[k++] = false; }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Integer.compare(offs[a], offs[b]));
            int pos = 0, usedSum = 0;
            boolean prevFree = false;
            for (int i = 0; i < n; i++) {
                int j = order[i];
                if (offs[j] != pos) e.add("gap or overlap at " + pos + " (next block at " + offs[j] + ")");
                if (free[j] && prevFree) e.add("two free blocks touch at " + offs[j]);
                if (!free[j]) usedSum += sizes[j];
                prevFree = free[j];
                pos = offs[j] + sizes[j];
            }
            if (pos != this.capacity) e.add("blocks end at " + pos + ", capacity " + this.capacity);
            if (usedSum != this.used) e.add("used " + this.used + " but used blocks hold " + usedSum);
            if (this.freeBySize.size() != this.freeStart.size() || this.freeEnd.size() != this.freeStart.size())
                e.add("free indexes disagree: bySize " + this.freeBySize.size() + " start " + this.freeStart.size() + " end " + this.freeEnd.size());
            return e;
        }
    }
}
