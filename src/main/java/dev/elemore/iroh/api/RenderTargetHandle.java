package dev.elemore.iroh.api;

/** api 4: an off-screen image ({@link IrohTargets}); screen-scaled targets follow window resizes. */
public interface RenderTargetHandle extends GpuResource {
    IrohTargets.Format format();

    int width();

    int height();

    /** Scale of the window (1 = full size), or 0 for a fixed size. */
    float scale();
}
