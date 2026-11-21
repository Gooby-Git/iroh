package dev.elemore.iroh.shaderpack.graph;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.elemore.iroh.shaderpack.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Geometry requirements (P3-2 item 4, RQ-5): what each geometry class's program reads from its vertices, against what
 * Iroh's 16-byte compressed terrain vertex carries, and the wide pack terrain vertex format the Phase 3
 * implementation card builds ({@link #WIDE_TERRAIN}). Data only; no renderer code is changed here.
 * <p>
 * Today's terrain vertex (net.vulkanmod.render.vertex.VertexBuilder.CompressedVertexBuilder, 16 B): position 3 x int16
 * at 1/2048 block with a -4 block offset (PlaneSnap rounding, rc10), light int16 (sky high byte, block low byte),
 * uv 2 x int16 at 1/32768, colour RGBA8 with shade and AO multiplied in. No normal, no block id, no tangent, no
 * mid-texcoord, no mid-block, AO not separable.
 */
public final class GeometryNeeds {

    public record Field(String name, int offset, int bytes, String encoding, String feeds, String source) {}

    /** The wide terrain vertex (36 B), used only while the PACK (or RT) path is active (RQ-5 triage). */
    public static final List<Field> WIDE_TERRAIN = List.of(
            new Field("position", 0, 6, "int16 x3, 1/2048 block, -4 block offset (same grid and PlaneSnap as today)",
                    "gl_Vertex / vaPosition (+ chunkOffset)", "mesher float position"),
            new Field("light", 6, 2, "uint8 block, uint8 sky, light level x 16 + 8 (unbaked, vanilla lightmap coordinate)",
                    "gl_MultiTexCoord1/2, vaUV2", "smooth/flat light pipeline before baking"),
            new Field("uv0", 8, 4, "unorm16 x2 (same 1/32768 scale as today)", "gl_MultiTexCoord0, vaUV0", "sprite uv"),
            new Field("colour", 12, 4, "RGBA8: vertex tint x face shade; with separateAo=true alpha = AO, else AO multiplied into rgb (Iris rule)",
                    "gl_Color, vaColor", "block colour + shade + AO"),
            new Field("normal", 16, 4, "snorm8 x3 + pad (unpacked face or quad normal, model space)", "gl_Normal, vaNormal", "quad normal"),
            new Field("tangent", 20, 4, "snorm8 x3 tangent + snorm8 handedness (at_tangent.w)", "at_tangent",
                    "per-quad from positions and uvs (mesher)"),
            new Field("midTexCoord", 24, 4, "unorm16 x2, sprite centre", "mc_midTexCoord", "sprite bounds"),
            new Field("midBlock", 28, 4, "snorm8 x3 = (block centre - vertex) x 64 (+-2 blocks), uint8 light emission",
                    "at_midBlock (.w = emission, IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE)", "unsnapped float position, BlockState.getLightEmission"),
            new Field("entity", 32, 4, "uint16 block id from block.properties, uint8 render type (Iris mc_Entity.y), uint8 pad",
                    "mc_Entity.xy", "block.properties id map, resolved per BlockState at pack load into a table the mesher reads"));

    public static final int WIDE_TERRAIN_BYTES = 36;

    /** program -> referenced vertex inputs (name:type) */
    public final Map<String, Set<String>> byProgram = new TreeMap<>();
    /** geometry class -> program per folder */
    public final Map<String, Map<String, String>> classes = new TreeMap<>();
    public final Map<String, Set<String>> fieldUsers = new TreeMap<>();
    public final List<String> notes = new ArrayList<>();

    public static GeometryNeeds build(PackData d, Map<String, PassGraph.Frame> frames) {
        GeometryNeeds g = new GeometryNeeds();
        for (Map.Entry<String, PassGraph.Frame> e : frames.entrySet())
            for (PassGraph.Pass p : e.getValue().passes) {
                if (!p.kind.equals("geometry"))
                    continue;
                for (String c : p.geometry)
                    g.classes.computeIfAbsent(c, k -> new TreeMap<>()).put(e.getKey(), p.program);
                PackData.Unit u = d.units.get(p.unit);
                if (u == null)
                    continue;
                JsonObject r = u.reflection();
                Set<String> in = g.byProgram.computeIfAbsent(p.program, k -> new TreeSet<>());
                if (r.has("vertexInputs"))
                    for (JsonElement v : r.getAsJsonArray("vertexInputs")) {
                        JsonObject o = v.getAsJsonObject();
                        String n = o.has("compat") ? o.get("compat").getAsString() : o.get("name").getAsString();
                        in.add(n + ":" + o.get("type").getAsString());
                        String field = fieldOf(n);
                        if (field != null && isTerrain(p.geometry))
                            g.fieldUsers.computeIfAbsent(field, k -> new TreeSet<>()).add(p.program);
                    }
            }
        g.notes.add("wide terrain vertex " + WIDE_TERRAIN_BYTES + " B vs 16 B today: x2.25 section vertex memory and upload bytes while PACK/RT is active; "
                + "a quad costs 144 B instead of 64 B. The staging ring's ~1 ms per frame section budget then moves 2.25x fewer vertices per frame.");
        g.notes.add("PlaneSnap is unchanged (same 1/2048 grid); midBlock is computed from the UNSNAPPED float position so voxelisation "
                + "(Navidson shadow pass writes voxel_img from at_midBlock) lands in the right block.");
        g.notes.add("Raster keeps the 16 B format (RQ-5 triage); the mesher writes one format per section build, chosen by the active path; "
                + "a path switch rebuilds all sections (allChanged).");
        g.notes.add("Entities, hand and block entities: vanilla NEW_ENTITY format plus mc_Entity (entity id from entity.properties / "
                + "block entity id from block.properties), at_tangent, mc_midTexCoord and at_velocity (previous position delta) as extra "
                + "elements in Iroh's entity vertex path; terrain needs no previous position (static: motion vectors from the "
                + "camera matrices alone).");
        g.notes.add("Lighting values are needed unbaked: AO separate from colour when separateAo=true, light as lightmap coordinates; "
                + "today's colour carries shade x AO and is not separable.");
        return g;
    }

    static boolean isTerrain(List<String> geometry) {
        for (String c : geometry)
            if (c.startsWith("terrain") || c.startsWith("shadow_terrain") || c.equals("shadow_translucent"))
                return true;
        return false;
    }

    static String fieldOf(String input) {
        return switch (input) {
            case "gl_Vertex", "vaPosition" -> "position";
            case "gl_MultiTexCoord1", "gl_MultiTexCoord2", "vaUV1", "vaUV2" -> "light";
            case "gl_MultiTexCoord0", "vaUV0" -> "uv0";
            case "gl_Color", "vaColor" -> "colour";
            case "gl_Normal", "vaNormal" -> "normal";
            case "at_tangent" -> "tangent";
            case "mc_midTexCoord" -> "midTexCoord";
            case "at_midBlock" -> "midBlock";
            case "mc_Entity" -> "entity";
            default -> null;
        };
    }

    /** vertex input of a pipeline drawing these geometry classes */
    static String vertexFormatOf(List<String> classes) {
        if (classes.isEmpty())
            return "none";
        String c = classes.get(0);
        if (c.startsWith("terrain") || c.startsWith("shadow_terrain") || c.equals("shadow_translucent"))
            return "IrohPackTerrain " + WIDE_TERRAIN_BYTES + " B (wide terrain vertex)";
        if (c.startsWith("lod_vista"))
            return "none: Vista pulls quads from SSBOs (bindings 21-26)";
        return switch (c) {
            case "sky_basic" -> "POSITION / POSITION_COLOR (vanilla sky buffers)";
            case "sky_textured" -> "POSITION_TEX (sun, moon)";
            case "clouds" -> "POSITION_TEX_COLOR_NORMAL (vanilla clouds)";
            case "weather", "particles_opaque", "particles_translucent" -> "PARTICLE (POSITION_TEX_COLOR_LIGHT)";
            case "outline" -> "POSITION_COLOR_NORMAL (lines)";
            case "world_border" -> "POSITION_TEX";
            default -> "NEW_ENTITY + mc_Entity, at_tangent, mc_midTexCoord, at_velocity";
        };
    }

    public Object toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> f = new ArrayList<>();
        for (Field x : WIDE_TERRAIN)
            f.add(Json.obj("field", x.name(), "offset", x.offset(), "bytes", x.bytes(), "encoding", x.encoding(), "feeds", x.feeds(),
                    "source", x.source(), "programs", new ArrayList<>(fieldUsers.getOrDefault(x.name(), Set.of()))));
        m.put("wideTerrainVertex", Json.obj("bytes", WIDE_TERRAIN_BYTES, "fields", f));
        Map<String, Object> bp = new LinkedHashMap<>();
        byProgram.forEach((k, v) -> bp.put(k, new ArrayList<>(v)));
        m.put("vertexInputsByProgram", bp);
        m.put("classes", classes);
        m.put("notes", notes);
        return m;
    }

    public String toText() {
        StringBuilder sb = new StringBuilder("-- geometry needs (vertex inputs per program)\n");
        byProgram.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
        sb.append("  wide terrain vertex (").append(WIDE_TERRAIN_BYTES).append(" B):\n");
        for (Field x : WIDE_TERRAIN)
            sb.append(String.format("    +%-2d %-12s %d B  %s  <- %s  used by %s%n", x.offset(), x.name(), x.bytes(), x.encoding(), x.source(),
                    fieldUsers.getOrDefault(x.name(), Set.of())));
        for (String n : notes)
            sb.append("  note: ").append(n).append('\n');
        return sb.toString();
    }
}
