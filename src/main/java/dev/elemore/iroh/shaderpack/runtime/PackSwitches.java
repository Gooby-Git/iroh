package dev.elemore.iroh.shaderpack.runtime;

import dev.elemore.iroh.debug.DebugSwitches;
import dev.elemore.iroh.debug.DebugSwitches.Switch;

/**
 * The pack path's runtime switches (P3-4). All default OFF / inert: with none of them set the jar behaves like rc14.
 * Each sub-step has its own switch so a Proctor run can bisect inside one session (IrohDebug.set).
 */
public final class PackSwitches {

    public static final String ENABLED = "iroh.pack.enabled";
    public static final String MODE = "iroh.pack.mode";
    public static final String PATH = "iroh.pack.path";
    public static final String FOLDER = "iroh.pack.folder";
    public static final String POST_SCENE = "iroh.pack.post.scene";
    public static final String POST_DEPTH = "iroh.pack.post.depth";
    public static final String PRESENT = "iroh.pack.present";
    public static final String SKIP = "iroh.pack.skip";
    public static final String LOG = "iroh.pack.log";
    public static final String GPU_TIMES = "iroh.pack.gpuTimes";

    private static volatile boolean registered;

    private PackSwitches() {}

    public static void registerAll() {
        if (registered)
            return;
        synchronized (PackSwitches.class) {
            if (registered)
                return;
            DebugSwitches.register(new Switch(ENABLED, "off", "Navidson pack path on/off (default off: the raster path only)",
                    DebugSwitches.BOOL, v -> PackRuntime.setEnabled(DebugSwitches.on(ENABLED))));
            DebugSwitches.register(new Switch(MODE, "post", "post = Navidson's deferred/composite/final over the raster picture; full = gbuffers + shadow (NOT implemented in P3-4: runs post)",
                    DebugSwitches.oneOf("post", "full"), v -> v.equals("full") ? "ok (full is not implemented yet: post runs)" : "ok"));
            DebugSwitches.register(new Switch(PATH, "", "pack folder (empty = <gameDir>/shaderpacks/Navidson); a change reloads the pack",
                    null, v -> PackRuntime.reload()));
            DebugSwitches.register(new Switch(FOLDER, "auto", "program folder: auto (from the dimension) or world0 / world-1 / world1 / world2 / <root>",
                    null, v -> "ok"));
            DebugSwitches.register(new Switch(POST_SCENE, "on", "post mode: raster scene colour -> colortex0 (off: colortex0 cleared to the fog colour)",
                    DebugSwitches.BOOL, v -> "ok"));
            DebugSwitches.register(new Switch(POST_DEPTH, "on", "post mode: raster scene depth -> depthtex0/1/2 (off: far plane)",
                    DebugSwitches.BOOL, v -> "ok"));
            DebugSwitches.register(new Switch(PRESENT, "on", "copy the pack output into the scene colour (off: the chain runs, the raster picture stays)",
                    DebugSwitches.BOOL, v -> "ok"));
            DebugSwitches.register(new Switch(SKIP, "", "comma-separated pass ids not executed (bisecting), e.g. composite6,composite7",
                    null, v -> "ok"));
            DebugSwitches.register(new Switch(LOG, "off", "one line per pack frame event (rebuilds, failures)", DebugSwitches.BOOL, v -> "ok"));
            DebugSwitches.register(new Switch(GPU_TIMES, "no samples yet", "READ-ONLY: per-pass GPU ms/frame of the pack path, 2 s average (IrohDebug.get)",
                    v -> "read-only (published by the renderer)", null));
            registered = true;
        }
    }
}
