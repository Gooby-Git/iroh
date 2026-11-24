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

    /**
     * perf/hitches: visibility cached per camera section (-Diroh.graph.cache=true, default off). A FULL update walks the
     * occlusion graph from the camera section without frustum pruning (in all directions) and keeps the visited list; any
     * other update only re-applies the frustum to that list (same per-section work: draw queues, block entities, dirty
     * sections). The walk itself does not depend on where the camera is inside its section or where it looks, so between
     * full updates the result equals a fresh full update (and is a superset of the old frustum-pruned walk, which could
     * not reach a visible section through a path leaving the frustum). Full updates happen synchronously when the camera
     * enters another section, the cull mode changes, or the caller passes {@code structureRefresh} (sections uploaded or
     * edited: connectivity may have changed). Turning, even 180 degrees, is a filter pass over a list that already
     * holds every direction: no hole, no frame of lag.
     */
    /** Runtime switch (PerfSwitches iroh.graph.cache), read once per update into {@link #cacheOn}. */
    public static boolean cacheEnabled() {
        return dev.elemore.iroh.perf.PerfSwitches.graphCacheOn; // INT-2: one volatile read
    }

    private boolean cacheOn;
    private boolean cacheValid;
    private int cacheSecX, cacheSecY, cacheSecZ;
    private boolean cacheSmartCull;
    /** Debug (-Diroh.graph.verify=true): every 120th filter pass also runs a full walk and compares the visible sets. */
    private static final boolean VERIFY = Boolean.getBoolean("iroh.graph.verify");
    private int verifyTick;

    public void update(Camera camera, Frustum frustum, boolean spectator) {
        update(camera, frustum, spectator, true);
    }

    public void update(Camera camera, Frustum frustum, boolean spectator, boolean structureRefresh) {
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

        int secX = blockpos.getX() >> 4, secY = blockpos.getY() >> 4, secZ = blockpos.getZ() >> 4;

        // P2-6: async visibility graph. Serves this frame from the worker's walk when one exists for the camera
        // section (repaired for openings), else falls through to the synchronous walk below (pruned, as without it).
        boolean asyncOn = asyncEnabled();
        if (asyncOn || this.async != null)
            asyncSwitch(asyncOn);
        if (asyncOn && flag) {
            RenderSection cam = this.sectionGrid.getSectionAtBlockPos(blockpos);
            if (cam != null && this.async.prepare(cam.gridIndex, this.sectionGrid.repositionCount,
                    Initializer.CONFIG.advCulling - 1, System.nanoTime())) {
                if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.GRAPH_FILTER_ONLY, 1);
                this.filterAsync(this.async.front());
                if (VERIFY && ++this.verifyTick % 120 == 0) this.verifyAsync(camera);
                this.logAsyncStats();
                this.scheduleRebuilds();
                this.minecraft.getProfiler().pop();
                return;
            }
            this.logAsyncStats();
        }

        boolean wasOn = this.cacheOn;
        this.cacheOn = cacheEnabled() && !asyncOn; // the async fallback walk is the plain pruned one
        if (this.cacheOn != wasOn) this.cacheValid = false; // switched at runtime
        boolean full = !this.cacheOn || !this.cacheValid || structureRefresh || flag != this.cacheSmartCull
                || secX != this.cacheSecX || secY != this.cacheSecY || secZ != this.cacheSecZ;

        if (full) {
            this.initUpdate();
            this.initializeQueueForFullUpdate(camera);

            if (flag)
                this.updateRenderChunks();
            else
                this.updateRenderChunksSpectator();
            if (this.cacheOn) {
                this.cacheValid = true;
                this.cacheSmartCull = flag;
                this.cacheSecX = secX;
                this.cacheSecY = secY;
                this.cacheSecZ = secZ;
            }
        } else {
            if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.GRAPH_FILTER_ONLY, 1);
            this.filterCached(flag);
            if (VERIFY && ++this.verifyTick % 120 == 0) this.verifyAgainstFull(camera, flag);
        }

        this.scheduleRebuilds();

        this.minecraft.getProfiler().pop();
    }

    /** P2-6 runtime switch (PerfSwitches iroh.graph.async, default off). */
    public static boolean asyncEnabled() {
        return dev.elemore.iroh.perf.PerfSwitches.graphAsyncOn;
    }

    /** Created on first use (its scratch is two ints per section). */
    private AsyncVisibility async;
    private static final boolean ASYNC_STATS = Boolean.getBoolean("iroh.graph.asyncStats");
    private long asyncStatsLast;

    private void asyncSwitch(boolean on) {
        if (this.async == null) {
            this.async = new AsyncVisibility(new GridView(this.sectionGrid.sections), true);
        }
        if (this.async.isOn() != on)
            this.async.setOn(on);
    }

    private void logAsyncStats() {
        if (!ASYNC_STATS) return;
        long now = System.currentTimeMillis();
        if (now - this.asyncStatsLast < 5000) return;
        this.asyncStatsLast = now;
        Initializer.LOGGER.info("[iroh-graph-async] {}", this.async.stats());
    }

    /** Per-frame copy of every ChunkArea's 64 sub-cube frustum verdicts, indexed by Graph.cullKey. */
    private byte[] areaFrustum = new byte[0];

    /**
     * The frame's visible set from the worker's walk: the frustum test of SectionGraph's walk applied to the result in
     * level order, with the same per-section work. Out-of-frustum entries are rejected from {@link #areaFrustum}
     * without loading the section.
     */
    private void filterAsync(VisibilityWalk.Result r) {
        this.unbuiltSections = 0;
        ChunkAreaManager areas = this.sectionGrid.getChunkAreaManager();
        areas.resetQueues();
        this.chunkAreaQueue.clear();
        this.blockEntitiesSections.clear();
        this.rebuildQueue.clear();
        this.sectionQueue.clear();
        this.lastFrame++;
        this.nonEmptyChunks = 0;

        int areaCount = areas.size;
        if (this.areaFrustum.length != areaCount << 6) this.areaFrustum = new byte[areaCount << 6];
        byte[] fr = this.areaFrustum;
        for (int a = 0; a < areaCount; a++)
            System.arraycopy(areas.getChunkArea(a).getFrustumBuffer(), 0, fr, a << 6, 64);

        RenderSection[] sections = this.sectionGrid.sections;
        int[] order = r.order, cull = r.cull;
        int n = r.count;
        this.sectionQueue.ensureCapacity(n);
        for (int k = 0; k < n; k++) {
            int slot = order[k];
            byte res = fr[cull[slot]];
            if (res > FrustumIntersection.INTERSECT)
                continue; // outside (notInFrustum, first branch)
            RenderSection renderSection = sections[slot];
            if (res == FrustumIntersection.INTERSECT && !this.frustum.testFrustum(renderSection.xOffset, renderSection.yOffset,
                    renderSection.zOffset, renderSection.xOffset + 16, renderSection.yOffset + 16, renderSection.zOffset + 16))
                continue;
            this.sectionQueue.add(renderSection); // visible sections, nearest first (translucent resort, stats)
            this.visitVisible(renderSection, true);
        }
    }

    /** Debug (-Diroh.graph.verify=true with iroh.graph.async): the async set against a fresh synchronous walk. */
    private void verifyAsync(Camera camera) {
        java.util.Set<RenderSection> served = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int i = 0; i < this.sectionQueue.size(); i++) {
            RenderSection s = this.sectionQueue.get(i);
            if (s.lastVisibleFrame == this.lastFrame) served.add(s);
        }
        // the synchronous pruned walk; this frame then draws its result
        this.cacheOn = false;
        this.cacheValid = false;
        this.initUpdate();
        this.initializeQueueForFullUpdate(camera);
        this.updateRenderChunks();
        int missing = 0, missingDrawable = 0, extra = 0, fresh = 0;
        for (int i = 0; i < this.sectionQueue.size(); i++) {
            RenderSection s = this.sectionQueue.get(i);
            if (s.lastVisibleFrame != this.lastFrame) continue;
            fresh++;
            if (!served.contains(s)) {
                missing++;
                if (!s.isCompletelyEmpty()) missingDrawable++;
            }
        }
        extra = served.size() - (fresh - missing);
        Initializer.LOGGER.info("[iroh-graph-verify] async filter vs sync walk: visible {} vs {}, missing {} (drawable {}), extra {}; {}",
                served.size(), fresh, missing, missingDrawable, extra, this.async.stats());
    }

    /** RenderSection grid as the async walk reads it. Plain reads except visibility (opaque: no torn long). */
    static final class GridView implements VisibilityWalk.Graph {
        private static final java.lang.invoke.VarHandle VIS;

        static {
            try {
                VIS = java.lang.invoke.MethodHandles.lookup().findVarHandle(RenderSection.class, "visibility", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final RenderSection[] s;

        GridView(RenderSection[] sections) {
            this.s = sections;
        }

        @Override
        public int size() {
            return this.s.length;
        }

        @Override
        public int adj(int i, int dir) {
            RenderSection r = this.s[i];
            RenderSection a = switch (dir) {
                case GraphDirections.DOWN -> r.adjDown;
                case GraphDirections.UP -> r.adjUp;
                case GraphDirections.NORTH -> r.adjNorth;
                case GraphDirections.SOUTH -> r.adjSouth;
                case GraphDirections.WEST -> r.adjWest;
                default -> r.adjEast;
            };
            return a == null ? -1 : a.gridIndex;
        }

        @Override
        public byte adjDirs(int i) {
            return this.s[i].adjDirs;
        }

        @Override
        public long vis(int i) {
            return (long) VIS.getOpaque(this.s[i]);
        }

        @Override
        public boolean empty(int i) {
            return this.s[i].isCompletelyEmpty();
        }

        @Override
        public int cullKey(int i) {
            RenderSection r = this.s[i];
            return (r.getChunkArea().index << 6) | (r.frustumIndex & 63);
        }
    }

    /** The cached walk is no longer valid (sections moved, world changed). */
    public void invalidateCache() {
        this.cacheValid = false;
    }

    /** Frustum pass over the cached visited list (perf/hitches). */
    private void filterCached(boolean smartCull) {
        this.unbuiltSections = 0;
        this.chunkAreaQueue.clear();
        this.sectionGrid.getChunkAreaManager().resetQueues();
        this.blockEntitiesSections.clear();
        this.rebuildQueue.clear();
        this.lastFrame++;
        this.nonEmptyChunks = 0;
        int maxDirectionsChanges = Initializer.CONFIG.advCulling - 1;
        int n = this.sectionQueue.size();
        for (int i = 0; i < n; i++) {
            RenderSection renderSection = this.sectionQueue.get(i);
            if (smartCull && renderSection.directionChanges > maxDirectionsChanges)
                continue; // not traversed by the walk either
            if (notInFrustum(renderSection))
                continue;
            this.visitVisible(renderSection, smartCull);
        }
    }

    /** The per-section work for a section in this frame's visible set (shared by the walk and the filter pass). */
    private void visitVisible(RenderSection renderSection, boolean blockEntities) {
        renderSection.lastVisibleFrame = this.lastFrame; // Iroh P1-6b: IrohApi.isSectionVisible

        if (!renderSection.isCompletelyEmpty()) {
            renderSection.getChunkArea().sectionQueue.add(renderSection);
            this.chunkAreaQueue.add(renderSection.getChunkArea());
            this.nonEmptyChunks++;
        }

        if (blockEntities && renderSection.containsBlockEntities()) { // the spectator walk never listed them
            this.blockEntitiesSections.ensureCapacity(1);
            this.blockEntitiesSections.add(renderSection);
        }

        countUnbuilt(renderSection);

        if (renderSection.isDirty()) {
            this.rebuildQueue.ensureCapacity(1);
            this.rebuildQueue.add(renderSection);
        }
    }

    /** Debug: compares this frame's filtered set with a fresh full walk (identity sets of lastVisibleFrame). */
    private void verifyAgainstFull(Camera camera, boolean smartCull) {
        java.util.Set<RenderSection> filtered = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int i = 0; i < this.sectionQueue.size(); i++) {
            RenderSection s = this.sectionQueue.get(i);
            if (s.lastVisibleFrame == this.lastFrame) filtered.add(s);
        }
        // a fresh full walk (it rebuilds the queues, so this frame then draws the fresh result)
        this.initUpdate();
        this.initializeQueueForFullUpdate(camera);
        if (smartCull) this.updateRenderChunks();
        else this.updateRenderChunksSpectator();
        int missing = 0, extra = 0;
        java.util.Set<RenderSection> fresh = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int i = 0; i < this.sectionQueue.size(); i++) {
            RenderSection s = this.sectionQueue.get(i);
            if (s.lastVisibleFrame == this.lastFrame) fresh.add(s);
        }
        for (RenderSection s : fresh) if (!filtered.contains(s)) missing++;
        for (RenderSection s : filtered) if (!fresh.contains(s)) extra++;
        Initializer.LOGGER.info("[iroh-graph-verify] cached filter vs full walk: visible {} vs {}, missing {}, extra {}",
                filtered.size(), fresh.size(), missing, extra);
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

            boolean inFrustum = !notInFrustum(renderSection);
            if (!inFrustum && !this.cacheOn)
                continue; // perf/hitches: with the cache the walk continues through it (frustum applied per frame)

            if (renderSection.directionChanges > maxDirectionsChanges)
                continue;

            if (inFrustum)
                this.visitVisible(renderSection, true);

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

    /**
     * perf/hitches: async rebuilds scheduled per graph update while the builders already have a backlog. Each scheduled
     * rebuild copies its 3x3x3 block data on the render thread NOW, so scheduling hundreds ahead of the builders cost
     * render-thread time and memory for copies that are often re-dirtied before a builder reaches them. Sections not
     * scheduled stay dirty and are picked up by the next update (nearest first: the queue is in graph order). Important
     * (waited-for) rebuilds are never limited. On: -Diroh.graph.rebuildBudget=true (default off).
     */
    static boolean rebuildBudget() {
        return dev.elemore.iroh.perf.PerfSwitches.rebuildBudgetOn; // INT-2: one volatile read
    }

    /** Pure rule (tested): how many more async rebuilds to schedule this update. */
    public static int rebuildAllowance(int pending, int activeBuilders) {
        int target = Math.max(32, activeBuilders * 24); // ~ a few frames of builder work queued ahead
        return Math.max(8, target - pending);
    }

    private void scheduleRebuilds() {
        if (dev.elemore.iroh.diag.SlowFrames.ON) {
            dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.REBUILDS_QUEUED, this.rebuildQueue.size());
            dev.elemore.iroh.diag.SlowFrames.enter(dev.elemore.iroh.diag.SlowFrames.Phase.SCHEDULE_REBUILDS);
        }
        try {
            scheduleRebuilds0();
        } finally {
            if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.exit(dev.elemore.iroh.diag.SlowFrames.Phase.SCHEDULE_REBUILDS);
        }
    }

    private void scheduleRebuilds0() {
        DeferMode mode = this.syncAllowed ? IrohOptions.chunkBuildDeferMode() : DeferMode.ALWAYS;
        boolean nearbyRule = mode != DeferMode.ALWAYS
                && this.minecraft.options.prioritizeChunkUpdates().get() == PrioritizeChunkUpdates.NEARBY;
        this.renderRegionCache.beginBatch(); // perf/hitches: block copies shared within this batch
        int asyncLeft = rebuildBudget()
                ? rebuildAllowance(this.taskDispatcher.pendingTaskCount(), this.taskDispatcher.activeBuilderLimit())
                : Integer.MAX_VALUE;
        int asyncScheduled = 0;
        boolean skippedForBudget = false;
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
                if (asyncScheduled >= asyncLeft && !retry) {
                    skippedForBudget = true;
                    continue; // perf/hitches: stays dirty; the next update (requested below) schedules it
                }
                scheduled = section.rebuildChunkAsync(this.taskDispatcher, this.renderRegionCache);
                if (scheduled) asyncScheduled++;
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
        if (skippedForBudget) {
            WorldRenderer wr = WorldRenderer.getInstance();
            if (wr != null) wr.scheduleGraphUpdate(); // the rest is scheduled by the next update, nearest first
        }
        if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.REBUILDS_SCHEDULED, asyncScheduled);

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

            boolean inFrustum = !notInFrustum(renderSection);
            if (!inFrustum && !this.cacheOn)
                continue;

            if (inFrustum)
                this.visitVisible(renderSection, false);

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

