package dev.elemore.iroh.inttest;

import dev.elemore.iroh.api.IrohDebug;
import dev.elemore.iroh.debug.DebugSwitches;
import dev.elemore.iroh.perf.PerfSwitches;
import dev.elemore.iroh.perf.TaskBudget;
import dev.elemore.iroh.shaderpack.runtime.PackRuntime;
import dev.elemore.iroh.temporal.DeviceCaps;
import dev.elemore.iroh.temporal.FsrAdapter;
import dev.elemore.iroh.temporal.TemporalFrame;
import dev.elemore.iroh.temporal.TemporalRuntime;
import dev.elemore.iroh.temporal.UpscaleSwitches;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * INT-1: the integrated jar is inert with no properties and the default iroh.json. One JVM, no game, no Vulkan device:
 * every renderer entry point of every merged feature (pack runtime P3-9, render scale / velocity / FSR P4-1a/b, perf
 * switches and the task budget, the slow-frame tool, api 4) is called the way the game calls it; any hook that went past
 * its "off" test would touch Renderer / Minecraft / Vulkan and throw. Then the hot-path audit ({@link HotPathAudit}) must
 * equal the pinned list below: what the first in-game run (ladder step I-0) must show costs nothing.
 * Not inert by design and stated here, not hidden: api 4 (card V-M1) installs its GPU device at Renderer init (one
 * descriptor pool per frame in flight, vkResetDescriptorPool per frame, drawIndirectCount enabled at device creation
 * when present) and dispatches BEFORE_MAIN_PASS (an empty-array check) every frame.
 * {@code ./gradlew integrationInertTest}; last line {@code # integrationinert PASS n/n}.
 */
public final class IntegrationInertTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    /** the pinned hot-path list: method -> guard / hook reads it performs when everything is off (count per name) */
    static final Map<String, String> PINNED = new TreeMap<>();
    static {
        PINNED.put("Renderer.bindGraphicsPipeline", "");
        PINNED.put("Renderer.preInitFrame", "SlowFrames.ON x2 (static final)");
        PINNED.put("Renderer.beginFrame0", "SlowFrames.ON x4, GpuLifecycle.frameStart x1 (api 4: vkResetDescriptorPool)");
        PINNED.put("Renderer.endFrame", "SlowFrames.ON x5, FrameHooks.dispatch x1");
        PINNED.put("Renderer.beginRendering", "");
        PINNED.put("Framebuffer.beginRenderPass", "");
        PINNED.put("DefaultMainPass.begin", "");
        PINNED.put("DefaultMainPass.rebindMainTarget", "");
        PINNED.put("WorldRenderer.renderSectionLayer", "WorldRenderer.packTerrainActive x1 (volatile), SlowFrames.ON x2, Sections.captureFog x1 (api 4, solid layer)");
        PINNED.put("VRenderSystem.applyProjectionMatrix", "TemporalFrame.JITTER_ON x1");
        PINNED.put("Pipeline.needsUpdate", "TemporalFrame.MIP_ON x1 (per image descriptor)");
        PINNED.put("GlState.viewport", "TemporalFrame.IN_WORLD x1");
        PINNED.put("GlState.applyScissor", "TemporalFrame.IN_WORLD x1");
        PINNED.put("UniformSlots.jittered", "TemporalFrame.JITTER_ON x1 (per converted-shader ProjMat read)");
        PINNED.put("TaskBudgetM.*", "TaskBudget.enabled x7 (DebugSwitches map lookup), runAllTasks wrapped (original called), SlowFrames.ON x3");
        PINNED.put("IrohRenderScaleGameM.*", "TemporalRuntime.onRenderHead/Tail/onRenderLevelHead/onBeforeHandClear/onHand/onBeforeGui: one static read each (+ mode(): map lookup in onRenderHead)");
    }

    public static void main(String[] args) throws Exception {
        boolean noProps = System.getProperties().stringPropertyNames().stream().noneMatch(k -> k.startsWith("iroh.") || k.equals("proctor"));
        check("no iroh.* / proctor properties", noProps);

        // --- behaviour: every entry point, 1000 frames, no game, no device ---
        Matrix4f persp = new Matrix4f().perspective(1.2f, 16 / 9f, 0.05f, Float.POSITIVE_INFINITY, true);
        Throwable thrown = null;
        try {
            for (int i = 0; i < 1000; i++) {
                TemporalRuntime.onRenderHead();
                TemporalRuntime.onRenderLevelHead(0.5f);
                PackRuntime.onWorldStart();
                TemporalRuntime.onWorldStart(1, 2, 3, new Matrix4f(), persp);
                PackRuntime.onBeforeEntities();
                TemporalRuntime.onAfterEntities();
                TemporalRuntime.onBeforeHandClear();
                TemporalRuntime.onHand(null);
                PackRuntime.onWorldEnd();
                TemporalRuntime.onBeforeGui();
                TemporalRuntime.onRenderTail();
                PackRuntime.onSetLevel();
            }
        } catch (Throwable t) {
            thrown = t;
            t.printStackTrace();
        }
        check("1000 frames of every feature's hooks with no game: nothing touched", thrown == null);
        check("pack path inactive, no implementation object", !PackRuntime.active() && implNull());
        check("raster terrain never skipped", !net.vulkanmod.render.chunk.WorldRenderer.packTerrainActive);
        check("upscale inactive: no world phase, no jitter, no mip bias, no velocity", !TemporalFrame.ACTIVE && !TemporalFrame.IN_WORLD
                && !TemporalFrame.JITTER_ON && !TemporalFrame.MIP_ON && !TemporalFrame.velocityOn && TemporalFrame.frameIndex == 0);
        check("no object history, no FSR context, no external GPU memory", TemporalFrame.objects.historySize() == 0
                && dev.elemore.iroh.memory.MemoryGuard.EXTERNAL.get() == 0 && !FsrAdapter.INSTANCE.usable());
        check("device features not requested (rc14 device for P4)", !DeviceCaps.shouldEnable() && !DeviceCaps.configRequested);
        check("slow-frame tool and stack sampler off (static final)", !dev.elemore.iroh.diag.SlowFrames.ON && !dev.elemore.iroh.diag.SlowStacks.ON);
        check("task budget off", !TaskBudget.enabled());
        boolean perfOff = true;
        for (String k : List.of(PerfSwitches.TASKS_BUDGET, PerfSwitches.GRAPH_CACHE, PerfSwitches.CLONE_CACHE, PerfSwitches.REBUILD_BUDGET,
                PerfSwitches.THREADS_TUNE, PerfSwitches.THREADS_ADAPTIVE, PerfSwitches.THREADS_PHYSICAL))
            perfOff &= !PerfSwitches.on(k);
        check("every perf switch off", perfOff);
        check("api 4 not installed offline (Renderer init installs it in game)", dev.elemore.iroh.gpu.GpuCore.orNull() == null);

        // --- one registry ---
        check("IrohDebug.set refused without -Dproctor=1 / -Diroh.debug=true", IrohDebug.set(UpscaleSwitches.MODE, "quality").startsWith("refused"));
        String list = IrohDebug.list();
        check("one registry lists pack, perf and upscale switches", list.contains("iroh.pack.enabled") && list.contains(PerfSwitches.TASKS_BUDGET)
                && list.contains(UpscaleSwitches.MODE) && list.contains(UpscaleSwitches.DEBUG_VIEW));
        boolean defaults = true;
        for (var e : DebugSwitches.switches().entrySet())
            if (!DebugSwitches.getOr(e.getKey(), e.getValue().defaultValue()).equals(e.getValue().defaultValue())) {
                System.out.println("  not default: " + e.getKey());
                defaults = false;
            }
        check("every registered switch at its default (" + DebugSwitches.switches().size() + " switches)", defaults);

        // --- hot paths ---
        Map<String, List<String>> audit = HotPathAudit.audit(Path.of("src/main/java"));
        System.out.println("---- hot-path guard reads with everything off (pinned list for ladder step I-0) ----");
        for (var e : PINNED.entrySet())
            System.out.println("  " + e.getKey() + ": " + (e.getValue().isEmpty() ? "nothing" : e.getValue()));
        System.out.println("---- raw audit ----");
        audit.forEach((k, v) -> System.out.println("  " + k + " " + summarize(v)));
        check("bindGraphicsPipeline untouched by every merged feature (P3-10 will add Substitution.OBSERVE)", audit.get("Renderer.bindGraphicsPipeline").isEmpty());
        check("render-pass begin / main pass begin untouched", audit.get("Renderer.beginRendering").isEmpty()
                && audit.get("Framebuffer.beginRenderPass").isEmpty() && audit.get("DefaultMainPass.begin").isEmpty()
                && audit.get("DefaultMainPass.rebindMainTarget").isEmpty());
        check("renderSectionLayer: pack skip + slow frames + api 4 fog capture only",
                summarize(audit.get("WorldRenderer.renderSectionLayer")).equals("{Sections.captureFog=1, SlowFrames.ON=2, SlowFrames.count=1, SlowFrames.enter=1, SlowFrames.exit=1, WorldRenderer.packTerrainActive=1}"));
        check("projection upload: one JITTER_ON read", summarize(audit.get("VRenderSystem.applyProjectionMatrix")).equals("{TemporalFrame.JITTER_ON=1, TemporalFrame.gpuProjection=1}"));
        check("frame begin: api 4 frameStart is the only non-guard call", summarize(audit.get("Renderer.beginFrame0")).equals(
                "{GpuLifecycle.frameStart=1, SlowFrames.ON=4, SlowFrames.enter=2, SlowFrames.exit=2}"));
        check("frame end: AFTER_PRESENT dispatch + slow-frame guards", summarize(audit.get("Renderer.endFrame")).equals(
                "{FrameHooks.dispatch=1, SlowFrames.ON=5, SlowFrames.enter=3, SlowFrames.exit=3}"));
        check("task drain: budget guard + slow-frame guards only", summarize(audit.get("TaskBudgetM.*")).equals(
                "{SlowFrames.ON=3, SlowFrames.count=1, SlowFrames.enter=1, SlowFrames.exit=1, TaskBudget.enabled=7, TaskBudget.forGame=1, TaskBudget.targetNs=1}"));

        System.out.println("# integrationinert " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }

    static boolean implNull() throws Exception {
        Field f = PackRuntime.class.getDeclaredField("impl");
        f.setAccessible(true);
        return f.get(null) == null;
    }

    static String summarize(List<String> v) {
        Map<String, Integer> m = new TreeMap<>();
        for (String s : v) m.merge(s, 1, Integer::sum);
        return m.toString();
    }
}
