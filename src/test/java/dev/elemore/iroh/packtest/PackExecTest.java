package dev.elemore.iroh.packtest;

import dev.elemore.iroh.shaderpack.FrontEnd;
import dev.elemore.iroh.shaderpack.PackSource;
import dev.elemore.iroh.shaderpack.StandardMacros;
import dev.elemore.iroh.shaderpack.graph.PackData;
import dev.elemore.iroh.shaderpack.graph.PackPlan;
import dev.elemore.iroh.shaderpack.runtime.ExecValidator;
import dev.elemore.iroh.shaderpack.runtime.FrameCapture;
import dev.elemore.iroh.shaderpack.runtime.FrameExecutor;
import dev.elemore.iroh.shaderpack.runtime.RecordingBackend;
import dev.elemore.iroh.shaderpack.runtime.SyntheticScene;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The frame executor on the recording fake device (P3-3): no Vulkan, no native code. For every folder of every pack
 * given, a synthetic frame is replayed from the deferred stage and the recorded calls are validated against the plan
 * (order, attachments and halves, bindings, mips, clears, barriers). Also round-trips the synthetic frame through the
 * .irohframe format. {@code ./gradlew packExecTest [-Ppack=...]}; last line {@code # packexec PASS n/n}.
 */
public final class PackExecTest {

    public static void main(String[] args) throws Exception {
        List<Path> packs = new ArrayList<>();
        String arg = null;
        for (String a : args)
            if (a.startsWith("--pack="))
                arg = a.substring(7);
        if (arg != null) {
            packs.add(Paths.get(arg));
        } else {
            packs.add(Paths.get(System.getProperty("user.home"), ".local/share/atlauncher/instances/Vulkan/shaderpacks/Navidson"));
            for (String t : new String[]{"t01-basic", "t02-options", "t04-targets", "t05-compute", "t09-compat"})
                packs.add(Paths.get("src/test/packs", t));
        }
        int pass = 0, total = 0;
        for (Path p : packs) {
            if (!Files.exists(p)) {
                System.out.println("# skip " + p + " (absent)");
                continue;
            }
            String name = p.getFileName().toString();
            Path optFile = p.resolveSibling(name + ".txt");
            String saved = Files.isRegularFile(optFile) ? Files.readString(optFile, StandardCharsets.UTF_8) : null;
            Path dump = Paths.get("build/packExec").resolve(PackHarness.safe(name));
            PackHarness.deleteTree(dump);
            FrontEnd.run(PackSource.open(p), new FrontEnd.Config(StandardMacros.Env.defaults(), saved, true, dump));
            PackData data = PackData.load(dump);
            PackPlan plan = PackPlan.build(data);
            for (String folder : plan.frames.keySet()) {
                total++;
                RecordingBackend rec = new RecordingBackend();
                FrameExecutor ex = new FrameExecutor(data, plan, folder, rec, FrameExecutor.cap(1L << 30));
                SyntheticScene scene = new SyntheticScene(data, plan.frames.get(folder), plan.resources.get(folder), 64, 36);
                ex.prepare(scene);
                ex.frame(scene);
                List<String> errs = ExecValidator.check(data, ex, rec, scene);
                int v = rec.violations.size();
                ex.frame(scene); // a second frame: state carried over (end-of-frame copies, history) stays valid
                for (int k = v; k < rec.violations.size(); k++)
                    errs.add("frame 2: " + rec.violations.get(k));
                // V-7: three frames with frames in flight (in game frame N+1 is submitted while frame N may run): no access
                // may race the previous frame's accesses after its last barrier
                RecordingBackend fly = new RecordingBackend();
                fly.framesInFlight = true;
                FrameExecutor exFly = new FrameExecutor(data, plan, folder, fly, FrameExecutor.cap(1L << 30));
                for (int k = 0; k < 3; k++)
                    exFly.frame(scene);
                fly.violations.stream().limit(4).forEach(x -> errs.add("in flight: " + x));
                // round trip through the capture format
                Path cap = Paths.get("build/packExec/captures").resolve(PackHarness.safe(name) + "-" + folder.replace("<", "").replace(">", "") + ".irohframe");
                FrameCapture.of(scene, name, folder, "synthetic", "PackExecTest").write(cap);
                FrameCapture back = FrameCapture.read(cap);
                if (!back.suppliedImages().equals(scene.suppliedImages()) || back.uniforms().size() != scene.uniforms().size())
                    errs.add("capture round trip changed the frame");
                long draws = rec.draws.size() / 2, dispatches = rec.dispatches.size() / 2;
                if (errs.isEmpty()) {
                    pass++;
                    System.out.println("PASS " + name + " " + folder + ": " + draws + " draws, " + dispatches + " dispatches, "
                            + ex.images.size() + " images, " + rec.calls.size() + " calls (2 frames)");
                } else {
                    System.out.println("FAIL " + name + " " + folder + ":");
                    errs.stream().limit(12).forEach(e -> System.out.println("    " + e));
                }
            }
        }
        // negative controls: the checks must catch a missing barrier and a reordered pass (Navidson world0)
        Path nav = Paths.get("build/packExec/Navidson");
        if (Files.isDirectory(nav)) {
            PackData data = PackData.load(nav);
            PackPlan plan = PackPlan.build(data);
            total++;
            RecordingBackend rec = new RecordingBackend();
            dev.elemore.iroh.shaderpack.runtime.GpuBackend noBarriers = (dev.elemore.iroh.shaderpack.runtime.GpuBackend) java.lang.reflect.Proxy.newProxyInstance(
                    PackExecTest.class.getClassLoader(), new Class<?>[]{dev.elemore.iroh.shaderpack.runtime.GpuBackend.class},
                    (proxy, m, a) -> m.getName().equals("barrier") ? null : m.invoke(rec, a));
            FrameExecutor ex = new FrameExecutor(data, plan, "world0", noBarriers, FrameExecutor.cap(1L << 30));
            SyntheticScene scene = new SyntheticScene(data, plan.frames.get("world0"), plan.resources.get("world0"), 64, 36);
            ex.frame(scene);
            boolean caughtBarrier = !rec.violations.isEmpty();
            java.util.Collections.swap(rec.draws, 1, 2);
            boolean caughtOrder = ExecValidator.check(data, ex, rec, scene).stream().anyMatch(e -> e.startsWith("draw 1 is"));
            if (caughtBarrier && caughtOrder) {
                pass++;
                System.out.println("PASS negative controls: missing barriers -> " + rec.violations.size() + " violations, swapped passes -> order error");
            } else {
                System.out.println("FAIL negative controls: barrier caught " + caughtBarrier + ", order caught " + caughtOrder);
            }
        }
        System.out.println("# packexec " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
