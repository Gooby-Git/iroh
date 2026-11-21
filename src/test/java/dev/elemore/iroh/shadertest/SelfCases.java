package dev.elemore.iroh.shadertest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.elemore.iroh.shader.ShaderConversion;
import dev.elemore.iroh.shader.ShaderSources;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written shaders for the failure classes the pack does not happen to contain (P1-2a). Each case
 * must convert and compile; the harness prints them as kind {@code case}. Add a case whenever a real
 * mod shader fails in a new way, so the class stays covered after that mod updates or leaves the pack.
 */
final class SelfCases {
    private SelfCases() {}

    private static final String JSON_BASIC = """
            { "vertex": "%s", "fragment": "%s",
              "samplers": [ { "name": "Sampler0" }, { "name": "Sampler2" } ],
              "uniforms": [
                { "name": "ModelViewMat", "type": "matrix4x4", "count": 16, "values": [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] },
                { "name": "ProjMat", "type": "matrix4x4", "count": 16, "values": [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1] },
                { "name": "ColorModulator", "type": "float", "count": 4, "values": [1,1,1,1] } ] }
            """;

    record Case(String id, Map<String, String> files) {}

    static List<String> run(String show, StringBuilder detail) {
        List<Case> cases = new ArrayList<>();

        // several declarations per line, comments everywhere, trailing tokens, arrays, uniform not in JSON,
        // uniform with initializer, struct uniform, mat3 uniform, sampler not in JSON
        cases.add(new Case("case:declarations", Map.of(
                "case:shaders/core/declarations.json", JSON_BASIC.formatted("case:declarations", "case:declarations"),
                "case:shaders/core/declarations.vsh", """
                        #version 150 // trailing comment
                        /* block
                           comment */ in vec3 Position; in vec4 Color;
                        in vec2 UV0 /* inline */ ;
                        uniform mat4 ModelViewMat, ProjMat; // two at once
                        uniform mat3 IViewRotMat;
                        uniform float Weights[4];
                        struct Light { vec3 dir; float power; };
                        uniform Light Sun;
                        uniform float Gain = 2.0;
                        out vec4 vertexColor; out vec2 texCoord0;
                        flat out int which;
                        void main() {
                            gl_Position = ProjMat * ModelViewMat * vec4(IViewRotMat * Position, 1.0);
                            vertexColor = Color * Gain * Weights[1] * Sun.power; texCoord0 = UV0; which = gl_VertexID % 4;
                        }
                        """,
                "case:shaders/core/declarations.fsh", """
                        #version 150
                        uniform sampler2D Sampler0; uniform sampler2D Sampler2, Extra;
                        uniform vec4 ColorModulator;
                        in vec4 vertexColor;
                        in vec2 texCoord0;
                        flat in int which;
                        out vec4 fragColor;
                        void main() {
                            vec4 sample = texture(Sampler0, texCoord0) + texture(Sampler2, texCoord0) * 0.0 + texture(Extra, texCoord0) * 0.0;
                            fragColor = sample * vertexColor * ColorModulator * float(which + 1);
                        }
                        """)));

        // #moj_import across namespaces and relative, nested, imported twice
        cases.add(new Case("case:imports", Map.of(
                "case:shaders/core/imports.json", JSON_BASIC.formatted("case:imports", "case:imports"),
                "case:shaders/core/imports.vsh", """
                        #version 150
                        #moj_import <fog.glsl>
                        #moj_import <case:util.glsl>
                        #moj_import "local.glsl"
                        in vec3 Position;
                        uniform mat4 ModelViewMat;
                        uniform mat4 ProjMat;
                        out float vertexDistance;
                        void main() { gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0); vertexDistance = fog_distance(Position, 0) * util_one() * local_one(); }
                        """,
                "case:shaders/core/imports.fsh", """
                        #version 150
                        #moj_import <case:util.glsl>
                        #moj_import <case:util.glsl>
                        in float vertexDistance;
                        uniform vec4 ColorModulator;
                        out vec4 fragColor;
                        void main() { fragColor = ColorModulator * util_one() * vertexDistance; }
                        """,
                "case:shaders/include/util.glsl", """
                        #version 150
                        #moj_import <case:nested.glsl>
                        float util_one() { return nested_one(); }
                        """,
                "case:shaders/include/nested.glsl", "float nested_one() { return 1.0; }\n",
                "case:shaders/core/local.glsl", "float local_one() { return 1.0; }\n")));

        // GLSL 1.20 style: attribute/varying, texture2D, gl_FragColor, keyword identifiers
        cases.add(new Case("case:legacy120", Map.of(
                "case:shaders/core/legacy120.json", JSON_BASIC.formatted("case:legacy120", "case:legacy120"),
                "case:shaders/core/legacy120.vsh", """
                        #version 120
                        attribute vec3 Position;
                        attribute vec2 UV0;
                        uniform mat4 ModelViewMat;
                        uniform mat4 ProjMat;
                        varying vec2 texCoord0;
                        void main() { float patch = 1.0; gl_Position = ProjMat * ModelViewMat * vec4(Position * patch, 1.0); texCoord0 = UV0; }
                        """,
                "case:shaders/core/legacy120.fsh", """
                        #version 120
                        uniform sampler2D Sampler0;
                        uniform vec4 ColorModulator;
                        varying vec2 texCoord0;
                        void main() { gl_FragColor = texture2D(Sampler0, texCoord0) * ColorModulator; }
                        """)));

        // explicit layouts kept, multiple fragment outputs, varying declared in a different order per stage
        cases.add(new Case("case:layouts", Map.of(
                "case:shaders/core/layouts.json", JSON_BASIC.formatted("case:layouts", "case:layouts"),
                "case:shaders/core/layouts.vsh", """
                        #version 330 core
                        layout(location = 0) in vec3 Position;
                        uniform mat4 ModelViewMat;
                        uniform mat4 ProjMat;
                        out vec3 a;
                        smooth out vec4 b;
                        void main() { gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0); a = Position; b = vec4(1.0); }
                        """,
                "case:shaders/core/layouts.fsh", """
                        #version 330 core
                        smooth in vec4 b;
                        in vec3 a;
                        uniform vec4 ColorModulator;
                        layout(location = 0) out vec4 c0;
                        out vec4 c1;
                        void main() { c0 = b * ColorModulator; c1 = vec4(a, 1.0); }
                        """)));

        List<String> out = new ArrayList<>();
        for (Case c : cases) {
            Map<String, String> files = new HashMap<>(c.files());
            ShaderSources src = new ShaderSources() {
                @Override
                public String read(String id) {
                    if (files.containsKey(id))
                        return files.get(id);
                    // vanilla includes from the client jar on the classpath
                    String path = "/assets/" + id.replace(':', '/');
                    try (var in = SelfCases.class.getResourceAsStream(path)) {
                        return in == null ? null : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        return null;
                    }
                }

                @Override
                public String packOf(String id) {
                    return "selftest";
                }
            };
            String name = c.id().substring("case:".length());
            JsonObject json = JsonParser.parseString(files.get("case:shaders/core/" + name + ".json")).getAsJsonObject();
            ShaderConversion.Result r = ShaderConversion.convert(ShaderConversion.Input.core(c.id(), json, null), src, true);
            if (show != null && show.equals(c.id()) && r.output != null) {
                detail.append("\n===== ").append(c.id()).append(" vertex =====\n").append(r.output.vertex());
                detail.append("\n===== ").append(c.id()).append(" fragment =====\n").append(r.output.fragment());
                detail.append("\n===== zero-filled: ").append(r.output.zeroUniforms()).append(" notes: ").append(r.output.notes()).append('\n');
            }
            if (r.vertex != null) r.vertex.free();
            if (r.fragment != null) r.fragment.free();
            out.add(String.format("%s %-7s %-55s %-20s%s", r.ok ? "PASS" : "FAIL", "case", c.id(), "selftest",
                    r.ok ? "" : " [" + r.stage + "] " + r.shortMessage()).stripTrailing());
        }

        // rc8 (td-40ae94): a pack shader declaring the integer lightmap element as a float vector
        {
            Map<String, String> files = Map.of(
                    "case:shaders/core/floatuv2.json", JSON_BASIC.formatted("case:floatuv2", "case:floatuv2"),
                    "case:shaders/core/floatuv2.vsh", """
                            #version 150
                            in vec3 Position; in vec4 Color; in vec2 UV0; in vec2 UV2;
                            uniform mat4 ModelViewMat; uniform mat4 ProjMat;
                            uniform sampler2D Sampler2;
                            out vec4 vertexColor; out vec2 texCoord0;
                            void main() {
                                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                                vertexColor = Color * texture(Sampler2, clamp(UV2 / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
                                texCoord0 = UV0;
                            }
                            """,
                    "case:shaders/core/floatuv2.fsh", """
                            #version 150
                            uniform sampler2D Sampler0; uniform vec4 ColorModulator;
                            in vec4 vertexColor; in vec2 texCoord0; out vec4 fragColor;
                            void main() { fragColor = texture(Sampler0, texCoord0) * vertexColor * ColorModulator; }
                            """);
            dev.elemore.iroh.shader.ShaderSources src2 = new dev.elemore.iroh.shader.ShaderSources() {
                @Override public String read(String id) { return files.get(id); }
                @Override public String packOf(String id) { return "selftest"; }
            };
            JsonObject json2 = JsonParser.parseString(files.get("case:shaders/core/floatuv2.json")).getAsJsonObject();
            ShaderConversion.Result r = ShaderConversion.convert(ShaderConversion.Input.core("case:floatuv2", json2,
                    List.of("Position", "Color", "UV0", "UV2")), src2, true);
            boolean converted = r.ok && r.output != null && r.output.vertex().contains("layout(location = 3) in ivec2 iroh_int_UV2;")
                    && r.output.vertex().contains("UV2 = vec2(iroh_int_UV2);");
            if (show != null && show.equals("case:floatuv2") && r.output != null)
                detail.append("\n===== case:floatuv2 vertex =====\n").append(r.output.vertex());
            if (r.vertex != null) r.vertex.free();
            if (r.fragment != null) r.fragment.free();
            out.add(String.format("%s %-7s %-55s %-20s%s", converted ? "PASS" : "FAIL", "case", "case:floatuv2", "selftest",
                    converted ? "" : " [" + r.stage + "] float UV2 not converted from the integer attribute " + r.shortMessage()).stripTrailing());
        }

        try {
            ShaderConversion.Result f = ShaderConversion.fallback("selftest");
            f.vertex.free();
            f.fragment.free();
            out.add(String.format("PASS %-7s %-55s %-20s", "case", "case:fallback", "selftest").stripTrailing());
        } catch (Throwable t) {
            out.add(String.format("FAIL %-7s %-55s %-20s [compile] %s", "case", "case:fallback", "selftest", t.getMessage()));
        }
        return out;
    }
}
