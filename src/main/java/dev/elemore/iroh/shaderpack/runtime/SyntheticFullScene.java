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
