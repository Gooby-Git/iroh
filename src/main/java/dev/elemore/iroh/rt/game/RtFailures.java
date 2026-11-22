package dev.elemore.iroh.rt.game;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * P5-2: RT's failure record and fault seam. Reads the SAME property as integration's FeatureFailures
 * ({@code -Diroh.test.failInit=rt} or {@code *}); after the merge {@link #sink} is pointed at FeatureFailures.record so
 * the self-check line lists {@code rt} under failures{..} (merge notes in knowledge/rt-iroh.md). One line per failure,
 * never a retry storm: a feature that failed stays off until restart.
 */
public final class RtFailures {
    private RtFailures() {}

    public static final String PROPERTY = "iroh.test.failInit";
    public static final String FALLBACK = "RT off for this session, raster continues";
    private static final Map<String, String> FAILED = new ConcurrentHashMap<>();
    /** where failures go: (feature "rt", line) -> logged line. Integration points it at FeatureFailures. */
    public static volatile BiFunction<String, Throwable, String> sink = RtFailures::logLine;

    /** Throws when the seam names rt (or *): used at the start of each RT init step. */
    public static void inject(String step) {
        String v = System.getProperty(PROPERTY, "");
        for (String s : v.split(","))
            if (s.trim().equals("*") || s.trim().equals("rt"))
                throw new IllegalStateException("injected failure (" + PROPERTY + "=" + v + ") at rt " + step);
    }

    static String record(String step, Throwable t) {
        String line = sink.apply(step, t);
        FAILED.put("rt", line);
        return line;
    }

    static String logLine(String step, Throwable t) {
        String line = String.format(Locale.ROOT, "Iroh: rt failed at %s (%s: %s); %s", step, t.getClass().getSimpleName(), t.getMessage(), FALLBACK);
        try {
            net.vulkanmod.Initializer.LOGGER.warn(line);
        } catch (Throwable ignored) {
            System.out.println(line); // offline (no game logger)
        }
        return line;
    }

    public static String reason() {
        return FAILED.get("rt");
    }

    public static void resetForTest() {
        FAILED.clear();
        sink = RtFailures::logLine;
    }
}
