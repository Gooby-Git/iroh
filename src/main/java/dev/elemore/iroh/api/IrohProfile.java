package dev.elemore.iroh.api;

import dev.elemore.iroh.gpu.GpuCore;

/**
 * api 4.1 (card V-M3): GPU timestamps per owner and pass. {@link #begin} / {@link #end} bracket a mod's GPU work
 * (compute, passes, draws; one open pass per owner); the times appear in Iroh's profile line ({@code -Diroh.profile},
 * "api4 vista:mesh=0.210 ...") and through {@link #ms}. Read a few frames late (never waits on the GPU). Where the
 * device has no timestamps, or the frame's first begin falls inside the main pass (the query pool reset needs to be
 * outside a render pass), nothing is measured that frame. Render thread.
 */
public final class IrohProfile {
    private IrohProfile() {
    }

    public static void begin(String owner, String pass) {
        GpuCore.get().profileBegin(owner, pass);
    }

    public static void end(String owner) {
        GpuCore.get().profileEnd(owner);
    }

    /** Smoothed GPU milliseconds of {@code owner}'s {@code pass}; 0 when never measured. */
    public static double ms(String owner, String pass) {
        return GpuCore.get().profileMs(owner, pass);
    }
}
