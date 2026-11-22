package net.vulkanmod.render.chunk.build.task;

import com.google.common.collect.Queues;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.ChunkAreaManager;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.vertex.TerrainRenderType;

import org.jetbrains.annotations.Nullable;

import java.util.Queue;

public class TaskDispatcher {
    private final Queue<CompileResult> compileResults = Queues.newLinkedBlockingDeque();
    public final ThreadBuilderPack fixedBuffers;

    private volatile boolean stopThreads;
    private Thread[] threads;
    private BuilderResources[] resources;
    private int idleThreads;
    /** Tasks queued or running (P1-8b): the idle-thread count alone is true while a woken thread has not yet taken its task. */
    private final java.util.concurrent.atomic.AtomicInteger pendingTasks = new java.util.concurrent.atomic.AtomicInteger();
    private final Queue<ChunkTask> highPriorityTasks = Queues.newConcurrentLinkedQueue();
    private final Queue<ChunkTask> lowPriorityTasks = Queues.newConcurrentLinkedQueue();

    public TaskDispatcher() {
        this.fixedBuffers = new ThreadBuilderPack();

        this.stopThreads = true;
    }

    public void createThreads() {
        createThreads(0);
    }

    /** perf/hitches: builders allowed to take tasks now (adaptive, ThreadPolicy); the others park. */
    private volatile int activeLimit = Integer.MAX_VALUE;
    private dev.elemore.iroh.perf.ThreadPolicy.Adaptive adaptive;
    private volatile int parkedThreads;
    private final Object parkLock = new Object();

    /** Render thread, once per frame: raises or lowers the active builder count with the backlog. */
    public void adaptBuilders() {
        var a = this.adaptive;
        if (a == null) return;
        int limit = a.update(this.pendingTasks.get(), System.nanoTime());
        if (limit != this.activeLimit) {
            this.activeLimit = limit;
            synchronized (this.parkLock) {
                this.parkLock.notifyAll();
            }
        }
    }

    public int activeBuilderLimit() {
        return this.threads == null ? 0 : Math.min(this.activeLimit, this.threads.length);
    }

    public void createThreads(int n) {
        if(!this.stopThreads) {
            this.stopThreads();
        }

        this.stopThreads = false;

        if(this.resources != null) {
            for (BuilderResources resources : this.resources) {
                resources.clear();
            }
        }

        // perf/hitches: count from physical cores (ThreadPolicy), configured count = the base; adaptive extra builders
        // run only while the backlog is large
        int physical = dev.elemore.iroh.perf.ThreadPolicy.physicalCores();
        int base = dev.elemore.iroh.perf.ThreadPolicy.physicalCount()
                ? dev.elemore.iroh.perf.ThreadPolicy.baseBuilders(n, physical)
                : dev.elemore.iroh.perf.ThreadPolicy.legacyBuilders(n, Runtime.getRuntime().availableProcessors()); // rc14
        int max = dev.elemore.iroh.perf.ThreadPolicy.maxBuilders(base, physical, dev.elemore.iroh.perf.ThreadPolicy.adaptive());
        this.adaptive = max > base ? new dev.elemore.iroh.perf.ThreadPolicy.Adaptive(base, max) : null;
        this.activeLimit = base;
        n = max;

        this.threads = new Thread[n];
        this.resources = new BuilderResources[n];

        for (int i = 0; i < n; i++) {
            BuilderResources builderResources = new BuilderResources();
            final int index = i;
            Thread thread = new Thread(() -> {
                        dev.elemore.iroh.perf.ThreadPolicy.registerBuilderThread(); // perf/hitches: nice + off the render core
                        runTaskThread(index, builderResources);
                    },
                    "Builder-" + i);
            thread.setPriority(Thread.NORM_PRIORITY);

            this.threads[i] = thread;
            this.resources[i] = builderResources;
            thread.start();
        }
    }

