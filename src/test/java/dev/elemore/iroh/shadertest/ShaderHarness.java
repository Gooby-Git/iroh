package dev.elemore.iroh.shadertest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.elemore.iroh.shader.ShaderConversion;
import dev.elemore.iroh.shader.ShaderSources;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.converter.GlslConverter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Offline shader regression suite (P1-2a). Runs every core shader, post program and post chain that the
 * Minecraft client jar, a mod folder (nested jars included) and the selected resource packs provide through
 * {@link ShaderConversion}, the code the game runs, and prints one sorted line per shader:
 * {@code PASS|FAIL <kind> <id> <pack> [stage] message}. Read-only; launches nothing.
 * <p>
 * {@code ./gradlew shaderHarness} (see knowledge/build.md). Properties: {@code -Pmods=<dir>} (default: the
 * Vulkan instance), {@code -Pmode=current|legacy} (legacy = the base's GlslConverter path, for comparison),
 * {@code -Pshader=<id>} (also print that shader's translated GLSL), {@code -Pout=<file>}.
 */
public final class ShaderHarness {

    /** One pack: files under assets/ that the shader system can read. */
    record Layer(String name, String pack, Map<String, byte[]> files) {}

    private static final Pattern CORE_JSON = Pattern.compile("assets/([^/]+)/shaders/core/(.+)\\.json");
    private static final Pattern PROGRAM_JSON = Pattern.compile("assets/([^/]+)/shaders/program/(.+)\\.json");
    private static final Pattern POST_JSON = Pattern.compile("assets/([^/]+)/shaders/post/(.+)\\.json");

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new LinkedHashMap<>();
        for (String a : args) {
            int eq = a.indexOf('=');
            if (a.startsWith("--") && eq > 0)
                opt.put(a.substring(2, eq), a.substring(eq + 1));
        }
        Path instance = Paths.get(opt.getOrDefault("instance",
                System.getProperty("user.home") + "/.local/share/atlauncher/instances/Vulkan"));
        Path mods = Paths.get(opt.getOrDefault("mods", instance.resolve("mods").toString()));
        String mode = opt.getOrDefault("mode", "current");
        String show = opt.get("shader");
        String outFile = opt.get("out");

        long t0 = System.nanoTime();
        List<Layer> layers = new ArrayList<>();
        Map<String, Layer> builtinPacks = new LinkedHashMap<>();

        layers.add(vanillaLayer());
        List<Path> jars;
        try (Stream<Path> s = Files.list(mods)) {
            jars = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
        for (Path jar : jars) {
            String fn = jar.getFileName().toString();
            if (fn.startsWith("iroh-"))
                continue; // Iroh's own Vulkan GLSL lives under assets/vulkanmod and is not a game shader
            byte[] bytes = Files.readAllBytes(jar);
            readJar(fn, modId(fn), bytes, layers, builtinPacks);
        }
        // selected resource packs from options.txt, in order (later wins)
        List<String> selected = selectedPacks(instance.resolve("options.txt"));
        for (String id : selected) {
            if (id.startsWith("file/")) {
                Path p = instance.resolve("resourcepacks").resolve(id.substring(5));
                if (Files.isRegularFile(p))
                    layers.add(readZipPack(id, Files.readAllBytes(p)));
                else if (Files.isDirectory(p))
                    layers.add(readDirPack(id, p));
            } else if (builtinPacks.containsKey(id)) {
                layers.add(builtinPacks.get(id));
            }
        }

        // resolution: later layer wins, as in the resource manager
        Map<String, Layer> winner = new TreeMap<>();
        Map<String, List<String>> providers = new TreeMap<>();
        Map<String, Set<String>> contents = new TreeMap<>();
        for (Layer l : layers)
            for (String path : l.files.keySet()) {
                winner.put(path, l);
                providers.computeIfAbsent(path, k -> new ArrayList<>()).add(l.name);
                contents.computeIfAbsent(path, k -> new HashSet<>()).add(java.util.Arrays.toString(
                        java.security.MessageDigest.getInstance("SHA-1").digest(l.files.get(path))));
            }

        ShaderSources sources = new ShaderSources() {
            @Override
            public String read(String id) {
                Layer l = winner.get(toPath(id));
                return l == null ? null : new String(l.files.get(toPath(id)), StandardCharsets.UTF_8);
            }

            @Override
            public String packOf(String id) {
                Layer l = winner.get(toPath(id));
                return l == null ? "?" : l.pack;
            }
        };

        Set<String> irohBuiltin = irohBuiltinCoreNames();
        List<String> lines = new ArrayList<>();
        StringBuilder detail = new StringBuilder();

        for (String path : winner.keySet()) {
            Matcher m;
            if ((m = CORE_JSON.matcher(path)).matches()) {
                String ns = m.group(1), name = m.group(2);
                if (ns.equals("vulkanmod"))
                    continue;
                String id = ns.equals("minecraft") ? name : ns + ":" + name;
                String tag = ns.equals("minecraft") && irohBuiltin.contains(name) ? " (runtime uses Iroh built-in)" : "";
                lines.add(runOne("core", id, winner.get(path), path, sources, mode, show, detail) + tag);
            } else if ((m = PROGRAM_JSON.matcher(path)).matches()) {
                String ns = m.group(1), name = m.group(2);
                String id = ns + ":" + name;
                lines.add(runOne("program", id, winner.get(path), path, sources, mode, show, detail));
            } else if ((m = POST_JSON.matcher(path)).matches()) {
                lines.add(checkChain(m.group(1) + ":" + m.group(2), winner.get(path), path, winner));
            }
        }
        if (!mode.equals("legacy"))
            lines.addAll(SelfCases.run(show, detail));
        if (!mode.equals("legacy"))
            lines.addAll(GlProgramCases.run(mods, opt.get("programs"), show, detail));
        lines.sort(null);

        List<String> report = new ArrayList<>();
        int pass = 0, fail = 0;
        for (String l : lines) {
            if (l.startsWith("PASS")) pass++;
            else fail++;
        }
        report.add("# Iroh shader harness, mode=" + mode + ", mods=" + mods);
        report.addAll(lines);
        report.add("# overrides of vanilla shader files by mods or packs (path <- providers, last wins)");
        Set<String> vanillaPaths = layers.get(0).files.keySet();
        for (var e : providers.entrySet()) {
            if (e.getValue().size() > 1 && e.getKey().startsWith("assets/minecraft/shaders/") && vanillaPaths.contains(e.getKey()))
                report.add("OVERRIDE " + e.getKey() + " <- " + String.join(", ", e.getValue()));
        }
        report.add("# same non-vanilla path from several packs with different content");
        for (var e : providers.entrySet()) {
            if (e.getValue().size() > 1 && !vanillaPaths.contains(e.getKey()) && contents.get(e.getKey()).size() > 1)
                report.add("CONFLICT " + e.getKey() + " <- " + String.join(", ", e.getValue()));
        }
        report.add("# shader files in built-in packs not selected in options.txt (not resolved above)");
        for (Layer l : builtinPacks.values())
            if (!selected.contains(l.pack))
                for (String p : new TreeSet<>(l.files.keySet()))
                    if (p.contains("/shaders/"))
                        report.add("BUILTIN " + l.pack + " " + p);
        report.add("# total " + (pass + fail) + "  PASS " + pass + "  FAIL " + fail);

        String text = String.join("\n", report) + "\n";
        if (outFile != null)
            Files.writeString(Paths.get(outFile), text);
        System.out.print(text);
        if (detail.length() > 0)
            System.out.print(detail);
        System.err.printf("shader harness: %d layers, %.1f s%n", layers.size(), (System.nanoTime() - t0) / 1e9);
    }

    private static String toPath(String id) {
        int c = id.indexOf(':');
        return "assets/" + id.substring(0, c) + "/" + id.substring(c + 1);
    }

    private static String runOne(String kind, String id, Layer layer, String path, ShaderSources sources, String mode,
                                 String show, StringBuilder detail) {
        JsonObject json;
        try {
            json = JsonParser.parseString(new String(layer.files.get(path), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Throwable t) {
            return line(false, kind, id, layer.pack, "json", String.valueOf(t.getMessage()));
        }
        if (mode.equals("legacy"))
            return legacy(kind, id, layer, json, sources);

        ShaderConversion.Input in;
        try {
            in = kind.equals("core") ? ShaderConversion.Input.core(id, json, null)
                    : ShaderConversion.Input.program(id, json);
        } catch (Throwable t) {
            return line(false, kind, id, layer.pack, "json", String.valueOf(t));
        }
        ShaderConversion.Result r = ShaderConversion.convert(in, sources, true);
        if (show != null && show.equals(id) && r.output != null) {
            detail.append("\n===== ").append(id).append(" vertex =====\n").append(r.output.vertex());
            detail.append("\n===== ").append(id).append(" fragment =====\n").append(r.output.fragment());
            detail.append("\n===== zero-filled: ").append(r.output.zeroUniforms()).append(" notes: ").append(r.output.notes()).append('\n');
        }
        if (r.vertex != null) r.vertex.free();
        if (r.fragment != null) r.fragment.free();
        String extra = "";
        if (r.ok && r.output != null && (!r.output.zeroUniforms().isEmpty() || !r.output.notes().isEmpty())) {
            List<String> bits = new ArrayList<>();
            if (!r.output.zeroUniforms().isEmpty())
                bits.add("zero " + String.join(",", r.output.zeroUniforms()));
            bits.addAll(r.output.notes());
            extra = String.join("; ", bits);
        }
        return r.ok ? line(true, kind, id, layer.pack, null, extra) : line(false, kind, id, layer.pack, r.stage, r.shortMessage());
    }

    /** The base's path before P1-2a: GlslConverter on the raw files, #moj_import -> shaderc #include. */
    private static String legacy(String kind, String id, Layer layer, JsonObject json, ShaderSources sources) {
        String v = json.get("vertex").getAsString(), f = json.get("fragment").getAsString();
        String vp, fp;
        if (kind.equals("core")) {
            // ShaderInstanceM.loadNames: namespaced only when the shader name has one
            vp = id.contains(":") ? ShaderConversion.coreProgramPath(v.contains(":") ? v : "minecraft:" + v, ".vsh")
                    : "minecraft:shaders/core/" + v + ".vsh";
            fp = id.contains(":") ? ShaderConversion.coreProgramPath(f.contains(":") ? f : "minecraft:" + f, ".fsh")
                    : "minecraft:shaders/core/" + f + ".fsh";
        } else {
            vp = ShaderConversion.effectProgramPath(v, ".vsh");
            fp = ShaderConversion.effectProgramPath(f, ".fsh");
        }
        String vs = sources.read(vp), fs = sources.read(fp);
        if (vs == null) return line(false, kind, id, layer.pack, "load-vsh", "missing " + vp);
        if (fs == null) return line(false, kind, id, layer.pack, "load-fsh", "missing " + fp);
        String stage = "convert";
        try {
            GlslConverter c = new GlslConverter();
            c.process(vs, fs);
            stage = "ubo";
            c.createUBO();
            stage = "compile-vsh";
            SPIRVUtils.compileShader(id + ".vsh", c.getVshConverted(), SPIRVUtils.ShaderKind.VERTEX_SHADER).free();
            stage = "compile-fsh";
            SPIRVUtils.compileShader(id + ".fsh", c.getFshConverted(), SPIRVUtils.ShaderKind.FRAGMENT_SHADER).free();
            return line(true, kind, id, layer.pack, null, "");
        } catch (Throwable t) {
            String msg = String.valueOf(t.getMessage());
            for (String l : msg.split("\n"))
                if (!l.isBlank() && !l.startsWith("Failed to compile shader")) {
                    msg = l.strip();
                    break;
                }
            return line(false, kind, id, layer.pack, stage, t.getClass().getSimpleName() + ": " + msg);
        }
    }

    private static String checkChain(String id, Layer layer, String path, Map<String, Layer> winner) {
        try {
            JsonObject json = JsonParser.parseString(new String(layer.files.get(path), StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> missing = new ArrayList<>();
            JsonArray passes = json.has("passes") ? json.getAsJsonArray("passes") : new JsonArray();
            for (JsonElement p : passes) {
                String prog = p.getAsJsonObject().get("name").getAsString();
                String jp = toPath(ShaderConversion.effectProgramPath(prog, ".json"));
                if (!winner.containsKey(jp))
                    missing.add(prog);
            }
            return missing.isEmpty() ? line(true, "post", id, layer.pack, null, passes.size() + " passes")
                    : line(false, "post", id, layer.pack, "chain", "missing program " + String.join(",", missing));
        } catch (Throwable t) {
            return line(false, "post", id, layer.pack, "json", String.valueOf(t.getMessage()));
        }
    }

    private static String line(boolean ok, String kind, String id, String pack, String stage, String msg) {
        StringBuilder sb = new StringBuilder(ok ? "PASS " : "FAIL ");
        sb.append(String.format("%-7s %-55s %-20s", kind, id, pack));
        if (stage != null)
            sb.append(" [").append(stage).append("]");
        if (msg != null && !msg.isEmpty())
            sb.append(' ').append(msg.replace('\n', ' '));
        return sb.toString().stripTrailing();
    }

    // ---------------------------------------------------------------- reading packs

    private static boolean wanted(String name) {
        return name.startsWith("assets/") && name.contains("/shaders/") && !name.endsWith("/");
    }

    private static Layer vanillaLayer() throws IOException, java.net.URISyntaxException {
        URI uri = net.minecraft.client.renderer.ShaderInstance.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path jar = Paths.get(uri);
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (wanted(e.getName()) && e.getName().startsWith("assets/minecraft/"))
                    try (InputStream is = zf.getInputStream(e)) {
                        files.put(e.getName(), is.readAllBytes());
                    }
            }
        }
        if (files.isEmpty())
            throw new IllegalStateException("no vanilla shaders in " + jar);
        return new Layer("vanilla", "vanilla", files);
    }

    private static void readJar(String name, String pack, byte[] bytes, List<Layer> layers, Map<String, Layer> builtinPacks) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, Map<String, byte[]>> packs = new LinkedHashMap<>();
        List<String[]> nested = new ArrayList<>();
        List<byte[]> nestedBytes = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName();
                if (wanted(n)) {
                    files.put(n, zis.readAllBytes());
                } else if (n.startsWith("resourcepacks/") && n.contains("/assets/") && n.contains("/shaders/") && !n.endsWith("/")) {
                    String rest = n.substring("resourcepacks/".length());
                    String packName = rest.substring(0, rest.indexOf('/'));
                    packs.computeIfAbsent(packName, k -> new LinkedHashMap<>())
                         .put(rest.substring(packName.length() + 1), zis.readAllBytes());
                } else if (n.startsWith("META-INF/jars/") && n.endsWith(".jar")) {
                    nested.add(new String[]{n.substring(n.lastIndexOf('/') + 1)});
                    nestedBytes.add(zis.readAllBytes());
                }
            }
        }
        for (int i = 0; i < nested.size(); i++) {
            String inner = nested.get(i)[0];
            readJar(name + "!" + inner, modId(inner), nestedBytes.get(i), layers, builtinPacks);
        }
        if (!files.isEmpty())
            layers.add(new Layer(name, pack, files));
        for (var e : packs.entrySet()) {
            String id = pack + ":" + e.getKey();
            builtinPacks.put(id, new Layer(name + "!resourcepacks/" + e.getKey(), id, e.getValue()));
        }
    }

    private static Layer readZipPack(String id, byte[] bytes) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null)
                if (wanted(e.getName()))
                    files.put(e.getName(), zis.readAllBytes());
        }
        return new Layer(id, id, files);
    }

    private static Layer readDirPack(String id, Path dir) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                if (wanted(rel))
                    files.put(rel, Files.readAllBytes(p));
            }
        }
        return new Layer(id, id, files);
    }

    private static List<String> selectedPacks(Path options) {
        List<String> out = new ArrayList<>();
        try {
            for (String l : Files.readAllLines(options)) {
                if (l.startsWith("resourcePacks:")) {
                    JsonArray a = JsonParser.parseString(l.substring("resourcePacks:".length())).getAsJsonArray();
                    for (JsonElement e : a)
                        out.add(e.getAsString());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Mod id guess from a jar file name: the leading part before the first '-' or '_' + digit. */
    private static String modId(String fileName) {
        String s = fileName.replaceAll("\\.jar$", "");
        Matcher m = Pattern.compile("^([A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z][A-Za-z0-9]*)*?)[-_+]\\d").matcher(s);
        return (m.find() ? m.group(1) : s).toLowerCase();
    }

    private static Set<String> irohBuiltinCoreNames() throws Exception {
        Set<String> out = new HashSet<>();
        var url = ShaderHarness.class.getResource("/assets/vulkanmod/shaders/core");
        if (url == null)
            return out;
        Path dir = Paths.get(url.toURI());
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(p -> p.toString().endsWith(".json"))
             .forEach(p -> out.add(p.getFileName().toString().replace(".json", "")));
        }
        return out;
    }
}
