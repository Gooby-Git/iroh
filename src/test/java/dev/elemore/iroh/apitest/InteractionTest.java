package dev.elemore.iroh.apitest;

import dev.elemore.iroh.api.IrohCompute;
import dev.elemore.iroh.api.IrohTargets;
import dev.elemore.iroh.api.RenderTargetHandle;
import dev.elemore.iroh.gpu.GpuCore;
import dev.elemore.iroh.render.MainPassArbiter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.elemore.iroh.apitest.ApiV4Checks.throwsIse;

/**
 * INT-3: the interaction matrix of the four things that end / resume / redirect Iroh's main pass (render scale's world
 * phase R, the pack world pass W, api 4.1 main join J, api 4.1 runOutsideMainPass O = Iroh's PassResume P), on fakes:
 * the pure arbiter (dev.elemore.iroh.render.MainPassArbiter) with a fake renderer whose W attachments can be open, GpuCore
 * on the recording device for the api-side rules, and source checks that the game code routes through the arbiter.
 * {@code ./gradlew interactionTest}; last line {@code # interaction PASS n/n}.
 */
public final class InteractionTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    /** Iroh's renderer: one main pass (or a mod framebuffer), the pack world pass's attachments, render scale's phase */
    static final class FakeRenderer implements MainPassArbiter.Hooks {
        final List<String> log = new ArrayList<>();
        String bound = "main";          // "main" | "modfb" | null
        boolean worldPassOpen;          // W: pack attachments open, Iroh's pass ended by W
        boolean worldPhase;             // R
        boolean cacheValid = true, dynValid = true;

        /** W opens: ends Iroh's pass, binds pack attachments (what WorldPass.enter does) */
        void openW() {
            log.add("W open");
            bound = null;
            worldPassOpen = true;
        }

        public void closePackWorldPass() {
            if (!worldPassOpen) return;
            worldPassOpen = false;
            log.add("W close");
            resumeMain(); // WorldPass.leave resumes Iroh's pass and invalidates
            cacheValid = false;
        }

        public boolean passBound() { return bound != null; }
        public void endPass() {
            if (worldPassOpen) log.add("BAD end with W open");
            log.add("end " + bound);
            bound = null;
        }
        public void resumePass() { resumeMain(); }
        public void invalidateBoundState() { cacheValid = true; log.add("invalidate"); }
        public void reapplyDynamicState() { dynValid = true; log.add("reapply"); }
        public boolean mainTargetBound() { return "main".equals(bound); }
        public void resumeMain() {
            if (worldPassOpen) log.add("BAD resume with W open");
            bound = "main";
            log.add("resume main" + (worldPhase ? " (world set)" : ""));
        }

        /** the recording a work item makes: must never be inside W or a pass */
        void record(String what) {
            if (worldPassOpen) log.add("BAD " + what + " inside W");
            if (bound != null) log.add("BAD " + what + " inside pass " + bound);
            log.add(what);
            dynValid = false;
            cacheValid = false;
        }

        boolean bad() { return log.stream().anyMatch(l -> l.startsWith("BAD")); }
    }

    public static void main(String[] args) throws Exception {
        // ---- W + O / P: W yields, the work records outside every pass, Iroh's pass resumes with fresh state
        FakeRenderer r = new FakeRenderer();
        r.openW();
        MainPassArbiter.runOutside(r, () -> r.record("compute"));
        check("W+O: W closed, main resumed, ended, work, resumed, invalidated, re-applied",
                r.log.equals(List.of("W open", "W close", "resume main", "end main", "compute", "resume main", "invalidate", "reapply")));
        check("W+O: nothing recorded inside W or a pass; state valid after", !r.bad() && r.cacheValid && r.dynValid && r.mainTargetBound());

        // ---- W + J: W yields, the join finds Iroh's main target bound
        FakeRenderer j = new FakeRenderer();
        j.openW();
        MainPassArbiter.prepareJoin(j);
        check("W+J: W closed, join on Iroh's main pass", !j.bad() && !j.worldPassOpen && j.mainTargetBound());

        // ---- J with no pass bound (after an out-of-pass stage): main resumed for the join
        FakeRenderer n = new FakeRenderer();
        n.bound = null;
        MainPassArbiter.prepareJoin(n);
        check("J with no pass: main resumed", n.mainTargetBound() && n.log.contains("resume main"));

        // ---- J while a mod framebuffer is bound: refused, nothing changed
        FakeRenderer m = new FakeRenderer();
        m.bound = "modfb";
        check("J with a foreign pass bound: refused", throwsIse(() -> MainPassArbiter.prepareJoin(m)));
        check("J refused: the foreign pass stays bound", "modfb".equals(m.bound) && !m.bad());

        // ---- R + O / R + J: resume on the ACTIVE (world) set
        FakeRenderer rs = new FakeRenderer();
        rs.worldPhase = true;
        MainPassArbiter.runOutside(rs, () -> rs.record("compute"));
        MainPassArbiter.prepareJoin(rs);
        check("R+O / R+J: resumed on the world set, join on it", !rs.bad() && rs.log.contains("resume main (world set)") && rs.mainTargetBound());

        // ---- O inside O, P inside O: the inner finds no pass and only records
        FakeRenderer oo = new FakeRenderer();
        MainPassArbiter.runOutside(oo, () -> {
            oo.record("outer");
            MainPassArbiter.runOutside(oo, () -> oo.record("inner"));
        });
        check("O inside O: one end/resume pair, both recorded outside", !oo.bad()
                && oo.log.stream().filter(l -> l.startsWith("end ")).count() == 1 && oo.log.indexOf("inner") > oo.log.indexOf("outer")
                && oo.mainTargetBound());

        // ---- R + W forbidden: the world pass refuses with one reason
        check("R+W: world pass refused while the world phase is open", MainPassArbiter.worldPassRefusal(true) != null);
        check("W alone: allowed", MainPassArbiter.worldPassRefusal(false) == null);

        // ---- api side on the recording device (GpuCore): J + O forbidden, nested O, tokens follow the phase
        ApiV4Checks.Rec rec = new ApiV4Checks.Rec();
        GpuCore core = new GpuCore(rec, 0.40);
        core.resize(64, 32);
        RenderTargetHandle mc = core.mainColor(), md = core.mainDepth();
        rec.mainBound = true;
        core.beginRendering(mc, md, false, null, 1f);
        check("J+O: runOutsideMainPass inside an open main join refused", throwsIse(() -> core.runOutsideMainPass(() -> {})));
        core.endRendering();
        int[] inner = {0};
        core.runOutsideMainPass(() -> core.runOutsideMainPass(() -> inner[0]++));
        check("O inside O on the device: inner work ran, one suspend/resume", inner[0] == 1
                && rec.log.stream().filter(l -> l.equals("suspend")).count() == 1);
        core.setWorldPhase(true, 32, 16);
        check("R+J: main tokens report the render size inside the world phase", mc.width() == 32 && md.height() == 16);
        RenderTargetHandle scaled = core.createTarget("vista", "d", IrohTargets.Format.DEPTH32F, 0, 0, 1);
        core.runOutsideMainPass(() -> core.mainDepthCopy(scaled));
        check("R+O: a scaled target copied from the main depth inside the world phase is render size", scaled.width() == 32 && scaled.height() == 16);
        core.setWorldPhase(false, 0, 0);
        check("R closed: main tokens back to the window size", mc.width() == 64 && md.height() == 32);
        rec.mainBound = false;

        // ---- the game code routes through the arbiter (source checks: the game classes need Minecraft)
        String dev = Files.readString(Path.of("src/main/java/dev/elemore/iroh/gpu/game/IrohGpuDevice.java"));
        check("IrohGpuDevice.runOutsideMainPass = VulkanImage.endPassRunResume (W closed there, PassResume order)",
                dev.contains("VulkanImage.endPassRunResume(work)"));
        check("IrohGpuDevice main join through MainPassArbiter.prepareJoin", dev.contains("MainPassArbiter.prepareJoin("));
        check("IrohGpuDevice: every recording entry closes W first (canRecordOutsideMainPass and cmd)",
                count(dev, "closePackWorldPass()") >= 2);
        String vi = Files.readString(Path.of("src/main/java/net/vulkanmod/vulkan/texture/VulkanImage.java"));
        int e = vi.indexOf("public static void endPassRunResume(");
        check("endPassRunResume closes W before capturing the bound pass",
                e >= 0 && vi.indexOf("SubstitutionHook.closeForForeign()", e) < vi.indexOf("getBoundRenderPass()", e));
        String pri = Files.readString(Path.of("src/main/java/dev/elemore/iroh/shaderpack/runtime/PackRuntimeImpl.java"));
        check("world pass asks the arbiter about the render-scale world phase", pri.contains("MainPassArbiter.worldPassRefusal("));
        String tr = Files.readString(Path.of("src/main/java/dev/elemore/iroh/temporal/TemporalRuntime.java"));
        check("pack wins over render scale (TemporalRuntime)", tr.contains("PackRuntime.active()"));
        String mp = Files.readString(Path.of("src/main/java/net/vulkanmod/vulkan/pass/DefaultMainPass.java"));
        check("DefaultMainPass.end: a frame ending with W live turns W off (one HOOK read)", mp.contains("SubstitutionHook.frameEnded()"));

        System.out.println("# interaction " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }

    static int count(String s, String what) {
        int c = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) c++;
        return c;
    }
}
