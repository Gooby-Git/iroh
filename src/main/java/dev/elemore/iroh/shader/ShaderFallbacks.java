package dev.elemore.iroh.shader;

import net.vulkanmod.Initializer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shaders that could not be converted and draw nothing instead (P1-2a). Each one is logged once per
 * session as {@code Iroh: shader fallback <id> (<pack>): [<stage>] <message>}.
 * <p>
 * Proctor reads {@link #fallbackShaders()} (static, any thread) to check that a run had none, or only the
 * expected ones.
 */
public final class ShaderFallbacks {
    private static final Map<String, String> FALLBACKS = Collections.synchronizedMap(new LinkedHashMap<>());

    private ShaderFallbacks() {}

    /** Record and log (first time only) a shader that fell back. */
    public static void record(String id, String pack, String stage, String message) {
        String line = "Iroh: shader fallback " + id + " (" + pack + "): [" + stage + "] " + message;
        if (FALLBACKS.putIfAbsent(id, line) == null)
            Initializer.LOGGER.error(line);
    }

    /** @return ids of every shader that fell back this session, in first-seen order (e.g. {@code charta:card}, {@code program/minecraft:blit}) */
    public static List<String> fallbackShaders() {
        synchronized (FALLBACKS) {
            return new ArrayList<>(FALLBACKS.keySet());
        }
    }

    /** @return the full log line of every fallback, same order as {@link #fallbackShaders()} */
    public static List<String> fallbackLines() {
        synchronized (FALLBACKS) {
            return new ArrayList<>(FALLBACKS.values());
        }
    }
}
