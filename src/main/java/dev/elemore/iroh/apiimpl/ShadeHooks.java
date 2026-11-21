package dev.elemore.iroh.apiimpl;

import dev.elemore.iroh.api.ShadeHook;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * Iroh internal (P1-10): registered {@link ShadeHook}s. The mesher reads {@link #snapshot()} once per mesh task (null =
 * none, one null check per quad) and wraps its light calculation in {@link #before} / {@link #restore}.
 */
public final class ShadeHooks {
    private static volatile ShadeHook[] hooks = null;

    private ShadeHooks() {
    }

    public static synchronized void register(ShadeHook hook) {
        if (hook == null)
            throw new NullPointerException("hook");
        ShadeHook[] cur = hooks == null ? new ShadeHook[0] : hooks;
        for (ShadeHook h : cur)
            if (h == hook)
                return;
        ShadeHook[] next = Arrays.copyOf(cur, cur.length + 1);
        next[next.length - 1] = hook;
        hooks = next;
        net.vulkanmod.Initializer.LOGGER.info("Iroh api: shade hook registered: {}", hook.getClass().getName());
    }

    /** The hooks for one mesh task, or null when none are registered. Any thread. */
    public static ShadeHook[] snapshot() {
        return hooks;
    }

    /**
     * Saves the four positions into {@code saved} (12 floats) and runs every hook. Returns whether positions must be
     * restored. A hook that throws is skipped (positions are restored anyway).
     */
    public static boolean before(ShadeHook[] hooks, BlockState state, BlockPos pos, MutableQuadView quad, float[] saved) {
        for (int i = 0; i < 4; i++) {
            saved[i * 3] = quad.x(i);
            saved[i * 3 + 1] = quad.y(i);
            saved[i * 3 + 2] = quad.z(i);
        }
        boolean moved = false;
        for (ShadeHook h : hooks) {
            try {
                moved |= h.beforeShade(state, pos, quad);
            } catch (RuntimeException | LinkageError e) {
                moved = true;
                logOnce(h, e);
            }
        }
        return moved;
    }

    /** Puts back the positions {@link #before} saved, only where they changed (pos() invalidates cached geometry). */
    public static void restore(MutableQuadView quad, float[] saved) {
        for (int i = 0; i < 4; i++) {
            float x = saved[i * 3], y = saved[i * 3 + 1], z = saved[i * 3 + 2];
            if (quad.x(i) != x || quad.y(i) != y || quad.z(i) != z)
                quad.pos(i, x, y, z);
        }
    }

    private static volatile boolean logged;

    private static void logOnce(ShadeHook h, Throwable e) {
        if (!logged) {
            logged = true;
            net.vulkanmod.Initializer.LOGGER.error("Iroh api: shade hook {} threw (logged once)", h.getClass().getName(), e);
        }
    }
}
