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
    /**
     * Entities submitted (Fabric AFTER_ENTITIES = vanilla's "blockentities" profiler point): entity geometry is in the frame's
     * buffer source, some of its batches are flushed later; block entities are not drawn yet.
     */
    AFTER_ENTITIES,
    /** Translucent terrain, tripwire and (non-Fabulous) particles drawn (Fabric AFTER_TRANSLUCENT). */
    AFTER_TRANSLUCENT,
    /** Clouds, weather and world border drawn (Fabric LAST, at renderDebug). Under Fabulous the composite is already done. */
    AFTER_WEATHER,
    /** renderLevel returned (Fabric END): the world is final; hand / item in hand not drawn yet. */
    WORLD_END,
    /**
     * Gui.render HEAD: the world, hand and screen effects are done. In game only (a level is rendered). Fires also with
     * {@code hideGui} (F1, cutscenes): vanilla 1.21.1 still calls Gui.render then and only skips its HUD layers.
     * {@link FrameContext#guiGraphics()} is the frame's GuiGraphics (flushed by Iroh after each hook).
     */
    BEFORE_HUD,
    /** Gui.render TAIL, HUD flushed. Same conditions as BEFORE_HUD (fires with hideGui). Screens / overlay / toasts follow. */
    AFTER_HUD,
    /** The frame was submitted and presented: no command recording allowed; CPU-side work only (bookkeeping, async readback). */
    AFTER_PRESENT,
    /**
     * api 3.1: the top of the 2D pass, every rendered frame: GameRenderer.render just before its final GuiGraphics.flush(),
     * i.e. after the HUD (Gui.render), after the loading overlay or the open screen (incl. tooltips), after the saving
     * indicator and after toasts. Fires with or without a level, with hideGui, with a screen open; not when Minecraft skips
     * rendering (noRender / minimised). Main target, GUI projection (ortho, gui-scaled) and model-view, depth test as
     * the GUI left it. {@link FrameContext#guiGraphics()} is the frame's GuiGraphics (flushed after each hook).
     * Draws land on top of everything, menus included: check {@code Minecraft.screen} if a letterbox should hide under them.
     */
    OVERLAY
}
