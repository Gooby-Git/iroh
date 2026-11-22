package dev.elemore.iroh.perf;

import dev.elemore.iroh.debug.DebugSwitches;
import dev.elemore.iroh.debug.DebugSwitches.Switch;

/**
 * perf/hitches (hitches4): the hitch switches as runtime switches (IrohDebug / DebugSwitches), for in-session ABAB
 * tests. All default off (the default jar behaves like rc14). Each is read at its point of use (once per frame / per
 * update / per batch), never a static final, so {@code IrohDebug.set} applies within a frame or two, except where the
 * handler says {@code rebuild} (builder threads recreated) or {@code restart} (renice / affinity cannot be undone).
 */
public final class PerfSwitches {
    public static final String TASKS_BUDGET = "iroh.tasks.budget";
    public static final String GRAPH_CACHE = "iroh.graph.cache";
    public static final String CLONE_CACHE = "iroh.graph.cloneCache";
    public static final String REBUILD_BUDGET = "iroh.graph.rebuildBudget";
    public static final String THREADS_TUNE = "iroh.threads.tune";
    public static final String THREADS_ADAPTIVE = "iroh.threads.adaptive";
    public static final String THREADS_PHYSICAL = "iroh.threads.physicalCount";

    private static volatile boolean registered;

    private PerfSwitches() {}

    public static void registerAll() {
        if (registered) return;
        synchronized (PerfSwitches.class) {
            if (registered) return;
            reg(TASKS_BUDGET, "time-budgeted packet/task drain v2 (perf/TaskBudget)", null);
            reg(GRAPH_CACHE, "visibility walk cached per camera section, frustum per frame (exact)", null);
            reg(CLONE_CACHE, "share region block copies within a rebuild batch", null);
            reg(REBUILD_BUDGET, "cap async rebuilds scheduled per update while builders are backed up", null);
            reg(THREADS_TUNE, "renice builders +4 and keep them off the render core (cannot be undone at runtime)",
                    v -> DebugSwitches.on(THREADS_TUNE) ? (ThreadPolicy.applyToRunningBuilders() ? "ok" : "restart") : "restart");
            reg(THREADS_ADAPTIVE, "extra builders while the backlog is large", v -> rebuildBuilders());
            reg(THREADS_PHYSICAL, "builder count from physical cores", v -> rebuildBuilders());
            registered = true;
        }
    }

    private static void reg(String key, String help, java.util.function.Function<String, String> onChange) {
        DebugSwitches.register(new Switch(key, "off", help, DebugSwitches.BOOL, onChange));
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
