package dev.elemore.iroh.inttest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * INT-1: static audit of what the integrated features add to the per-frame hot paths. For each hot method the body is
 * cut out of the source (brace matching) and every feature guard / hook call in it is listed. The pinned list in
 * IntegrationInertTest is "what the first in-game run must show costs nothing"; a new hook changes the list and fails
 * the test until it is reviewed and re-pinned.
 */
public final class HotPathAudit {
    /** file -> method names (all overloads / lambdas inside the body are included) */
    public static final Map<String, String[]> HOT = new LinkedHashMap<>();
    static {
        HOT.put("net/vulkanmod/vulkan/Renderer.java", new String[]{"bindGraphicsPipeline", "preInitFrame", "preInitFrame0", "beginFrame", "beginFrame0", "endFrame", "beginRendering", "endRenderPass"});
        HOT.put("net/vulkanmod/vulkan/framebuffer/Framebuffer.java", new String[]{"beginRenderPass"});
        HOT.put("net/vulkanmod/vulkan/framebuffer/SceneTarget.java", new String[]{"getFramebufferId"});
        HOT.put("net/vulkanmod/vulkan/pass/DefaultMainPass.java", new String[]{"begin", "end", "rebindMainTarget"});
        HOT.put("net/vulkanmod/render/chunk/WorldRenderer.java", new String[]{"renderSectionLayer"});
        HOT.put("net/vulkanmod/vulkan/VRenderSystem.java", new String[]{"applyProjectionMatrix"});
        HOT.put("net/vulkanmod/vulkan/shader/Pipeline.java", new String[]{"needsUpdate", "updateDescriptorSet"});
        HOT.put("dev/elemore/iroh/glshim/GlState.java", new String[]{"viewport", "applyScissor"});
        HOT.put("dev/elemore/iroh/shader/UniformSlots.java", new String[]{"jittered"});
        HOT.put("dev/elemore/iroh/apiimpl/WorldStages.java", new String[]{"register"});
        HOT.put("net/vulkanmod/mixin/render/frame/TaskBudgetM.java", new String[]{"*"});
        HOT.put("net/vulkanmod/mixin/render/upscale/IrohRenderScaleGameM.java", new String[]{"*"});
        HOT.put("net/vulkanmod/mixin/render/upscale/IrohRenderScaleLevelM.java", new String[]{"*"});
        HOT.put("net/vulkanmod/mixin/profiling/slow/SlowFramesGameRendererM.java", new String[]{"*"});
        HOT.put("net/vulkanmod/mixin/profiling/slow/SlowFramesLevelM.java", new String[]{"*"});
        HOT.put("net/vulkanmod/mixin/profiling/slow/SlowFramesLevel2M.java", new String[]{"*"});
        HOT.put("net/vulkanmod/mixin/profiling/slow/SlowFramesMinecraftM.java", new String[]{"*"});
        HOT.put("net/vulkanmod/mixin/profiling/slow/SlowFramesTaskM.java", new String[]{"*"});
        HOT.put("net/vulkanmod/mixin/profiling/slow/SlowFramesChunkM.java", new String[]{"*"});
        // INT-2: per-frame / per-update paths of the perf switches and api 4
        HOT.put("net/vulkanmod/render/chunk/graph/SectionGraph.java", new String[]{"update", "scheduleRebuilds0"});
        HOT.put("net/vulkanmod/render/chunk/build/RenderRegionBuilder.java", new String[]{"beginBatch"});
        HOT.put("net/vulkanmod/render/chunk/build/task/TaskDispatcher.java", new String[]{"adaptBuilders", "doSectionUpdate", "updateSections0"});
        HOT.put("net/vulkanmod/mixin/chunk/BulkDirtyM.java", new String[]{"*"}); // P2-4
        HOT.put("net/vulkanmod/render/chunk/RenderSection.java", new String[]{"reset"});
    }

