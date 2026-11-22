package dev.elemore.iroh.shaderpack.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.elemore.iroh.shaderpack.UniformCatalog;
import dev.elemore.iroh.shaderpack.graph.CustomEval;
import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PassGraph;
import dev.elemore.iroh.shaderpack.graph.ResourcePlan;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A procedural stand-in for a captured frame (P3-3 replay before any real capture exists): a grass plane at y = 64, a
 * stone box, a tall box casting a shadow, a glowing block, a water quad, sky. Ray-cast on the CPU into what the solid
 * gbuffer passes would have left for the deferred stage: lit scene colour in colortex0 (Complementary-style forward
 * lighting: albedo x (sun x N.L x shadow + ambient)), material data in colortex6 (smoothness, mask, sky light), normals
 * in colortex4, depthtex0/1/2 as GL window depth, an UNDISTORTED orthographic shadow map (Navidson distorts its shadow
 * space, so shadows land in the wrong place: this exercises the lookups, not their placement), and the uniform values
 * (camera, sun, time, weather; custom uniforms through {@link CustomEval}). Deterministic: no randomness, no clock.
 */
public final class SyntheticScene implements PackFrameInputs {

    private final int w, h;
    private final Map<String, double[]> uniforms = new TreeMap<>();
    private final Map<String, float[]> images = new LinkedHashMap<>();
    private final Map<String, int[]> extents = new LinkedHashMap<>();

    // camera
    static final double[] CAM = {0.5, 66.62, 8.5};
    static final double YAW = Math.toRadians(20), PITCH = Math.toRadians(-12), FOV = Math.toRadians(70);
    static final double NEAR = 0.05, FAR = 192;
    // sun (direction TO the sun, world space)
    static final double[] SUN = norm(new double[]{-0.45, 0.75, 0.35});

    record Box(double x0, double y0, double z0, double x1, double y1, double z1, float[] albedo, float emission, float smooth) {}

    static final Box[] BOXES = {
            new Box(-3, 64, -4, 0, 67, -1, new float[]{0.45f, 0.45f, 0.47f}, 0, 0.2f),
            new Box(2, 64, -9, 3, 72, -8, new float[]{0.55f, 0.38f, 0.22f}, 0, 0.1f),
            new Box(-1, 64, -7, 0, 65, -6, new float[]{1.0f, 0.75f, 0.35f}, 1, 0.3f)};
    static final float[] GRASS = {0.28f, 0.48f, 0.18f};
    static final float[] WATER = {0.12f, 0.25f, 0.42f};

