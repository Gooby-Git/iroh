package dev.elemore.iroh.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage (ii), custom uniforms: {@code uniform.<type>.<name> = <expr>} and {@code variable.<type>.<name> = <expr>}
 * parsed to an expression tree and type-checked (OptiFine shaders.properties expression language: numbers, pi,
 * true/false, BIOME_* / CAT_* / PPT_* constants, uniforms and earlier customs, component access {@code .x .r .0},
 * matrix {@code m.<col>.<row>}, operators {@code + - * / % ! && || < > <= >= == !=}, and the documented functions).
 * Evaluation is P3-3. Written from the public doc; no Iris or Vitrail parser code.
 */
public final class CustomUniforms implements Json.Dumpable {

    public sealed interface Node permits Num, Ref, Call, Un, Bin {
        Object json();
    }

    public record Num(double v, boolean isInt) implements Node {
        public Object json() {
            return isInt ? (Object) (long) v : (Object) v;
        }
    }

    public record Ref(String name, List<String> access) implements Node {
        public Object json() {
            return access.isEmpty() ? Json.obj("ref", name) : Json.obj("ref", name, "access", access);
        }
    }

    public record Call(String fn, List<Node> args) implements Node {
        public Object json() {
            List<Object> a = new ArrayList<>();
            for (Node n : args)
                a.add(n.json());
            return Json.obj("call", fn, "args", a);
        }
    }

    public record Un(String op, Node x) implements Node {
        public Object json() {
            return Json.obj("op", op, "x", x.json());
        }
    }

    public record Bin(String op, Node a, Node b) implements Node {
        public Object json() {
            return Json.obj("op", op, "a", a.json(), "b", b.json());
        }
    }

    public static final class Entry {
        public final PackProperties.CustomUniformDecl decl;
        public Node tree;
        public String inferred;
        public final Set<String> deps = new LinkedHashSet<>();
        public final List<String> errors = new ArrayList<>();

        Entry(PackProperties.CustomUniformDecl decl) {
            this.decl = decl;
        }
    }

    public final List<Entry> entries = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();

    static final Set<String> TYPES = Set.of("float", "int", "bool", "vec2", "vec3", "vec4");
    static final Set<String> PARAMETERS_INT = Set.of("biome", "biome_category", "biome_precipitation", "heldItemId",
            "heldItemId2", "worldTime", "moonPhase", "worldDay");
    static final Set<String> PARAMETERS_FLOAT = Set.of("temperature", "rainfall");
    static final Set<String> PARAMETERS_BOOL = Set.of("is_alive", "is_burning", "is_child", "is_glowing", "is_hurt",
            "is_in_lava", "is_in_water", "is_invisible", "is_on_ground", "is_ridden", "is_riding", "is_sneaking",
            "is_sprinting", "is_wet");

    public static CustomUniforms check(List<PackProperties.CustomUniformDecl> decls) {
        CustomUniforms cu = new CustomUniforms();
        Map<String, String> known = new LinkedHashMap<>();
        for (PackProperties.CustomUniformDecl d : decls)
            known.put(d.name(), d.type()); // variables may be referenced before their line (graph order)
        for (PackProperties.CustomUniformDecl d : decls) {
            Entry e = new Entry(d);
            cu.entries.add(e);
            if (!TYPES.contains(d.type()))
                e.errors.add("unknown type " + d.type());
            if (UniformCatalog.STANDARD.containsKey(d.name()) && d.kind().equals("uniform"))
                e.errors.add("custom uniform shadows the standard uniform " + d.name());
            try {
                Parser p = new Parser(d.expr());
                e.tree = p.expr();
                if (p.pos < p.toks.size())
                    throw new IllegalArgumentException("unexpected '" + p.toks.get(p.pos) + "'");
                e.inferred = type(e.tree, known, e);
                if (e.inferred != null && !assignable(e.inferred, d.type()))
                    e.errors.add("expression is " + e.inferred + ", declared " + d.type());
            } catch (IllegalArgumentException ex) {
                e.errors.add(ex.getMessage());
            }
        }
        // cycles
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (Entry e : cu.entries)
            byName.put(e.decl.name(), e);
        for (Entry e : cu.entries) {
            if (cycle(e.decl.name(), e.decl.name(), byName, new LinkedHashSet<>()))
                e.errors.add("dependency cycle through " + e.decl.name());
        }
        return cu;
    }

