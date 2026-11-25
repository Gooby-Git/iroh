package dev.elemore.iroh.perf;

import dev.elemore.iroh.debug.DebugSwitches;
import dev.elemore.iroh.debug.DebugSwitches.Switch;

/**
 * perf/hitches (hitches4): the hitch switches as runtime switches (IrohDebug / DebugSwitches), for in-session ABAB
 * tests. All default off (the default jar behaves like rc14). Each is read at its point of use (once per frame / per
 * update / per batch) through a static volatile flag (INT-2; never a static final), so {@code IrohDebug.set} applies
 * within a frame or two, except where the
 * handler says {@code rebuild} (builder threads recreated) or {@code restart} (renice / affinity cannot be undone).
 */
public final class PerfSwitches {
    public static final String TASKS_BUDGET = "iroh.tasks.budget";
    public static final String GRAPH_CACHE = "iroh.graph.cache";
    /** P2-6 (L3): visibility walk on a worker, frustum per frame on the render thread (net.vulkanmod...graph.AsyncVisibility). */
    public static final String GRAPH_ASYNC = "iroh.graph.async";
    public static final String CLONE_CACHE = "iroh.graph.cloneCache";
    public static final String REBUILD_BUDGET = "iroh.graph.rebuildBudget";
    public static final String THREADS_TUNE = "iroh.threads.tune";
    public static final String THREADS_ADAPTIVE = "iroh.threads.adaptive";
    public static final String THREADS_PHYSICAL = "iroh.threads.physicalCount";
    /** P2-4: pooled area buffers (AreaArena), used-range grow copies, deferred segment free on layer reset */
    public static final String AREA_ARENA = "iroh.area.arena";
    /** P2-4: section intake stops BEFORE a section whose predicted cost crosses the budget (IntakeBudget) */
    public static final String INTAKE_BUDGET = "iroh.intake.budget";
    /** P2-4: LevelRenderer.setSectionDirtyWithNeighbors as one 3x3x3 mark (9 region-cache drops, one graph flag) */
    public static final String BULK_DIRTY = "iroh.chunk.bulkDirty";

    /**
     * P2-7a (L5a): terrain from one world arena, one vertex bind + multi-draw-indirect per page and layer (net.vulkanmod
     * ...buffer.WorldArena / TerrainCommandWriter) instead of per chunk area. Indirect-draw tier only; read at
     * WorldRenderer.allChanged, so a change reloads the chunks.
     */
    public static final String TERRAIN_MDI = "iroh.terrain.mdi";

    /** P2-11 (L8): record the pipeline states first seen in play; at load, create them on a worker before first use (default off) */
    public static final String PIPELINE_PREWARM = "iroh.pipeline.prewarm";
    /** P2-11: VK_EXT_graphics_pipeline_library path. SCAFFOLD ONLY: the path is not implemented, the switch only reports (DeviceCaps.gplReport) */
    public static final String PIPELINE_GPL = "iroh.pipeline.gpl";

    private static volatile boolean registered;

    /**
     * INT-2 (RQ-INT1-a audit): the per-frame guards as plain static volatile booleans, so the off path is ONE volatile
     * read instead of a switch-map lookup + string compares. Start from the -D property; kept equal to the registry by
     * each switch's change handler (IrohDebug.set) and re-read at {@link #registerAll}.
     */
    public static volatile boolean tasksBudgetOn = prop(TASKS_BUDGET);
    public static volatile boolean graphCacheOn = prop(GRAPH_CACHE);
    public static volatile boolean graphAsyncOn = prop(GRAPH_ASYNC);
    public static volatile boolean cloneCacheOn = prop(CLONE_CACHE);
    public static volatile boolean rebuildBudgetOn = prop(REBUILD_BUDGET);
    public static volatile boolean areaArenaOn = prop(AREA_ARENA);
    public static volatile boolean intakeBudgetOn = prop(INTAKE_BUDGET);
    public static volatile boolean bulkDirtyOn = prop(BULK_DIRTY);
    public static volatile boolean pipelinePrewarmOn = prop(PIPELINE_PREWARM);
    public static volatile boolean terrainMdiOn = prop(TERRAIN_MDI);

    private static boolean prop(String key) {
        String v = System.getProperty(key, "false");
        return v.equals("on") || v.equals("true") || v.equals("1");
    }

