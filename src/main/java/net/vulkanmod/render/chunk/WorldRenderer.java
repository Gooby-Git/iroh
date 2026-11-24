package net.vulkanmod.render.chunk;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import dev.elemore.iroh.chunk.ChunkDeferPolicy;
import dev.elemore.iroh.chunk.SyncWaitSet;
import dev.elemore.iroh.config.IrohOptions;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.buffer.UploadManager;
import net.vulkanmod.render.chunk.build.RenderRegionBuilder;
import net.vulkanmod.render.chunk.build.task.TaskDispatcher;
import net.vulkanmod.render.chunk.build.task.ChunkTask;
import net.vulkanmod.render.chunk.graph.SectionGraph;
import net.vulkanmod.render.profiling.BuildTimeProfiler;
import net.vulkanmod.render.profiling.Profiler;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.IndirectBuffer;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.*;

public class WorldRenderer {
    private static WorldRenderer INSTANCE;

    private final Minecraft minecraft;
    private ClientLevel level;
    private int renderDistance;
    private final RenderBuffers renderBuffers;

    private Vec3 cameraPos;
    private int lastCameraSectionX;
    private int lastCameraSectionY;
    private int lastCameraSectionZ;
    private float lastCameraX;
    private float lastCameraY;
    private float lastCameraZ;
    private float lastCamRotX;
    private float lastCamRotY;

    private SectionGrid sectionGrid;

    private SectionGraph sectionGraph;
    private boolean graphNeedsUpdate;
    /** perf/hitches (graph cache): a full (non translucent-resort) section upload may have changed connectivity. */
    private boolean graphStructureDirty = true;

    private final Set<BlockEntity> globalBlockEntities = Sets.newHashSet();

    private final TaskDispatcher taskDispatcher;

    private double xTransparentOld;
    private double yTransparentOld;
    private double zTransparentOld;

    IndirectBuffer[] indirectBuffers;

    public RenderRegionBuilder renderRegionCache;

    private final List<Runnable> onAllChangedCallbacks = new ObjectArrayList<>();

    private WorldRenderer(RenderBuffers renderBuffers) {
        this.minecraft = Minecraft.getInstance();
        this.renderBuffers = renderBuffers;
        this.renderRegionCache = new RenderRegionBuilder();
        this.taskDispatcher = new TaskDispatcher();
        ChunkTask.setTaskDispatcher(this.taskDispatcher);
        allocateIndirectBuffers();
        TerrainRenderType.updateMapping();

        Renderer.getInstance().addOnResizeCallback(() -> {
            if (this.indirectBuffers.length != Renderer.getFramesNum())
                allocateIndirectBuffers();
        });
    }

    private void allocateIndirectBuffers() {
        if (this.indirectBuffers != null)
            Arrays.stream(this.indirectBuffers).forEach(Buffer::scheduleFree);

        this.indirectBuffers = new IndirectBuffer[Renderer.getFramesNum()];

        for (int i = 0; i < this.indirectBuffers.length; ++i) {
            this.indirectBuffers[i] = new IndirectBuffer(1000000, MemoryTypes.HOST_MEM);
        }
    }

    public static WorldRenderer init(RenderBuffers renderBuffers) {
        if (INSTANCE != null)
            return INSTANCE;
        else
            return INSTANCE = new WorldRenderer(renderBuffers);
    }

    public static WorldRenderer getInstance() {
        return INSTANCE;
    }

    public static ClientLevel getLevel() {
        return INSTANCE.level;
    }

    public static Vec3 getCameraPos() {
        return INSTANCE.cameraPos;
    }

    private void benchCallback() {
        BuildTimeProfiler.runBench(this.graphNeedsUpdate || !this.taskDispatcher.isIdle());
    }

    private int pruneTick;

