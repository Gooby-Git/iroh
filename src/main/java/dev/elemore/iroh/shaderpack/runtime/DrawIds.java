package dev.elemore.iroh.shaderpack.runtime;

/**
 * P3-11: the ids of what the renderer is drawing right now, set by observe-only mixins on EntityRenderDispatcher.render
 * and BlockEntityRenderDispatcher.render (HEAD sets, RETURN resets to -1) and read by the world pass for each
 * substituted draw (Iris uniforms entityId, blockEntityId, currentRenderedItemId, entityColor). Render thread only.
 * <p>KNOWN GAP (UNVERIFIED: check in G3b): vanilla BATCHES entity geometry in its buffer source and draws at endBatch,
 * after the render call returned, so batched draws see -1 (Iris' "unknown" default); only draws issued inside the
 * call (immediate renderers) get the real id. Correct per-entity ids need the batch split per id (Iris tags each
 * RenderType with the entity), a follow-up behind its own switch (iris-loader.md section 15).
 */
public final class DrawIds {

    private DrawIds() {}

    /** written only while the world pass is live (the mixins test one volatile first) */
    public static int entityId = -1, blockEntityId = -1, itemId = -1;
    /** inside LevelRenderer.renderSnowAndRain (rain/snow use the particle shader): draws are class weather */
    public static boolean weather;
    /** vanilla hurt / creeper flash overlay as Iris' entityColor (rgb, a = strength) */
    public static final float[] entityColor = new float[4];

    public static void reset() {
        entityId = blockEntityId = itemId = -1;
        entityColor[0] = entityColor[1] = entityColor[2] = entityColor[3] = 0;
    }

    /** id lookups installed when the pack loads (registry name -> pack id), null = none */
    public static volatile java.util.function.ToIntFunction<String> entityIds, blockIds;

    public static void entity(String registryName, boolean hurt) {
        var f = entityIds;
        entityId = f == null ? -1 : f.applyAsInt(registryName);
        if (hurt) { // vanilla's overlay: red tint while hurt (UNVERIFIED constant HURT_COLOR: check in G3b against Iris)
            entityColor[0] = HURT_COLOR[0];
            entityColor[1] = HURT_COLOR[1];
            entityColor[2] = HURT_COLOR[2];
            entityColor[3] = HURT_COLOR[3];
        }
    }

    public static void blockEntity(String blockRegistryName) {
        var f = blockIds;
        blockEntityId = f == null ? -1 : f.applyAsInt(blockRegistryName);
    }

    /** UNVERIFIED: check in G3b (Iris derives entityColor from the overlay texture: red 1, 0, 0 at 0.3) */
    public static final float[] HURT_COLOR = {1f, 0f, 0f, 0.3f};
}
