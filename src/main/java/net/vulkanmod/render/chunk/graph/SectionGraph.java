package net.vulkanmod.render.chunk.graph;

import dev.elemore.iroh.chunk.ChunkDeferPolicy;
import dev.elemore.iroh.chunk.RebuildWaiting;
import dev.elemore.iroh.chunk.SyncWaitSet;
import dev.elemore.iroh.config.IrohOptions;
import dev.elemore.iroh.config.IrohOptions.DeferMode;
import net.minecraft.client.PrioritizeChunkUpdates;
import com.google.common.collect.Lists;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.interfaces.FrustumMixed;
import net.vulkanmod.render.chunk.*;
import net.vulkanmod.render.chunk.build.RenderRegionBuilder;
import net.vulkanmod.render.chunk.build.task.CompiledSection;
import net.vulkanmod.render.chunk.build.task.TaskDispatcher;
import net.vulkanmod.render.chunk.frustum.VFrustum;
import net.vulkanmod.render.chunk.util.AreaSetQueue;
import net.vulkanmod.render.chunk.util.ResettableQueue;
import net.vulkanmod.render.profiling.Profiler;
import org.joml.FrustumIntersection;

import java.util.List;

public class SectionGraph {
    Minecraft minecraft;
    private final Level level;

    private final SectionGrid sectionGrid;
    private final ChunkAreaManager chunkAreaManager;
    private final TaskDispatcher taskDispatcher;
    private final ResettableQueue<RenderSection> sectionQueue = new ResettableQueue<>();
    private AreaSetQueue chunkAreaQueue;
    private short lastFrame = 0;

    private final ResettableQueue<RenderSection> blockEntitiesSections = new ResettableQueue<>();
    private final ResettableQueue<RenderSection> rebuildQueue = new ResettableQueue<>();

    private VFrustum frustum;

    public RenderRegionBuilder renderRegionCache;
    int nonEmptyChunks;


    public SectionGraph(Level level, SectionGrid sectionGrid, TaskDispatcher taskDispatcher) {
        this.level = level;
        this.sectionGrid = sectionGrid;
        this.chunkAreaManager = sectionGrid.getChunkAreaManager();
        this.taskDispatcher = taskDispatcher;

        this.chunkAreaQueue = new AreaSetQueue(sectionGrid.getChunkAreaManager().size);
        this.minecraft = Minecraft.getInstance();
        this.renderRegionCache = WorldRenderer.getInstance().renderRegionCache;
    }

    public void update(Camera camera, Frustum frustum, boolean spectator) {
        Profiler profiler = Profiler.getMainProfiler();

        BlockPos blockpos = camera.getBlockPosition();
        this.cameraBlock = blockpos;

        this.minecraft.getProfiler().popPush("update");

        boolean flag = this.minecraft.smartCull;
        if (spectator && this.level.getBlockState(blockpos).isSolidRender(this.level, blockpos)) {
            flag = false;
        }

        profiler.push("frustum");
        this.frustum = (((FrustumMixed) (frustum)).customFrustum()).offsetToFullyIncludeCameraCube(8);
        this.sectionGrid.updateFrustumVisibility(this.frustum);
        profiler.pop();

        this.minecraft.getProfiler().push("partial_update");

        this.initUpdate();
        this.initializeQueueForFullUpdate(camera);

        if (flag)
            this.updateRenderChunks();
        else
            this.updateRenderChunksSpectator();

        this.scheduleRebuilds();

        this.minecraft.getProfiler().pop();
    }

