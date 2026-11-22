package dev.elemore.iroh.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A properties file of a pack after its conditional preprocessing: ordered keys (the last assignment of a key
 * wins, as in java.util.Properties) with the line each came from. Key/value split follows java.util.Properties:
 * the key ends at the first '=', ':' or whitespace; '#' and '!' lines are comments; a trailing backslash
 * continues the line. Conditionals run on joined lines (Vitrail trap: decide before joining is wrong).
 */
public final class PropertiesText {

    public record Entry(String key, String value, String file, int line) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();

    public static PropertiesText parse(String file, String text, Map<String, String> macros) {
        PropertiesText p = new PropertiesText();
        if (text == null)
            return p;
        Cpp cpp = new Cpp(null, true);
        macros.forEach(cpp::define);
        try {
            cpp.run(file, text);
        } catch (Cpp.CppException e) {
            p.notes.add("preprocessing failed: " + e.getMessage());
            return p;
        }
        p.notes.addAll(cpp.notes());
        String[] lines = cpp.text().split("\n", -1);
        List<Cpp.Origin> origins = cpp.origins();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].strip();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("!"))
                continue;
            int k = 0;
            while (k < l.length() && l.charAt(k) != '=' && l.charAt(k) != ':' && !Character.isWhitespace(l.charAt(k)))
                k++;
            String key = l.substring(0, k);
            int v = k;
            while (v < l.length() && Character.isWhitespace(l.charAt(v)))
                v++;
            if (v < l.length() && (l.charAt(v) == '=' || l.charAt(v) == ':'))
                v++;
            while (v < l.length() && Character.isWhitespace(l.charAt(v)))
                v++;
            String value = l.substring(v).strip();
            // Iris preprocesses the file as text, so option macros in values expand
            // (Navidson: size.buffer.colortex1 = REFLECTION_RES REFLECTION_RES). Not for keys whose values
            // name options themselves (toggles, screens, sliders, profiles).
            if (!(key.startsWith("program.") && key.endsWith(".enabled")) && !key.startsWith("screen")
                    && !key.equals("sliders") && !key.startsWith("profile."))
                value = cpp.expandText(value).strip().replaceAll("\\s+", " ");
            Cpp.Origin o = i < origins.size() ? origins.get(i) : new Cpp.Origin(file, i + 1);
            p.entries.remove(key);
            p.entries.put(key, new Entry(key, value, o.file(), o.line()));
        }
        return p;
    }

    public Map<String, Entry> entries() {
        return entries;
    }

    public String get(String key) {
        Entry e = entries.get(key);
        return e == null ? null : e.value();
    }

    public List<String> notes() {
        return notes;
    }
}