    private void runTaskThread(int index, BuilderResources builderResources) {
        while(!this.stopThreads) {
            if (index >= this.activeLimit) { // perf/hitches: an adaptive builder parks while the backlog is small
                synchronized (this.parkLock) { // its own monitor: schedule()'s notify() must reach an idle ACTIVE builder
                    if (index >= this.activeLimit && !this.stopThreads) {
                        this.parkedThreads++;
                        try {
                            this.parkLock.wait(200);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        } finally {
                            this.parkedThreads--;
                        }
                    }
                }
                continue;
            }
            ChunkTask task = this.pollTask();

            if(task == null)
                synchronized (this) {
                    try {
                        this.idleThreads++;
                        this.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    this.idleThreads--;
                }

            if(task == null)
                continue;

            try {
                task.runTask(builderResources);
            } finally {
                this.pendingTasks.decrementAndGet();
                task.finish();
            }
        }
    }

    public void schedule(ChunkTask chunkTask) {
        if(chunkTask == null)
            return;

        this.pendingTasks.incrementAndGet();

        if (chunkTask.highPriority) {
            this.highPriorityTasks.offer(chunkTask);
        } else {
            this.lowPriorityTasks.offer(chunkTask);
        }

        synchronized (this) {
            this.notify();
        }
    }

    @Nullable
    private ChunkTask pollTask() {
        ChunkTask task = this.highPriorityTasks.poll();

        if(task == null)
            task = this.lowPriorityTasks.poll();

        return task;
    }

    public void stopThreads() {
        if(this.stopThreads)
            return;

        this.stopThreads = true;

        synchronized (this) {
            this.notifyAll();
        }
        synchronized (this.parkLock) {
            this.parkLock.notifyAll();
        }

        for (Thread thread : this.threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }

    /** perf/uploads: render-thread section-upload budget per frame while playing (unlimited on loading screens). */
    private static final long UPLOAD_BUDGET_NS = Long.getLong("iroh.uploadBudgetUs", 1000L) * 1000L;
    private static final long UPLOAD_BUDGET_BYTES = Long.getLong("iroh.uploadBudgetMB", 48L) << 20;

    public boolean updateSections() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        boolean loading = mc.level == null || mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen
                || mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen;
        return updateSections(loading);
    }

    /**
     * Uploads finished section meshes. perf/uploads: with {@code unlimited} false it stops after about 1 ms or 48 MB
     * of render-thread work (-Diroh.uploadBudgetUs / -Diroh.uploadBudgetMB); the rest stays queued for the next frame,
     * so a burst of rebuilds (fly-through, world load behind the HUD) spreads over frames instead of one long frame.
     * Callers that are waiting for specific sections (ZERO_FRAMES / ONE_FRAME defer modes, loading screens) pass true.
     */
    public boolean updateSections(boolean unlimited) {
        if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.enter(dev.elemore.iroh.diag.SlowFrames.Phase.SECTION_INTAKE);
        try {
            return updateSections0(unlimited);
        } finally {
            if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.exit(dev.elemore.iroh.diag.SlowFrames.Phase.SECTION_INTAKE);
        }
    }

    /** perf/frame-attrib: render-thread view of the builders (slow-frame counters). */
    public int busyBuilders() {
        return this.threads == null ? 0 : Math.max(0, this.threads.length - this.idleThreads - this.parkedThreads);
    }

    public int pendingTaskCount() {
        return this.pendingTasks.get();
    }

    public int pendingResultCount() {
        return this.compileResults.size();
    }

    private boolean updateSections0(boolean unlimited) {
        long t0 = System.nanoTime();
        long bytes0 = dev.elemore.iroh.diag.UploadStats.chunkBytes;
        CompileResult result;
        boolean flag = false;
        int n = 0;
        while ((result = this.compileResults.peek()) != null) {
            if (!unlimited && n > 0 && (System.nanoTime() - t0 > UPLOAD_BUDGET_NS || approxBytes > UPLOAD_BUDGET_BYTES)) {
                if (dev.elemore.iroh.diag.UploadStats.ON) dev.elemore.iroh.diag.UploadStats.sectionsDeferred += this.compileResults.size();
                break;
            }
            this.compileResults.poll();
            flag = true;
            n++;
            try {
                doSectionUpdate(result);
            } finally {
                releaseAll(result); // idempotent: whatever the upload did not consume goes back
            }
        }
        if (dev.elemore.iroh.diag.SlowFrames.ON) {
            dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.SECTIONS, n);
            dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.SECTION_KB, (int) (approxBytes >> 10));
        }
        approxBytes = 0;
        if (dev.elemore.iroh.diag.UploadStats.ON) {
            dev.elemore.iroh.diag.UploadStats.sectionsUploaded += n;
            dev.elemore.iroh.diag.UploadStats.frameUploadNs += System.nanoTime() - t0;
        }

