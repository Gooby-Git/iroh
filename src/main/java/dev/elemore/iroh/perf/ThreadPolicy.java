package dev.elemore.iroh.perf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Iroh (perf/hitches): Iroh looks after its own background threads instead of relying on a pack mod's name list.
 * <p>
 * Block 6 (slow-frame tool): client-tick and graph frames ran at 45-60% CPU of wall time (the render thread was waiting
 * for a core), while Iroh's two {@code Builder-N} threads were busy with 300-500 pending builds in 70% of slow frames.
 * elemore-core's ThreadTuner keeps listed background threads off the highest physical core (the render thread's), but
 * its list has Sodium's "Chunk Render", not Iroh's "Builder-". Java thread priorities are no-ops on Linux.
 * <ul>
 *   <li>{@code -Diroh.threads.tune=true}: on (default off since block 7).</li>
 *   <li>Linux: each builder lowers itself with {@code renice -n <iroh.threads.builderNice, default 4>} (raising nice needs
 *       no privilege) and, with 4+ physical cores, stays off the highest-numbered physical core
 *       ({@code taskset -p -c <other cpus>}): the SAME core elemore-core reserves, picked by the same rule, so the two never
 *       disagree. Applied once per thread from a daemon ("iroh-threads"), never from the render thread.</li>
 *   <li>Builder count ({@link #baseBuilders}, {@link #maxBuilders}): base = the configured count, or (physical cores - 2) / 2
 *       (render + server keep a core each, the rest is shared with worldgen), at least 1. With
 *       {@code iroh.threads.adaptive} (default false) up to min(physical - 2, 6) builders run while the backlog is large
 *       ({@link Adaptive}): 6C/12T -> 2 normally, 4 during flight backlogs.</li>
 * </ul>
 */
public final class ThreadPolicy {
    // hitches2: every switch OFF by default (block 7: unproven; the jar's default equals rc14). Enable one at a time.
    public static final boolean TUNE = Boolean.getBoolean("iroh.threads.tune");
    public static final boolean ADAPTIVE = Boolean.getBoolean("iroh.threads.adaptive");
    /** Builder count from physical cores ((P-2)/2) instead of the base's (logical-1)/2 when the config says auto. */
    public static final boolean PHYSICAL_COUNT = Boolean.getBoolean("iroh.threads.physicalCount");
    public static final boolean KEEP_OFF_RENDER_CORE = !"false".equals(System.getProperty("iroh.threads.keepOffRenderCore"));
    public static final int BUILDER_NICE = Integer.getInteger("iroh.threads.builderNice", 4);
    private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux");

    private ThreadPolicy() {}

    // ================================================================ pure rules (tested)

    /** Builders normally running: the configured count, else (physical - 2) / 2, at least 1. */
    public static int baseBuilders(int configured, int physicalCores) {
        if (configured > 0) return configured;
        return Math.max(1, (physicalCores - 2) / 2);
    }

    /** The base's rule (rc14): configured, else (logical cores - 1) / 2. */
    public static int legacyBuilders(int configured, int logicalCores) {
        if (configured > 0) return configured;
        return Math.max((logicalCores - 1) / 2, 1);
    }

    /** Builders allowed while the backlog is large. */
    public static int maxBuilders(int base, int physicalCores, boolean adaptive) {
        if (!adaptive) return base;
        return Math.max(base, Math.min(physicalCores - 2, 6));
    }

    /**
     * Topology from {@code thread_siblings_list} per online cpu: returns {reserved cpus, other cpus} as comma lists, or
     * null when fewer than 4 physical cores (no reservation). Same rule as elemore-core: the highest physical core.
     */
    public static String[] reservation(Map<Integer, String> siblingsByCpu) {
        TreeMap<Integer, TreeSet<Integer>> cores = new TreeMap<>();
        TreeSet<Integer> online = new TreeSet<>(siblingsByCpu.keySet());
        for (var e : siblingsByCpu.entrySet()) {
            TreeSet<Integer> set = parseList(e.getValue());
            if (!set.isEmpty()) cores.put(set.first(), set);
        }
        if (cores.size() < 4) return null;
        TreeSet<Integer> reserved = cores.lastEntry().getValue();
        List<String> res = new ArrayList<>(), other = new ArrayList<>();
        for (int c : online) (reserved.contains(c) ? res : other).add(String.valueOf(c));
        return new String[]{String.join(",", res), String.join(",", other)};
    }

    public static int physicalCores(Map<Integer, String> siblingsByCpu) {
        TreeSet<Integer> firsts = new TreeSet<>();
        for (String s : siblingsByCpu.values()) {
            TreeSet<Integer> set = parseList(s);
            if (!set.isEmpty()) firsts.add(set.first());
        }
        return firsts.size();
    }

    static TreeSet<Integer> parseList(String s) {
        TreeSet<Integer> set = new TreeSet<>();
        for (String part : s.trim().split(",")) {
            if (part.isBlank()) continue;
            String[] r = part.trim().split("-");
            int a = Integer.parseInt(r[0]), b = r.length > 1 ? Integer.parseInt(r[1]) : a;
            for (int i = a; i <= b; i++) set.add(i);
        }
        return set;
    }

    /**
     * Backlog-driven builder limit with hysteresis: up to max after the backlog stayed above {@code HIGH} for 0.5 s,
     * back to base after it stayed below {@code LOW} for 2 s. Render thread, once per frame; no allocation.
     */
    public static final class Adaptive {
        public static final int HIGH = 192, LOW = 32;
        static final long UP_NS = 500_000_000L, DOWN_NS = 2_000_000_000L;
        private final int base, max;
        private int limit;
        private long highSince = -1, lowSince = -1;

        public Adaptive(int base, int max) {
            this.base = base;
            this.max = max;
            this.limit = base;
        }

        public int update(int pending, long nowNs) {
            if (pending > HIGH) {
                lowSince = -1;
                if (highSince < 0) highSince = nowNs;
                if (nowNs - highSince >= UP_NS) limit = max;
            } else if (pending < LOW) {
                highSince = -1;
                if (lowSince < 0) lowSince = nowNs;
                if (nowNs - lowSince >= DOWN_NS) limit = base;
            } else {
                highSince = -1;
                lowSince = -1;
            }
            return limit;
        }

        public int limit() {
            return limit;
        }
    }

    // ================================================================ Linux application

    private static volatile Map<Integer, String> topology;
    private static volatile String otherCpus;
    private static volatile boolean topologyRead;
    private static final LinkedBlockingQueue<String> PENDING = new LinkedBlockingQueue<>();
    private static Thread applier;

    /** Physical cores of this machine (1 if unknown). */
    public static int physicalCores() {
        readTopology();
        Map<Integer, String> t = topology;
        if (t == null || t.isEmpty()) return Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        return Math.max(1, physicalCores(t));
    }

    private static synchronized void readTopology() {
        if (topologyRead) return;
        topologyRead = true;
        if (!LINUX) return;
        try {
            Map<Integer, String> map = new TreeMap<>();
            for (int cpu = 0; cpu < 1024; cpu++) {
                Path dir = Path.of("/sys/devices/system/cpu/cpu" + cpu);
                if (!Files.isDirectory(dir)) break;
                Path sib = dir.resolve("topology/thread_siblings_list");
                if (!Files.exists(sib)) continue;
                map.put(cpu, Files.readString(sib));
            }
            topology = map;
            String[] r = reservation(map);
            otherCpus = r == null ? null : r[1];
        } catch (Exception e) {
            topology = null;
        }
    }

    /** Called by a builder thread when it starts: queues its own thread id for renice / affinity. Never blocks. */
    public static void registerBuilderThread() {
        if (!TUNE || !LINUX) return;
        try {
            // /proc/thread-self -> "<pid>/task/<tid>"
            String link = Files.readSymbolicLink(Path.of("/proc/thread-self")).toString();
            String tid = link.substring(link.lastIndexOf('/') + 1);
            readTopology();
            PENDING.offer(tid);
            startApplier();
        } catch (Exception ignored) {
        }
    }

    private static synchronized void startApplier() {
        if (applier != null) return;
        applier = new Thread(ThreadPolicy::applyLoop, "iroh-threads");
        applier.setDaemon(true);
        applier.start();
    }

    private static boolean logged;

    private static void applyLoop() {
        while (true) {
            String tid;
            try {
                tid = PENDING.take();
            } catch (InterruptedException e) {
                return;
            }
            int nice = run("renice", "-n", String.valueOf(BUILDER_NICE), "-p", tid);
            String cpus = KEEP_OFF_RENDER_CORE ? otherCpus : null;
            int aff = cpus == null ? 0 : run("taskset", "-p", "-c", cpus, tid);
            if (!logged) {
                logged = true;
                net.vulkanmod.Initializer.LOGGER.info("Iroh threads: builders nice {} ({}), {} (physical cores {})", BUILDER_NICE,
                        nice == 0 ? "ok" : "renice exit " + nice,
                        cpus == null ? "no core reservation" : "kept off the render core, CPUs " + cpus + (aff == 0 ? "" : " (taskset exit " + aff + ")"),
                        physicalCores());
            }
        }
    }

    private static int run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            return p.exitValue();
        } catch (Exception e) {
            return -2;
        }
    }
}
