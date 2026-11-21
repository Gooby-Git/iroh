package dev.elemore.iroh.api;

/**
 * Code run at one {@link FrameStage} of every frame. See {@link IrohFrame#addHook}.
 * <p>
 * Contract: render thread only. May record draws through {@link IrohDraw} (world and HUD stages; not at AFTER_PRESENT).
 * Must leave the render state as it found it: bound target, blend, depth test / write, cull, colour mask (checked under
 * -ea after every hook; in normal runs a violation is logged once and the state restored). Must not allocate per frame
 * if it can avoid it, must not block, and must not call {@code Minecraft.getInstance().levelRenderer} drawing methods.
 */
@FunctionalInterface
public interface FrameHook {
    void run(FrameContext context);
}
