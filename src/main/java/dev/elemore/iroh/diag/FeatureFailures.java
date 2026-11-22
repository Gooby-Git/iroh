package dev.elemore.iroh.diag;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * INT-2 first-launch safety net: every optional feature's init failure goes through {@link #record}, which logs ONE
 * line per feature (the first failure; the caller then runs its fallback) and remembers the reason for the startup
 * self-check and the settings rows. {@link #inject} is the test-only seam: {@code -Diroh.test.failInit=<feature>[,..]}
 * (or {@code *}) makes that feature's init throw, so every degrade path can be proven offline and, if ever needed, in a
 * guarded run. Feature names: {@code api4}, {@code fsr}, {@code velocity}, {@code renderScale}, {@code pack},
 * {@code deviceCaps}, {@code startupReport}. No property = nothing injected (one empty-set test per init, never per frame).
 */
public final class FeatureFailures {
    public static final String PROPERTY = "iroh.test.failInit";

    private static volatile Set<String> inject = parse(System.getProperty(PROPERTY, ""));
    private static final Map<String, String> FAILED = new ConcurrentHashMap<>();

    private FeatureFailures() {}

    static Set<String> parse(String csv) {
        Set<String> s = new java.util.HashSet<>();
        for (String p : csv.split(","))
            if (!p.isBlank()) s.add(p.trim());
        return Set.copyOf(s);
    }

    /** test-only: throws when {@code feature} is listed in -Diroh.test.failInit. Call at the start of an init. */
    public static void inject(String feature) {
        Set<String> s = inject;
        if (!s.isEmpty() && (s.contains(feature) || s.contains("*")))
            throw new IllegalStateException(PROPERTY + "=" + feature + " (test-only forced init failure)");
    }

    /**
     * Logs the failure of {@code feature} once (first failure only) with what happens instead, and keeps the reason.
     * @return the reason text
     */
    public static String record(String feature, Throwable t, String fallback) {
        String reason = t == null ? "unknown" : t.getClass().getSimpleName() + ": " + t.getMessage();
        if (FAILED.putIfAbsent(feature, reason) == null) {
            try {
                net.vulkanmod.Initializer.LOGGER.error("Iroh: {} failed ({}); {}", feature, reason, fallback);
            } catch (Throwable ignored) {
                System.err.println("Iroh: " + feature + " failed (" + reason + "); " + fallback);
            }
        }
        return reason;
    }

    /** reason of the first failure of {@code feature}, or null */
    public static String reason(String feature) {
        return FAILED.get(feature);
    }

    public static Map<String, String> snapshot() {
        return new TreeMap<>(FAILED);
    }

    /** tests only */
    public static void resetForTest(String injectCsv) {
        FAILED.clear();
        inject = parse(injectCsv == null ? "" : injectCsv);
    }
}
