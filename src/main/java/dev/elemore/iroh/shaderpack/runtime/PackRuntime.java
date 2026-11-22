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

    public static void onSetLevel() {
        PackRuntimeImpl i = impl;
        if (i != null)
            i.unload();
    }

    /** IrohDebug / startup: switch the pack path on or off (off frees everything) */
    public static synchronized String setEnabled(boolean on) {
        if (on) {
            if (impl == null)
                impl = new PackRuntimeImpl();
            String r = impl.enable();
            active = impl.usable();
            return r;
        }
        active = false;
        if (impl != null)
            impl.disable();
        return "ok";
    }

    /** called by PackRuntimeImpl when the path failed for the session */
    static void deactivate() {
        active = false;
    }

    public static synchronized String reload() {
        if (impl == null)
            return "ok (not running)";
        impl.disable();
        if (!DebugSwitches.on(PackSwitches.ENABLED))
            return "ok";
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
