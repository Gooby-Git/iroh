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
        int n = Math.max((Runtime.getRuntime().availableProcessors() - 1) / 2, 1);
        createThreads(n);
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

        // Auto select thread count
        if (n == 0) {
            n = Math.max((Runtime.getRuntime().availableProcessors() - 1) / 2, 1);
        }

        this.threads = new Thread[n];
        this.resources = new BuilderResources[n];

        for (int i = 0; i < n; i++) {
            BuilderResources builderResources = new BuilderResources();
            Thread thread = new Thread(() -> runTaskThread(builderResources),
                    "Builder-" + i);
            thread.setPriority(Thread.NORM_PRIORITY);

            this.threads[i] = thread;
            this.resources[i] = builderResources;
            thread.start();
        }
    }

    private void runTaskThread(BuilderResources builderResources) {
        while(!this.stopThreads) {
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
        long t0 = System.nanoTime();
        long bytes0 = dev.elemore.iroh.diag.UploadStats.chunkBytes;
        CompileResult result;
        boolean flag = false;
        int n = 0;
        while ((result = this.compileResults.peek()) != null) {
            if (!unlimited && n > 0 && (System.nanoTime() - t0 > UPLOAD_BUDGET_NS || approxBytes > uploadBudgetBytes())) {
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
        approxBytes = 0;
        if (dev.elemore.iroh.diag.UploadStats.ON) {
            dev.elemore.iroh.diag.UploadStats.sectionsUploaded += n;
            dev.elemore.iroh.diag.UploadStats.frameUploadNs += System.nanoTime() - t0;
        }

        return flag;
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
        return String.format("iT: %d Ts: %d", this.idleThreads, taskCount);
    }

    public BuilderResources[] getResourcesArray() {
        return resources;
    }
}
