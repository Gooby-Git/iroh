package dev.elemore.iroh.shaderpack;

import dev.elemore.iroh.shaderpack.PackGlsl.Stage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pack front end (P3-1): stages (i) file system, (ii) properties and id maps, (iii) options,
 * (iv) programs, (v) source assembly ({@link Cpp}), (vi) translation ({@link PackGlsl}) and shaderc, each a
 * function of the previous one's output, each dumped to JSON so a failure is attributable to one stage.
 * Pure: reads the pack, writes only into the output folder given (or nothing).
 */
public final class FrontEnd {

    public record Config(StandardMacros.Env env, String savedOptions, boolean compile, Path outDir) {}

    /** One compiled unit: a raster program or one compute file. */
    public static final class Unit {
        public final String dir;
        public final String program;
        public final String id;            // dir/program[.csh name]
        public final boolean compute;
        public final boolean enabled;
        /** why the unit is not drawn: "toggle" (program.*.enabled) or a named reason; null = served */
        public final String offReason;
        public final Map<Stage, String> paths = new LinkedHashMap<>();
        public final Map<Stage, PackGlsl.Unit> parsed = new LinkedHashMap<>();
        public final Map<Stage, List<Cpp.Origin>> origins = new LinkedHashMap<>();
        public final Map<Stage, List<String>> includes = new LinkedHashMap<>();
        public PackGlsl.Result result;
        public final Map<Stage, byte[]> spirv = new LinkedHashMap<>();
        public String failStage;          // null = pass
        public String failMessage;
        public String failClass;
        public final List<String> notes = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();

        Unit(String dir, String program, String id, boolean compute, boolean enabled) {
            this.dir = dir;
            this.program = program;
            this.id = id;
            this.compute = compute;
            String why = unservedReason(program);
            this.offReason = why != null ? why : enabled ? null : "switched off by program." + program + ".enabled";
            this.enabled = enabled && why == null;
        }

        public boolean passed() {
            return failStage == null;
        }

        void fail(String stage, String cls, String msg) {
            if (failStage == null) {
                failStage = stage;
                failClass = cls;
                failMessage = msg;
            }
        }
    }

    public static final class PackResult {
        public PackSource pack;
        public OptionModel options;
        public PackProperties properties;
        public ProgramSet programs;
        public IdMaps idMaps;
        public CustomUniforms customUniforms;
        public Map<String, Integer> bindings;
        public final List<Unit> units = new ArrayList<>();
        public final List<String> packErrors = new ArrayList<>();
        public Map<String, String> macros;
        public long millis;
    }

    private FrontEnd() {}

    /**
     * Programs a pack ships for another mod's renderer, which Iroh does not drive. They are still translated
     * and compiled (so the report shows what they would need) but a failure does not fail the pack.
     */
    public static String unservedReason(String program) {
        if (program.startsWith("clrwl_"))
            return "Colorwheel (Flywheel's Iris bridge) program: its clrwl_* functions come from that mod at link time";
        if (program.startsWith("dh_"))
            return "Distant Horizons program (DH is unsupported under Iroh, DECISIONS #13; its dhMaterialId etc. are DH's)";
        if (program.startsWith("voxy_"))
            return "Voxy program (Voxy is unsupported under Iroh, DECISIONS #13)";
        return null;
    }

