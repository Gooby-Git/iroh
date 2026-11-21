package dev.elemore.iroh.shadertest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.vulkanmod.vulkan.shader.SPIRVUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P1-11 item 2: Iroh's built-in core shader set against vanilla's. For every vanilla 1.21.1
 * {@code assets/minecraft/shaders/core/*.json} it compares the uniform and sampler names Iroh's copy declares
 * (flat list plus UBO fields) with the names vanilla declares, and checks that a vanilla vertex shader which
 * passes FogShape to fog_distance has an Iroh vertex shader that does the same. Output lines
 * {@code PASS|FAIL core-parity <name> ...}; a difference is PASS only when it is explained: Iroh folds
 * ModelViewMat and ProjMat into a single MVP, a name vanilla declares but never reads in its GLSL cannot matter,
 * and the rest sit in {@link #EXPLAINED} with a reason.
 */
final class CoreParity {
    private CoreParity() {}

    /** shader -> name -> reason; the only differences that are accepted by hand. */
    private static final Map<String, Map<String, String>> EXPLAINED = Map.of(
            "blit_screen", Map.of("DiffuseSampler",
                    "Iroh's blit shader reads Sampler0; the base's GameRendererMixin owns the blit (UNVERIFIED that no mod calls setSampler(DiffuseSampler) on it)",
                    "Sampler0", "the same rename seen from Iroh's side"),
            "rendertype_clouds", Map.of("Sampler0",
                    "Iroh's clouds are VulkanMod's own mesh (basic/clouds); this core entry only keeps the name alive and has no texture",
                    "ModelOffset", "Iroh-side cloud camera offset (Uniforms.ModelOffset), vanilla has none"));

    private static final Pattern FOG_CALL = Pattern.compile("fog_distance\\s*\\(([^;]*)\\)\\s*;");

    static List<String> run(Map<String, byte[]> vanilla) {
        List<String> out = new ArrayList<>();
        Pattern p = Pattern.compile("assets/minecraft/shaders/core/([^/]+)\\.json");
        for (String path : new TreeSet<>(vanilla.keySet())) {
            Matcher m = p.matcher(path);
            if (!m.matches())
                continue;
            String name = m.group(1);
            try {
                out.add(one(name, vanilla));
            } catch (Throwable t) {
                out.add(row(false, name, "check crashed: " + t));
            }
        }
        return out;
    }

    /**
     * Iroh's own core set, independent of vanilla: every JSON's vertex and fragment GLSL compiles with shaderc, and
     * the UBO field order in the JSON (vertex binding 0, fragment binding 1) equals the member order in the GLSL block,
     * because the uniform upload writes the JSON's fields in order.
     */
    static List<String> runOwn() {
        List<String> out = new ArrayList<>();
        try {
            var url = CoreParity.class.getResource("/assets/vulkanmod/shaders/core");
            Path dir = Paths.get(url.toURI());
            List<Path> jsons;
            try (Stream<Path> w = Files.walk(dir)) {
                jsons = w.filter(x -> x.toString().endsWith(".json")).sorted().toList();
            }
            for (Path j : jsons) {
                String name = j.getFileName().toString().replace(".json", "");
                try {
                    JsonObject o = JsonParser.parseString(Files.readString(j)).getAsJsonObject();
                    List<String> bad = new ArrayList<>();
                    for (String[] st : new String[][]{{"vertex", ".vsh", "0"}, {"fragment", ".fsh", "1"}}) {
                        String src = irohSource(o, st[0], st[1]);
                        if (src == null) {
                            bad.add(st[0] + " source missing");
                            continue;
                        }
                        try {
                            SPIRVUtils.compileShader(name + st[1], src, st[0].equals("vertex")
                                    ? SPIRVUtils.ShaderKind.VERTEX_SHADER : SPIRVUtils.ShaderKind.FRAGMENT_SHADER).free();
                        } catch (Throwable t) {
                            String m = String.valueOf(t.getMessage());
                            bad.add(st[0] + " does not compile: " + m.lines().filter(l -> !l.isBlank()).skip(0).findFirst().orElse(m).strip());
                        }
                        List<String> glslFields = block(src, st[2]);
                        List<String> jsonFields = new ArrayList<>();
                        if (o.has("UBOs"))
                            for (JsonElement b : o.getAsJsonArray("UBOs"))
                                if (b.getAsJsonObject().get("binding").getAsString().equals(st[2]))
                                    for (JsonElement f : b.getAsJsonObject().getAsJsonArray("fields"))
                                        jsonFields.add(f.getAsJsonObject().get("name").getAsString());
                        if (glslFields != null && !glslFields.equals(jsonFields))
                            bad.add(st[0] + " UBO order: GLSL " + glslFields + " JSON " + jsonFields);
                    }
                    out.add(bad.isEmpty() ? row(true, name, "compiles, UBO order matches", "iroh-own")
                            : row(false, name, String.join("; ", bad), "iroh-own"));
                } catch (Throwable t) {
                    out.add(row(false, name, "crashed: " + t, "iroh-own"));
                }
            }
        } catch (Throwable t) {
            out.add(row(false, "-", "could not list Iroh core shaders: " + t, "iroh-own"));
        }
        return out;
    }

    /** Member names of the uniform block at the given binding, in order; null if the stage has no such block. */
    private static List<String> block(String src, String binding) {
        Matcher m = Pattern.compile("binding\\s*=\\s*" + binding + "\\s*\\)\\s*uniform\\s+\\w+\\s*\\{([^}]*)\\}").matcher(src);
        if (!m.find())
            return null;
        List<String> l = new ArrayList<>();
        for (String d : m.group(1).split(";")) {
            String t = d.strip();
            if (t.isEmpty())
                continue;
            String[] w = t.split("\\s+");
            l.add(w[w.length - 1].replaceAll("\\[.*", ""));
        }
        return l;
    }

    private static String one(String name, Map<String, byte[]> vanilla) throws IOException {
        JsonObject vj = JsonParser.parseString(str(vanilla.get("assets/minecraft/shaders/core/" + name + ".json"))).getAsJsonObject();
        String ij = res("core/" + name + "/" + name + ".json");
        if (ij == null)
            ij = res("core/" + name + ".json");
        if (ij == null)
            return row(false, name, "no Iroh copy");
        JsonObject irohJson = JsonParser.parseString(ij).getAsJsonObject();

        Set<String> vu = names(vj, false), vs = names(vj, true), iu = names(irohJson, false), is = names(irohJson, true);
        String vsrc = glsl(vanilla, vj, "vertex", ".vsh") + "\n" + glsl(vanilla, vj, "fragment", ".fsh");

        List<String> bad = new ArrayList<>(), ok = new ArrayList<>();
        boolean mvp = iu.contains("MVP");
        for (String n : vu)
            if (!iu.contains(n))
                classify(name, n, "uniform", mvp && (n.equals("ModelViewMat") || n.equals("ProjMat")) ? "folded into MVP" : null,
                        vsrc, bad, ok);
        for (String n : vs)
            if (!is.contains(n))
                classify(name, n, "sampler", null, vsrc, bad, ok);
        for (String n : iu)
            if (!vu.contains(n) && !n.equals("MVP"))
                if (EXPLAINED.getOrDefault(name, Map.of()).containsKey(n)) ok.add("+" + n + " (" + EXPLAINED.get(name).get(n) + ")");
                else bad.add("Iroh-only uniform " + n);
        for (String n : is)
            if (!vs.contains(n))
                if (EXPLAINED.getOrDefault(name, Map.of()).containsKey(n)) ok.add("+" + n + " (" + EXPLAINED.get(name).get(n) + ")");
                else bad.add("Iroh-only sampler " + n);

        // FogShape: a vanilla vertex shader that feeds FogShape to fog_distance needs the same in Iroh's
        String vv = glsl(vanilla, vj, "vertex", ".vsh");
        if (usesFogShape(vv)) {
            String iv = irohSource(irohJson, "vertex", ".vsh");
            if (iv == null) bad.add("Iroh vertex shader not found");
            else if (!usesFogShape(iv)) bad.add("FogShape hard-coded in Iroh's vertex shader (" + fogCalls(iv) + ")");
            else if (!iu.contains("FogShape")) bad.add("fog_distance reads FogShape but the JSON does not declare it");
        }
        if (!bad.isEmpty())
            return row(false, name, String.join("; ", bad) + (ok.isEmpty() ? "" : " | explained: " + String.join("; ", ok)));
        return row(true, name, ok.isEmpty() ? "same names" : String.join("; ", ok));
    }

    private static void classify(String shader, String n, String kind, String fold, String vanillaSrc, List<String> bad, List<String> ok) {
        if (fold != null) {
            ok.add(n + " " + fold);
            return;
        }
        String manual = EXPLAINED.getOrDefault(shader, Map.of()).get(n);
        if (manual != null) {
            ok.add("-" + n + " (" + manual + ")");
            return;
        }
        // declared in vanilla's JSON but never read by its GLSL: cannot be observed, so not a difference
        if (!Pattern.compile("(?m)^\\s*uniform\\s[^;]*\\b" + Pattern.quote(n) + "\\b").matcher(vanillaSrc).find()) {
            ok.add(n + " declared but unused in vanilla GLSL");
            return;
        }
        bad.add("vanilla " + kind + " " + n + " missing in Iroh");
    }

    private static boolean usesFogShape(String src) {
        Matcher m = FOG_CALL.matcher(src);
        while (m.find())
            if (m.group(1).contains("FogShape"))
                return true;
        return false;
    }

    private static String fogCalls(String src) {
        Matcher m = FOG_CALL.matcher(src);
        List<String> l = new ArrayList<>();
        while (m.find())
            l.add("fog_distance(" + m.group(1).strip() + ")");
        return String.join(", ", l);
    }

    private static Set<String> names(JsonObject j, boolean samplers) {
        Set<String> s = new TreeSet<>();
        if (samplers) {
            if (j.has("samplers"))
                for (JsonElement e : j.getAsJsonArray("samplers"))
                    s.add(e.getAsJsonObject().get("name").getAsString());
            return s;
        }
        if (j.has("uniforms"))
            for (JsonElement e : j.getAsJsonArray("uniforms"))
                s.add(e.getAsJsonObject().get("name").getAsString());
        if (j.has("UBOs"))
            for (JsonElement b : j.getAsJsonArray("UBOs"))
                for (JsonElement f : b.getAsJsonObject().getAsJsonArray("fields"))
                    s.add(f.getAsJsonObject().get("name").getAsString());
        return s;
    }

    private static String glsl(Map<String, byte[]> vanilla, JsonObject vj, String stage, String ext) {
        String n = vj.get(stage).getAsString();
        n = n.contains(":") ? n.substring(n.indexOf(':') + 1) : "core/" + n;
        byte[] b = vanilla.get("assets/minecraft/shaders/" + n + ext);
        return b == null ? "" : str(b);
    }

    /** Iroh's own GLSL for a stage: the JSON's "vertex"/"fragment" name, last path segment, in its own folder. */
    private static String irohSource(JsonObject irohJson, String stage, String ext) throws IOException {
        String n = irohJson.get(stage).getAsString();
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.substring(n.indexOf(':') + 1);
        return res("core/" + n + "/" + n + ext);
    }

    private static String res(String rel) throws IOException {
        try (InputStream is = CoreParity.class.getResourceAsStream("/assets/vulkanmod/shaders/" + rel)) {
            return is == null ? null : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static String row(boolean ok, String name, String msg) {
        return row(ok, name, msg, "iroh-core");
    }

    private static String row(boolean ok, String name, String msg, String pack) {
        return (ok ? "PASS " : "FAIL ") + String.format("%-7s %-55s %-20s", "parity", name, pack) + " " + msg;
    }
}
