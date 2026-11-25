package dev.elemore.iroh.perf;

import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.lwjgl.util.vma.Vma.vmaCreateBuffer;
import static org.lwjgl.util.vma.Vma.vmaDestroyBuffer;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Iroh (P2-4, perf/streaming): pooled device buffers for the terrain area buffers (AreaBuffer), so section intake on the
 * render thread does not create device buffers (VMA, sometimes a fresh vkAllocateMemory) while streaming. Switch
 * {@code iroh.area.arena} (runtime, default off; PerfSwitches).
 * <ul>
 *   <li>Sizes come in half-octave classes (256 KiB, 384 KiB, 512 KiB, 768 KiB, 1 MiB, ...): a buffer freed by one area
 *       (grown past it, or the area moved away) fits the next area that needs that class.</li>
 *   <li>A retired buffer comes back to the pool once the upload epoch it was retired in has completed (frames in
 *       flight and the grow copy no longer read it), up to a pool cap; beyond the cap it is freed as before.</li>
 *   <li>A prefetch thread ({@code Iroh-AreaArena}) creates the class just taken and the next class (the grow target)
 *       ahead of need, within a small spare budget and only while the memory guard allows optional allocations. It
 *       only calls VMA (internally synchronized); every pool and MemoryManager bookkeeping step stays on the render
 *       thread.</li>
 *   <li>A pool miss still allocates at once on the render thread (counted: {@code areaAlloc} slow-frame counter).</li>
 *   <li>World unload and memory-guard trims empty the pool; the prefetch thread stops with the world.</li>
 * </ul>
 * Arena buffers carry VERTEX and INDEX usage so one pool serves both kinds of area buffer.
 */
public final class AreaArena {
    private AreaArena() {}

    // ---------------------------------------------------------------- size classes (pure)

    static final long MIN_CLASS = 256L << 10;
    /** classes above this are not pooled (allocated exactly): 1 GiB */
    static final int MAX_CLASS = 2 * 12; // 256 KiB << 12 = 1 GiB

    /** Bytes of class {@code c}: even = 256 KiB << c/2, odd = 384 KiB << c/2. */
    public static long classBytes(int c) {
        return (c & 1) == 0 ? MIN_CLASS << (c >> 1) : (MIN_CLASS + (MIN_CLASS >> 1)) << (c >> 1);
    }

    /** Smallest class holding {@code bytes}; -1 above the largest pooled class. */
    public static int classOf(long bytes) {
        for (int c = 0; c <= MAX_CLASS; c++)
            if (classBytes(c) >= bytes) return c;
        return -1;
    }

    /** Pooled size for a request (the class size), or the request itself when it is too large to pool. */
    public static long sizeFor(long bytes) {
        int c = classOf(bytes);
        return c < 0 ? bytes : classBytes(c);
    }

    // ---------------------------------------------------------------- pool (pure, render thread)

    /** Free buffers per class with a byte cap. Not thread-safe: the render thread owns it. */
    public static final class Pool<T> {
        private final ArrayDeque<T>[] free;
        private long pooledBytes, capBytes;
        public int hits, misses, kept, dropped;

        @SuppressWarnings("unchecked")
        public Pool(long capBytes) {
            this.free = new ArrayDeque[MAX_CLASS + 1];
            for (int i = 0; i < this.free.length; i++) this.free[i] = new ArrayDeque<>();
            this.capBytes = capBytes;
        }

        public T take(int c) {
            T t = c < 0 ? null : this.free[c].pollLast(); // LIFO: the most recently returned (warmest) first
            if (t != null) {
                this.pooledBytes -= classBytes(c);
                this.hits++;
            } else this.misses++;
            return t;
        }

        /** false = over the cap: the caller frees it. */
        public boolean put(int c, T t) {
            if (c < 0 || this.pooledBytes + classBytes(c) > this.capBytes) {
                this.dropped++;
                return false;
            }
            this.free[c].addLast(t);
            this.pooledBytes += classBytes(c);
            this.kept++;
            return true;
        }

