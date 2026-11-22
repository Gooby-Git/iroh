package dev.elemore.iroh.shaderpack.runtime;

import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PackPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pack path's lifecycle as a state machine, free of game classes so it is tested offline on the recording
 * backend (P3-4 item 4). States:
 * <pre>
 *   OFF --enable(plan)--> ACTIVE --frame(folder, size)--> ACTIVE (executor built lazily; rebuilt on folder or size change)
 *   ACTIVE --unload()--> ACTIVE (executor closed: every pack image freed; rebuilt at the next frame)
 *   ACTIVE --memory refusal / executor failure--> FAILED (executor closed; stays off for the session, one log line)
 *   any --disable()--> OFF (executor closed)
 * </pre>
 * Frees go through the backend, which defers them past every in-flight frame (in game: VulkanImage.FREE under the epoch
 * rule, frame ops for views and pipelines), so a resize or a dimension change never frees an image a submitted frame
 * still uses.
 */
public final class PackLifecycle {

    public enum State { OFF, ACTIVE, FAILED }

    private final GpuBackend gpu;
    private final FrameExecutor.MemoryGate gate;
    private PackData data;
    private PackPlan plan;
    private FrameExecutor ex;
    private String folder;
    private int width, height;
    private State state = State.OFF;
    public String failure;
    public final List<String> log = new ArrayList<>();
    public int rebuilds;
    /** executor trace on/off (P3-8: off in game unless iroh.pack.log; strings are then never built) */
    public boolean tracing = true;

    public PackLifecycle(GpuBackend gpu, FrameExecutor.MemoryGate gate) {
        this.gpu = gpu;
        this.gate = gate;
    }

    public State state() {
        return state;
    }

    public FrameExecutor executor() {
        return ex;
    }

    public PackPlan plan() {
        return plan;
    }

    public void enable(PackData d, PackPlan p) {
        closeExecutor();
        data = d;
        plan = p;
        state = State.ACTIVE;
        failure = null;
        log.add("enabled: " + d.name + ", folders " + p.frames.keySet());
    }

    /** folder for a dimension id: the folder whose ids name it, else the one with "*", else the root */
    public String folderFor(String dimensionId) {
        String star = null;
        for (Map.Entry<String, dev.elemore.iroh.shaderpack.graph.PassGraph.Frame> e : plan.frames.entrySet()) {
            if (e.getValue().dimensionIds.contains(dimensionId))
                return e.getKey();
            if (e.getValue().dimensionIds.contains("*"))
                star = e.getKey();
        }
        if (star != null)
            return star;
        return plan.frames.containsKey("<root>") ? "<root>" : plan.frames.keySet().iterator().next();
    }

    /**
     * One pack frame. Returns false when nothing ran (off, failed, or the frame was refused). The caller supplies the
     * inputs, the folder, where to present and which passes to skip.
     */
    public boolean frame(PackFrameInputs in, String folder, int presentTo, Set<String> skip) {
        if (state != State.ACTIVE)
            return false;
        try {
            if (ex == null || !folder.equals(this.folder) || in.width() != width || in.height() != height) {
                if (ex != null)
                    log.add("rebuild: " + this.folder + " " + width + "x" + height + " -> " + folder + " " + in.width() + "x" + in.height());
                closeExecutor();
                this.folder = folder;
                width = in.width();
                height = in.height();
                ex = new FrameExecutor(data, plan, folder, gpu, gate);
                ex.tracing = tracing;
                ex.prepare(in);
                rebuilds++;
                for (String p : ex.problems)
                    if (p.startsWith("memory gate refused")) {
                        fail("device memory: " + p);
                        return false;
                    }
            }
            ex.presentTo = presentTo;
            ex.skip = skip;
            ex.frame(in);
            return true;
        } catch (RuntimeException e) {
            fail(e.toString());
            return false;
        }
    }

    /** world unload: free every pack image; the next frame rebuilds */
    public void unload() {
        if (ex != null)
            log.add("unload: pack images freed");
        closeExecutor();
    }

    public void disable() {
        closeExecutor();
        state = State.OFF;
        log.add("disabled");
    }

    private void fail(String why) {
        closeExecutor();
        state = State.FAILED;
        failure = why;
        log.add("FAILED, pack path off for this session: " + why);
    }

    private void closeExecutor() {
        if (ex != null) {
            try {
                ex.close();
            } catch (RuntimeException e) {
                log.add("close: " + e);
            }
            ex = null;
        }
        folder = null;
        width = height = 0;
    }
}
