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

    public static void main(String[] args) throws Exception {
        check("property unset", System.getProperty(PackSwitches.ENABLED) == null);
        for (int i = 0; i < 1000; i++)
            PackRuntime.onWorldEnd();
        PackRuntime.onSetLevel();
        check("inactive after 1000 frames", !PackRuntime.active());
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
        check("exactly 3 files outside the runtime reference PackRuntime (WorldStages, mixin, IrohDebug): " + hooks, hooks == 3);
        System.out.println("# defaultoff " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