    private static boolean cycle(String target, String at, Map<String, Entry> byName, Set<String> seen) {
        Entry e = byName.get(at);
        if (e == null || !seen.add(at))
            return false;
        for (String d : e.deps) {
            if (d.equals(target))
                return true;
            if (cycle(target, d, byName, seen))
                return true;
        }
        return false;
    }

    static boolean assignable(String from, String to) {
        if (from.equals(to))
            return true;
        return (from.equals("int") || from.equals("float") || from.equals("bool")) && (to.equals("int") || to.equals("float"))
                && !(from.equals("bool") && to.equals("float") && false);
    }

    // ------------------------------------------------------------------------------------------- typing

    private static String type(Node n, Map<String, String> customs, Entry e) {
        if (n instanceof Num num)
            return num.isInt() ? "int" : "float";
        if (n instanceof Ref ref) {
            String base = refType(ref.name(), customs, e);
            for (String a : ref.access()) {
                if (base.startsWith("mat")) {
                    base = "vec" + base.charAt(3);
                } else if (base.matches("[iu]?vec[234]|[iu]vec[234]")) {
                    base = base.startsWith("i") ? "int" : "float";
                } else {
                    throw new IllegalArgumentException("component access ." + a + " on " + base + " " + ref.name());
                }
            }
            return base;
        }
        if (n instanceof Un u) {
            String t = type(u.x(), customs, e);
            if (u.op().equals("!")) {
                if (!t.equals("bool"))
                    throw new IllegalArgumentException("'!' on " + t);
                return "bool";
            }
            return t;
        }
        if (n instanceof Bin b) {
            String x = type(b.a(), customs, e);
            String y = type(b.b(), customs, e);
            switch (b.op()) {
                case "&&", "||" -> {
                    if (!x.equals("bool") || !y.equals("bool"))
                        throw new IllegalArgumentException("'" + b.op() + "' on " + x + " and " + y);
                    return "bool";
                }
                case "<", ">", "<=", ">=", "==", "!=" -> {
                    return "bool";
                }
                default -> {
                    return arith(x, y, b.op());
                }
            }
        }
        Call c = (Call) n;
        List<String> a = new ArrayList<>();
        for (Node x : c.args())
            a.add(type(x, customs, e));
        return callType(c.fn(), a);
    }

    private static String refType(String name, Map<String, String> customs, Entry e) {
        if (name.equals("pi"))
            return "float";
        if (name.equals("true") || name.equals("false"))
            return "bool";
        if (name.startsWith("BIOME_") || name.startsWith("CAT_") || name.startsWith("PPT_"))
            return "int";
        if (customs.containsKey(name)) {
            e.deps.add(name);
            return customs.get(name);
        }
        if (PARAMETERS_INT.contains(name))
            return "int";
        if (PARAMETERS_FLOAT.contains(name))
            return "float";
        if (PARAMETERS_BOOL.contains(name))
            return "bool";
        UniformCatalog.Std s = UniformCatalog.STANDARD.get(name);
        if (s != null)
            return s.type();
        throw new IllegalArgumentException("unknown name " + name);
    }

    private static String scalarOf(String t) {
        return t.startsWith("i") ? "int" : t.startsWith("vec") ? "float" : t;
    }

    private static String arith(String x, String y, String op) {
        if (x.equals("bool") || y.equals("bool"))
            throw new IllegalArgumentException("'" + op + "' on " + x + " and " + y);
        boolean vx = x.contains("vec");
        boolean vy = y.contains("vec");
        if (vx && vy) {
            if (x.charAt(x.length() - 1) != y.charAt(y.length() - 1))
                throw new IllegalArgumentException("'" + op + "' on " + x + " and " + y);
            return "vec" + x.charAt(x.length() - 1);
        }
        if (vx)
            return "vec" + x.charAt(x.length() - 1);
        if (vy)
            return "vec" + y.charAt(y.length() - 1);
        return x.equals("int") && y.equals("int") ? "int" : "float";
    }

