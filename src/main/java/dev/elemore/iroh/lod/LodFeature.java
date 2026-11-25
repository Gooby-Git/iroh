package dev.elemore.iroh.lod;

import dev.elemore.iroh.lod.config.LodConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.BooleanSupplier;

/**
 * Distant Terrain lifecycle (card VL-0 skeleton). Owns the one question "is the feature running": it is not until a
 * later card builds the renderer ({@link #BACKEND_BUILT}); it is also off while the Vista mod is installed (two writers
 * on one store would corrupt it) and while {@code lod.enabled} is false. Off means no thread, no GPU object and no hook:
 * nothing else in Iroh calls into this package yet except {@link #init()} (one log line) and the settings rows.
 */
public final class LodFeature {
    private static final Logger LOGGER = LogManager.getLogger("Iroh");

    /** Flipped by the card that wires the renderer. Until then the feature is inert whatever the settings say. */
    public static final boolean BACKEND_BUILT = false;

    /** Test seam: whether the Vista mod is loaded. */
    static BooleanSupplier vistaProbe = LodFeature::fabricHasVista;

    private static volatile String offReason = "Distant Terrain is not built yet";
    private static volatile boolean active;

    private LodFeature() {}

    /** Called once from the client initializer after the config is loaded. Never throws, creates nothing. */
    public static void init() {
        try {
            refresh();
            LOGGER.info("Distant Terrain: {}", active ? "on" : "off (" + offReason + ")");
        } catch (Throwable t) {
            active = false;
            offReason = "init failed: " + t;
            LOGGER.warn("Distant Terrain: off (init failed: {})", t.toString());
        }
    }

    /** Recomputes {@link #active()} and {@link #offReason()} from the current settings. */
    public static void refresh() {
        String why;
        if (vistaPresent()) why = "the Vista mod is installed";
        else if (!LodConfig.INSTANCE.enabled) why = "turned off in the settings";
        else if (!BACKEND_BUILT) why = "Distant Terrain is not built yet";
        else why = null;
        offReason = why;
        active = why == null;
    }

    /** True when LOD threads and rendering may run. A plain volatile read. */
    public static boolean active() { return active; }

    /** Why the feature is not running (null when it is). */
    public static String offReason() { return offReason; }

    public static boolean vistaPresent() {
        try {
            return vistaProbe.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Lang key of the reason every settings row is greyed, or null when the rows can be edited. Vista wins over "not built yet". */
    public static String rowsReasonKey() {
        if (vistaPresent()) return "iroh.lod.reason.vista";
        if (!BACKEND_BUILT) return "iroh.lod.reason.notBuilt";
        return null;
    }

    private static boolean fabricHasVista() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("vista");
    }

    public static boolean tempestInstalled() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("tempest");
        } catch (Throwable t) {
            return false;
        }
    }
}
