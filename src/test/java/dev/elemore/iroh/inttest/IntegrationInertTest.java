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
        // INT-2 minimal list: every entry is a plain static read (static final, or one volatile/plain static field)
        PINNED.put("Renderer.bindGraphicsPipeline", "Substitution.HOOK x1 (volatile int; INT-3: P3-10 observe / P3-11 world pass, SubstitutionHook only when non-zero)");
        PINNED.put("Renderer.uploadAndBindUBOs", "Substitution.HOOK x1 per draw (volatile int; INT-3: P3-11)");
        PINNED.put("Renderer.endRenderPass", "Substitution.HOOK x1 (volatile int, bit WORLD: close the world pass first; INT-3: P3-11)");
        PINNED.put("VulkanImage.endPassRunResume", "Substitution.HOOK x1 (volatile int, bit WORLD; INT-3: P3-11)");
        PINNED.put("Renderer.preInitFrame", "SlowFrames.ON x2 (static final)");
        PINNED.put("Renderer.beginFrame0", "SlowFrames.ON x4 (static final), GpuLifecycle.frameStart x1 (one volatile null test: api 4 is lazy)");
        PINNED.put("Renderer.endFrame", "SlowFrames.ON x5 (static final), FrameHooks.dispatch x1 (AFTER_PRESENT: FrameProfiler.ON + empty-array test)");
        PINNED.put("Renderer.beginRenderPass", "(audited since INT-3) FrameProfiler.ON x1 (static final), FrameHooks.beginFrame x1 (context fields, no guard needed), FrameHooks.dispatch x2 (BEFORE_MAIN_PASS, FRAME_BEGIN: empty-array tests), GpuCore.orNull x2 (UPLOAD-SYNC deferred-upload drain before BEFORE_MAIN_PASS + INT-3 api 4.1 settle after it: one volatile null test, api 4 is lazy)");
        PINNED.put("Renderer.beginRendering", "Substitution.HOOK x1 (volatile int, bit WORLD; INT-3: P3-11)");
        PINNED.put("Framebuffer.beginRenderPass", "");
        PINNED.put("DefaultMainPass.begin", "");
        PINNED.put("DefaultMainPass.end", "Substitution.HOOK x1 (volatile int; WORLD is a constant): a world pass still live at frame end turns off (INT-3)");
        PINNED.put("IrohDrawIdsM.* / IrohBlockEntityIdsM.*", "Substitution.HOOK x1 at HEAD and x1 at RETURN per entity / block entity render (volatile int; INT-3: P3-11 draw ids)");
        PINNED.put("IrohWeatherContextM.*", "Substitution.HOOK x1 at renderSnowAndRain HEAD, a plain static store at RETURN (INT-3: P3-11)");
        PINNED.put("DefaultMainPass.rebindMainTarget", "");
        PINNED.put("WorldRenderer.renderSectionLayer", "packTerrainActive x1 (volatile), SlowFrames.ON x2, Sections.captureFog x1 (solid layer; one volatile read until a mod asks for the fog), PackRuntime.onTranslucentTerrain x1 (only inside the pack-terrain branch; first statement !active; INT-3: P3-11)");
        PINNED.put("VRenderSystem.applyProjectionMatrix", "TemporalFrame.JITTER_ON x1 (plain static)");
        PINNED.put("Pipeline.needsUpdate", "TemporalFrame.MIP_ON x1 per image descriptor (plain static; IN_WORLD only read when MIP_ON)");
        PINNED.put("GlState.viewport", "TemporalFrame.IN_WORLD x1 (plain static)");
        PINNED.put("GlState.applyScissor", "TemporalFrame.IN_WORLD x1 (plain static)");
        PINNED.put("UniformSlots.jittered", "TemporalFrame.JITTER_ON x1 per converted-shader ProjMat read (plain static)");
        PINNED.put("TaskBudgetM.*", "TaskBudget.enabled x7 = PerfSwitches.tasksBudgetOn (one volatile read each; was a switch-map lookup), runAllTasks wrapped (original called), SlowFrames.ON x3");
        PINNED.put("IrohRenderScaleGameM.*", "TemporalRuntime.onRenderHead (UpscaleSwitches.modeSet volatile) / onRenderTail / onRenderLevelHead / onBeforeHandClear / onHand / onBeforeGui / chainWidth / chainHeight: one plain static read each");
        PINNED.put("SectionGraph.update", "SectionGraph.cacheEnabled x1 = PerfSwitches.graphCacheOn (volatile), SlowFrames.ON");
        PINNED.put("SectionGraph.scheduleRebuilds0", "rebuildBudget x1 = PerfSwitches.rebuildBudgetOn (volatile), RenderRegionBuilder.beginBatch (cloneCacheOn volatile), SlowFrames.ON");
        PINNED.put("TaskDispatcher.adaptBuilders", "this.adaptive null test (adaptive builders off)");
        PINNED.put("AbstractBlockRenderContext.shadeQuad", "RtMeshMode.tintOnly x1 per quad (volatile boolean; P5-2 RT tint-only colour)");
        PINNED.put("TaskDispatcher.doSectionUpdate / RenderSection.reset", "Sections.any x1 (CopyOnWriteArrayList.isEmpty: one volatile read) per section");
        PINNED.put("WorldStages.register", "TemporalFrame.IN_WORLD x2, PackRuntime.onWorldStart/onAfterSky (INT-3: P3-11)/onBeforeEntities/onWorldEnd (one volatile read each; no write when off), FrameHooks.dispatch x7 (empty-array tests)");
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
        check("api 4 not installed offline", dev.elemore.iroh.gpu.GpuCore.orNull() == null);
        // INT-2 (RQ-INT1-a): what Renderer init does in game creates NOTHING until a mod calls api 4
        dev.elemore.iroh.gpu.game.GpuLifecycle.install(3, 1920, 1080);
        for (int i = 0; i < 1000; i++) {
            dev.elemore.iroh.gpu.game.GpuLifecycle.frameStart(i % 3);
            dev.elemore.iroh.apiimpl.FrameHooks.dispatch(dev.elemore.iroh.api.FrameStage.BEFORE_MAIN_PASS);
            dev.elemore.iroh.apiimpl.FrameHooks.dispatch(dev.elemore.iroh.api.FrameStage.AFTER_PRESENT);
        }
        check("api 4 lazy: Renderer-init install + 1000 frames created no device, no descriptor pool (state "
                + dev.elemore.iroh.gpu.game.GpuLifecycle.state() + ")", dev.elemore.iroh.gpu.GpuCore.orNull() == null
                && !dev.elemore.iroh.gpu.game.GpuLifecycle.installed() && dev.elemore.iroh.gpu.game.GpuLifecycle.available());
        dev.elemore.iroh.gpu.game.GpuLifecycle.resetForTest();
        check("fog capture not armed (no FogState per frame) until a mod asks", !dev.elemore.iroh.apiimpl.Sections.fogWanted());
        check("per-frame guards are plain flags and all off", !PerfSwitches.tasksBudgetOn && !PerfSwitches.graphCacheOn
                && !PerfSwitches.cloneCacheOn && !PerfSwitches.rebuildBudgetOn && !UpscaleSwitches.modeSet);

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
        check("bindGraphicsPipeline: only the ONE Substitution.HOOK read (INT-3)", audit.get("Renderer.bindGraphicsPipeline").equals(List.of("Substitution.HOOK")));
        check("render-pass begin: only the ONE Substitution.HOOK read; main pass begin untouched", audit.get("Renderer.beginRendering").equals(List.of("Substitution.HOOK"))
                && audit.get("Framebuffer.beginRenderPass").isEmpty() && audit.get("DefaultMainPass.begin").isEmpty()
                && audit.get("DefaultMainPass.rebindMainTarget").isEmpty());
        Map<String, String> expect = new TreeMap<>();
        expect.put("WorldRenderer.renderSectionLayer", "{PackRuntime.onTranslucentTerrain=1, Sections.captureFog=1, SlowFrames.ON=2, SlowFrames.count=1, SlowFrames.enter=1, SlowFrames.exit=1, WorldRenderer.packTerrainActive=1}");
        expect.put("VRenderSystem.applyProjectionMatrix", "{TemporalFrame.JITTER_ON=1, TemporalFrame.gpuProjection=1}");
        expect.put("Renderer.beginFrame0", "{GpuLifecycle.frameStart=1, SlowFrames.ON=4, SlowFrames.enter=2, SlowFrames.exit=2}");
        expect.put("Renderer.endFrame", "{FrameHooks.dispatch=1, SlowFrames.ON=5, SlowFrames.enter=3, SlowFrames.exit=3}");
        expect.put("TaskBudgetM.*", "{SlowFrames.ON=3, SlowFrames.count=1, SlowFrames.enter=1, SlowFrames.exit=1, TaskBudget.enabled=7, TaskBudget.forGame=1, TaskBudget.targetNs=1}");
        expect.put("SectionGraph.update", "{SectionGraph.cacheEnabled=1, SlowFrames.ON=1, SlowFrames.count=1}");
        expect.put("SectionGraph.scheduleRebuilds0", "{RenderRegionBuilder.beginBatch=1, SectionGraph.rebuildBudget=1, SlowFrames.ON=1, SlowFrames.count=1}");
        expect.put("TaskDispatcher.adaptBuilders", "{}");
        expect.put("TaskDispatcher.doSectionUpdate", "{Sections.any=1, Sections.built=1}");
        expect.put("RenderSection.reset", "{Sections.any=1, Sections.reset=1}");
        expect.put("AbstractBlockRenderContext.shadeQuad", "{RtMeshMode.tintOnly=1}"); // P5-2: one volatile read per quad
        // every other hot path, exactly as audited (INT-2): any new guard or hook fails until reviewed and re-pinned
        expect.put("Renderer.bindGraphicsPipeline", "{Substitution.HOOK=1}"); // INT-3: P3-10/P3-11
        expect.put("Renderer.uploadAndBindUBOs", "{Substitution.HOOK=1}"); // INT-3: P3-11, per draw
        expect.put("VulkanImage.endPassRunResume", "{Substitution.HOOK=1}"); // INT-3: P3-11
        expect.put("Renderer.preInitFrame", "{SlowFrames.ON=2, SlowFrames.enter=1, SlowFrames.exit=1}");
        expect.put("Renderer.preInitFrame0", "{SlowFrames.ON=2, SlowFrames.enter=1, SlowFrames.exit=1}");
        expect.put("Renderer.beginFrame", "{SlowFrames.ON=2, SlowFrames.enter=1, SlowFrames.exit=1}");
        expect.put("Renderer.beginRenderPass", "{FrameHooks.beginFrame=1, FrameHooks.dispatch=2, FrameProfiler.ON=1, FrameProfiler.frameStart=1, GpuCore.orNull=2}"); // INT-3 (api 4.1 settle) + UPLOAD-SYNC (deferred uploads drained before the hooks)
        expect.put("Renderer.beginRendering", "{Substitution.HOOK=1}"); // INT-3: P3-11
        expect.put("Renderer.endRenderPass", "{Substitution.HOOK=1}"); // INT-3: P3-11
        expect.put("Framebuffer.beginRenderPass", "{}");
        expect.put("SceneTarget.getFramebufferId", "{}");
        expect.put("DefaultMainPass.begin", "{}");
        expect.put("DefaultMainPass.end", "{Substitution.HOOK=1, Substitution.WORLD=1}"); // INT-3: W live at frame end
        expect.put("IrohDrawIdsM.*", "{Substitution.HOOK=2, Substitution.WORLD=2}"); // INT-3: P3-11 per entity
        expect.put("IrohBlockEntityIdsM.*", "{Substitution.HOOK=2, Substitution.WORLD=2}"); // INT-3: P3-11 per block entity
        expect.put("IrohWeatherContextM.*", "{Substitution.HOOK=1, Substitution.WORLD=1}"); // INT-3: P3-11 weather HEAD
        expect.put("DefaultMainPass.rebindMainTarget", "{}");
        expect.put("Pipeline.needsUpdate", "{TemporalFrame.IN_WORLD=1, TemporalFrame.MIP_ON=1, TemporalFrame.samplerBias=1}");
        expect.put("Pipeline.updateDescriptorSet", "{TemporalFrame.IN_WORLD=1, TemporalFrame.MIP_ON=1, TemporalFrame.samplerBias=1}");
        expect.put("GlState.viewport", "{TemporalFrame.IN_WORLD=1, TemporalFrame.fixRect=1}");
        expect.put("GlState.applyScissor", "{TemporalFrame.IN_WORLD=1, TemporalFrame.fixRect=1}");
        expect.put("UniformSlots.jittered", "{TemporalFrame.IN_WORLD=1, TemporalFrame.JITTER_ON=1, TemporalFrame.gpuProjection=1}");
        expect.put("WorldStages.register", "{FrameHooks.dispatch=7, FrameHooks.setInWorld=2, FrameHooks.setWorld=1, PackRuntime.onAfterSky=1, PackRuntime.onBeforeEntities=1, PackRuntime.onWorldEnd=1, PackRuntime.onWorldStart=1, TemporalFrame.IN_WORLD=2, TemporalRuntime.onAfterEntities=1, TemporalRuntime.onWorldStart=1}");
        expect.put("IrohRenderScaleGameM.*", "{TemporalRuntime.chainHeight=1, TemporalRuntime.chainWidth=1, TemporalRuntime.onBeforeGui=1, TemporalRuntime.onBeforeHandClear=1, TemporalRuntime.onHand=1, TemporalRuntime.onRenderHead=1, TemporalRuntime.onRenderLevelHead=1, TemporalRuntime.onRenderTail=1}");
        expect.put("IrohRenderScaleLevelM.*", "{TemporalRuntime.chainHeight=1, TemporalRuntime.chainWidth=1}");
        expect.put("SlowFramesGameRendererM.*", "{SlowFrames.ON=4, SlowFrames.enter=2, SlowFrames.exit=2}");
        expect.put("SlowFramesLevelM.*", "{SlowFrames.ON=10, SlowFrames.count=1, SlowFrames.enter=5, SlowFrames.exit=5}");
        expect.put("SlowFramesLevel2M.*", "{SlowFrames.ON=4, SlowFrames.enter=2, SlowFrames.exit=2}");
        expect.put("SlowFramesMinecraftM.*", "{SlowFrames.ON=7, SlowFrames.count=1, SlowFrames.enter=2, SlowFrames.exit=2, SlowFrames.frameEnd=1, SlowFrames.frameStart=1, SlowFrames.quit=1, SlowFrames.set=3, SlowStacks.ON=1, SlowStacks.setWorldActive=1}");
        expect.put("SlowFramesTaskM.*", "{SlowFrames.ON=2, SlowFrames.now=2, SlowFrames.task=1}");
        expect.put("SlowFramesChunkM.*", "{SlowFrames.ON=2, SlowFrames.count=1, SlowFrames.enter=1, SlowFrames.exit=1}");
        expect.put("RenderRegionBuilder.beginBatch", "{PerfSwitches.cloneCacheOn=1}");
        check("every audited hot path is pinned (" + audit.size() + ")", expect.keySet().containsAll(audit.keySet()));
        for (var e : expect.entrySet()) {
            String got = summarize(audit.get(e.getKey()));
            check("hot path " + e.getKey() + " = pinned " + e.getValue() + " (got " + got + ")", got.equals(e.getValue()));
        }
        List<String> callee = HotPathAudit.calleeViolations(Path.of("src/main/java"));
        callee.forEach(v -> System.out.println("  callee: " + v));
        check("every helper a hot path calls returns through ONE plain static read when off (" + HotPathAudit.CALLEE_GUARD.size() + " helpers)", callee.isEmpty());
        check("no hot path or helper reads the switch map (DebugSwitches) per frame", audit.values().stream().flatMap(List::stream).noneMatch(x -> x.startsWith("DebugSwitches")));

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
