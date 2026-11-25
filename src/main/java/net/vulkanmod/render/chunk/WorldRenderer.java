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

    /** P2-7a (iroh.terrain.mdi): the current grid's world terrain arena (null = per-area buffers), decided at allChanged */
    private net.vulkanmod.render.chunk.buffer.WorldArena worldArena;
    private final net.vulkanmod.render.chunk.buffer.TerrainCommandWriter worldWriter = new net.vulkanmod.render.chunk.buffer.TerrainCommandWriter();
    private String worldArenaDecision = "";

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
        // P2-7c: a Hi-Z retest the last frame never reached (no CUTOUT call and no later terrain layer) belongs to that frame
        if (this.terrainCull != null && this.terrainCull.retestPending()) {
            this.terrainCull.dropRetest();
            if (!this.hizDropLogged) {
                this.hizDropLogged = true;
                Initializer.LOGGER.warn("Iroh: a GPU-cull Hi-Z retest was never run (no terrain layer after the opaque ones); dropped");
            }
        }

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
            this.retireWorldArena(); // P2-7a: first, so the old grid's areas only reset their parameters
            if (this.sectionGrid != null) {
                this.sectionGrid.freeAllBuffers();
            }
            this.worldArena = this.decideWorldArena();
            net.vulkanmod.render.chunk.buffer.WorldArena.setCurrent(this.worldArena); // the new grid's DrawBuffers capture it

            this.taskDispatcher.clearBatchQueue();
            synchronized (this.globalBlockEntities) {
                this.globalBlockEntities.clear();
            }

            this.sectionGrid = new SectionGrid(this.level, this.renderDistance);
            this.sectionGraph = new SectionGraph(this.level, this.sectionGrid, this.taskDispatcher);
            // P2-7b: GPU-cull metadata for the new grid while the world arena exists on an indirect tier (filled as sections build)
            if (this.worldArena != null && net.vulkanmod.vulkan.device.IndirectDrawCaps.cullTier() != net.vulkanmod.vulkan.device.IndirectDrawCaps.CullTier.CPU) {
                this.cullData = new net.vulkanmod.render.chunk.cull.SectionCullData(this.sectionGrid.gridWidth, this.sectionGrid.gridHeight, this.level.getMinSection());
                net.vulkanmod.render.chunk.cull.SectionCullData.setCurrent(this.cullData);
            }

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
            this.retireWorldArena(); // P2-7a
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

    private void retireWorldArena() {
        if (this.worldArena != null) {
            this.worldArena.retireAll();
            this.worldArena = null;
        }
        net.vulkanmod.render.chunk.buffer.WorldArena.setCurrent(null);
        // P2-7b: the cull's metadata belongs to the grid; its GPU side goes once the frames that used it are done
        this.cullData = null;
        net.vulkanmod.render.chunk.cull.SectionCullData.setCurrent(null);
        var tc = this.terrainCull;
        this.terrainCull = null;
        this.cullPreparedSerial = Long.MIN_VALUE;
        this.hizPrevValid = false; // P2-7c
        if (tc != null)
            net.vulkanmod.vulkan.memory.MemoryManager.getInstance().addFrameOp(tc::destroyNow);
    }

    // ---- P2-7b (L5b): GPU culling of the opaque world-arena layers -------------------------------------------------

    private net.vulkanmod.render.chunk.cull.SectionCullData cullData;
    private net.vulkanmod.render.chunk.cull.TerrainCull terrainCull;
    private boolean terrainCullFailed;
    private static org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties cullMemProps;
    private final net.vulkanmod.render.chunk.buffer.TerrainCommandWriter[] cullWriters = {
            new net.vulkanmod.render.chunk.buffer.TerrainCommandWriter(), new net.vulkanmod.render.chunk.buffer.TerrainCommandWriter(),
            new net.vulkanmod.render.chunk.buffer.TerrainCommandWriter()};
    private final net.vulkanmod.render.chunk.cull.TerrainCull.Frame cullFrame = new net.vulkanmod.render.chunk.cull.TerrainCull.Frame();
    private final float[] cullKeyMvp = new float[16];
    private long cullPreparedSerial = Long.MIN_VALUE;
    private double cullKeyX, cullKeyY, cullKeyZ;
    private int cullKeyTech;
    private boolean cullPrepared;
    private int cullDrawsLast;

    /** P2-7b: the GPU culling techniques the options ask for this frame (0 = CPU path); facing needs back-face culling on */
    static int cullTechniques() {
        var c = Initializer.CONFIG;
        int t = (c.cullGpuFrustum ? net.vulkanmod.render.chunk.cull.TerrainCull.T_FRUSTUM : 0)
                | (c.cullGpuHorizon ? net.vulkanmod.render.chunk.cull.TerrainCull.T_HORIZON : 0)
                | (c.cullGpuScreenSize ? net.vulkanmod.render.chunk.cull.TerrainCull.T_SCREEN : 0)
                | (c.cullGpuFacing && c.backFaceCulling ? net.vulkanmod.render.chunk.cull.TerrainCull.T_FACING : 0);
        t |= c.cullGpuHiZ ? net.vulkanmod.render.chunk.cull.TerrainCull.T_HIZ : 0; // P2-7c
        if (t != 0 && dev.elemore.iroh.shaderpack.runtime.PackRuntime.active()) return 0; // vertex displacement is not in the boxes
        return t;
    }

    // ---- P2-7c (L5c): two-pass Hi-Z occlusion + translucent Hi-Z ----------------------------------------------------

    /** Iroh's depth convention: standard (0 near, 1 far; projections are built zZeroToOne, Matrix4fM). FSR-Q Q9/Q10 flips it here. */
    static final boolean REVERSED_Z = false;
    /** the frame whose depth the pyramid holds: its MVP, camera and depth size (pass 1 of the next frame tests against it) */
    private final float[] hizPrevMvp = new float[16];
    private double hizPrevX, hizPrevY, hizPrevZ;
    private int hizPrevW, hizPrevH;
    private boolean hizPrevValid;
    private final net.vulkanmod.render.chunk.buffer.TerrainCommandWriter cullTransWriter = new net.vulkanmod.render.chunk.buffer.TerrainCommandWriter();
    private int hizRetestCallsLast, hizTransCallsLast;
    private boolean hizTransLast, cullTransFailed, hizDropLogged;
    /** the framebuffer pass 1 was recorded for (its flagged commands are drawn into that one only) */
    private Object cullRecFb;

    interface BuiltThen {
        void run(boolean built);
    }

    /** P2-7c: the translucent layer through the GPU cull (frustum / screen size as on, Hi-Z against this frame's depth) */
    static boolean cullTranslucent() {
        var c = Initializer.CONFIG;
        return c.cullGpuHiZTranslucent && !dev.elemore.iroh.shaderpack.runtime.PackRuntime.active();
    }

    /** the frame now holds depth for Hi-Z in the main scene target (the frame's world viewport is the whole target) */
    private static net.vulkanmod.vulkan.framebuffer.SceneTarget hizTarget() {
        var fb = Renderer.getInstance().getBoundFramebuffer();
        return fb instanceof net.vulkanmod.vulkan.framebuffer.SceneTarget st ? st : null;
    }

    /**
     * P2-7c: inside an endPassRunResume break: the depth of {@code st} readable by compute, the pyramid built, {@code then}
     * recorded, the depth back in its attachment layout (the resume's pass begin finds it there). Remembers this frame as
     * the pyramid's frame for the next pass 1.
     */
    private void hizBuildInBreak(net.vulkanmod.render.chunk.cull.TerrainCull tc, net.vulkanmod.vulkan.framebuffer.SceneTarget st, long serial,
                                 double camX, double camY, double camZ, float[] mvp, BuiltThen then) {
        org.lwjgl.vulkan.VkCommandBuffer cmd = Renderer.getCommandBuffer();
        var depth = st.getDepthAttachment();
        int old = depth.getCurrentLayout();
        boolean general = old == org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL; // a feedback pass keeps it GENERAL
        int read = general ? old : org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;
        int compute = org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, fragTests = org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                | org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
        boolean built = false;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            net.vulkanmod.vulkan.texture.VulkanImage.transitionLayout(stack, cmd, depth, old, read, net.vulkanmod.vulkan.texture.VulkanImage.stagesOfLayout(old),
                    net.vulkanmod.vulkan.texture.VulkanImage.writeAccessOfLayout(old), compute, org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT);
            try {
                built = tc.buildPyramid(cmd, Renderer.getCurrentFrame(), serial, depth, depth.getId(), depth.format, read, depth.width, depth.height);
                then.run(built);
            } finally {
                net.vulkanmod.vulkan.texture.VulkanImage.transitionLayout(stack, cmd, depth, read, old, compute, 0, fragTests,
                        org.lwjgl.vulkan.VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | org.lwjgl.vulkan.VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            }
        }
        if (!built) { // the pyramid does not hold this depth (descriptor sets of this frame describe another view / size)
            this.hizPrevValid = false;
            return;
        }
        System.arraycopy(mvp, 0, this.hizPrevMvp, 0, 16); // the MVP the depth was drawn with (review P2-7c #9)
        this.hizPrevX = camX; this.hizPrevY = camY; this.hizPrevZ = camZ;
        this.hizPrevW = depth.width; this.hizPrevH = depth.height;
        this.hizPrevValid = true;
    }

    /** P2-7c: pass 1's Hi-Z inputs of the frame (prev pyramid's MVP, camera shift, sizes); false = no main scene target here */
    private boolean hizFrame(net.vulkanmod.render.chunk.cull.TerrainCull.Frame f, double camX, double camY, double camZ) {
        var st = hizTarget();
        if (st == null || st.getDepthAttachment() == null) return false;
        var depth = st.getDepthAttachment();
        if (depth.width != st.getWidth() || depth.height != st.getHeight())
            return false; // the screen mapping (viewport = target size) must address the depth's pixels
        f.depthW = depth.width;
        f.depthH = depth.height;
        f.prevValid = this.hizPrevValid && this.hizPrevW == depth.width && this.hizPrevH == depth.height;
        System.arraycopy(this.hizPrevMvp, 0, f.prevMvp, 0, 16);
        f.prevShiftX = (float) (camX - this.hizPrevX);
        f.prevShiftY = (float) (camY - this.hizPrevY);
        f.prevShiftZ = (float) (camZ - this.hizPrevZ);
        return true;
    }

    /**
     * P2-7c: after the last opaque layer (end of the CUTOUT call): pyramid of the depth pass 1 drew, the retest of what pass 1
     * culled only by the previous frame's Hi-Z, then pass 2 of every opaque layer (its own render state, as its call set it up).
     */
    private void afterOpaqueLayers(double callX, double callY, double callZ, boolean build) {
        var tc = this.terrainCull;
        var wa = this.worldArena;
        if (tc == null || wa == null || !tc.retestPending())
            return;
        // review P2-7c #3: the flagged commands were written for pass 1's camera, origin, MVP and framebuffer: drawn with those
        // (a different framebuffer bound now = another view: they cannot be drawn into it, dropped with a warning)
        if (Renderer.getInstance().getBoundFramebuffer() != this.cullRecFb) {
            tc.dropRetest();
            if (!this.hizDropLogged) {
                this.hizDropLogged = true;
                Initializer.LOGGER.warn("Iroh: GPU-cull Hi-Z retest reached on another framebuffer than pass 1; dropped");
            }
            return;
        }
        final double camX = this.cullKeyX, camY = this.cullKeyY, camZ = this.cullKeyZ;
        // the flagged commands are drawn in every case: without a pyramid of this record's depth the retest keeps all of them
        var st = build && camX == callX && camY == callY && camZ == callZ ? hizTarget() : null;
        long serial = net.vulkanmod.vulkan.texture.VulkanImage.frameSerial();
        boolean[] ok = {false};
        long mvpPtr = VRenderSystem.getMVP().ptr;
        float[] callMvp = new float[16];
        boolean mvpSwap = false;
        for (int i = 0; i < 16; i++) {
            callMvp[i] = org.lwjgl.system.MemoryUtil.memGetFloat(mvpPtr + i * 4L);
            mvpSwap |= callMvp[i] != this.cullKeyMvp[i];
        }
        var stBuild = mvpSwap ? null : st; // a different MVP: this depth is not pass 1's view
        try {
            net.vulkanmod.vulkan.texture.VulkanImage.endPassRunResume(() -> {
                if (stBuild != null) {
                    try {
                        hizBuildInBreak(tc, stBuild, serial, camX, camY, camZ, this.cullKeyMvp,
                                built -> ok[0] = tc.recordRetest(Renderer.getCommandBuffer(), Renderer.getCurrentFrame(), serial, built));
                        return;
                    } catch (RuntimeException e) { // the pyramid could not be built: the retest keeps every flagged command
                        Initializer.LOGGER.warn("Iroh: Hi-Z pyramid failed, the GPU cull's Hi-Z retest keeps everything: {}", e.toString());
                        this.hizPrevValid = false;
                    }
                }
                if (tc.retestPending())
                    ok[0] = tc.recordRetest(Renderer.getCommandBuffer(), Renderer.getCurrentFrame(), serial, false);
            });
            if (!ok[0])
                return;
            if (mvpSwap) for (int i = 0; i < 16; i++) org.lwjgl.system.MemoryUtil.memPutFloat(mvpPtr + i * 4L, this.cullKeyMvp[i]);
            try {
                drawPass2(tc, wa, camX, camY, camZ);
            } finally {
                if (mvpSwap) for (int i = 0; i < 16; i++) org.lwjgl.system.MemoryUtil.memPutFloat(mvpPtr + i * 4L, callMvp[i]);
            }
        } catch (RuntimeException e) { // review P2-7c #6: never into the game; the CPU path draws from the next frame on
            this.terrainCullFailed = true;
            Initializer.LOGGER.warn("Iroh: GPU-cull Hi-Z retest / pass 2 failed, CPU path draws from now on: {}", e.toString());
        }
    }

    private void drawPass2(net.vulkanmod.render.chunk.cull.TerrainCull tc, net.vulkanmod.render.chunk.buffer.WorldArena wa, double camX, double camY, double camZ) {
        int calls = 0;
        for (TerrainRenderType t : new TerrainRenderType[]{TerrainRenderType.CUTOUT_MIPPED, TerrainRenderType.CUTOUT}) {
            Set<TerrainRenderType> allowed = Initializer.CONFIG.uniqueOpaqueLayer ? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
            var w = this.cullWriters[t.ordinal()];
            if (!allowed.contains(t) || w.draws() == 0)
                continue;
            RenderType rt = t == TerrainRenderType.CUTOUT_MIPPED ? RenderType.cutoutMipped() : RenderType.cutout();
            rt.setupRenderState();
            try {
                GraphicsPipeline pipeline = PipelineManager.getTerrainWorldShader(t);
                Renderer renderer = Renderer.getInstance();
                renderer.bindGraphicsPipeline(pipeline);
                VTextureSelector.bindShaderTextures(pipeline);
                t.setCutoutUniform();
                calls += drawCulledLayer(wa, t, pipeline, camX, camY, camZ, w, (cmd, d) -> tc.drawRetest(cmd, t.ordinal(), d, w));
            } finally {
                rt.clearRenderState();
            }
        }
        this.hizRetestCallsLast = calls;
    }

    interface CullDraw {
        int draw(org.lwjgl.vulkan.VkCommandBuffer cmd, int d);
    }

    /** the bound world pipeline's push constant (frame origin), UBOs, quad indices, then per writer draw its vertex page and {@code draw} */
    private int drawCulledLayer(net.vulkanmod.render.chunk.buffer.WorldArena wa, TerrainRenderType t, GraphicsPipeline pipeline, double camX, double camY, double camZ,
                                net.vulkanmod.render.chunk.buffer.TerrainCommandWriter w, CullDraw draw) {
        int ox = Mth.floor(camX) >> 4, oz = Mth.floor(camZ) >> 4;
        int oy = Mth.clamp(Mth.floor(camY) >> 4, this.level.getMinSection(), this.level.getMaxSection() - 1);
        Renderer renderer = Renderer.getInstance();
        org.lwjgl.vulkan.VkCommandBuffer cmd = Renderer.getCommandBuffer();
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.ByteBuffer pc = stack.malloc(12);
            pc.putFloat(0, (float) ((ox << 4) + DrawBuffers.POS_OFFSET - camX));
            pc.putFloat(4, (float) ((oy << 4) + DrawBuffers.POS_OFFSET - camY));
            pc.putFloat(8, (float) ((oz << 4) + DrawBuffers.POS_OFFSET - camZ));
            org.lwjgl.vulkan.VK10.vkCmdPushConstants(cmd, pipeline.getLayout(), org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT, 0, pc);
        }
        renderer.uploadAndBindUBOs(pipeline);
        IndexBuffer quads = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
        Renderer.getDrawer().bindIndexBuffer(cmd, quads, quads.indexType.value);
        var vpool = wa.vertexPool(t.ordinal());
        var ipool = wa.indexPool();
        long boundVertex = 0;
        int boundIndexKey = net.vulkanmod.render.chunk.buffer.TerrainCommandWriter.QUADS;
        int calls = 0;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            long pBuf = stack.nmalloc(8, 8), pOff = stack.ncalloc(8, 1, 8);
            for (int d = 0, n = w.draws(); d < n; d++) {
                long vb = wa.bufferId(vpool, w.drawVertexPage(d));
                int key = w.drawIndexKey(d);
                long ibuf = key == net.vulkanmod.render.chunk.buffer.TerrainCommandWriter.QUADS ? quads.getId() : wa.bufferId(ipool, key);
                if (vb == 0 || ibuf == 0)
                    continue; // a page id without a page: cannot happen (TerrainCommandWriter counts orphans first)
                if (vb != boundVertex) {
                    org.lwjgl.system.MemoryUtil.memPutLong(pBuf, vb);
                    org.lwjgl.vulkan.VK10.nvkCmdBindVertexBuffers(cmd, 0, 1, pBuf, pOff);
                    boundVertex = vb;
                }
                if (key != boundIndexKey) {
                    if (key == net.vulkanmod.render.chunk.buffer.TerrainCommandWriter.QUADS)
                        Renderer.getDrawer().bindIndexBuffer(cmd, quads, quads.indexType.value);
                    else // R-P213BC: sorted translucent indices are always 16-bit
                        org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer(cmd, ibuf, 0, org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16);
                    boundIndexKey = key;
                }
                calls += draw.draw(cmd, d);
            }
        }
        return calls;
    }

    /**
     * P2-7c: the translucent layer through the GPU cull: written in the CPU path's order (areas and sections back to front)
     * with cull words, then in one pass break: the pyramid of the depth NOW (what the translucent draw is depth-tested
     * against: opaque terrain of both passes, entities), the translucent cull. False = the CPU path draws it.
     */
    private boolean drawTranslucentCulled(net.vulkanmod.render.chunk.buffer.WorldArena wa, TerrainRenderType t, GraphicsPipeline pipeline,
                                          double camX, double camY, double camZ) {
        var tc = this.terrainCullFailed || this.cullTransFailed ? null : terrainCull();
        var st = hizTarget();
        if (tc == null || st == null || st.getDepthAttachment() == null
                || st.getDepthAttachment().width != st.getWidth() || st.getDepthAttachment().height != st.getHeight())
            return false;
        int ox = Mth.floor(camX) >> 4, oz = Mth.floor(camZ) >> 4;
        int oy = Mth.clamp(Mth.floor(camY) >> 4, this.level.getMinSection(), this.level.getMaxSection() - 1);
        var w = this.cullTransWriter;
        w.beginCull(true, ox, oy, oz, net.vulkanmod.vulkan.device.DeviceManager.deviceProperties.limits().maxDrawIndirectCount());
        for (Iterator<ChunkArea> it = this.sectionGraph.getChunkAreaQueue().iterator(true); it.hasNext(); ) {
            ChunkArea chunkArea = it.next();
            if (chunkArea.sectionQueue.size() > 0 && chunkArea.drawBuffers.hasWorldLayer(t))
                chunkArea.drawBuffers.writeWorldCommandsCull(this.cameraPos, chunkArea.sectionQueue, t, w, false);
        }
        w.finish();
        this.worldDrawsLast = w.draws();
        this.worldCommandsLast = w.totalCommands();
        if (w.draws() == 0)
            return true;
        var f = new net.vulkanmod.render.chunk.cull.TerrainCull.Frame();
        long mvpPtr = VRenderSystem.getMVP().ptr;
        for (int i = 0; i < 16; i++) f.mvp[i] = org.lwjgl.system.MemoryUtil.memGetFloat(mvpPtr + i * 4L);
        f.originRelX = (float) ((ox << 4) - camX);
        f.originRelY = (float) ((oy << 4) - camY);
        f.originRelZ = (float) ((oz << 4) - camZ);
        int cx = Mth.floor(camX) >> 4, cy = Mth.floor(camY) >> 4, cz = Mth.floor(camZ) >> 4;
        f.camSecX = cx; f.camSecY = cy; f.camSecZ = cz;
        f.camFracX = (float) (camX - (cx << 4));
        f.camFracY = (float) (camY - (cy << 4));
        f.camFracZ = (float) (camZ - (cz << 4));
        f.depthW = st.getDepthAttachment().width;
        f.depthH = st.getDepthAttachment().height;
        f.vpX = 0; f.vpY = 0; f.vpW = f.depthW; f.vpH = f.depthH; // the scene target's world viewport = its depth image
        var c = Initializer.CONFIG;
        f.techniques = (c.cullGpuFrustum ? net.vulkanmod.render.chunk.cull.TerrainCull.T_FRUSTUM : 0)
                | (c.cullGpuScreenSize ? net.vulkanmod.render.chunk.cull.TerrainCull.T_SCREEN : 0) | net.vulkanmod.render.chunk.cull.TerrainCull.T_HIZ;
        long serial = net.vulkanmod.vulkan.texture.VulkanImage.frameSerial();
        boolean[] ok = {false};
        var data = this.cullData;
        net.vulkanmod.vulkan.texture.VulkanImage.endPassRunResume(() -> {
            try {
                hizBuildInBreak(tc, st, serial, camX, camY, camZ, f.mvp,
                        built -> ok[0] = tc.recordTranslucent(Renderer.getCommandBuffer(), Renderer.getCurrentFrame(), serial, f, data, w, built));
            } catch (RuntimeException e) { // never worse than the CPU path: the translucent GPU cull stays off, the opaque one is unaffected
                this.cullTransFailed = true;
                this.hizPrevValid = false;
                Initializer.LOGGER.warn("Iroh: GPU translucent cull failed, the CPU path draws translucent terrain from now on: {}", e.toString());
            }
        });
        this.hizTransLast = ok[0];
        if (!ok[0])
            return false;
        this.hizTransCallsLast = drawCulledLayer(wa, t, pipeline, camX, camY, camZ, w, (cmd, d) -> tc.drawTranslucent(cmd, d, w));
        return true;
    }

    private net.vulkanmod.render.chunk.cull.TerrainCull terrainCull() {
        var old = this.terrainCull;
        if (old != null && old.frames() != Renderer.getFramesNum()) { // frame queue size changed: per-slot staging no longer matches
            this.terrainCull = null;
            this.cullPreparedSerial = Long.MIN_VALUE;
            this.hizPrevValid = false;
            net.vulkanmod.vulkan.memory.MemoryManager.getInstance().addFrameOp(old::destroyNow);
            this.cullData.markFull();
        }
        if (this.terrainCull == null && !this.terrainCullFailed) {
            try {
                net.vulkanmod.render.chunk.cull.TerrainCull.EXTERNAL = dev.elemore.iroh.memory.MemoryGuard::addExternal;
                if (cullMemProps == null) {
                    cullMemProps = org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties.malloc(); // once per process, never freed
                    org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties(net.vulkanmod.vulkan.device.DeviceManager.physicalDevice, cullMemProps);
                }
                this.terrainCull = new net.vulkanmod.render.chunk.cull.TerrainCull(net.vulkanmod.vulkan.device.DeviceManager.vkDevice, cullMemProps,
                        Renderer.getFramesNum(), net.vulkanmod.vulkan.device.IndirectDrawCaps.cullTier() == net.vulkanmod.vulkan.device.IndirectDrawCaps.CullTier.COUNT,
                        r -> net.vulkanmod.vulkan.memory.MemoryManager.getInstance().addFrameOp(r),
                        net.vulkanmod.vulkan.device.IndirectDrawCaps.hiZPath() == net.vulkanmod.vulkan.device.IndirectDrawCaps.HiZPath.MINMAX, REVERSED_Z); // P2-7c
                this.hizPrevValid = false;
                Initializer.LOGGER.info("Iroh: GPU terrain cull ready ({})", net.vulkanmod.vulkan.device.IndirectDrawCaps.report());
            } catch (RuntimeException e) { // never worse than the CPU path: log once, keep drawing without it
                this.terrainCullFailed = true;
                Initializer.LOGGER.warn("Iroh: GPU terrain cull unavailable, CPU path draws: {}", e.toString());
            }
        }
        return this.terrainCull;
    }

    /**
     * P2-7b: one opaque layer through the GPU cull. Once per frame (frame serial, camera, MVP and techniques unchanged)
     * all three opaque layers' commands are written with cull words and the cull is recorded outside the render pass
     * (VulkanImage.endPassRunResume); each layer then draws its pages from the cull's output. False = draw the layer
     * through the CPU path (cull not available or not recorded).
     */
    private boolean drawWorldLayerCulled(net.vulkanmod.render.chunk.buffer.WorldArena wa, TerrainRenderType t, GraphicsPipeline pipeline,
                                         double camX, double camY, double camZ, int tech) {
        var tc = this.terrainCullFailed ? null : terrainCull();
        if (tc == null)
            return false;
        int ox = Mth.floor(camX) >> 4, oz = Mth.floor(camZ) >> 4;
        int oy = Mth.clamp(Mth.floor(camY) >> 4, this.level.getMinSection(), this.level.getMaxSection() - 1);
        long serial = net.vulkanmod.vulkan.texture.VulkanImage.frameSerial();
        long mvpPtr = VRenderSystem.getMVP().ptr;
        boolean same = this.cullPreparedSerial == serial && this.cullKeyX == camX && this.cullKeyY == camY && this.cullKeyZ == camZ && this.cullKeyTech == tech;
        for (int i = 0; i < 16 && same; i++) same = this.cullKeyMvp[i] == org.lwjgl.system.MemoryUtil.memGetFloat(mvpPtr + i * 4L);
        if (!same && tc.retestPending()) {
            // P2-7c: a second record in one frame (camera / MVP / serial changed between opaque layers) while the first one's
            // Hi-Z flags were never retested: draw all of them now (keep-all retest), then restore this layer's state
            this.afterOpaqueLayers(camX, camY, camZ, false);
            RenderType rt = t == TerrainRenderType.CUTOUT_MIPPED ? RenderType.cutoutMipped() : t == TerrainRenderType.CUTOUT ? RenderType.cutout() : RenderType.solid();
            rt.setupRenderState();
            Renderer.getInstance().bindGraphicsPipeline(pipeline);
            VTextureSelector.bindShaderTextures(pipeline);
            t.setCutoutUniform();
        }
        if (!same) {
            int maxDraws = net.vulkanmod.vulkan.device.DeviceManager.deviceProperties.limits().maxDrawIndirectCount();
            boolean allFacings = (tech & net.vulkanmod.render.chunk.cull.TerrainCull.T_FACING) != 0;
            for (int l = 0; l < this.cullWriters.length; l++) {
                var w = this.cullWriters[l];
                TerrainRenderType lt = TerrainRenderType.VALUES[l];
                w.beginCull(ox, oy, oz, maxDraws);
                for (Iterator<ChunkArea> it = this.sectionGraph.getChunkAreaQueue().iterator(false); it.hasNext(); ) {
                    ChunkArea chunkArea = it.next();
                    if (chunkArea.sectionQueue.size() > 0 && chunkArea.drawBuffers.hasWorldLayer(lt))
                        chunkArea.drawBuffers.writeWorldCommandsCull(this.cameraPos, chunkArea.sectionQueue, lt, w, allFacings);
                }
                w.finish();
            }
            var f = this.cullFrame;
            for (int i = 0; i < 16; i++) f.mvp[i] = this.cullKeyMvp[i] = org.lwjgl.system.MemoryUtil.memGetFloat(mvpPtr + i * 4L);
            f.originRelX = (float) ((ox << 4) - camX);
            f.originRelY = (float) ((oy << 4) - camY);
            f.originRelZ = (float) ((oz << 4) - camZ);
            int cx = Mth.floor(camX) >> 4, cy = Mth.floor(camY) >> 4, cz = Mth.floor(camZ) >> 4;
            f.camSecX = cx; f.camSecY = cy; f.camSecZ = cz;
            f.camFracX = (float) (camX - (cx << 4));
            f.camFracY = (float) (camY - (cy << 4));
            f.camFracZ = (float) (camZ - (cz << 4));
            // screen size: the main scene target only (its world viewport is the whole target); elsewhere the test is off
            var fb = Renderer.getInstance().getBoundFramebuffer();
            int techFrame = tech;
            if (fb instanceof net.vulkanmod.vulkan.framebuffer.SceneTarget) {
                f.vpX = 0; f.vpY = 0; f.vpW = fb.getWidth(); f.vpH = fb.getHeight();
            } else techFrame &= ~net.vulkanmod.render.chunk.cull.TerrainCull.T_SCREEN;
            // P2-7c: Hi-Z only against the main scene depth (the pass-2 retest at the end of the opaque layers builds from it)
            if ((techFrame & net.vulkanmod.render.chunk.cull.TerrainCull.T_HIZ) != 0 && !hizFrame(f, camX, camY, camZ))
                techFrame &= ~net.vulkanmod.render.chunk.cull.TerrainCull.T_HIZ;
            f.techniques = techFrame;
            boolean[] ok = {false};
            var data = this.cullData;
            if (techFrame != 0)
                net.vulkanmod.vulkan.texture.VulkanImage.endPassRunResume(() -> {
                    try {
                        ok[0] = tc.record(Renderer.getCommandBuffer(), Renderer.getCurrentFrame(), serial, f, data, this.cullWriters);
                    } catch (RuntimeException e) { // e.g. device memory for the cull buffers: never worse than the CPU path
                        this.terrainCullFailed = true;
                        Initializer.LOGGER.warn("Iroh: GPU terrain cull failed, CPU path draws from now on: {}", e.toString());
                    }
                });
            this.cullPrepared = ok[0];
            this.cullPreparedSerial = serial;
            this.cullKeyX = camX; this.cullKeyY = camY; this.cullKeyZ = camZ;
            this.cullRecFb = Renderer.getInstance().getBoundFramebuffer(); // P2-7c
            this.cullKeyTech = tech;
        }
        if (!this.cullPrepared)
            return false;

        int layer = t.ordinal();
        var w = this.cullWriters[layer];
        int draws = w.draws();
        this.worldDrawsLast = draws;
        this.worldCommandsLast = w.totalCommands();
        if (draws == 0)
            return true;
        Renderer renderer = Renderer.getInstance();
        org.lwjgl.vulkan.VkCommandBuffer cmd = Renderer.getCommandBuffer();
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.ByteBuffer pc = stack.malloc(12);
            pc.putFloat(0, (float) ((ox << 4) + DrawBuffers.POS_OFFSET - camX));
            pc.putFloat(4, (float) ((oy << 4) + DrawBuffers.POS_OFFSET - camY));
            pc.putFloat(8, (float) ((oz << 4) + DrawBuffers.POS_OFFSET - camZ));
            org.lwjgl.vulkan.VK10.vkCmdPushConstants(cmd, pipeline.getLayout(), org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT, 0, pc);
        }
        renderer.uploadAndBindUBOs(pipeline);
        IndexBuffer quads = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
        Renderer.getDrawer().bindIndexBuffer(cmd, quads, quads.indexType.value); // opaque layers draw with the quad indices only
        var vpool = wa.vertexPool(layer);
        long boundVertex = 0;
        int calls = 0;
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            long pBuf = stack.nmalloc(8, 8), pOff = stack.ncalloc(8, 1, 8);
            for (int d = 0; d < draws; d++) {
                long vb = wa.bufferId(vpool, w.drawVertexPage(d));
                if (vb == 0 || w.drawIndexKey(d) != net.vulkanmod.render.chunk.buffer.TerrainCommandWriter.QUADS)
                    continue; // cannot happen for opaque layers (no sorted indices; TerrainCommandWriter counts orphans first)
                if (vb != boundVertex) {
                    org.lwjgl.system.MemoryUtil.memPutLong(pBuf, vb);
                    org.lwjgl.vulkan.VK10.nvkCmdBindVertexBuffers(cmd, 0, 1, pBuf, pOff);
                    boundVertex = vb;
                }
                calls += tc.drawCulled(cmd, layer, d, w);
            }
        }
        this.cullDrawsLast = calls;
        return true;
    }

    /**
     * P2-7a: world-arena terrain for the next grid when the switch is on AND the device draws terrain indirectly with
     * firstInstance (tier INDIRECT; T-C devices keep the per-area direct draws; the user's indirectDraw option is not
     * consulted: the explicit switch wins and the path is valid on the tier) AND not pack FULL mode AND the default compressed-vertex terrain
     * program draws (no pack FULL mode, no foreign terrain shader) AND every section offset fits terrain_world.vsh's
     * 8 signed bits (render distance <= 126, world height <= 127 sections). Logged when the decision changes.
     */
    private net.vulkanmod.render.chunk.buffer.WorldArena decideWorldArena() {
        String why;
        if (!dev.elemore.iroh.perf.PerfSwitches.terrainMdiOn) why = null;
        else if (net.vulkanmod.vulkan.device.IndirectDrawCaps.tier() != net.vulkanmod.vulkan.device.IndirectDrawCaps.TerrainTier.INDIRECT)
            why = "device draws terrain directly (" + net.vulkanmod.vulkan.device.IndirectDrawCaps.report() + ")";
        else if (dev.elemore.iroh.shaderpack.runtime.PackStartup.FULL) // collectPackTerrain reads per-area buffers (restart-only mode)
            why = "pack FULL mode draws terrain from the per-area buffers";
        else if (!PipelineManager.terrainWorldAvailable()) why = "terrain_world pipelines unavailable (non-compressed vertex format or another terrain shader)";
        else if (this.level.getSectionsCount() > 127 || this.renderDistance > 126)
            why = "section offsets exceed 8 bits (height " + this.level.getSectionsCount() + " sections, render distance " + this.renderDistance + ")";
        else why = "";
        String line = why == null ? "" : why.isEmpty() ? "on: one vertex bind + multi-draw-indirect per page and layer"
                : "off (switch on, but " + why + "): per-area draws";
        if (!line.equals(this.worldArenaDecision)) {
            this.worldArenaDecision = line;
            if (!line.isEmpty()) Initializer.LOGGER.info("Iroh: terrain mdi {}", line);
        }
        if (why == null || !why.isEmpty()) return null;
        return net.vulkanmod.render.chunk.buffer.WorldArena.forGame(net.vulkanmod.render.chunk.buffer.WorldArenaGameOps.INSTANCE,
                TerrainRenderType.VALUES.length, DrawBuffers.VERTEX_SIZE);
    }

    /** P2-7a: memStats / F3 line of the world arena, or null when the per-area path draws */
    public String worldArenaStats() {
        var w = this.worldArena;
        String s = w == null ? null : w.stats() + ", last layer draws " + this.worldDrawsLast + ", commands " + this.worldCommandsLast
                + (this.worldWriter.outOfRange + this.worldWriter.orphans > 0 ? ", skipped " + this.worldWriter.outOfRange + "/" + this.worldWriter.orphans : "");
        var tc = this.terrainCull;
        if (s != null && tc != null && this.cullPrepared && cullTechniques() != 0) // P2-7b: GPU cull of the last frame (kept = read back two frames later, COUNT tier)
            s += ", gpu cull tech " + this.cullKeyTech + " " + (tc.compact() ? "count" : "fixed") + ": " + tc.segments() + " segments, " + this.cullDrawsLast
                    + " indirect calls (last layer), candidates " + tc.commands()
                    + (tc.keptLast() >= 0 ? ", kept " + tc.keptLast() + "/" + tc.candidatesLast() : "")
                    // P2-7c: Hi-Z path, commands pass 1 culled only by Hi-Z and how many of them the retest drew (disocclusions)
                    + ((this.cullKeyTech & net.vulkanmod.render.chunk.cull.TerrainCull.T_HIZ) != 0 ? ", hiz " + (tc.hiz().minmax() ? "minmax" : "shader")
                    + (tc.hizFlaggedLast() >= 0 ? " culled " + (tc.hizFlaggedLast() - tc.hizRetestKeptLast()) + " retest drew " + tc.hizRetestKeptLast() : "")
                    + (tc.retestPyramidOk ? "" : " (retest kept all)") + ", pass-2 calls " + this.hizRetestCallsLast : "");
        if (s != null && tc != null && cullTranslucent())
            s += ", translucent gpu cull " + (this.hizTransLast ? this.hizTransCallsLast + " calls, candidates " + tc.translucentCommands() : "off this frame (CPU path)");
        return s;
    }

    private int worldDrawsLast, worldCommandsLast;

    /**
     * P2-7a: one layer from the world arena. Same visible areas, sections, order and facing masks as the per-area loop
     * (TerrainCommandWriter documents the order rule), but the pipeline (terrain_world), UBOs, push constant (frame
     * origin) and quad index buffer are bound ONCE, then per draw (one per vertex page, or per page run when the layer
     * blends) a vertex bind, a command copy into the frame's indirect buffer and one vkCmdDrawIndexedIndirect.
     */
    private void drawWorldLayer(net.vulkanmod.render.chunk.buffer.WorldArena wa, TerrainRenderType t, GraphicsPipeline pipeline,
                                double camX, double camY, double camZ, int currentFrame) {
        if (this.cullData != null && t.ordinal() < net.vulkanmod.render.chunk.cull.SectionCullData.OPAQUE_LAYERS) { // P2-7b
            int tech = cullTechniques();
            if (tech != 0 && this.drawWorldLayerCulled(wa, t, pipeline, camX, camY, camZ, tech))
                return;
        }
        if (this.cullData != null && t == TerrainRenderType.TRANSLUCENT && cullTranslucent() // P2-7c
                && this.drawTranslucentCulled(wa, t, pipeline, camX, camY, camZ))
            return;
        var w = this.worldWriter;
        boolean isTranslucent = t == TerrainRenderType.TRANSLUCENT;
        int ox = Mth.floor(camX) >> 4, oz = Mth.floor(camZ) >> 4;
        int oy = Mth.clamp(Mth.floor(camY) >> 4, this.level.getMinSection(), this.level.getMaxSection() - 1);
        w.begin(isTranslucent || t == TerrainRenderType.TRIPWIRE, ox, oy, oz,
                net.vulkanmod.vulkan.device.DeviceManager.deviceProperties.limits().maxDrawIndirectCount());
        for (Iterator<ChunkArea> iterator = this.sectionGraph.getChunkAreaQueue().iterator(isTranslucent); iterator.hasNext(); ) {
            ChunkArea chunkArea = iterator.next();
            if (chunkArea.sectionQueue.size() > 0 && chunkArea.drawBuffers.hasWorldLayer(t)) // = the per-area getAreaBuffer(t) != null skip
                chunkArea.drawBuffers.writeWorldCommands(this.cameraPos, chunkArea.sectionQueue, t, w);
        }
        w.finish();
        int draws = w.draws();
        this.worldDrawsLast = draws;
        this.worldCommandsLast = w.totalCommands();
        if (draws == 0)
            return;

        Renderer renderer = Renderer.getInstance();
        org.lwjgl.vulkan.VkCommandBuffer cmd = Renderer.getCommandBuffer();
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.ByteBuffer pc = stack.malloc(12); // = DrawBuffers.updateChunkAreaOrigin with the frame origin as the "area"
            pc.putFloat(0, (float) ((ox << 4) + DrawBuffers.POS_OFFSET - camX));
            pc.putFloat(4, (float) ((oy << 4) + DrawBuffers.POS_OFFSET - camY));
            pc.putFloat(8, (float) ((oz << 4) + DrawBuffers.POS_OFFSET - camZ));
            org.lwjgl.vulkan.VK10.vkCmdPushConstants(cmd, pipeline.getLayout(), org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT, 0, pc);
        }
        renderer.uploadAndBindUBOs(pipeline);

        IndirectBuffer ib = this.indirectBuffers[currentFrame];
        final int stride = net.vulkanmod.render.chunk.buffer.TerrainCommandWriter.STRIDE;
        // all of the layer's commands written once, in place, into the frame's mapped indirect buffer
        long mapped = ib.reserveMapped(w.totalCommands() * stride);
        long base = ib.getOffset();
        if (mapped != 0) w.copyTo(mapped);
        IndexBuffer quads = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
        var vpool = wa.vertexPool(t.ordinal());
        var ipool = wa.indexPool();
        long boundVertex = 0;
        int boundIndexKey = net.vulkanmod.render.chunk.buffer.TerrainCommandWriter.QUADS; // bound at layer start
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            long pBuf = stack.nmalloc(8, 8), pOff = stack.ncalloc(8, 1, 8);
            for (int d = 0; d < draws; d++) {
                long vb = wa.bufferId(vpool, w.drawVertexPage(d));
                int key = w.drawIndexKey(d);
                long ibuf = key == net.vulkanmod.render.chunk.buffer.TerrainCommandWriter.QUADS ? quads.getId() : wa.bufferId(ipool, key);
                if (vb == 0 || ibuf == 0)
                    continue; // a page id without a page: cannot happen (TerrainCommandWriter counts orphans first)
                if (vb != boundVertex) {
                    org.lwjgl.system.MemoryUtil.memPutLong(pBuf, vb);
                    org.lwjgl.vulkan.VK10.nvkCmdBindVertexBuffers(cmd, 0, 1, pBuf, pOff);
                    boundVertex = vb;
                }
                if (key != boundIndexKey) {
                    if (key == net.vulkanmod.render.chunk.buffer.TerrainCommandWriter.QUADS)
                        Renderer.getDrawer().bindIndexBuffer(cmd, quads, quads.indexType.value);
                    else // R-P213BC: sorted translucent indices are always 16-bit
                        org.lwjgl.vulkan.VK10.vkCmdBindIndexBuffer(cmd, ibuf, 0, org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16);
                    boundIndexKey = key;
                }
                int n = w.drawCount(d);
                long off;
                if (mapped != 0) {
                    off = base + (long) w.drawFirst(d) * stride;
                } else { // indirect buffer not host-mapped: copy this draw's commands through the staging path
                    ib.recordCopyCmd(org.lwjgl.system.MemoryUtil.memByteBuffer(w.drawCommands(d), n * stride));
                    off = ib.getOffset();
                }
                org.lwjgl.vulkan.VK10.vkCmdDrawIndexedIndirect(cmd, ib.getId(), off, n, stride);
            }
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
        // P2-7c safety net: the CUTOUT call did not run after pass 1 (a mod skipped it): the Hi-Z flagged commands are drawn
        // now, all of them (the depth here holds entities, which the opaque terrain was never tested against)
        if (terrainRenderType != null && terrainRenderType.ordinal() > TerrainRenderType.CUTOUT.ordinal() && this.terrainCull != null
                && this.worldArena != null && this.terrainCull.retestPending()) {
            this.afterOpaqueLayers(camX, camY, camZ, false);
            renderType.setupRenderState();
        }

        Renderer renderer = Renderer.getInstance();
        var worldArena = this.worldArena; // P2-7a: world-arena terrain (null = per-area buffers)
        GraphicsPipeline pipeline = worldArena != null ? PipelineManager.getTerrainWorldShader(terrainRenderType) : PipelineManager.getTerrainShader(terrainRenderType);
        renderer.bindGraphicsPipeline(pipeline);

        VTextureSelector.bindShaderTextures(pipeline);

        IndexBuffer indexBuffer = Renderer.getDrawer().getQuadsIndexBuffer().getIndexBuffer();
        Renderer.getDrawer().bindIndexBuffer(Renderer.getCommandBuffer(), indexBuffer, indexBuffer.indexType.value);

        int currentFrame = Renderer.getCurrentFrame();
        Set<TerrainRenderType> allowedRenderTypes = Initializer.CONFIG.uniqueOpaqueLayer ? TerrainRenderType.COMPACT_RENDER_TYPES : TerrainRenderType.SEMI_COMPACT_RENDER_TYPES;
        if (allowedRenderTypes.contains(terrainRenderType)) {
            terrainRenderType.setCutoutUniform();

            if (worldArena != null)
                this.drawWorldLayer(worldArena, terrainRenderType, pipeline, camX, camY, camZ, currentFrame);
            else for (Iterator<ChunkArea> iterator = this.sectionGraph.getChunkAreaQueue().iterator(isTranslucent); iterator.hasNext(); ) {
                ChunkArea chunkArea = iterator.next();
                var queue = chunkArea.sectionQueue;
                DrawBuffers drawBuffers = chunkArea.drawBuffers;

                // Upstream VulkanMod 5a80b35 (1.21.11 port, WorldRenderer hunk): the base also uploaded and bound the
                // UBOs here, for every area and layer, drawn or not; each call writes the pipeline's UBOs into the frame
                // uniform ring and binds a descriptor set. Only areas that draw need it (the call below).
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
        if (!indirectDraw && worldArena == null) {
            VRenderSystem.setModelOffset(0, 0, 0);
            renderer.pushConstants(pipeline);
        }

        this.minecraft.getProfiler().pop();
        renderType.clearRenderState();
        // P2-7c: the last opaque layer's call (CUTOUT, made whether or not uniqueOpaqueLayer draws it): Hi-Z retest + pass 2
        if (terrainRenderType == TerrainRenderType.CUTOUT && worldArena != null && this.terrainCull != null)
            this.afterOpaqueLayers(camX, camY, camZ, true);
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

            // Upstream VulkanMod 45b97c3 ("Mitigate translucency sorting issue"): the base counted every visited section,
            // empty air included, so only the nearest 15 sections of any kind were resorted and translucent sections a
            // few sections away (water seen from above, glass) kept a stale order. Upstream counts non-empty sections up
            // to 200; Iroh follows vanilla 1.21.1 (LevelRenderer.renderSectionLayer): count only the sections that got a
            // sort task, at most 15, nearest first.
            while (iterator.hasNext() && j < 15) {
                RenderSection section = iterator.next();

                if (section.resortTransparency(this.taskDispatcher))
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
        this.retireWorldArena(); // P2-7a
        this.worldWriter.free();
        for (var cw : this.cullWriters) cw.free(); // P2-7b
        this.cullTransWriter.free(); // P2-7c
    }

}