    private void initializeQueueForFullUpdate(Camera camera) {
        Vec3 vec3 = camera.getPosition();
        BlockPos blockpos = camera.getBlockPosition();
        RenderSection renderSection = this.sectionGrid.getSectionAtBlockPos(blockpos);

        if (renderSection == null) {
            boolean flag = blockpos.getY() > this.level.getMinBuildHeight();
            int y = flag ? this.level.getMaxBuildHeight() - 8 : this.level.getMinBuildHeight() + 8;
            int x = Mth.floor(vec3.x / 16.0D) * 16;
            int z = Mth.floor(vec3.z / 16.0D) * 16;

            List<RenderSection> list = Lists.newArrayList();
            int renderDistance = WorldRenderer.getInstance().getRenderDistance();

            for (int x1 = -renderDistance; x1 <= renderDistance; ++x1) {
                for (int z1 = -renderDistance; z1 <= renderDistance; ++z1) {

                    RenderSection renderSection1 = this.sectionGrid.getSectionAtBlockPos(new BlockPos(x + SectionPos.sectionToBlockCoord(x1, 8), y, z + SectionPos.sectionToBlockCoord(z1, 8)));
                    if (renderSection1 != null) {
                        initFirstNode(renderSection1, this.lastFrame);
                        list.add(renderSection1);
                    }
                }
            }

            this.sectionQueue.ensureCapacity(list.size());
            for (RenderSection chunkInfo : list) {
                this.sectionQueue.add(chunkInfo);
            }

        } else {
            initFirstNode(renderSection, this.lastFrame);

            this.sectionQueue.add(renderSection);
        }

    }

    private static void initFirstNode(RenderSection renderSection, short frame) {
        renderSection.mainDir = 7;
        renderSection.sourceDirs = (byte) (1 << 7);
        renderSection.directions = (byte) 0xFF;
        renderSection.setLastFrame(frame);
        renderSection.visibility |= initVisibility();
        renderSection.directionChanges = 0;
        renderSection.steps = 0;
    }

    // Init special value used by first graph node
    private static long initVisibility() {
        long vis = 0;
        for (int dir = 0; dir < 6; dir++) {
            vis |= 1L << ((6 << 3) + dir);
            vis |= 1L << ((7 << 3) + dir);
        }

        return vis;
    }

    /** Sections the last graph update reached that can be built (chunk data and light present) but have no compiled result yet. */
    private int unbuiltSections;

    public int getUnbuiltSections() {
        return this.unbuiltSections;
    }

    private void countUnbuilt(RenderSection renderSection) {
        if (renderSection.isCompiled() || !renderSection.isChunkRenderReady())
            return;

        this.unbuiltSections++;

        // Ready, never compiled, not queued and nothing in flight: it would never be built (the chunk became ready after
        // the section was last marked clean). Queue it again so the count can reach zero.
        if (!renderSection.isDirty() && this.taskDispatcher.isIdle())
            renderSection.setDirty(false);
    }