    /** re-reads the four per-frame flags from the registry (after registration or a change) */
    static String refreshFlags() {
        tasksBudgetOn = DebugSwitches.on(TASKS_BUDGET);
        graphCacheOn = DebugSwitches.on(GRAPH_CACHE);
        graphAsyncOn = DebugSwitches.on(GRAPH_ASYNC);
        cloneCacheOn = DebugSwitches.on(CLONE_CACHE);
        rebuildBudgetOn = DebugSwitches.on(REBUILD_BUDGET);
        areaArenaOn = DebugSwitches.on(AREA_ARENA);
        intakeBudgetOn = DebugSwitches.on(INTAKE_BUDGET);
        bulkDirtyOn = DebugSwitches.on(BULK_DIRTY);
        pipelinePrewarmOn = DebugSwitches.on(PIPELINE_PREWARM);
        terrainMdiOn = DebugSwitches.on(TERRAIN_MDI);
        return "ok";
    }

    private PerfSwitches() {}

    public static void registerAll() {
        if (registered) return;
        synchronized (PerfSwitches.class) {
            if (registered) return;
            reg(TASKS_BUDGET, "time-budgeted packet/task drain v2 (perf/TaskBudget)", v -> refreshFlags());
            reg(GRAPH_CACHE, "visibility walk cached per camera section, frustum per frame (exact)", v -> refreshFlags());
            reg(GRAPH_ASYNC, "visibility walk on a worker thread, frustum per frame; sync walk until a result for the camera section exists", v -> refreshFlags());
            reg(CLONE_CACHE, "share region block copies within a rebuild batch", v -> refreshFlags());
            reg(REBUILD_BUDGET, "cap async rebuilds scheduled per update while builders are backed up", v -> refreshFlags());
            reg(AREA_ARENA, "pooled area buffers: no device buffer creation in section intake, grow copies used ranges only", v -> refreshFlags());
            reg(INTAKE_BUDGET, "section intake stops before a section predicted to cross the 1 ms budget", v -> refreshFlags());
            reg(BULK_DIRTY, "chunk/light loads mark the 3x3x3 dirty neighbourhood in one pass", v -> refreshFlags());
            reg(PIPELINE_PREWARM, "record pipeline states first seen in play (config/iroh/cache/prewarm.txt) and create them on a worker after the resource reload", v -> refreshFlags());
            reg(TERRAIN_MDI, "terrain from one world arena: one vertex bind + multi-draw-indirect per page and layer instead of per chunk area (indirect tier; reloads chunks)",
                    v -> { refreshFlags(); return reloadChunks(); });
            reg(PIPELINE_GPL, "VK_EXT_graphics_pipeline_library (SCAFFOLD: capability report only, no GPL path yet)", v -> "ok");
            reg(THREADS_TUNE,"renice builders +4 and keep them off the render core (cannot be undone at runtime)",
                    v -> DebugSwitches.on(THREADS_TUNE) ? (ThreadPolicy.applyToRunningBuilders() ? "ok" : "restart") : "restart");
            reg(THREADS_ADAPTIVE, "extra builders while the backlog is large", v -> rebuildBuilders());
            reg(THREADS_PHYSICAL, "builder count from physical cores", v -> rebuildBuilders());
            refreshFlags();
            registered = true;
        }
    }

    private static void reg(String key, String help, java.util.function.Function<String, String> onChange) {
        DebugSwitches.register(new Switch(key, "off", help, DebugSwitches.BOOL, onChange));
    }

    /** P2-7a: the section buffers move between the per-area and the world layout: rebuild every section (render thread) */
    private static String reloadChunks() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.levelRenderer == null) return "ok";
        mc.execute(() -> mc.levelRenderer.allChanged());
        return "reload";
    }

    private static String rebuildBuilders() {
        var wr = net.vulkanmod.render.chunk.WorldRenderer.getInstance();
        if (wr == null) return "ok";
        // render thread work: recreate the builders at the next frame
        net.minecraft.client.Minecraft.getInstance().execute(() ->
                wr.getTaskDispatcher().createThreads(net.vulkanmod.Initializer.CONFIG.builderThreads));
        return "rebuild";
    }

    /** Read at the point of use. */
    public static boolean on(String key) {
        return DebugSwitches.on(key);
    }
}
