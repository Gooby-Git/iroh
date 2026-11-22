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

    public static RtSettings settings() {
        RtSettings s = launch;
        if (s == null) {
            java.util.Map<Object, Object> p = new java.util.HashMap<>(System.getProperties());
            if (!p.containsKey("iroh.rt.look") && configLook != null) p.put("iroh.rt.look", configLook);
            launch = s = RtSettings.from(p);
        }
        return s;
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
        ON = false;
        configLook = null;
        launch = null;
    }
}
