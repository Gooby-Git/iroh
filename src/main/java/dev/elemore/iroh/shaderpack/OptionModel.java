package dev.elemore.iroh.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage (iii): the pack's options, discovered from source lines (OptiFine rules, shaders.properties doc):
 * <ul>
 *   <li>boolean: {@code #define NAME} (on) or {@code // #define NAME} (off), recognised only when some file of the
 *       pack tests the name with a bare {@code #ifdef NAME} / {@code #ifndef NAME} ({@code defined NAME} inside
 *       an {@code #if} does not count, as observed in Iris);
 *       Iris behaviour, looser than OptiFine's documented same-file rule;</li>
 *   <li>value: {@code #define NAME value // comment [v1 v2 ...]}; a trailing {@code //} comment is required
 *       (Iris rule: a bare {@code #define PI 3.14} is not an option); the default is added to the value list;</li>
 *   <li>const: {@code const int|float|bool name = literal; // comment}, the Shaders Mod form;</li>
 *   <li>one option may appear in several files and is switched in all of them; differing defaults make the
 *       option ambiguous (OptiFine: disabled, cannot be changed): recorded, the saved value is not applied.</li>
 * </ul>
 * Applying values is the OptiFine/Iris way: the declaration line is rewritten while the preprocessor reads it
 * ({@link #rewriter()}), so a changed option acts everywhere the pack declares it, before any {@code #if}.
 */
public final class OptionModel {

    public enum Kind { BOOL, VALUE, CONST }

    public static final class Option {
        public final String name;
        public final Kind kind;
        public String defaultValue;
        public final List<String> values = new ArrayList<>();
        public String comment = "";
        public final Set<String> files = new LinkedHashSet<>();
        public boolean ambiguous;
        public String constType;   // CONST only

        Option(String name, Kind kind) {
            this.name = name;
            this.kind = kind;
        }
    }

    private static final Pattern BOOL = Pattern.compile("^\\s*(//+\\s*)?#\\s*define\\s+([A-Za-z_]\\w*)\\s*(?://(.*))?$");
    private static final Pattern VALUE = Pattern.compile("^\\s*#\\s*define\\s+([A-Za-z_]\\w*)\\s+([^\\s/]+)\\s*//(.*)$");
    private static final Pattern CONST = Pattern.compile("^\\s*const\\s+(int|float|bool)\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;\\s]+)\\s*;\\s*//(.*)$");
    private static final Pattern LIST = Pattern.compile("\\[([^\\]]*)\\]");
    private static final Pattern LITERAL = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?[fF]?|true|false");

    private final Map<String, Option> options = new TreeMap<>();
    /** effective value per option (default, or the saved one); booleans "true"/"false" */
    private final Map<String, String> values = new TreeMap<>();
    private final List<String> notes = new ArrayList<>();
    private final Map<String, String> savedUnknown = new TreeMap<>();
    private final Map<String, String> savedApplied = new TreeMap<>();

    public static boolean isSourceFile(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".glsl") || p.endsWith(".fsh") || p.endsWith(".vsh") || p.endsWith(".csh")
                || p.endsWith(".gsh") || p.endsWith(".tcs") || p.endsWith(".tes") || p.endsWith(".inc");
    }

    public static OptionModel discover(PackSource pack) {
        OptionModel m = new OptionModel();
        // Iris accepts a boolean whose #ifdef test is in ANY file of the pack (Navidson's BORDER_FOG, VIGNETTE_R:
        // defined in lib/common.glsl, tested elsewhere, and saved in Navidson.txt by Iris); OptiFine's doc says
        // "same file". The pack-wide rule is the one the owner's saved options were made with.
        Set<String> tested = new java.util.HashSet<>();
        for (String path : pack.paths())
            if (isSourceFile(path))
                tested.addAll(testedNames(pack.text(path)));
        for (String path : pack.paths()) {
            if (!isSourceFile(path))
                continue;
            String text = pack.text(path);
            for (String line : text.split("\n")) {
                if (line.indexOf("define") < 0 && line.indexOf("const") < 0)
                    continue;
                Matcher b = BOOL.matcher(line);
                if (b.matches()) {
                    String name = b.group(2);
                    if (tested.contains(name))
                        m.add(path, name, Kind.BOOL, b.group(1) == null ? "true" : "false", b.group(3), null);
                    continue;
                }
                Matcher v = VALUE.matcher(line);
                if (v.matches()) {
                    m.add(path, v.group(1), Kind.VALUE, v.group(2), v.group(3), null);
                    continue;
                }
                Matcher c = CONST.matcher(line);
                if (c.matches() && LITERAL.matcher(c.group(3)).matches())
                    m.add(path, c.group(2), Kind.CONST, c.group(3), c.group(4), c.group(1));
            }
        }
        for (Option o : m.options.values())
            m.values.put(o.name, o.defaultValue);
        return m;
    }

    private static Set<String> testedNames(String text) {
        Set<String> s = new java.util.HashSet<>();
        // only a bare #ifdef / #ifndef registers a boolean: "#if defined A && defined NAME" alone leaves Iris's
        // menu button unresolved (owner's note from Iris's latest.log, memory project_navidson_shader)
        Matcher m = Pattern.compile("#\\s*ifn?def\\s+([A-Za-z_]\\w*)").matcher(text);
        while (m.find())
            s.add(m.group(1));
        return s;
    }

    private void add(String path, String name, Kind kind, String def, String comment, String constType) {
        Option o = options.get(name);
        if (o == null) {
            o = new Option(name, kind);
            o.defaultValue = def;
            o.constType = constType;
            o.comment = comment == null ? "" : comment.trim();
            if (comment != null) {
                Matcher l = LIST.matcher(comment);
                if (l.find())
                    for (String s : l.group(1).trim().split("\\s+"))
                        if (!s.isEmpty() && !o.values.contains(s))
                            o.values.add(s);
            }
            if (kind == Kind.BOOL) {
                o.values.clear();
                o.values.add("true");
                o.values.add("false");
            } else if (!o.values.contains(def)) {
                o.values.add(0, def);
            }
            options.put(name, o);
        } else if (o.kind != kind || !o.defaultValue.equals(def)) {
            if (!o.ambiguous)
                notes.add("option " + name + " is ambiguous: " + o.kind + " " + o.defaultValue + " in " + o.files
                        + " vs " + kind + " " + def + " in " + path + " (disabled, as OptiFine does)");
            o.ambiguous = true;
        }
        o.files.add(path);
    }

    /** Applies a saved option file ({@code <pack>.txt}, Java properties text). */
    public void applySaved(String text) {
        if (text == null)
            return;
        for (String line : text.replace("\r", "").split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("!"))
                continue;
            int eq = l.indexOf('=');
            if (eq < 0)
                continue;
            String k = l.substring(0, eq).trim();
            String v = l.substring(eq + 1).trim();
            Option o = options.get(k);
            if (o == null) {
                savedUnknown.put(k, v);
                continue;
            }
            if (o.ambiguous) {
                notes.add("saved value " + k + "=" + v + " not applied: option is ambiguous");
                continue;
            }
            if (o.kind == Kind.BOOL && !v.equals("true") && !v.equals("false")) {
                notes.add("saved value " + k + "=" + v + " is not a boolean: ignored");
                continue;
            }
            if (o.kind != Kind.BOOL && !o.values.contains(v))
                notes.add("saved value " + k + "=" + v + " is not in the option's list " + o.values + " (applied anyway)");
            values.put(k, v);
            savedApplied.put(k, v);
        }
    }

    /** Sets one option (profiles, tests). */
    public void set(String name, String value) {
        if (options.containsKey(name))
            values.put(name, value);
    }

    public Map<String, Option> options() {
        return options;
    }

    public Map<String, String> values() {
        return values;
    }

    public List<String> notes() {
        return notes;
    }

    public Map<String, String> savedUnknown() {
        return savedUnknown;
    }

    public Map<String, String> savedApplied() {
        return savedApplied;
    }

    /** Option macros as the preprocessor of shaders.properties sees them: booleans on are defined (empty). */
    public Map<String, String> macroValues() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Option o : options.values()) {
            String v = values.get(o.name);
            if (o.kind == Kind.BOOL) {
                if ("true".equals(v))
                    m.put(o.name, "");
            } else if (o.kind == Kind.VALUE) {
                m.put(o.name, v);
            }
        }
        return m;
    }

    /** Rewrites option declaration lines to the effective values. Returns the same instance when unchanged. */
    public UnaryOperator<String> rewriter() {
        return line -> {
            int d = line.indexOf("define");
            if (d >= 0) {
                Matcher b = BOOL.matcher(line);
                if (b.matches()) {
                    Option o = options.get(b.group(2));
                    if (o == null || o.kind != Kind.BOOL || o.ambiguous)
                        return line;
                    boolean on = "true".equals(values.get(o.name));
                    boolean written = b.group(1) == null;
                    if (on == written)
                        return line;
                    String tail = b.group(3) == null ? "" : " //" + b.group(3);
                    return on ? "#define " + o.name + tail : "//#define " + o.name + tail;
                }
                Matcher v = VALUE.matcher(line);
                if (v.matches()) {
                    Option o = options.get(v.group(1));
                    if (o == null || o.kind != Kind.VALUE || o.ambiguous)
                        return line;
                    String val = values.get(o.name);
                    if (val == null || val.equals(v.group(2)))
                        return line;
                    return "#define " + o.name + " " + val + " //" + v.group(3);
                }
                return line;
            }
            if (line.indexOf("const") >= 0) {
                Matcher c = CONST.matcher(line);
                if (c.matches()) {
                    Option o = options.get(c.group(2));
                    if (o == null || o.kind != Kind.CONST || o.ambiguous)
                        return line;
                    String val = values.get(o.name);
                    if (val == null || val.equals(c.group(3)))
                        return line;
                    return "const " + c.group(1) + " " + o.name + " = " + val + "; //" + c.group(4);
                }
            }
            return line;
        };
    }
}
