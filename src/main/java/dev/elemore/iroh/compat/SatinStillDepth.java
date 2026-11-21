package dev.elemore.iroh.compat;

import java.lang.reflect.Field;
import java.util.function.IntConsumer;

/**
 * Iroh (rc13, td-598433): releases Satin's per-RenderTarget still-depth texture ({@code satin$stillDepthTexture}, an int
 * field Satin's DepthGlFramebufferMixin merges into RenderTarget). See {@code RenderTargetSatinM} for why. The field is
 * found by reflection on first use: it is added by another mod's mixin, so it cannot be shadowed from a mixin applied
 * before Satin's. A missing field (other Satin version) turns this into a no-op, logged once.
 */
public final class SatinStillDepth {
    private static final String FIELD = "satin$stillDepthTexture";
    private static volatile Field field;
    private static volatile boolean resolved;

    /** How the texture is released; TextureUtil.releaseTextureId in game, replaceable for the offline test. */
    public static IntConsumer RELEASE = id -> com.mojang.blaze3d.platform.TextureUtil.releaseTextureId(id);

    public static long releasedOnDestroy, releasedOnCreate;
    private static boolean loggedCreate;

    private SatinStillDepth() {}

    public static Field find(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() == int.class && (f.getName().equals(FIELD) || f.getName().endsWith("stillDepthTexture"))) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    /** Releases the target's still-depth texture if it holds one; sets the field to -1 (Satin's "none"). */
    public static void release(Object target, String where) {
        Field f = field;
        if (!resolved) {
            synchronized (SatinStillDepth.class) {
                if (!resolved) {
                    f = field = find(com.mojang.blaze3d.pipeline.RenderTarget.class);
                    resolved = true;
                    if (f == null)
                        net.vulkanmod.Initializer.LOGGER.warn("Iroh: Satin is loaded but RenderTarget has no {} field; still-depth leak guard off", FIELD);
                }
            }
        }
        if (f == null) return;
        releaseVia(f, target, where);
    }

    /** The release itself (field given: offline test). */
    public static void releaseVia(Field f, Object target, String where) {
        int id;
        try {
            id = f.getInt(target);
            if (id <= 0) return; // -1 = none; 0 is never a texture name
            f.setInt(target, -1);
        } catch (IllegalAccessException e) {
            return;
        }
        RELEASE.accept(id);
        if (where.equals("createBuffers")) {
            releasedOnCreate++;
            if (!loggedCreate) {
                loggedCreate = true;
                net.vulkanmod.Initializer.LOGGER.info("Iroh: released a Satin still-depth texture (GL {}) still alive at createBuffers "
                        + "(its destroyBuffers release did not run); further ones are counted silently", id);
            }
        } else {
            releasedOnDestroy++;
        }
    }
}