    private static String callType(String fn, List<String> a) {
        switch (fn) {
            case "sin", "cos", "asin", "acos", "tan", "atan", "torad", "todeg", "exp", "exp2", "log", "log2", "sqrt",
                 "frac", "fract", "signum", "sign", "floor", "ceil", "round", "abs", "cbrt" -> {
                need(fn, a, 1, 1);
                return fn.equals("abs") || fn.equals("floor") || fn.equals("ceil") || fn.equals("round") || fn.equals("signum") || fn.equals("sign")
                        ? a.get(0) : (a.get(0).contains("vec") ? a.get(0) : "float");
            }
            case "atan2", "pow", "fmod", "hypot", "step" -> {
                need(fn, a, 2, 2);
                return arith(a.get(0), a.get(1), fn).equals("int") ? "float" : arith(a.get(0), a.get(1), fn);
            }
            case "min", "max" -> {
                need(fn, a, 2, 99);
                String t = a.get(0);
                for (String x : a.subList(1, a.size()))
                    t = arith(t, x, fn);
                return t;
            }
            case "clamp", "lerp", "mix", "smoothstep" -> {
                need(fn, a, 3, 3);
                return arith(arith(a.get(0), a.get(1), fn), a.get(2), fn);
            }
            case "random" -> {
                need(fn, a, 0, 0);
                return "float";
            }
            case "if", "ifb" -> {
                if (a.size() < 3 || a.size() % 2 == 0)
                    throw new IllegalArgumentException(fn + "() needs cond, value, ..., else");
                String t = a.get(1);
                for (int i = 0; i + 1 < a.size(); i += 2) {
                    if (!a.get(i).equals("bool"))
                        throw new IllegalArgumentException(fn + "() condition " + (i / 2 + 1) + " is " + a.get(i));
                    t = unify(t, a.get(i + 1), fn);
                }
                t = unify(t, a.get(a.size() - 1), fn);
                return fn.equals("ifb") ? "bool" : t;
            }
            case "between" -> {
                need(fn, a, 3, 3);
                return "bool";
            }
            case "equals" -> {
                need(fn, a, 3, 3);
                return "bool";
            }
            case "in" -> {
                need(fn, a, 2, 999);
                return "bool";
            }
            case "smooth" -> {
                need(fn, a, 1, 4);
                return "float";
            }
            case "print", "printb" -> {
                need(fn, a, 1, 3);
                return a.get(0);
            }
            case "vec2", "vec3", "vec4" -> {
                need(fn, a, 1, 4);
                return fn;
            }
            case "length", "distance", "dot" -> {
                need(fn, a, 1, 2);
                return "float";
            }
            case "normalize" -> {
                need(fn, a, 1, 1);
                return a.get(0);
            }
            case "cross" -> {
                need(fn, a, 2, 2);
                return "vec3";
            }
            default -> throw new IllegalArgumentException("unknown function " + fn + "()");
        }
    }

    private static String unify(String a, String b, String fn) {
        if (a.equals(b))
            return a;
        if ((a.equals("int") || a.equals("float")) && (b.equals("int") || b.equals("float")))
            return "float";
        throw new IllegalArgumentException(fn + "() branches are " + a + " and " + b);
    }

    private static void need(String fn, List<String> a, int min, int max) {
        if (a.size() < min || a.size() > max)
            throw new IllegalArgumentException(fn + "() takes " + min + (max == min ? "" : ".." + max) + " arguments, got " + a.size());
    }

    // ------------------------------------------------------------------------------------------- parse

    private static final class Parser {
        final List<String> toks = new ArrayList<>();
        int pos;

