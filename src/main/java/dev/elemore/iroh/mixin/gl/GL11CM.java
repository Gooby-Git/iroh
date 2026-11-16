package dev.elemore.iroh.mixin.gl;

import dev.elemore.iroh.glshim.GlStrings;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Core-profile GL11 entry points that mods call directly and that would abort the JVM with
 * "No context is current" under Iroh. Only what boot needs today (P1-1); the full shim is P1-2.
 * GL11.glGetString delegates here, so both are covered.
 */
@Mixin(value = GL11C.class, remap = false)
public class GL11CM {

    /**
     * @author Iroh
     * @reason no GL context; first seen from ImmediatelyFast.windowInit (vendor workaround check)
     */
    @Nullable
    @NativeType("GLubyte const *")
    @Overwrite(remap = false)
    public static String glGetString(@NativeType("GLenum") int name) {
        return GlStrings.get(name);
    }

    /**
     * @author Iroh
     * @reason no GL context; ImmediatelyFast's MixinGL11 (when its error-check switch is on) forwards GL11.glGetError here
     */
    @NativeType("GLenum")
    @Overwrite(remap = false)
    public static int glGetError() {
        return 0;
    }
}
