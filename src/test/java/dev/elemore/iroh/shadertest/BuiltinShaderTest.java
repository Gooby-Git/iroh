package dev.elemore.iroh.shadertest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.vulkanmod.render.shader.ShaderLoadUtil;
import net.vulkanmod.vulkan.shader.SPIRVUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * rc3: Iroh's OWN Vulkan GLSL (assets/vulkanmod/shaders/{basic,core}), which shaderHarness does not cover.
 * (1) Every built-in program compiles to SPIR-V through the runtime path (ShaderLoadUtil source lookup + SPIRVUtils).
 * (2) Fog parity: the bodies of linear_fog, linear_fog_fade and fog_distance in Iroh's include/fog.glsl equal vanilla
 *     1.21.1's include/fog.glsl (whitespace and comments ignored), so terrain, clouds and entities fog exactly as vanilla.
 * {@code ./gradlew builtinShaderTest}; last line "# builtin PASS n/n".
 */
public final class BuiltinShaderTest {
    private static int pass, fail;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : "src/main/resources/assets/vulkanmod/shaders");
        int ok = 0, bad = 0;
        List<String> failures = new ArrayList<>();
        for (String kind : new String[]{"basic", "core"}) {
            List<Path> jsons;
            try (Stream<Path> w = Files.walk(root.resolve(kind))) {
                jsons = w.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            }
            for (Path j : jsons) {
                String name = j.getFileName().toString().replace(".json", "");
                JsonObject cfg = JsonParser.parseString(Files.readString(j)).getAsJsonObject();
                String v = cfg.has("vertex") ? cfg.get("vertex").getAsString() : name;
                String f = cfg.has("fragment") ? cfg.get("fragment").getAsString() : name;
                String base = ShaderLoadUtil.class.getResource("/assets/vulkanmod").toExternalForm() + "/shaders/" + kind;
                for (String[] st : new String[][]{{v, "v"}, {f, "f"}}) {
                    SPIRVUtils.ShaderKind sk = st[1].equals("v") ? SPIRVUtils.ShaderKind.VERTEX_SHADER : SPIRVUtils.ShaderKind.FRAGMENT_SHADER;
                    String shaderName = st[0].substring(st[0].lastIndexOf('/') + 1);
                    try {
                        String src = ShaderLoadUtil.getShaderSource(base, name, shaderName, sk);
                        if (src == null) throw new IllegalStateException("no source");
                        SPIRVUtils.compileShader(shaderName, src, sk).free();
                        ok++;
                    } catch (Throwable t) {
                        bad++;
                        failures.add(kind + "/" + name + " " + st[1] + ": " + String.valueOf(t.getMessage()).lines().findFirst().orElse(""));
                    }
                }
            }
        }
        failures.forEach(s -> System.out.println("  " + s));
        check("every built-in program compiles (" + ok + " stages ok, " + bad + " failed)", bad == 0);

        String iroh = Files.readString(root.resolve("include/fog.glsl"));
        String vanilla;
        try (InputStream in = net.minecraft.client.renderer.ShaderInstance.class.getResourceAsStream("/assets/minecraft/shaders/include/fog.glsl")) {
            vanilla = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String fn : new String[]{"linear_fog", "linear_fog_fade", "fog_distance"})
            check("include/fog.glsl " + fn + " is vanilla's", body(iroh, fn) != null && body(iroh, fn).equals(body(vanilla, fn)));

        System.out.println("# builtin " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        if (fail != 0) System.exit(1);
    }

    /** The normalised text of a function (signature to matching brace), comments and whitespace removed. */
    private static String body(String src, String fn) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        Matcher m = Pattern.compile("\\b\\w+\\s+" + fn + "\\s*\\(").matcher(s);
        if (!m.find()) return null;
        int i = s.indexOf('{', m.start()), depth = 0;
        for (int k = i; k < s.length(); k++) {
            if (s.charAt(k) == '{') depth++;
            if (s.charAt(k) == '}' && --depth == 0) return s.substring(m.start(), k + 1).replaceAll("\\s+", "");
        }
        return null;
    }

    private static void check(String what, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }
}