    public static PackResult run(PackSource pack, Config cfg) {
        long t0 = System.nanoTime();
        PackResult res = new PackResult();
        res.pack = pack;
        Map<String, String> std = StandardMacros.of(cfg.env());
        res.macros = std;

        // (iii) options first: the properties files are preprocessed with option macros
        res.options = OptionModel.discover(pack);
        res.options.applySaved(cfg.savedOptions());
        // (ii)
        res.properties = PackProperties.read(pack, std, res.options);
        for (String f : res.properties.featuresRequired)
            if (!cfg.env().features().contains(f))
                res.packErrors.add("iris.features.required names " + f + ", which Iroh does not serve: the pack is refused (Iris rule)");
        Map<String, String> propMacros = new LinkedHashMap<>(std);
        propMacros.putAll(res.options.macroValues());
        res.idMaps = IdMaps.read(pack, "", propMacros);
        res.customUniforms = CustomUniforms.check(res.properties.customUniforms);
        // (iv)
        res.programs = ProgramSet.discover(pack, std, res.properties.programEnabled);

        // (v) assemble every unit
        for (Map.Entry<String, Map<String, ProgramSet.Program>> d : res.programs.byDir.entrySet()) {
            for (ProgramSet.Program p : d.getValue().values()) {
                if (!p.stages.isEmpty()) {
                    Unit u = new Unit(p.dir, p.name, p.id(), false, p.enabled);
                    p.stages.forEach((k, v) -> u.paths.put(Stage.of(k), v));
                    u.notes.addAll(p.notes);
                    res.units.add(u);
                }
                for (String c : p.computes) {
                    String file = c.substring(c.lastIndexOf('/') + 1);
                    Unit u = new Unit(p.dir, p.name, (p.dir.isEmpty() ? "" : p.dir + "/") + file, true, p.enabled);
                    u.paths.put(Stage.COMPUTE, c);
                    res.units.add(u);
                }
            }
        }
        if (!res.packErrors.isEmpty()) {
            res.millis = (System.nanoTime() - t0) / 1_000_000;
            if (cfg.outDir() != null) {
                try {
                    PackDump.write(res, cfg.outDir());
                } catch (IOException e) {
                    res.packErrors.add("could not write the dump: " + e);
                }
            }
            return res;
        }

        Map<String, String> glslMacros = new LinkedHashMap<>(std);
        glslMacros.putAll(StandardMacros.glslOnly());
        res.units.parallelStream().forEach(u -> assemble(pack, u, glslMacros, res.options));

        // pack-wide binding table for samplers and images (stable: standard names fixed, the rest sorted)
        Set<String> opaque = new TreeSet<>();
        for (Unit u : res.units)
            for (PackGlsl.Unit pu : u.parsed.values())
                for (PackGlsl.Decl dcl : pu.decls)
                    if (dcl.kind.equals("sampler") || dcl.kind.equals("image"))
                        opaque.add(dcl.name);
        res.bindings = bindingTable(opaque);
        Map<String, Integer> table = res.bindings;

        // (vi) translate + compile
        Map<String, String> imageFormats = new LinkedHashMap<>();
        res.properties.images.forEach((name, m) -> imageFormats.put(name, String.valueOf(m.get("internalFormat"))));
        res.units.parallelStream().forEach(u -> translate(u, table::get, imageFormats, cfg.compile()));
        res.millis = (System.nanoTime() - t0) / 1_000_000;
        if (cfg.outDir() != null) {
            try {
                PackDump.write(res, cfg.outDir());
            } catch (IOException e) {
                res.packErrors.add("could not write the dump: " + e);
            }
        }
        return res;
    }

    /** Standard sampler/image names at fixed bindings 1..; pack-specific names from 128 in name order. */
    static Map<String, Integer> bindingTable(Set<String> names) {
        Map<String, Integer> m = new LinkedHashMap<>();
        int b = 1;
        for (String s : UniformCatalog.STANDARD_SAMPLERS)
            m.put(s, b++);
        int next = 128;
        for (String n : names)
            if (!m.containsKey(n))
                m.put(n, next++);
        return Collections.unmodifiableMap(m);
    }

