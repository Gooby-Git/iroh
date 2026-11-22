package dev.elemore.iroh.rt.game;

/**
 * P5-2 mesher hook: in RT mode the 16-byte terrain vertex colour must carry the biome TINT only; vanilla's face shade and
 * AO (QuadLightData.br) are RT's job (else shading is applied twice: rt-iroh.md trap). Read ONCE per quad in
 * AbstractBlockRenderContext.shadeQuad (pinned). Changing it requires a full section rebuild (the Look switch does one).
 * Fluids (FluidRenderer) still bake shade: RT treats water by its own material path; listed as a gap in the merge notes.
 */
public final class RtMeshMode {
    private RtMeshMode() {}

    /** true only while an RT session runs (set by RtFrameHooks; cleared on disable/fallback) */
    public static volatile boolean tintOnly;
}
