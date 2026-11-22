package dev.elemore.iroh.packtest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.elemore.iroh.shaderpack.FrontEnd;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Expectations of a self-made test pack ({@code iroh-expect.txt} beside its {@code shaders/}):
 * <pre>
 *   pass &lt;unit id&gt;
 *   fail &lt;unit id&gt; &lt;substring of the failure message&gt;
 *   packerror &lt;substring&gt;
 *   json &lt;file under the dump&gt; &lt;dotted.path[0].key&gt; = &lt;value as JSON text or bare string&gt;
 *   jsonhas &lt;file&gt; &lt;dotted.path&gt; &lt;substring of the element's JSON text&gt;
 *   glsl &lt;file under glsl/&gt; &lt;substring that must appear&gt;
 * </pre>
 * Each wrong line prints {@code EXPECT-WRONG ...}.
 */
final class Expect {

    private static final Set<String> expectedFails = new HashSet<>();
    private static boolean packError;

    private Expect() {}

    static boolean expectedFail(String id) {
        return expectedFails.contains(id);
    }

    static boolean expectedPackError() {
        return packError;
    }

    static void reset() {
        expectedFails.clear();
        packError = false;
    }

    static int[] check(FrontEnd.PackResult r, List<String> lines, Path dump) {
        int checked = 0;
        int wrong = 0;
        for (String raw : lines) {
            String l = raw.strip();
            if (l.isEmpty() || l.startsWith("#"))
                continue;
            checked++;
            String[] p = l.split("\\s+", 3);
            String why = null;
            try {
                switch (p[0]) {
                    case "pass" -> {
                        FrontEnd.Unit u = unit(r, p[1]);
                        if (u == null)
                            why = "no unit " + p[1];
                        else if (!u.passed())
                            why = "failed: " + u.failMessage;
                    }
                    case "fail" -> {
                        expectedFails.add(p[1]);
                        FrontEnd.Unit u = unit(r, p[1]);
                        if (u == null)
                            why = "no unit " + p[1];
                        else if (u.passed())
                            why = "passed";
                        else if (p.length > 2 && !u.failMessage.contains(p[2]))
                            why = "message was: " + u.failMessage;
                    }
                    case "packerror" -> {
                        packError = true;
                        String sub = l.substring("packerror".length()).strip();
                        if (r.packErrors.stream().noneMatch(e -> e.contains(sub)))
                            why = "pack errors were " + r.packErrors;
                    }
                    case "json", "jsonhas" -> {
                        String[] q = l.split("\\s+", 4);
                        JsonElement root = JsonParser.parseString(Files.readString(dump.resolve(q[1]), StandardCharsets.UTF_8));
                        JsonElement at = walk(root, q[2]);
                        if (p[0].equals("json")) {
                            String want = q[3].startsWith("=") ? q[3].substring(1).strip() : q[3];
                            JsonElement w;
                            try {
                                w = JsonParser.parseString(want);
                            } catch (RuntimeException e) {
                                w = new com.google.gson.JsonPrimitive(want);
                            }
                            if (!same(at, w))
                                why = "was " + at;
                        } else if (at == null || !at.toString().contains(q[3])) {
                            why = "was " + at;
                        }
                    }
                    case "glsl" -> {
                        String text = Files.readString(dump.resolve("glsl").resolve(p[1]), StandardCharsets.UTF_8);
                        if (!text.contains(p[2]))
                            why = "not found";
                    }
                    default -> why = "unknown expectation";
                }
            } catch (Exception e) {
                why = e.toString();
            }
            if (why != null) {
                wrong++;
                System.out.println("EXPECT-WRONG " + l + "  -> " + why);
            }
        }
        return new int[]{checked, wrong};
    }

    private static boolean same(JsonElement a, JsonElement b) {
        if (a == null)
            return b.isJsonNull();
        if (a.equals(b))
            return true;
        if (a.isJsonPrimitive() && b.isJsonPrimitive())
            return a.getAsString().equals(b.getAsString());
        return false;
    }

    private static FrontEnd.Unit unit(FrontEnd.PackResult r, String id) {
        for (FrontEnd.Unit u : r.units)
            if (u.id.equals(id))
                return u;
        return null;
    }

    /** dotted path; "a.b[2].c"; keys containing dots are written with '|' for '.' */
    private static JsonElement walk(JsonElement e, String path) {
        for (String part : path.split("\\.")) {
            part = part.replace('|', '.');
            String key = part;
            Integer idx = null;
            int br = part.indexOf('[');
            if (br >= 0) {
                key = part.substring(0, br);
                idx = Integer.parseInt(part.substring(br + 1, part.indexOf(']')));
            }
            if (!key.isEmpty()) {
                if (!(e instanceof JsonObject o) || !o.has(key))
                    return null;
                e = o.get(key);
            }
            if (idx != null) {
                if (!(e instanceof JsonArray a) || idx >= a.size())
                    return null;
                e = a.get(idx);
            }
        }
        return e;
    }
}
