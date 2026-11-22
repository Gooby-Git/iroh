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
    }

    /** feature guards and hook calls: the things that run when everything is off */
    static final Pattern GUARD = Pattern.compile(
            "(?:dev\\.elemore\\.iroh\\.[\\w.]+\\.)?(Sections|SlowFrames|SlowStacks|TemporalFrame|PackRuntime|Substitution|TaskBudget|PerfSwitches|DebugSwitches|FrameHooks|GpuLifecycle|TemporalRuntime|FrameProfiler|EmuStats)"
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
