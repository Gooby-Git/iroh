package dev.elemore.iroh.diag;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Iroh (P1-13a): who drives the EMULATION layer, i.e. VulkanMod's overlay of vanilla's raw-state API (RenderSystem,
 * GlStateManager, BufferUploader, VertexBuffer overwrites). These calls never reach GlNet, so GlShim cannot see them.
 * {@code -Diroh.emuStats=true}: every call is counted per api method; the caller is attributed by a stack walk for the
 * first {@value #EXACT} calls of each api method and then for 1 in {@value #EVERY} (those count x{@value #EVERY}), so the
 * per-mod numbers are estimates after the first {@value #EXACT}. A table goes to the log every 10 s and at exit:
 * {@code Iroh emulation: <count> <api> <- <mod> [via <mod>]}. "via" names the first non-vanilla frame when the
 * direct caller is vanilla code (GuiGraphics, LevelRenderer ...) calling on a mod's behalf: that is class A use.
 * Acceptance for the owner rule: no row whose DIRECT caller is an owner mod. Off: one static boolean test per call.
 */
public final class EmuStats {
    public static final boolean ON = Boolean.getBoolean("iroh.emuStats");
    static final int EXACT = 64, EVERY = 256;

    private static final Logger LOGGER = LogManager.getLogger("Iroh");
    private static final Map<String, AtomicLong> CALLS = new ConcurrentHashMap<>();
    private static final Map<String, LongAdder> BY_MOD = new ConcurrentHashMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance();
    private static volatile long lastPrint;

    static {
        if (ON) Runtime.getRuntime().addShutdownHook(new Thread(() -> print("at exit"), "Iroh emuStats exit"));
    }

    private EmuStats() {
    }

    /** One call of an emulated api method. Call as {@code if (EmuStats.ON) EmuStats.hit("GlStateManager._enableBlend");}. */
    public static void hit(String api) {
        long n = CALLS.computeIfAbsent(api, k -> new AtomicLong()).incrementAndGet();
        if (n <= EXACT || n % EVERY == 0) {
            String who = caller();
            BY_MOD.computeIfAbsent(api + " <- " + who, k -> new LongAdder()).add(n <= EXACT ? 1 : EVERY);
        }
        long now = System.nanoTime();
        if (now - lastPrint > 10_000_000_000L) {
            lastPrint = now;
            print("10 s");
        }
    }

    /** "mod" or "minecraft via mod" for the first frame outside Iroh, blaze3d and the JDK. */
    static String caller() {
        return WALKER.walk(s -> {
            String direct = null, via = null;
            for (var it = s.iterator(); it.hasNext(); ) {
                StackWalker.StackFrame f = it.next();
                String c = f.getClassName();
                if (c.startsWith("dev.elemore.iroh.") || c.startsWith("net.vulkanmod.") || c.startsWith("com.mojang.blaze3d.")
                        || c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("org.lwjgl.")) continue;
                String mod = ModAttribution.of(c, f.getMethodName());
                if (direct == null) {
                    direct = mod;
                    if (!mod.equals("minecraft")) break;
                } else if (!mod.equals("minecraft")) {
                    via = mod;
                    break;
                }
            }
            if (direct == null) return "?";
            return via == null ? direct : direct + " via " + via;
        });
    }

    /** The table, most calls first. */
    public static synchronized String table() {
        TreeMap<Long, StringBuilder> rows = new TreeMap<>(java.util.Comparator.reverseOrder());
        BY_MOD.forEach((k, v) -> rows.computeIfAbsent(v.sum(), x -> new StringBuilder()).append("Iroh emulation: ").append(v.sum()).append(' ').append(k).append('\n'));
        StringBuilder sb = new StringBuilder();
        rows.values().forEach(sb::append);
        return sb.toString();
    }

    private static void print(String when) {
        try {
            LOGGER.info("Iroh emulation table ({}; {} api methods called):\n{}", when, CALLS.size(), table());
        } catch (Throwable ignored) {
        }
    }

    /** Tests. */
    static void reset() {
        CALLS.clear();
        BY_MOD.clear();
    }
}
