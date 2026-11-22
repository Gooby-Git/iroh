package dev.elemore.iroh.rt.game;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * P5-2: RT's failure record and fault seam ({@code -Diroh.test.failInit=rt} or {@code *}). INT-4: both go through
 * FeatureFailures (one seam, one registry), so the self-check line lists {@code rt} under failures{..}. One line per failure,
 * never a retry storm: a feature that failed stays off until restart.
 */
public final class RtFailures {
    private RtFailures() {}

    public static final String PROPERTY = "iroh.test.failInit";
    public static final String FALLBACK = "RT off for this session, raster continues";
    private static final Map<String, String> FAILED = new ConcurrentHashMap<>();
    /**
     * where failures go: (step, cause) -> logged line. INT-4: FeatureFailures by default (ONE failure registry: one
     * "Iroh: rt failed (...)" line, failures{rt=..} in the self-check); tests may replace it.
     */
    public static volatile BiFunction<String, Throwable, String> sink = RtFailures::toFeatureFailures;

    /**
     * Throws when the seam names rt (or *): used at the start of each RT init step. INT-4: the SAME parsed seam as
     * every other feature (FeatureFailures.inject), not a second reader of the property.
     */
    public static void inject(String step) {
        try {
            dev.elemore.iroh.diag.FeatureFailures.inject("rt");
        } catch (IllegalStateException e) {
            throw new IllegalStateException(e.getMessage() + " at rt " + step, e);
        }
    }

    static String toFeatureFailures(String step, Throwable t) {
        String reason = dev.elemore.iroh.diag.FeatureFailures.record("rt", t, "at " + step + ": " + FALLBACK);
        return "Iroh: rt failed (" + reason + "); at " + step + ": " + FALLBACK;
    }

    static String record(String step, Throwable t) {
        String line = sink.apply(step, t);
        FAILED.put("rt", line);
        return line;
    }

    public static String reason() {
        return FAILED.get("rt");
    }

    /** Tests only: clears the record and re-reads the seam property into FeatureFailures. */
    public static void resetForTest() {
        FAILED.clear();
        sink = RtFailures::toFeatureFailures;
        dev.elemore.iroh.diag.FeatureFailures.resetForTest(System.getProperty(PROPERTY, ""));
    }
}
