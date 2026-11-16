package dev.elemore.iroh.shader;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import net.minecraft.FileUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves {@code #moj_import} the way Minecraft 1.21.1 + Fabric API resolve it, by reusing vanilla's own
 * {@link GlslPreprocessor} (the regex, #version merging, #line directives, comment handling) and only
 * supplying {@link #applyImport}. Written from vanilla's anonymous preprocessor in
 * {@code ShaderInstance.getOrCreate} and Fabric API's {@code ShaderProgramImportProcessorMixin}:
 * <ul>
 *   <li>{@code <file>}: {@code shaders/include/<file>} in the {@code minecraft} namespace;</li>
 *   <li>{@code <ns:file>} (Fabric): {@code ns:shaders/include/<file>};</li>
 *   <li>{@code "file"}: relative to the directory of the program file (always the top-level program's
 *       directory, also inside nested imports, as vanilla does);</li>
 *   <li>each path is imported once per program; a second import of the same path yields nothing;</li>
 *   <li>a missing import becomes {@code #error <message>} (vanilla logs and does the same).</li>
 * </ul>
 * Post-effect programs ({@link #forEffectProgram()}) do not support imports at all in vanilla
 * ({@code EffectProgram.PREPROCESSOR}): every import becomes {@code #error Import statement not supported}.
 */
public final class MojImportPreprocessor extends GlslPreprocessor {
    private final ShaderSources sources;
    private final String programDir; // e.g. "shaders/core/" or "charta:shaders/core/"
    private final boolean effect;
    private final Set<String> importedPaths = new HashSet<>();
    private String lastError;

    private MojImportPreprocessor(ShaderSources sources, String programPath, boolean effect) {
        this.sources = sources;
        this.programDir = programPath == null ? "" : FileUtil.getFullResourcePath(programPath);
        this.effect = effect;
    }

    /** @param programPath the program file as vanilla+Fabric name it: {@code shaders/core/x.vsh} or {@code ns:shaders/core/x.vsh} */
    public static MojImportPreprocessor forCoreProgram(ShaderSources sources, String programPath) {
        return new MojImportPreprocessor(sources, programPath, false);
    }

    public static MojImportPreprocessor forEffectProgram() {
        return new MojImportPreprocessor(null, null, true);
    }

    /** Runs vanilla's preprocessor and joins its output. */
    public String processToString(String source) {
        List<String> parts = this.process(source);
        StringBuilder sb = new StringBuilder(source.length() + 256);
        for (String s : parts)
            sb.append(s);
        return sb.toString();
    }

    /** @return the message of the last failed import, or null */
    public String lastError() {
        return lastError;
    }

    @Override
    public String applyImport(boolean relative, String name) {
        if (effect)
            return "#error Import statement not supported";

        String path = FileUtil.normalizeResourcePath((relative ? programDir : "shaders/include/") + name);

        // Fabric API: <ns:file> -> ns:shaders/include/file (FabricShaderProgram.rewriteAsId)
        if (!relative && name.indexOf(':') >= 0) {
            int c = name.indexOf(':');
            String ns = name.substring(0, c);
            String p = name.substring(c + 1);
            path = ns + ":" + path.replace(name, p);
        }

        if (!importedPaths.add(path))
            return null;

        String id = path.indexOf(':') >= 0 ? path : "minecraft:" + path;
        String text = sources.read(id);
        if (text == null) {
            lastError = "Could not open GLSL import " + id;
            return "#error " + lastError;
        }
        return text;
    }
}
