package dev.elemore.iroh.api;

import org.joml.Matrix4fc;
import org.joml.Vector3dc;

/**
 * What a {@link FrameHook} sees. One object for the whole frame, updated in place before each stage: valid only during
 * the hook call (do not keep it, copy what you need). Render thread. All getters are field reads (no allocation).
 */
public interface FrameContext {
    /** The stage being dispatched. */
    FrameStage stage();

    /** Increments once per frame. */
    long frameIndex();

    /** Partial tick of this frame. */
    float partialTick();

    /** True for the world stages (WORLD_START .. WORLD_END) while a level is rendered; false on menus. */
    boolean inWorld();

    /** Camera position (absolute world coordinates). World stages only, else the last world frame's value. */
    Vector3dc cameraPos();

    /** Camera-relative view (model-view) matrix vanilla used for the world pass. World stages only. */
    Matrix4fc viewMatrix();

    /** Projection matrix of the world pass (Vulkan clip space as Iroh renders it). World stages only. */
    Matrix4fc projectionMatrix();

    /** Framebuffer size of the scene target in pixels. */
    int viewportWidth();

    int viewportHeight();

    /** Render path drawing this frame. */
    RenderPath renderPath();

    /** True while a shader pack's shadow pass is being drawn (never on RASTER). */
    boolean isShadowPass();

    /**
     * api 3.1: the frame's GuiGraphics (vanilla's, over the frame buffer source) in the 2D stages BEFORE_HUD, AFTER_HUD and
     * OVERLAY; null in the others. Draw with it as in a Screen; Iroh flushes it after each hook (do not keep it).
     */
    default net.minecraft.client.gui.GuiGraphics guiGraphics() {
        return null;
    }
}
