package dev.elemore.iroh.api;

/**
 * Points of Iroh's frame where {@link IrohFrame} hooks run, in frame order (vanilla 1.21.1 LevelRenderer.renderLevel and
 * GameRenderer.render, with Iroh's frame begin and present around them). The world stages are dispatched from Fabric's
 * {@code WorldRenderEvents} (which Iroh keeps firing at vanilla's points), so an IrohFrame hook and a Fabric listener at the
 * same point see the same state. Constants are only appended.
 * <p>
 * Bound target / depth at each stage (RASTER path): every world stage runs with the main scene target bound (GL row
 * order, see glshim.Orientation), its depth attached and holding everything drawn so far; the camera-relative
 * view/projection in {@link FrameContext} are the ones vanilla used for that pass.
 */
public enum FrameStage {
    /** The frame command buffer has just begun; the scene target is bound and cleared; nothing drawn. */
    FRAME_BEGIN,
    /** renderLevel started (Fabric START): no world geometry yet, the sky is not drawn yet. */
    WORLD_START,
    /** Sky drawn, sections set up and culled (Fabric AFTER_SETUP); terrain not drawn. Depth: empty (sky writes none). */
    AFTER_SKY,
    /** Solid, cutout-mipped and cutout terrain drawn (Fabric BEFORE_ENTITIES). Depth: opaque terrain. */
    AFTER_OPAQUE_TERRAIN,
    /** Entities and block entities drawn (Fabric AFTER_ENTITIES). */
    AFTER_ENTITIES,
    /** Translucent terrain, tripwire and (non-Fabulous) particles drawn (Fabric AFTER_TRANSLUCENT). */
    AFTER_TRANSLUCENT,
    /** Clouds, weather and world border drawn (Fabric LAST). Under Fabulous the transparency composite follows. */
    AFTER_WEATHER,
    /** renderLevel returned (Fabric END): the world is final; hand / item in hand not drawn yet. */
    WORLD_END,
    /** Just before the HUD (Gui.render) is drawn; the world, hand and screen effects are done. */
    BEFORE_HUD,
    /** HUD drawn (screens / overlays follow). */
    AFTER_HUD,
    /** The frame was submitted and presented: no command recording allowed; CPU-side work only (bookkeeping, async readback). */
    AFTER_PRESENT
}