    public void setupRenderer(Camera camera, Frustum frustum, boolean isCapturedFrustum, boolean spectator) {
        if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.enter(dev.elemore.iroh.diag.SlowFrames.Phase.SETUP_TERRAIN); // perf/frame-attrib
        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Setup_Renderer");

        benchCallback();

        this.cameraPos = camera.getPosition();
        // rc7: prune chunk-status entries far outside the client chunk cache (radius renderDistance + 3) every 600 frames
        if (++this.pruneTick % 600 == 0 && ChunkStatusMap.INSTANCE != null)
            ChunkStatusMap.INSTANCE.prune(net.minecraft.core.SectionPos.blockToSectionCoord(this.cameraPos.x),
                    net.minecraft.core.SectionPos.blockToSectionCoord(this.cameraPos.z), this.renderDistance + 8);
        if (this.minecraft.options.getEffectiveRenderDistance() != this.renderDistance) {
            this.allChanged();
        }

        this.level.getProfiler().push("camera");
        float cameraX = (float) cameraPos.x();
        float cameraY = (float) cameraPos.y();
        float cameraZ = (float) cameraPos.z();
        int sectionX = SectionPos.posToSectionCoord(cameraX);
        int sectionY = SectionPos.posToSectionCoord(cameraY);
        int sectionZ = SectionPos.posToSectionCoord(cameraZ);

        profiler.push("reposition");
        if (this.lastCameraSectionX != sectionX || this.lastCameraSectionY != sectionY || this.lastCameraSectionZ != sectionZ) {
            this.lastCameraSectionX = sectionX;
            this.lastCameraSectionY = sectionY;
            this.lastCameraSectionZ = sectionZ;
            this.sectionGrid.repositionCamera(cameraX, cameraZ);
        }
        profiler.pop();

        double entityDistanceScaling = this.minecraft.options.entityDistanceScaling().get();
        Entity.setViewScale(Mth.clamp((double) this.renderDistance / 8.0D, 1.0D, 2.5D) * entityDistanceScaling);

        this.level.getProfiler().popPush("cull");
        this.minecraft.getProfiler().popPush("culling");

        this.minecraft.getProfiler().popPush("update");

        boolean cameraMoved = false;
        float d_xRot = Math.abs(camera.getXRot() - this.lastCamRotX);
        float d_yRot = Math.abs(camera.getYRot() - this.lastCamRotY);
        cameraMoved |= d_xRot > 2.0f || d_yRot > 2.0f;

        cameraMoved |= cameraX != this.lastCameraX || cameraY != this.lastCameraY || cameraZ != this.lastCameraZ;
        this.graphNeedsUpdate |= cameraMoved;

        if (!isCapturedFrustum) {
            //Debug
//            this.graphNeedsUpdate = true;

            if (this.graphNeedsUpdate) {
                this.graphNeedsUpdate = false;
                this.lastCameraX = cameraX;
                this.lastCameraY = cameraY;
                this.lastCameraZ = cameraZ;
                this.lastCamRotX = camera.getXRot();
                this.lastCamRotY = camera.getYRot();

                if (dev.elemore.iroh.diag.SlowFrames.ON) {
                    dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.GRAPH_UPDATES, 1);
                    dev.elemore.iroh.diag.SlowFrames.enter(dev.elemore.iroh.diag.SlowFrames.Phase.GRAPH_UPDATE);
                }
                // hitches2 (block 7 verify: the 100 ms refresh throttle lost up to 229 visible sections): with the graph
                // cache, ANY structural upload since the last walk forces a full walk, so a filter pass only ever runs
                // on an unchanged graph and equals a fresh full walk by construction.
                boolean refresh = !net.vulkanmod.render.chunk.graph.SectionGraph.cacheEnabled() || this.graphStructureDirty;
                this.graphStructureDirty = false;
                this.sectionGraph.update(camera, frustum, spectator, refresh);
                this.waitForImportantRebuilds(camera, frustum, spectator);
                if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.exit(dev.elemore.iroh.diag.SlowFrames.Phase.GRAPH_UPDATE);
            }
        }

        this.indirectBuffers[Renderer.getCurrentFrame()].reset();

