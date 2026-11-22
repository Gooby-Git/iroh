package dev.elemore.iroh.rttest;

import net.vulkanmod.render.vertex.PlaneSnap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * P5-0 offline RT scene: a 32x32x32 voxel world (2x2x2 sections of 16^3) with terrain, a hill with a cave lit only by
 * a glowstone, a house with a roof overhang, a glass-pane window and a cyan lamp inside, a pond (2 deep), a tree with
 * cutout leaves, a polished patch (glossy), and an outdoor glowstone. Meshed like Iroh's chunk mesher into the 16-byte
 * compressed terrain vertex (int16 positions x2048 with -4 block offset on the PlaneSnap grid, light short, uv x32768,
 * RGBA colour), per section in buckets: 0 opaque, 1 alpha-tested, 2 translucent (water).
 * <p>
 * RT rule found here (see knowledge/rt-iroh.md): the raster mesher bakes vanilla's face shade and AO into the vertex
 * colour; the RT path needs the biome tint alone, so in RT mode the mesher writes colour = tint (this scene does).
 */
public final class RtScene {
    public final int SX, SY, SZ, W, H, D;
    /** P5-1 look scenes: the village world (default: P5-0's 32^3 test world, goldens pinned to it). P5-2: 80x32x80, the
     *  48x48 village sits at offset {@link #VPAD} inside terrain that continues on every side, so no camera of the sheets
     *  sees the world's edge (P5-1's day view had a grey band at the left where the world ended). */
    public final boolean village;
    /** P5-2: village content offset in x and z (blocks). Scene cameras add it ({@link #v}). */
    public static final int VPAD = 16;
    /** Generation-time offset added by set/get (VPAD while the village content is placed, else 0). */
    int gox, goz;
    /** Vanilla-style light levels per cell (BFS), village only: sky and block light 0..15. */
    public int[] skyLight, blockLight;
    // blocks
    public static final int AIR = 0, GRASS = 1, DIRT = 2, STONE = 3, PLANKS = 4, LOG = 5, LEAVES = 6, PANE = 7, WATER = 8,
            GLOWSTONE = 9, POLISHED = 10, LANTERN = 11, COBBLE = 12, TORCH = 13, GLASS = 14, SAND = 15, GRAVEL = 16, IRON = 17, SMOOTH = 18,
            ROOF = 19, WLANTERN = 20;
    // atlas tiles (4x4 grid of 16x16 texels)
    public static final int T_GRASS_TOP = 0, T_GRASS_SIDE = 1, T_DIRT = 2, T_STONE = 3, T_PLANKS = 4, T_LOG = 5, T_LOG_TOP = 6,
            T_LEAVES = 7, T_PANE = 8, T_WATER = 9, T_GLOWSTONE = 10, T_POLISHED = 11, T_LANTERN = 12, T_COBBLE = 13, T_TORCH = 14,
            T_GLASS = 15, T_SAND = 16, T_GRAVEL = 17, T_IRON = 18, T_SMOOTH = 19, T_ROOF = 20, T_WLANTERN = 21, T_STICK = 22, TILES = 64;
    public static final int ATLAS = 128, TILE = 16, GRID = 8;
    public static final float WATER_TOP = 0.875f;

    /** Per-tile material (the v1 "vanilla without PBR" rules made explicit; see docs/P5-DESIGN.md section 8). */
    public record Material(float roughness, int alphaMode, boolean water, boolean spec, float er, float eg, float eb, float f0) {
        boolean emissive() { return er + eg + eb > 0; }
    }

    public final int[] blocks;
    public final int[] atlas = new int[ATLAS * ATLAS];      // RGBA8, R in the low byte
    public final Material[] materials = new Material[TILES];
    public final List<SectionMesh> sections = new ArrayList<>();
    public final List<Light> lights = new ArrayList<>();
    /** P5-3: entity part models uploaded next to the terrain (null = no entities; the P5-0..P5-2 runs are unchanged). */
    public RtEntities entities;

    /** One section's mesh: vertices per bucket (16 bytes each, 4 per quad). */
    public static final class SectionMesh {
        public final int sx, sy, sz;
        public final ByteBuffer[] buckets = new ByteBuffer[3];
        public final int[] quads = new int[3];

        SectionMesh(int sx, int sy, int sz) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            for (int i = 0; i < 3; i++) buckets[i] = ByteBuffer.allocate(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
        }

        public int totalQuads() { return quads[0] + quads[1] + quads[2]; }
    }

