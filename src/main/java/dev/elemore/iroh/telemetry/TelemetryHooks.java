package dev.elemore.iroh.telemetry;

/**
 * What other Iroh features tell the Telemetry overlay (card TEL-1). All plain / volatile writes, no allocation; a feature
 * that never calls them simply shows its default. Render thread unless noted.
 */
public final class TelemetryHooks {
    private TelemetryHooks() {}

    /** Rendering level shown on the features line: "Raster" by default; the RT / shader pack code sets e.g. "Ray tracing". */
    public static volatile String renderingLevel;
    /** LOD renderer (Vista) running; any thread. */
    public static volatile boolean lodOn;
    private static volatile long generatedFrames;

    /**
     * Frame generation (Q17) calls this once per generated (interpolated) frame it presents; the overlay then shows
     * "rendered X / generated Y fps". Renders without frame generation never call it, so the row stays hidden.
     */
    public static void onGeneratedFrame() { generatedFrames++; }

    public static long generatedFrames() { return generatedFrames; }
}
