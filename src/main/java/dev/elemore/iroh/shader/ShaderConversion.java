package dev.elemore.iroh.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import org.lwjgl.vulkan.VK11;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The one code path that turns a mod or resource-pack shader (core {@code ShaderInstance} or post
 * {@code EffectInstance} program) into SPIR-V. Used by {@code ShaderInstanceM}, {@code EffectInstanceM}
 * and by the offline harness ({@code ./gradlew shaderHarness}), so a PASS in the harness means the game
 * gets the same SPIR-V. Never throws: failures come back as a {@link Result} naming the stage.
 */
public final class ShaderConversion {

    public enum Kind { CORE, PROGRAM }

    /** What vanilla reads from the shader JSON, plus the vertex format's attribute names (null offline). */
    public record Input(Kind kind, String id, String vertexName, String fragmentName,
                        List<String> samplers, Set<String> uniforms, List<String> attributeNames) {

        public static Input core(String id, JsonObject json, List<String> attributeNames) {
            return new Input(Kind.CORE, id, json.get("vertex").getAsString(), json.get("fragment").getAsString(),
                    samplerNames(json), uniformNames(json), attributeNames);
        }

        public static Input program(String id, JsonObject json) {
            List<String> attrs = null;
            if (json.has("attributes")) {
                attrs = new ArrayList<>();
                for (JsonElement e : json.getAsJsonArray("attributes"))
                    attrs.add(e.getAsString());
            }
            return new Input(Kind.PROGRAM, id, json.get("vertex").getAsString(), json.get("fragment").getAsString(),
                    samplerNames(json), uniformNames(json), attrs != null ? attrs : List.of("Position"));
        }
    }

    public static final class Result {
        public final String id;
        public final boolean ok;
        public final String stage;    // json, load-vsh, load-fsh, import-vsh, import-fsh, translate-vsh/fsh, compile-vsh/fsh
        public final String message;
        public final GlslTranslator.Output output;
        public final SPIRVUtils.SPIRV vertex;
        public final SPIRVUtils.SPIRV fragment;
        public final String pack;     // pack that provides the vertex program

        Result(String id, boolean ok, String stage, String message, GlslTranslator.Output output,
               SPIRVUtils.SPIRV vertex, SPIRVUtils.SPIRV fragment, String pack) {
            this.id = id;
            this.ok = ok;
            this.stage = stage;
            this.message = message;
            this.output = output;
            this.vertex = vertex;
            this.fragment = fragment;
            this.pack = pack;
        }

        static Result fail(String id, String stage, String message, String pack) {
            return new Result(id, false, stage, message, null, null, null, pack);
        }

        /** First line of the message, for one-line logs. */
        public String shortMessage() {
            if (message == null)
                return "";
            String m = message.strip();
            for (String line : m.split("\n")) {
                String l = line.strip();
                // shaderc's first line is "Failed to compile shader x into SPIR-V:"; the useful one follows
                if (!l.isEmpty() && !l.startsWith("Failed to compile shader"))
                    return l.length() > 240 ? l.substring(0, 240) : l;
            }
            return m.length() > 240 ? m.substring(0, 240) : m;
        }
    }

    private ShaderConversion() {}

    /** Resource id of a core program file, as vanilla + Fabric API resolve it. */
    public static String coreProgramPath(String programName, String ext) {
        int c = programName.indexOf(':');
        if (c >= 0)
            return programName.substring(0, c) + ":shaders/core/" + programName.substring(c + 1) + ext;
        return "shaders/core/" + programName + ext;
    }

    /** Resource id of a post-effect program file (Iroh's EffectInstanceM decomposition). */
    public static String effectProgramPath(String programName, String ext) {
        int c = programName.indexOf(':');
        if (c >= 0)
            return (c == 0 ? "minecraft" : programName.substring(0, c)) + ":shaders/program/" + programName.substring(c + 1) + ext;
        return "minecraft:shaders/program/" + programName + ext;
    }

    private static String asId(String path) {
        return path.indexOf(':') >= 0 ? path : "minecraft:" + path;
    }

