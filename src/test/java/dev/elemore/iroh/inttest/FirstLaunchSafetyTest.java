package dev.elemore.iroh.inttest;

import dev.elemore.iroh.api.IrohBuffers;
import dev.elemore.iroh.debug.DebugSwitches;
import dev.elemore.iroh.diag.FeatureFailures;
import dev.elemore.iroh.diag.StartupReport;
import dev.elemore.iroh.gpu.GpuCore;
import dev.elemore.iroh.gpu.game.GpuLifecycle;
import dev.elemore.iroh.shaderpack.runtime.PackRuntime;
import dev.elemore.iroh.temporal.DeviceCaps;
import dev.elemore.iroh.temporal.FsrSession;
import dev.elemore.iroh.temporal.TemporalRuntime;
import dev.elemore.iroh.temporal.UpscaleSwitches;
import dev.elemore.iroh.upscale.fsr.FsrUpscaler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * INT-2 first-launch safety net, offline (no game, no Vulkan device). For every optional feature its init is forced to
 * throw through the test seam ({@code FeatureFailures.inject}, i.e. {@code -Diroh.test.failInit=<feature>}) and the
 * fallback is checked: no exception escapes, ONE log line (one recorded reason), the feature reports itself off and the
 * rest keeps working. Where the init needs a real device (render-scale world images, the velocity pipeline) the
 * fallback logic runs for real and the call-site order is checked in the source (seam before any frame state changes,
 * catch around it). Also the startup self-check line. {@code ./gradlew firstLaunchTest}; last line
 * {@code # firstlaunch PASS n/n}.
 */
public final class FirstLaunchSafetyTest {
    static int pass, total;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        else System.out.println("FAIL " + what);
    }

    static boolean noThrow(Runnable r) {
        try {
            r.run();
            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- api 4: lazy device, forced create failure
        FeatureFailures.resetForTest("api4");
        GpuLifecycle.resetForTest();
        GpuLifecycle.install(2, 800, 600);
        check("api 4 after install: nothing created, capability still offered (lazy)", GpuCore.orNull() == null
                && !GpuLifecycle.installed() && GpuLifecycle.available() && GpuLifecycle.state().equals("ready(lazy)"));
        String msg = "";
        try {
            IrohBuffers.create("vista", "b", 64, IrohBuffers.STORAGE);
        } catch (UnsupportedOperationException e) {
            msg = e.getMessage();
        }
        check("api 4 forced create failure: the mod's call is refused with the reason (" + msg + ")", msg.contains("device creation failed")
                && msg.contains(FeatureFailures.PROPERTY));
        check("api 4 failure: one recorded reason, capabilities withdrawn, state failed", FeatureFailures.reason("api4") != null
                && !GpuLifecycle.available() && GpuLifecycle.state().equals("failed") && GpuCore.orNull() == null);
        boolean again = false;
        try {
            IrohBuffers.create("vista", "b", 64, IrohBuffers.STORAGE);
        } catch (UnsupportedOperationException e) {
            again = true;
        }
        check("api 4 failure: later calls refused at once (no retry storm)", again && FeatureFailures.snapshot().size() == 1);
        check("api 4 per-frame hooks are no-ops without a device", noThrow(() -> {
            GpuLifecycle.frameStart(0);
            GpuLifecycle.resize(640, 480);
            GpuLifecycle.worldPhase(true, 320, 240);
            GpuLifecycle.worldPhase(false, 0, 0);
            GpuLifecycle.worldUnloaded();
            GpuLifecycle.shutdown();
        }));
        GpuLifecycle.resetForTest();
        GpuCore.setUnavailableReason("no device yet, or capability off");

        // ---- FSR: forced context create failure -> bilinear, one line
        FeatureFailures.resetForTest("fsr");
        int[] logs = {0};
        FsrSession session = new FsrSession(new FsrSession.Backend() {
            @Override public FsrUpscaler create(int f, int s, int rw, int rh, int dw, int dh) {
                FeatureFailures.inject("fsr");
                throw new AssertionError("not reached");
            }
            @Override public boolean allow(long bytes) { return true; }
            @Override public void account(long bytes) {}
            @Override public void destroyLater(Runnable r) { r.run(); }
            @Override public void log(String m) { logs[0]++; }
        });
        FsrUpscaler up1 = session.ensure(0, 0, 1280, 720, 2560, 1440);
        FsrUpscaler up2 = session.ensure(0, 0, 1280, 720, 2560, 1440);
        check("FSR forced create failure: null (caller dispatches bilinear), failed with reason, ONE log line, no retry until a mode/size change ("
                + logs[0] + " lines)", up1 == null && up2 == null && session.failed() && session.failure().contains("bilinear")
                && logs[0] == 1 && session.creates() == 0);

        // ---- render scale / upscale: the fallback (mode off for the session) and the call-site order
        FeatureFailures.resetForTest("renderScale");
        UpscaleSwitches.registerAll();
        DebugSwitches.set(UpscaleSwitches.MODE, "quality");
        check("mode switch flips the volatile per-frame guard on", UpscaleSwitches.modeSet);
        try {
            FeatureFailures.inject("renderScale");
            check("renderScale seam throws when listed", false);
        } catch (IllegalStateException e) {
            TemporalRuntime.offForSession("renderScale", e);
        }
        check("render-scale failure: mode off, guard off (window size), one reason", !UpscaleSwitches.modeSet
                && DebugSwitches.getOr(UpscaleSwitches.MODE, "?").equals("off") && FeatureFailures.reason("renderScale") != null);
        String dmp = Files.readString(Path.of("src/main/java/net/vulkanmod/vulkan/pass/DefaultMainPass.java"));
        String beginWorld = HotPathAudit.bodies(dmp, "beginWorld").get(0);
        int seam = beginWorld.indexOf("FeatureFailures.inject(\"renderScale\")"), alloc = beginWorld.indexOf("ensureWorldImages"),
                endPass = beginWorld.indexOf("endRenderPass"), activate = beginWorld.indexOf("activateWorld");
        check("beginWorld allocates (and the seam fires) BEFORE ending the bound pass: a failure leaves the frame untouched",
                seam >= 0 && seam < alloc && alloc < endPass && endPass < activate);
        String endWorld = HotPathAudit.bodies(dmp, "endWorld").get(0);
        check("endWorld restores the display set in finally and turns upscaling off on an exception",
                endWorld.contains("catch (RuntimeException e)") && endWorld.contains("worldEndFailed(e)")
                        && endWorld.indexOf("finally") < endWorld.indexOf("activateDisplay", endWorld.indexOf("finally")));
        String tr = Files.readString(Path.of("src/main/java/dev/elemore/iroh/temporal/TemporalRuntime.java"));
        String levelHead = HotPathAudit.bodies(tr, "onRenderLevelHead").get(0);
        check("onRenderLevelHead catches a beginWorld failure and switches render scale off",
                levelHead.contains("catch (RuntimeException e)") && levelHead.contains("offForSession(\"renderScale\", e)"));
        String worldEnd = HotPathAudit.bodies(tr, "recordWorldEnd").get(0);
        int vSeam = worldEnd.indexOf("FeatureFailures.inject(\"velocity\")"), vNew = worldEnd.indexOf("new VelocityPass"),
                vCatch = worldEnd.indexOf("FeatureFailures.record(\"velocity\"");
        check("velocity pipeline create: seam, create and catch in order; a failure leaves bilinear for the session",
                vSeam >= 0 && vSeam < vNew && vNew < vCatch && worldEnd.contains("velocityFailed = true")
                        && worldEnd.contains("velocityPass != null && DeviceCaps.velocityCapable()"));

        // ---- pack path: forced enable failure
        FeatureFailures.resetForTest("pack");
        String[] r = new String[1];
        check("pack forced enable failure: no exception escapes", noThrow(() -> r[0] = PackRuntime.setEnabled(true)));
        check("pack failure: path off, raster terrain kept, one reason (" + r[0] + ")", r[0] != null && r[0].startsWith("error:")
                && !PackRuntime.active() && !net.vulkanmod.render.chunk.WorldRenderer.packTerrainActive && FeatureFailures.reason("pack") != null);

        // ---- device features: forced failure at device creation
        FeatureFailures.resetForTest("deviceCaps");
        check("deviceCaps forced failure: applySafely never throws", noThrow(() -> DeviceCaps.applySafely(null, null, null, null, null, null, null)));
        check("deviceCaps failure: upscale features reported off (velocity/FSR unavailable), one reason", !DeviceCaps.requested
                && !DeviceCaps.velocityCapable() && !DeviceCaps.fsrCapable() && FeatureFailures.reason("deviceCaps") != null);

        // ---- startup self-check
        FeatureFailures.resetForTest("startupReport");
        String broken = StartupReport.line();
        check("self-check forced failure: still ONE line, says unavailable (" + broken + ")", broken.startsWith("Iroh self-check: unavailable")
                && !broken.contains("\n"));
        FeatureFailures.resetForTest("");
        Map<String, String> sw = new TreeMap<>(Map.of("iroh.upscale.mode", "off", "iroh.tasks.budget", "off"));
        Map<String, String> def = new TreeMap<>(Map.of("iroh.upscale.mode", "off", "iroh.tasks.budget", "off"));
        String inert = StartupReport.build(sw, def, Map.of(), "drawIndirectCount=true", "api 4.0 api4=ready(lazy)",
                Map.of("fsr", "jar", "nrd", "missing (not in this jar)"), Map.of());
        check("self-check line, defaults: inert=yes, one line, every section (" + inert + ")", inert.startsWith("Iroh self-check: inert=yes")
                && inert.contains("switches{iroh.tasks.budget=off, iroh.upscale.mode=off}") && inert.contains("device{drawIndirectCount=true}")
                && inert.contains("api4=ready(lazy)") && inert.contains("natives{fsr=jar, nrd=missing (not in this jar)}") && !inert.contains("\n"));
        sw.put("iroh.upscale.mode", "quality");
        String notInert = StartupReport.build(sw, def, Map.of("iroh.slowFrames", "20"), "x", "api", Map.of(), Map.of("pack", "boom"));
        check("self-check line names what is not inert (" + notInert + ")", notInert.contains("inert=NO") && notInert.contains("iroh.upscale.mode")
                && notInert.contains("iroh.slowFrames") && notInert.contains("failures [pack]"));
        String real = StartupReport.line();
        System.out.println("  offline self-check line: " + real);
        check("real self-check offline: one line, never throws, natives located without loading", real.startsWith("Iroh self-check:")
                && !real.contains("\n") && real.contains("natives{") && !dev.elemore.iroh.upscale.fsr.NativeLoader.locate().startsWith("loaded"));

        System.out.println("# firstlaunch " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