    /** Emissive quad light (built at mesh time): corner, two edges, outward normal, uniform radiance. */
    public record Light(float[] p0, float[] e1, float[] e2, float[] n, float area, float[] radiance) {
        public float power() {
            return area * (float) Math.PI * (0.2126f * radiance[0] + 0.7152f * radiance[1] + 0.0722f * radiance[2]);
        }
    }

    public RtScene() {
        this(false);
    }

    public RtScene(boolean village) {
        this.village = village;
        SX = village ? 5 : 2;
        SY = 2;
        SZ = village ? 5 : 2;
        W = SX * 16;
        H = SY * 16;
        D = SZ * 16;
        blocks = new int[W * H * D];
        buildMaterials();
        buildAtlas();
        if (village) {
            generateVillage();
            computeLight();
        } else generate();
        mesh();
    }

    /** Village coordinates (P5-1 numbering) to world coordinates (P5-2 padded world). */
    public static org.joml.Vector3f v(float x, float y, float z) {
        return new org.joml.Vector3f(x + VPAD, y, z + VPAD);
    }

    public int get(int x, int y, int z) {
        x += gox;
        z += goz;
        if (x < 0 || y < 0 || z < 0 || x >= W || y >= H || z >= D) return AIR;
        return blocks[(y * D + z) * W + x];
    }

    void set(int x, int y, int z, int b) {
        x += gox;
        z += goz;
        if (x < 0 || y < 0 || z < 0 || x >= W || y >= H || z >= D) return;
        blocks[(y * D + z) * W + x] = b;
    }

    static boolean opaqueCube(int b) {
        return b == GRASS || b == DIRT || b == STONE || b == PLANKS || b == LOG || b == GLOWSTONE || b == POLISHED || b == LANTERN
                || b == COBBLE || b == SAND || b == GRAVEL || b == IRON || b == SMOOTH || b == ROOF;
    }

    // ------------------------------------------------------------------------------------------------ materials/atlas

    void buildMaterials() {
        Material diffuse = new Material(1f, 0, false, false, 0, 0, 0, 0.04f);
        for (int i = 0; i < TILES; i++) materials[i] = diffuse;
        materials[T_LEAVES] = new Material(1f, 1, false, false, 0, 0, 0, 0.04f);
        materials[T_PANE] = new Material(1f, 1, false, false, 0, 0, 0, 0.04f);
        materials[T_WATER] = new Material(0f, 2, true, true, 0, 0, 0, 0.02f);
        materials[T_POLISHED] = new Material(0.3f, 0, false, true, 0, 0, 0, 0.04f);
        materials[T_GLOWSTONE] = new Material(1f, 0, false, false, 9.0f, 6.0f, 2.6f, 0.04f);
        materials[T_LANTERN] = new Material(1f, 0, false, false, 1.6f, 6.0f, 7.0f, 0.04f);
        if (village) villageMaterials();
    }

    /** P5-1: the village's materials come from the game's rules (RtMaterialRules) by vanilla block id. */
    void villageMaterials() {
        String[][] ids = {{"" + T_GRASS_TOP, "minecraft:grass_block"}, {"" + T_DIRT, "minecraft:dirt"}, {"" + T_STONE, "minecraft:stone"},
                {"" + T_PLANKS, "minecraft:oak_planks"}, {"" + T_LOG, "minecraft:oak_log"}, {"" + T_COBBLE, "minecraft:cobblestone"},
                {"" + T_SAND, "minecraft:sand"}, {"" + T_GRAVEL, "minecraft:gravel"}, {"" + T_IRON, "minecraft:iron_block"},
                {"" + T_SMOOTH, "minecraft:smooth_stone"}, {"" + T_POLISHED, "minecraft:polished_andesite"}, {"" + T_ROOF, "minecraft:dark_oak_planks"},
                {"" + T_STICK, "minecraft:oak_planks"}};
        for (String[] e : ids) {
            dev.elemore.iroh.rt.RtMaterialRules.Surface sf = dev.elemore.iroh.rt.RtMaterialRules.surface(e[1]);
            materials[Integer.parseInt(e[0])] = new Material(sf.roughness(), 0, false, sf.roughness() <= 0.40f, 0, 0, 0, sf.f0());
        }
        materials[T_GLASS] = new Material(0.05f, 1, false, false, 0, 0, 0, 0.04f);
        emissive(T_GLOWSTONE, "minecraft:glowstone", 6f);
        emissive(T_TORCH, "minecraft:torch", 4 * 0.125f * 0.1875f + 0.125f * 0.125f);
        emissive(T_WLANTERN, "minecraft:lantern", 6f * 0.375f * 0.375f);
        emissive(T_LANTERN, "minecraft:sea_lantern", 6f);
    }

