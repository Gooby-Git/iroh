package net.vulkanmod.render.chunk.build.task;

import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.build.RenderRegion;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class ChunkTask {
    public static final boolean BENCH = true;

    protected static TaskDispatcher taskDispatcher;

    public static BuildTask createBuildTask(RenderSection renderSection, RenderRegion renderRegion, boolean highPriority) {
        return new BuildTask(renderSection, renderRegion, highPriority);
    }

    protected AtomicBoolean cancelled = new AtomicBoolean(false);
    protected final RenderSection section;
    public boolean highPriority = false;

    /**
     * Set by the scheduler for a rebuild the frame waits for (P1-11, chunk build defer mode); counted down by
     * {@link TaskDispatcher} when the task has run, was cancelled or was dropped.
     */
    public volatile java.util.concurrent.CountDownLatch done;

    public void finish() {
        var l = this.done;
        if (l != null)
            l.countDown();
    }

    ChunkTask(RenderSection renderSection) {
        this.section = renderSection;
    }

    public abstract String name();

    public abstract Result runTask(BuilderResources builderResources);

    /** R-P213BC: packed section position, the failure tracker's key (0 without a section). */
    long sectionKey() {
        var s = this.section;
        return s == null ? 0L : ((long) (s.xOffset() >> 4) & 0x3FFFFF) << 42 | ((long) (s.zOffset() >> 4) & 0x3FFFFF) << 20 | ((long) (s.yOffset() >> 4) & 0xFFFFF);
    }

    String describeSection() {
        var s = this.section;
        return s == null ? "?" : (s.xOffset() >> 4) + "," + (s.yOffset() >> 4) + "," + (s.zOffset() >> 4);
    }

    /** R-P213BC: the task threw on a builder thread; ask for the section to be meshed again (the dispatcher bounds how often). */
    void markForRebuild() {
        var s = this.section;
        if (s != null && !this.cancelled.get())
            s.setDirty(false);
    }

    public void cancel() {
        this.cancelled.set(true);
    }

    public static void setTaskDispatcher(TaskDispatcher dispatcher) {
        taskDispatcher = dispatcher;
    }
    
    public enum Result {
        CANCELLED,
        SUCCESSFUL
    }
}