        Parser(String s) {
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (Character.isLetter(c) || c == '_') {
                    int j = i;
                    while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_'))
                        j++;
                    toks.add(s.substring(i, j));
                    i = j;
                } else if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)) && (toks.isEmpty() || !isName(toks.get(toks.size() - 1))))) {
                    int j = i;
                    while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.' || s.charAt(j) == 'e' || s.charAt(j) == 'E'
                            || ((s.charAt(j) == '-' || s.charAt(j) == '+') && j > i && (s.charAt(j - 1) == 'e' || s.charAt(j - 1) == 'E'))))
                        j++;
                    if (j < s.length() && (s.charAt(j) == 'f' || s.charAt(j) == 'F'))
                        j++;
                    toks.add(s.substring(i, j));
                    i = j;
                } else {
                    String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                    if (List.of("&&", "||", "<=", ">=", "==", "!=").contains(two)) {
                        toks.add(two);
                        i += 2;
                    } else {
                        toks.add(String.valueOf(c));
                        i++;
                    }
                }
            }
        }

        static boolean isName(String t) {
            return Character.isLetter(t.charAt(0)) || t.charAt(0) == '_';
        }

        boolean at(String s) {
            return pos < toks.size() && toks.get(pos).equals(s);
        }

        Node expr() {
            return or();
        }

        Node or() {
            Node a = and();
            while (at("||")) {
                pos++;
                a = new Bin("||", a, and());
            }
            return a;
        }

        Node and() {
            Node a = cmp();
            while (at("&&")) {
                pos++;
                a = new Bin("&&", a, cmp());
            }
            return a;
        }

        Node cmp() {
            Node a = add();
            while (at("<") || at(">") || at("<=") || at(">=") || at("==") || at("!=")) {
                String op = toks.get(pos++);
                a = new Bin(op, a, add());
            }
            return a;
        }

        Node add() {
            Node a = mul();
            while (at("+") || at("-")) {
                String op = toks.get(pos++);
                a = new Bin(op, a, mul());
            }
            return a;
        }

        Node mul() {
            Node a = unary();
            while (at("*") || at("/") || at("%")) {
                String op = toks.get(pos++);
                a = new Bin(op, a, unary());
            }
            return a;
        }

        Node unary() {
            if (at("!") || at("-") || at("+")) {
                String op = toks.get(pos++);
                return new Un(op, unary());
            }
            return primary();
        }

        Node primary() {
            if (pos >= toks.size())
                throw new IllegalArgumentException("unexpected end of expression");
            String t = toks.get(pos++);
            if (t.equals("(")) {
                Node n = expr();
                if (!at(")"))
                    throw new IllegalArgumentException("expected ')'");
                pos++;
                return n;
            }
            if (Character.isDigit(t.charAt(0)) || t.charAt(0) == '.') {
                String x = t.replaceAll("[fF]$", "");
                boolean isInt = x.matches("\\d+");
                try {
                    return new Num(Double.parseDouble(x), isInt);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("bad number " + t);
                }
            }
            if (!isName(t))
                throw new IllegalArgumentException("unexpected '" + t + "'");
            if (at("(")) {
                pos++;
                List<Node> args = new ArrayList<>();
                if (!at(")")) {
                    args.add(expr());
                    while (at(",")) {
                        pos++;
                        args.add(expr());
                    }
                }
                if (!at(")"))
                    throw new IllegalArgumentException("expected ')' after arguments of " + t);
                pos++;
                return new Call(t, args);
            }
            List<String> access = new ArrayList<>();
            while (at(".")) {
                pos++;
                if (pos >= toks.size())
                    throw new IllegalArgumentException("'.' at end");
                String a = toks.get(pos++);
                // ".0.1" lexes as a number "0.1" after a name
                for (String part : a.split("\\."))
                    if (!part.isEmpty())
                        access.add(part);
            }
            return new Ref(t, access);
        }
    }

    @Override
    public Object toJson() {
        List<Object> l = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", e.decl.kind());
            m.put("type", e.decl.type());
            m.put("name", e.decl.name());
            m.put("expr", e.decl.expr());
            m.put("line", e.decl.line());
            m.put("inferred", e.inferred);
            m.put("deps", new ArrayList<>(e.deps));
            m.put("tree", e.tree == null ? null : e.tree.json());
            m.put("errors", e.errors);
            l.add(m);
        }
        return l;
    }
}
