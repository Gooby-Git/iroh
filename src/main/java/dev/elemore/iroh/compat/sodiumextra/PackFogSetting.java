package dev.elemore.iroh.compat.sodiumextra;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.Initializer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The pack's atmospheric-fog setting. The Elemore pack ships `config/sodium-extra-options.json` with
 * `render_settings.fog_settings.atmospheric.distance_chunks = -1`, which in the pack means "no terrain fog"; Sodium Extra
 * applied it on the OpenGL pack, and with Sodium Extra gone nothing did (td-2f83c4: Nether fogged at 9.6..96 blocks where
 * the OpenGL pack shows far terrain unfogged). Iroh reads the same data file and applies the one value the pack uses.
 * Other values (0 = vanilla fog) change nothing. Behaviour of values above 0 is UNVERIFIED and not implemented.
 */
public final class PackFogSetting {
    private PackFogSetting() {}

    private static boolean loaded;
    private static boolean disabled;

    /** True when terrain fog should be switched off for this level. */
    public static boolean terrainFogOff(String dimension) {
        if (!loaded) load(dimension);
        return disabled;
    }

    private static synchronized void load(String dimension) {
        if (loaded) return;
        loaded = true;
        if (FabricLoader.getInstance().isModLoaded("sodium-extra")) return; // the real mod does it
        Path file = FabricLoader.getInstance().getConfigDir().resolve("sodium-extra-options.json");
        if (!Files.isRegularFile(file)) return;
        try {
            JsonObject fog = JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                    .getAsJsonObject("render_settings").getAsJsonObject("fog_settings");
            JsonObject atmos = fog.getAsJsonObject("atmospheric");
            JsonElement multi = fog.get("multi_dimension_fog_control");
            // Per-dimension overrides are only honoured with the multi-dimension switch on; the pack has it off.
            if (multi != null && multi.getAsBoolean()) {
                JsonObject over = fog.getAsJsonObject("dimension_overrides");
                if (over != null && over.has(dimension)) atmos = over.getAsJsonObject(dimension);
            }
            int chunks = atmos.get("distance_chunks").getAsInt();
            disabled = chunks == -1;
            Initializer.LOGGER.info("Iroh: pack fog setting distance_chunks={} (terrain fog {})", chunks, disabled ? "off" : "vanilla");
        } catch (Exception e) {
            Initializer.LOGGER.warn("Iroh: could not read {}: {}", file, e.toString());
        }
    }
}
