package dev.elemore.iroh.rt.denoise.nrd;

import dev.elemore.iroh.natives.NativeLib;

/**
 * Loads libiroh_nrd. NVIDIA RTX SDKs licence: the library is NOT bundled in the Iroh jar by default; it is found through
 * -Diroh.nrd.lib, a jar built with -PbundleNv=true, or the vendor folder config/iroh/vendor (docs/P6-PLAN.md). Never throws.
 */
public final class NrdLoader {
    private static final NativeLib LIB = new NativeLib("iroh.nrd.lib", "iroh_nrd", () -> NrdNative.commonStructSize() == 364 ? 0 : 1);

    private NrdLoader() {}

    public static boolean load() {
        return LIB.load();
    }

    public static String unavailableReason() {
        return LIB.unavailableReason();
    }

    /** INT-2: where the library would come from, without loading it (startup self-check) */
    public static String locate() {
        return LIB.locate();
    }
}
