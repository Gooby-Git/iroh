package dev.elemore.iroh.glshim;

import org.lwjgl.opengl.GLCapabilities;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * A GLCapabilities with every flag false and every function address 0: "this machine has no OpenGL".
 * LWJGL's GL.getCapabilities() throws when no context was ever made current, which is always the case under Iroh;
 * mods that read it at start-up (GPUBooster in a RenderSystem.recordRenderCall, XaeroWorldMap's MapLimiter,
 * AsyncParticles' Backends, WaterMedia) then crash the render thread. Reading all-false flags makes them pick
 * their fallback paths. Built with Unsafe.allocateInstance because the constructor wants a function provider.
 */
public final class GlCaps {
    private static final GLCapabilities EMPTY = build();

    private GlCaps() {}

    /** @return the shared all-false capabilities, or null if it could not be built (callers then leave LWJGL's behaviour alone) */
    public static GLCapabilities empty() {
        return EMPTY;
    }

    private static GLCapabilities build() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (GLCapabilities) ((Unsafe) f.get(null)).allocateInstance(GLCapabilities.class);
        } catch (Throwable t) {
            return null;
        }
    }
}
