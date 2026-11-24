package dev.elemore.iroh.shaderpack.runtime;

import dev.elemore.iroh.debug.DebugSwitches;

import java.nio.file.Path;

/**
 * The pack path's entry points from the renderer (P3-4). DEFAULT OFF and inert: every hook starts with one volatile
 * read and returns at once unless {@code iroh.pack.enabled} is on (property at startup or IrohDebug at runtime). This
 * class touches no game or Vulkan class itself; {@link PackRuntimeImpl} (loaded only once enabled) does the work.
 * <ul>
 *   <li>{@link #onWorldEnd()}: from WorldStages at Fabric's WorldRenderEvents.END (after the whole world, before the
 *       GUI): post mode runs Navidson's deferred/composite/final over the raster picture and copies the output into
 *       the scene target, so the GUI, F3 and F2 screenshots sit on top of it.</li>
 *   <li>{@link #onSetLevel()}: world unload / change: every pack image is freed (rebuilt at the next frame).</li>
 * </ul>
 */
public final class PackRuntime {

    private static volatile boolean active;
    private static volatile boolean startupChecked;
    private static volatile PackRuntimeImpl impl;

    private PackRuntime() {}

    /** true only after the pack path was switched on: the jar is inert otherwise */
    public static boolean active() {
        return active;
    }

    public static void onWorldEnd() {
        if (!active) {
            if (startupChecked)
                return;
            startupChecked = true;
            if (!"true".equals(System.getProperty(PackSwitches.ENABLED)) && !"on".equals(System.getProperty(PackSwitches.ENABLED)))
                return;
            PackSwitches.registerAll();
            setEnabled(true);
            if (!active)
                return;
        }
        PackRuntimeImpl i = impl;
        if (i != null)
            i.worldEnd();
    }

    /** P3-6 FULL mode, WorldRenderEvents.START: decides whether the raster terrain layers are skipped this frame;
     *  P3-11: begins a world-pass frame when iroh.pack.world.split is on */
    public static void onWorldStart() {
        PackRuntimeImpl i = impl;
        boolean on = active && i != null && i.fullReady();
        if (net.vulkanmod.render.chunk.WorldRenderer.packTerrainActive != on) // INT-2: no volatile write per frame when off
            net.vulkanmod.render.chunk.WorldRenderer.packTerrainActive = on;
        if (on) // P3-11 world pass (split / substitute switches; off = nothing)
            i.worldBegin();
    }

    /** P3-11, WorldRenderEvents.AFTER_SETUP (after the sky) */
    public static void onAfterSky() {
        if (!active)
            return;
        PackRuntimeImpl i = impl;
        if (i != null)
            i.worldAfterSky();
    }

    /** P3-6 FULL mode, WorldRenderEvents.BEFORE_ENTITIES: shadow + gbuffers + the whole chain, output and depth back
     *  (P3-11 world pass: shadow .. gbuffers-solid only) */
    public static void onBeforeEntities() {
        if (!active)
            return;
        PackRuntimeImpl i = impl;
        if (i != null && net.vulkanmod.render.chunk.WorldRenderer.packTerrainActive) {
            if (i.worldFrameActive())
                i.worldOpaque();
            else
                i.fullFrame();
        }
    }

    /** P3-11: vanilla's translucent terrain layer (Iroh's WorldRenderer, full mode): deferred runs here */
    public static void onTranslucentTerrain() {
        if (!active)
            return;
        PackRuntimeImpl i = impl;
        if (i != null)
            i.worldTranslucent();
    }

    public static void onSetLevel() {
        PackRuntimeImpl i = impl;
        if (i != null)
            i.unload();
    }

    /** INT-4: the "Look is ray tracing" refusal is logged once per session */
    private static boolean lookRefusalLogged;

    /** V-5: a switch from another thread (Proctor's proctor-conn, IrohDebug) waits this long for the render thread */
    static final long SWITCH_WAIT_MS = 10_000;

