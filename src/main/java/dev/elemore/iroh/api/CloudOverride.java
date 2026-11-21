package dev.elemore.iroh.api;

/** See {@link IrohSky#overrideClouds}. Render thread, once per frame where vanilla draws clouds. */
@FunctionalInterface
public interface CloudOverride {
    /** Draw the clouds (through {@link IrohDraw}) and return true to claim the stage, or false to let the next one / Iroh's own draw. */
    boolean draw(FrameContext context);
}