    /**
     * INT-2: the helpers the hot paths call must return at once through a plain static read when everything is off.
     * file#method -> the token their FIRST statement must contain; and no switch-map lookup (DebugSwitches) may run
     * before it. A helper that grows a map lookup or work before its guard fails integrationInertTest.
     */
    public static final Map<String, String> CALLEE_GUARD = new LinkedHashMap<>();
    static {
        CALLEE_GUARD.put("dev/elemore/iroh/perf/TaskBudget.java#enabled", "PerfSwitches.tasksBudgetOn");
        CALLEE_GUARD.put("dev/elemore/iroh/perf/IntakeBudget.java#enabled", "PerfSwitches.intakeBudgetOn");
        CALLEE_GUARD.put("dev/elemore/iroh/perf/AreaArena.java#on", "PerfSwitches.areaArenaOn");
        CALLEE_GUARD.put("net/vulkanmod/render/chunk/graph/SectionGraph.java#cacheEnabled", "PerfSwitches.graphCacheOn");
        CALLEE_GUARD.put("net/vulkanmod/render/chunk/graph/SectionGraph.java#rebuildBudget", "PerfSwitches.rebuildBudgetOn");
        CALLEE_GUARD.put("net/vulkanmod/render/chunk/build/RenderRegionBuilder.java#beginBatch", "clones.isEmpty()");
        CALLEE_GUARD.put("net/vulkanmod/render/chunk/build/task/TaskDispatcher.java#adaptBuilders", "this.adaptive");
        CALLEE_GUARD.put("dev/elemore/iroh/temporal/TemporalRuntime.java#onRenderHead", "UpscaleSwitches.modeSet");
        CALLEE_GUARD.put("dev/elemore/iroh/temporal/TemporalRuntime.java#onRenderTail", "inGameRender");
        CALLEE_GUARD.put("dev/elemore/iroh/temporal/TemporalRuntime.java#onRenderLevelHead", "TemporalFrame.ACTIVE");
        CALLEE_GUARD.put("dev/elemore/iroh/temporal/TemporalRuntime.java#onBeforeHandClear", "TemporalFrame.IN_WORLD");
        CALLEE_GUARD.put("dev/elemore/iroh/temporal/TemporalRuntime.java#onHand", "velocityFrame()");
        CALLEE_GUARD.put("dev/elemore/iroh/temporal/TemporalRuntime.java#velocityFrame", "TemporalFrame.IN_WORLD");
        CALLEE_GUARD.put("dev/elemore/iroh/temporal/TemporalRuntime.java#onBeforeGui", "TemporalFrame.IN_WORLD");
        CALLEE_GUARD.put("dev/elemore/iroh/temporal/TemporalRuntime.java#chainWidth", "TemporalFrame.ACTIVE");
        CALLEE_GUARD.put("dev/elemore/iroh/temporal/TemporalRuntime.java#chainHeight", "TemporalFrame.ACTIVE");
        CALLEE_GUARD.put("dev/elemore/iroh/apiimpl/Sections.java#captureFog", "fogWanted");
        CALLEE_GUARD.put("dev/elemore/iroh/apiimpl/Sections.java#any", "LISTENERS.isEmpty()");
        CALLEE_GUARD.put("dev/elemore/iroh/gpu/game/GpuLifecycle.java#frameStart", "device");
        CALLEE_GUARD.put("dev/elemore/iroh/gpu/game/GpuLifecycle.java#worldPhase", "worldPhase = active");
        CALLEE_GUARD.put("dev/elemore/iroh/shaderpack/runtime/PackRuntime.java#onWorldEnd", "!active");
        CALLEE_GUARD.put("dev/elemore/iroh/shaderpack/runtime/PackRuntime.java#onBeforeEntities", "!active");
        CALLEE_GUARD.put("dev/elemore/iroh/shaderpack/runtime/PackRuntime.java#onWorldStart", "impl");
        CALLEE_GUARD.put("dev/elemore/iroh/apiimpl/FrameHooks.java#dispatch", "FrameProfiler.ON");
    }

