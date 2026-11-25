package dev.elemore.iroh.configtest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.elemore.iroh.config.IrohOptions;
import net.vulkanmod.config.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Headless test of config/iroh.json (card P1-6). Usage: ConfigMigrationTest <instance config dir>.
 * Copies the pack's leftover Sodium / Sodium Extra / Iris / Cull Fewer Leaves files into temp dirs, runs the first-run
 * migration, prints the resulting iroh.json and checks values, persistence, the old VulkanMod file, and that every
 * option has its lang keys. Last line "# configtest PASS n/n" or FAIL.
 */
public class ConfigMigrationTest {
    static int pass, total;

    static void check(boolean ok, String what) {
        total++;
        if (ok) pass++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    public static void main(String[] args) throws Exception {
        Path src = Path.of(args.length > 0 ? args[0] : System.getProperty("user.home") + "/.local/share/atlauncher/instances/Vulkan/config");
        String[] files = {"sodium-options.json", "sodium-extra-options.json", "iris.properties", "cull-fewer-leaves.toml"};

        // --- A: pack files only ---
        Path a = Files.createTempDirectory("iroh-cfg-a");
        for (String f : files) if (Files.exists(src.resolve(f))) Files.copy(src.resolve(f), a.resolve(f));
        Config ca = new Config();
        List<String> log = IrohOptions.load(ca, a);
        log.forEach(l -> System.out.println("  log: " + l));
        String text = Files.readString(a.resolve("iroh.json"));
        System.out.println("---- iroh.json (A: first run, pack files only) ----");
        System.out.println(text);
        System.out.println("----");
        JsonObject j = JsonParser.parseString(text).getAsJsonObject();
        check(j.get("leavesQuality").getAsString().equals("FANCY"), "leaves_quality FANCY -> leavesQuality FANCY");
        check(!j.get("enableVignette").getAsBoolean(), "enable_vignette false -> enableVignette false");
        // P1-10: expectations read from the source file (the instance was re-synced 2026-11-20: threads 4 -> 2,
        // defer ONE_FRAME -> ALWAYS); the rule under test is the mapping, not the owner's current values.
        JsonObject perf = JsonParser.parseString(Files.readString(src.resolve("sodium-options.json"))).getAsJsonObject()
                .getAsJsonObject("performance");
        int srcThreads = perf.get("chunk_builder_threads").getAsInt();
        String srcDefer = perf.get("chunk_build_defer_mode").getAsString();
        check(j.get("builderThreads").getAsInt() == Math.min(srcThreads, Math.max(1, Runtime.getRuntime().availableProcessors() - 1)),
                "chunk_builder_threads " + srcThreads + " -> builderThreads");
        check(j.get("chunkBuildDeferMode").getAsString().equals(srcDefer), "chunk_build_defer_mode " + srcDefer);
        check(j.get("telemetryOverlay").getAsBoolean() && j.get("telFps").getAsBoolean() && j.get("telAvg").getAsBoolean() && j.get("telLow1").getAsBoolean() && !j.has("showFps"), "show_fps true -> Telemetry overlay with FPS, average, 1% low");
        check(j.get("fogDistanceChunks").getAsInt() == -1, "atmospheric distance_chunks -1 -> fogDistanceChunks -1");
        check(j.get("shaderPack").getAsString().equals("Navidson") && j.get("shadersEnabled").getAsBoolean(), "iris shaderPack Navidson, enabled");
        check(j.get("leafCullingRejection").getAsInt() == 20 && j.get("leafCullingDepth").getAsInt() == 2, "cull-fewer-leaves randomRejection 20, depth 2");
        check(j.get("leafCulling").getAsBoolean(), "cull-fewer-leaves enabled -> leafCulling on");
        check(dev.elemore.iroh.apiimpl.LeafCulling.enabled() && dev.elemore.iroh.apiimpl.LeafCulling.depth() == 2
                && dev.elemore.iroh.apiimpl.LeafCulling.randomRejection() == 20, "mesher LeafCulling follows iroh.json after load");
        check(j.get("advCulling").getAsInt() == 2 && j.get("indirectDraw").getAsBoolean(), "VulkanMod defaults unchanged");
        check(Files.exists(a.resolve("sodium-options.json")), "old files left in place");

        // --- B: persistence: edit a Sodium file, load again; iroh.json wins, nothing re-migrated ---
        Files.writeString(a.resolve("sodium-options.json"), "{\"quality\":{\"leaves_quality\":\"FAST\"}}");
        Config cb = new Config();
        IrohOptions.load(cb, a);
        check(cb.leavesQuality.equals("FANCY") && !cb.enableVignette && cb.telemetryOverlay && cb.fogDistanceChunks == -1, "second run reads iroh.json, ignores the old files");
        cb.enableVignette = true;
        IrohOptions.save();
        Config cb2 = new Config();
        IrohOptions.load(cb2, a);
        check(cb2.enableVignette, "a changed value survives a reload");

        // --- C: VulkanMod file present: migrated and kept, its builderThreads beats Sodium's ---
        Path c = Files.createTempDirectory("iroh-cfg-c");
        for (String f : files) if (Files.exists(src.resolve(f))) Files.copy(src.resolve(f), c.resolve(f));
        Files.writeString(c.resolve("vulkanmod_settings.json"),
                "{\"windowMode\":1,\"advCulling\":3,\"indirectDraw\":false,\"frameQueueSize\":3,\"builderThreads\":2,\"backFaceCulling\":false,"
                        + "\"videoMode\":{\"width\":2560,\"height\":1440,\"bitDepth\":8,\"refreshRate\":144}}");
        Config cc = new Config();
        IrohOptions.load(cc, c);
        System.out.println("---- iroh.json (C: vulkanmod_settings.json + pack files) ----");
        System.out.println(Files.readString(c.resolve("iroh.json")));
        System.out.println("----");
        check(cc.windowMode == 1 && cc.advCulling == 3 && !cc.indirectDraw && cc.frameQueueSize == 3 && !cc.backFaceCulling, "vulkanmod_settings.json values carried over");
        check(cc.videoMode != null && cc.videoMode.refreshRate == 144, "video mode carried over");
        check(cc.builderThreads == 2, "VulkanMod builderThreads 2 kept over Sodium's 4");
        check(cc.leavesQuality.equals("FANCY"), "pack options still seeded");
        check(Files.exists(c.resolve("vulkanmod_settings.json")), "vulkanmod_settings.json kept");

        // --- D: nothing at all: defaults ---
        Path d = Files.createTempDirectory("iroh-cfg-d");
        Config cd = new Config();
        IrohOptions.load(cd, d);
        check(cd.leavesQuality.equals("DEFAULT") && cd.enableVignette && !cd.telemetryOverlay && cd.fogDistanceChunks == 0 && cd.builderThreads == 0,
                "no files: defaults (vignette on, fps off, vanilla fog)");
        check(Files.exists(d.resolve("iroh.json")), "iroh.json written on first run");

        // --- TEL-1: an iroh.json from before the Telemetry tab (showFps) becomes the overlay; the transient CSV switch is never saved ---
        Path t = Files.createTempDirectory("iroh-cfg-tel");
        Files.writeString(t.resolve("iroh.json"), "{\"showFps\": true}");
        Config ct = new Config();
        ct.telFps = ct.telAvg = ct.telLow1 = false;
        IrohOptions.load(ct, t);
        check(ct.telemetryOverlay && ct.telFps && ct.telAvg && ct.telLow1, "old iroh.json showFps true -> overlay on with the old counter's items");
        IrohOptions.save();
        String saved = Files.readString(t.resolve("iroh.json"));
        check(!saved.contains("showFps") && saved.contains("telemetryOverlay") && !saved.contains("telRecordCsv"), "saved file has the new keys, no showFps, no record switch");

        // --- E: garbage in the file does not crash ---
        Files.writeString(d.resolve("iroh.json"), "{\"advCulling\": 77, \"leavesQuality\": \"WOBBLY\", \"fogDistanceChunks\": 9999, \"showFps\": \"yes\"}");
        Config ce = new Config();
        IrohOptions.load(ce, d);
        check(ce.advCulling == 2 && ce.leavesQuality.equals("DEFAULT") && ce.fogDistanceChunks == 256, "bad values rejected or clamped");

        // --- F: registry sanity: unique ids, lang keys for everything shown, defaults equal Config's ---
        JsonObject lang = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/vulkanmod/lang/en_us.json"))).getAsJsonObject();
        int missing = 0;
        for (IrohOptions.Opt<?> o : IrohOptions.all()) {
            if (o.id.equals("videoMode") || o.id.equals("shaderPack") || o.id.equals("shadersEnabled")) continue;
            for (String k : new String[]{o.nameKey, o.tooltipKey}) {
                if (!lang.has(k) && !k.startsWith("options.") && !k.startsWith("vulkanmod.options.advCulling")) {
                    if (k.startsWith("options.")) continue;
                    System.out.println("  missing lang key " + k);
                    missing++;
                }
            }
        }
        check(missing == 0, "every option has a name and tooltip key in en_us.json");
        Config defaults = new Config();
        Config reg = new Config();
        IrohOptions.load(reg, Files.createTempDirectory("iroh-cfg-f"));
        boolean same = defaults.advCulling == reg.advCulling && defaults.indirectDraw == reg.indirectDraw && defaults.uniqueOpaqueLayer == reg.uniqueOpaqueLayer
                && defaults.entityCulling == reg.entityCulling && defaults.device == reg.device && defaults.ambientOcclusion == reg.ambientOcclusion
                && defaults.frameQueueSize == reg.frameQueueSize && defaults.builderThreads == reg.builderThreads && defaults.backFaceCulling == reg.backFaceCulling;
        check(same, "ported VulkanMod options keep their defaults");
        boolean regDefaults = true;
        for (IrohOptions.Opt<?> o : IrohOptions.all()) {
            if (o.storage != IrohOptions.Storage.JSON || o.defaultValue == null) continue;
            if (!String.valueOf(o.get()).equals(String.valueOf(o.defaultValue))) {
                System.out.println("  default mismatch " + o.id + ": " + o.get() + " vs " + o.defaultValue);
                regDefaults = false;
            }
        }
        check(regDefaults, "registry defaults equal the Config initialisers");

        // --- G (P4-1b): the Iroh section's upscaler rows -> runtime switches, device features at launch ---
        check(reg.upscaler.equals("OFF") && !dev.elemore.iroh.temporal.UpscaleSettings.wantsDeviceFeatures(reg)
                && !dev.elemore.iroh.temporal.DeviceCaps.configRequested, "upscaler Off by default: no device features requested");
        Path g = Files.createTempDirectory("iroh-cfg-g");
        Files.writeString(g.resolve("iroh.json"), "{\"upscaler\": \"FSR3\", \"upscaleQuality\": \"PERFORMANCE\", \"upscaleSharpness\": 40}");
        Config cg = new Config();
        IrohOptions.load(cg, g);
        check(cg.upscaler.equals("FSR3") && cg.upscaleQuality.equals("PERFORMANCE") && cg.upscaleSharpness == 40, "upscaler rows read from iroh.json");
        check(dev.elemore.iroh.temporal.DeviceCaps.configRequested && dev.elemore.iroh.temporal.DeviceCaps.shouldEnable(),
                "FSR 3.1 in iroh.json asks for the device features before the device exists");
        dev.elemore.iroh.temporal.UpscaleSettings.apply(cg);
        check(dev.elemore.iroh.debug.DebugSwitches.getOr(dev.elemore.iroh.temporal.UpscaleSwitches.MODE, "").equals("performance")
                && dev.elemore.iroh.debug.DebugSwitches.getOr(dev.elemore.iroh.temporal.UpscaleSwitches.UPSCALER, "").equals("fsr")
                && dev.elemore.iroh.debug.DebugSwitches.getOr(dev.elemore.iroh.temporal.UpscaleSwitches.SHARPNESS, "").equals("0.40"),
                "settings drive the runtime switches (mode performance, upscaler fsr, sharpness 0.40)");
        cg.upscaler = "OFF";
        dev.elemore.iroh.temporal.UpscaleSettings.apply(cg);
        check(dev.elemore.iroh.temporal.UpscaleSwitches.mode() == dev.elemore.iroh.temporal.ScaleMode.OFF, "Off in the settings turns the mode off");
        cg.upscaler = "FSR3";
        System.setProperty(dev.elemore.iroh.temporal.UpscaleSwitches.MODE, "native");
        dev.elemore.iroh.debug.DebugSwitches.set(dev.elemore.iroh.temporal.UpscaleSwitches.MODE, "native");
        dev.elemore.iroh.temporal.UpscaleSettings.apply(cg);
        check(dev.elemore.iroh.temporal.UpscaleSwitches.mode() == dev.elemore.iroh.temporal.ScaleMode.NATIVE_AA, "a launch property wins over the settings");
        System.clearProperty(dev.elemore.iroh.temporal.UpscaleSwitches.MODE);
        dev.elemore.iroh.debug.DebugSwitches.set(dev.elemore.iroh.temporal.UpscaleSwitches.MODE, "off");
        Files.writeString(g.resolve("iroh.json"), "{\"upscaler\": \"WARP\", \"upscaleSharpness\": 400}");
        Config cg2 = new Config();
        IrohOptions.load(cg2, g);
        check(cg2.upscaler.equals("OFF") && cg2.upscaleSharpness <= 100 && !dev.elemore.iroh.temporal.DeviceCaps.configRequested, "bad upscaler values rejected or clamped");
        check(dev.elemore.iroh.temporal.UpscaleSettings.upscalerReason(dev.elemore.iroh.temporal.UpscaleSettings.Upscaler.FSR4) != null
                && dev.elemore.iroh.temporal.UpscaleSettings.upscalerReason(dev.elemore.iroh.temporal.UpscaleSettings.Upscaler.DLSS) != null
                && dev.elemore.iroh.temporal.UpscaleSettings.upscalerReason(dev.elemore.iroh.temporal.UpscaleSettings.Upscaler.OFF) == null,
                "FSR 4 / DLSS choices carry their reason (Windows only / needs an NVIDIA GPU or not yet)");
        check(dev.elemore.iroh.temporal.UpscaleSettings.qualityReason(cg2) != null && dev.elemore.iroh.temporal.UpscaleSettings.sharpnessReason(cg2) != null,
                "quality and sharpening greyed while the upscaler is Off");

        System.out.println("---- registry ----");
        IrohOptions.describeAll().forEach(l -> System.out.println("  " + l));
        System.out.println("# configtest " + (pass == total ? "PASS " : "FAIL ") + pass + "/" + total);
        if (pass != total) System.exit(1);
    }
}
