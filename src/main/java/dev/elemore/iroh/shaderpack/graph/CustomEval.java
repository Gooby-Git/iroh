package dev.elemore.iroh.shaderpack.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates a pack's custom uniforms ({@code uniform.*} / {@code variable.*}) from the parse trees P3-1 dumped
 * (pack.json "customUniforms"), once per frame, in dependency order (P3-2 item 3; the binding into the block is P3-3).
 * <p>
 * Semantics (OptiFine shaders.properties doc; details that doc leaves open are marked):
 * <ul>
 *   <li>Arithmetic in double; vectors component-wise with scalar broadcast; booleans are 1/0; an {@code int}
 *       uniform takes the truncated value (UNVERIFIED rounding rule).</li>
 *   <li>{@code smooth([id], val, [fadeIn, [fadeOut]])}: exponential approach, fade time = half-life in seconds
 *       (factor 1 - 2^(-dt/fade); UNVERIFIED curve, one function to correct: {@link #smoothFactor}); first value set
 *       outright; fade 0 = no smoothing; dt 0 holds. State per id, else per call site. Argument forms: 4 = id, val,
 *       in, out; 3 = id, val, in when the first is an integer literal, else val, in, out; 2 = id, val when the first
 *       is an integer literal, else val, in.</li>
 *   <li>{@code lerp(k, x, y)} = x + (y - x) k; {@code fmod(x, y)} = x - y floor(x / y) (GLSL mod; UNVERIFIED vs
 *       Java's %); {@code frac(x)} = x - floor(x); {@code signum}; {@code random()} from the env; {@code if} / {@code ifb}
 *       chains; {@code between(x, min, max)} inclusive; {@code equals(x, y, eps)}; {@code in(x, v...)}.</li>
 *   <li>Constants: pi, true, false, PPT_NONE/RAIN/SNOW = 0/1/2; BIOME_* and CAT_* come from the env (the biome
 *       registry at run time).</li>
 *   <li>A declaration with errors, or reading a name nothing answers, is dropped and named, and so is everything
 *       depending on it (never evaluated as zero: Vitrail rule).</li>
 * </ul>
 */
public final class CustomEval {

    /** A value: 1..4 components (16 for mat4), boolean flag. */
    public record Value(double[] v, boolean bool) {
        public static Value of(double d) {
            return new Value(new double[]{d}, false);
        }

        public static Value of(boolean b) {
            return new Value(new double[]{b ? 1 : 0}, true);
        }

        public static Value vec(double... d) {
            return new Value(d, false);
        }

        public double s() {
            return v[0];
        }

        @Override
        public String toString() {
            if (bool)
                return String.valueOf(v[0] != 0);
            if (v.length == 1)
                return String.valueOf(v[0]);
            return java.util.Arrays.toString(v);
        }
    }

    public interface Env {
        /** a standard uniform, parameter or constant (BIOME_*, CAT_*), or null */
        Value get(String name);

        default double random() {
            return 0.5;
        }
    }

    private record Decl(String kind, String type, String name, JsonObject tree, Set<String> deps) {}

    private final List<Decl> order = new ArrayList<>();
    public final Map<String, String> dropped = new LinkedHashMap<>();
    private final Map<String, double[]> smoothState = new HashMap<>();

    public CustomEval(JsonArray customUniforms) {
        Map<String, Decl> all = new LinkedHashMap<>();
        for (JsonElement e : customUniforms) {
            JsonObject o = e.getAsJsonObject();
            String name = o.get("name").getAsString();
            if (o.getAsJsonArray("errors").size() > 0 || o.get("tree").isJsonNull()) {
                dropped.put(name, "errors: " + o.getAsJsonArray("errors"));
                continue;
            }
            Set<String> deps = new LinkedHashSet<>();
            for (JsonElement dpe : o.getAsJsonArray("deps"))
                deps.add(dpe.getAsString());
            all.put(name, new Decl(o.get("kind").getAsString(), o.get("type").getAsString(), name, obj(o.get("tree")), deps));
        }
        // topological order; anything depending on a dropped declaration is dropped too
        Set<String> done = new LinkedHashSet<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Decl dcl : all.values()) {
                if (done.contains(dcl.name()) || dropped.containsKey(dcl.name()))
                    continue;
                String bad = null;
                boolean ready = true;
                for (String dep : dcl.deps()) {
                    if (dropped.containsKey(dep))
                        bad = dep;
                    else if (!done.contains(dep))
                        ready = false;
                }
                if (bad != null) {
                    dropped.put(dcl.name(), "depends on dropped " + bad);
                    progress = true;
                } else if (ready) {
                    order.add(dcl);
                    done.add(dcl.name());
                    progress = true;
                }
            }
        }
        for (Decl dcl : all.values())
            if (!done.contains(dcl.name()) && !dropped.containsKey(dcl.name()))
                dropped.put(dcl.name(), "dependency cycle");
    }

    public List<String> order() {
        List<String> l = new ArrayList<>();
        for (Decl d : order)
            l.add(d.name());
        return l;
    }

    /** One frame: evaluates every live declaration in order. dt = this frame's duration in seconds. */
    public Map<String, Value> evaluate(Env env, double dt) {
        Map<String, Value> out = new LinkedHashMap<>();
        for (Decl d : order) {
            Value v = eval(d.tree(), env, out, dt, d.name());
            if (d.type().equals("int") && !v.bool())
                v = Value.of((double) (long) v.s());
            if (d.type().equals("float") && v.bool())
                v = Value.of(v.s());
            out.put(d.name(), v);
        }
        return out;
    }

    /** smoothing factor for a frame of dt seconds and a fade (half-life) of fade seconds */
    public static double smoothFactor(double dt, double fade) {
        if (fade <= 0)
            return 1;
        return 1 - Math.pow(2, -dt / fade);
    }

    private Value eval(JsonObject n, Env env, Map<String, Value> done, double dt, String site) {
        if (n.has("ref"))
            return ref(n, env, done);
        if (n.has("op") && n.has("x")) {
            Value x = eval(obj(n.get("x")), env, done, dt, site);
            return switch (n.get("op").getAsString()) {
                case "!" -> Value.of(x.s() == 0);
                case "-" -> map(x, a -> -a);
                default -> x;
            };
        }
        if (n.has("op")) {
            String op = n.get("op").getAsString();
            if (op.equals("&&") || op.equals("||")) {
                boolean a = eval(obj(n.get("a")), env, done, dt, site).s() != 0;
                if (op.equals("&&") && !a)
                    return Value.of(false);
                if (op.equals("||") && a)
                    return Value.of(true);
                return Value.of(eval(obj(n.get("b")), env, done, dt, site).s() != 0);
            }
            Value a = eval(obj(n.get("a")), env, done, dt, site);
            Value b = eval(obj(n.get("b")), env, done, dt, site);
            return switch (op) {
                case "+" -> zip(a, b, Double::sum);
                case "-" -> zip(a, b, (x, y) -> x - y);
                case "*" -> zip(a, b, (x, y) -> x * y);
                case "/" -> zip(a, b, (x, y) -> x / y);
                case "%" -> zip(a, b, (x, y) -> x % y);
                case "<" -> Value.of(a.s() < b.s());
                case ">" -> Value.of(a.s() > b.s());
                case "<=" -> Value.of(a.s() <= b.s());
                case ">=" -> Value.of(a.s() >= b.s());
                case "==" -> Value.of(a.s() == b.s());
                case "!=" -> Value.of(a.s() != b.s());
                default -> throw new IllegalStateException("operator " + op);
            };
        }
        if (n.has("call")) {
            String fn = n.get("call").getAsString();
            JsonArray argsJ = n.getAsJsonArray("args");
            if (fn.equals("if") || fn.equals("ifb")) {
                int i = 0;
                for (; i + 1 < argsJ.size(); i += 2)
                    if (eval(obj(argsJ.get(i)), env, done, dt, site).s() != 0)
                        return finish(fn, eval(obj(argsJ.get(i + 1)), env, done, dt, site));
                return finish(fn, eval(obj(argsJ.get(argsJ.size() - 1)), env, done, dt, site));
            }
            if (fn.equals("smooth"))
                return smooth(argsJ, env, done, dt, site);
            List<Value> a = new ArrayList<>();
            for (JsonElement x : argsJ)
                a.add(eval(obj(x), env, done, dt, site));
            return call(fn, a, env);
        }
        // number literal (Num.json() is a bare primitive: wrapped by the caller as {"num": x})
        if (n.has("num"))
            return Value.of(n.get("num").getAsDouble());
        throw new IllegalStateException("cannot evaluate " + n);
    }

    private static Value finish(String fn, Value v) {
        return fn.equals("ifb") ? Value.of(v.s() != 0) : v;
    }

    private Value smooth(JsonArray args, Env env, Map<String, Value> done, double dt, String site) {
        int n = args.size();
        boolean firstIsId = n == 4 || (n >= 2 && isIntLiteral(args.get(0)));
        int i = 0;
        String key;
        if (firstIsId) {
            key = "id:" + literal(args.get(0));
            i = 1;
        } else {
            key = site + ":" + args.toString();
        }
        double[] val = eval(obj(args.get(i)), env, done, dt, site).v();
        double fadeIn = i + 1 < n ? eval(obj(args.get(i + 1)), env, done, dt, site).s() : 1.0;
        double fadeOut = i + 2 < n ? eval(obj(args.get(i + 2)), env, done, dt, site).s() : fadeIn;
        double[] st = smoothState.get(key);
        if (st == null) {
            smoothState.put(key, val.clone());
            return new Value(val.clone(), false);
        }
        if (dt <= 0)
            return new Value(st.clone(), false);
        for (int k = 0; k < st.length && k < val.length; k++) {
            double fade = val[k] > st[k] ? fadeIn : fadeOut;
            st[k] += (val[k] - st[k]) * smoothFactor(dt, fade);
        }
        return new Value(st.clone(), false);
    }

    private static JsonObject obj(JsonElement e) {
        if (e.isJsonObject())
            return e.getAsJsonObject();
        JsonObject o = new JsonObject();
        o.add("num", e);
        return o;
    }

    private static boolean isIntLiteral(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && !e.getAsString().contains(".");
    }

    private static String literal(JsonElement e) {
        return e.getAsString();
    }

    private Value ref(JsonObject n, Env env, Map<String, Value> done) {
        String name = n.get("ref").getAsString();
        Value v = switch (name) {
            case "pi" -> Value.of(Math.PI);
            case "true" -> Value.of(true);
            case "false" -> Value.of(false);
            case "PPT_NONE" -> Value.of(0);
            case "PPT_RAIN" -> Value.of(1);
            case "PPT_SNOW" -> Value.of(2);
            default -> done.containsKey(name) ? done.get(name) : env.get(name);
        };
        if (v == null)
            throw new IllegalStateException("name " + name + " has no value");
        if (n.has("access"))
            for (JsonElement a : n.getAsJsonArray("access"))
                v = component(v, a.getAsString());
        return v;
    }

    private static Value component(Value v, String a) {
        int idx = switch (a) {
            case "x", "r", "s" -> 0;
            case "y", "g", "t" -> 1;
            case "z", "b", "p" -> 2;
            case "w", "a", "q" -> 3;
            default -> Integer.parseInt(a);
        };
        if (v.v().length == 16 || v.v().length == 9) { // matrix column
            int dim = v.v().length == 16 ? 4 : 3;
            double[] col = new double[dim];
            System.arraycopy(v.v(), idx * dim, col, 0, dim);
            return new Value(col, false);
        }
        return Value.of(v.v()[idx]);
    }

    private static Value map(Value x, java.util.function.DoubleUnaryOperator f) {
        double[] r = new double[x.v().length];
        for (int i = 0; i < r.length; i++)
            r[i] = f.applyAsDouble(x.v()[i]);
        return new Value(r, false);
    }

    private static Value zip(Value a, Value b, java.util.function.DoubleBinaryOperator f) {
        int n = Math.max(a.v().length, b.v().length);
        double[] r = new double[n];
        for (int i = 0; i < n; i++)
            r[i] = f.applyAsDouble(a.v()[a.v().length == 1 ? 0 : i], b.v()[b.v().length == 1 ? 0 : i]);
        return new Value(r, false);
    }

    private static Value call(String fn, List<Value> a, Env env) {
        return switch (fn) {
            case "sin" -> map(a.get(0), Math::sin);
            case "cos" -> map(a.get(0), Math::cos);
            case "asin" -> map(a.get(0), Math::asin);
            case "acos" -> map(a.get(0), Math::acos);
            case "tan" -> map(a.get(0), Math::tan);
            case "atan" -> map(a.get(0), Math::atan);
            case "atan2" -> zip(a.get(0), a.get(1), Math::atan2);
            case "torad" -> map(a.get(0), Math::toRadians);
            case "todeg" -> map(a.get(0), Math::toDegrees);
            case "min" -> fold(a, Math::min);
            case "max" -> fold(a, Math::max);
            case "clamp" -> zip(zip(a.get(0), a.get(1), Math::max), a.get(2), Math::min);
            case "abs" -> map(a.get(0), Math::abs);
            case "floor" -> map(a.get(0), Math::floor);
            case "ceil" -> map(a.get(0), Math::ceil);
            case "exp" -> map(a.get(0), Math::exp);
            case "exp2" -> map(a.get(0), x -> Math.pow(2, x));
            case "frac", "fract" -> map(a.get(0), x -> x - Math.floor(x));
            case "log" -> map(a.get(0), Math::log);
            case "log2" -> map(a.get(0), x -> Math.log(x) / Math.log(2));
            case "pow" -> zip(a.get(0), a.get(1), Math::pow);
            case "random" -> Value.of(env.random());
            case "round" -> map(a.get(0), x -> (double) Math.round(x));
            case "signum", "sign" -> map(a.get(0), Math::signum);
            case "sqrt" -> map(a.get(0), Math::sqrt);
            case "cbrt" -> map(a.get(0), Math::cbrt);
            case "hypot" -> zip(a.get(0), a.get(1), Math::hypot);
            case "fmod" -> zip(a.get(0), a.get(1), (x, y) -> x - y * Math.floor(x / y));
            case "lerp", "mix" -> fn.equals("lerp")
                    ? zip(a.get(1), zip(zip(a.get(2), a.get(1), (x, y) -> x - y), a.get(0), (x, y) -> x * y), Double::sum)
                    : zip(a.get(0), zip(zip(a.get(1), a.get(0), (x, y) -> x - y), a.get(2), (x, y) -> x * y), Double::sum);
            case "step" -> zip(a.get(0), a.get(1), (e, x) -> x < e ? 0 : 1);
            case "smoothstep" -> {
                double e0 = a.get(0).s(), e1 = a.get(1).s();
                yield map(a.get(2), x -> {
                    double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
                    return t * t * (3 - 2 * t);
                });
            }
            case "between" -> Value.of(a.get(0).s() >= a.get(1).s() && a.get(0).s() <= a.get(2).s());
            case "equals" -> Value.of(Math.abs(a.get(0).s() - a.get(1).s()) <= a.get(2).s());
            case "in" -> {
                boolean r = false;
                for (int i = 1; i < a.size(); i++)
                    r |= a.get(0).s() == a.get(i).s();
                yield Value.of(r);
            }
            case "print", "printb" -> a.get(a.size() - 1);
            case "vec2", "vec3", "vec4" -> {
                int dim = fn.charAt(3) - '0';
                double[] r = new double[dim];
                int k = 0;
                for (Value v : a)
                    for (double x : v.v())
                        if (k < dim)
                            r[k++] = x;
                if (k == 1)
                    java.util.Arrays.fill(r, r[0]);
                yield new Value(r, false);
            }
            case "length" -> {
                double s = 0;
                for (double x : a.get(0).v())
                    s += x * x;
                yield Value.of(Math.sqrt(s));
            }
            case "distance" -> call("length", List.of(zip(a.get(0), a.get(1), (x, y) -> x - y)), env);
            case "dot" -> {
                double s = 0;
                for (int i = 0; i < a.get(0).v().length; i++)
                    s += a.get(0).v()[i] * a.get(1).v()[i];
                yield Value.of(s);
            }
            case "normalize" -> {
                double l = call("length", a, env).s();
                yield map(a.get(0), x -> x / l);
            }
            case "cross" -> {
                double[] x = a.get(0).v(), y = a.get(1).v();
                yield Value.vec(x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0]);
            }
            default -> throw new IllegalStateException("function " + fn);
        };
    }

    private static Value fold(List<Value> a, java.util.function.DoubleBinaryOperator f) {
        Value r = a.get(0);
        for (int i = 1; i < a.size(); i++)
            r = zip(r, a.get(i), f);
        return r;
    }
}
