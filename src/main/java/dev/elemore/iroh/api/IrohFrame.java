package dev.elemore.iroh.api;

import dev.elemore.iroh.apiimpl.FrameHooks;

import java.util.EnumSet;
import java.util.Set;

/**
 * Frame hooks (api 3, P1-13a): the native way for our mods to run code at a point of Iroh's frame, replacing mixins into
 * LevelRenderer / GameRenderer. Render thread only (registration: any thread).
 * <p>
 * Ordering: ascending {@code priority} (default 0), equal priorities in registration order. Cost: a stage with no hook is
 * one array-length test; each hook is one virtual call plus (under -ea) a state comparison. A hook that throws is
 * isolated (logged) and removed after 3 failures. Paths: a hook declares the render paths it supports (default RASTER
 * only); on another path (PACK, RT: Phase 3 / 5) it is skipped, so a hook written for raster never runs inside a shader
 * pack's program flow by accident. How the stages map onto PACK and RT is documented in knowledge/api.md.
 */
public final class IrohFrame {
    private IrohFrame() {
    }

    /** Handle to remove a hook. */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /** {@code addHook(stage, 0, EnumSet.of(RASTER), hook)}. */
    public static Registration addHook(FrameStage stage, FrameHook hook) {
        return FrameHooks.add(stage, 0, EnumSet.of(RenderPath.RASTER), hook);
    }

    /**
     * @param priority lower runs first
     * @param paths    render paths the hook supports; it is skipped on the others
     */
    public static Registration addHook(FrameStage stage, int priority, Set<RenderPath> paths, FrameHook hook) {
        return FrameHooks.add(stage, priority, EnumSet.copyOf(paths), hook);
    }
}
