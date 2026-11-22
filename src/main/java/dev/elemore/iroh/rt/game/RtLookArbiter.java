package dev.elemore.iroh.rt.game;

/**
 * P5-2: who wins between the three looks (owner: ONE Look switch in the Iroh video-settings section, RT <-> Navidson;
 * DECISIONS "Settings UI"). RT is a third look beside raster and the pack:
 * <ul>
 * <li>RT and the pack (Navidson through the loader) are MUTUALLY EXCLUSIVE: the Look switch selects one; with RT the
 *   pack is not loaded (no W world pass, no pack targets), with the pack RT is inert (no features at the next launch,
 *   no session now).</li>
 * <li>Raster is what runs when the chosen look cannot: RT selected but unsupported/failed -> raster (never the pack
 *   silently); pack selected but failed -> raster (P3 rule).</li>
 * <li>RT composes with render scale and FSR: RT targets are render-size, the RT output enters the same upscaler cut as
 *   raster (P4 chain); MainPassArbiter's R (render-scale world phase) is allowed with RT exactly as with raster.</li>
 * </ul>
 * A runtime switch of the look takes effect for RT only at the next launch when the device lacked the RT features
 * (they are chosen at device creation, like FSR's); the row says so.
 */
public final class RtLookArbiter {
    private RtLookArbiter() {}

    public enum Active { RASTER, PACK, RT }

    /**
     * @param lookRt      the Look switch says RAY_TRACED (else NAVIDSON)
     * @param rtRunning   RtSwitch.ON (device features present, no failure)
     * @param packLoaded  the pack loader has Navidson ready
     */
    public static Active resolve(boolean lookRt, boolean rtRunning, boolean packLoaded) {
        // INT-4: the rule lives in MainPassArbiter (rule 8); this is its RT-side name
        return Active.valueOf(dev.elemore.iroh.render.MainPassArbiter.resolveLook(lookRt, rtRunning, packLoaded).name());
    }

    /** Settings row state: null = selectable, else the greyed reason shown as tooltip. */
    public static String rowDisabledReason(boolean rtCapable, boolean rtFeaturesAtLaunch) {
        if (!rtCapable) return "Ray tracing needs a GPU with Vulkan ray query (VK_KHR_ray_query)";
        if (!rtFeaturesAtLaunch) return "Takes effect after a restart";
        return null;
    }
}
