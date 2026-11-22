package dev.elemore.iroh.shaderpack.runtime;

import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PassGraph;
import dev.elemore.iroh.shaderpack.graph.ResourcePlan;
import dev.elemore.iroh.shaderpack.mesh.WideTerrainVertex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P3-5 FULL-mode replay input: the same small world as {@link SyntheticScene} (ground, three boxes, a pool), but as
 * real geometry: WideTerrainVertex quads per 16-block section (one batch each, origin camera-relative), a procedural
 * 64x64 block atlas, block ids from the pack's block.properties. The frame starts at "begin": the shadow pass,
 * gbuffers_terrain / gbuffers_water, depth captures and the whole post chain run on the backend. Shadow matrices
 * follow {@link Mat#shadow} with the folder's shadowDistance (as the game will). Uniforms otherwise those of
 * SyntheticScene. Deterministic (golden-tested).
 */
public final class SyntheticFullScene implements PackFrameInputs {

    private final SyntheticScene base;
    private final Map<String, double[]> uniforms;
    private final List<Geometry> geometry = new ArrayList<>();
    private final Map<String, float[]> textures = new LinkedHashMap<>();
    private final Map<String, int[]> extents = new HashMap<>();
    static final int ATLAS = 64, TILE = 16;
    static final int GRASS = 0, STONE = 1, WOOD = 2, GLOW = 3, WATER = 4;

    public SyntheticFullScene(PackData data, PassGraph.Frame frame, ResourcePlan plan, int width, int height) {
        this(data, frame, plan, width, height, Set.of());
    }

    /**
     * P3-9: {@code classes} adds vanilla-format draws (program substitution's inputs) for: sky, entities, block,
     * hand, particles, weather, outline, breaking, beacon, glint (any subset; terrain is always there)
     */
    public SyntheticFullScene(PackData data, PassGraph.Frame frame, ResourcePlan plan, int width, int height, Set<String> classes) {
        base = new SyntheticScene(data, frame, plan, width, height);
        uniforms = new HashMap<>(base.uniforms());
        double dist = parse(frame.directives.get("shadowDistance"), 96);
        double[] sunWorld = SyntheticScene.SUN;
        double[][] sh = Mat.shadow(sunWorld, SyntheticScene.CAM, dist, parse(frame.directives.get("shadowIntervalSize"), 2));
        uniforms.put("shadowModelView", sh[0]);
        uniforms.put("shadowModelViewInverse", Mat.invert(sh[0]));
        uniforms.put("shadowProjection", sh[1]);
        uniforms.put("shadowProjectionInverse", Mat.invert(sh[1]));
        PackIdMaps ids = PackIdMaps.of(data);
        buildAtlas();
        buildWorld(ids);
        buildVanilla(ids, classes);
    }

    // ------------------------------------------------------------------------------------------------ P3-9 vanilla-format draws

    public static final String NEW_ENTITY = "vanilla:Position,Color,UV0,UV1,UV2,Normal", POSITION_COLOR = "vanilla:Position,Color",
            POSITION_TEX = "vanilla:Position,UV0", PARTICLE = "vanilla:Position,UV0,Color,UV2",
            POSITION_TEX_COLOR_LIGHTMAP = "vanilla:Position,UV0,Color,UV2", POSITION_COLOR_NORMAL = "vanilla:Position,Color,Normal";

    /** quads in a vanilla format, positions relative to {@code origin} (camera-relative) */
    private final class VBatch {
        final String cls, layout;
        final List<float[]> quads = new ArrayList<>(); // per quad: 4 x (x,y,z) + rgba + uv rect + light + normal
        VBatch(String cls, String layout) {
            this.cls = cls;
            this.layout = layout;
        }

        void quad(float[][] p, float[] rgba, float[] uv, int light, float[] n) {
            float[] q = new float[12 + 4 + 4 + 1 + 3];
            for (int i = 0; i < 4; i++)
                System.arraycopy(p[i], 0, q, i * 3, 3);
            System.arraycopy(rgba, 0, q, 12, 4);
            System.arraycopy(uv, 0, q, 16, 4);
            q[20] = light;
            System.arraycopy(n, 0, q, 21, 3);
            quads.add(q);
        }

        Geometry build(double[] origin, Map<String, double[]> uniforms) {
            VanillaLayout vl = VanillaLayout.of(layout);
            ByteBuffer b = ByteBuffer.allocate(quads.size() * 4 * vl.stride).order(ByteOrder.LITTLE_ENDIAN);
            float[][] uvc = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
            for (int qi = 0; qi < quads.size(); qi++) {
                float[] q = quads.get(qi);
                for (int v = 0; v < 4; v++) {
                    int o = (qi * 4 + v) * vl.stride;
                    for (String e : layout.substring(8).split(",")) {
                        int eo = vl.offsets.get(e);
                        switch (e) {
                            case "Position" -> b.putFloat(o + eo, q[v * 3]).putFloat(o + eo + 4, q[v * 3 + 1]).putFloat(o + eo + 8, q[v * 3 + 2]);
                            case "Color" -> b.put(o + eo, u8(q[12])).put(o + eo + 1, u8(q[13])).put(o + eo + 2, u8(q[14])).put(o + eo + 3, u8(q[15]));
                            case "UV0" -> b.putFloat(o + eo, q[16] + (q[18] - q[16]) * uvc[v][0]).putFloat(o + eo + 4, q[17] + (q[19] - q[17]) * uvc[v][1]);
                            case "UV1" -> b.putShort(o + eo, (short) 0).putShort(o + eo + 2, (short) 10);
                            case "UV2" -> b.putShort(o + eo, (short) ((int) q[20] & 0xFFFF)).putShort(o + eo + 2, (short) ((int) q[20] >>> 16));
                            case "Normal" -> b.put(o + eo, (byte) Math.round(q[21] * 127)).put(o + eo + 1, (byte) Math.round(q[22] * 127))
                                    .put(o + eo + 2, (byte) Math.round(q[23] * 127)).put(o + eo + 3, (byte) 0);
                            default -> {
                            }
                        }
                    }
                }
            }
            return new Geometry(cls, layout, b, quads.size(), origin, null, uniforms);
        }
    }

    static byte u8(float v) {
        return (byte) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    private static final float[] WHITE = {1, 1, 1, 1};
    private static final int FULL_LIGHT = 240 | 240 << 16;

    /** a box as 6 quads (outward, counter-clockwise), camera-relative coordinates */
    private void box(VBatch b, double x0, double y0, double z0, double x1, double y1, double z1, float[] uv, int light) {
        float a = (float) (x0 - SyntheticScene.CAM[0]), bb = (float) (y0 - SyntheticScene.CAM[1]), c = (float) (z0 - SyntheticScene.CAM[2]);
        float d = (float) (x1 - SyntheticScene.CAM[0]), e = (float) (y1 - SyntheticScene.CAM[1]), f = (float) (z1 - SyntheticScene.CAM[2]);
        b.quad(new float[][]{{a, e, c}, {a, e, f}, {d, e, f}, {d, e, c}}, WHITE, uv, light, new float[]{0, 1, 0});
        b.quad(new float[][]{{a, bb, f}, {a, bb, c}, {d, bb, c}, {d, bb, f}}, WHITE, uv, light, new float[]{0, -1, 0});
        b.quad(new float[][]{{d, e, c}, {d, bb, c}, {a, bb, c}, {a, e, c}}, WHITE, uv, light, new float[]{0, 0, -1});
        b.quad(new float[][]{{a, e, f}, {a, bb, f}, {d, bb, f}, {d, e, f}}, WHITE, uv, light, new float[]{0, 0, 1});
        b.quad(new float[][]{{a, e, c}, {a, bb, c}, {a, bb, f}, {a, e, f}}, WHITE, uv, light, new float[]{-1, 0, 0});
        b.quad(new float[][]{{d, e, f}, {d, bb, f}, {d, bb, c}, {d, e, c}}, WHITE, uv, light, new float[]{1, 0, 0});
    }

    private void buildVanilla(PackIdMaps ids, Set<String> classes) {
        double[] zero = {0, 0, 0};
        if (classes.contains("sky")) {
            VBatch up = new VBatch("sky_basic", POSITION_COLOR);
            float r = 64;
            up.quad(new float[][]{{-r, 16, -r}, {-r, 16, r}, {r, 16, r}, {r, 16, -r}}, new float[]{0.47f, 0.65f, 1f, 1}, sprite(GLOW), 0, new float[]{0, -1, 0});
            up.quad(new float[][]{{-r, -16, r}, {-r, -16, -r}, {r, -16, -r}, {r, -16, r}}, new float[]{0.05f, 0.05f, 0.1f, 1}, sprite(GLOW), 0, new float[]{0, 1, 0});
            geometry.add(up.build(zero, null));
            VBatch sun = new VBatch("sky_textured", POSITION_TEX);
            double[] sd = SyntheticScene.SUN;
            float cx = (float) sd[0] * 90, cy = (float) sd[1] * 90, cz = (float) sd[2] * 90, h = 8;
            sun.quad(new float[][]{{cx - h, cy, cz - h}, {cx - h, cy, cz + h}, {cx + h, cy, cz + h}, {cx + h, cy, cz - h}}, WHITE, sprite(GLOW), 0, new float[]{0, -1, 0});
            geometry.add(sun.build(zero, null));
        }
        if (classes.contains("entities")) {
            VBatch e = new VBatch("entities", NEW_ENTITY);
            box(e, 4.05, 64, -3.45, 4.95, 65.8, -2.55, sprite(WOOD), FULL_LIGHT);
            geometry.add(e.build(zero, Map.of("entityId", new double[]{ids.entity("minecraft:zombie")}, "entityColor", new double[4])));
        }
        if (classes.contains("block")) {
            VBatch b = new VBatch("block_entities", NEW_ENTITY);
            box(b, -5.94, 64, -6.94, -5.06, 64.875, -6.06, sprite(WOOD), FULL_LIGHT);
            geometry.add(b.build(zero, Map.of("blockEntityId", new double[]{ids.block("minecraft:chest")})));
        }
        if (classes.contains("hand")) {
            VBatch hb = new VBatch("hand_solid", NEW_ENTITY);
            hb.quad(new float[][]{{0.35f, -0.25f, -0.7f}, {0.35f, -0.75f, -0.75f}, {0.6f, -0.75f, -0.8f}, {0.6f, -0.25f, -0.75f}}, WHITE, sprite(WOOD),
                    FULL_LIGHT, new float[]{0, 0, 1});
            geometry.add(hb.build(zero, null));
        }
        if (classes.contains("particles")) {
            VBatch pb = new VBatch("particles_opaque", PARTICLE);
            for (int i = 0; i < 6; i++) {
                float x = (float) (0.5 + i * 0.4 - SyntheticScene.CAM[0]), y = (float) (65.2 + (i % 3) * 0.3 - SyntheticScene.CAM[1]),
                        z = (float) (-4 - SyntheticScene.CAM[2]), s = 0.1f;
                pb.quad(new float[][]{{x - s, y + s, z}, {x - s, y - s, z}, {x + s, y - s, z}, {x + s, y + s, z}}, new float[]{1, 0.6f, 0.2f, 1}, sprite(GLOW),
                        FULL_LIGHT, new float[]{0, 0, 1});
            }
            geometry.add(pb.build(zero, null));
        }
        if (classes.contains("weather")) {
            uniforms.put("rainStrength", new double[]{1});
            uniforms.put("wetness", new double[]{1});
            VBatch w = new VBatch("weather", POSITION_TEX_COLOR_LIGHTMAP);
            for (int i = -3; i <= 3; i++) {
                float x = i * 1.5f, z = -6;
                w.quad(new float[][]{{x, 6, z}, {x, -3, z}, {x + 1, -3, z}, {x + 1, 6, z}}, new float[]{0.7f, 0.75f, 0.9f, 0.6f}, sprite(WATER), FULL_LIGHT,
                        new float[]{0, 0, 1});
            }
            geometry.add(w.build(zero, null));
        }
        if (classes.contains("outline")) {
            VBatch o = new VBatch("outline", POSITION_COLOR_NORMAL);
            float x0 = (float) (-3 - SyntheticScene.CAM[0]), x1 = (float) (0 - SyntheticScene.CAM[0]), y = (float) (67.002 - SyntheticScene.CAM[1]),
                    z0 = (float) (-4 - SyntheticScene.CAM[2]), z1 = (float) (-1 - SyntheticScene.CAM[2]), t = 0.02f;
            o.quad(new float[][]{{x0, y, z0}, {x0, y, z0 + t}, {x1, y, z0 + t}, {x1, y, z0}}, new float[]{0, 0, 0, 0.4f}, sprite(STONE), 0, new float[]{0, 1, 0});
            o.quad(new float[][]{{x0, y, z1 - t}, {x0, y, z1}, {x1, y, z1}, {x1, y, z1 - t}}, new float[]{0, 0, 0, 0.4f}, sprite(STONE), 0, new float[]{0, 1, 0});
            geometry.add(o.build(zero, null));
        }
        if (classes.contains("breaking")) {
            VBatch d = new VBatch("damaged_block", NEW_ENTITY);
            float x0 = (float) (-3 - SyntheticScene.CAM[0]), x1 = (float) (0 - SyntheticScene.CAM[0]), y0 = (float) (64 - SyntheticScene.CAM[1]),
                    y1 = (float) (67 - SyntheticScene.CAM[1]), z = (float) (-0.999 - SyntheticScene.CAM[2]);
            d.quad(new float[][]{{x0, y1, z}, {x0, y0, z}, {x1, y0, z}, {x1, y1, z}}, new float[]{0.3f, 0.3f, 0.3f, 1}, sprite(STONE), FULL_LIGHT, new float[]{0, 0, 1});
            geometry.add(d.build(zero, null));
        }
        if (classes.contains("beacon")) {
            VBatch bb = new VBatch("beacon_beam", NEW_ENTITY);
            box(bb, 6.3, 64, -10.7, 6.7, 90, -10.3, sprite(GLOW), FULL_LIGHT);
            geometry.add(bb.build(zero, null));
        }
        if (classes.contains("glint")) {
            VBatch g = new VBatch("armor_glint", NEW_ENTITY);
            box(g, 4.04, 64, -3.46, 4.96, 65.81, -2.54, sprite(WATER), FULL_LIGHT);
            geometry.add(g.build(zero, null));
        }
    }

    static double parse(String s, double dflt) {
        if (s == null)
            return dflt;
        try {
            return Double.parseDouble(s.replaceAll("[fF]$", ""));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // ------------------------------------------------------------------------------------------------ atlas

    private void buildAtlas() {
        float[] px = new float[ATLAS * ATLAS * 4];
        float[][] base = {{0.36f, 0.58f, 0.24f}, {0.50f, 0.50f, 0.52f}, {0.52f, 0.37f, 0.22f}, {0.95f, 0.78f, 0.42f}, {0.20f, 0.38f, 0.75f}};
        for (int t = 0; t < base.length; t++) {
            int tx = (t % 4) * TILE, ty = (t / 4) * TILE;
            for (int y = 0; y < TILE; y++)
                for (int x = 0; x < TILE; x++) {
                    int h = hash(t * 977 + x * 31 + y * 131);
                    float n = 0.82f + 0.36f * ((h & 0xFF) / 255f);
                    if (t == WOOD && (x % 4 == 0))
                        n *= 0.75f;
                    if (t == GLOW && ((h >> 8) & 3) == 0)
                        n = 1.15f;
                    int i = 4 * ((ty + y) * ATLAS + tx + x);
                    for (int c = 0; c < 3; c++)
                        px[i + c] = Math.min(1, base[t][c] * n);
                    px[i + 3] = t == WATER ? 0.70f : 1f;
                }
        }
        textures.put("geo:tex", px);
        extents.put("geo:tex", new int[]{ATLAS, ATLAS, 1});
        uniforms.put("atlasSize", new double[]{ATLAS, ATLAS});
    }

    static int hash(int v) {
        v ^= v >>> 16;
        v *= 0x7feb352d;
        v ^= v >>> 15;
        v *= 0x846ca68b;
        v ^= v >>> 16;
        return v;
    }

    float[] sprite(int t) {
        float u0 = (t % 4) * TILE / (float) ATLAS, v0 = (t / 4) * TILE / (float) ATLAS;
        return new float[]{u0, v0, u0 + TILE / (float) ATLAS, v0 + TILE / (float) ATLAS};
    }

    // ------------------------------------------------------------------------------------------------ world

    /** quads per section key "sx,sy,sz|class" */
    private final Map<String, List<WideTerrainVertex.Quad>> sections = new LinkedHashMap<>();

    private void buildWorld(PackIdMaps ids) {
        int grass = ids.block("minecraft:grass_block"), stone = ids.block("minecraft:stone"), wood = ids.block("minecraft:oak_log"),
                glow = ids.block("minecraft:glowstone"), water = ids.block("minecraft:water");
        // ground: grass tops of blocks y=63 over x -16..15, z -24..7, minus the pool
        for (int x = -16; x < 16; x++)
            for (int z = -24; z < 8; z++) {
                boolean pool = x >= 4 && x < 8 && z >= -6 && z < -2;
                if (pool) {
                    face(x, 62, z, 1, GRASS, grass, 0, 0, "terrain_solid"); // pool floor
                    waterTop(x, 62, z, water);
                } else {
                    face(x, 63, z, 1, GRASS, grass, 0, 0, "terrain_solid");
                }
            }
        // pool walls (visible sides of the hole)
        for (int x = 4; x < 8; x++) {
            face(x, 62, -7, 4, STONE, stone, 0, 0, "terrain_solid"); // south face of z=-7 block
            face(x, 62, -2, 5, STONE, stone, 0, 0, "terrain_solid");
        }
        for (int z = -6; z < -2; z++) {
            face(3, 62, z, 2, STONE, stone, 0, 0, "terrain_solid");
            face(8, 62, z, 3, STONE, stone, 0, 0, "terrain_solid");
        }
        int[][] boxes = {{-3, 64, -4, 0, 67, -1, STONE}, {2, 64, -9, 3, 72, -8, WOOD}, {-1, 64, -7, 0, 65, -6, GLOW}};
        for (int[] b : boxes) {
            int sprite = b[6];
            int id = sprite == STONE ? stone : sprite == WOOD ? wood : glow;
            int emission = sprite == GLOW ? 15 : 0;
            for (int x = b[0]; x < b[3]; x++)
                for (int y = b[1]; y < b[4]; y++)
                    for (int z = b[2]; z < b[5]; z++)
                        for (int f = 0; f < 6; f++) {
                            int nx = x + DIRS[f][0], ny = y + DIRS[f][1], nz = z + DIRS[f][2];
                            boolean inside = nx >= b[0] && nx < b[3] && ny >= b[1] && ny < b[4] && nz >= b[2] && nz < b[5];
                            if (!inside && !(f == 0 && y == b[1]))
                                face(x, y, z, f, sprite, id, emission, emission > 0 ? 240 : 0, "terrain_solid");
                        }
        }
        for (Map.Entry<String, List<WideTerrainVertex.Quad>> e : sections.entrySet()) {
            String[] k = e.getKey().split("\\|");
            String[] sp = k[0].split(",");
            double[] origin = {Integer.parseInt(sp[0]) * 16 - SyntheticScene.CAM[0], Integer.parseInt(sp[1]) * 16 - SyntheticScene.CAM[1],
                    Integer.parseInt(sp[2]) * 16 - SyntheticScene.CAM[2]};
            List<WideTerrainVertex.Quad> qs = e.getValue();
            ByteBuffer buf = ByteBuffer.allocate(qs.size() * WideTerrainVertex.QUAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < qs.size(); i++)
                WideTerrainVertex.writeQuad(buf, i * WideTerrainVertex.QUAD_BYTES, qs.get(i), false);
            geometry.add(new Geometry(k[1], "wide-terrain", buf, qs.size(), origin));
        }
    }

    /** face order: down, up, north (-z), south (+z), west (-x), east (+x) */
    static final int[][] DIRS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    static final float[] SHADE = {0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f};

    /** corners (block-relative), counter-clockwise seen from outside, matching uv (0,0) (1,0) (1,1) (0,1) */
    static final float[][][] CORNERS = {
            {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},  // down
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},  // up
            {{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}},  // north
            {{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}},  // south
            {{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}},  // west
            {{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}}}; // east
    static final float[][] UV = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};

    private void face(int x, int y, int z, int f, int spriteIdx, int blockId, int emission, int blockLight, String cls) {
        faceAt(x, y, z, f, spriteIdx, blockId, emission, blockLight, cls, 0);
    }

    private void waterTop(int x, int y, int z, int id) {
        faceAt(x, y, z, 1, WATER, id, 0, 0, "terrain_translucent", -0.1f);
    }

    private void faceAt(int x, int y, int z, int f, int spriteIdx, int blockId, int emission, int blockLight, String cls, float yOff) {
        int sx = Math.floorDiv(x, 16), sy = Math.floorDiv(y, 16), sz = Math.floorDiv(z, 16);
        float bx = x - sx * 16, by = y - sy * 16, bz = z - sz * 16;
        float[] pos = new float[12], uv = new float[8];
        float[] sp = sprite(spriteIdx);
        for (int i = 0; i < 4; i++) {
            float[] c = CORNERS[f][i];
            pos[3 * i] = bx + c[0];
            pos[3 * i + 1] = by + c[1] + (f == 1 ? yOff : 0);
            pos[3 * i + 2] = bz + c[2];
            uv[2 * i] = sp[0] + (sp[2] - sp[0]) * UV[i][0];
            uv[2 * i + 1] = sp[1] + (sp[3] - sp[1]) * UV[i][1];
        }
        int shade = Math.round(SHADE[f] * 255);
        int argb = 0xFF000000 | shade << 16 | shade << 8 | shade;
        int light = blockLight | 240 << 16;
        WideTerrainVertex.Quad q = new WideTerrainVertex.Quad(pos, new int[]{argb, argb, argb, argb}, new float[]{1, 1, 1, 1}, uv,
                new int[]{light, light, light, light}, new float[]{DIRS[f][0], DIRS[f][1], DIRS[f][2]}, sp,
                new float[]{bx, by, bz}, emission, blockId, cls.equals("terrain_translucent") ? 2 : 0);
        sections.computeIfAbsent(sx + "," + sy + "," + sz + "|" + cls, k -> new ArrayList<>()).add(q);
    }

    // ------------------------------------------------------------------------------------------------ inputs

    @Override
    public int width() {
        return base.width();
    }

    @Override
    public int height() {
        return base.height();
    }

    @Override
    public Map<String, double[]> uniforms() {
        return uniforms;
    }

    @Override
    public Set<String> suppliedImages() {
        return Set.of();
    }

    @Override
    public float[] image(String name) {
        return textures.get(name);
    }

    @Override
    public int[] extent(String name) {
        return extents.get(name);
    }

    @Override
    public String startStage() {
        return "begin";
    }

    @Override
    public double frameSeconds() {
        return base.frameSeconds();
    }

    @Override
    public List<Geometry> geometry() {
        return geometry;
    }

    @Override
    public Set<String> geometryTextures() {
        return textures.keySet();
    }

    public int quads() {
        int n = 0;
        for (Geometry g : geometry)
            n += g.quads();
        return n;
    }
}
