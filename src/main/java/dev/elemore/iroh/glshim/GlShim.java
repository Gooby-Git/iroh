package dev.elemore.iroh.glshim;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.vulkanmod.Initializer;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared plumbing for the context-free GL answers (P1-1b, boot path only; the full shim is P1-2).
 *
 * <ul>
 *   <li>{@link #noContext()}: true when the calling thread has no real GL context. Iroh never makes one current,
 *       but mods such as DreamDisplays or WaterMedia can create their own GLFW context on a helper thread; their
 *       calls must reach the real driver, so every shim declines to answer when a context is current.</li>
 *   <li>{@link #note(String, Object)}: one log line per distinct (call, calling mod) at DEBUG, plus a counter.
 *       Raise to INFO with {@code -Diroh.glshim.log=info} (P1-2 wants the real usage). {@link #counts()} exposes
 *       the counters.</li>
 * </ul>
 */
public final class GlShim {
    private static final boolean INFO = "info".equalsIgnoreCase(System.getProperty("iroh.glshim.log"));
    private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, String> MOD_OF_CLASS = new ConcurrentHashMap<>();

    private GlShim() {}

    public static boolean noContext() {
        try {
            return GLFW.glfwGetCurrentContext() == 0L;
        } catch (Throwable t) {
            return true;
        }
    }

    public static Map<String, AtomicLong> counts() {
        return COUNTS;
    }

    /** Records one shimmed call. {@code call} is e.g. "glGetString(0x1f00)"; the first hit per call+mod is logged. */
    public static void note(String call, Object answer) {
        String caller = StackWalker.getInstance().walk(s -> s
                .map(StackWalker.StackFrame::getClassName)
                .filter(c -> !c.startsWith("dev.elemore.iroh.") && !c.startsWith("org.lwjgl.") && !c.startsWith("net.vulkanmod."))
                .findFirst().orElse("?"));
        String key = call + " <- " + modOf(caller);
        if (COUNTS.computeIfAbsent(key, k -> new AtomicLong()).getAndIncrement() == 0) {
            if (INFO) Initializer.LOGGER.info("Iroh GL shim: {} from {} ({}) -> {}", call, modOf(caller), caller, answer);
            else Initializer.LOGGER.debug("Iroh GL shim: {} from {} ({}) -> {}", call, modOf(caller), caller, answer);
        }
    }

    /** Mod id owning a class, by matching the class's code source against every mod's root paths. "?" if unknown. */
    static String modOf(String className) {
        return MOD_OF_CLASS.computeIfAbsent(className, cn -> {
            try {
                Class<?> c = Class.forName(cn, false, GlShim.class.getClassLoader());
                java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
                if (cs == null) return "?";
                String loc = Path.of(cs.getLocation().toURI()).toString();
                for (ModContainer m : FabricLoader.getInstance().getAllMods())
                    for (Path root : m.getRootPaths())
                        if (root.toUri().toString().contains(loc) || loc.contains(root.toString())) return m.getMetadata().getId();
            } catch (Throwable ignored) {
            }
            return cn.startsWith("net.minecraft.") || cn.startsWith("com.mojang.") ? "minecraft" : "?";
        });
    }
}