    private static void assemble(PackSource pack, Unit u, Map<String, String> macros, OptionModel options) {
        for (Map.Entry<Stage, String> e : u.paths.entrySet()) {
            Cpp cpp = new Cpp((from, path) -> {
                String base = from.contains("/") ? from.substring(0, from.lastIndexOf('/')) : "";
                String norm = PackSource.normalise(base, path);
                if (norm == null)
                    throw new Cpp.CppException(from, 0, "#include \"" + path + "\" leaves shaders/");
                String exact = pack.resolve(norm);
                if (exact == null)
                    return null;
                return new String[]{exact, pack.text(exact)};
            }, false);
            macros.forEach(cpp::define);
            cpp.setLineRewriter(options.rewriter());
            try {
                cpp.run(e.getValue(), pack.text(e.getValue()));
            } catch (Cpp.CppException ex) {
                u.fail("preprocess", classOf("preprocess", ex.getMessage()), ex.getMessage());
                return;
            } catch (RuntimeException ex) {
                u.fail("preprocess", "preprocess: internal error", e.getValue() + ": " + ex);
                return;
            }
            u.origins.put(e.getKey(), cpp.origins());
            u.includes.put(e.getKey(), cpp.includes());
            for (String n : cpp.notes())
                u.notes.add(n);
            try {
                u.parsed.put(e.getKey(), PackGlsl.analyze(e.getKey(), cpp.text()));
            } catch (PackGlsl.TranslateException ex) {
                String where = where(u, e.getKey(), ex.line);
                u.fail("parse", classOf("parse", ex.getMessage()), where + ": " + ex.getMessage());
                return;
            } catch (RuntimeException ex) {
                u.fail("parse", "parse: internal error", e.getValue() + ": " + ex);
                return;
            }
        }
    }

    static String where(Unit u, Stage s, int line) {
        List<Cpp.Origin> o = u.origins.get(s);
        if (o == null || line <= 0 || line > o.size())
            return u.paths.getOrDefault(s, "?") + (line > 0 ? " (unit line " + line + ")" : "");
        Cpp.Origin x = o.get(line - 1);
        return x.file() + ":" + x.line();
    }

    private static final Pattern SHADERC_LINE = Pattern.compile("^[^:\n]*:(\\d+): (error|warning): (.*)$", Pattern.MULTILINE);

    private static void translate(Unit u, PackGlsl.PackBindings bindings, Map<String, String> imageFormats, boolean compile) {
        if (!u.passed())
            return;
        try {
            u.result = PackGlsl.translate(u.parsed, bindings, imageFormats);
        } catch (PackGlsl.TranslateException ex) {
            Stage s = ex.stage == null ? u.paths.keySet().iterator().next() : ex.stage;
            u.fail("translate", classOf("translate", ex.getMessage()), where(u, s, ex.line) + ": " + ex.getMessage());
            return;
        } catch (RuntimeException ex) {
            StackTraceElement[] st = ex.getStackTrace();
            u.fail("translate", "translate: internal error", ex + (st.length > 0 ? " at " + st[0] : ""));
            return;
        }
        u.notes.addAll(u.result.notes);
        if (!compile)
            return;
        for (Map.Entry<Stage, String> e : u.result.glsl.entrySet()) {
            PackCompiler.Compiled c = PackCompiler.compile(e.getKey(), e.getValue(), u.id + "." + e.getKey().key);
            if (c.error() != null) {
                // first error line, mapped back to the pack file
                Matcher m = SHADERC_LINE.matcher(c.error());
                String msg = c.error().strip();
                String mapped = null;
                while (m.find()) {
                    if (!m.group(2).equals("error"))
                        continue;
                    int outLine = Integer.parseInt(m.group(1));
                    int hl = u.result.headerLines.get(e.getKey());
                    String loc;
                    if (outLine <= hl) {
                        loc = "<iroh header line " + outLine + ">";
                    } else {
                        int unitLine = outLine - hl;
                        List<Cpp.Origin> o = u.origins.get(e.getKey());
                        loc = unitLine <= o.size() ? where(u, e.getKey(), unitLine) : "<iroh main wrapper>";
                    }
                    String src = sourceLine(e.getValue(), outLine);
                    mapped = loc + ": " + m.group(3) + (src.isEmpty() ? "" : "  [" + src + "]");
                    break;
                }
                u.fail("compile:" + e.getKey().key, classOf("compile", mapped == null ? msg : mapped), mapped == null ? msg : mapped);
                return;
            }
            if (!c.warnings().isBlank())
                u.warnings.add(e.getKey().key + ": " + c.warnings().strip());
            u.spirv.put(e.getKey(), c.spirv());
        }
        checkInterfaces(u);
    }

