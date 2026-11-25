package dev.elemore.iroh.packtest;

import dev.elemore.iroh.api.IrohDebug;
import dev.elemore.iroh.debug.DebugSwitches;
import dev.elemore.iroh.shaderpack.runtime.PackRuntime;
import dev.elemore.iroh.shaderpack.runtime.PackSwitches;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P3-4 default-off: without iroh.pack.enabled the entry points the renderer calls (PackRuntime.onWorldEnd at
 * WorldRenderEvents.END, onSetLevel from LevelRenderer.setLevel) do nothing: no implementation object, no pack loaded,
 * no Vulkan or game class touched (this test runs with no game and no Vulkan device: any touch would throw).
 * IrohDebug.set refuses without -Dproctor=1 / -Diroh.debug=true; get answers the defaults; the hooks are wired at
 * exactly the two places. {@code ./gradlew packDefaultOffTest}; last line {@code # defaultoff PASS n/n}.
 */
public final class PackDefaultOffTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1))
            n++;
        return n;
    }

    public static void main(String[] args) throws Exception {
        check("property unset", System.getProperty(PackSwitches.ENABLED) == null);
        for (int i = 0; i < 1000; i++)
            PackRuntime.onWorldEnd();
        PackRuntime.onSetLevel();
        check("inactive after 1000 frames", !PackRuntime.active());
        // P3-6: full mode is startup-only and off: the 16-byte section format stays, the terrain-skip flag stays false
        check("PackStartup.FULL false without the properties", !dev.elemore.iroh.shaderpack.runtime.PackStartup.FULL);
        for (int i = 0; i < 100; i++) {
            PackRuntime.onWorldStart();
            PackRuntime.onAfterSky();
            PackRuntime.onBeforeEntities();
            PackRuntime.onTranslucentTerrain();
        }
        check("P3-11: substitution hook word stays 0 when off (one volatile read per bind and per draw)", dev.elemore.iroh.shaderpack.runtime.Substitution.HOOK == 0);
        check("raster terrain never skipped when off", !net.vulkanmod.render.chunk.WorldRenderer.packTerrainActive);
        check("substitution observe off: bindGraphicsPipeline costs one volatile read", !dev.elemore.iroh.shaderpack.runtime.Substitution.OBSERVE);
        Field f = PackRuntime.class.getDeclaredField("impl");
        f.setAccessible(true);
        check("no implementation object created", f.get(null) == null);
        check("capture refused while off", PackRuntime.requestCapture(Path.of("build/x")).startsWith("refused"));
        check("IrohDebug.set refused without proctor/debug", IrohDebug.set(PackSwitches.ENABLED, "on").startsWith("refused"));
        check("refused set changed nothing", !PackRuntime.active() && f.get(null) == null);
        check("enabled default off", IrohDebug.get(PackSwitches.ENABLED).equals("off"));
        check("mode default post", IrohDebug.get(PackSwitches.MODE).equals("post"));
        boolean threw = false;
        try { IrohDebug.get("iroh.pack.nope"); } catch (IllegalArgumentException e) { threw = true; }
        check("unknown key throws", threw);
        threw = false;
        try { DebugSwitches.set(PackSwitches.MODE, "sideways"); } catch (IllegalArgumentException e) { threw = true; }
        check("bad value throws", threw);
        check("list names every pack switch", IrohDebug.list().contains(PackSwitches.SKIP));
        check("runtime-safe switch ok", DebugSwitches.set(PackSwitches.SKIP, "composite7").equals("ok") && DebugSwitches.getOr(PackSwitches.SKIP, "").equals("composite7"));
        String ws = Files.readString(Path.of("src/main/java/dev/elemore/iroh/apiimpl/WorldStages.java"));
        String lr = Files.readString(Path.of("src/main/java/net/vulkanmod/mixin/chunk/LevelRendererMixin.java"));
        int hooks = 0;
        for (Path p : Files.walk(Path.of("src/main/java")).filter(p -> p.toString().endsWith(".java") && !p.toString().contains("shaderpack/runtime/")).toList())
            if (Files.readString(p).contains("PackRuntime."))
                hooks++;
        check("WorldStages calls onWorldEnd", ws.contains("PackRuntime.onWorldEnd()"));
        check("LevelRendererMixin calls onSetLevel", lr.contains("PackRuntime.onSetLevel()"));
        check("exactly 9 files outside the runtime reference PackRuntime (WorldStages, mixin, IrohDebug, TemporalRuntime + UpscaleSettings: pack wins over render scale until P4-8, WorldRenderer: P3-11 translucent hook, Vulkan: PACK-FULL shutdown, IrohOptions + telemetry McSources: TEL-1 read-only active() display): " + hooks, hooks == 9);
        String vk = Files.readString(Path.of("src/main/java/net/vulkanmod/vulkan/Vulkan.java"));
        check("Vulkan.cleanUp calls PackRuntime.shutdown (PACK-FULL; a no-op when the pack never ran)", vk.contains("PackRuntime.shutdown()"));
        // P3-11: the renderer's per-bind and per-draw paths read Substitution.HOOK once and nothing else of the pack path
        String rd = Files.readString(Path.of("src/main/java/net/vulkanmod/vulkan/Renderer.java"));
        String bindBody = rd.substring(rd.indexOf("public void bindGraphicsPipeline("), rd.indexOf("public void uploadAndBindUBOs("));
        String uboBody = rd.substring(rd.indexOf("public void uploadAndBindUBOs("), rd.indexOf("public void pushConstants("));
        check("bindGraphicsPipeline: one HOOK read guards the hook", count(bindBody, "Substitution.HOOK") == 1 && count(bindBody, "shaderpack") == 2
                && bindBody.indexOf("Substitution.HOOK != 0") < bindBody.indexOf("SubstitutionHook.bind"));
        check("uploadAndBindUBOs: one HOOK read guards the hook", count(uboBody, "Substitution.HOOK") == 1 && count(uboBody, "shaderpack") == 2
                && uboBody.indexOf("Substitution.HOOK != 0") < uboBody.indexOf("SubstitutionHook.beforeDraw"));
        check("every SubstitutionHook call in the renderer is behind a HOOK read", count(rd, "SubstitutionHook.") == count(rd, "Substitution.HOOK"));
        check("world split default off", IrohDebug.get(PackSwitches.WORLD_SPLIT).equals("off"));
        check("world substitute default off", IrohDebug.get(PackSwitches.WORLD_SUBSTITUTE).equals("off"));
        System.out.println("# defaultoff " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
