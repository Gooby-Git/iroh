package dev.elemore.iroh.shaderpack;

import dev.elemore.iroh.shaderpack.PackGlsl.Stage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serialises a {@link FrontEnd.PackResult} (schema summary in knowledge/iris-loader.md "P3-1 front end"):
 * <pre>
 *   pack.json                 pack-level record: options, properties, programs, id maps, custom uniforms,
 *                             render targets, shadow, uniform usage, bindings, failures
 *   programs/&lt;dir&gt;/&lt;name&gt;.json   per unit: reflection, directives, includes, notes, result
 *   glsl/&lt;dir&gt;/&lt;name&gt;.&lt;stage&gt;.glsl   translated source (build output only, never committed)
 *   spirv/&lt;dir&gt;/&lt;name&gt;.&lt;stage&gt;.spv
 * </pre>
 */
public final class PackDump {

    private PackDump() {}

    public static void write(FrontEnd.PackResult r, Path out) throws IOException {
        Files.createDirectories(out);
        Files.writeString(out.resolve("pack.json"), Json.write(packJson(r)), StandardCharsets.UTF_8);
        for (FrontEnd.Unit u : r.units) {
            String rel = u.id.contains("/") ? u.id : "_root/" + u.id;
            Path pj = out.resolve("programs").resolve(rel + ".json");
            Files.createDirectories(pj.getParent());
            Files.writeString(pj, Json.write(unitJson(r, u)), StandardCharsets.UTF_8);
            if (u.result != null)
                for (Map.Entry<Stage, String> e : u.result.glsl.entrySet()) {
                    Path g = out.resolve("glsl").resolve(rel + "." + e.getKey().key + ".glsl");
                    Files.createDirectories(g.getParent());
                    Files.writeString(g, e.getValue(), StandardCharsets.UTF_8);
                }
            for (Map.Entry<Stage, byte[]> e : u.spirv.entrySet()) {
                Path s = out.resolve("spirv").resolve(rel + "." + e.getKey().key + ".spv");
                Files.createDirectories(s.getParent());
                Files.write(s, e.getValue());
            }
        }
    }