    void emissive(int tile, String id, float area) {
        float[] r = dev.elemore.iroh.rt.RtMaterialRules.radiance(dev.elemore.iroh.rt.RtMaterialRules.emission(id, 0), area);
        materials[tile] = new Material(1f, 0, false, false, r[0], r[1], r[2], 0.04f);
    }

    static int hash(int x, int y, int t) {
        int h = x * 374761393 + y * 668265263 + t * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    static int rgba(float r, float g, float b, float a) {
        int R = Math.round(Math.max(0, Math.min(1, r)) * 255), G = Math.round(Math.max(0, Math.min(1, g)) * 255),
                B = Math.round(Math.max(0, Math.min(1, b)) * 255), A = Math.round(Math.max(0, Math.min(1, a)) * 255);
        return R | (G << 8) | (B << 16) | (A << 24);
    }

    void buildAtlas() {
        for (int t = 0; t < TILES; t++)
            for (int y = 0; y < TILE; y++)
                for (int x = 0; x < TILE; x++) {
                    float n = ((hash(x, y, t) & 0xFFFF) / 65535f) - 0.5f; // texel noise
                    float r, g, b, a = 1;
                    switch (t) {
                        case T_GRASS_TOP -> { r = 0.55f + n * 0.15f; g = 0.60f + n * 0.15f; b = 0.50f + n * 0.1f; } // tinted green by vertex colour
                        case T_GRASS_SIDE -> {
                            if (y < 4 - (hash(x, 0, 99) & 1)) { r = 0.33f; g = 0.52f + n * 0.1f; b = 0.20f; }
                            else { r = 0.45f + n * 0.1f; g = 0.32f + n * 0.08f; b = 0.20f + n * 0.05f; }
                        }
                        case T_DIRT -> { r = 0.45f + n * 0.12f; g = 0.32f + n * 0.08f; b = 0.20f + n * 0.05f; }
                        case T_STONE -> { r = g = b = 0.48f + n * 0.14f; }
                        case T_PLANKS -> { float s = (y % 4 == 0) ? 0.75f : 1f; r = (0.62f + n * 0.06f) * s; g = (0.47f + n * 0.05f) * s; b = (0.28f + n * 0.04f) * s; }
                        case T_LOG -> { float s = (x % 4 == 0) ? 0.7f : 1f; r = (0.38f + n * 0.06f) * s; g = (0.29f + n * 0.05f) * s; b = (0.18f) * s; }
                        case T_LOG_TOP -> { float d = (float) Math.hypot(x - 7.5, y - 7.5); float s = ((int) d % 3 == 0) ? 0.8f : 1f; r = 0.62f * s; g = 0.50f * s; b = 0.30f * s; }
                        case T_LEAVES -> { r = 0.55f + n * 0.2f; g = 0.62f + n * 0.2f; b = 0.50f + n * 0.1f; a = ((hash(x, y, 777) & 0xFF) < 100) ? 0 : 1; }
                        case T_PANE -> { boolean frame = x == 0 || y == 0 || x == 15 || y == 15 || x == 7 || y == 7; r = 0.80f; g = 0.85f; b = 0.88f; a = frame ? 1 : 0; }
                        case T_WATER -> { r = 0.20f; g = 0.35f + n * 0.05f; b = 0.60f; }
                        case T_GLOWSTONE -> { r = 0.95f + n * 0.05f; g = 0.78f + n * 0.15f; b = 0.45f + n * 0.1f; }
                        case T_POLISHED -> { r = g = b = 0.70f + n * 0.04f; }
                        case T_LANTERN -> { r = 0.65f + n * 0.1f; g = 0.90f; b = 0.92f; }
                        case T_COBBLE -> { float c = ((hash(x / 3, y / 3, 5) & 3) == 0) ? 0.62f : 0.80f; r = g = b = (0.46f + n * 0.2f) * c; }
                        case T_TORCH -> { r = 1.0f; g = 0.85f + n * 0.1f; b = 0.45f; }
                        case T_STICK -> { r = 0.42f + n * 0.05f; g = 0.30f; b = 0.17f; }
                        case T_GLASS -> { boolean fr = x == 0 || y == 0 || x == 15 || y == 15 || (x == y + 3 && x < 9); r = 0.85f; g = 0.92f; b = 0.95f; a = fr ? 1 : 0; }
                        case T_SAND -> { r = 0.86f + n * 0.06f; g = 0.80f + n * 0.06f; b = 0.60f + n * 0.05f; }
                        case T_GRAVEL -> { float c = ((hash(x / 2, y / 2, 7) & 3) == 0) ? 0.7f : 1f; r = (0.55f + n * 0.12f) * c; g = (0.52f + n * 0.12f) * c; b = (0.50f + n * 0.1f) * c; }
                        case T_IRON -> { float c = (x == 0 || y == 0 || x == 15 || y == 15) ? 0.8f : 1f; r = g = (0.84f + n * 0.03f) * c; b = (0.86f + n * 0.03f) * c; }
                        case T_SMOOTH -> { float c = (y == 0 || y == 15) ? 0.85f : 1f; r = g = b = (0.66f + n * 0.03f) * c; }
                        case T_ROOF -> { float c = (y % 4 == 0) ? 0.72f : 1f; r = (0.30f + n * 0.04f) * c; g = (0.20f + n * 0.03f) * c; b = (0.11f) * c; }
                        case T_WLANTERN -> { boolean cage = x < 2 || x > 13 || y < 2 || y > 13; r = cage ? 0.25f : 1.0f; g = cage ? 0.25f : 0.80f; b = cage ? 0.28f : 0.45f; }
                        default -> { r = 1; g = 0; b = 1; }
                    }
                    int tx = (t % GRID) * TILE + x, ty = (t / GRID) * TILE + y;
                    atlas[ty * ATLAS + tx] = rgba(r, g, b, a);
                }
        RtEntities.paint(atlas);   // P5-3 entity textures in tiles no terrain uses
    }

    // ------------------------------------------------------------------------------------------------ world

    void generate() {
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++) {
                double hill = Math.max(0, 11 - Math.hypot(x - 25, z - 24) * 0.9);
                int h = 8 + (int) Math.round(hill);
                for (int y = 0; y < h; y++) set(x, y, z, y < h - 3 ? STONE : y < h - 1 ? DIRT : GRASS);
            }
        // cave: a tunnel into the hill from the west, lit only by a glowstone at its end
        for (int x = 15; x <= 30; x++)
            for (int z = 22; z <= 24; z++)
                for (int y = 9; y <= 11; y++) set(x, y, z, AIR);
        for (int x = 15; x <= 30; x++) for (int z = 22; z <= 24; z++) set(x, 8, z, STONE);
        set(29, 9, 23, GLOWSTONE);
        // house: planks walls 8..11, roof at 12 with a one-block overhang, door south, glass pane window west, lamp inside
        for (int x = 3; x <= 12; x++) for (int z = 3; z <= 12; z++) for (int y = 8; y <= 14; y++) set(x, y, z, AIR);
        for (int x = 4; x <= 11; x++)
            for (int z = 4; z <= 11; z++) {
                set(x, 7, z, PLANKS);
                for (int y = 8; y <= 11; y++) if (x == 4 || x == 11 || z == 4 || z == 11) set(x, y, z, PLANKS);
            }
        for (int x = 3; x <= 12; x++) for (int z = 3; z <= 12; z++) set(x, 12, z, PLANKS);
        set(7, 8, 11, AIR);
        set(7, 9, 11, AIR);
        for (int z = 6; z <= 8; z++) for (int y = 9; y <= 10; y++) set(4, y, z, PANE);
        set(9, 8, 8, LANTERN);
        // polished patch (glossy reflections in the sun)
        for (int x = 14; x <= 18; x++) for (int z = 4; z <= 8; z++) set(x, 7, z, POLISHED);
        // pond, 2 deep
        for (int x = 14; x <= 24; x++)
            for (int z = 8; z <= 16; z++) {
                if (Math.hypot((x - 19) / 4.5, (z - 12) / 3.5) > 1) continue;
                for (int y = 8; y <= 14; y++) set(x, y, z, AIR);
                set(x, 5, z, DIRT);
                set(x, 6, z, WATER);
                set(x, 7, z, WATER);
            }
        // tree
        for (int y = 8; y <= 12; y++) set(25, y, 9, LOG);
        for (int x = 23; x <= 27; x++)
            for (int z = 7; z <= 11; z++)
                for (int y = 11; y <= 14; y++)
                    if (get(x, y, z) == AIR && Math.hypot(Math.hypot(x - 25, z - 9), (y - 12.5) * 1.3) <= 2.4) set(x, y, z, LEAVES);
        set(25, 13, 9, LEAVES);
        set(25, 14, 9, LEAVES);
        // foreground bush (cutout leaves on the grass) and a free-standing glass pane wall: dappled shadows
        for (int x = 2; x <= 4; x++) for (int z = 18; z <= 20; z++) for (int y = 8; y <= 9; y++) set(x, y, z, LEAVES);
        for (int z = 17; z <= 21; z++) for (int y = 8; y <= 10; y++) set(11, y, z, PANE);
        // outdoor glowstone
        set(9, 8, 16, GLOWSTONE);
    }

