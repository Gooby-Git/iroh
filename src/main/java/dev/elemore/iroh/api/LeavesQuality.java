package dev.elemore.iroh.api;

/** How Iroh's mesher draws leaves right now. */
public enum LeavesQuality {
    /** Transparent leaves, inner faces drawn (cut down only by leaf culling and vetoes). */
    FANCY,
    /** Opaque leaves. */
    FAST
}
