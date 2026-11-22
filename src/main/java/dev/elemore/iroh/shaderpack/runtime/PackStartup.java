package dev.elemore.iroh.shaderpack.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Startup-only pack decisions (P3-6). FULL mode needs the 36-byte section vertex format from the first section
 * meshed, so it is read ONCE from the JVM properties ({@code -Diroh.pack.enabled=true -Diroh.pack.mode=full});
 * IrohDebug answers "restart" for a runtime change. Without both properties nothing here changes anything: the
 * format stays the 16-byte compressed one and the mesher never touches the pack classes.
 */
public final class PackStartup {

    public static final boolean FULL = isOn(System.getProperty(PackSwitches.ENABLED)) && "full".equals(System.getProperty(PackSwitches.MODE));

    private PackStartup() {}

    static boolean isOn(String v) {
        return "true".equals(v) || "on".equals(v) || "1".equals(v);
    }

    /**
     * mc_Entity.x per block for the mesher: filled when the pack has loaded (PackRuntimeImpl), -1 before that
     * (sections meshed before the load are rebuilt once the ids exist). Keyed by registry id string.
     */
    public static volatile Map<String, Integer> blockIds = Map.of();
    private static final Map<Object, Integer> CACHE = new ConcurrentHashMap<>();
    private static volatile Map<String, Integer> cacheFor = Map.of();

    /** block id for a Block object (cached; {@code name} supplies the registry id on a miss) */
    public static int blockId(Object block, java.util.function.Supplier<String> name) {
        Map<String, Integer> ids = blockIds;
        if (ids.isEmpty())
            return -1;
        if (cacheFor != ids) {
            CACHE.clear();
            cacheFor = ids;
        }
        Integer v = CACHE.get(block);
        if (v == null) {
            v = ids.getOrDefault(name.get(), -1);
            CACHE.put(block, v);
        }
        return v;
    }
}
