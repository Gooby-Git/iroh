package dev.elemore.iroh.api;

/** Which of Iroh's render paths is drawing the world. Constants are only ever appended. */
public enum RenderPath {
    /** Vanilla-equivalent rasterisation (the OpenGL-with-Sodium parity target). The only path in Phase 1 and 2. */
    RASTER,
    /** An Iris-format shader pack loaded through Iroh's pack loader (Phase 3). Reserved. */
    PACK,
    /** Ray-traced path (hybrid or full path tracing, Phase 5+). Reserved. */
    RT
}
