package dev.elemore.iroh.diag;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Iroh (perf/frame-attrib): {@code -Diroh.slowFrames=<ms>} explains single slow frames on the render thread.
 * <p>
 * Every frame (Minecraft.runTick) is split into nested phases ({@link Phase}); each phase gets its SELF time (its time
 * minus the phases nested in it), so the phases plus the "unaccounted" rest add up to the frame's wall time. Counters
 * ({@link Counter}) say how much work a phase did (sections accepted, entities, ticks, tasks...). A frame longer than
 * the threshold logs ONE line: frame number, total, the three largest phases, the unaccounted rest, render-thread CPU
 * time vs wall time (wall much larger than CPU with no blocking phase = the thread was descheduled or waited on a lock),
 * bytes allocated on the render thread, the counters, and the slowest single task / mod hook by name. Every 60 s and at
 * quit: slow-frame count and the ranking of "largest phase" over the slow frames (count, mean ms).
 * <p>
 * Records are fixed-size arrays in a ring of {@link #RING} frames: nothing is allocated per frame (the log line of a
 * slow frame is built only for that frame). Off (no property): every call site tests the static-final {@link #ON}.
 * Hot path: two clock reads per phase. Render thread only (calls from other threads are ignored).
 */
public final class SlowFrames {
    public static final long THRESHOLD_NS = parseMs(System.getProperty("iroh.slowFrames"));
    public static final boolean ON = THRESHOLD_NS > 0;

    /** Phases. A phase is charged its self time; nesting follows the calls. */
    public enum Phase {
        PRE_FRAME("pre-frame ops"),        // Renderer.preInitFrame (rest)
        SECTION_INTAKE("section intake"),  // TaskDispatcher.updateSections: built sections accepted + uploaded
        AREA_REALLOC("area buffer grow"),  // AreaBuffer.reallocate: new device buffer + full copy (inside intake)
        STAGING_WAIT("staging full wait"), // StagingBuffer.stage: budget exhausted, waits for an epoch / device idle
        UPLOAD_SUBMIT("upload submit"),    // UploadManager.submitUploads / StagingBuffer.flushConsumers
        TASKS("tasks"),                    // Minecraft.runAllTasks: packets, recordRenderCall, executor queue
        TICK("client tick"),               // Minecraft.tick
        CHUNK_PACKET("chunk packet data"), // ClientChunkCache.replaceWithPacketData (inside tasks)
        IROH_CHUNK("Iroh chunk hooks"),    // Iroh's share of a chunk load / section dirty mark
        LIGHT("client light updates"),     // renderLevel: pollLightUpdates + runLightUpdates (light engine)
        BUFFER_FLUSH("buffer flush"),      // renderLevel: MultiBufferSource endBatch / endLastBatch (entity draws)
        RENDER("render other"),            // GameRenderer.render (self: everything not split out below)
        FENCE("frame fence"),              // vkWaitForFences for the frame slot
        ACQUIRE("acquire"),                // vkAcquireNextImageKHR
        BEGIN("begin frame"),              // Renderer.beginFrame (rest: initFrame, frees, begin pass)
        SETUP_TERRAIN("setup terrain"),    // WorldRenderer.setupRenderer (self)
        GRAPH_UPDATE("visibility graph"),  // SectionGraph.update (+ defer waits)
        SCHEDULE_REBUILDS("schedule rebuilds"), // SectionGraph.scheduleRebuilds: region block copies on the render thread
        TERRAIN_DRAW("terrain draw"),      // WorldRenderer.renderSectionLayer (self)
        SORT("translucent sort"),          // sortTranslucentSections
        ENTITIES("entities"),
        BLOCK_ENTITIES("block entities"),
        PARTICLES("particles"),
        SKY("sky"),
        CLOUDS("clouds"),
        WEATHER("weather"),
        MOD_HOOKS("Iroh frame hooks"),     // IrohFrame stage callbacks
        EMU_DRAWS("GL-shim draws"),        // emulated raw-GL draws (GlPrograms.draw)
        GUI("gui/hud"),
        FLUSH_INFRAME("in-frame uploads"), // VulkanImage.flushInFrame
        SUBMIT("submit"),                  // vkQueueSubmit + bookkeeping (self)
        PRESENT("present"),                // vkQueuePresentKHR
        LIMITER("fps limiter sleep");      // RenderSystem.limitDisplayFPS

        public final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    public enum Counter {
        TICKS("ticks"), TASKS("tasks"), CHUNKS("chunkPackets"), DEFERRED_TASKS("deferredTasks"), SECTIONS("sections"), SECTION_KB("sectionKB"), SORTED("sorted"),
        GRAPH_UPDATES("graphUpd"), GRAPH_FILTER_ONLY("graphFilterOnly"), REBUILDS_QUEUED("dirtyVisible"), REBUILDS_SCHEDULED("rebuildsScheduled"), ENTITIES("entities"), BLOCK_ENTITIES("blockEnt"), LAYERS("layers"),
        HOOKS("hooks"), EMU_DRAWS("emuDraws"), AREA_REALLOCS("areaGrow"), AREA_GROW_KB("areaGrowKB"), STAGING_WAITS("stagingWaits"), BUILDERS_BUSY("buildersBusy"), PENDING_BUILDS("pendingBuilds"), PENDING_UPLOADS("pendingUploads");

        public final String label;

        Counter(String label) {
            this.label = label;
        }
    }

    static final Phase[] PHASES = Phase.values();
    static final Counter[] COUNTERS = Counter.values();
    static final int P = PHASES.length, C = COUNTERS.length;
    public static final int RING = 256;
    private static final int MAX_DEPTH = 32;
    private static final long SUMMARY_EVERY_NS = 60_000_000_000L;

    // ---- seams (tests replace them; the game uses the defaults)
    public static LongSupplier CLOCK = System::nanoTime;
    public static LongSupplier CPU = SlowFrames::threadCpu;
    public static LongSupplier ALLOC = SlowFrames::threadAlloc;
    public static Consumer<String> SINK = s -> org.apache.logging.log4j.LogManager.getLogger("Iroh").info(s);
    /** The thread whose frames are measured (the render thread, set at the first frameStart). */
    static Thread owner;

    // ---- ring of records
    static final long[][] selfNs = new long[RING][P];
    static final int[][] counters = new int[RING][C];
    static final long[] frameNo = new long[RING], wallNs = new long[RING], cpuNs = new long[RING], allocBytes = new long[RING];
    static int head = -1;
    static long frames;

    // ---- current frame
    private static final int[] stackPhase = new int[MAX_DEPTH];
    private static final long[] stackStart = new long[MAX_DEPTH];
    private static final long[] stackChildren = new long[MAX_DEPTH];
    private static int depth;
    private static boolean inFrame;
    private static int runTickDepth;
    private static long frameStart, cpuStart, allocStart;
    private static long[] cur = new long[P];
    private static int[] curC = new int[C];
    private static Object slowestTask, slowestHook;
    private static long slowestTaskNs, slowestHookNs;

    // ---- summary
    static final int[] largestCount = new int[P];
    static final long[] largestNs = new long[P];
    static long slowFrames, summarySlowFrames, lastSummary;

    private SlowFrames() {}

    static long parseMs(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            double ms = Double.parseDouble(s.trim());
            return ms > 0 ? (long) (ms * 1_000_000L) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean mine() {
        return Thread.currentThread() == owner;
    }

    // ================================================================ frame

    /** Minecraft.runTick HEAD. Nested runTick calls (loading-screen recursion) belong to the outer frame. */
    public static void frameStart() {
        if (runTickDepth++ > 0) return;
        if (owner == null) owner = Thread.currentThread();
        if (!mine()) return;
        head = (head + 1) % RING;
        cur = selfNs[head];
        curC = counters[head];
        java.util.Arrays.fill(cur, 0);
        java.util.Arrays.fill(curC, 0);
        slowestTask = slowestHook = null;
        nTypes = 0;
        slowestTaskNs = slowestHookNs = 0;
        depth = 0;
        inFrame = true;
        cpuStart = CPU.getAsLong();
        allocStart = ALLOC.getAsLong();
        frameStart = CLOCK.getAsLong();
        if (lastSummary == 0) lastSummary = frameStart;
    }

    /** Minecraft.runTick RETURN. */
    public static void frameEnd() {
        if (--runTickDepth > 0) return;
        runTickDepth = 0;
        if (!inFrame || !mine()) return;
        long now = CLOCK.getAsLong();
        while (depth > 0) exitTop(now); // phases left open by an exception
        inFrame = false;
        long wall = now - frameStart;
        frames++;
        frameNo[head] = frames;
        wallNs[head] = wall;
        cpuNs[head] = CPU.getAsLong() - cpuStart;
        allocBytes[head] = ALLOC.getAsLong() - allocStart;
        if (wall > threshold()) slowFrame(head);
        if (now - lastSummary >= SUMMARY_EVERY_NS) {
            SINK.accept(summary("60 s"));
            lastSummary = now;
            summarySlowFrames = 0;
        }
    }

    /** Tests: threshold when the property is not set. */
    static long testThresholdNs = Long.MAX_VALUE;

    static long threshold() {
        return THRESHOLD_NS > 0 ? THRESHOLD_NS : testThresholdNs;
    }

    // ================================================================ phases

    public static void enter(Phase p) {
        if (!inFrame || !mine() || depth >= MAX_DEPTH) return;
        stackPhase[depth] = p.ordinal();
        stackChildren[depth] = 0;
        stackStart[depth++] = CLOCK.getAsLong();
    }

    /** Closes {@code p} (and anything opened inside it that was not closed). */
    public static void exit(Phase p) {
        if (!inFrame || !mine() || depth == 0) return;
        int k = depth - 1;
        while (k >= 0 && stackPhase[k] != p.ordinal()) k--;
        if (k < 0) return; // never entered (started before the frame): ignore
        long now = CLOCK.getAsLong();
        while (depth > k) exitTop(now);
    }

    private static void exitTop(long now) {
        int k = --depth;
        long dur = now - stackStart[k];
        cur[stackPhase[k]] += dur - stackChildren[k];
        if (k > 0) stackChildren[k - 1] += dur;
    }

    public static void count(Counter c, int n) {
        if (!inFrame || !mine()) return;
        curC[c.ordinal()] += n;
    }

    /** A counter sampled once (builder state at frame start). */
    public static void set(Counter c, int v) {
        if (!inFrame || !mine()) return;
        curC[c.ordinal()] = v;
    }

    /** One render-thread task finished ({@code ns} long): keeps the slowest of the frame (named only if it is logged). */
    public static void task(Object task, long ns) {
        if (!inFrame || !mine()) return;
        curC[Counter.TASKS.ordinal()]++;
        if (ns > slowestTaskNs) {
            slowestTaskNs = ns;
            slowestTask = task;
        }
        // perf/hitches: time per packet type (the packet a handler lambda captured); no allocation after a type is seen
        Object key = KEY.get(task.getClass()).key(task);
        int i = 0;
        while (i < nTypes && typeKey[i] != key) i++;
        if (i == nTypes) {
            if (nTypes == typeKey.length) return;
            typeKey[nTypes++] = key;
            typeNs[i] = 0;
            typeCount[i] = 0;
        }
        typeNs[i] += ns;
        typeCount[i]++;
    }

    /** Per-frame time by task key (packet type, or task class). */
    private static final Object[] typeKey = new Object[48];
    private static final long[] typeNs = new long[48];
    private static final int[] typeCount = new int[48];
    private static int nTypes;

    /** How to key a task: the captured packet's type if it holds one, else its class. Resolved once per task class. */
    interface Keyer {
        Object key(Object task);
    }

    private static final ClassValue<Keyer> KEY = new ClassValue<>() {
        @Override
        protected Keyer computeValue(Class<?> c) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                } catch (Throwable t) {
                    continue;
                }
                if (isPacketClass(f.getType()) || f.getType().getSimpleName().endsWith("Packet")) {
                    return task -> {
                        try {
                            Object p = f.get(task);
                            return p == null ? c : packetKey(p);
                        } catch (Throwable t) {
                            return c;
                        }
                    };
                }
            }
            return task -> c;
        }
    };

    private static boolean isPacketClass(Class<?> t) {
        try {
            return net.minecraft.network.protocol.Packet.class.isAssignableFrom(t);
        } catch (Throwable e) {
            return false;
        }
    }

    /** The packet's type (a stable object per packet kind), or its class. */
    private static Object packetKey(Object p) {
        try {
            if (p instanceof net.minecraft.network.protocol.Packet<?> pk) return pk.type();
        } catch (Throwable ignored) {
        }
        return p.getClass();
    }

    private static String keyName(Object k) {
        if (k instanceof Class<?> c) {
            String n = c.getSimpleName();
            return n.isEmpty() ? c.getName() : n;
        }
        try {
            if (k instanceof net.minecraft.network.protocol.PacketType<?> t) return t.id().getPath();
        } catch (Throwable ignored) {
        }
        return String.valueOf(k);
    }

    public static void hook(Object hook, long ns) {
        if (!inFrame || !mine()) return;
        curC[Counter.HOOKS.ordinal()]++;
        if (ns > slowestHookNs) {
            slowestHookNs = ns;
            slowestHook = hook;
        }
    }

    public static long now() {
        return CLOCK.getAsLong();
    }

    // ================================================================ reporting

    private static final int[] TOP = new int[3];

    private static void slowFrame(int r) {
        long[] s = selfNs[r];
        long accounted = 0;
        for (long v : s) accounted += v;
        top3(s);
        slowFrames++;
        summarySlowFrames++;
        if (TOP[0] >= 0) {
            largestCount[TOP[0]]++;
            largestNs[TOP[0]] += s[TOP[0]];
        }
        StringBuilder b = new StringBuilder(256).append("[iroh-slow] frame ").append(frameNo[r]).append(' ')
                .append(ms(wallNs[r])).append(" ms:");
        for (int t : TOP) if (t >= 0) b.append(' ').append(PHASES[t].label).append(' ').append(ms(s[t]));
        b.append("; unaccounted ").append(ms(wallNs[r] - accounted))
                .append("; cpu ").append(ms(cpuNs[r])).append(" of wall ").append(ms(wallNs[r]))
                .append("; alloc ").append(allocBytes[r] >> 10).append(" KB;");
        int[] c = counters[r];
        for (int i = 0; i < C; i++) if (c[i] != 0) b.append(' ').append(COUNTERS[i].label).append('=').append(c[i]);
        if (slowestTask != null) b.append("; slowest task ").append(name(slowestTask)).append(' ').append(ms(slowestTaskNs));
        if (nTypes > 0) { // perf/hitches: the three task kinds with the most time
            b.append("; tasks by kind:");
            boolean[] used = new boolean[nTypes];
            for (int k = 0; k < 3; k++) {
                int best = -1;
                for (int i = 0; i < nTypes; i++) if (!used[i] && (best < 0 || typeNs[i] > typeNs[best])) best = i;
                if (best < 0) break;
                used[best] = true;
                b.append(' ').append(keyName(typeKey[best])).append(" x").append(typeCount[best]).append(' ').append(ms(typeNs[best]));
            }
        }
        if (slowestHook != null) b.append("; slowest hook ").append(name(slowestHook)).append(' ').append(ms(slowestHookNs));
        SINK.accept(b.toString());
    }

    private static void top3(long[] s) {
        TOP[0] = TOP[1] = TOP[2] = -1;
        for (int i = 0; i < s.length; i++) {
            if (s[i] <= 0) continue;
            for (int j = 0; j < 3; j++) {
                if (TOP[j] < 0 || s[i] > s[TOP[j]]) {
                    for (int m = 2; m > j; m--) TOP[m] = TOP[m - 1];
                    TOP[j] = i;
                    break;
                }
            }
        }
    }

    /** Summary: slow frames, and the phases that were the largest in them (count, mean ms), most frequent first. */
    public static synchronized String summary(String when) {
        StringBuilder b = new StringBuilder("[iroh-slow] summary (").append(when).append("): ").append(frames)
                .append(" frames, ").append(slowFrames).append(" over ").append(ms(threshold()))
                .append(" ms (").append(summarySlowFrames).append(" since the last summary); largest phase:");
        Integer[] order = new Integer[P];
        for (int i = 0; i < P; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> largestCount[y] != largestCount[x] ? largestCount[y] - largestCount[x] : x - y);
        boolean any = false;
        for (int i : order) {
            if (largestCount[i] == 0) break;
            any = true;
            b.append(' ').append(PHASES[i].label).append(" x").append(largestCount[i]).append(" mean ")
                    .append(ms(largestNs[i] / largestCount[i]));
        }
        if (!any) b.append(" none");
        return b.toString();
    }

    /** At quit. */
    public static void quit() {
        if (frames > 0) SINK.accept(summary("quit"));
    }

    static String ms(long ns) {
        long t = Math.round(ns / 100_000.0); // tenths of a ms
        return (t / 10) + "." + Math.abs(t % 10);
    }

    /** A task / hook name: a packet handler lambda is named by its packet. Slow frames only. */
    static String name(Object o) {
        Class<?> c = o.getClass();
        try {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v != null && v.getClass().getSimpleName().endsWith("Packet")) return v.getClass().getSimpleName() + " (packet)";
                if (v != null && isPacketClass(v.getClass())) return keyName(packetKey(v)) + " (packet)";
            }
        } catch (Throwable ignored) {
        }
        String n = c.getName();
        int lambda = n.indexOf("$$Lambda");
        return lambda > 0 ? n.substring(0, lambda) + " lambda" : n;
    }

    // ================================================================ OS signals

    private static final java.lang.management.ThreadMXBean TMX = ON ? java.lang.management.ManagementFactory.getThreadMXBean() : null;

    private static long threadCpu() {
        try {
            return TMX.getCurrentThreadCpuTime();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long threadAlloc() {
        try {
            return ((com.sun.management.ThreadMXBean) TMX).getCurrentThreadAllocatedBytes();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Tests: clear everything. */
    static void reset() {
        head = -1;
        frames = slowFrames = summarySlowFrames = lastSummary = 0;
        depth = runTickDepth = 0;
        inFrame = false;
        owner = null;
        java.util.Arrays.fill(largestCount, 0);
        java.util.Arrays.fill(largestNs, 0);
    }
}
