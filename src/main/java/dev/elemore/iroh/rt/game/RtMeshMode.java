package dev.elemore.iroh.rt.game;

/**
 * P5-2 mesher hook: in RT mode the 16-byte terrain vertex colour must carry the biome TINT only; vanilla's face shade and
 * AO (QuadLightData.br) are RT's job (else shading is applied twice: rt-iroh.md trap). Read ONCE per quad in
 * AbstractBlockRenderContext.shadeQuad (pinned). Changing it requires a full section rebuild (the Look switch does one).
 * Fluids (FluidRenderer) still bake shade: RT treats water by its own material path; listed as a gap in the merge notes.
 */
public final class RtMeshMode {
    private RtMeshMode() {}

    /** true only while an RT session runs AND its passes shade the picture (set by RtFrameHooks; cleared on disable/fallback) */
    public static volatile boolean tintOnly;

    /**
     * INT-4: the RT passes (G-buffer MRT, sun/emit/GI, denoiser, compose) are NOT wired into the game in this build: the
     * picture is still raster. Tint-only colour would strip vanilla face shade and AO from that raster picture, so it
     * stays off until the compose stage exists (the BLAS reads positions only; colour does not matter to it).
     */
    public static final boolean RT_SHADES_PICTURE = false;
}
