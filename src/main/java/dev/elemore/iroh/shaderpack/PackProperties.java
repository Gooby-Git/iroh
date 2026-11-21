package dev.elemore.iroh.shaderpack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Stage (ii): {@code shaders.properties} interpreted (OptiFine shaders.properties doc + Iris feature doc).
 * Read twice: once preprocessed with standard + option macros (most keys), once with standard macros only for
 * the keys OptiFine reads "without option macros" (version, oldLighting, separateAo, sliders, profiles, screen).
 * Every key lands in exactly one category; anything Iroh does not know stays in {@code flags} untouched.
 */
public final class PackProperties implements Json.Dumpable {

    public static final Set<String> BLEND_FACTORS = Set.of("ZERO", "ONE", "SRC_COLOR", "ONE_MINUS_SRC_COLOR", "DST_COLOR",
            "ONE_MINUS_DST_COLOR", "SRC_ALPHA", "ONE_MINUS_SRC_ALPHA", "DST_ALPHA", "ONE_MINUS_DST_ALPHA", "SRC_ALPHA_SATURATE");
    public static final Set<String> ALPHA_FUNCS = Set.of("NEVER", "LESS", "EQUAL", "LEQUAL", "GREATER", "NOTEQUAL", "GEQUAL", "ALWAYS");

    public record CustomUniformDecl(String kind, String type, String name, String expr, String file, int line) {}

    public final Map<String, List<String>> screens = new LinkedHashMap<>();
    public final Map<String, String> screenColumns = new LinkedHashMap<>();
    public final List<String> sliders = new ArrayList<>();
    public final Map<String, List<String>> profiles = new LinkedHashMap<>();
    public final Map<String, String> programToggles = new LinkedHashMap<>();
    public final Map<String, Boolean> programEnabled = new LinkedHashMap<>();
    public final Map<String, Object> alphaTest = new LinkedHashMap<>();
    public final Map<String, Object> blend = new LinkedHashMap<>();
    public final Map<String, Object> scale = new LinkedHashMap<>();
    public final Map<String, Object> sizeBuffer = new LinkedHashMap<>();
    public final Map<String, Boolean> flip = new LinkedHashMap<>();
    public final Map<String, String> textures = new LinkedHashMap<>();
    public final Map<String, String> customTextures = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> images = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> bufferObjects = new LinkedHashMap<>();
    public final List<CustomUniformDecl> customUniforms = new ArrayList<>();
    public final List<String> featuresRequired = new ArrayList<>();
    public final List<String> featuresOptional = new ArrayList<>();
    public final Map<String, String> flags = new TreeMap<>();
    public final Map<String, String> flatKeys = new TreeMap<>();
    public final List<String> notes = new ArrayList<>();

    public static PackProperties read(PackSource pack, Map<String, String> standard, OptionModel options) {
        PackProperties pp = new PackProperties();
        String text = pack.text("shaders.properties");
        if (text == null) {
            pp.notes.add("no shaders.properties");
            return pp;
        }
        Map<String, String> all = new LinkedHashMap<>(standard);
        all.putAll(options.macroValues());
        PropertiesText full = PropertiesText.parse("shaders.properties", text, all);
        PropertiesText flat = PropertiesText.parse("shaders.properties", text, standard);
        pp.notes.addAll(full.notes());

        for (PropertiesText.Entry e : flat.entries().values()) {
            String k = e.key();
            if (k.equals("sliders")) {
                pp.sliders.addAll(words(e.value()));
            } else if (k.equals("screen.columns")) {
                pp.screenColumns.put("", e.value()); // main screen
            } else if (k.equals("screen") || (k.startsWith("screen.") && !k.endsWith(".columns"))) {
                pp.screens.put(k.equals("screen") ? "" : k.substring(7), words(e.value()));
            } else if (k.startsWith("screen.") && k.endsWith(".columns")) {
                pp.screenColumns.put(k.substring(7, k.length() - 8), e.value());
            } else if (k.startsWith("profile.")) {
                pp.profiles.put(k.substring(8), words(e.value()));
            } else if (k.startsWith("version.") || k.equals("oldLighting") || k.equals("separateAo")) {
                pp.flatKeys.put(k, e.value());
            }
        }

        for (PropertiesText.Entry e : full.entries().values()) {
            String k = e.key();
            String v = e.value();
            if (k.equals("sliders") || k.equals("screen") || k.startsWith("screen.") || k.startsWith("profile.")
                    || k.startsWith("version.") || k.equals("oldLighting") || k.equals("separateAo"))
                continue;
            try {
                pp.classify(k, v, e, all);
            } catch (RuntimeException ex) {
                pp.notes.add(e.file() + ":" + e.line() + ": " + k + ": " + ex.getMessage());
            }
        }
        return pp;
    }

