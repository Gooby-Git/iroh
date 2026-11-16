package dev.elemore.iroh.glshim;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * owo-lib's braid.TextureUtilMixin records texture sizes from TextureUtil.prepareImage. Iroh cancels that method at HEAD,
 * so the owo injection sits in dead code; this forwards the one call it makes, when owo is present.
 */
public final class OwoCompat {
    private static final MethodHandle REGISTER = find();

    private OwoCompat() {}

    private static MethodHandle find() {
        try {
            Class<?> c = Class.forName("io.wispforest.owo.braid.util.TextureSizeLookup", false, OwoCompat.class.getClassLoader());
            return MethodHandles.publicLookup().findStatic(c, "_registerTextureSize", MethodType.methodType(void.class, int.class, int.class, int.class));
        } catch (Throwable t) {
            return null;
        }
    }

    public static void registerTextureSize(int id, int width, int height) {
        if (REGISTER == null) return;
        try {
            REGISTER.invokeExact(id, width, height);
        } catch (Throwable ignored) {
        }
    }
}
