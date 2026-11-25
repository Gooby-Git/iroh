// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 the Iroh authors. Originally Vista (owner code), relicensed into Iroh.

package dev.elemore.iroh.lod.nat;

import dev.elemore.iroh.natives.NativeLib;

/**
 * Loads libiroh_lod (jar {@code /natives/<os>-<arch>/}, or {@code -Diroh.lod.native=path} for tests and development) and
 * checks its ABI against {@link LodNative#ABI}. Never throws: when it is unavailable the Java implementations run.
 */
public final class LodNativeLoader {
    private static final NativeLib LIB = new NativeLib("iroh.lod.native", "iroh_lod", () -> LodNative.abi() == LodNative.ABI ? 0 : 1);

    private LodNativeLoader() {}

    public static boolean load() {
        return LIB.load();
    }

    public static String unavailableReason() {
        return LIB.unavailableReason();
    }

    /** True when the loaded library contains the private seed worldgen. */
    public static boolean hasSeed() {
        return LIB.load() && LodNative.hasSeed() == 1;
    }

    public static String locate() {
        return LIB.locate();
    }
}
