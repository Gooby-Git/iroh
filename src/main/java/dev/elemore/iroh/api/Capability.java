package dev.elemore.iroh.api;

/**
 * What this Iroh build actually does, as returned by {@link IrohApi#capabilities()}. A hook that exists but has no
 * effect yet (for example a held-light provider on the RASTER path) is NOT listed. Constants are only ever appended.
 */
public enum Capability {
    /** {@link IrohApi#isSectionVisible} / {@link IrohApi#isBoxVisible} answer from the real frustum + occlusion graph. */
    SECTION_VISIBILITY,
    /** {@link IrohApi#registerFaceCullVeto} is consulted by the chunk mesher. */
    FACE_CULL_VETO,
    /** Built-in leaf interior culling ({@link IrohApi#cullLeafFace}) is available. */
    LEAF_CULLING,
    /** A shader pack can be loaded ({@link IrohApi#isShaderPackInUse} may become true). Phase 3. */
    SHADER_PACKS,
    /** A shadow pass exists and {@link IrohApi#isRenderingShadowPass} may become true. Phase 3. */
    SHADOW_PASS,
    /** Registered {@link HeldLightProvider}s change the picture. PACK / RT paths only. */
    HELD_LIGHT
}
