package dev.elemore.iroh.packtest;

import dev.elemore.iroh.shaderpack.FrontEnd;
import dev.elemore.iroh.shaderpack.PackSource;
import dev.elemore.iroh.shaderpack.StandardMacros;
import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PackPlan;
import dev.elemore.iroh.shaderpack.runtime.FrameExecutor;
import dev.elemore.iroh.shaderpack.runtime.PackLifecycle;
import dev.elemore.iroh.shaderpack.runtime.RecordingBackend;
import dev.elemore.iroh.shaderpack.runtime.SyntheticScene;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * The pack lifecycle on the recording backend (P3-4 item 4): enable, frames, three resizes, a dimension change, a
 * world unload, a memory refusal, disable. Checks: no synchronisation violation, no use of a freed image, no double
 * free, zero live pack images and pipelines after disable / unload / failure, rebuild counts as expected, the folder
 * mapping from dimension ids. {@code ./gradlew packLifecycleTest}; last line {@code # lifecycle PASS n/n}.
 */
public final class PackLifecycleTest {

    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok)
            pass++;
        else
            System.out.println("FAIL " + what);
    }

    public static void main(String[] args) throws Exception {
        Path pack = Paths.get(System.getProperty("user.home"), ".local/share/atlauncher/instances/Vulkan/shaderpacks/Navidson");
        if (!Files.isDirectory(pack))
            pack = Paths.get("src/test/packs/t02-options");
        String name = pack.getFileName().toString();
        Path opt = pack.resolveSibling(name + ".txt");
        String saved = Files.isRegularFile(opt) ? Files.readString(opt, StandardCharsets.UTF_8) : null;
        Path dump = Paths.get("build/packLifecycle").resolve(name);
        PackHarness.deleteTree(dump);
        FrontEnd.run(PackSource.open(pack), new FrontEnd.Config(StandardMacros.Env.defaults(), saved, true, dump));
        PackData data = PackData.load(dump);
        PackPlan plan = PackPlan.build(data);

        RecordingBackend rec = new RecordingBackend();
        PackLifecycle lc = new PackLifecycle(rec, FrameExecutor.cap(1L << 30));
        int scene = rec.importImage(null, "scene colour");
        check("starts OFF", lc.state() == PackLifecycle.State.OFF);
        check("a frame while OFF does nothing", !lc.frame(scene(data, plan, "world0", 64, 36), "world0", scene, Set.of()) && rec.draws.isEmpty());
        lc.enable(data, plan);
        String f0 = lc.folderFor("minecraft:overworld");
        String fn = lc.folderFor("minecraft:the_nether");
        String fo = lc.folderFor("citadels:outside_of_time");
        check("overworld -> world0 (dimension.world0=*)", f0.equals(plan.frames.containsKey("world0") ? "world0" : f0));
        check("nether -> world-1 when the pack has it", !plan.frames.containsKey("world-1") || fn.equals("world-1"));
        check("outside of time -> world2 when the pack has it", !plan.frames.containsKey("world2") || fo.equals("world2"));

        int[][] sizes = {{64, 36}, {64, 36}, {80, 45}, {96, 54}, {48, 27}};
        for (int[] sz : sizes)
            check("frame " + sz[0] + "x" + sz[1], lc.frame(scene(data, plan, f0, sz[0], sz[1]), f0, scene, Set.of()));
        check("4 builds for 4 distinct sizes", lc.rebuilds == 4);
        if (plan.frames.containsKey(fn) && !fn.equals(f0)) {
            check("dimension change frame", lc.frame(scene(data, plan, fn, 48, 27), fn, scene, Set.of()));
            check("dimension change rebuilds", lc.rebuilds == 5);
            check("back to the overworld", lc.frame(scene(data, plan, f0, 48, 27), f0, scene, Set.of()));
        }
        int liveBeforeUnload = rec.live();
        lc.unload();
        check("unload frees every pack image (" + liveBeforeUnload + " before)", rec.live() == 0 && liveBeforeUnload > 0);
        check("unload frees every pipeline", rec.livePipelines() == 0);
        check("frame after unload rebuilds", lc.frame(scene(data, plan, f0, 48, 27), f0, scene, Set.of()) && rec.live() > 0);
        lc.disable();
        check("disable: OFF", lc.state() == PackLifecycle.State.OFF);
        check("disable frees every pack image", rec.live() == 0);
        check("disable frees every pipeline", rec.livePipelines() == 0);
        check("no use after free, no double free, no hazard: " + rec.violations, rec.violations.isEmpty());
        check("present blit into the scene colour each frame", rec.calls.stream().filter(c -> c.startsWith("blit pack:output -> scene colour")).count() >= 5);

        // memory refusal: a gate that refuses after 4 MiB -> FAILED, nothing live, later frames refused
        RecordingBackend rec2 = new RecordingBackend();
        PackLifecycle lc2 = new PackLifecycle(rec2, FrameExecutor.cap(4L << 20));
        lc2.enable(data, plan);
        boolean ran = lc2.frame(scene(data, plan, f0, 640, 360), f0, -1, Set.of());
        check("refused by the memory gate -> FAILED", !ran && lc2.state() == PackLifecycle.State.FAILED && lc2.failure != null);
        check("FAILED leaves nothing live", rec2.live() == 0 && rec2.livePipelines() == 0);
        check("FAILED stays off", !lc2.frame(scene(data, plan, f0, 64, 36), f0, -1, Set.of()));
        check("refusal: no violation " + rec2.violations, rec2.violations.isEmpty());

        lc.log.forEach(l -> System.out.println("  log: " + l));
        lc2.log.forEach(l -> System.out.println("  log: " + l));
        System.out.println("# lifecycle " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }

    static SyntheticScene scene(PackData d, PackPlan p, String folder, int w, int h) {
        return new SyntheticScene(d, p.frames.get(folder), p.resources.get(folder), w, h);
    }
}