    static Map<String, Object> unitJson(FrontEnd.PackResult r, FrontEnd.Unit u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id);
        m.put("dir", u.dir);
        m.put("program", u.program);
        m.put("family", ProgramSet.family(u.program).name());
        m.put("compute", u.compute);
        m.put("enabled", u.enabled);
        if (u.offReason != null)
            m.put("offReason", u.offReason);
        Map<String, Object> files = new LinkedHashMap<>();
        u.paths.forEach((s, p) -> files.put(s.key, p));
        m.put("files", files);
        m.put("result", u.passed() ? "PASS" : "FAIL");
        if (!u.passed()) {
            m.put("failStage", u.failStage);
            m.put("failClass", u.failClass);
            m.put("failMessage", u.failMessage);
        }
        Map<String, Object> inc = new LinkedHashMap<>();
        u.includes.forEach((s, l) -> inc.put(s.key, new ArrayList<>(new TreeSet<>(l))));
        m.put("includes", inc);
        Map<String, Object> ver = new LinkedHashMap<>();
        u.parsed.forEach((s, p) -> ver.put(s.key, p.version + (p.profile.isEmpty() ? "" : " " + p.profile)));
        m.put("glslVersion", ver);
        PackGlsl.Result t = u.result;
        if (t != null) {
            Map<String, Object> refl = new LinkedHashMap<>();
            List<Object> uni = new ArrayList<>();
            for (PackGlsl.UniformMember um : t.uniforms) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("name", um.name());
                x.put("type", um.type() + (um.array() > 0 ? "[" + um.array() + "]" : ""));
                x.put("offset", um.offset());
                x.put("size", um.size());
                x.put("class", uniformClass(r, um.name(), um.source()));
                x.put("referenced", t.referenced.contains(um.name()));
                if (um.initializer() != null)
                    x.put("default", um.initializer());
                uni.add(x);
            }
            refl.put("uniformBlock", Json.obj("set", 0, "binding", 0, "size", t.blockSize, "members", uni));
            refl.put("samplers", bindings(t.samplers, t.referenced));
            refl.put("images", bindings(t.images, t.referenced));
            refl.put("buffers", bindings(t.buffers, null));
            refl.put("vertexInputs", t.vertexInputs);
            List<Object> vars = new ArrayList<>();
            for (PackGlsl.Varying v : t.varyings)
                vars.add(Json.obj("name", v.name(), "type", v.type(), "location", v.location(), "slots", v.slots(), "from", v.from()));
            refl.put("varyings", vars);
            refl.put("fragmentOutputs", t.fragmentOutputs);
            refl.put("drawBuffers", t.drawBuffers);
            refl.put("drawBuffersSource", t.drawBuffersSource);
            if (t.localSize != null)
                refl.put("localSize", List.of(t.localSize[0], t.localSize[1], t.localSize[2]));
            refl.put("compatBuiltins", t.builtinsUsed);
            m.put("reflection", refl);
            m.put("constDirectives", t.constDirectives);
            m.put("legacyFormatDirectives", t.legacyFormatDirectives);
            Map<String, Object> sp = new LinkedHashMap<>();
            u.spirv.forEach((s, b) -> sp.put(s.key, b.length));
            m.put("spirvBytes", sp);
        }
        m.put("notes", u.notes);
        m.put("warnings", u.warnings);
        return m;
    }

    static Object bindings(List<PackGlsl.Binding> l, java.util.Set<String> referenced) {
        List<Object> out = new ArrayList<>();
        for (PackGlsl.Binding b : l) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", b.name());
            x.put("type", b.type());
            x.put("set", b.set());
            x.put("binding", b.binding());
            if (b.format() != null)
                x.put("format", b.format());
            if (!b.access().isEmpty())
                x.put("access", b.access());
            if (referenced != null)
                x.put("referenced", referenced.contains(b.name()));
            List<String> st = new ArrayList<>();
            for (Stage s : b.stages())
                st.add(s.key);
            x.put("stages", st);
            out.add(x);
        }
        return out;
    }

    static String uniformClass(FrontEnd.PackResult r, String name, String source) {
        if (source.startsWith("builtin:"))
            return "compat:" + UniformCatalog.STANDARD.get(name).cost();
        for (CustomUniforms.Entry e : r.customUniforms.entries)
            if (e.decl.name().equals(name))
                return "custom:" + e.decl.kind();
        UniformCatalog.Std s = UniformCatalog.STANDARD.get(name);
        if (s != null)
            return "standard:" + s.cost();
        if (name.startsWith("vista"))
            return "vista:NEW_WORK";
        return "unknown";
    }

    public static Map<String, Object> packJson(FrontEnd.PackResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", "iroh.pack.v1");
        m.put("pack", r.pack.name());
        m.put("origin", r.pack.origin());
        m.put("files", r.pack.paths().size());
        m.put("caseNotes", r.pack.caseNotes());
        m.put("errors", r.packErrors);
        m.put("macros", r.macros);
        // options
        Map<String, Object> opt = new LinkedHashMap<>();
        Map<String, Object> list = new LinkedHashMap<>();
        for (OptionModel.Option o : r.options.options().values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("kind", o.kind.name());
            x.put("default", o.defaultValue);
            x.put("value", r.options.values().get(o.name));
            x.put("values", o.values);
            if (o.ambiguous)
                x.put("ambiguous", true);
            x.put("files", new ArrayList<>(o.files));
            list.put(o.name, x);
        }
        opt.put("count", r.options.options().size());
        opt.put("savedApplied", r.options.savedApplied());
        opt.put("savedUnknown", r.options.savedUnknown());
        opt.put("notes", r.options.notes());
        opt.put("options", list);
        opt.put("currentProfile", currentProfile(r));
        m.put("options", opt);
        m.put("properties", r.properties);
        m.put("programs", r.programs);
        m.put("idMaps", r.idMaps);
        m.put("customUniforms", r.customUniforms);
        m.put("renderTargets", renderTargets(r, 2560, 1440));
        m.put("uniformUsage", uniformUsage(r));
        m.put("bindings", r.bindings);
        // summary
        int pass = 0;
        int fail = 0;
        for (FrontEnd.Unit u : r.units)
            if (u.passed())
                pass++;
            else
                fail++;
        Map<String, Object> sum = new LinkedHashMap<>();
        sum.put("units", r.units.size());
        sum.put("pass", pass);
        sum.put("fail", fail);
        Map<String, Object> classes = new LinkedHashMap<>();
        FrontEnd.failureClasses(r).forEach((c, l) -> {
            List<String> ids = new ArrayList<>();
            for (FrontEnd.Unit u : l)
                ids.add(u.id);
            classes.put(c, Json.obj("count", l.size(), "units", ids, "example", l.get(0).failMessage));
        });
        sum.put("failureClasses", classes);
        m.put("summary", sum);
        return m;
    }

    static String currentProfile(FrontEnd.PackResult r) {
        for (Map.Entry<String, List<String>> p : r.properties.profiles.entrySet()) {
            if (matches(r, p.getValue(), 0))
                return p.getKey();
        }
        return "Custom";
    }

    private static boolean matches(FrontEnd.PackResult r, List<String> entries, int depth) {
        if (depth > 8)
            return false;
        for (String e : entries) {
            if (e.startsWith("profile.")) {
                List<String> other = r.properties.profiles.get(e.substring(8));
                if (other == null || !matches(r, other, depth + 1))
                    return false;
                continue;
            }
            if (e.startsWith("!program.") || e.startsWith("program."))
                continue;
            String name;
            String val;
            if (e.startsWith("!")) {
                name = e.substring(1);
                val = "false";
            } else if (e.contains(":") || e.contains("=")) {
                int i = e.contains(":") ? e.indexOf(':') : e.indexOf('=');
                name = e.substring(0, i);
                val = e.substring(i + 1);
            } else {
                name = e;
                val = "true";
            }
            String cur = r.options.values().get(name);
            if (cur == null || !numEq(cur, val))
                return false;
        }
        return true;
    }

    private static boolean numEq(String a, String b) {
        if (a.equals(b))
            return true;
        try {
            return Double.parseDouble(a) == Double.parseDouble(b);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------ render targets

    private static final Pattern BUF = Pattern.compile("(colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4]|shadowcolor\\d*)(Format|Clear|ClearColor|MipmapEnabled)");

    /** Per dimension folder: render targets used, their formats, clears, sizes, and VRAM at w x h. */
    public static Map<String, Object> renderTargets(FrontEnd.PackResult r, int w, int h) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, List<FrontEnd.Unit>> byDir = new TreeMap<>();
        for (FrontEnd.Unit u : r.units)
            byDir.computeIfAbsent(u.dir, k -> new ArrayList<>()).add(u);
        for (Map.Entry<String, List<FrontEnd.Unit>> d : byDir.entrySet()) {
            Map<String, Map<String, Object>> targets = new TreeMap<>((a, b) -> {
                int c = a.replaceAll("\\d", "").compareTo(b.replaceAll("\\d", ""));
                if (c != 0)
                    return c;
                return Integer.compare(num(a), num(b));
            });
            Map<String, String> directives = new LinkedHashMap<>();
            for (FrontEnd.Unit u : d.getValue()) {
                if (u.result == null || !u.enabled)
                    continue; // switched off or another mod's program: not drawn
                boolean shadowPass = u.program.equals("shadow") || u.program.startsWith("shadow_");
                for (Map.Entry<String, String> e : u.result.constDirectives.entrySet()) {
                    String prev = directives.put(e.getKey(), e.getValue());
                    if (prev != null && !prev.equals(e.getValue()))
                        directives.put(e.getKey() + "#conflict", prev + " | " + e.getValue());
                }
                for (PackGlsl.Binding b : u.result.samplers)
                    if (u.result.referenced.contains(b.name()))
                        use(targets, b.name(), "read:" + u.program);
                for (PackGlsl.Binding b : u.result.images)
                    if (u.result.referenced.contains(b.name()))
                        use(targets, b.name(), "image:" + u.program);
                if (u.result.fragmentOutputs != null && !u.result.fragmentOutputs.isEmpty()) {
                    List<Integer> db = u.result.drawBuffers;
                    if (db != null)
                        for (Integer i : db)
                            use(targets, (shadowPass ? "shadowcolor" : "colortex") + i, "write:" + u.program);
                }
            }
            long total = 0;
            long totalDoubled = 0;
            for (Map.Entry<String, Map<String, Object>> t : targets.entrySet()) {
                String n = t.getKey();
                Map<String, Object> tm = t.getValue();
                String fmt = null;
                for (Map.Entry<String, String> dd : directives.entrySet()) {
                    Matcher m = BUF.matcher(dd.getKey());
                    if (m.matches() && Formats.canonical(m.group(1)).equals(n) && m.group(2).equals("Format"))
                        fmt = dd.getValue();
                }
                for (Map.Entry<String, String> dd : directives.entrySet()) {
                    Matcher m = BUF.matcher(dd.getKey());
                    if (m.matches() && Formats.canonical(m.group(1)).equals(n) && !m.group(2).equals("Format"))
                        tm.put(m.group(2).substring(0, 1).toLowerCase() + m.group(2).substring(1), dd.getValue());
                }
                if (fmt == null)
                    fmt = n.equals("colortex1") && targets.containsKey("colortex1") ? "RGBA8 (default; RGBA32F if read as gdepth)" : "RGBA8 (default)";
                tm.put("format", fmt);
                String f0 = fmt.split(" ")[0];
                tm.put("promotedToRGBA", Formats.promoted(f0));
                double sx = 1;
                double sy = 1;
                Object size = r.properties.sizeBuffer.get(n);
                if (size == null)
                    for (Map.Entry<String, String> a : Formats.ALIASES.entrySet())
                        if (a.getValue().equals(n) && r.properties.sizeBuffer.containsKey(a.getKey()))
                            size = r.properties.sizeBuffer.get(a.getKey());
                long px;
                if (n.startsWith("shadowcolor")) {
                    int res = (int) Math.round(numberOr(directives.get("shadowMapResolution"), 1024));
                    px = (long) res * res;
                    tm.put("size", List.of(res, res));
                } else if (size instanceof List<?> l) {
                    String as = String.valueOf(l.get(0));
                    String bs = String.valueOf(l.get(1));
                    long pw = as.contains(".") ? Math.round(w * Double.parseDouble(as)) : Long.parseLong(as);
                    long ph = bs.contains(".") ? Math.round(h * Double.parseDouble(bs)) : Long.parseLong(bs);
                    px = pw * ph;
                    tm.put("size", List.of(pw, ph));
                    tm.put("sizeBuffer", l);
                } else {
                    px = (long) (w * sx) * (long) (h * sy);
                    tm.put("size", List.of(w, h));
                }
                int bpp = Formats.bytes(f0);
                long bytes = bpp < 0 ? 0 : px * bpp;
                tm.put("bytes", bytes);
                total += bytes;
                totalDoubled += bytes * 2;
            }
            // depth and shadow maps
            long depth = (long) w * h * 4 * 3;
            int sres = (int) Math.round(numberOr(directives.get("shadowMapResolution"), 1024));
            long shadowDepth = (long) sres * sres * 4 * 2;
            long images = 0;
            Map<String, Object> imgs = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> im : r.properties.images.entrySet()) {
                @SuppressWarnings("unchecked") List<Double> sz = (List<Double>) im.getValue().get("size");
                boolean rel = (Boolean) im.getValue().get("relative");
                long texels = 1;
                if (rel)
                    texels = Math.round(w * sz.get(0)) * Math.round(h * sz.get(1));
                else
                    for (Double s : sz)
                        texels *= Math.round(s);
                int bpp = Formats.bytes(String.valueOf(im.getValue().get("internalFormat")));
                long b = bpp < 0 ? 0 : texels * bpp;
                imgs.put(im.getKey(), Json.obj("format", im.getValue().get("internalFormat"), "texels", texels, "bytes", b));
                images += b;
            }
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("resolution", List.of(w, h));
            dm.put("targets", targets);
            dm.put("directives", directives);
            dm.put("customImages", imgs);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("colourTargetsSingleMiB", mib(total));
            v.put("colourTargetsDoubledMiB", mib(totalDoubled));
            v.put("depthTexturesMiB", mib(depth));
            v.put("shadowDepthMiB", mib(shadowDepth));
            v.put("customImagesMiB", mib(images));
            v.put("totalDoubledMiB", mib(totalDoubled + depth + shadowDepth + images));
            dm.put("vram", v);
            out.put(d.getKey().isEmpty() ? "<root>" : d.getKey(), dm);
        }
        return out;
    }

    private static double mib(long b) {
        return Math.round(b / 1048576.0 * 10) / 10.0;
    }

    private static int num(String s) {
        String d = s.replaceAll("\\D", "");
        return d.isEmpty() ? -1 : Integer.parseInt(d);
    }

    private static double numberOr(String s, double def) {
        if (s == null)
            return def;
        try {
            return Double.parseDouble(s.replaceAll("[fF]$", "").trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void use(Map<String, Map<String, Object>> targets, String name, String how) {
        String n = Formats.canonical(name);
        if (!n.matches("colortex\\d+|shadowcolor\\d+"))
            return;
        Map<String, Object> t = targets.computeIfAbsent(n, k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("usedBy", new TreeSet<String>());
            return m;
        });
        @SuppressWarnings("unchecked") TreeSet<String> s = (TreeSet<String>) t.get("usedBy");
        s.add(how);
    }

    /** Uniform name -> class and how many units ask for it. */
    public static Map<String, Object> uniformUsage(FrontEnd.PackResult r) {
        Map<String, Map<String, Object>> m = new TreeMap<>();
        for (FrontEnd.Unit u : r.units) {
            if (u.result == null)
                continue;
            for (PackGlsl.UniformMember um : u.result.uniforms) {
                if (!u.result.referenced.contains(um.name()))
                    continue;
                Map<String, Object> x = m.computeIfAbsent(um.name(), k -> {
                    Map<String, Object> y = new LinkedHashMap<>();
                    y.put("type", um.type() + (um.array() > 0 ? "[" + um.array() + "]" : ""));
                    y.put("class", uniformClass(r, um.name(), um.source()));
                    y.put("units", 0);
                    return y;
                });
                x.put("units", (Integer) x.get("units") + 1);
            }
        }
        return new LinkedHashMap<>(m);
    }
}
