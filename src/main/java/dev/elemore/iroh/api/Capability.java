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
    HELD_LIGHT,

    // ---- api 3 (P1-13a)
    /** {@link IrohFrame#addHook} stages are dispatched. */
    FRAME_HOOKS,
    /** {@link IrohSky} cloud suppression / cloud and weather overrides are consulted. */
    SKY_HOOKS,
    /** {@link IrohDraw} immediate geometry with declared pipelines. */
    IMMEDIATE_DRAW,
    /** {@link IrohShaders#compile} (GLSL 450 / SPIR-V). */
    OWNER_SHADERS,
    /** {@link IrohTextures#createDynamic}. */
    DYNAMIC_TEXTURES,
    /** {@link IrohTargets}. Not before Phase 2/9. */
    RENDER_TARGETS,
    /** {@link IrohBuffers}. Not before Phase 2/9. */
    GPU_BUFFERS,
    /** Sparse (partially resident) buffers. Not before Phase 9. */
    SPARSE_BUFFERS,
    /** Buffer device addresses. Not before Phase 9. */
    DEVICE_ADDRESS,
    /** {@link IrohCompute}. Not before Phase 2/9. */
    COMPUTE,
    /** {@link IrohDraw#multiDrawIndirectCount}. Not before Phase 9. */
    MULTI_DRAW_INDIRECT_COUNT
}
