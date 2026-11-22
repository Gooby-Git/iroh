package dev.elemore.iroh.shaderpack.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The P3-1 front end's output, read back from {@code build/packHarness/<pack>/} (schema iroh.pack.v1): the pass graph
 * is built against this data contract only, never against the front end's Java objects, so a plan can be recomputed
 * from a dump and a dump diff explains a plan diff.
 */
public final class PackData {

    /** One compiled unit (raster program or one compute file) from programs/<dir>/<name>.json. */
    public static final class Unit {
        public final JsonObject json;
        public final String id, dir, program;
        public final boolean compute, enabled, passed;

        Unit(JsonObject j) {
            json = j;
            id = j.get("id").getAsString();
            dir = j.get("dir").getAsString();
            program = j.get("program").getAsString();
            compute = j.get("compute").getAsBoolean();
            enabled = j.get("enabled").getAsBoolean();
            passed = j.get("result").getAsString().equals("PASS");
        }

        public JsonObject reflection() {
            return json.has("reflection") ? json.getAsJsonObject("reflection") : new JsonObject();
        }

        public Map<String, String> constDirectives() {
            Map<String, String> m = new LinkedHashMap<>();
            if (json.has("constDirectives"))
                json.getAsJsonObject("constDirectives").entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsString()));
            return m;
        }

        /** draw buffers: the directive, or for no directive the declared outputs' locations (see PassGraph). */
        public List<Integer> drawBuffers() {
            JsonObject r = reflection();
            if (r.has("drawBuffers") && r.get("drawBuffers").isJsonArray()) {
                List<Integer> l = new ArrayList<>();
                for (JsonElement e : r.getAsJsonArray("drawBuffers"))
                    l.add(e.getAsInt());
                return l;
            }
            return null;
        }

        public int fragmentOutputCount() {
            JsonObject r = reflection();
            if (!r.has("fragmentOutputs"))
                return 0;
            int max = -1;
            for (JsonElement e : r.getAsJsonArray("fragmentOutputs"))
                max = Math.max(max, e.getAsJsonObject().get("location").getAsInt());
            return max + 1;
        }

        /** referenced samplers and images: name -> type */
        public Map<String, String> sampled() {
            Map<String, String> m = new TreeMap<>();
            JsonObject r = reflection();
            for (String k : new String[]{"samplers", "images"})
                if (r.has(k))
                    for (JsonElement e : r.getAsJsonArray(k)) {
                        JsonObject b = e.getAsJsonObject();
                        if (!b.has("referenced") || b.get("referenced").getAsBoolean())
                            m.put(b.get("name").getAsString(), b.get("type").getAsString());
                    }
            return m;
        }

        public Map<String, String> images() {
            Map<String, String> m = new TreeMap<>();
            JsonObject r = reflection();
            if (r.has("images"))
                for (JsonElement e : r.getAsJsonArray("images")) {
                    JsonObject b = e.getAsJsonObject();
                    if (!b.has("referenced") || b.get("referenced").getAsBoolean())
                        m.put(b.get("name").getAsString(), b.has("format") ? b.get("format").getAsString() : "?");
                }
            return m;
        }

        public int[] localSize() {
            JsonObject r = reflection();
            if (!r.has("localSize"))
                return null;
            JsonArray a = r.getAsJsonArray("localSize");
            return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
        }
    }

    public final String name;
    public final JsonObject pack;
    /** the dump folder (build/packHarness/<pack>/) */
    public Path dump;
    public final Map<String, Unit> units = new TreeMap<>();

    private PackData(String name, JsonObject pack) {
        this.name = name;
        this.pack = pack;
    }

    public static PackData load(Path dump) throws IOException {
        JsonObject pack = JsonParser.parseString(Files.readString(dump.resolve("pack.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        PackData d = new PackData(pack.get("pack").getAsString(), pack);
        d.dump = dump;
        Path progs = dump.resolve("programs");
        if (Files.isDirectory(progs)) {
            try (Stream<Path> s = Files.walk(progs)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                    Unit u = new Unit(JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject());
                    d.units.put(u.id, u);
                }
            }
        }
        return d;
    }

    /** SPIR-V of one stage of a unit (vertex, fragment, compute), from the dump */
    public byte[] spirv(Unit u, String stage) throws IOException {
        String rel = u.id.contains("/") ? u.id : "_root/" + u.id;
        return Files.readAllBytes(dump.resolve("spirv").resolve(rel + "." + stage + ".spv"));
    }

    /** where the pack itself lies (pack.json "origin"), for custom texture files */
    public String origin() {
        return pack.get("origin").getAsString();
    }

    public JsonObject properties() {
        return pack.getAsJsonObject("properties");
    }

    public JsonObject folders() {
        return pack.getAsJsonObject("programs").getAsJsonObject("folders");
    }

    public String flag(String key) {
        JsonObject f = properties().getAsJsonObject("flags");
        return f.has(key) ? f.get(key).getAsString() : null;
    }

    /** raster unit of {@code program} in {@code dir} ("" root) */
    public Unit raster(String dir, String program) {
        String id = dir.isEmpty() ? program : dir + "/" + program;
        Unit u = units.get(id);
        return u != null && !u.compute ? u : null;
    }

    /** compute units of a program, in dispatch order (name.csh, then _a.._z, as the front end listed them) */
    public List<Unit> computes(String dir, String program) {
        List<Unit> l = new ArrayList<>();
        JsonObject f = folders().getAsJsonObject(dir.isEmpty() ? "<root>" : dir);
        if (f == null)
            return l;
        JsonObject p = f.getAsJsonObject("programs").getAsJsonObject(program);
        if (p == null)
            return l;
        for (JsonElement c : p.getAsJsonArray("computes")) {
            String path = c.getAsString();
            String file = path.substring(path.lastIndexOf('/') + 1);
            Unit u = units.get(dir.isEmpty() ? file : dir + "/" + file);
            if (u != null)
                l.add(u);
        }
        return l;
    }

    /** program names present in a folder (raster or compute) with their enabled flag */
    public Map<String, Boolean> programs(String dir) {
        Map<String, Boolean> m = new TreeMap<>();
        JsonObject f = folders().getAsJsonObject(dir.isEmpty() ? "<root>" : dir);
        if (f != null)
            f.getAsJsonObject("programs").entrySet().forEach(e ->
                    m.put(e.getKey(), e.getValue().getAsJsonObject().get("enabled").getAsBoolean()));
        return m;
    }
}