    public static Result convert(Input in, ShaderSources sources, boolean compile) {
        String vPath, fPath;
        if (in.kind == Kind.CORE) {
            vPath = coreProgramPath(in.vertexName, ".vsh");
            fPath = coreProgramPath(in.fragmentName, ".fsh");
        } else {
            vPath = effectProgramPath(in.vertexName, ".vsh");
            fPath = effectProgramPath(in.fragmentName, ".fsh");
        }
        String pack = sources.packOf(asId(vPath));

        String vRaw = sources.read(asId(vPath));
        if (vRaw == null)
            return Result.fail(in.id, "load-vsh", "missing " + asId(vPath), pack);
        String fRaw = sources.read(asId(fPath));
        if (fRaw == null)
            return Result.fail(in.id, "load-fsh", "missing " + asId(fPath), pack);

        String vSrc, fSrc;
        try {
            MojImportPreprocessor vp = in.kind == Kind.CORE ? MojImportPreprocessor.forCoreProgram(sources, vPath)
                    : MojImportPreprocessor.forEffectProgram();
            vSrc = vp.processToString(vRaw);
            if (vp.lastError() != null)
                return Result.fail(in.id, "import-vsh", vp.lastError(), pack);
        } catch (Throwable t) {
            return Result.fail(in.id, "import-vsh", String.valueOf(t), pack);
        }
        try {
            MojImportPreprocessor fp = in.kind == Kind.CORE ? MojImportPreprocessor.forCoreProgram(sources, fPath)
                    : MojImportPreprocessor.forEffectProgram();
            fSrc = fp.processToString(fRaw);
            if (fp.lastError() != null)
                return Result.fail(in.id, "import-fsh", fp.lastError(), pack);
        } catch (Throwable t) {
            return Result.fail(in.id, "import-fsh", String.valueOf(t), pack);
        }

        GlslTranslator.Output out;
        try {
            out = new GlslTranslator(in.attributeNames, in.samplers, in.uniforms, ShaderConversion::offsets)
                    .translate(vSrc, fSrc);
        } catch (GlslTranslator.TranslationException e) {
            return Result.fail(in.id, e.stage == GlslTranslator.Stage.VERTEX ? "translate-vsh" : "translate-fsh",
                    e.getMessage(), pack);
        } catch (Throwable t) {
            return Result.fail(in.id, "translate", String.valueOf(t), pack);
        }

        if (!compile)
            return new Result(in.id, true, null, null, out, null, null, pack);

        SPIRVUtils.SPIRV v, f;
        try {
            v = SPIRVUtils.compileShader(in.id + ".vsh", out.vertex(), SPIRVUtils.ShaderKind.VERTEX_SHADER);
        } catch (Throwable t) {
            return new Result(in.id, false, "compile-vsh", t.getMessage(), out, null, null, pack);
        }
        try {
            f = SPIRVUtils.compileShader(in.id + ".fsh", out.fragment(), SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
        } catch (Throwable t) {
            v.free();
            return new Result(in.id, false, "compile-fsh", t.getMessage(), out, null, null, pack);
        }
        return new Result(in.id, true, null, null, out, v, f, pack);
    }

    // ---------------------------------------------------------------- uniform block

    /** std140 offsets in bytes, computed by the same builder that makes the runtime UBO. */
    static int[] offsets(List<String[]> typeAndName) {
        UBO ubo = buildUbo(typeAndName);
        int[] out = new int[typeAndName.size()];
        List<Uniform> us = ubo.getUniforms();
        for (int i = 0; i < out.length; i++)
            out[i] = us.get(i).getOffset() * 4;
        return out;
    }

    private static UBO buildUbo(List<String[]> typeAndName) {
        AlignedStruct.Builder b = new AlignedStruct.Builder();
        for (String[] tn : typeAndName)
            b.addUniformInfo(Uniform.createUniformInfo(tn[0], tn[1]));
        return b.buildUBO(0, VK11.VK_SHADER_STAGE_ALL);
    }

    /** The runtime UBO for a translated shader (binding 0, all stages), members in block order. */
    public static UBO buildUbo(GlslTranslator.Output out) {
        List<String[]> tn = new ArrayList<>();
        for (GlslTranslator.UniformDecl u : out.uniforms())
            tn.add(new String[]{u.type(), u.name()});
        UBO ubo = buildUbo(tn);
        // the layout must be exactly what the GLSL block declares
        List<Uniform> us = ubo.getUniforms();
        for (int i = 0; i < us.size(); i++)
            if (us.get(i).getOffset() * 4 != out.uniforms().get(i).offsetBytes())
                throw new IllegalStateException("UBO layout drift for " + out.uniforms().get(i).name());
        return ubo;
    }

    // ---------------------------------------------------------------- fallback

    public static final String FALLBACK_UNIFORM = "IrohFallback";

    /**
     * A program that draws nothing: every vertex lands outside the clip volume. Used when a shader cannot
     * be converted, so the ShaderInstance still has a pipeline, programs and uniforms and nobody sees a null.
     */
    public static Result fallback(String id) {
        String block = "layout(std140, binding = 0) uniform IrohUniforms {\n    layout(offset = 0) vec4 "
                + FALLBACK_UNIFORM + ";\n};\n";
        String vsh = "#version 450\n" + block
                + "void main() {\n    gl_Position = vec4(2.0, 2.0, 2.0, 1.0) + " + FALLBACK_UNIFORM + " * 0.0;\n}\n";
        String fsh = "#version 450\n" + block
                + "layout(location = 0) out vec4 fragColor;\nvoid main() {\n    fragColor = " + FALLBACK_UNIFORM + ";\n}\n";
        GlslTranslator.Output out = new GlslTranslator.Output(vsh, fsh,
                List.of(new GlslTranslator.UniformDecl("vec4", FALLBACK_UNIFORM, 0)), List.of(), List.of(), List.of());
        SPIRVUtils.SPIRV v = SPIRVUtils.compileShader("iroh_fallback.vsh", vsh, SPIRVUtils.ShaderKind.VERTEX_SHADER);
        SPIRVUtils.SPIRV f = SPIRVUtils.compileShader("iroh_fallback.fsh", fsh, SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
        return new Result(id, true, null, null, out, v, f, "iroh");
    }

    // ---------------------------------------------------------------- JSON helpers

    public static List<String> samplerNames(JsonObject json) {
        List<String> out = new ArrayList<>();
        JsonArray arr = json.has("samplers") ? json.getAsJsonArray("samplers") : null;
        if (arr != null)
            for (JsonElement e : arr)
                out.add(e.getAsJsonObject().get("name").getAsString());
        return out;
    }

    public static Set<String> uniformNames(JsonObject json) {
        Set<String> out = new LinkedHashSet<>();
        JsonArray arr = json.has("uniforms") ? json.getAsJsonArray("uniforms") : null;
        if (arr != null)
            for (JsonElement e : arr)
                out.add(e.getAsJsonObject().get("name").getAsString());
        return out;
    }
}
