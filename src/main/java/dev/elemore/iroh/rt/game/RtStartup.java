package dev.elemore.iroh.rt.game;

/**
 * P5-2: RT's entries in the startup self-check line ("Iroh self-check: ..."). {@link #apiPart} is appended to the api
 * section ONLY when RT was requested at launch, so the rc14 / INT-3 line with RT off is unchanged and still reads
 * inert=yes. Values: {@code rt=on(preset,residency,capMiB)}, {@code rt=unavailable(<what is missing>)},
 * {@code rt=failed(<step>)}. The device bits come from {@link RtDeviceFeatures#selfCheckBits}.
 */
public final class RtStartup {
    private RtStartup() {}

    public static String apiPart() {
        if (!RtSwitch.requestedAtLaunch()) return "";
        if (RtFailures.reason() != null) return " rt=failed";
        if (!RtSwitch.ON) return " rt=unavailable";
        return " rt=on(" + RtSwitch.settings().preset() + ",residency " + Integer.getInteger("iroh.rt.residency", 8)
                + ",cap " + Long.getLong("iroh.rt.capMiB", RtGuardMemory.DEFAULT_CAP >> 20) + "MiB)";
    }
}