        this.minecraft.getProfiler().pop();
        profiler.pop();
        if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.exit(dev.elemore.iroh.diag.SlowFrames.Phase.SETUP_TERRAIN);
    }

    private static final boolean DEFER_STATS = Boolean.getBoolean("iroh.deferStats");
    private long deferStatsLast;

    /**
     * Chunk build defer mode, ZERO_FRAMES half (P1-11): the important rebuilds the graph update just queued finish before
     * this frame is drawn. The render thread waits (bounded by {@link ChunkDeferPolicy#ZERO_FRAME_BUDGET_NS}), uploads what
     * arrived, and reruns the graph update so a section that was empty and now has geometry is in this frame's draw queue.
     * ONE_FRAME waits in {@link #uploadSections()} at the start of the next frame instead.
     */
    private void waitForImportantRebuilds(Camera camera, Frustum frustum, boolean spectator) {
        if (IrohOptions.chunkBuildDeferMode() != IrohOptions.DeferMode.ZERO_FRAMES)
            return;
        SyncWaitSet set = this.sectionGraph.syncSet();
        if (set.pending() == 0)
            return;

        this.minecraft.getProfiler().push("defer_zero_frames");
        set.awaitAll(ChunkDeferPolicy.budgetNs(IrohOptions.DeferMode.ZERO_FRAMES));
        boolean arrived;
        try {
            arrived = this.taskDispatcher.updateSections(true); // perf/uploads: waiting for these sections: no budget
            UploadManager.INSTANCE.submitUploads();
        } catch (Exception e) {
            Initializer.LOGGER.error(e.getMessage());
            allChanged();
            this.minecraft.getProfiler().pop();
            return;
        }
        if (arrived) {
            this.sectionGraph.setSyncAllowed(false);
            try {
                this.sectionGraph.update(camera, frustum, spectator);
            } finally {
                this.sectionGraph.setSyncAllowed(true);
            }
        }
        this.logDeferStats();
        this.minecraft.getProfiler().pop();
    }

    private void logDeferStats() {
        if (!DEFER_STATS)
            return;
        long now = System.currentTimeMillis();
        if (now - this.deferStatsLast > 5000 && this.sectionGraph != null) {
            this.deferStatsLast = now;
            Initializer.LOGGER.info("Iroh chunk defer: {}", this.sectionGraph.syncSet().stats());
        }
    }

    public void uploadSections() {
        this.taskDispatcher.adaptBuilders(); // perf/hitches: adaptive builder count from the backlog
        this.minecraft.getProfiler().push("upload");

        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Uploads");

        boolean waitedForSections = false;
        // ONE_FRAME: the rebuilds queued as important in the previous frame finish before this one is presented.
        if (this.sectionGraph != null) {
            SyncWaitSet set = this.sectionGraph.syncSet();
            IrohOptions.DeferMode mode = IrohOptions.chunkBuildDeferMode();
            if (mode == IrohOptions.DeferMode.ONE_FRAME && set.pending() > 0) {
                waitedForSections = true;
                set.awaitAll(ChunkDeferPolicy.budgetNs(mode));
                this.logDeferStats();
            } else {
                set.clear();
            }
        }

        try {
            if (waitedForSections ? this.taskDispatcher.updateSections(true) : this.taskDispatcher.updateSections()) {
                this.graphNeedsUpdate = true;
                if (this.taskDispatcher.takeStructuralUpload()) this.graphStructureDirty = true; // connectivity may have changed
            }
        } catch (Exception e) {
            Initializer.LOGGER.error(e.getMessage());
            allChanged();
        }

        profiler.pop();

        this.minecraft.getProfiler().pop();
    }

    public boolean isSectionCompiled(BlockPos blockPos) {
        RenderSection renderSection = this.sectionGrid.getSectionAtBlockPos(blockPos);
        return renderSection != null && renderSection.isCompiled();
    }

    public void allChanged() {
        if (this.level != null) {
            this.level.clearTintCaches();

            this.renderRegionCache.clear();
            this.taskDispatcher.createThreads(Initializer.CONFIG.builderThreads);

            this.graphNeedsUpdate = true;

            this.renderDistance = this.minecraft.options.getEffectiveRenderDistance();
            if (this.sectionGrid != null) {
                this.sectionGrid.freeAllBuffers();
            }

            this.taskDispatcher.clearBatchQueue();
            synchronized (this.globalBlockEntities) {
                this.globalBlockEntities.clear();
            }

            this.sectionGrid = new SectionGrid(this.level, this.renderDistance);
            this.sectionGraph = new SectionGraph(this.level, this.sectionGrid, this.taskDispatcher);

            this.onAllChangedCallbacks.forEach(Runnable::run);

            Entity entity = this.minecraft.getCameraEntity();
            if (entity != null) {
                this.sectionGrid.repositionCamera(entity.getX(), entity.getZ());
            }

        }
    }

    public void setLevel(@Nullable ClientLevel level) {
        this.lastCameraX = Float.MIN_VALUE;
        this.lastCameraY = Float.MIN_VALUE;
        this.lastCameraZ = Float.MIN_VALUE;
        this.lastCameraSectionX = Integer.MIN_VALUE;
        this.lastCameraSectionY = Integer.MIN_VALUE;
        this.lastCameraSectionZ = Integer.MIN_VALUE;

//        this.entityRenderDispatcher.setLevel(level);
        if (level == null) dev.elemore.iroh.gpu.game.GpuLifecycle.worldUnloaded(); // api 4: owner dies = Iroh frees
        this.level = level;
        ChunkStatusMap.createInstance(renderDistance);
        if (level != null) {
            dev.elemore.iroh.memory.MemoryGuard.worldStarted(); // rc9: growth rules warm up from here
            this.allChanged();
        } else {
            if (this.sectionGrid != null) {
                this.sectionGrid.freeAllBuffers();
                this.sectionGrid = null;
            }

            this.taskDispatcher.stopThreads();
            // rc5 (td-dcbf58): compare across join/quit/rejoin; steady growth per world is a real leak
            Initializer.LOGGER.info("Iroh: world unloaded; live Vulkan objects: {}", net.vulkanmod.vulkan.memory.MemoryManager.liveSummary());
            if (dev.elemore.iroh.diag.ImageTrace.ON) Initializer.LOGGER.info("Iroh: world unloaded; {}", dev.elemore.iroh.diag.ImageTrace.report()); // rc13
            dev.elemore.iroh.memory.MemoryGuard.worldUnloaded(); // rc8
            // rc6: again once this frame slot's deferred frees have run (section buffers are scheduled, not yet freed, above)
            net.vulkanmod.vulkan.memory.MemoryManager.getInstance().addFrameOp(() -> Initializer.LOGGER.info(
                    "Iroh: world unloaded, after deferred frees: {}", net.vulkanmod.vulkan.memory.MemoryManager.liveSummary()));

            this.graphNeedsUpdate = true;
        }

    }

    public void addOnAllChangedCallback(Runnable runnable) {
        this.onAllChangedCallbacks.add(runnable);
    }

    public void clearOnAllChangedCallbacks() {
        this.onAllChangedCallbacks.clear();
    }

    /** Diagnostic (td-242adb): {@code -Diroh.fogTrace=true} logs the fog inputs terrain is drawn with, once a second per layer. */
    private static final boolean FOG_TRACE = Boolean.getBoolean("iroh.fogTrace");
    private long fogTraceLast;

    private void traceFog(RenderType renderType, double camX, double camY, double camZ) {
        long now = System.currentTimeMillis();
        if (now - this.fogTraceLast < 1000 || renderType != RenderType.solid())
            return;
        this.fogTraceLast = now;
        var cam = this.minecraft.gameRenderer.getMainCamera();
        var fc = VRenderSystem.getShaderFogColor();
        Initializer.LOGGER.info("[iroh-fog] cam=({}, {}, {}) fluid={} start={} end={} shape={} fogColor=({}, {}, {}, {})",
                String.format("%.2f", camX), String.format("%.2f", camY), String.format("%.2f", camZ), cam.getFluidInCamera(),
                com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart(), com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd(),
                com.mojang.blaze3d.systems.RenderSystem.getShaderFogShape(), fc.getFloat(0), fc.getFloat(4), fc.getFloat(8), fc.getFloat(12));
    }

    private static final boolean DEBUG_TERRAIN_NO_CULL = Boolean.getBoolean("iroh.debug.terrainNoCull");

    // ------------------------------------------------------------------------------------------------ Iroh P3-6

    /** true while the FULL pack path draws terrain this frame: the raster terrain layers are skipped */
    public static volatile boolean packTerrainActive;

    private int[][] packShadowIdx = new int[0][];
    private int[] packShadowCount = new int[0];
    private static final int[] ALL_FACINGS = new int[512];
    static {
        Arrays.fill(ALL_FACINGS, -1);
    }
    private final int[] packMask = new int[512];
    private final int[] packLightMask = new int[512];
    private final int[] packIdx = new int[512];
    public int packShadowSections, packShadowDraws, packMainDraws;

    static String packClass(TerrainRenderType t) {
        return switch (t) {
            case SOLID -> "terrain_solid";
            case CUTOUT_MIPPED -> "terrain_cutout_mipped";
            case CUTOUT -> "terrain_cutout";
            case TRANSLUCENT, TRIPWIRE -> "terrain_translucent"; // tripwire: drawn in the translucent phase (gbuffers_water)
        };
    }

    /**
     * P3-6 FULL pack mode: the terrain as executor batches over Iroh's own buffers. Main passes: the raster path's
     * visible chunk areas and section queues with its facing masks (same visibility, same commands). Shadow pass
     * ({@code shadowView} != null): every built section whose 16^3 box lies within the shadow camera's ortho box
     * (half extent {@code shadowDistance}, depth 0.05..256), all facing buckets. Commands go into this frame's indirect
     * buffer; no raster draw happens. Render thread, inside a frame.
     */
    public void collectPackTerrain(java.util.List<dev.elemore.iroh.shaderpack.runtime.PackFrameInputs.Geometry> out,
                                   double camX, double camY, double camZ, double[] shadowView, double shadowDistance) {
        if (this.sectionGraph == null || this.indirectBuffers == null)
            return;
        IndirectBuffer ib = this.indirectBuffers[Renderer.getCurrentFrame()];
        IndexBuffer quads = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
        Vec3 cam = new Vec3(camX, camY, camZ);
        Set<TerrainRenderType> allowed = Initializer.CONFIG.uniqueOpaqueLayer ? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
        packMainDraws = 0;
        for (TerrainRenderType t : TerrainRenderType.VALUES) {
            if (!allowed.contains(t))
                continue;
            boolean translucent = t == TerrainRenderType.TRANSLUCENT;
            for (Iterator<ChunkArea> it = this.sectionGraph.getChunkAreaQueue().iterator(translucent); it.hasNext(); ) {
                ChunkArea area = it.next();
                DrawBuffers db = area.drawBuffers;
                var queue = area.sectionQueue;
                if (db.getAreaBuffer(t) == null || queue.size() == 0)
                    continue;
                int n = 0;
                for (var s = queue.iterator(translucent); s.hasNext() && n < packIdx.length; ) {
                    RenderSection section = s.next();
                    packIdx[n] = section.inAreaIndex;
                    packMask[n] = db.facingMask(cam, section, translucent);
                    n++;
                }
                packAdd(out, ib, quads, db, t, packClass(t), n, packIdx, packMask, camX, camY, camZ);
            }
        }
        if (shadowView != null && this.sectionGrid != null) {
            int areas = this.sectionGrid.getChunkAreaManager().size;
            if (packShadowIdx.length != areas) {
                packShadowIdx = new int[areas][512];
                packShadowCount = new int[areas];
            }
            Arrays.fill(packShadowCount, 0);
            packShadowSections = 0;
            double lim = shadowDistance + 13.9; // section half-diagonal
            for (RenderSection s : this.sectionGrid.sections) {
                ChunkArea a = s == null ? null : s.getChunkArea();
                if (a == null || a.index >= areas)
                    continue;
                double cx = s.xOffset + 8 - camX, cy = s.yOffset + 8 - camY, cz = s.zOffset + 8 - camZ;
                double vx = shadowView[0] * cx + shadowView[4] * cy + shadowView[8] * cz + shadowView[12];
                double vy = shadowView[1] * cx + shadowView[5] * cy + shadowView[9] * cz + shadowView[13];
                double vz = shadowView[2] * cx + shadowView[6] * cy + shadowView[10] * cz + shadowView[14];
                if (Math.abs(vx) > lim || Math.abs(vy) > lim || vz > 13.9 || vz < -256 - 13.9)
                    continue;
                int c = packShadowCount[a.index];
                if (c < 512) {
                    packShadowIdx[a.index][c] = s.inAreaIndex;
                    packShadowCount[a.index] = c + 1;
                    packShadowSections++;
                }
            }
            packShadowDraws = 0;
            // P3-7 C: only the facing buckets the light can see (+ UNDEFINED), see ShadowPlanner
            java.util.Arrays.fill(packLightMask, dev.elemore.iroh.shaderpack.runtime.ShadowPlanner.lightFacingMask(
                    dev.elemore.iroh.shaderpack.runtime.ShadowPlanner.lightDir(shadowView)));
            for (int i = 0; i < areas; i++) {
                if (packShadowCount[i] == 0)
                    continue;
                ChunkArea a = this.sectionGrid.getChunkAreaManager().getChunkArea(i);
                if (a == null)
                    continue;
                for (TerrainRenderType t : TerrainRenderType.VALUES)
                    if (allowed.contains(t) && a.drawBuffers.getAreaBuffer(t) != null)
                        packShadowDraws += packAdd(out, ib, quads, a.drawBuffers, t, "shadow:" + packClass(t), packShadowCount[i],
                                packShadowIdx[i], packLightMask, camX, camY, camZ);
            }
        }
        ib.submitUploads();
    }

    private int packAdd(java.util.List<dev.elemore.iroh.shaderpack.runtime.PackFrameInputs.Geometry> out, IndirectBuffer ib,
                        IndexBuffer quads, DrawBuffers db, TerrainRenderType t, String cls, int n, int[] idx, int[] mask,
                        double camX, double camY, double camZ) {
        int count = db.writePackCommands(n, idx, mask, t);
        if (count == 0)
            return 0;
        int stride = DrawBuffers.packCommandStride();
        ib.recordCopyCmd(org.lwjgl.system.MemoryUtil.memByteBuffer(DrawBuffers.packCommandPtr(), count * stride));
        boolean translucent = t == TerrainRenderType.TRANSLUCENT;
        long indexBuffer = translucent && db.getIndexBuffer() != null ? db.getIndexBuffer().getId() : quads.getId();
        int indexType = translucent && db.getIndexBuffer() != null ? org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16 : quads.indexType.value;
        var o = db.origin();
        out.add(new dev.elemore.iroh.shaderpack.runtime.PackFrameInputs.Geometry(cls, "wide-terrain", null, 0,
                new double[]{o.x - camX, o.y - camY, o.z - camZ},
                new dev.elemore.iroh.shaderpack.runtime.PackFrameInputs.Indirect(db.getAreaBuffer(t).getId(), indexBuffer, indexType,
                        ib.getId(), ib.getOffset(), count, stride)));
        if (!cls.startsWith("shadow:"))
            packMainDraws += count;
        return count;
    }

    public void renderSectionLayer(RenderType renderType, double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection) {
        if (Renderer.recordingCommandBuffer("renderSectionLayer") == null) // rc3r2 (td-7937f0): no frame recording
            return;
        // Iroh P3-6: the FULL pack path drew the terrain this frame (raster sections skipped; the pack output and
        // depth were copied into the scene target before entities)
        if (packTerrainActive && TerrainRenderType.get(renderType) != null)
            return;
        if (dev.elemore.iroh.diag.SlowFrames.ON) { // perf/frame-attrib
            dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.LAYERS, 1);
            dev.elemore.iroh.diag.SlowFrames.enter(dev.elemore.iroh.diag.SlowFrames.Phase.TERRAIN_DRAW);
        }
        TerrainRenderType terrainRenderType = TerrainRenderType.get(renderType);
        renderType.setupRenderState();
        if (terrainRenderType == TerrainRenderType.SOLID) dev.elemore.iroh.apiimpl.Sections.captureFog(); // api 4: IrohApi.fogState()
        // td-242adb experiment: -Diroh.debug.terrainNoCull=true draws terrain without GPU back-face culling (pair it with
        // iroh.json "backFaceCulling": false for the per-facing CPU buckets). If the powder-snow column then looks like the
        // OpenGL reference, OpenGL is showing the column's own faces from inside.
        if (DEBUG_TERRAIN_NO_CULL)
            net.vulkanmod.vulkan.VRenderSystem.disableCull();
        if (FOG_TRACE)
            traceFog(renderType, camX, camY, camZ);

        this.sortTranslucentSections(camX, camY, camZ);

        this.minecraft.getProfiler().push("filterempty");
        this.minecraft.getProfiler().popPush(() -> "render_" + renderType);

        final boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;
        final boolean indirectDraw = net.vulkanmod.vulkan.device.IndirectDrawCaps.useTerrainIndirect(); // P2-3: option AND device tier (firstInstance)

        VRenderSystem.applyMVP(modelView, projection);
        VRenderSystem.setPrimitiveTopologyGL(GL11.GL_TRIANGLES);

        Renderer renderer = Renderer.getInstance();
        GraphicsPipeline pipeline = PipelineManager.getTerrainShader(terrainRenderType);
        renderer.bindGraphicsPipeline(pipeline);

        VTextureSelector.bindShaderTextures(pipeline);

        IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
        Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(), indexBuffer, indexBuffer.indexType.value);

        int currentFrame = Renderer.getCurrentFrame();
        Set<TerrainRenderType> allowedRenderTypes = Initializer.CONFIG.uniqueOpaqueLayer ? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
        if (allowedRenderTypes.contains(terrainRenderType)) {
            terrainRenderType.setCutoutUniform();

            for (Iterator<ChunkArea> iterator = this.sectionGraph.getChunkAreaQueue().iterator(isTranslucent); iterator.hasNext(); ) {
                ChunkArea chunkArea = iterator.next();
                var queue = chunkArea.sectionQueue;
                DrawBuffers drawBuffers = chunkArea.drawBuffers;

                renderer.uploadAndBindUBOs(pipeline);
                if (drawBuffers.getAreaBuffer(terrainRenderType) != null && queue.size() > 0) {

                    drawBuffers.bindBuffers(Renderer.getCommandBuffer(), pipeline, terrainRenderType, camX, camY, camZ);
                    renderer.uploadAndBindUBOs(pipeline);

                    if (indirectDraw)
                        drawBuffers.buildDrawBatchesIndirect(cameraPos, indirectBuffers[currentFrame], queue, terrainRenderType);
                    else
                        drawBuffers.buildDrawBatchesDirect(cameraPos, queue, terrainRenderType);
                }
            }
        }

        if (terrainRenderType == TerrainRenderType.CUTOUT || terrainRenderType == TerrainRenderType.TRIPWIRE) {
            indirectBuffers[currentFrame].submitUploads();
//            uniformBuffers.submitUploads();
        }

        //Need to reset push constants in case the pipeline will still be used for rendering
        if (!indirectDraw) {
            VRenderSystem.setModelOffset(0, 0, 0);
            renderer.pushConstants(pipeline);
        }

        this.minecraft.getProfiler().pop();
        renderType.clearRenderState();
        if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.exit(dev.elemore.iroh.diag.SlowFrames.Phase.TERRAIN_DRAW);
    }

    private void sortTranslucentSections(double camX, double camY, double camZ) {
        this.minecraft.getProfiler().push("translucent_sort");
        if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.enter(dev.elemore.iroh.diag.SlowFrames.Phase.SORT); // perf/frame-attrib
        double d0 = camX - this.xTransparentOld;
        double d1 = camY - this.yTransparentOld;
        double d2 = camZ - this.zTransparentOld;
//        if (d0 * d0 + d1 * d1 + d2 * d2 > 1.0D) {
        if (d0 * d0 + d1 * d1 + d2 * d2 > 2.0D) {
            this.xTransparentOld = camX;
            this.yTransparentOld = camY;
            this.zTransparentOld = camZ;
            int j = 0;

            Iterator<RenderSection> iterator = this.sectionGraph.getSectionQueue().iterator(false);

            while (iterator.hasNext() && j < 15) {
                RenderSection section = iterator.next();

                section.resortTransparency(this.taskDispatcher);

                ++j;
            }
            if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.SORTED, j);
        }

        if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.exit(dev.elemore.iroh.diag.SlowFrames.Phase.SORT);
        this.minecraft.getProfiler().pop();
    }

    public void renderBlockEntities(PoseStack poseStack, double camX, double camY, double camZ,
                                    Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress, float gameTime) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
        profiler.push("Block-entities");
        if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.enter(dev.elemore.iroh.diag.SlowFrames.Phase.BLOCK_ENTITIES); // perf/frame-attrib
        try {
            renderBlockEntities0(poseStack, camX, camY, camZ, destructionProgress, gameTime);
        } finally {
            if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.exit(dev.elemore.iroh.diag.SlowFrames.Phase.BLOCK_ENTITIES);
        }
    }

    private void renderBlockEntities0(PoseStack poseStack, double camX, double camY, double camZ,
                                      Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress, float gameTime) {
        MultiBufferSource bufferSource = this.renderBuffers.bufferSource();

        for (RenderSection renderSection : this.sectionGraph.getBlockEntitiesSections()) {
            List<BlockEntity> list = renderSection.getCompiledSection().getBlockEntities();
            if (!list.isEmpty()) {
                for (BlockEntity blockEntity : list) {
                    BlockPos blockPos = blockEntity.getBlockPos();
                    MultiBufferSource bufferSource1 = bufferSource;
                    poseStack.pushPose();
                    poseStack.translate((double) blockPos.getX() - camX, (double) blockPos.getY() - camY, (double) blockPos.getZ() - camZ);
                    SortedSet<BlockDestructionProgress> sortedset = destructionProgress.get(blockPos.asLong());
                    if (sortedset != null && !sortedset.isEmpty()) {
                        int j1 = sortedset.last().getProgress();
                        if (j1 >= 0) {
                            PoseStack.Pose pose = poseStack.last();
                            VertexConsumer vertexconsumer = new SheetedDecalTextureGenerator(this.renderBuffers.crumblingBufferSource().getBuffer(ModelBakery.DESTROY_TYPES.get(j1)), pose, 1.0f);
                            bufferSource1 = (renderType) -> {
                                VertexConsumer vertexConsumer2 = bufferSource.getBuffer(renderType);
                                return renderType.affectsCrumbling() ? VertexMultiConsumer.create(vertexconsumer, vertexConsumer2) : vertexConsumer2;
                            };
                        }
                    }

                    this.minecraft.getBlockEntityRenderDispatcher().render(blockEntity, gameTime, poseStack, bufferSource1);
                    if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.BLOCK_ENTITIES, 1);
                    poseStack.popPose();
                }
            }
        }
    }

    /** Chunk (x, z) turned render-ready (ChunkStatusMap): one graph update, only if sections are parked waiting for it. */
    public void chunkBecameRenderReady(int chunkX, int chunkZ) {
        if (this.sectionGraph != null && this.sectionGraph.waiting().chunkReady(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ)))
            this.graphNeedsUpdate = true;
    }

    /** The refused-rebuild bookkeeping of the current section graph, or null before the first allChanged. */
    public static dev.elemore.iroh.chunk.RebuildWaiting rebuildWaiting() {
        WorldRenderer wr = INSTANCE;
        return wr != null && wr.sectionGraph != null ? wr.sectionGraph.waiting() : null;
    }

    public void scheduleGraphUpdate() {
        this.graphNeedsUpdate = true;
    }

    /**
     * Truthful "everything in view is built" (P1-8b, td-9f5b8e): no graph update pending, no build task queued or running,
     * no compile result waiting for upload, and no reachable section that can be built but has no compiled result.
     */
    public boolean hasRenderedAllSections() {
        return this.sectionGraph != null && !this.graphNeedsUpdate && this.taskDispatcher.isIdle()
                && this.sectionGraph.getUnbuiltSections() == 0;
    }

    public boolean graphNeedsUpdate() {
        return this.graphNeedsUpdate;
    }

    public int getVisibleSectionsCount() {
        return this.sectionGraph.getSectionQueue().size();
    }

    public void setSectionDirty(int x, int y, int z, boolean flag) {
        // hitches4: count only. Block 8: timing each call (tens of thousands per frame: every set block and each
        // section of a loaded chunk with its neighbours) made the tool's own exit() show up in 98 slow-frame samples.
        if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.DIRTY_MARKS, 1);
        this.sectionGrid.setDirty(x, y, z, flag);

        this.renderRegionCache.remove(x, z);
    }

    /**
     * P2-4 (iroh.chunk.bulkDirty): LevelRenderer.setSectionDirtyWithNeighbors as one call: the same 27 sections marked
     * and the same 9 region-cache columns dropped as 27 {@link #setSectionDirty} calls (each dropped once, not 3 times).
     */
    public void setSectionDirtyWithNeighbors(int x, int y, int z) {
        if (dev.elemore.iroh.diag.SlowFrames.ON) dev.elemore.iroh.diag.SlowFrames.count(dev.elemore.iroh.diag.SlowFrames.Counter.DIRTY_MARKS, 27);
        this.sectionGrid.setDirtyNeighbours(x, y, z);
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++)
                this.renderRegionCache.remove(x + dx, z + dz);
    }

    public SectionGrid getSectionGrid() {
        return this.sectionGrid;
    }

    public ChunkAreaManager getChunkAreaManager() {
        return this.sectionGrid != null ? this.sectionGrid.chunkAreaManager : null; // rc7: no NPE between worlds
    }

    public TaskDispatcher getTaskDispatcher() {
        return taskDispatcher;
    }

    public short getLastFrame() {
        return this.sectionGraph.getLastFrame();
    }

    public int getRenderDistance() {
        return this.renderDistance;
    }

    public String getChunkStatistics() {
        return this.sectionGraph.getStatistics();
    }

    public void cleanUp() {
        if (indirectBuffers != null)
            Arrays.stream(indirectBuffers).forEach(Buffer::scheduleFree);
    }

}
