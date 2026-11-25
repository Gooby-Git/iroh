package dev.elemore.iroh.lod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * One-time import of the Vista mod's config into Iroh's Distant Terrain settings (card VL-0, docs/VISTA-IN-IROH.md
 * section 3). Runs when {@code config/iroh.json} has no {@code lod.*} keys yet; the Vista files are only read, never
 * edited or deleted, so the old mod still works. Every key maps 1:1 ({@code xaeroFill} becomes {@code lod.shareWithMaps});
 * {@code vista-server.json} supplies the {@code lod.server.*} rows and the landmark list, which goes to
 * {@code config/iroh-lod-server.json}; {@code citadelLandmarks} is dropped; {@code -Dvista.cpuThreads} becomes
 * {@code lod.workerThreads}. Values are clamped to the row ranges; one log line per key that changed.
 */
public final class LodMigration {
    public static final String VISTA_FILE = "vista.json";
    public static final String VISTA_SERVER_FILE = "vista-server.json";
    public static final String SERVER_FILE = "iroh-lod-server.json";
    /** Key whose absence in iroh.json means "never migrated". */
    public static final String MARKER_KEY = "lod.enabled";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LodMigration() {}

    /**
     * Called from {@code IrohOptions.load}. Returns true when iroh.json lacked the lod keys (the caller then saves, which
     * writes defaults or the imported values); false when the keys exist or the file is unreadable (nothing touched).
     */
    public static boolean apply(Path dir, List<String> log) {
        Path file = dir.resolve("iroh.json");
        if (Files.isRegularFile(file)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has(MARKER_KEY)) return false;
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }
        String threads = System.getProperty("vista.cpuThreads");
        migrate(dir, LodConfig.INSTANCE, threads, log);
        return true;
    }

    /** Fills {@code c} from dir/vista.json and dir/vista-server.json (either may be missing). */
    public static void migrate(Path dir, LodConfig c, String cpuThreadsProperty, List<String> log) {
        JsonObject v = read(dir.resolve(VISTA_FILE), log);
        if (v != null) {
            c.enabled = bool(v, "enabled", c.enabled, "lod.enabled", log);
            c.distance = num(v, "distance", c.distance, LodConfig.DISTANCE_MIN, LodConfig.DISTANCE_MAX, "lod.distance", log);
            c.budgetMiB = budget(v, c.budgetMiB, log);
            c.detailDistance = num(v, "detailDistance", c.detailDistance, LodConfig.DETAIL_MIN, LodConfig.DETAIL_MAX, "lod.detailDistance", log);
            c.blendWidth = num(v, "blendWidth", c.blendWidth, 0, LodConfig.BLEND_MAX, "lod.blendWidth", log);
            c.transitionMs = num(v, "transitionMs", c.transitionMs, 0, LodConfig.TRANSITION_MAX, "lod.transitionMs", log);
            c.occlusionCulling = bool(v, "occlusionCulling", c.occlusionCulling, "lod.occlusionCulling", log);
            c.landmarks = bool(v, "landmarks", c.landmarks, "lod.landmarks", log);
            c.brightness = num(v, "brightness", c.brightness, LodConfig.BRIGHTNESS_MIN, LodConfig.BRIGHTNESS_MAX, "lod.brightness", log);
            c.saturation = num(v, "saturation", c.saturation, 0, LodConfig.SATURATION_MAX, "lod.saturation", log);
            c.nightLights = bool(v, "nightLights", c.nightLights, "lod.nightLights", log);
            c.motion = bool(v, "motion", c.motion, "lod.motion", log);
            c.fogDistance = num(v, "fogDistance", c.fogDistance, LodConfig.FOG_MIN, LodConfig.FOG_MAX, "lod.fogDistance", log);
            c.weatherOnLods = bool(v, "weatherOnLods", c.weatherOnLods, "lod.weatherOnLods", log);
            c.weatherCurtains = bool(v, "weatherCurtains", c.weatherCurtains, "lod.weatherCurtains", log);
            c.farPlayers = bool(v, "farPlayers", c.farPlayers, "lod.farPlayers", log);
            c.farPlayerMinPixels = num(v, "farPlayerMinPixels", c.farPlayerMinPixels, 0, LodConfig.MIN_PIXELS_MAX, "lod.farPlayerMinPixels", log);
            c.nameTagDistance = num(v, "nameTagDistance", c.nameTagDistance, 0, LodConfig.NAME_TAG_MAX, "lod.nameTagDistance", log);
            c.seedLods = bool(v, "seedLods", c.seedLods, "lod.seedLods", log);
            c.shareWithMaps = bool(v, "xaeroFill", c.shareWithMaps, "lod.shareWithMaps (was xaeroFill)", log);
            log.add("lod: imported " + VISTA_FILE + " (kept in place)");
        }
        if (cpuThreadsProperty != null) {
            try {
                int t = LodConfig.clamp(Integer.parseInt(cpuThreadsProperty.trim()), 0, LodConfig.THREADS_MAX);
                if (t != c.workerThreads) log.add("lod: lod.workerThreads " + c.workerThreads + " -> " + t + " (from -Dvista.cpuThreads)");
                c.workerThreads = t;
            } catch (NumberFormatException e) {
                log.add("lod: ignored -Dvista.cpuThreads=" + cpuThreadsProperty);
            }
        }
        JsonObject s = read(dir.resolve(VISTA_SERVER_FILE), log);
        if (s != null) {
            c.serverStructurePass = bool(s, "structurePass", c.serverStructurePass, "lod.server.structurePass", log);
            c.serverStructureRange = num(s, "structureRange", c.serverStructureRange, 0, LodConfig.STRUCTURE_RANGE_MAX, "lod.server.structureRange", log);
            c.serverShareSeedTerrain = bool(s, "shareSeedTerrain", c.serverShareSeedTerrain, "lod.server.shareSeedTerrain", log);
            c.serverSharePlayerPositions = bool(s, "sharePlayerPositions", c.serverSharePlayerPositions, "lod.server.sharePlayerPositions", log);
            if (s.has("citadelLandmarks")) log.add("lod: dropped citadelLandmarks (landmark providers register through the API)");
            JsonElement lm = s.get("landmarks");
            Path out = dir.resolve(SERVER_FILE);
            if (lm != null && lm.isJsonArray() && !Files.exists(out)) {
                JsonObject o = new JsonObject();
                o.add("landmarks", lm.getAsJsonArray());
                try {
                    Files.writeString(out, GSON.toJson(o) + "\n", StandardCharsets.UTF_8);
                    log.add("lod: wrote " + SERVER_FILE + " (" + lm.getAsJsonArray().size() + " landmarks)");
                } catch (IOException e) {
                    log.add("lod: could not write " + SERVER_FILE + ": " + e);
                }
            }
            log.add("lod: imported " + VISTA_SERVER_FILE + " (kept in place)");
        }
    }

    private static JsonObject read(Path p, List<String> log) {
        if (!Files.isRegularFile(p)) return null;
        try {
            return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            log.add("lod: could not read " + p.getFileName() + ": " + e);
            return null;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean cur, String row, List<String> log) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !((JsonPrimitive) e).isBoolean()) return cur;
        boolean v = e.getAsBoolean();
        if (v != cur) log.add("lod: " + row + " " + cur + " -> " + v);
        return v;
    }

    private static int num(JsonObject o, String key, int cur, int min, int max, String row, List<String> log) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !((JsonPrimitive) e).isNumber()) return cur;
        int raw = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, e.getAsLong()));
        int v = LodConfig.clamp(raw, min, max);
        if (v != raw) log.add("lod: " + row + " " + raw + " clamped to " + v);
        if (v != cur) log.add("lod: " + row + " " + cur + " -> " + v);
        return v;
    }

    private static int budget(JsonObject o, int cur, List<String> log) {
        JsonElement e = o.get("budgetMiB");
        if (e == null || !e.isJsonPrimitive() || !((JsonPrimitive) e).isNumber()) return cur;
        int raw = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, e.getAsLong()));
        int v = LodConfig.normaliseBudget(raw);
        if (v != raw) log.add("lod: lod.budgetMiB " + raw + " normalised to " + v);
        if (v != cur) log.add("lod: lod.budgetMiB " + cur + " -> " + v);
        return v;
    }
}
