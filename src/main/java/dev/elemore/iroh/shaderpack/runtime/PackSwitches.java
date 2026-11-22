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
    public static final String CPU = "iroh.pack.cpu";
    // P3-6 FULL-mode sub-steps (bisecting; all runtime-safe)
    public static final String FULL_TERRAIN = "iroh.pack.full.terrain";
    public static final String FULL_SHADOW = "iroh.pack.full.shadow";
    public static final String FULL_TRANSLUCENT = "iroh.pack.full.translucent";
    public static final String FULL_RASTER_SKY = "iroh.pack.full.rasterSky";
    public static final String FULL_DEPTH_BACK = "iroh.pack.full.depthBack";
    public static final String FULL_SHADOW_REUSE = "iroh.pack.full.shadowReuse";

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
            DebugSwitches.register(new Switch(MODE, "post", "post = Navidson's deferred/composite/final over the raster picture; full = shadow + gbuffers from real geometry (offline replay only so far: in game the chunk-mesher hookup is the next step, post runs)",
                    DebugSwitches.oneOf("post", "full"), v -> v.equals("full") ? "restart (full mode needs the 36-byte section format chosen at startup; not wired in game yet: post runs)" : "ok"));
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
            DebugSwitches.register(new Switch(CPU, "no samples yet", "READ-ONLY: pack frame CPU ms and full-mode draw / shadow section counts, 2 s average",
                    v -> "read-only (published by the renderer)", null));
            DebugSwitches.register(new Switch(FULL_TERRAIN, "on", "full mode: terrain through Navidson (off: raster terrain + post mode for that frame on)",
                    DebugSwitches.BOOL, v -> "ok"));
            DebugSwitches.register(new Switch(FULL_SHADOW, "on", "full mode: the shadow pass (off: shadow maps stay cleared = lit)",
                    DebugSwitches.BOOL, v -> "ok"));
            DebugSwitches.register(new Switch(FULL_TRANSLUCENT, "on", "full mode: gbuffers_water for translucent terrain (off: no water/glass)",
                    DebugSwitches.BOOL, v -> "ok"));
            DebugSwitches.register(new Switch(FULL_RASTER_SKY, "on", "full mode: the raster sky is copied into colortex0 before gbuffers (off: the pack's clear colour)",
                    DebugSwitches.BOOL, v -> "ok"));
            DebugSwitches.register(new Switch(FULL_DEPTH_BACK, "on", "full mode: depthtex0 is copied into the scene depth so raster entities/particles/hand occlude correctly",
                    DebugSwitches.BOOL, v -> "ok"));
            DebugSwitches.register(new Switch(FULL_SHADOW_REUSE, "3", "full mode: reuse the shadow maps up to N frames while the shadow camera barely moves (0 = render every frame)",
                    v -> v.trim().matches("\\d+") ? null : "a frame count", v -> "ok"));
            registered = true;
        }
    }
}