    private double distSqrToCamera(RenderSection section) {
        // vanilla: section origin + (8, 8, 8) against the camera block position
        double dx = section.xOffset() + 8 - this.cameraBlock.getX();
        double dy = section.yOffset() + 8 - this.cameraBlock.getY();
        double dz = section.zOffset() + 8 - this.cameraBlock.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private void initUpdate() {
        this.unbuiltSections = 0;
        this.resetUpdateQueues();

        this.lastFrame++;
        this.nonEmptyChunks = 0;
    }

    private void resetUpdateQueues() {
        this.chunkAreaQueue.clear();
        this.sectionGrid.getChunkAreaManager().resetQueues();
        this.sectionQueue.clear();
        this.blockEntitiesSections.clear();
        this.rebuildQueue.clear();
    }

    private void updateRenderChunks() {
        int maxDirectionsChanges = Initializer.CONFIG.advCulling - 1;

        while (this.sectionQueue.hasNext()) {
            RenderSection renderSection = this.sectionQueue.poll();

            if (notInFrustum(renderSection))
                continue;

            if (renderSection.directionChanges > maxDirectionsChanges)
                continue;

            renderSection.lastVisibleFrame = this.lastFrame; // Iroh P1-6b: IrohApi.isSectionVisible

            if (!renderSection.isCompletelyEmpty()) {
                renderSection.getChunkArea().sectionQueue.add(renderSection);
                this.chunkAreaQueue.add(renderSection.getChunkArea());
                this.nonEmptyChunks++;
            }

            if (renderSection.containsBlockEntities()) {
                this.blockEntitiesSections.ensureCapacity(1);
                this.blockEntitiesSections.add(renderSection);
            }

            countUnbuilt(renderSection);

            if (renderSection.isDirty()) {
                this.rebuildQueue.ensureCapacity(1);
                this.rebuildQueue.add(renderSection);
            }

            byte dirs = (byte) (renderSection.getVisibilityDirs() & renderSection.getDirections());

            visitAdjacentNodes(renderSection, dirs);
        }
    }

    /** Debug switch (P1-9): reproduce the old behaviour (a section whose chunk was not render-ready was marked clean and lost). */
    private static final boolean LEGACY_DROP = Boolean.getBoolean("iroh.legacyDirtyDrop");
    private static final boolean STATS = Boolean.getBoolean("iroh.rebuildStats");
    /** First-refusal snapshot of the compiled section, to tell "compiled after retry" from "never compiled" in the stats line. */
    private final java.util.IdentityHashMap<RenderSection, CompiledSection> droppedStats = new java.util.IdentityHashMap<>();
    private long statsLastLog;

    /**
     * Sections whose rebuild was refused (chunk not render-ready) wait here until that chunk turns ready (td-27b474). The
     * decision is event driven: ChunkStatusMap -> WorldRenderer.chunkBecameRenderReady -> {@link RebuildWaiting#chunkReady}.
     */
    private final RebuildWaiting waiting = new RebuildWaiting();

    public RebuildWaiting waiting() {
        return this.waiting;
    }

    /** Camera block of the last update, for the vanilla "nearby" radius (P1-11). */
    private BlockPos cameraBlock = BlockPos.ZERO;

    /** Rebuilds the current frame decided to wait for (chunk build defer mode, P1-11); consumed by WorldRenderer. */
    private final SyncWaitSet syncSet = new SyncWaitSet();
    /** False for the repeat update WorldRenderer runs after a ZERO_FRAMES wait: it must not wait a second time. */
    private boolean syncAllowed = true;

    public SyncWaitSet syncSet() {
        return this.syncSet;
    }

    public void setSyncAllowed(boolean allowed) {
        this.syncAllowed = allowed;
    }

    private void scheduleRebuilds() {
        DeferMode mode = this.syncAllowed ? IrohOptions.chunkBuildDeferMode() : DeferMode.ALWAYS;
        boolean nearbyRule = mode != DeferMode.ALWAYS
                && this.minecraft.options.prioritizeChunkUpdates().get() == PrioritizeChunkUpdates.NEARBY;
        for (int i = 0; i < this.rebuildQueue.size(); i++) {
            RenderSection section = this.rebuildQueue.get(i);

            // Parked section: its chunk was not render-ready when it was refused. Nothing changed unless the chunk is
            // ready now, so do not even try (this is what made the retry events multiply in rc2).
            boolean retry = false;
            if (section.waitingForChunk && !LEGACY_DROP) {
                if (!section.isChunkRenderReady())
                    continue;
                section.waitingForChunk = false;
                this.waiting.unpark(section.parkedKey);
                retry = true;
            }

            boolean scheduled;
            if (mode != DeferMode.ALWAYS && ChunkDeferPolicy.isImportant(mode, nearbyRule, section.isDirtyFromPlayer(),
                    this.distSqrToCamera(section))) {
                if (this.syncSet.hasRoom())
                    scheduled = section.rebuildChunkImportant(this.taskDispatcher, this.renderRegionCache, this.syncSet);
                else {
                    this.syncSet.noteOverCap();
                    scheduled = section.rebuildChunkAsync(this.taskDispatcher, this.renderRegionCache);
                }
            } else {
                scheduled = section.rebuildChunkAsync(this.taskDispatcher, this.renderRegionCache);
            }

            if (scheduled) {
                if (retry)
                    this.waiting.retriedAccepted++;
                section.setNotDirty();
            } else if (LEGACY_DROP) {
                if (STATS) {
                    this.waiting.refused++;
                    this.droppedStats.putIfAbsent(section, section.getCompiledSection());
                }
                section.setNotDirty();
            } else {
                // Chunk (or a neighbour chunk) not render-ready: stay dirty and wait for that chunk's status to change.
                if (this.waiting.park(chunkKey(section), section::isChunkRenderReady)) {
                    section.waitingForChunk = true;
                    section.parkedKey = chunkKey(section);
                } else {
                    // ready between the refusal and now: its wake-up already fired, so ask for the retry ourselves
                    WorldRenderer wr = WorldRenderer.getInstance();
                    if (wr != null)
                        wr.scheduleGraphUpdate();
                }
                if (STATS)
                    this.droppedStats.putIfAbsent(section, section.getCompiledSection());
            }
        }
        this.rebuildQueue.clear();

        if (STATS)
            logStats();
    }

    private static long chunkKey(RenderSection section) {
        return ChunkPos.asLong(section.xOffset() >> 4, section.zOffset() >> 4);
    }

    private void logStats() {
        long now = System.currentTimeMillis();
        if (now - this.statsLastLog <= 3000 || (this.waiting.refused == 0 && this.waiting.idleEvents == 0))
            return;
        this.statsLastLog = now;
        int compiledAfter = 0, neverCompiled = 0, stale = 0, waitingNow = 0, waitingChunkReady = 0, waitingReadyHidden = 0;
        for (var e : this.droppedStats.entrySet()) {
            RenderSection s = e.getKey();
            boolean wasUncompiled = e.getValue() == CompiledSection.UNCOMPILED;
            boolean changed = s.getCompiledSection() != e.getValue();
            if (s.waitingForChunk) {
                waitingNow++;
                // Parked, chunk ready and in this update's visible set: must be 0 (the update just retried every such section).
                // Parked sections outside the frustum or occluded are retried when the walk reaches them, so they are not counted.
                if (s.isChunkRenderReady()) {
                    waitingReadyHidden++;
                    if (s.lastVisibleFrame == this.lastFrame) waitingChunkReady++;
                }
            }
            if (changed) compiledAfter++;
            else if (wasUncompiled) neverCompiled++;
            else stale++;
        }
        Initializer.LOGGER.info("[iroh-rebuild-stats] legacy={} refusedWaiting={} retried={} retriedAccepted={} compiledAfterRetry={} "
                        + "neverCompiled={} staleKeptOldMesh={} waitingNow={} waitingButChunkReady={} waitingReadyNotVisible={} lateReady={} wakeups={} idleReadyEvents={} "
                        + "distinctSections={} dropEvents={} dirtyNow={}",
                LEGACY_DROP, this.waiting.refused, this.waiting.retried, this.waiting.retriedAccepted, compiledAfter,
                neverCompiled, stale, waitingNow, waitingChunkReady, waitingReadyHidden, this.waiting.lateReady, this.waiting.wakeups, this.waiting.idleEvents,
                this.droppedStats.size(), this.waiting.refused, countDirty());
    }

    private int countDirty() {
        int n = 0;
        for (RenderSection s : this.droppedStats.keySet())
            if (s.isDirty()) n++;
        return n;
    }

    private boolean notInFrustum(RenderSection renderSection) {
        byte frustumRes = renderSection.getChunkArea().inFrustum(renderSection.frustumIndex);
        if (frustumRes > FrustumIntersection.INTERSECT) {
            return true;
        } else if (frustumRes == FrustumIntersection.INTERSECT) {
            return !frustum.testFrustum(renderSection.xOffset, renderSection.yOffset, renderSection.zOffset,
                    renderSection.xOffset + 16, renderSection.yOffset + 16, renderSection.zOffset + 16);
        }
        return false;
    }

    private void visitAdjacentNodes(RenderSection renderSection, byte dirs) {
        dirs &= renderSection.adjDirs;

        this.sectionQueue.ensureCapacity(6);

        RenderSection relativeSection;

        relativeSection = renderSection.adjDown;
        checkToAdd(renderSection, relativeSection, (byte) GraphDirections.DOWN, (byte) GraphDirections.UP, dirs);

        relativeSection = renderSection.adjUp;
        checkToAdd(renderSection, relativeSection, (byte) GraphDirections.UP, (byte) GraphDirections.DOWN, dirs);

        relativeSection = renderSection.adjNorth;
        checkToAdd(renderSection, relativeSection, (byte) GraphDirections.NORTH, (byte) GraphDirections.SOUTH, dirs);

        relativeSection = renderSection.adjSouth;
        checkToAdd(renderSection, relativeSection, (byte) GraphDirections.SOUTH, (byte) GraphDirections.NORTH, dirs);

        relativeSection = renderSection.adjWest;
        checkToAdd(renderSection, relativeSection, (byte) GraphDirections.WEST, (byte) GraphDirections.EAST, dirs);

        relativeSection = renderSection.adjEast;
        checkToAdd(renderSection, relativeSection, (byte) GraphDirections.EAST, (byte) GraphDirections.WEST, dirs);
    }

    private void checkToAdd(RenderSection renderSection, RenderSection relativeSection, byte dir, byte opposite, byte dirs) {
        if ((dirs & (1 << dir)) != 0) {
            addNode(renderSection, relativeSection, dir, opposite);
        }
    }

    private void updateRenderChunksSpectator() {
        while (this.sectionQueue.hasNext()) {
            RenderSection renderSection = this.sectionQueue.poll();

            if (notInFrustum(renderSection))
                continue;

            renderSection.lastVisibleFrame = this.lastFrame; // Iroh P1-6b: IrohApi.isSectionVisible

            if (!renderSection.isCompletelyEmpty()) {
                renderSection.getChunkArea().sectionQueue.add(renderSection);
                this.chunkAreaQueue.add(renderSection.getChunkArea());
                this.nonEmptyChunks++;
            }

            countUnbuilt(renderSection);

            if (renderSection.isDirty()) {
                this.rebuildQueue.ensureCapacity(1);
                this.rebuildQueue.add(renderSection);
            }

            byte dirs = (byte) (renderSection.adjDirs & renderSection.getDirections());

            visitAdjacentNodes(renderSection, dirs);
        }
    }

    private void addNode(RenderSection renderSection, RenderSection relativeSection, byte direction, byte opposite) {
        if (relativeSection.getLastFrame() != this.lastFrame) {
            relativeSection.setLastFrame(this.lastFrame);

            relativeSection.mainDir = direction;
            relativeSection.sourceDirs = (byte) (1 << direction);

            final byte steps = (byte) (renderSection.steps + 1);
            relativeSection.directionChanges = (byte) (steps < 10 ? 0 : 127);
            relativeSection.steps = steps;

            relativeSection.directions = (byte) (renderSection.directions & ~(1 << opposite));
            this.sectionQueue.add(relativeSection);
        }

        relativeSection.addDir(direction);

        boolean increase = (renderSection.sourceDirs & 1 << direction) == 0 && !renderSection.isCompletelyEmpty();
        byte dc = increase ? (byte) (renderSection.directionChanges + 1) : renderSection.directionChanges;

        relativeSection.directionChanges = dc < relativeSection.directionChanges ? dc : relativeSection.directionChanges;
    }

    public AreaSetQueue getChunkAreaQueue() {
        return this.chunkAreaQueue;
    }

    public ResettableQueue<RenderSection> getSectionQueue() {
        return this.sectionQueue;
    }

    public ResettableQueue<RenderSection> getBlockEntitiesSections() {
        return this.blockEntitiesSections;
    }

    public short getLastFrame() {
        return lastFrame;
    }

    public String getStatistics() {
        int totalSections = this.sectionGrid.getSectionCount();
        int sections = this.sectionQueue.size();
        int renderDistance = WorldRenderer.getInstance().getRenderDistance();
        String tasksInfo = this.taskDispatcher == null ? "null" : this.taskDispatcher.getStats();

        return String.format("Chunks: %d(%d)/%d D: %d, %s", this.nonEmptyChunks, sections, totalSections, renderDistance, tasksInfo);
    }
}

