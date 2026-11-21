package dev.elemore.iroh.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.vulkanmod.config.Config;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * One-time seeding of Iroh's options from the settings Sodium, Sodium Extra, Iris and Cull Fewer Leaves left in the
 * pack's config folder (they still describe what the pack looked like; see knowledge/pack-compat.md section 3).
 * Runs only when config/iroh.json does not exist yet. The old files are read, never changed. A value is only carried over
 * when the option is still at its default after the VulkanMod migration, so a value the user set in
 * vulkanmod_settings.json wins over the Sodium one (builderThreads is the only overlap).
 */
public final class PackMigration {
    private PackMigration() {}

    public static void apply(Path dir, Config c, List<String> log) {
        sodium(dir.resolve("sodium-options.json"), c, log);
        sodiumExtra(dir.resolve("sodium-extra-options.json"), c, log);
        iris(dir.resolve("iris.properties"), c, log);
        cullFewerLeaves(dir.resolve("cull-fewer-leaves.toml"), c, log);
    }

    private static JsonObject readJson(Path f, List<String> log) {
        if (!Files.isRegularFile(f)) return null;
        try {
            return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            log.add("could not read " + f.getFileName() + ": " + e);
            return null;
        }
    }

    private static JsonElement path(JsonObject o, String... keys) {
        JsonElement e = o;
        for (String k : keys) {
            if (e == null || !e.isJsonObject()) return null;
            e = e.getAsJsonObject().get(k);
        }
        return e == null || e.isJsonNull() ? null : e;
    }

    private static void sodium(Path f, Config c, List<String> log) {
        JsonObject j = readJson(f, log);
        if (j == null) return;
        JsonElement e = path(j, "quality", "leaves_quality");
        if (e != null) {
            try {
                c.leavesQuality = IrohOptions.LeavesQuality.valueOf(e.getAsString().toUpperCase(Locale.ROOT)).name();
                log.add("sodium leaves_quality -> leavesQuality " + c.leavesQuality);
            } catch (RuntimeException x) { log.add("sodium leaves_quality unreadable: " + e); }
        }
        e = path(j, "quality", "enable_vignette");
        if (e != null) { c.enableVignette = e.getAsBoolean(); log.add("sodium enable_vignette -> enableVignette " + c.enableVignette); }
        e = path(j, "performance", "chunk_builder_threads");
        if (e != null && c.builderThreads == 0) {
            int n = Math.max(0, Math.min(Math.max(1, Runtime.getRuntime().availableProcessors() - 1), e.getAsInt()));
            c.builderThreads = n;
            log.add("sodium chunk_builder_threads " + e.getAsInt() + " -> builderThreads " + n);
        }
        e = path(j, "performance", "chunk_build_defer_mode");
        if (e != null) {
            try {
                c.chunkBuildDeferMode = IrohOptions.DeferMode.valueOf(e.getAsString().toUpperCase(Locale.ROOT)).name();
                log.add("sodium chunk_build_defer_mode -> chunkBuildDeferMode " + c.chunkBuildDeferMode);
            } catch (RuntimeException x) { log.add("sodium chunk_build_defer_mode unreadable: " + e); }
        }
    }

    private static void sodiumExtra(Path f, Config c, List<String> log) {
        JsonObject j = readJson(f, log);
        if (j == null) return;
        JsonElement e = path(j, "extra_settings", "show_fps");
        if (e != null) { c.showFps = e.getAsBoolean(); log.add("sodium-extra show_fps -> showFps " + c.showFps); }
        e = path(j, "render_settings", "fog_settings", "atmospheric", "distance_chunks");
        if (e != null) {
            int n = e.getAsInt();
            c.fogDistanceChunks = Math.max(-1, n);
            log.add("sodium-extra atmospheric.distance_chunks " + n + " -> fogDistanceChunks " + c.fogDistanceChunks
                    + " (per-dimension overrides are not carried over)");
        }
    }

    private static void iris(Path f, Config c, List<String> log) {
        if (!Files.isRegularFile(f)) return;
        try {
            Properties p = new Properties();
            p.load(new StringReader(Files.readString(f, StandardCharsets.UTF_8)));
            String pack = p.getProperty("shaderPack");
            if (pack != null && !pack.isBlank()) {
                c.shaderPack = pack.trim();
                // Iris's own default for enableShaders is true.
                c.shadersEnabled = Boolean.parseBoolean(p.getProperty("enableShaders", "true").trim());
                log.add("iris shaderPack -> shaderPack " + c.shaderPack + ", shadersEnabled " + c.shadersEnabled);
            }
        } catch (IOException | RuntimeException e) {
            log.add("could not read iris.properties: " + e);
        }
    }

    /** Minimal reader for the flat "key = value" TOML of Cull Fewer Leaves (comments start with #). */
    private static void cullFewerLeaves(Path f, Config c, List<String> log) {
        if (!Files.isRegularFile(f)) return;
        try {
            boolean enabled = true;
            Integer depth = null, rejection = null;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).strip(), v = line.substring(eq + 1).strip();
                switch (k) {
                    case "enabled" -> enabled = Boolean.parseBoolean(v);
                    case "depth" -> depth = Integer.parseInt(v);
                    case "randomRejection" -> rejection = Integer.parseInt(v);
                    default -> { }
                }
            }
            if (!enabled) c.leafCullingDepth = 0;
            else if (depth != null) c.leafCullingDepth = Math.max(1, Math.min(4, depth));
            if (rejection != null) c.leafCullingRejection = Math.max(0, Math.min(100, rejection));
            log.add("cull-fewer-leaves enabled=" + enabled + " depth=" + depth + " randomRejection=" + rejection
                    + " -> leafCullingDepth " + c.leafCullingDepth + ", leafCullingRejection " + c.leafCullingRejection);
        } catch (IOException | RuntimeException e) {
            log.add("could not read cull-fewer-leaves.toml: " + e);
        }
    }
}