        public int count(int c) {
            return c < 0 ? 0 : this.free[c].size();
        }

        public long pooledBytes() {
            return this.pooledBytes;
        }

        public long capBytes() {
            return this.capBytes;
        }

        public void setCap(long capBytes) {
            this.capBytes = capBytes;
        }

        /** Everything pooled, emptied. */
        public List<T> drain() {
            List<T> out = new ArrayList<>();
            for (ArrayDeque<T> q : this.free) {
                out.addAll(q);
                q.clear();
            }
            this.pooledBytes = 0;
            return out;
        }
    }

    // ---------------------------------------------------------------- game glue

    /** Usage of every arena buffer: area vertex and index data, written by transfers, copied on grow. */
    public static final int USAGE = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
    private static final int FULL_USAGE_BASE = USAGE | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

    /** P5-2 (merge 3b into 4c): with the RT device features on, arena buffers also carry device address + AS build input (the BLAS reads the arena in place); 0 extra otherwise */
    private static int rtUsage() {
        return dev.elemore.iroh.rt.game.RtVma.arenaUsage();
    }
    static final boolean PREFETCH = !"false".equals(System.getProperty("iroh.area.arenaPrefetch"));
    static final long POOL_MB = Long.getLong("iroh.area.arenaPoolMB", 256);
    static final long SPARE_MB = Long.getLong("iroh.area.arenaSpareMB", 64);

    /** A pooled area buffer; {@code cls} = its size class. */
    public static final class ArenaBuffer extends Buffer {
        public final int cls;

        ArenaBuffer(int cls, MemoryType type) {
            super(USAGE | rtUsage(), type);
            this.cls = cls;
        }
    }

    private record Raw(int cls, long id, long allocation) {}

    private static Pool<ArenaBuffer> pool;
    private static boolean worldOpen = true;
    /** prefetch requests not yet back (render thread) */
    private static final int[] outstanding = new int[MAX_CLASS + 1];
    private static long outstandingBytes;
    private static final LinkedBlockingQueue<Integer> requests = new LinkedBlockingQueue<>();
    private static final ConcurrentLinkedQueue<Raw> ready = new ConcurrentLinkedQueue<>();
    private static Thread worker;
    private static java.util.concurrent.atomic.AtomicBoolean workerStop;
    /** the thread that owns the pool (set at the first take); trims from other threads are deferred to it */
    private static volatile Thread renderThread;
    private static volatile boolean trimRequested;
    // stats (memStats, slow frames)
    public static int syncAllocs, prefetched, prefetchRefused;

    /** The switch, and a device where the area buffers live in plain device-local memory. One volatile read when off. */
    public static boolean on() {
        return PerfSwitches.areaArenaOn && MemoryTypes.GPU_MEM != null && MemoryTypes.GPU_MEM.getType() == MemoryType.Type.DEVICE_LOCAL
                && !MemoryTypes.GPU_MEM.mappable();
    }

    private static Pool<ArenaBuffer> pool() {
        if (pool == null) pool = new Pool<>(capBytes(POOL_MB, 32));
        return pool;
    }

    /** min(configured MiB, heap / divisor): small cards keep a small pool. */
    static long capBytes(long mb, int divisor) {
        long heap = MemoryTypes.GPU_MEM == null ? 0 : MemoryTypes.GPU_MEM.vkMemoryHeap.size();
        long cap = mb << 20;
        return heap > 0 ? Math.min(cap, heap / divisor) : cap;
    }