    /**
     * IrohDebug / startup: switch the pack path on or off (off frees everything). V-5: always applied ON THE RENDER
     * THREAD ({@link RenderThreadCall}): the pack path is render-thread only, and a disable from another thread freed
     * images under a pack frame being recorded and tore the executor down twice (double vkDestroyFramebuffer).
     */
    public static String setEnabled(boolean on) {
        return RenderThreadCall.call(() -> setEnabledNow(on), SWITCH_WAIT_MS);
    }

    private static synchronized String setEnabledNow(boolean on) {
        if (on) {
            // INT-4 MainPassArbiter rule 8: the configured Look wins; RAY_TRACED keeps the pack path off (one line)
            String look = dev.elemore.iroh.render.MainPassArbiter.packRefusal(dev.elemore.iroh.rt.game.RtSwitch.lookRayTraced());
            if (look != null) {
                active = false;
                if (!lookRefusalLogged) {
                    lookRefusalLogged = true;
                    try {
                        net.vulkanmod.Initializer.LOGGER.warn("Iroh pack: {}", look);
                    } catch (Throwable ignored) {
                        System.out.println("Iroh pack: " + look); // offline
                    }
                }
                return "refused: " + look;
            }
            try {
                dev.elemore.iroh.diag.FeatureFailures.inject("pack");
                if (impl == null)
                    impl = new PackRuntimeImpl();
                String r = impl.enable();
                active = impl.usable();
                return r;
            } catch (RuntimeException | LinkageError e) {
                // INT-2: an exception here used to reach WorldRenderEvents.END (crash): the raster picture stays
                active = false;
                net.vulkanmod.render.chunk.WorldRenderer.packTerrainActive = false;
                try {
                    if (impl != null) impl.disable();
                } catch (RuntimeException ignored) {
                }
                return "error: " + dev.elemore.iroh.diag.FeatureFailures.record("pack", e, "shader pack path off for this session (raster picture)");
            }
        }
        active = false;
        if (impl != null)
            impl.disable();
        return "ok";
    }

    /**
     * PACK-FULL (T-2 I-7/I-9: 62-227 objects leaked at vkDestroyDevice in full mode): Vulkan.cleanUp, render thread,
     * device idle, BEFORE Iroh runs its frame ops and frees leftovers: the pack's images, pipelines, render passes,
     * framebuffers, samplers, buffers, descriptor pools and query pool are released (nothing called disable at exit).
     */
    public static synchronized void shutdown() {
        active = false;
        PackRuntimeImpl i = impl;
        if (i == null)
            return;
        try {
            i.disable();
        } catch (RuntimeException e) {
            net.vulkanmod.Initializer.LOGGER.warn("Iroh pack: shutdown release failed: {}", e.toString());
        }
    }

    /** called by PackRuntimeImpl when the path failed for the session */
    static void deactivate() {
        active = false;
    }

    /** IrohDebug (pack path changed): disable + enable, on the render thread (V-5, see {@link #setEnabled}) */
    public static String reload() {
        return RenderThreadCall.call(PackRuntime::reloadNow, SWITCH_WAIT_MS);
    }

    private static synchronized String reloadNow() {
        if (impl == null)
            return "ok (not running)";
        impl.disable();
        impl.dropLoaded(); // V-7 (review 698ba7c #2): disable keeps the loaded pack; enable() then returned "ok" on the OLD pack
        if (!DebugSwitches.on(PackSwitches.ENABLED)) {
            active = false; // V-7 (review #3): else the next frame rebuilds every pack image before a queued enabled=off frees them
            return "ok";
        }
        String r = impl.enable();
        active = impl.usable();
        return r.equals("ok") ? "rebuild" : r;
    }

    public static String requestCapture(Path dir) {
        PackRuntimeImpl i = impl;
        if (i == null || !active)
            return "refused: the pack path is not running (iroh.pack.enabled=on first)";
        return i.requestCapture(dir);
    }
}
