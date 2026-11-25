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

        // Upstream VulkanMod babff0c: the old threads were joined by stopThreads() above (or earlier, when the world was
        // unloaded), so their builders are free to release. The base only cleared them, leaking every builder's native
        // index and vertex blocks (tens of MB of address space per thread) on each allChanged / builder-count change.
        if(this.resources != null) {
            if (this.allJoined) {
                for (BuilderResources resources : this.resources) {
                    if (resources != null)
                        resources.free();
                }
            } // else a builder is still running on them (stopThreads timed out): leak rather than free under it
            this.resources = null;
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
            BuilderResources builderResources = this.resourceFactory.get();
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
        while(!this.stopThreads && this.isCurrent(index)) {
            if (index >= this.activeLimit) { // perf/hitches: an adaptive builder parks while the backlog is small
                synchronized (this.parkLock) { // its own monitor: schedule()'s notify() must reach an idle ACTIVE builder
                    if (index >= this.activeLimit && !this.stopThreads) {
                        this.parkedThreads++;
                        try {
                            this.parkLock.wait(200);
                        } catch (InterruptedException e) {
                            return; // only stopThreads' timeout path interrupts a builder
                        } finally {
                            this.parkedThreads--;
                        }
                    }
                }
                continue;
            }
            ChunkTask task = this.pollTask();

            if (task == null) {
                Runnable hook = this.afterEmptyPoll; // test seam: the gap between the empty poll and the wait
                if (hook != null)
                    hook.run();
                // R-P213BC: the wait condition is checked inside the monitor. schedule() offers the task and then notifies
                // under this same monitor, and stopThreads() sets its flag before its notifyAll, so a wakeup that lands
                // between the poll above and the wait below can no longer be lost (it hung stopThreads' join).
                synchronized (this) {
                    if (!this.stopThreads && this.highPriorityTasks.isEmpty() && this.lowPriorityTasks.isEmpty()) {
                        this.idleThreads++;
                        try {
                            this.wait();
                        } catch (InterruptedException e) {
                            return; // only stopThreads' timeout path interrupts a builder
                        } finally {
                            this.idleThreads--;
                        }
                    }
                }
                continue;
            }

            try {
                task.runTask(builderResources);
                this.failures.succeeded(task);
            } catch (Throwable t) {
                // R-P213BC: one failing task used to end this builder for the rest of the session (e5dad79's NPE was one).
                if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError))
                    throw t; // OutOfMemoryError and friends: not ours to swallow
                // the task died half way: its TerrainBuilders are still in begin() state and would fail every later task
                try {
                    if (builderResources != null)
                        builderResources.clear();
                    this.failures.taskFailed(task, t);
                } catch (Throwable ignored) {
                    // reporting must not end the thread either
                }
            } finally {
                this.pendingTasks.decrementAndGet();
                task.finish();
            }
        }
    }

    /** R-P213BC: false for a builder of an older generation (stopThreads timed out, createThreads started new ones). */
    private boolean isCurrent(int index) {
        Thread[] t = this.threads;
        return t != null && index < t.length && t[index] == Thread.currentThread();
    }

    /** R-P213BC: failed chunk tasks: rate-limited log, bounded retry of the section. */
    final ChunkTaskFailures failures = new ChunkTaskFailures();
    /** Test seam: BuilderResources needs the Fabric renderer, which a headless test does not have. */
    java.util.function.Supplier<BuilderResources> resourceFactory = BuilderResources::new;
    /** Test seam (null in the game): runs on a builder thread after pollTask() returned nothing, before it waits. */
    volatile Runnable afterEmptyPoll;

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
            this.notifyAll(); // all: a waiter above activeLimit would swallow a single notify (adaptive builders)
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

        // R-P213BC: bounded join. A builder stuck in a task (or a wakeup bug) must not freeze the render thread in
        // allChanged / setLevel(null); builders that miss the deadline are interrupted and their native blocks are NOT freed.
        long deadline = System.nanoTime() + JOIN_TIMEOUT_NS;
        boolean all = true;
        for (Thread thread : this.threads) {
            try {
                long ms = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
                thread.join(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                all = false;
                thread.interrupt();
            }
        }
        this.allJoined = all;
        if (!all)
            net.vulkanmod.Initializer.LOGGER.error("Iroh: chunk builder threads did not stop within {} ms; their native buffers are left allocated",
                    JOIN_TIMEOUT_NS / 1_000_000L);
    }

    static final long JOIN_TIMEOUT_NS = Long.getLong("iroh.builderJoinMs", 5000L) * 1_000_000L;
    private volatile boolean allJoined = true;

    /** perf/uploads: render-thread section-upload budget per frame while playing (unlimited on loading screens). */
    private static final long UPLOAD_BUDGET_NS = Long.getLong("iroh.uploadBudgetUs", 1000L) * 1000L;
    private static long uploadBudgetBytes = -1;

    /**
     * P3-7: bytes per frame. Explicit {@code -Diroh.uploadBudgetMB} wins; the default (48 MiB for the 16-byte section
     * vertex) scales with the section vertex size, so FULL pack mode's 36-byte sections keep the same SECTION
     * throughput (108 MiB): the time budget (iroh.uploadBudgetUs) still bounds the frame cost. knowledge/settings.md.
     */
    static long uploadBudgetBytes() {
        if (uploadBudgetBytes < 0)
            uploadBudgetBytes = derivedUploadBudget(System.getProperty("iroh.uploadBudgetMB"),
                    net.vulkanmod.render.PipelineManager.terrainVertexFormat == null ? 16
                            : net.vulkanmod.render.PipelineManager.terrainVertexFormat.getVertexSize());
        return uploadBudgetBytes;
    }

    public static long derivedUploadBudget(String explicitMB, int vertexSize) {
        if (explicitMB != null)
            try {
                return Long.parseLong(explicitMB.trim()) << 20;
            } catch (NumberFormatException ignored) {
            }
        return (48L << 20) * Math.max(16, vertexSize) / 16;
    }

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

    /** P2-4: per-section cost model of the intake ({@code iroh.intake.budget}). */
    private final dev.elemore.iroh.perf.IntakeBudget intakeBudget = new dev.elemore.iroh.perf.IntakeBudget();

    private boolean updateSections0(boolean unlimited) {
        long t0 = System.nanoTime();
        long bytes0 = dev.elemore.iroh.diag.UploadStats.chunkBytes;
        CompileResult result;
        boolean flag = false;
        int n = 0;
        // P2-4: predict the next section's cost and stop BEFORE it crosses the budget (the old rule checks after)
        boolean predict = !unlimited && dev.elemore.iroh.perf.IntakeBudget.enabled();
        while ((result = this.compileResults.peek()) != null) {
            long nextBytes = 0;
            if (predict) {
                nextBytes = result.byteSize();
                if (!this.intakeBudget.admit(System.nanoTime() - t0, UPLOAD_BUDGET_NS, approxBytes, uploadBudgetBytes(), nextBytes, n)) {
                    this.intakeBudget.deferredSections += this.compileResults.size();
                    if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.INTAKE_DEFERRED, this.compileResults.size());
                    if (dev.elemore.iroh.diag.UploadStats.ON) dev.elemore.iroh.diag.UploadStats.sectionsDeferred += this.compileResults.size();
                    break;
                }
            } else if (!unlimited && n > 0 && (System.nanoTime() - t0 > UPLOAD_BUDGET_NS || approxBytes > uploadBudgetBytes())) {
                if (dev.elemore.iroh.diag.UploadStats.ON) dev.elemore.iroh.diag.UploadStats.sectionsDeferred += this.compileResults.size();
                break;
            }
            this.compileResults.poll();
            flag = true;
            n++;
            long s0 = predict ? System.nanoTime() : 0;
            long grows0 = predict ? net.vulkanmod.render.chunk.buffer.AreaBuffer.bufferEvents : 0;
            try {
                doSectionUpdate(result);
            } finally {
                releaseAll(result); // idempotent: whatever the upload did not consume goes back
            }
            if (predict)
                this.intakeBudget.observe(nextBytes, System.nanoTime() - s0, net.vulkanmod.render.chunk.buffer.AreaBuffer.bufferEvents != grows0);
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
        if (dev.elemore.iroh.diag.SegmentCheck.ON) dev.elemore.iroh.diag.SegmentCheck.afterIntake(); // TERRAIN-HOLES

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
        if (compileResult.originStale()) { // RQ-HOLES-b: relocated while the build ran; the mesh is for the old position
            releaseAll(compileResult);
            return;
        }
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
            if (dev.elemore.iroh.apiimpl.Sections.any()) // api 4: section listeners (LOD hand-off)
                dev.elemore.iroh.apiimpl.Sections.built(section.xOffset >> 4, section.yOffset >> 4, section.zOffset >> 4, section.isCompletelyEmpty());
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
