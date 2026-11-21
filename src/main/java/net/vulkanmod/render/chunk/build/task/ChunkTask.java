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
