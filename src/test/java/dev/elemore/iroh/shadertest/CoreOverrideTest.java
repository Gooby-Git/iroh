package dev.elemore.iroh.shadertest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.elemore.iroh.shader.CoreOverrides;
import dev.elemore.iroh.shader.ShaderConversion;
import dev.elemore.iroh.shader.ShaderSources;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * P1-10 (RQ-14) offline check of resource-pack overrides of vanilla core shaders: which shaders CoreOverrides flags
 * for a pack stack, and that each flagged shader converts and compiles from the stack (what ShaderInstanceM then
 * uses instead of Iroh's built-in). Stack: client jar + the Proctor ParityCore test pack (or -Ppack=dir) + synthetic
 * packs for an include override and a broken shader. {@code ./gradlew coreOverrideTest}; last line "# coreoverride PASS n/n".
 */
public final class CoreOverrideTest {
    private static int pass, fail;

    record Layer(String pack, Map<String, byte[]> files) {}

    public static void main(String[] args) throws Exception {
        Path parity = Paths.get(args.length > 0 ? args[0]
                : System.getProperty("user.home") + "/Projects/proctor/scenarios/iroh/vanilla/corepack/ParityCore");
        Layer vanilla = vanilla();
        Layer pc = Files.isDirectory(parity) ? dir("ParityCore", parity) : null;

        // 1. vanilla alone: nothing overridden
        ShaderSources van = stack(List.of(vanilla));
        check("vanilla alone: rendertype_solid not overridden", CoreOverrides.find(van, "rendertype_solid", "rendertype_solid", "rendertype_solid") == null);
        check("vanilla alone: position_tex not overridden", CoreOverrides.find(van, "position_tex", "position_tex", "position_tex") == null);

        // 2. ParityCore: its five shaders flagged, converted and compiled; others untouched
        if (pc == null) {
            check("ParityCore pack present at " + parity, false);
        } else {
            ShaderSources s = stack(List.of(vanilla, pc));
            for (String name : List.of("position_tex", "position_tex_color", "position_color", "rendertype_gui", "rendertype_text")) {
                JsonObject json = json(s, name);
                CoreOverrides.Hit ov = CoreOverrides.find(s, name, json.get("vertex").getAsString(), json.get("fragment").getAsString());
                check("ParityCore: " + name + " flagged (" + (ov == null ? "none" : ov.pack() + " " + ov.file()) + ")",
                        ov != null && ov.pack().equals("ParityCore"));
                ShaderConversion.Result r = ShaderConversion.convert(ShaderConversion.Input.core(name, json, null), s, true);
                boolean red = r.output != null && r.output.fragment().contains("0.8");
                if (r.vertex != null) r.vertex.free();
                if (r.fragment != null) r.fragment.free();
                check("ParityCore: " + name + " converts + compiles from the pack (pack's +0.8 red present)", r.ok && red);
            }
            check("ParityCore: rendertype_solid still built-in", CoreOverrides.find(s, "rendertype_solid", "rendertype_solid", "rendertype_solid") == null);
            // removal = the stack without the pack: back to built-in
            check("ParityCore removed: position_tex back to built-in", CoreOverrides.find(van, "position_tex", "position_tex", "position_tex") == null);
        }

        // 3. an include-only override (fog.glsl) flags every shader importing it
        Map<String, byte[]> inc = new LinkedHashMap<>();
        String fog = new String(vanilla.files.get("assets/minecraft/shaders/include/fog.glsl"), StandardCharsets.UTF_8);
        inc.put("assets/minecraft/shaders/include/fog.glsl", ("// pack edit\n" + fog).getBytes(StandardCharsets.UTF_8));
        ShaderSources si = stack(List.of(vanilla, new Layer("FogPack", inc)));
        CoreOverrides.Hit ov = CoreOverrides.find(si, "rendertype_entity_solid", "rendertype_entity_solid", "rendertype_entity_solid");
        check("include override: fog.glsl flags rendertype_entity_solid", ov != null && ov.file().endsWith("include/fog.glsl"));
        check("include override: position_color (no fog import) untouched", CoreOverrides.find(si, "position_color", "position_color", "position_color") == null);

        // 4. Iroh's own blit stays built-in even when overridden
        Map<String, byte[]> blit = new LinkedHashMap<>();
        blit.put("assets/minecraft/shaders/core/blit_screen.fsh", vanilla.files.get("assets/minecraft/shaders/core/blit_screen.fsh"));
        check("blit_screen is never overridden", CoreOverrides.find(stack(List.of(vanilla, new Layer("BlitPack", blit))), "blit_screen", "blit_screen", "blit_screen") == null);

        // 5. a broken override is flagged but does not convert -> ShaderInstanceM keeps the built-in
        Map<String, byte[]> bad = new LinkedHashMap<>();
        bad.put("assets/minecraft/shaders/core/position_color.fsh", "#version 150\nvoid main() { this is not glsl }\n".getBytes(StandardCharsets.UTF_8));
        ShaderSources sb = stack(List.of(vanilla, new Layer("BrokenPack", bad)));
        JsonObject pj = json(sb, "position_color");
        ShaderConversion.Result r = ShaderConversion.convert(ShaderConversion.Input.core("position_color", pj, null), sb, true);
        if (r.vertex != null) r.vertex.free();
        if (r.fragment != null) r.fragment.free();
        check("broken override: flagged, conversion fails (" + r.stage + ") -> built-in kept",
                CoreOverrides.find(sb, "position_color", "position_color", "position_color") != null && !r.ok);

        System.out.println("# coreoverride " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        if (fail != 0)
            System.exit(1);
    }

    private static JsonObject json(ShaderSources s, String name) {
        return JsonParser.parseString(s.read("minecraft:shaders/core/" + name + ".json")).getAsJsonObject();
    }

    private static ShaderSources stack(List<Layer> layers) {
        return new ShaderSources() {
            Layer win(String id) {
                String p = "assets/" + id.substring(0, id.indexOf(':')) + "/" + id.substring(id.indexOf(':') + 1);
                Layer w = null;
                for (Layer l : layers)
                    if (l.files.containsKey(p)) w = l;
                return w;
            }

            @Override
            public String read(String id) {
                Layer l = win(id);
                String p = "assets/" + id.substring(0, id.indexOf(':')) + "/" + id.substring(id.indexOf(':') + 1);
                return l == null ? null : new String(l.files.get(p), StandardCharsets.UTF_8);
            }

            @Override
            public String packOf(String id) {
                Layer l = win(id);
                return l == null ? "?" : l.pack;
            }
        };
    }

    private static Layer vanilla() throws Exception {
        Path jar = Paths.get(net.minecraft.client.renderer.ShaderInstance.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zf.entries()))
                if (e.getName().startsWith("assets/minecraft/shaders/"))
                    try (InputStream is = zf.getInputStream(e)) {
                        files.put(e.getName(), is.readAllBytes());
                    }
        }
        return new Layer(CoreOverrides.VANILLA, files);
    }

    private static Layer dir(String pack, Path root) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<Path> all = new ArrayList<>();
        try (Stream<Path> w = Files.walk(root)) {
            w.filter(Files::isRegularFile).forEach(all::add);
        }
        for (Path p : all) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (rel.startsWith("assets/"))
                files.put(rel, Files.readAllBytes(p));
        }
        return new Layer(pack, files);
    }

    private static void check(String what, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }
}