    public SyntheticScene(PackData data, PassGraph.Frame frame, ResourcePlan plan, int width, int height) {
        this.w = width;
        this.h = height;
        double[] view = viewMatrix();
        double[] proj = perspective(FOV, (double) w / h, NEAR, FAR);
        double[] viewInv = invert(view), projInv = invert(proj);
        // shadow: light looks along -SUN, centred on the camera, ortho half-size 96 (Navidson shadowDistance)
        double half = 96;
        double[] shadowView = lookAlong(SUN);
        double[] shadowProj = ortho(-half, half, -half, half, -256, 256);
        putUniforms(view, proj, viewInv, projInv, shadowView, shadowProj);
        evalCustom(data);

        // which images the solid gbuffer passes would have supplied
        Set<String> wanted = new LinkedHashSet<>();
        for (PassGraph.Pass p : frame.passes)
            if (p.kind.equals("geometry") && p.stage.equals("gbuffers-solid"))
                for (Map<String, Object> wr : p.writes)
                    wanted.add((String) wr.get("target"));
        float[] c0 = new float[w * h * 4], c6 = new float[w * h * 4], c4 = new float[w * h * 4], c3 = new float[w * h * 4];
        float[] depth = new float[w * h * 4];
        int sres = plan.shadowRes;
        float[] shadow = shadowMap(sres, shadowView, shadowProj);
        double[] pv = mul(proj, view);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = 4 * (y * w + x);
                double[] dir = rayDir(x, y, viewInv, projInv);
                Hit hit = trace(new double[]{0, 0, 0}, dir);   // camera-relative (player space)
                if (hit == null) {
                    // what gbuffers_skybasic would have left (the sky is a geometry pass, not replayed): a linear gradient
                    double t = Math.max(0, dir[1]);
                    c0[i] = (float) (0.62 + (0.20 - 0.62) * t);
                    c0[i + 1] = (float) (0.72 + (0.38 - 0.72) * t);
                    c0[i + 2] = (float) (0.86 + (0.80 - 0.86) * t);
                    c0[i + 3] = 1;
                    depth[i] = 1;
                    c6[i + 2] = 1; // sky light
                    continue;
                }
                double[] p = hit.p;
                double[] clip = mulv(pv, new double[]{p[0], p[1], p[2], 1});
                depth[i] = (float) (clip[2] / clip[3] * 0.5 + 0.5);
                double ndl = Math.max(0, dot(hit.n, SUN));
                double lit = ndl * shadowAt(shadow, sres, shadowView, shadowProj, p, hit.n);
                for (int c = 0; c < 3; c++) {
                    double v = hit.albedo[c] * (2.2 * lit + 0.25) + hit.emission * hit.albedo[c] * 4;
                    c0[i + c] = (float) v;
                }
                c0[i + 3] = 1;
                c6[i] = hit.smooth;
                c6[i + 1] = hit.water ? 0.5f : 0f;
                c6[i + 2] = 1;
                double[] vn = mulv(view, new double[]{hit.n[0], hit.n[1], hit.n[2], 0});
                c4[i] = (float) vn[0];
                c4[i + 1] = (float) vn[1];
                c4[i + 2] = (float) vn[2];
                c4[i + 3] = hit.water ? 0.8f : 0f;
                if (hit.water) {
                    c3[i] = 0.2f;
                    c3[i + 1] = 0.35f;
                    c3[i + 2] = 0.5f;
                    c3[i + 3] = 0.7f;
                }
            }
        put("depthtex0", depth, w, h, 1);
        put("depthtex1", depth.clone(), w, h, 1);
        put("depthtex2", depth.clone(), w, h, 1);
        put("shadowtex0", shadow, sres, sres, 1);
        put("shadowtex1", shadow.clone(), sres, sres, 1);
        float[] white = new float[sres * sres * 4];
        java.util.Arrays.fill(white, 1f);
        if (frame.targets.containsKey("shadowcolor0"))
            put("shadowcolor0", white, sres, sres, 1);
        if (frame.targets.containsKey("shadowcolor1"))
            put("shadowcolor1", white.clone(), sres, sres, 1);
        Map<String, float[]> gb = Map.of("colortex0", c0, "colortex6", c6, "colortex4", c4, "colortex3", c3);
        for (String t : wanted) {
            if (gb.containsKey(t)) {
                ResourcePlan.Image im = find(plan, t);
                if (im != null && im.relative && im.fx == 1 && im.fy == 1)
                    put(t, gb.get(t), w, h, 1);
            }
        }
        // only images the plan actually allocates
        images.keySet().removeIf(n -> find(plan, n) == null && !n.startsWith("depthtex") && !n.startsWith("shadowtex"));
        if (find(plan, "depthtex1") == null)
            images.remove("depthtex1");
        if (find(plan, "depthtex2") == null)
            images.remove("depthtex2");
        if (find(plan, "shadowtex0") == null) {
            images.remove("shadowtex0");
            images.remove("shadowtex1");
        } else if (find(plan, "shadowtex1") == null) {
            images.remove("shadowtex1");
        }
        extents.keySet().retainAll(images.keySet());
    }

    static ResourcePlan.Image find(ResourcePlan plan, String name) {
        for (ResourcePlan.Image im : plan.images)
            if (im.name.equals(name) || im.name.startsWith(name + " "))
                return im;
        return null;
    }

    private void put(String n, float[] px, int pw, int ph, int pd) {
        images.put(n, px);
        extents.put(n, new int[]{pw, ph, pd});
    }

    // ------------------------------------------------------------------------------------------------ uniforms

    private void putUniforms(double[] view, double[] proj, double[] viewInv, double[] projInv, double[] sv, double[] sp) {
        u("gbufferModelView", view);
        u("gbufferModelViewInverse", viewInv);
        u("gbufferProjection", proj);
        u("gbufferProjectionInverse", projInv);
        u("gbufferPreviousModelView", view);
        u("gbufferPreviousProjection", proj);
        u("shadowModelView", sv);
        u("shadowModelViewInverse", invert(sv));
        u("shadowProjection", sp);
        u("shadowProjectionInverse", invert(sp));
        u("cameraPosition", CAM);
        u("previousCameraPosition", CAM);
        u("eyePosition", new double[]{CAM[0], CAM[1], CAM[2]});
        u("relativeEyePosition", new double[]{0, 0, 0});
        u("cameraPositionInt", new double[]{Math.floor(CAM[0]), Math.floor(CAM[1]), Math.floor(CAM[2])});
        u("cameraPositionFract", new double[]{CAM[0] - Math.floor(CAM[0]), CAM[1] - Math.floor(CAM[1]), CAM[2] - Math.floor(CAM[2])});
        u("previousCameraPositionInt", uniforms.get("cameraPositionInt"));
        u("previousCameraPositionFract", uniforms.get("cameraPositionFract"));
        double[] sunView = mulv(view, new double[]{SUN[0] * 100, SUN[1] * 100, SUN[2] * 100, 0});
        u("sunPosition", new double[]{sunView[0], sunView[1], sunView[2]});
        u("moonPosition", new double[]{-sunView[0], -sunView[1], -sunView[2]});
        u("shadowLightPosition", new double[]{sunView[0], sunView[1], sunView[2]});
        double[] up = mulv(view, new double[]{0, 100, 0, 0});
        u("upPosition", new double[]{up[0], up[1], up[2]});
        u("playerLookVector", mulv(viewInv, new double[]{0, 0, -1, 0}));
        u("sunAngle", 0.2);
        u("shadowAngle", 0.2);
        u("worldTime", 4800);
        u("worldDay", 3);
        u("moonPhase", 0);
        u("frameCounter", 600);
        u("frameTime", 1 / 60.0);
        u("frameTimeCounter", 10.0);
        u("viewWidth", w);
        u("viewHeight", h);
        u("aspectRatio", (double) w / h);
        u("near", NEAR);
        u("far", FAR);
        u("rainStrength", 0);
        u("wetness", 0);
        u("thunderStrength", 0);
        u("eyeBrightness", new double[]{0, 240});
        u("eyeBrightnessSmooth", new double[]{0, 240});
        u("isEyeInWater", 0);
        u("eyeAltitude", CAM[1]);
        u("screenBrightness", 1);
        u("fogColor", new double[]{0.63, 0.74, 0.86});
        u("skyColor", new double[]{0.47, 0.65, 1.0});
        u("fogStart", 120);
        u("fogEnd", 192);
        u("fogDensity", 0.0);
        u("biome", 1);
        u("biome_category", 5);
        u("biome_precipitation", 1);
        u("temperature", 0.8);
        u("rainfall", 0.4);
        u("atlasSize", new double[]{1024, 1024});
        u("hasSkylight", 1);
        u("bedrockLevel", -64);
        u("heightLimit", 384);
        u("logicalHeightLimit", 384);
        u("cloudHeight", 192);
        u("ambientLight", 0);
        u("currentPlayerHealth", 1);
        u("maxPlayerHealth", 20);
        u("lightningBoltPosition", new double[]{0, 0, 0, 0});
    }

    private void u(String n, double... v) {
        uniforms.put(n, v);
    }

    private void evalCustom(PackData data) {
        JsonArray cu = data.pack.getAsJsonArray("customUniforms");
        CustomEval ev = new CustomEval(cu);
        CustomEval.Env env = name -> {
            double[] v = uniforms.get(name);
            if (v != null)
                return new CustomEval.Value(v.clone(), false);
            if (name.startsWith("BIOME_") || name.startsWith("CAT_"))
                return CustomEval.Value.of(name.equals("BIOME_PLAINS") ? 1 : 1000 + (name.hashCode() & 0xff));
            UniformCatalog.Std s = UniformCatalog.STANDARD.get(name);
            int n = s == null ? 1 : switch (s.type()) {
                case "vec2", "ivec2" -> 2;
                case "vec3", "ivec3" -> 3;
                case "vec4", "ivec4" -> 4;
                case "mat4" -> 16;
                default -> 1;
            };
            return new CustomEval.Value(new double[n], s != null && s.type().equals("bool"));
        };
        Map<String, CustomEval.Value> out = ev.evaluate(env, 1 / 60.0);
        out.forEach((k, v) -> uniforms.put(k, v.v()));
        for (JsonElement e : cu) {
            JsonObject o = e.getAsJsonObject();
            uniforms.putIfAbsent(o.get("name").getAsString(), new double[4]);
        }
    }

    // ------------------------------------------------------------------------------------------------ tracing

    static final class Hit {
        double t;
        double[] p, n;
        float[] albedo;
        float emission, smooth;
        boolean water;
    }

    /** origin and direction in camera-relative (player) space */
    static Hit trace(double[] o, double[] d) {
        Hit best = null;
        double ox = o[0] + CAM[0], oy = o[1] + CAM[1], oz = o[2] + CAM[2];
        // ground y = 64 (water quad -6..6 x 4..9 z sits 0.12 below grass level is drawn as a lower plane)
        if (d[1] < -1e-6) {
            double t = (64 - oy) / d[1];
            if (t > 0) {
                double hx = ox + d[0] * t, hz = oz + d[2] * t;
                Hit hh = new Hit();
                hh.t = t;
                boolean water = hx > -7 && hx < 4 && hz > -1 && hz < 3;
                hh.albedo = water ? WATER : checker(hx, hz);
                hh.water = water;
                hh.smooth = water ? 0.9f : 0.05f;
                hh.n = new double[]{0, 1, 0};
                best = hh;
            }
        }
        for (Box b : BOXES) {
            double[] tn = slab(ox, oy, oz, d, b);
            if (tn != null && (best == null || tn[0] < best.t)) {
                Hit hh = new Hit();
                hh.t = tn[0];
                hh.n = new double[]{tn[1], tn[2], tn[3]};
                hh.albedo = b.albedo();
                hh.emission = b.emission();
                hh.smooth = b.smooth();
                best = hh;
            }
        }
        if (best != null) {
            best.p = new double[]{o[0] + d[0] * best.t, o[1] + d[1] * best.t, o[2] + d[2] * best.t};
            if (best.t > FAR)
                return null;
        }
        return best;
    }

    static float[] checker(double x, double z) {
        boolean c = (((int) Math.floor(x) + (int) Math.floor(z)) & 1) == 0;
        float k = c ? 1f : 0.86f;
        return new float[]{GRASS[0] * k, GRASS[1] * k, GRASS[2] * k};
    }

    /** ray / axis-aligned box: {t, nx, ny, nz} or null */
    static double[] slab(double ox, double oy, double oz, double[] d, Box b) {
        double t0 = 1e-4, t1 = 1e9;
        double[] o = {ox, oy, oz}, lo = {b.x0(), b.y0(), b.z0()}, hi = {b.x1(), b.y1(), b.z1()};
        int axis = -1;
        double sign = 0;
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < lo[a] || o[a] > hi[a])
                    return null;
                continue;
            }
            double ta = (lo[a] - o[a]) / d[a], tb = (hi[a] - o[a]) / d[a];
            double s = -1;
            if (ta > tb) {
                double tmp = ta;
                ta = tb;
                tb = tmp;
                s = 1;
            }
            if (ta > t0) {
                t0 = ta;
                axis = a;
                sign = s;
            }
            t1 = Math.min(t1, tb);
            if (t0 > t1)
                return null;
        }
        if (axis < 0)
            return null;
        double[] n = new double[3];
        n[axis] = sign;
        return new double[]{t0, n[0], n[1], n[2]};
    }

    private double[] rayDir(int x, int y, double[] viewInv, double[] projInv) {
        // GL row order: row 0 = bottom
        double nx = (x + 0.5) / w * 2 - 1, ny = (y + 0.5) / h * 2 - 1;
        double[] v = mulv(projInv, new double[]{nx, ny, 1, 1});
        double[] dv = {v[0] / v[3], v[1] / v[3], v[2] / v[3], 0};
        double[] dw = mulv(viewInv, dv);
        return norm(new double[]{dw[0], dw[1], dw[2]});
    }

    private float[] shadowMap(int res, double[] sv, double[] sp) {
        float[] m = new float[res * res * 4];
        double[] inv = invert(mul(sp, sv));
        for (int y = 0; y < res; y++)
            for (int x = 0; x < res; x++) {
                double nx = (x + 0.5) / res * 2 - 1, ny = (y + 0.5) / res * 2 - 1;
                double[] near = mulv(inv, new double[]{nx, ny, -1, 1});
                double[] o = {near[0] / near[3], near[1] / near[3], near[2] / near[3]};
                Hit hit = trace(o, new double[]{-SUN[0], -SUN[1], -SUN[2]});
                float depth = 1;
                if (hit != null) {
                    double[] c = mulv(mul(sp, sv), new double[]{hit.p[0], hit.p[1], hit.p[2], 1});
                    depth = (float) (c[2] / c[3] * 0.5 + 0.5);
                }
                m[4 * (y * res + x)] = depth;
            }
        return m;
    }

    private static double shadowAt(float[] map, int res, double[] sv, double[] sp, double[] p, double[] n) {
        double[] q = {p[0] + n[0] * 0.05, p[1] + n[1] * 0.05, p[2] + n[2] * 0.05, 1};
        double[] c = mulv(mul(sp, sv), q);
        double sx = c[0] * 0.5 + 0.5, sy = c[1] * 0.5 + 0.5, sz = c[2] * 0.5 + 0.5;
        if (sx < 0 || sx >= 1 || sy < 0 || sy >= 1)
            return 1;
        float d = map[4 * ((int) (sy * res) * res + (int) (sx * res))];
        return sz - 0.002 > d ? 0 : 1;
    }

    // ------------------------------------------------------------------------------------------------ maths (column-major)

    static double[] viewMatrix() {
        double cy = Math.cos(YAW), sy = Math.sin(YAW), cp = Math.cos(PITCH), sp = Math.sin(PITCH);
        double[] fwd = {-sy * cp, sp, -cy * cp};
        return lookDir(fwd, new double[]{0, 1, 0});
    }

    /** view matrix looking along f (no translation: camera-relative space) */
    static double[] lookDir(double[] f, double[] upHint) {
        f = norm(f);
        double[] s = norm(cross(f, upHint));
        double[] u = cross(s, f);
        return new double[]{s[0], u[0], -f[0], 0, s[1], u[1], -f[1], 0, s[2], u[2], -f[2], 0, 0, 0, 0, 1};
    }

    static double[] lookAlong(double[] toSun) {
        double[] f = {-toSun[0], -toSun[1], -toSun[2]};
        return lookDir(f, Math.abs(f[1]) > 0.99 ? new double[]{0, 0, 1} : new double[]{0, 1, 0});
    }

    static double[] perspective(double fov, double aspect, double n, double f) {
        double t = 1 / Math.tan(fov / 2);
        return new double[]{t / aspect, 0, 0, 0, 0, t, 0, 0, 0, 0, (f + n) / (n - f), -1, 0, 0, 2 * f * n / (n - f), 0};
    }

    static double[] ortho(double l, double r, double b, double t, double n, double f) {
        return new double[]{2 / (r - l), 0, 0, 0, 0, 2 / (t - b), 0, 0, 0, 0, -2 / (f - n), 0,
                -(r + l) / (r - l), -(t + b) / (t - b), -(f + n) / (f - n), 1};
    }

    static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int c = 0; c < 4; c++)
            for (int rr = 0; rr < 4; rr++) {
                double s = 0;
                for (int k = 0; k < 4; k++)
                    s += a[k * 4 + rr] * b[c * 4 + k];
                r[c * 4 + rr] = s;
            }
        return r;
    }

    static double[] mulv(double[] m, double[] v) {
        double[] r = new double[4];
        for (int rr = 0; rr < 4; rr++)
            for (int k = 0; k < 4; k++)
                r[rr] += m[k * 4 + rr] * v[k];
        return r;
    }

    static double[] invert(double[] m) {
        double[] inv = new double[16];
        double[] a = m;
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
        double det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        for (int i = 0; i < 16; i++)
            inv[i] /= det;
        return inv;
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] norm(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    // ------------------------------------------------------------------------------------------------ inputs

    @Override
    public int width() {
        return w;
    }

    @Override
    public int height() {
        return h;
    }

    @Override
    public Map<String, double[]> uniforms() {
        return uniforms;
    }

    @Override
    public Set<String> suppliedImages() {
        return new LinkedHashSet<>(images.keySet());
    }

    @Override
    public float[] image(String name) {
        return images.get(name);
    }

    @Override
    public int[] extent(String name) {
        return extents.get(name);
    }

    @Override
    public String startStage() {
        return "deferred";
    }

    @Override
    public double frameSeconds() {
        return 1 / 60.0;
    }
}
