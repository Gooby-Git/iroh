package dev.elemore.iroh.glshim;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared plumbing for Iroh's GL answers (P1-1b boot path, P1-2 safety net and state shim).
 *
 * <ul>
 *   <li>{@link #noContext()}: true when the calling thread has no real GL context. Iroh never makes one current,
 *       but mods such as DreamDisplays or WaterMedia can create their own GLFW context on a helper thread; their
 *       calls must reach the real driver, so every Java-level shim declines to answer when a context is current
 *       (the native-level safety net in {@link GlNet} only ever sees threads without capabilities).</li>
 *   <li>{@link #note(String, Object)}: a handled call; one DEBUG line per distinct (call, calling mod), INFO with
 *       {@code -Diroh.glshim.log=info}.</li>
 *   <li>{@link #unhandled(String)}: a GL entry point Iroh does not emulate was called on a thread without a GL
 *       context. It returned 0 / GL_FALSE / nothing. Always logged once per (function, mod) at INFO as
 *       {@code Iroh GL shim: <mod> called <fn> (unhandled)} so a Proctor run can list them; counted.</li>
 * </ul>
 */
public final class GlShim {
    public static final Logger LOGGER = LogManager.getLogger("Iroh");
    private static final boolean INFO = "info".equalsIgnoreCase(System.getProperty("iroh.glshim.log"));
    private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> UNHANDLED = new ConcurrentHashMap<>();
    private static final Map<String, String> MOD_OF_CLASS = new ConcurrentHashMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance();

    private GlShim() {}

    public static boolean noContext() {
        try {
            return GLFW.glfwGetCurrentContext() == 0L;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Handled calls, key "call <- mod". */
    public static Map<String, AtomicLong> counts() {
        return COUNTS;
    }

    /** Unhandled calls, key "fn <- mod". */
    public static Map<String, AtomicLong> unhandledCounts() {
        return UNHANDLED;
    }

    /** Records one shimmed call. {@code call} is e.g. "glGetString(0x1f00)"; the first hit per call+mod is logged. */
    public static void note(String call, Object answer) {
        String caller = callerClass();
        String mod = callerMod();
        String key = call + " <- " + mod;
        if (COUNTS.computeIfAbsent(key, k -> new AtomicLong()).getAndIncrement() == 0) {
            if (INFO) LOGGER.info("Iroh GL shim: {} from {} ({}) -> {}", call, mod, caller, answer);
            else LOGGER.debug("Iroh GL shim: {} from {} ({}) -> {}", call, mod, caller, answer);
        }
    }

    /** A GL function with no emulation was called without a GL context (see {@link GlNet}). */
    public static void unhandled(String fn) {
        String mod = callerMod();
        if (UNHANDLED.computeIfAbsent(fn + " <- " + mod, k -> new AtomicLong()).getAndIncrement() == 0)
            LOGGER.info("Iroh GL shim: {} called {} (unhandled) [{}]", mod, fn, callerFrame());
    }

    /** "class.method" of the first non-Iroh frame: a mixin merged into a vanilla class shows its own method name. */
    static String callerFrame() {
        return WALKER.walk(s -> s
                .filter(f -> isForeign(f.getClassName()))
                .map(f -> f.getClassName() + "." + f.getMethodName())
                .findFirst().orElse("?"));
    }

    private static boolean isForeign(String c) {
        return !c.startsWith("dev.elemore.iroh.") && !c.startsWith("org.lwjgl.")
               && !c.startsWith("net.vulkanmod.") && !c.startsWith("com.mojang.blaze3d.")
               && !c.startsWith("jdk.internal.") && !c.startsWith("java.");
    }

    /** A handler threw; the call returned 0. Logged once per function. */
    public static void failed(String fn, Throwable t) {
        if (UNHANDLED.computeIfAbsent(fn + " <- (handler error)", k -> new AtomicLong()).getAndIncrement() == 0)
            LOGGER.warn("Iroh GL shim: handler for {} threw (call ignored, called by {})", fn, callerMod(), t);
    }

    /** One line per unhandled (function, mod) with its count, sorted; for logs and tests. */
    public static String unhandledSummary() {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(UNHANDLED).forEach((k, v) -> sb.append(k).append(" x").append(v.get()).append('\n'));
        return sb.toString();
    }

    /** First frame that is not Iroh, LWJGL, the base, or blaze3d's GL wrappers (GlStateManager calls on behalf of someone). */
    static String callerClass() {
        return WALKER.walk(s -> s
                .map(StackWalker.StackFrame::getClassName)
                .filter(c -> !c.startsWith("dev.elemore.iroh.") && !c.startsWith("org.lwjgl.")
                        && !c.startsWith("net.vulkanmod.") && !c.startsWith("com.mojang.blaze3d.")
                        && !c.startsWith("jdk.internal.") && !c.startsWith("java."))
                .findFirst().orElse("?"));
    }

    /**
     * Mod id owning a class (P1-13a: {@link dev.elemore.iroh.diag.ModAttribution}: mod roots incl. jar-in-jar, nested
     * first; the slice-2 code-source match left every nested mod unmatched and mixin handlers attributed to "minecraft").
     */
    static String modOf(String className) {
        return dev.elemore.iroh.diag.ModAttribution.ofClass(className);
    }

    /** Mod of the first foreign frame, honouring mixin handler names merged into vanilla classes. */
    static String callerMod() {
        return WALKER.walk(s -> s
                .filter(f -> isForeign(f.getClassName()))
                .map(f -> dev.elemore.iroh.diag.ModAttribution.of(f.getClassName(), f.getMethodName()))
                .findFirst().orElse("?"));
    }
}