    // ------------------------------------------------------------------------------------------------ mesher

    static final int[][] DIRS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    int tileFor(int b, int face) {
        return switch (b) {
            case GRASS -> face == 1 ? T_GRASS_TOP : face == 0 ? T_DIRT : T_GRASS_SIDE;
            case DIRT -> T_DIRT;
            case STONE -> T_STONE;
            case PLANKS -> T_PLANKS;
            case LOG -> face <= 1 ? T_LOG_TOP : T_LOG;
            case LEAVES -> T_LEAVES;
            case PANE -> T_PANE;
            case WATER -> T_WATER;
            case GLOWSTONE -> T_GLOWSTONE;
            case POLISHED -> T_POLISHED;
            case LANTERN -> T_LANTERN;
            case COBBLE -> T_COBBLE;
            case GLASS -> T_GLASS;
            case SAND -> T_SAND;
            case GRAVEL -> T_GRAVEL;
            case IRON -> T_IRON;
            case SMOOTH -> T_SMOOTH;
            case ROOF -> T_ROOF;
            default -> T_STONE;
        };
    }

    static int tint(int b, int face) {
        if ((b == GRASS && face == 1) || b == LEAVES) return rgba(0.62f, 0.85f, 0.42f, 1);
        return 0xFFFFFFFF;
    }

