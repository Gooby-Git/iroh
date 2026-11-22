package dev.elemore.iroh.debug;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Registry of Iroh's runtime switches (keys {@code iroh.<area>.<name>}, the same names as the {@code -D} properties).
 * A switch starts from its system property (or its default), is read at the point of use every frame (never cached in
 * a static final), and may be changed at runtime through {@link dev.elemore.iroh.api.IrohDebug#set}. A switch's
 * change handler decides what a change means: {@code "ok"} (applies within a frame or two), {@code "rebuild"} (applies
 * after a scheduled rebuild the handler triggered), {@code "restart"} (cannot change at runtime: value stored for the
 * next launch only, nothing applied) or an error message. Thread-safe.
 */
public final class DebugSwitches {

    public record Switch(String key, String defaultValue, String help, Function<String, String> validate,
                         Function<String, String> onChange) {}

    private static final Map<String, Switch> SWITCHES = new ConcurrentHashMap<>();
    private static final Map<String, String> VALUES = new ConcurrentHashMap<>();

    private DebugSwitches() {}

    /** Registers a switch; its initial value is {@code System.getProperty(key, default)}. Re-registering keeps the value. */
    public static void register(Switch s) {
        SWITCHES.put(s.key(), s);
        VALUES.computeIfAbsent(s.key(), k -> System.getProperty(k, s.defaultValue()));
    }

    public static String get(String key) {
        if (!SWITCHES.containsKey(key))
            throw new IllegalArgumentException("unknown Iroh switch " + key);
        return VALUES.get(key);
    }

    /** value, or the default when the key was never registered (safe for hot paths that run before registration) */
    public static String getOr(String key, String fallback) {
        String v = VALUES.get(key);
        return v != null ? v : System.getProperty(key, fallback);
    }

    public static boolean on(String key) {
        String v = getOr(key, "false");
        return v.equals("on") || v.equals("true") || v.equals("1");
    }

    /** @return the handler's detail: ok / rebuild / restart / an error (value then unchanged) */
    public static String set(String key, String value) {
        Switch s = SWITCHES.get(key);
        if (s == null)
            throw new IllegalArgumentException("unknown Iroh switch " + key + " (known: " + new TreeMap<>(SWITCHES).keySet() + ")");
        String bad = s.validate() == null ? null : s.validate().apply(value);
        if (bad != null)
            throw new IllegalArgumentException(key + "=" + value + ": " + bad);
        String old = VALUES.put(key, value);
        if (s.onChange() == null || value.equals(old))
            return "ok";
        try {
            return s.onChange().apply(value);
        } catch (RuntimeException e) {
            return "error: " + e;
        }
    }

    public static Map<String, String> snapshot() {
        return new TreeMap<>(VALUES);
    }

    public static Map<String, Switch> switches() {
        return new TreeMap<>(SWITCHES);
    }

    /** validators */
    public static Function<String, String> oneOf(String... allowed) {
        return v -> {
            for (String a : allowed)
                if (a.equals(v))
                    return null;
            return "expected one of " + String.join("|", allowed);
        };
    }

    public static final Function<String, String> BOOL = oneOf("on", "off", "true", "false");
}
