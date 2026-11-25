package dev.elemore.iroh.lod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.elemore.iroh.config.IrohOptions;
import dev.elemore.iroh.lod.config.LodConfig;
import dev.elemore.iroh.lod.config.LodMigration;
import dev.elemore.iroh.lod.config.LodOptions;
import net.vulkanmod.config.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** VL-0 headless test. Last line "# lodconfig PASS n/n" or FAIL. Fixtures: the pack's vista.json / vista-server.json. */
public class LodConfigTest {
    static int pass, total;

    static void check(boolean ok, String what) {
        total++;
        if (ok) pass++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static Path dir(String name, boolean vista, boolean server) throws Exception {
        Path d = Files.createTempDirectory("iroh-lod-" + name);
        if (vista) Files.copy(Path.of("src/test/resources/lod/vista.json"), d.resolve("vista.json"));
        if (server) Files.copy(Path.of("src/test/resources/lod/vista-server.json"), d.resolve("vista-server.json"));
        return d;
    }

    static JsonObject json(Path d) throws Exception {
        return JsonParser.parseString(Files.readString(d.resolve("iroh.json"))).getAsJsonObject();
    }

    static List<String> load(Path d) {
        LodConfig.INSTANCE.reset();
        return IrohOptions.load(new Config(), d);
    }

    public static void main(String[] args) throws Exception {
        // --- registry ---
        IrohOptions.all(); // class init registers the lod rows
        List<IrohOptions.Opt<?>> rows = LodOptions.rows();
        check(rows.size() == 29, "29 Distant Terrain rows registered (got " + rows.size() + ")");
        boolean cat = true, unique = true;
        for (var o : rows) {
            cat &= o.category == IrohOptions.Category.DISTANT_TERRAIN && o.id.startsWith("lod.");
            unique &= IrohOptions.byId(o.id) == o;
        }
        check(cat && unique, "every row is in the DISTANT_TERRAIN category with a lod.* id");
        JsonObject lang = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/iroh/lang/en_us.json"))).getAsJsonObject();
        int missing = 0;
        for (var o : rows) for (String k : new String[]{o.nameKey, o.tooltipKey}) if (!lang.has(k)) { System.out.println("  missing " + k); missing++; }
        for (String k : new String[]{"iroh.pages.distantTerrain", "iroh.lod.status", "iroh.lod.reason.vista", "iroh.lod.reason.notBuilt",
                "iroh.lod.reason.tempest", "iroh.lod.reason.noBridge", "iroh.lod.native"}) if (!lang.has(k)) { System.out.println("  missing " + k); missing++; }
        check(missing == 0, "every row and tab string has a key in assets/iroh/lang/en_us.json");

        // --- A: pack files -> the "pack" column ---
        Path a = dir("a", true, true);
        List<String> log = load(a);
        log.stream().filter(l -> l.startsWith("lod:")).forEach(l -> System.out.println("  log: " + l));
        JsonObject j = json(a);
        check(j.get("lod.enabled").getAsBoolean() && j.get("lod.distance").getAsInt() == 12288 && j.get("lod.budgetMiB").getAsInt() == 0
                && j.get("lod.detailDistance").getAsInt() == 800 && j.get("lod.blendWidth").getAsInt() == 10
                && j.get("lod.transitionMs").getAsInt() == 800, "pack column: general + detail");
        check(j.get("lod.brightness").getAsInt() == 85 && j.get("lod.saturation").getAsInt() == 80 && j.get("lod.fogDistance").getAsInt() == 100
                && j.get("lod.nightLights").getAsBoolean() && j.get("lod.motion").getAsBoolean(), "pack column: look");
        check(j.get("lod.weatherOnLods").getAsBoolean() && j.get("lod.weatherCurtains").getAsBoolean() && j.get("lod.farPlayers").getAsBoolean()
                && j.get("lod.farPlayerMinPixels").getAsInt() == 4 && j.get("lod.nameTagDistance").getAsInt() == 128
                && j.get("lod.landmarks").getAsBoolean(), "pack column: weather, players, landmarks");
        check(j.get("lod.seedLods").getAsBoolean() && j.get("lod.shareWithMaps").getAsBoolean() && j.get("lod.captureVisited").getAsBoolean()
                && j.get("lod.occlusionCulling").getAsBoolean() && j.get("lod.workerThreads").getAsInt() == 0
                && j.get("lod.uploadMiBPerFrame").getAsInt() == 6, "pack column: data (xaeroFill -> shareWithMaps), memory, upload");
        check(j.get("lod.server.structurePass").getAsBoolean() && j.get("lod.server.structureRange").getAsInt() == 4096
                && j.get("lod.server.shareSeedTerrain").getAsBoolean() && j.get("lod.server.sharePlayerPositions").getAsBoolean(),
                "pack column: server rows from vista-server.json");
        Path srv = a.resolve("iroh-lod-server.json");
        JsonObject s = Files.exists(srv) ? JsonParser.parseString(Files.readString(srv)).getAsJsonObject() : new JsonObject();
        check(s.has("landmarks") && s.getAsJsonArray("landmarks").size() == 1
                && s.getAsJsonArray("landmarks").get(0).getAsJsonObject().get("name").getAsString().startsWith("example"),
                "landmarks list written to iroh-lod-server.json; citadelLandmarks dropped");
        check(log.stream().anyMatch(l -> l.contains("citadelLandmarks")), "dropped key logged");
        check(Files.readString(a.resolve("vista.json")).equals(Files.readString(Path.of("src/test/resources/lod/vista.json")))
                && Files.exists(a.resolve("vista-server.json")), "Vista files read, never edited or removed");
        check(!log.stream().anyMatch(l -> l.contains("lod.distance") && l.contains("clamped")), "pack values need no clamping");

        // --- B: round trip, and no second migration ---
        Files.writeString(a.resolve("vista.json"), "{\"distance\": 512}");
        load(a);
        check(LodConfig.INSTANCE.distance == 12288 && LodConfig.INSTANCE.blendWidth == 10, "second load reads iroh.json, does not re-import vista.json");
        IrohOptions.save();
        check(IrohOptions.toJsonText().equals(Files.readString(a.resolve("iroh.json")).trim()), "round trip: save() text equals the file");

        // --- C: no Vista files -> defaults column ---
        Path c = dir("c", false, false);
        load(c);
        JsonObject jc = json(c);
        LodConfig d = new LodConfig();
        check(jc.get("lod.distance").getAsInt() == 4096 && jc.get("lod.blendWidth").getAsInt() == 0 && jc.get("lod.transitionMs").getAsInt() == 400
                && jc.get("lod.brightness").getAsInt() == 100 && jc.get("lod.saturation").getAsInt() == 100 && jc.get("lod.enabled").getAsBoolean()
                && d.distance == 4096 && !Files.exists(c.resolve("iroh-lod-server.json")), "no Vista files: defaults written, no server file");

        // --- D: existing iroh.json without lod keys + vista.json -> migrated once ---
        Path e = dir("e", true, false);
        Files.writeString(e.resolve("iroh.json"), "{\"version\": 1, \"showFps\": true}");
        load(e);
        check(LodConfig.INSTANCE.distance == 12288 && json(e).has("lod.distance") && json(e).get("showFps").getAsBoolean(),
                "existing iroh.json without lod keys: Vista values imported, other keys kept");

        // --- E: clamps, -Dvista.cpuThreads ---
        Path f = Files.createTempDirectory("iroh-lod-f");
        Files.writeString(f.resolve("vista.json"), "{\"distance\": 99999, \"budgetMiB\": 100, \"brightness\": 0, \"blendWidth\": -5, "
                + "\"detailDistance\": 5000, \"xaeroFill\": false, \"nameTagDistance\": \"far\"}");
        LodConfig cf = new LodConfig();
        List<String> lf = new java.util.ArrayList<>();
        LodMigration.migrate(f, cf, "99", lf);
        check(cf.distance == 12288 && cf.budgetMiB == 512 && cf.brightness == 50 && cf.blendWidth == 0 && cf.detailDistance == 2048,
                "out-of-range values clamped (distance, budget, brightness, blend, detail)");
        check(!cf.shareWithMaps && cf.nameTagDistance == 128, "xaeroFill false -> shareWithMaps false; non-number ignored");
        check(cf.workerThreads == 8, "-Dvista.cpuThreads=99 -> lod.workerThreads clamped to 8");
        check(lf.stream().filter(l -> l.contains("clamped") || l.contains("normalised")).count() >= 5, "one log line per clamped key");
        Path g = Files.createTempDirectory("iroh-lod-g");
        Files.writeString(g.resolve("vista.json"), "{not json");
        LodConfig cg = new LodConfig();
        LodMigration.migrate(g, cg, null, new java.util.ArrayList<>());
        check(cg.distance == 4096, "unreadable vista.json: defaults kept, no exception");

        // --- F: the feature is inert ---
        LodFeature.refresh();
        check(!LodFeature.active() && LodFeature.offReason() != null && !LodFeature.BACKEND_BUILT, "feature inert: not active, reason given");
        LodFeature.vistaProbe = () -> true;
        LodFeature.refresh();
        check(!LodFeature.active() && LodFeature.offReason().contains("Vista") && "iroh.lod.reason.vista".equals(LodFeature.rowsReasonKey()),
                "Vista mod present: off with that reason");
        LodFeature.vistaProbe = () -> false;
        LodFeature.refresh();
        check("iroh.lod.reason.notBuilt".equals(LodFeature.rowsReasonKey()) && LodOptions.DISTANCE.unavailableReason() != null,
                "rows greyed with 'not built yet' while the backend is missing");

        System.out.println("# lodconfig " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
