package dev.elemore.iroh.packtest;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.elemore.iroh.shaderpack.CustomUniforms;
import dev.elemore.iroh.shaderpack.Json;
import dev.elemore.iroh.shaderpack.PackProperties;
import dev.elemore.iroh.shaderpack.UniformCatalog;
import dev.elemore.iroh.shaderpack.graph.CustomEval;
import dev.elemore.iroh.shaderpack.graph.CustomEval.Value;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom uniform evaluator against hand-computed values (P3-2 item 3). {@code ./gradlew customEvalTest}; last line
 * {@code # customeval PASS n/n}. Trees come from the real P3-1 parser through its JSON form, exactly as the plan
 * reads them. With a Navidson dump present (build/packHarness/Navidson) every Navidson custom uniform is also
 * evaluated over 120 frames of a synthetic world without an exception or a NaN.
 */
public final class CustomEvalTest {

    static int pass, total;

    static void check(String what, double got, double want) {
        total++;
        if (Math.abs(got - want) <= 1e-6 * Math.max(1, Math.abs(want))) {
            pass++;
        } else {
            System.out.println("FAIL " + what + ": got " + got + ", want " + want);
        }
    }

    static void check(String what, boolean ok) {
        total++;
        if (ok)
            pass++;
        else
            System.out.println("FAIL " + what);
    }

    static CustomEval compile(String... lines) {
        List<PackProperties.CustomUniformDecl> decls = new ArrayList<>();
        int n = 0;
        for (String l : lines) {
            String[] kv = l.split("=", 2);
            String[] k = kv[0].trim().split("\\.", 3);
            decls.add(new PackProperties.CustomUniformDecl(k[0], k[1], k[2], kv[1].trim(), "test", ++n));
        }
        CustomUniforms cu = CustomUniforms.check(decls);
        JsonArray arr = JsonParser.parseString(Json.write(cu.toJson())).getAsJsonArray();
        return new CustomEval(arr);
    }

    public static void main(String[] args) throws Exception {
        Map<String, Value> world = new HashMap<>();
        world.put("rainStrength", Value.of(1));
        world.put("cameraPosition", Value.vec(5, 64, 0));
        world.put("previousCameraPosition", Value.vec(4.995, 64, 0));
        world.put("biome_precipitation", Value.of(2));
        world.put("viewWidth", Value.of(1920));
        world.put("viewHeight", Value.of(1080));
        world.put("frameCounter", Value.of(21));
        double[] mv = new double[16];
        for (int i = 0; i < 16; i++)
            mv[i] = i * 0.5;
        world.put("gbufferModelView", new Value(mv, false));
        CustomEval.Env env = world::get;

        CustomEval e = compile(
                "uniform.float.rainFactor = smooth(1, rainStrength, 3, 3)",
                "variable.float.dx = cameraPosition.x - previousCameraPosition.x",
                "uniform.float.moving = if(abs(dx) > 0.01, 1.0, 0.0)",
                "uniform.bool.snowy = in(biome_precipitation, PPT_SNOW)",
                "uniform.vec2.screen = vec2(viewWidth, viewHeight)",
                "uniform.float.m01 = gbufferModelView.0.1",
                "uniform.vec4.col2 = vec4(gbufferModelView.2.0, gbufferModelView.2.1, gbufferModelView.2.2, gbufferModelView.2.3)",
                "uniform.int.framemod8 = frameCounter % 8",
                "uniform.float.l = lerp(0.25, 2, 6)",
                "uniform.float.fm = fmod(-1, 3)",
                "uniform.float.fr = frac(2.75)",
                "uniform.float.cl = clamp(5, 0, 1)",
                "uniform.bool.bt = between(2, 1, 3)",
                "uniform.bool.eq = equals(1.0, 1.05, 0.1)",
                "uniform.float.mx = max(1, 7, 3)",
                "uniform.float.chain = if(false, 1, true, 2, 3)",
                "uniform.float.sg = signum(-4)",
                "uniform.float.at = atan2(1, 1)",
                "uniform.float.prec = 2 + 3 * 4 - 6 / 2",
                "uniform.float.plain = smooth(rainStrength, 2)",
                "uniform.float.bad = rainStrength && true",
                "uniform.float.child = bad + 1",
                "uniform.float.unknownName = notAUniform * 2");
        check("dropped bad", e.dropped.containsKey("bad"));
        check("dropped child of bad", e.dropped.containsKey("child") && e.dropped.get("child").contains("bad"));
        check("dropped unknown name", e.dropped.containsKey("unknownName"));
        check("dx before moving", e.order().indexOf("dx") < e.order().indexOf("moving"));

        Map<String, Value> f1 = e.evaluate(env, 0.05);
        check("smooth first value set outright", f1.get("rainFactor").s(), 1.0);
        check("dx", f1.get("dx").s(), 0.005);
        check("if(abs(dx) > 0.01)", f1.get("moving").s(), 0.0);
        check("in(precipitation, PPT_SNOW)", f1.get("snowy").bool() && f1.get("snowy").s() == 1);
        check("vec2 x", f1.get("screen").v()[0], 1920);
        check("vec2 y", f1.get("screen").v()[1], 1080);
        check("matrix .0.1 (column-major)", f1.get("m01").s(), 0.5);
        check("matrix column 2 w", f1.get("col2").v()[3], 5.5);
        check("int uniform 21 % 8", f1.get("framemod8").s(), 5);
        check("lerp(0.25, 2, 6)", f1.get("l").s(), 3);
        check("fmod(-1, 3) GLSL-style", f1.get("fm").s(), 2);
        check("frac(2.75)", f1.get("fr").s(), 0.75);
        check("clamp(5, 0, 1)", f1.get("cl").s(), 1);
        check("between inclusive", f1.get("bt").s() == 1);
        check("equals within epsilon", f1.get("eq").s() == 1);
        check("max of three", f1.get("mx").s(), 7);
        check("if chain", f1.get("chain").s(), 2);
        check("signum", f1.get("sg").s(), -1);
        check("atan2(1,1)", f1.get("at").s(), Math.PI / 4);
        check("precedence 2+3*4-6/2", f1.get("prec").s(), 11);

        world.put("rainStrength", Value.of(0));
        world.put("previousCameraPosition", Value.vec(4.9, 64, 0));
        Map<String, Value> f2 = e.evaluate(env, 1.5);
        check("smooth 1.5 s at half-life 3 s: 2^-0.5", f2.get("rainFactor").s(), Math.pow(2, -0.5));
        check("moving after a 0.1 block step", f2.get("moving").s(), 1.0);
        check("smooth(val, fade) form: 2^-(1.5/2)", f2.get("plain").s(), Math.pow(2, -0.75));
        Map<String, Value> f3 = e.evaluate(env, 0.0);
        check("dt 0 holds the smoothed value", f3.get("rainFactor").s(), Math.pow(2, -0.5));
        check("smoothFactor(fade 0) = 1", CustomEval.smoothFactor(0.016, 0), 1.0);

        // Navidson's real declarations, if a dump is present
        Path nav = Paths.get("build/packHarness/Navidson/pack.json");
        if (Files.isRegularFile(nav)) {
            JsonArray cu = JsonParser.parseString(Files.readString(nav)).getAsJsonObject().getAsJsonArray("customUniforms");
            CustomEval ne = new CustomEval(cu);
            Map<String, Value> w = new HashMap<>();
            CustomEval.Env nenv = name -> {
                if (w.containsKey(name))
                    return w.get(name);
                if (name.startsWith("BIOME_") || name.startsWith("CAT_"))
                    return Value.of(Math.abs(name.hashCode()) % 64);
                UniformCatalog.Std s = UniformCatalog.STANDARD.get(name);
                String type = s != null ? s.type() : "float";
                int n = switch (type) {
                    case "vec2", "ivec2" -> 2;
                    case "vec3", "ivec3" -> 3;
                    case "vec4", "ivec4" -> 4;
                    case "mat4" -> 16;
                    default -> 1;
                };
                return new Value(new double[n], type.equals("bool"));
            };
            boolean ok = true;
            for (int frame = 0; frame < 120 && ok; frame++) {
                w.put("frameCounter", Value.of(frame));
                w.put("rainStrength", Value.of(frame < 60 ? 1 : 0));
                w.put("eyeBrightness", Value.vec(frame, 240 - frame));
                w.put("cameraPosition", Value.vec(frame * 0.1, 70, 0));
                w.put("previousCameraPosition", Value.vec(Math.max(0, frame - 1) * 0.1, 70, 0));
                try {
                    for (Map.Entry<String, Value> v : ne.evaluate(nenv, 1 / 60.0).entrySet())
                        for (double x : v.getValue().v())
                            if (Double.isNaN(x)) {
                                System.out.println("FAIL Navidson " + v.getKey() + " is NaN at frame " + frame);
                                ok = false;
                            }
                } catch (RuntimeException ex) {
                    System.out.println("FAIL Navidson frame " + frame + ": " + ex);
                    ok = false;
                }
            }
            check("Navidson: " + ne.order().size() + " custom uniforms over 120 frames, dropped " + ne.dropped, ok && ne.dropped.isEmpty());
            Map<String, Value> last = ne.evaluate(nenv, 1 / 60.0);
            check("Navidson framemod8 at frame 119 = 7", last.get("framemod8").s(), 119 % 8);
        } else {
            System.out.println("# (no build/packHarness/Navidson dump: run packHarness on Navidson for the integration case)");
        }
        System.out.println("# customeval " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
