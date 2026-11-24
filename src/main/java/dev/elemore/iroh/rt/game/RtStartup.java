package dev.elemore.iroh.rt.game;

/**
 * P5-2: RT's entries in the startup self-check line ("Iroh self-check: ..."). {@link #apiPart} is appended to the api
 * section ONLY when RT was requested at launch, so the rc14 / INT-3 line with RT off is unchanged and still reads
 * inert=yes. Values: {@code rt=on(preset,residency,capMiB; acceleration structures only in this build)} (INT-4), {@code rt=unavailable(<what is missing>)},
 * {@code rt=failed(<step>)}. The device bits come from {@link RtDeviceFeatures#selfCheckBits}.
 */
public final class RtStartup {
    private RtStartup() {}

    public static String apiPart() {
        if (!RtSwitch.requestedAtLaunch()) return "";
        if (RtFailures.reason() != null) return " rt=failed";
        if (!RtSwitch.ON) return " rt=unavailable";
        return " rt=on(" + RtSwitch.settings().preset() + "," + dev.elemore.iroh.rt.RtLimits.describe(RtSwitch.settings().preset())
                + "; " + RtSwitch.STAGE + ")"; // INT-4: honest stage; R1-HANG: limits (SAFE when -Diroh.rt.safe=true)
    }
}