    /**
     * Render thread: a buffer of at least {@code minBytes} (its size is {@link #sizeFor}). Pool first; else a
     * synchronous allocation (counted). Asks the prefetch thread for this class and the next one.
     */
    public static Buffer take(long minBytes) {
        renderThread = Thread.currentThread();
        Pool<ArenaBuffer> p = pool();
        drainReady(p);
        int c = classOf(minBytes);
        ArenaBuffer b = p.take(c);
        if (b == null) {
            b = new ArenaBuffer(c, MemoryTypes.GPU_MEM);
            b.createBuffer(c < 0 ? minBytes : classBytes(c)); // MemoryManager.createBuffer: guard + registration
            syncAllocs++;
        }
        if (PREFETCH && worldOpen && c >= 0) {
            request(p, c);
            if (c < MAX_CLASS) request(p, c + 1);
        }
        return b;
    }

    /** Render thread: {@code b} is no longer used by the CPU side; it returns to the pool after the current epoch. */
    public static void recycle(Buffer b) {
        if (!(b instanceof ArenaBuffer ab) || ab.cls < 0 || !worldOpen) {
            b.scheduleFree();
            return;
        }
        MemoryManager.getInstance().addEpochOp(() -> returnToPool(ab));
    }

    /** Epoch op (render thread, inside MemoryManager.initFrame): the GPU no longer reads {@code b}. */
    private static void returnToPool(ArenaBuffer b) {
        if (!worldOpen || !on() || !dev.elemore.iroh.memory.MemoryGuard.allowOptional(0) || !pool().put(b.cls, b))
            b.scheduleFree();
    }

    private static void request(Pool<ArenaBuffer> p, int c) {
        long bytes = classBytes(c);
        if (p.count(c) + outstanding[c] >= 1) return;                          // one spare per wanted class
        if (outstandingBytes + bytes > capBytes(SPARE_MB, 64)) return;           // prefetch budget
        if (p.pooledBytes() + outstandingBytes + bytes > p.capBytes()) return;  // would not fit the pool anyway
        if (!dev.elemore.iroh.memory.MemoryGuard.allowOptional(bytes)) {
            prefetchRefused++;
            return;
        }
        outstanding[c]++;
        outstandingBytes += bytes;
        ensureWorker();
        requests.offer(c);
    }

    /** Render thread: buffers the prefetch thread finished go into the pool (registered with MemoryManager here). */
    private static void drainReady(Pool<ArenaBuffer> p) {
        for (Raw r; (r = ready.poll()) != null; ) {
            // max(0): a result can arrive after worldUnloaded reset the counts (a stop that timed out)
            outstanding[r.cls] = Math.max(0, outstanding[r.cls] - 1);
            outstandingBytes = Math.max(0, outstandingBytes - classBytes(r.cls));
            if (r.id == VK_NULL_HANDLE) continue; // refused / failed: nothing to keep
            ArenaBuffer b = new ArenaBuffer(r.cls, MemoryTypes.GPU_MEM);
            MemoryManager.getInstance().adoptBuffer(b, r.id, r.allocation, classBytes(r.cls), true);
            // review a64133f #4: after a trim / under memory pressure a late prefetch is not kept
            if (!worldOpen || !dev.elemore.iroh.memory.MemoryGuard.allowOptional(0) || !p.put(r.cls, b)) b.scheduleFree();
            else prefetched++;
        }
    }

    /** Render thread, once per frame from MemoryGuard.tick: collect finished prefetches. */
    public static void frameTick() {
        if (pool == null) return;
        if (trimRequested) {
            trimRequested = false;
            trim();
        }
        if (!ready.isEmpty()) drainReady(pool);
    }