    private void classify(String k, String v, PropertiesText.Entry e, Map<String, String> macros) {
        if (k.startsWith("program.") && k.endsWith(".enabled")) {
            String prog = k.substring(8, k.length() - 8);
            programToggles.put(prog, v);
            programEnabled.put(prog, evalToggle(v, macros));
        } else if (k.startsWith("alphaTest.")) {
            String prog = k.substring(10);
            List<String> w = words(v);
            if (w.size() == 1 && w.get(0).equalsIgnoreCase("off")) {
                alphaTest.put(prog, "off");
            } else if (w.size() == 2 && ALPHA_FUNCS.contains(w.get(0))) {
                alphaTest.put(prog, Json.obj("func", w.get(0), "ref", Double.parseDouble(w.get(1))));
            } else {
                throw new IllegalArgumentException("bad alphaTest '" + v + "'");
            }
        } else if (k.startsWith("blend.")) {
            String rest = k.substring(6);
            List<String> w = words(v);
            if (w.size() == 1 && w.get(0).equalsIgnoreCase("off")) {
                blend.put(rest, "off");
            } else if (w.size() == 4 && BLEND_FACTORS.containsAll(w)) {
                blend.put(rest, w);
            } else {
                throw new IllegalArgumentException("bad blend '" + v + "'");
            }
        } else if (k.startsWith("scale.")) {
            List<Double> d = new ArrayList<>();
            for (String s : words(v))
                d.add(Double.parseDouble(s));
            if (d.isEmpty() || d.size() > 3)
                throw new IllegalArgumentException("bad scale '" + v + "'");
            scale.put(k.substring(6), d);
        } else if (k.startsWith("size.buffer.")) {
            List<String> d = words(v);
            if (d.size() != 2)
                throw new IllegalArgumentException("bad size.buffer '" + v + "'");
            for (String s : d)
                Double.parseDouble(s);
            sizeBuffer.put(k.substring(12), d); // a value with '.' is relative to the render size (OptiFine doc)
        } else if (k.startsWith("flip.")) {
            flip.put(k.substring(5), Boolean.parseBoolean(v.trim()));
        } else if (k.startsWith("texture.")) {
            textures.put(k.substring(8), v);
        } else if (k.startsWith("customTexture.")) {
            customTextures.put(k.substring(14), v);
        } else if (k.startsWith("image.")) {
            List<String> w = words(v);
            if (w.size() < 8)
                throw new IllegalArgumentException("image needs 'sampler format internalFormat pixelType clear relative sx sy [sz]', got '" + v + "'");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sampler", w.get(0));
            m.put("format", w.get(1));
            m.put("internalFormat", w.get(2));
            m.put("pixelType", w.get(3));
            m.put("clearEachFrame", Boolean.parseBoolean(w.get(4)));
            boolean rel = Boolean.parseBoolean(w.get(5));
            m.put("relative", rel);
            List<Double> size = new ArrayList<>();
            for (String s : w.subList(6, w.size()))
                size.add(Double.parseDouble(s));
            m.put("size", size);
            m.put("dimensions", rel ? 2 : size.size());
            images.put(k.substring(6), m);
        } else if (k.startsWith("bufferObject.")) {
            List<String> w = words(v);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bytes", Long.parseLong(w.get(0)));
            if (w.size() >= 4) {
                m.put("relative", Boolean.parseBoolean(w.get(1)));
                m.put("scale", List.of(Double.parseDouble(w.get(2)), Double.parseDouble(w.get(3))));
            }
            bufferObjects.put(k.substring(13), m);
        } else if (k.startsWith("uniform.") || k.startsWith("variable.")) {
            String[] p = k.split("\\.", 3);
            if (p.length != 3)
                throw new IllegalArgumentException("expected <kind>.<type>.<name>");
            customUniforms.add(new CustomUniformDecl(p[0], p[1], p[2], v, e.file(), e.line()));
        } else if (k.equals("iris.features.required")) {
            featuresRequired.addAll(words(v));
        } else if (k.equals("iris.features.optional")) {
            featuresOptional.addAll(words(v));
        } else {
            flags.put(k, v);
        }
    }

    /** OptiFine toggle expression: option names (on = defined), && || ! ( ). Unknown names are false. */
    static boolean evalToggle(String expr, Map<String, String> macros) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < expr.length() && (Character.isLetterOrDigit(expr.charAt(j)) || expr.charAt(j) == '_'))
                    j++;
                String id = expr.substring(i, j);
                if (id.equals("true") || id.equals("false")) {
                    sb.append(id.equals("true") ? "1" : "0");
                } else {
                    String body = macros.get(id);
                    if (body == null)
                        sb.append('0');
                    else if (body.isBlank())
                        sb.append('1');
                    else
                        sb.append('(').append(body).append(')');
                }
                i = j;
            } else {
                sb.append(c);
                i++;
            }
        }
        Cpp cpp = new Cpp(null, true);
        return cpp.evalCondition(sb.toString());
    }

    static List<String> words(String v) {
        List<String> l = new ArrayList<>();
        for (String s : v.trim().split("\\s+"))
            if (!s.isEmpty())
                l.add(s);
        return l;
    }

    @Override
    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("screens", screens);
        ui.put("columns", screenColumns);
        ui.put("sliders", sliders);
        ui.put("profiles", profiles);
        m.put("optionScreen", ui);
        Map<String, Object> toggles = new LinkedHashMap<>();
        programToggles.forEach((p, e) -> toggles.put(p, Json.obj("expr", e, "enabled", programEnabled.get(p))));
        m.put("programToggles", toggles);
        m.put("alphaTest", alphaTest);
        m.put("blend", blend);
        m.put("scale", scale);
        m.put("sizeBuffer", sizeBuffer);
        m.put("flip", flip);
        m.put("textures", textures);
        m.put("customTextures", customTextures);
        m.put("images", images);
        m.put("bufferObjects", bufferObjects);
        m.put("features", Json.obj("required", featuresRequired, "optional", featuresOptional,
                "unservedRequired", featuresRequired.stream().filter(f -> !StandardMacros.SERVED_FEATURES.contains(f)).toList(),
                "unservedOptional", featuresOptional.stream().filter(f -> !StandardMacros.SERVED_FEATURES.contains(f)).toList()));
        m.put("flatKeys", flatKeys);
        m.put("flags", flags);
        m.put("notes", notes);
        return m;
    }

    @SuppressWarnings("unused")
    private static List<String> list(String... s) {
        return Arrays.asList(s);
    }
}
