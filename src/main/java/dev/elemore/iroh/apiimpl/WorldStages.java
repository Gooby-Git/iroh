package dev.elemore.iroh.apiimpl;

import dev.elemore.iroh.api.FrameStage;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.world.phys.Vec3;

/**
 * Iroh internal (P1-13a): world-pass frame stages come from Fabric's WorldRenderEvents (bundled fabric-rendering-v1),
 * so Iroh adds no second set of LevelRenderer injections and the order is the one every Fabric mod already sees.
 * START -> WORLD_START, AFTER_SETUP (after the sky in 1.21.1) -> AFTER_SKY, BEFORE_ENTITIES -> AFTER_OPAQUE_TERRAIN,
 * AFTER_ENTITIES, AFTER_TRANSLUCENT, LAST (after weather and the Fabulous composite) -> AFTER_WEATHER, END -> WORLD_END.
 * One listener per event, registered once; each costs one array-length test when no hook is registered.
 */
public final class WorldStages {
    private WorldStages() {
    }

    public static void register() {
        WorldRenderEvents.START.register(ctx -> {
            Vec3 c = ctx.camera().getPosition();
            FrameHooks.setWorld(c.x, c.y, c.z, ctx.positionMatrix(), ctx.projectionMatrix());
            FrameHooks.setInWorld(true);
            FrameHooks.dispatch(FrameStage.WORLD_START);
        });
        WorldRenderEvents.AFTER_SETUP.register(ctx -> FrameHooks.dispatch(FrameStage.AFTER_SKY));
        WorldRenderEvents.BEFORE_ENTITIES.register(ctx -> FrameHooks.dispatch(FrameStage.AFTER_OPAQUE_TERRAIN));
        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> FrameHooks.dispatch(FrameStage.AFTER_ENTITIES));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> FrameHooks.dispatch(FrameStage.AFTER_TRANSLUCENT));
        WorldRenderEvents.LAST.register(ctx -> FrameHooks.dispatch(FrameStage.AFTER_WEATHER));
        WorldRenderEvents.END.register(ctx -> {
            FrameHooks.dispatch(FrameStage.WORLD_END);
            FrameHooks.setInWorld(false);
        });
    }
}