    /**
     * Corners of a face of the unit box [x0,x1]x[y0,y1]x[z0,z1], counter-clockwise seen from outside; corner 0 is the
     * texture's bottom-left (uv 0,1), then bottom-right (1,1), top-right (1,0), top-left (0,0).
     */
    static float[][] faceCorners(int face, float x0, float y0, float z0, float x1, float y1, float z1) {
        return switch (face) {
            case 0 -> new float[][]{{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
            case 1 -> new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
            case 2 -> new float[][]{{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}};
            case 3 -> new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            case 4 -> new float[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
            default -> new float[][]{{x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}};
        };
    }

    static final float[][] UVS = {{0, 1}, {1, 1}, {1, 0}, {0, 0}};

    /** Writes one quad in Iroh's 16-byte compressed layout (CompressedVertexBuilder.vertex). Positions section-local. */
    static void putQuad(ByteBuffer out, float[][] c, int tile, int color, int light) {
        float u0 = (tile % GRID) / (float) GRID, v0 = (tile / GRID) / (float) GRID;
        for (int i = 0; i < 4; i++) {
            float u = u0 + UVS[i][0] / GRID, v = v0 + UVS[i][1] / GRID;
            out.putShort((short) Math.round(PlaneSnap.snap(c[i][0]) * 2048f - 8192f));
            out.putShort((short) Math.round(PlaneSnap.snap(c[i][1]) * 2048f - 8192f));
            out.putShort((short) Math.round(PlaneSnap.snap(c[i][2]) * 2048f - 8192f));
            out.putShort((short) light);
            out.putShort((short) (u * 32768f));
            out.putShort((short) (v * 32768f));
            out.putInt(color);
        }
    }

    void mesh() {
        for (int sy = 0; sy < SY; sy++)
            for (int sz = 0; sz < SZ; sz++)
                for (int sx = 0; sx < SX; sx++) {
                    SectionMesh m = new SectionMesh(sx, sy, sz);
                    for (int ly = 0; ly < 16; ly++)
                        for (int lz = 0; lz < 16; lz++)
                            for (int lx = 0; lx < 16; lx++) {
                                int x = sx * 16 + lx, y = sy * 16 + ly, z = sz * 16 + lz;
                                int b = get(x, y, z);
                                if (b == AIR) continue;
                                lightShort = village ? lightShortAt(x, y, z) : 0xF0F0;
                                if (b == TORCH || b == WLANTERN) {
                                    float a0 = b == TORCH ? 7 / 16f : 5 / 16f, a1 = b == TORCH ? 9 / 16f : 11 / 16f;
                                    if (b == TORCH) {
                                        for (int f = 1; f < 6; f++) emit(m, 0, faceCorners(f, lx + a0, ly, lz + a0, lx + a1, ly + 7 / 16f, lz + a1), T_STICK, 0xFFFFFFFF, sx, sy, sz, f);
                                        for (int f = 1; f < 6; f++) emit(m, 0, faceCorners(f, lx + a0, ly + 7 / 16f, lz + a0, lx + a1, ly + 10 / 16f, lz + a1), T_TORCH, 0xFFFFFFFF, sx, sy, sz, f);
                                    } else
                                        for (int f = 0; f < 6; f++) emit(m, 0, faceCorners(f, lx + a0, ly, lz + a0, lx + a1, ly + 6 / 16f, lz + a1), T_WLANTERN, 0xFFFFFFFF, sx, sy, sz, f);
                                    continue;
                                }
                                if (b == GLASS) {
                                    for (int f = 0; f < 6; f++) {
                                        int nb = get(x + DIRS[f][0], y + DIRS[f][1], z + DIRS[f][2]);
                                        if (nb == GLASS || opaqueCube(nb)) continue;
                                        emit(m, 1, faceCorners(f, lx, ly, lz, lx + 1, ly + 1, lz + 1), T_GLASS, 0xFFFFFFFF, sx, sy, sz, f);
                                    }
                                    continue;
                                }
                                if (b == PANE) {
                                    // thin pane in the x = 0.5 plane, both sides (vanilla panes are two-sided quads)
                                    float[][] a = faceCorners(4, lx + 0.5f, ly, lz, lx + 0.5f, ly + 1, lz + 1);
                                    float[][] bq = faceCorners(5, lx + 0.5f, ly, lz, lx + 0.5f, ly + 1, lz + 1);
                                    emit(m, 1, a, T_PANE, 0xFFFFFFFF, sx, sy, sz, 4);
                                    emit(m, 1, bq, T_PANE, 0xFFFFFFFF, sx, sy, sz, 5);
                                    continue;
                                }
                                for (int f = 0; f < 6; f++) {
                                    int nb = get(x + DIRS[f][0], y + DIRS[f][1], z + DIRS[f][2]);
                                    float top = 1;
                                    if (b == WATER) {
                                        if (nb == WATER || opaqueCube(nb)) continue;
                                        top = get(x, y + 1, z) == WATER ? 1 : WATER_TOP;
                                    } else if (opaqueCube(nb)) continue;
                                    int bucket = b == WATER ? 2 : (b == LEAVES ? 1 : 0);
                                    float[][] c = faceCorners(f, lx, ly, lz, lx + 1, ly + top, lz + 1);
                                    if (village) lightShort = lightShortAt(x + DIRS[f][0], y + DIRS[f][1], z + DIRS[f][2]);
                                    emit(m, bucket, c, tileFor(b, f), tint(b, f), sx, sy, sz, f);
                                }
                            }
                    for (int i = 0; i < 3; i++) m.buckets[i].flip();
                    sections.add(m);
                }
    }

    int lightShort = 0xF0F0;

    /** Iroh's packed vertex light short: sky level << 12 | block level << 4 (0xF0F0 = both 15). */
    int lightShortAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= W || y >= H || z >= D) return 0xF0F0;
        int i = (y * D + z) * W + x;
        return (skyLight[i] << 12) | (blockLight[i] << 4);
    }

    static int emitLevel(int b) {
        return b == GLOWSTONE || b == LANTERN || b == WLANTERN ? 15 : b == TORCH ? 14 : 0;
    }

    /** Vanilla-style light: sky 15 down open columns, then both fields flood-fill -1 per step through non-opaque cells. */
    void computeLight() {
        int n = W * H * D;
        skyLight = new int[n];
        blockLight = new int[n];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++)
                for (int y = H - 1; y >= 0; y--) {
                    int b = get(x, y, z);
                    if (opaqueCube(b)) break;
                    if (b == WATER || b == LEAVES) { skyLight[(y * D + z) * W + x] = 14; q.add(new int[]{x, y, z}); break; }
                    skyLight[(y * D + z) * W + x] = 15;
                    q.add(new int[]{x, y, z});
                }
        flood(q, skyLight);
        for (int y = 0; y < H; y++) for (int z = 0; z < D; z++) for (int x = 0; x < W; x++) {
            int l = emitLevel(get(x, y, z));
            if (l > 0) { blockLight[(y * D + z) * W + x] = l; q.add(new int[]{x, y, z}); }
        }
        flood(q, blockLight);
    }

