package dev.elemore.iroh.shaderpack;

import java.util.Collection;
import java.util.Map;

/**
 * Minimal deterministic JSON writer for the front end's dumps: Map (insertion order kept, use LinkedHashMap or
 * TreeMap), Collection, String, Number, Boolean, null, records with {@link Dumpable}. Pretty-printed with two
 * spaces so two runs diff line by line.
 */
public final class Json {

    public interface Dumpable {
        Object toJson();
    }

    private Json() {}

    /** Ordered map from key/value pairs. Never Map.of for anything serialised: its iteration order is
     *  randomised per JVM (Vitrail trap), and dumps must diff cleanly between runs. */
    public static java.util.LinkedHashMap<String, Object> obj(Object... kv) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
            m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void indent(StringBuilder sb, int n) {
        sb.append('\n');
        for (int i = 0; i < n; i++)
            sb.append("  ");
    }

    private static boolean simple(Object o) {
        return o == null || o instanceof String || o instanceof Number || o instanceof Boolean;
    }

    private static void write(StringBuilder sb, Object o, int depth) {
        if (o instanceof Dumpable d)
            o = d.toJson();
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String s) {
            str(sb, s);
        } else if (o instanceof Double d && (d.isNaN() || d.isInfinite())) {
            str(sb, d.toString());
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first)
                    sb.append(',');
                first = false;
                indent(sb, depth + 1);
                str(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), depth + 1);
            }
            indent(sb, depth);
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            if (c.isEmpty()) {
                sb.append("[]");
                return;
            }
            boolean flat = c.size() <= 16 && c.stream().allMatch(Json::simple);
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first)
                    sb.append(flat ? ", " : ",");
                first = false;
                if (!flat)
                    indent(sb, depth + 1);
                write(sb, x, depth + 1);
            }
            if (!flat)
                indent(sb, depth);
            sb.append(']');
        } else if (o.getClass().isArray() && o instanceof int[] a) {
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0)
                    sb.append(", ");
                sb.append(a[i]);
            }
            sb.append(']');
        } else {
            str(sb, o.toString());
        }
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20)
                        sb.append(String.format("\\u%04x", (int) c));
                    else
                        sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
