package dev.elemore.iroh.upscale.fsr;

import dev.elemore.iroh.natives.NativeLib;

/**
 * Loads libiroh_fsr (see {@link NativeLib} for the search order; override with -Diroh.fsr.lib=path). Never throws: failure
 * means "unavailable" with a reason string the UI shows.
 */
public final class NativeLoader {
    private static final NativeLib LIB = new NativeLib("iroh.fsr.lib", "iroh_fsr", () -> FsrNative.structSize() == FsrDispatchStruct.SIZE ? 0 : 1);

    private NativeLoader() {}

    public static boolean load() {
        return LIB.load();
    }

    /** Empty when loaded, else why FSR is unavailable. */
    public static String unavailableReason() {
        return LIB.unavailableReason();
    }
}