    void flood(ArrayDeque<int[]> q, int[] lv) {
        while (!q.isEmpty()) {
            int[] c = q.poll();
            int l = lv[(c[1] * D + c[2]) * W + c[0]];
            for (int[] d : DIRS) {
                int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
                if (x < 0 || y < 0 || z < 0 || x >= W || y >= H || z >= D || opaqueCube(get(x, y, z))) continue;
                int i = (y * D + z) * W + x;
                if (lv[i] < l - 1) { lv[i] = l - 1; q.add(new int[]{x, y, z}); }
            }
        }
    }

    void box(int x0, int y0, int z0, int x1, int y1, int z1, int b) {
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) set(x, y, z, b);
    }

    /** P5-1 look world: two houses (torch-lit interior, lantern, glass), gravel path with torch posts, smooth-stone plaza
     *  with an iron block, a 3-deep pond with a sand rim, three oak trees, a hill with a torch-lit cave mouth. */
    void generateVillage() {
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++) {
                double hill = Math.max(0, 14 - Math.hypot((x - VPAD - 46) * 0.8, (z - VPAD - 40) * 0.7) * 0.75);
                // P5-2 padding: gentle far rises outside the village so the horizon is terrain, not the world's edge
                double far = Math.max(0, Math.max(Math.max(VPAD - x, x - (VPAD + 47)), Math.max(VPAD - z, z - (VPAD + 47))));
                hill = Math.max(hill, far * 0.35 + 1.2 * Math.sin(x * 0.37) * Math.sin(z * 0.29) * Math.min(1, far / 4));
                int h = 8 + (int) Math.round(hill);
                for (int y = 0; y < h; y++) set(x, y, z, y < h - 3 ? STONE : y < h - 1 ? DIRT : GRASS);
            }
        gox = goz = VPAD;
        try {
            villageContent();
        } finally {
            gox = goz = 0;
        }
    }

    void villageContent() {
        // cave mouth into the hill from the west, torch-lit, glowstone at the end
        box(30, 8, 33, 47, 11, 37, AIR);
        box(30, 7, 33, 47, 7, 37, STONE);
        for (int x = 34; x <= 46; x += 5) { set(x, 8, 33, TORCH); set(x + 2, 8, 37, TORCH); }
        set(46, 9, 35, GLOWSTONE);
        // house A: cobble foundation, planks walls, log corners, roof, door south, glass windows, torches + lantern inside
        box(5, 8, 5, 16, 16, 15, AIR);
        box(6, 8, 6, 15, 8, 14, COBBLE);
        for (int y = 9; y <= 11; y++) for (int x = 6; x <= 15; x++) for (int z = 6; z <= 14; z++)
            if (x == 6 || x == 15 || z == 6 || z == 14) set(x, y, z, (x == 6 || x == 15) && (z == 6 || z == 14) ? LOG : PLANKS);
        box(5, 12, 5, 16, 12, 15, ROOF);
        box(6, 13, 6, 15, 13, 14, ROOF);
        box(7, 14, 8, 14, 14, 12, ROOF);
        set(10, 9, 14, AIR);
        set(10, 10, 14, AIR);
        set(11, 9, 14, AIR);
        set(11, 10, 14, AIR);
        for (int z = 8; z <= 12; z += 4) { set(6, 10, z, GLASS); set(15, 10, z, GLASS); }
        set(13, 10, 6, GLASS);
        set(8, 10, 6, GLASS);
        set(7, 9, 7, TORCH);
        set(14, 9, 7, TORCH);
        set(10, 9, 10, PLANKS);
        set(10, 10, 10, WLANTERN);
        // house B: small cobble hut, torch on a post by the door
        box(22, 8, 7, 28, 12, 13, AIR);
        box(23, 8, 8, 27, 8, 12, COBBLE);
        for (int y = 9; y <= 10; y++) for (int x = 23; x <= 27; x++) for (int z = 8; z <= 12; z++)
            if (x == 23 || x == 27 || z == 8 || z == 12) set(x, y, z, COBBLE);
        box(22, 11, 7, 28, 11, 13, ROOF);
        set(25, 9, 12, AIR);
        set(25, 10, 12, AIR);
        set(24, 9, 10, TORCH);
        set(27, 8, 14, LOG);
        set(27, 9, 14, TORCH);
        // gravel path with torch posts
        for (int x = 9; x <= 31; x++) for (int z = 17; z <= 18; z++) set(x, 7, z, GRAVEL);
        for (int z = 15; z <= 16; z++) { set(10, 7, z, GRAVEL); set(11, 7, z, GRAVEL); }
        set(14, 8, 19, LOG);
        set(14, 9, 19, TORCH);
        set(28, 8, 19, LOG);
        set(28, 9, 19, TORCH);
        // smooth-stone plaza with an iron block statue and a polished andesite ring
        box(16, 7, 20, 22, 7, 24, SMOOTH);
        for (int x = 16; x <= 22; x++) { set(x, 7, 20, POLISHED); set(x, 7, 24, POLISHED); }
        set(19, 8, 22, IRON);
        set(19, 9, 22, IRON);
        // pond, 3 deep, sand rim and bed
        for (int x = 6; x <= 20; x++)
            for (int z = 24; z <= 38; z++) {
                double r = Math.hypot((x - 13) / 6.0, (z - 31) / 5.0);
                if (r > 1.25) continue;
                if (r > 1.0) { if (get(x, 8, z) == AIR) set(x, 7, z, SAND); continue; }
                box(x, 8, z, x, 12, z, AIR);
                set(x, 3, z, SAND);
                set(x, 4, z, WATER);
                set(x, 5, z, WATER);
                set(x, 6, z, WATER);
                set(x, 7, z, WATER);
            }
        // three oak trees
        int[][] trees = {{3, 21}, {22, 44}, {33, 6}};
        for (int[] t : trees) {
            int tx = t[0], tz = t[1];
            for (int y = 8; y <= 12; y++) set(tx, y, tz, LOG);
            for (int x = tx - 2; x <= tx + 2; x++)
                for (int z = tz - 2; z <= tz + 2; z++)
                    for (int y = 11; y <= 14; y++)
                        if (get(x, y, z) == AIR && Math.hypot(Math.hypot(x - tx, z - tz), (y - 12.5) * 1.3) <= 2.5) set(x, y, z, LEAVES);
            set(tx, 13, tz, LEAVES);
            set(tx, 14, tz, LEAVES);
        }
    }

    void emit(SectionMesh m, int bucket, float[][] c, int tile, int color, int sx, int sy, int sz, int face) {
        putQuad(m.buckets[bucket], c, tile, color, lightShort);
        m.quads[bucket]++;
        Material mat = materials[tile];
        if (mat.emissive()) {
            float ox = sx * 16, oy = sy * 16, oz = sz * 16;
            float[] p0 = {c[0][0] + ox, c[0][1] + oy, c[0][2] + oz};
            float[] e1 = {c[1][0] - c[0][0], c[1][1] - c[0][1], c[1][2] - c[0][2]};
            float[] e2 = {c[3][0] - c[0][0], c[3][1] - c[0][1], c[3][2] - c[0][2]};
            float[] n = {DIRS[face][0], DIRS[face][1], DIRS[face][2]};
            float[] cr = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
            float area = (float) Math.sqrt(cr[0] * cr[0] + cr[1] * cr[1] + cr[2] * cr[2]);
            lights.add(new Light(p0, e1, e2, n, area, new float[]{mat.er, mat.eg, mat.eb}));
        }
    }

    // ------------------------------------------------------------------------------------------------ CPU view

    /** Decoded triangle soup (exact quantised positions, as the GPU sees them) for the CPU reference. */
    public static final class Tris {
        public float[] p;      // 9 per triangle, world
        public float[] uv;     // 6 per triangle
        public int[] tile, tint;
        public boolean[] translucent;
        public int n;
    }

    /** Atlas tile of a triangle: from its uv centroid (every corner sits on a tile border; the centroid never does). */
    static int tileOf(float[] uv, int k) {
        float uc = (uv[k * 6] + uv[k * 6 + 2] + uv[k * 6 + 4]) / 3f, vc = (uv[k * 6 + 1] + uv[k * 6 + 3] + uv[k * 6 + 5]) / 3f;
        return (int) (uc * GRID) + GRID * (int) (vc * GRID);
    }

    public static float decodePos(short s) {
        return s / 2048f + 4f;
    }

    public Tris triangles() {
        Tris t = new Tris();
        int total = 0;
        for (SectionMesh m : sections) total += m.totalQuads() * 2;
        t.p = new float[total * 9];
        t.uv = new float[total * 6];
        t.tile = new int[total];
        t.tint = new int[total];
        t.translucent = new boolean[total];
        int k = 0;
        int[][] tri = {{0, 1, 2}, {2, 3, 0}};
        for (SectionMesh m : sections)
            for (int bkt = 0; bkt < 3; bkt++) {
                ByteBuffer b = m.buckets[bkt];
                for (int q = 0; q < m.quads[bkt]; q++)
                    for (int[] tr : tri) {
                        for (int c = 0; c < 3; c++) {
                            int o = (q * 4 + tr[c]) * 16;
                            t.p[k * 9 + c * 3] = decodePos(b.getShort(o)) + m.sx * 16;
                            t.p[k * 9 + c * 3 + 1] = decodePos(b.getShort(o + 2)) + m.sy * 16;
                            t.p[k * 9 + c * 3 + 2] = decodePos(b.getShort(o + 4)) + m.sz * 16;
                            t.uv[k * 6 + c * 2] = (b.getShort(o + 8) & 0xFFFF) / 32768f;
                            t.uv[k * 6 + c * 2 + 1] = (b.getShort(o + 10) & 0xFFFF) / 32768f;
                        }
                        int o = q * 4 * 16;
                        t.tile[k] = tileOf(t.uv, k);
                        t.tint[k] = b.getInt(o + 12);
                        t.translucent[k] = bkt == 2;
                        k++;
                    }
            }
        t.n = k;
        return t;
    }
}