        return flag;
    }

    private boolean structuralUpload;

    /** perf/hitches: whether a full section result (not a translucent resort) was applied since the last call. */
    public boolean takeStructuralUpload() {
        boolean b = this.structuralUpload;
        this.structuralUpload = false;
        return b;
    }

    /** Bytes of the results uploaded in the current updateSections call (budget). */
    private long approxBytes;

    private void releaseAll(CompileResult result) {
        for (var b : result.renderedLayers.values())
            if (b != null) b.release();
    }

    public void scheduleSectionUpdate(CompileResult compileResult) {
        this.compileResults.add(compileResult);
    }

    private void doSectionUpdate(CompileResult compileResult) {
        RenderSection section = compileResult.renderSection;
        ChunkArea renderArea = section.getChunkArea();
        DrawBuffers drawBuffers = renderArea.getDrawBuffers();

        // Check if area has been dismissed before uploading
        ChunkAreaManager chunkAreaManager = WorldRenderer.getInstance().getChunkAreaManager();
        if (chunkAreaManager == null || chunkAreaManager.getChunkArea(renderArea.index) != renderArea) {
            releaseAll(compileResult); // perf/uploads: the base leaked these buffers (native heap, now ring staging)
            return;
        }

        if(compileResult.fullUpdate) {
            this.structuralUpload = true; // perf/hitches: visibility / emptiness may have changed (graph cache)
            var renderLayers = compileResult.renderedLayers;
            for(TerrainRenderType renderType : TerrainRenderType.VALUES) {
                UploadBuffer uploadBuffer = renderLayers.get(renderType);

                if(uploadBuffer != null) {
                    this.approxBytes += uploadBuffer.byteSize();
                    drawBuffers.upload(section, uploadBuffer, renderType);
                } else {
                    section.resetDrawParameters(renderType);
                }
            }

            compileResult.updateSection();
        }
        else {
            UploadBuffer uploadBuffer = compileResult.renderedLayers.get(TerrainRenderType.TRANSLUCENT);
            drawBuffers.upload(section, uploadBuffer, TerrainRenderType.TRANSLUCENT);
        }
    }

    public boolean isIdle() { return this.pendingTasks.get() == 0 && this.compileResults.isEmpty(); }

    public void clearBatchQueue() {
        while(!this.highPriorityTasks.isEmpty()) {
            ChunkTask chunkTask = this.highPriorityTasks.poll();
            if (chunkTask != null) {
                chunkTask.cancel();
                this.pendingTasks.decrementAndGet();
                chunkTask.finish();
            }
        }

        while(!this.lowPriorityTasks.isEmpty()) {
            ChunkTask chunkTask = this.lowPriorityTasks.poll();
            if (chunkTask != null) {
                chunkTask.cancel();
                this.pendingTasks.decrementAndGet();
                chunkTask.finish();
            }
        }
    }

    public String getStats() {
        int taskCount = highPriorityTasks.size() + lowPriorityTasks.size();
        return String.format("iT: %d Ts: %d B: %d/%d", this.idleThreads, taskCount, activeBuilderLimit(), this.threads == null ? 0 : this.threads.length);
    }

    public BuilderResources[] getResourcesArray() {
        return resources;
    }
}
