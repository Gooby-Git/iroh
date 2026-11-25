package dev.elemore.iroh.perf;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * P2-11 (L8): where Vulkan pipelines get created. A pipeline created while a frame command buffer is recording stalls the
 * frame by the driver's compile time (tens of ms on a cold cache for a terrain or entity state first seen in play); one
 * created by the pre-warm worker, or taken from its results, does not. Every {@code vkCreate*Pipelines} site reports here.
 * <ul>
 *   <li>{@link #inFrame}: created by a normal (non pre-warm) call while {@link #RECORDING} says a frame is recording.</li>
 *   <li>{@link #created}: every normal call, in a frame or not (load time, device init).</li>
 *   <li>{@link #prewarmed}: created by the pre-warm worker; {@link #prewarmHits}: a bind found its pipeline ready.</li>
 * </ul>
 * The render thread also reports normal creations to {@code SlowFrames} (counter {@code pipelines}, {@code pipelineUs}), so a
 * slow frame names the stall; the counts below are always on (a few atomic adds per CREATION, never per bind).
 */
public final class PipelineStats {
    public enum Kind { GRAPHICS, COMPUTE }

    /** is a frame command buffer recording right now; Renderer.initRenderer installs the renderer's own check (harnesses and tests: never) */
    public static volatile BooleanSupplier RECORDING = () -> false;

    private static final AtomicLong created = new AtomicLong(), inFrame = new AtomicLong(), inFrameNs = new AtomicLong(),
            createdNs = new AtomicLong(), prewarmed = new AtomicLong(), prewarmedNs = new AtomicLong(), prewarmHits = new AtomicLong(),
            prewarmFailed = new AtomicLong();

    private PipelineStats() {}

    /** A normal (render path or load time) creation that took {@code ns}. Any thread. */
    public static void created(Kind kind, long ns) {
        created.incrementAndGet();
        createdNs.addAndGet(ns);
        if (kind == Kind.GRAPHICS || kind == Kind.COMPUTE) {
            if (RECORDING.getAsBoolean()) {
                inFrame.incrementAndGet();
                inFrameNs.addAndGet(ns);
            }
        }
        // render thread only (SlowFrames ignores other threads, and outside a frame, i.e. whenever -Diroh.slowFrames is off); attributes the stall to the frame
        dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.PIPELINES, 1);
        dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.PIPELINE_US, (int) Math.min(Integer.MAX_VALUE, ns / 1000));
    }

    /** The pre-warm worker created one (never counted as in-frame, whichever thread or time). */
    public static void createdPrewarm(long ns) {
        prewarmed.incrementAndGet();
        prewarmedNs.addAndGet(ns);
    }

    /** A bind found its pipeline already created by the pre-warm worker. */
    public static void prewarmHit() {
        prewarmHits.incrementAndGet();
    }

    public static void prewarmFailed() {
        prewarmFailed.incrementAndGet();
    }

    public static long inFrame() { return inFrame.get(); }
    public static long created() { return created.get(); }
    public static long prewarmed() { return prewarmed.get(); }
    public static long prewarmHits() { return prewarmHits.get(); }

    /** one line for the memStats block */
    public static String line() {
        return "pipelines: created " + created.get() + " (" + (createdNs.get() / 1_000_000) + " ms), in recording frames " + inFrame.get()
                + " (" + (inFrameNs.get() / 1_000_000) + " ms), pre-warmed " + prewarmed.get() + " (" + (prewarmedNs.get() / 1_000_000)
                + " ms, " + prewarmFailed.get() + " failed), pre-warm hits at bind " + prewarmHits.get();
    }

    /** tests */
    public static void reset() {
        created.set(0); inFrame.set(0); inFrameNs.set(0); createdNs.set(0); prewarmed.set(0); prewarmedNs.set(0); prewarmHits.set(0); prewarmFailed.set(0);
    }
}