    /**
     * Pairs the compiled stages: each fragment input location must be written by a vertex output of the same
     * type, and the bindings in the modules must be the ones the reflection record names.
     */
    static void checkInterfaces(Unit u) {
        Map<Stage, List<SpirvReflect.Var>> refl = new LinkedHashMap<>();
        try {
            u.spirv.forEach((s, b) -> refl.put(s, SpirvReflect.read(b)));
        } catch (RuntimeException ex) {
            u.fail("interface", "interface: SPIR-V unreadable", ex.toString());
            return;
        }
        List<SpirvReflect.Var> vs = refl.get(Stage.VERTEX);
        List<SpirvReflect.Var> fs = refl.get(Stage.FRAGMENT);
        if (vs != null && fs != null) {
            Map<Integer, SpirvReflect.Var> outs = new java.util.HashMap<>();
            for (SpirvReflect.Var v : vs)
                if (v.storage().equals("out") && !v.builtin() && v.location() >= 0)
                    outs.put(v.location(), v);
            for (SpirvReflect.Var v : fs) {
                if (!v.storage().equals("in") || v.builtin() || v.location() < 0)
                    continue;
                SpirvReflect.Var o = outs.get(v.location());
                if (o == null) {
                    u.fail("interface", "interface: fragment input without a vertex output", "fragment input " + v.name()
                            + " at location " + v.location() + " is not written by the vertex stage");
                    return;
                }
                if (!o.type().equals(v.type())) {
                    u.fail("interface", "interface: varying type mismatch", "location " + v.location() + ": vertex writes "
                            + o.name() + " " + o.type() + ", fragment reads " + v.name() + " " + v.type());
                    return;
                }
            }
        }
        // every descriptor the modules declare must be in the reflection record at the same set/binding
        Map<String, int[]> declared = new java.util.HashMap<>();
        for (PackGlsl.Binding b : u.result.samplers)
            declared.put(b.name(), new int[]{b.set(), b.binding()});
        for (PackGlsl.Binding b : u.result.images)
            declared.put(b.name(), new int[]{b.set(), b.binding()});
        for (List<SpirvReflect.Var> l : refl.values())
            for (SpirvReflect.Var v : l) {
                if (!v.storage().equals("uniform_constant") || v.binding() < 0)
                    continue;
                String n = v.name();
                if (n.startsWith("iroh_u_"))
                    n = n.substring(7);          // renamed pack variable ("texture")
                else if (n.endsWith("_iroh"))
                    n = n.substring(0, n.length() - 5); // keyword collision rename
                int[] want = declared.get(n);
                if (want == null || want[0] != v.set() || want[1] != v.binding()) {
                    u.fail("interface", "interface: descriptor not in the reflection record", v.name() + " set " + v.set()
                            + " binding " + v.binding() + " (record: " + (want == null ? "none" : want[0] + "/" + want[1]) + ")");
                    return;
                }
            }
    }

    static String sourceLine(String text, int line) {
        int i = 0;
        for (int k = 1; k < line && i >= 0; k++)
            i = text.indexOf('\n', i) + 1;
        if (i < 0 || i >= text.length())
            return "";
        int e = text.indexOf('\n', i);
        String s = (e < 0 ? text.substring(i) : text.substring(i, e)).strip();
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    /** A failure class: the message with names, numbers and paths taken out, so failures group by cause. */
    static String classOf(String stage, String msg) {
        String m = msg;
        int bracket = m.indexOf("  [");
        if (bracket > 0)
            m = m.substring(0, bracket);
        m = m.replaceAll("^[^ ]*:\\d+: ", "");
        m = m.replaceAll("'[^']*'", "'X'");
        m = m.replaceAll("\"[^\"]*\"", "\"X\"");
        m = m.replaceAll("\\b\\d+\\b", "N");
        m = m.replaceAll("(uniform|sampler|varying|image|option|macro) \\w+", "$1 X");
        return stage + ": " + m.strip();
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Units grouped by failure class. */
    public static Map<String, List<Unit>> failureClasses(PackResult r) {
        Map<String, List<Unit>> m = new TreeMap<>();
        for (Unit u : r.units)
            if (!u.passed())
                m.computeIfAbsent(u.failClass, k -> new ArrayList<>()).add(u);
        return m;
    }
}
