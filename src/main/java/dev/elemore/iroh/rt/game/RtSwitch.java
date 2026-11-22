package dev.elemore.iroh.rt.game;

import dev.elemore.iroh.rt.RtLook;
import dev.elemore.iroh.rt.RtSettings;

/**
 * P5-2: THE one guard of the RT game adapter. Every hook Iroh's renderer calls for RT starts with a read of {@link #ON}
 * (a static volatile boolean) and returns at once when it is false, so with RT off nothing below runs, allocates or
 * registers (integrationInertTest pins each call site and each hook's first statement; RtSeamTest proves it offline).
 * <p>
 * Decision order (no game state, so it can run before the Vulkan device exists):
 * {@link #requestedAtLaunch} = dev gate {@code -Diroh.rt=true} AND look RAY_TRACED (system property {@code iroh.rt.look}
 * or iroh.json {@code look}, see {@link #configLook}); the device hook then enables the RT features only if requested
 * AND the device has them; {@link #ON} becomes true only after the device was created with them and no RT step failed.
 * A failure anywhere calls {@link #fallBack}: ON false, one log line (fault seam {@code -Diroh.test.failInit=rt}), raster.
 */
public final class RtSwitch {
    private RtSwitch() {}

    /** RT is running this session. Read first in every hook. */
    public static volatile boolean ON;
    /** iroh.json {@code look} (set by IrohOptions.load before device creation; null = not set). */
    public static volatile String configLook;
    /** The settings resolved at launch (null until {@link #requestedAtLaunch} ran). */
    private static volatile RtSettings launch;

    /**
     * INT-4: what this build does with RT when it runs (the Look row, the log and the self-check say it; the in-game RT
     * passes are not wired yet: only the adapter: device features, memory owner, BLAS queue, TLAS, lifecycle).
     */
    public static final String STAGE = "acceleration structures only in this build";

    public static RtSettings settings() {
        RtSettings s = launch;
        if (s == null) {
            java.util.Map<Object, Object> p = new java.util.HashMap<>(System.getProperties());
            if (!p.containsKey("iroh.rt.look") && configLook != null) p.put("iroh.rt.look", configLook);
            launch = s = RtSettings.from(p);
        }
        return s;
    }

    /**
     * INT-4: iroh.json's {@code look} (IrohOptions.load, before device creation). Drops the cached launch settings so an
     * earlier caller (e.g. pack switches applied before the config was read) cannot freeze the decision without it.
     */
    public static void setConfigLook(String look) {
        configLook = look;
        launch = null;
    }

    /**
     * INT-4 (MainPassArbiter rule 8): the configured Look is RAY_TRACED (launch property, else iroh.json), with or
     * without the dev gate or the hardware. Then the shader pack path stays off: the Look switch picks one.
     */
    public static boolean lookRayTraced() {
        RtLook now = lookNow;
        return (now != null ? now : settings().look()) == RtLook.RAY_TRACED;
    }

    /** the Look row's choice once it was changed in this session (null = the launch Look) */
    private static volatile RtLook lookNow;

    /** INT-4: the Look row changed at runtime (RtFrameHooks.applyLook): rule 8 follows the row from now on */
    static void lookChanged(boolean rt) {
        lookNow = rt ? RtLook.RAY_TRACED : RtLook.NAVIDSON;
    }

    /** Before device creation: may RT features be requested? (dev gate + look; capabilities are checked by the device hook) */
    public static boolean requestedAtLaunch() {
        RtSettings s = settings();
        return s.devGate() && s.look() == RtLook.RAY_TRACED;
    }

    /** Any RT failure: RT off for the session, one line, the frame continues on raster. Returns the logged line. */
    public static String fallBack(String step, Throwable t) {
        ON = false;
        return RtFailures.record(step, t);
    }

    /** Tests only. */
    public static void resetForTest() {
        lookNow = null;
        ON = false;
        configLook = null;
        launch = null;
    }
}
