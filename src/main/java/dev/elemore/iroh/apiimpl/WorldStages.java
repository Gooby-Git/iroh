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
            if (dev.elemore.iroh.temporal.TemporalFrame.IN_WORLD) // P4-1a: camera history for motion vectors
                dev.elemore.iroh.temporal.TemporalRuntime.onWorldStart(c.x, c.y, c.z, ctx.positionMatrix(), ctx.projectionMatrix());
            FrameHooks.setInWorld(true);
            dev.elemore.iroh.shaderpack.runtime.PackRuntime.onWorldStart(); // P3-6 full mode: raster terrain skip flag
            FrameHooks.dispatch(FrameStage.WORLD_START);
        });
        WorldRenderEvents.AFTER_SETUP.register(ctx -> {
            dev.elemore.iroh.shaderpack.runtime.PackRuntime.onAfterSky(); // P3-11 world pass (default off: one volatile read)
            FrameHooks.dispatch(FrameStage.AFTER_SKY);
        });
        WorldRenderEvents.BEFORE_ENTITIES.register(ctx -> {
            dev.elemore.iroh.shaderpack.runtime.PackRuntime.onBeforeEntities(); // P3-6 full mode (default off)
            FrameHooks.dispatch(FrameStage.AFTER_OPAQUE_TERRAIN);
        });
        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            FrameHooks.dispatch(FrameStage.AFTER_ENTITIES);
            if (dev.elemore.iroh.temporal.TemporalFrame.IN_WORLD) // P4-1b: opaque copy for the reactive / T&C masks
                dev.elemore.iroh.temporal.TemporalRuntime.onAfterEntities();
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> FrameHooks.dispatch(FrameStage.AFTER_TRANSLUCENT));
        WorldRenderEvents.LAST.register(ctx -> FrameHooks.dispatch(FrameStage.AFTER_WEATHER));
        WorldRenderEvents.END.register(ctx -> {
            // P3-4: the Navidson pack path (default off: one volatile read)
            dev.elemore.iroh.shaderpack.runtime.PackRuntime.onWorldEnd();
            FrameHooks.dispatch(FrameStage.WORLD_END);
            FrameHooks.setInWorld(false);
        });
    }
}
