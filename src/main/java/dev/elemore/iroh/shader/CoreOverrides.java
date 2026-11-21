package dev.elemore.iroh.shader;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Iroh (P1-10, RQ-14, td-3a945c): does a resource pack or a mod override one of the vanilla core shaders Iroh has a
 * built-in Vulkan version of? Vanilla builds every {@code ShaderInstance} from {@code shaders/core/<name>.json} plus the
 * {@code .vsh/.fsh} it names plus their {@code #moj_import}s, all read through the resource manager, so the highest
 * pack wins file by file. Iroh's built-in replaces all of that, so a pack's version only takes effect if Iroh notices:
 * when ANY of those files (json, either stage, any include they import, nested) is won by a pack other than
 * {@code vanilla}, the whole shader is converted from the resource manager's view (pack files where the pack has them,
 * vanilla's own GLSL elsewhere) through the P1-2a translator instead of using the built-in.
 * <p>
 * Pure (no game state): ShaderInstanceM calls it with the live resource provider; shaderHarness / coreOverrideTest call
 * it with an offline layer stack.
 */
public final class CoreOverrides {
    /** {@code Resource.sourcePackId()} of the client jar's own resources. */
    public static final String VANILLA = "vanilla";

    /**
     * Shaders Iroh itself draws with outside {@code ShaderInstance.apply()} (DrawUtil binds the blit pipeline's UBO
     * directly, without vanilla's sampler setup), so a converted pipeline would see no texture: they keep the built-in.
     */
    public static final Set<String> NOT_OVERRIDABLE = Set.of("blit_screen");

    private CoreOverrides() {
    }

    /** What overrides a shader: the first non-vanilla pack found and the file it won, or null when none. */
    public record Hit(String pack, String file) {
    }

    /**
     * @param name         the ShaderInstance name (vanilla core names have no namespace)
     * @param vertexName   the json's "vertex" program
     * @param fragmentName the json's "fragment" program
     */
    public static Hit find(ShaderSources sources, String name, String vertexName, String fragmentName) {
        if (name.indexOf(':') >= 0 || NOT_OVERRIDABLE.contains(name))
            return null;
        Set<String> ids = new LinkedHashSet<>();
        ids.add(id(ShaderConversion.coreProgramPath(name, ".json")));
        String vPath = ShaderConversion.coreProgramPath(vertexName, ".vsh");
        String fPath = ShaderConversion.coreProgramPath(fragmentName, ".fsh");
        ids.add(id(vPath));
        ids.add(id(fPath));
        // every include the winning sources import (nested), exactly as the converter will resolve them
        Recording rec = new Recording(sources);
        for (String p : new String[]{vPath, fPath}) {
            String src = sources.read(id(p));
            if (src == null)
                continue;
            try {
                MojImportPreprocessor.forCoreProgram(rec, p).processToString(src);
            } catch (Throwable ignored) {
                // a broken import is the converter's business; it reports it
            }
        }
        ids.addAll(rec.read);
        for (String id : ids) {
            String pack = sources.packOf(id);
            if (pack != null && !pack.equals("?") && !pack.equals(VANILLA))
                return new Hit(pack, id);
        }
        return null;
    }

    private static String id(String path) {
        return path.indexOf(':') >= 0 ? path : "minecraft:" + path;
    }

    private static final class Recording implements ShaderSources {
        final ShaderSources inner;
        final Set<String> read = new LinkedHashSet<>();

        Recording(ShaderSources inner) {
            this.inner = inner;
        }

        @Override
        public String read(String id) {
            read.add(id);
            return inner.read(id);
        }

        @Override
        public String packOf(String id) {
            return inner.packOf(id);
        }
    }
}
