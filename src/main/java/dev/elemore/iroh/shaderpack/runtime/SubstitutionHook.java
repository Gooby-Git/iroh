package dev.elemore.iroh.shaderpack.runtime;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.elemore.iroh.debug.DebugSwitches;

import java.util.HashSet;
import java.util.Set;

/** game-side glue for {@link Substitution} (loaded only once observe mode is switched on) */
public final class SubstitutionHook {

    private SubstitutionHook() {}

    private static volatile Set<String> rasterMods = Set.of();
    private static volatile String rasterModsSource = "";

    public static void observe(String shader, VertexFormat format) {
        String src = DebugSwitches.getOr(PackSwitches.RASTER_MODS, "");
        if (!src.equals(rasterModsSource)) {
            Set<String> s = new HashSet<>();
            for (String m : src.split(","))
                if (!m.isBlank())
                    s.add(m.trim());
            rasterMods = s;
            rasterModsSource = src;
        }
        Substitution.observe(shader, format == null ? null : format.getElementAttributeNames(),
                dev.elemore.iroh.apiimpl.FrameHooks.context().inWorld(), rasterMods);
    }
}