    private static void ensureWorker() {
        if (worker != null && worker.isAlive()) return;
        var stop = new java.util.concurrent.atomic.AtomicBoolean(); // per thread: a stop that timed out never revives it
        workerStop = stop;
        worker = new Thread(() -> workerLoop(stop), "Iroh-AreaArena");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static void workerLoop(java.util.concurrent.atomic.AtomicBoolean stop) {
        while (!stop.get()) {
            int c;
            try {
                c = requests.take();
            } catch (InterruptedException e) {
                break;
            }
            // review a64133f #2: no prefetch once stopping or once the memory guard refuses optional memory
            if (stop.get() || !dev.elemore.iroh.memory.MemoryGuard.allowOptional(classBytes(c))) {
                ready.add(new Raw(c, VK_NULL_HANDLE, 0));
                if (stop.get()) break;
                continue;
            }
            ready.add(allocateRaw(c));
        }
    }

    /**
     * Prefetch thread: one buffer of class {@code c} straight through VMA (no MemoryManager state, no guard escalation:
     * a failure only means "no spare"). Same create info as MemoryManager.createBuffer.
     */
    private static Raw allocateRaw(int c) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(classBytes(c)).usage(FULL_USAGE_BASE | rtUsage());
            var families = net.vulkanmod.vulkan.queue.Queue.getQueueFamilies();
            if (families.transferFamily != families.graphicsFamily) {
                info.sharingMode(VK_SHARING_MODE_CONCURRENT);
                info.pQueueFamilyIndices(stack.ints(families.graphicsFamily, families.transferFamily));
            }
            VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack).requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.pointers(VK_NULL_HANDLE);
            int r = vmaCreateBuffer(net.vulkanmod.vulkan.Vulkan.getAllocator(), info, alloc, pBuffer, pAlloc, null);
            if (r != VK_SUCCESS) return new Raw(c, VK_NULL_HANDLE, 0);
            return new Raw(c, pBuffer.get(0), pAlloc.get(0));
        } catch (Throwable t) {
            return new Raw(c, VK_NULL_HANDLE, 0);
        }
    }

    private static void stopWorker() {
        Thread w = worker;
        if (w == null) return;
        workerStop.set(true);
        w.interrupt();
        try {
            w.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (w.isAlive()) // inside vmaCreateBuffer for > 5 s: it exits after that call (own stop flag); its result is freed by a later drain
            net.vulkanmod.Initializer.LOGGER.warn("Iroh: area arena prefetch thread did not stop within 5 s");
        worker = null;
        requests.clear();
    }

    /** World loaded (render thread). */
    public static void worldStarted() {
        worldOpen = true;
        if (pool != null) pool.setCap(capBytes(POOL_MB, 32));
    }

    /** World unloaded (render thread, after the area buffers were released): pool and prefetches freed, thread stopped. */
    public static void worldUnloaded() {
        worldOpen = false;
        stopWorker();
        destroyReady();
        java.util.Arrays.fill(outstanding, 0);
        outstandingBytes = 0;
        trim();
    }

    /** Memory guard trim / world unload: every pooled buffer is freed (epoch gated, as any buffer). */
    public static void trim() {
        if (pool == null) return;
        Thread rt = renderThread;
        if (rt != null && Thread.currentThread() != rt) { // review a64133f #1: the pool belongs to the render thread
            trimRequested = true;
            return;
        }
        if (!ready.isEmpty()) drainReady(pool);
        for (ArenaBuffer b : pool.drain()) b.scheduleFree();
    }

    /** Prefetched buffers never handed to MemoryManager (thread stopped): destroyed directly, the GPU never saw them. */
    private static void destroyReady() {
        for (Raw r; (r = ready.poll()) != null; )
            if (r.id != VK_NULL_HANDLE) vmaDestroyBuffer(net.vulkanmod.vulkan.Vulkan.getAllocator(), r.id, r.allocation);
    }

    public static String statsLine() {
        Pool<ArenaBuffer> p = pool;
        return "area arena " + (on() ? "on" : "off") + ": pooled " + (p == null ? 0 : p.pooledBytes() >> 20) + " / cap "
                + (p == null ? 0 : p.capBytes() >> 20) + " MiB, hits " + (p == null ? 0 : p.hits) + ", misses " + (p == null ? 0 : p.misses)
                + ", sync allocs " + syncAllocs + ", prefetched " + prefetched + " (refused " + prefetchRefused + "), kept "
                + (p == null ? 0 : p.kept) + ", dropped " + (p == null ? 0 : p.dropped);
    }
}
