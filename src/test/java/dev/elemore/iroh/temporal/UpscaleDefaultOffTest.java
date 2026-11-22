package dev.elemore.iroh.temporal;

import dev.elemore.iroh.api.IrohDebug;
import dev.elemore.iroh.debug.DebugSwitches;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * P4-1a default-off (pattern of card/P3-4's PackDefaultOffTest): without {@code iroh.upscale.mode} every entry point
 * the renderer calls is inert. This test runs with NO game and NO Vulkan device: any hook that went past its "off"
 * test would touch Renderer / Minecraft and throw. Also: no device feature requested, projections untouched (same
 * object back), ProjMat supplier returns vanilla's own buffer, viewports untouched, post chains keep their size,
 * IrohDebug refuses without -Dproctor=1 / -Diroh.debug=true, and the hooks sit in exactly the expected files.
 * {@code ./gradlew upscaleDefaultOffTest}; last line {@code # upscaledefaultoff PASS n/n}.
 */
public final class UpscaleDefaultOffTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    public static void main(String[] args) throws Exception {
        check("property unset", System.getProperty(UpscaleSwitches.MODE) == null);
        check("mode() OFF", UpscaleSwitches.mode() == ScaleMode.OFF);
        check("no device features requested", !DeviceCaps.shouldEnable());

        Matrix4f persp = new Matrix4f().perspective(1.2f, 16 / 9f, 0.05f, Float.POSITIVE_INFINITY, true);
        boolean threw = false;
        try {
            for (int i = 0; i < 1000; i++) {
                TemporalRuntime.onRenderHead();
                TemporalRuntime.onRenderLevelHead(0.5f);
                TemporalRuntime.onAfterEntities();
                TemporalRuntime.onBeforeHandClear();
                TemporalRuntime.onHand(null);
                TemporalRuntime.onWorldStart(1, 2, 3, new Matrix4f(), persp);
                TemporalRuntime.onBeforeGui();
                TemporalRuntime.onRenderTail();
            }
        } catch (Throwable t) {
            threw = true;
            t.printStackTrace();
        }
        check("1000 frames of hooks with no game: nothing touched", !threw);
        check("inactive", !TemporalFrame.ACTIVE && !TemporalFrame.IN_WORLD && !TemporalFrame.JITTER_ON && !TemporalFrame.MIP_ON);
        check("no velocity, no debug view", !TemporalFrame.velocityOn && TemporalFrame.debugMode == 0);
        check("frame counter never advanced", TemporalFrame.frameIndex == 0);
        check("GPU projection is the same object", TemporalFrame.gpuProjection(persp) == persp);
        float[] a = new float[16], b = new float[16];
        persp.get(a);
        persp.get(b);
        TemporalFrame.gpuProjection(a);
        check("ProjMat float[] untouched", java.util.Arrays.equals(a, b));
        int[] r = {0, 0, 2560, 1440};
        check("viewport untouched", !TemporalFrame.fixRect(r) && r[2] == 2560 && r[3] == 1440);
        check("P4-1b: no object history, no hand, no FSR memory counted while off", TemporalFrame.objects.historySize() == 0
                && !TemporalFrame.handValid && dev.elemore.iroh.memory.MemoryGuard.EXTERNAL.get() == 0 && !DeviceCaps.configRequested);
        check("P4-1b: FSR not usable without the device features (no native load attempted through the adapter)", !FsrAdapter.INSTANCE.usable());
        check("post chains keep the window size", TemporalRuntime.chainWidth(2560) == 2560 && TemporalRuntime.chainHeight(1440) == 1440);

        MappedBuffer vanilla = new MappedBuffer(64);
        Supplier<MappedBuffer> s = dev.elemore.iroh.shader.UniformSlots.jitteredForTest(() -> vanilla);
        check("ProjMat supplier returns vanilla's own buffer", s.get() == vanilla);

        check("IrohDebug.set refused without proctor/debug", IrohDebug.set(UpscaleSwitches.MODE, "quality").startsWith("refused"));
        check("refused set changed nothing", UpscaleSwitches.mode() == ScaleMode.OFF);
        TemporalRuntime.onRenderHead();
        check("still inactive after the refused set", !TemporalFrame.ACTIVE);
        check("mode default off", IrohDebug.get(UpscaleSwitches.MODE).equals("off"));
        check("debug view default off", IrohDebug.get(UpscaleSwitches.DEBUG_VIEW).equals("off"));
        check("jitter default auto", IrohDebug.get(UpscaleSwitches.JITTER).equals("auto"));
        threw = false;
        try { IrohDebug.get("iroh.upscale.nope"); } catch (IllegalArgumentException e) { threw = true; }
        check("unknown key throws", threw);
        threw = false;
        try { DebugSwitches.set(UpscaleSwitches.DEBUG_VIEW, "sideways"); } catch (IllegalArgumentException e) { threw = true; }
        check("bad value throws", threw);
        threw = false;
        try { DebugSwitches.set(UpscaleSwitches.MODE, "fastest"); } catch (IllegalArgumentException e) { threw = true; }
        check("bad mode throws", threw);
        String list = IrohDebug.list();
        check("list names every upscale switch", list.contains(UpscaleSwitches.MODE) && list.contains(UpscaleSwitches.DEBUG_VIEW)
                && list.contains(UpscaleSwitches.VELOCITY) && list.contains(UpscaleSwitches.MIP_BIAS));
        check("features switch is restart-only", DebugSwitches.set(UpscaleSwitches.FEATURES, "on").equals("restart"));
        DebugSwitches.set(UpscaleSwitches.FEATURES, "auto");

        // The velocity shader compiles (both variants) with the game's own shaderc settings.
        String src = VelocityPass.source();
        boolean compiled;
        try {
            net.vulkanmod.vulkan.shader.SPIRVUtils.compileShader("iroh_velocity.comp", src, net.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind.COMPUTE_SHADER).free();
            net.vulkanmod.vulkan.shader.SPIRVUtils.compileShader("iroh_velocity_debug.comp", src.replaceFirst("#version 450\n", "#version 450\n#define DEBUG_VIEW 1\n"),
                    net.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind.COMPUTE_SHADER).free();
            compiled = true;
        } catch (Throwable t) {
            t.printStackTrace();
            compiled = false;
        }
        check("velocity.comp compiles (base + debug)", compiled);

        // Wiring: the temporal package is referenced from exactly these files outside itself.
        Set<String> expected = new TreeSet<>(Set.of(
                "net/vulkanmod/vulkan/VRenderSystem.java", "dev/elemore/iroh/shader/UniformSlots.java",
                "net/vulkanmod/vulkan/shader/Pipeline.java", "dev/elemore/iroh/glshim/GlState.java",
                "net/vulkanmod/vulkan/pass/DefaultMainPass.java", "dev/elemore/iroh/apiimpl/WorldStages.java",
                "net/vulkanmod/vulkan/device/DeviceManager.java", "net/vulkanmod/vulkan/Vulkan.java",
                "net/vulkanmod/mixin/render/upscale/IrohRenderScaleGameM.java", "net/vulkanmod/mixin/render/upscale/IrohRenderScaleLevelM.java",
                "dev/elemore/iroh/api/IrohDebug.java", "net/vulkanmod/vulkan/framebuffer/SceneTarget.java",
                "dev/elemore/iroh/config/IrohOptions.java"));
        Set<String> found = new TreeSet<>();
        Path root = Path.of("src/main/java");
        for (Path p : Files.walk(root).filter(p -> p.toString().endsWith(".java") && !p.toString().contains("dev/elemore/iroh/temporal/") && !p.toString().contains("dev/elemore/iroh/upscale/")).toList())
            if (Files.readString(p).contains("dev.elemore.iroh.temporal"))
                found.add(root.relativize(p).toString());
        check("hooks exactly in " + expected + " (found " + found + ")", found.equals(expected));
        String gs = Files.readString(root.resolve("dev/elemore/iroh/glshim/GlState.java"));
        check("GlState hooks guarded by IN_WORLD", gs.split("TemporalFrame.IN_WORLD &&", -1).length - 1 == 2);
        String vr = Files.readString(root.resolve("net/vulkanmod/vulkan/VRenderSystem.java"));
        check("VRenderSystem hook guarded by JITTER_ON", vr.contains("TemporalFrame.JITTER_ON\n                ? dev.elemore.iroh.temporal.TemporalFrame.gpuProjection(mat) : mat"));
        String pl = Files.readString(root.resolve("net/vulkanmod/vulkan/shader/Pipeline.java"));
        check("Pipeline sampler hooks guarded by MIP_ON", pl.split("TemporalFrame.MIP_ON && dev.elemore.iroh.temporal.TemporalFrame.IN_WORLD", -1).length - 1 == 2);

        System.out.println("# upscaledefaultoff " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