    /** CALLEE_GUARD violations (empty = every helper returns through its plain static read) */
    public static List<String> calleeViolations(Path src) throws Exception {
        List<String> bad = new ArrayList<>();
        for (var e : CALLEE_GUARD.entrySet()) {
            String[] fm = e.getKey().split("#");
            List<String> bodies = bodies(Files.readString(src.resolve(fm[0])), fm[1]);
            if (bodies.isEmpty()) {
                bad.add(e.getKey() + ": method not found");
                continue;
            }
            for (String b : bodies) {
                String body = stripComments(b).substring(1).trim();
                int end = body.indexOf(';');
                String first = end < 0 ? body : body.substring(0, end);
                if (!first.contains(e.getValue()) || first.contains("DebugSwitches"))
                    bad.add(e.getKey() + ": first statement is '" + first.replaceAll("\\s+", " ") + "', expected a plain read of " + e.getValue());
            }
        }
        return bad;
    }

    /** feature guards and hook calls: the things that run when everything is off */
    static final Pattern GUARD = Pattern.compile(
            "(?:dev\\.elemore\\.iroh\\.[\\w.]+\\.)?(Sections|SlowFrames|SlowStacks|TemporalFrame|PackRuntime|Substitution|TaskBudget|IntakeBudget|AreaArena|PerfSwitches|DebugSwitches|UpscaleSwitches|FrameHooks|GpuLifecycle|TemporalRuntime|FrameProfiler|EmuStats|SectionGraph|ThreadPolicy)"
                    + "\\s*\\.\\s*(\\w+)");

    public static Map<String, List<String>> audit(Path src) throws Exception {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (var e : HOT.entrySet()) {
            String text = Files.readString(src.resolve(e.getKey()));
            for (String m : e.getValue()) {
                List<String> bodies = m.equals("*") ? List.of(text) : bodies(text, m);
                List<String> hits = new ArrayList<>();
                for (String b : bodies) {
                    Matcher mm = GUARD.matcher(stripComments(b));
                    while (mm.find())
                        if (!mm.group(2).equals("Phase") && !mm.group(2).equals("Counter"))
                            hits.add(mm.group(1) + "." + mm.group(2));
                    Matcher pt = Pattern.compile("\\bpackTerrainActive\\b").matcher(stripComments(b));
                    while (pt.find())
                        hits.add("WorldRenderer.packTerrainActive");
                    // INT-2: unqualified / instance calls of the perf-switch helpers
                    Matcher uq = Pattern.compile("(?<![\\w.])(cacheEnabled|rebuildBudget)\\s*\\(|\\.(beginBatch)\\s*\\(").matcher(stripComments(b));
                    while (uq.find())
                        hits.add(uq.group(1) != null ? "SectionGraph." + uq.group(1) : "RenderRegionBuilder." + uq.group(2));
                }
                out.put(e.getKey().replaceAll(".*/", "").replace(".java", "") + "." + m, hits);
            }
        }
        return out;
    }

    static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /** bodies of methods named {@code name} (declaration = name followed by '(' ... ')' ... '{' at statement level) */
    static List<String> bodies(String text, String name) {
        List<String> r = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^\\s+(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:public|private|protected|static|final|synchronized|\\s)+[\\w<>\\[\\],.? ]+\\s" + name + "\\s*\\(").matcher(text);
        while (m.find()) {
            int brace = text.indexOf('{', m.end());
            int semi = text.indexOf(';', m.end());
            if (brace < 0 || (semi >= 0 && semi < brace)) continue;
            int depth = 0, i = brace;
            for (; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) break;
            }
            r.add(text.substring(brace, i + 1));
        }
        return r;
    }

    public static void main(String[] args) throws Exception {
        audit(Path.of("src/main/java")).forEach((k, v) -> System.out.println(k + " " + v));
    }
}
