package dev.elemore.iroh.api;

/** api 4: an off-screen image ({@link IrohTargets}); screen-scaled targets follow window resizes. */
public interface RenderTargetHandle extends GpuTexture {
    IrohTargets.Format format();

    /** Scale of the window (1 = full size), or 0 for a fixed size. */
    float scale();
}
