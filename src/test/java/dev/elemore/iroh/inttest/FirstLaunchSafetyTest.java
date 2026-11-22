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

        // ---- INT-3: pack world pass (P3-11): forced create failure -> terrain-only full mode, ONE feature-failure line
        String pri = Files.readString(Path.of("src/main/java/dev/elemore/iroh/shaderpack/runtime/PackRuntimeImpl.java"));
        String wb = HotPathAudit.bodies(pri, "worldBegin").get(0), fw = HotPathAudit.bodies(pri, "failWorld").get(0);
        int wSeam = wb.indexOf("FeatureFailures.inject(\"worldPass\")"), wNew = wb.indexOf("new WorldPass("), wCatch = wb.indexOf("failWorld(e.getClass()");
        check("worldPass: seam, create and catch in order; a failure returns raster for the frame",
                wSeam >= 0 && wSeam < wNew && wNew < wCatch && wb.indexOf("return false", wCatch) > wCatch);
        check("worldPass: every world-pass failure is ONE FeatureFailures line, once per session (worldFailed guard)",
                fw.contains("FeatureFailures.record(\"worldPass\"") && fw.contains("worldFailed != null") && !fw.contains("log("));
        FeatureFailures.resetForTest("worldPass");
        boolean wThrows = false;
        try {
            FeatureFailures.inject("worldPass");
        } catch (IllegalStateException e) {
            wThrows = true;
        }
        check("worldPass seam fires when listed", wThrows);

        // ---- INT-3: api 4.1 withdrawn on its own (seam api41): apiMinor 0, 4.1 calls refused with the reason, 4.0 stays
        FeatureFailures.resetForTest("api41");
        GpuLifecycle.resetForTest();
        GpuLifecycle.install(2, 800, 600);
        check("api41 forced failure: apiMinor 0, one reason, api 4.0 still offered (lazy)", dev.elemore.iroh.api.IrohApi.apiMinor() == 0
                && FeatureFailures.reason("api41") != null && FeatureFailures.snapshot().size() == 1 && GpuLifecycle.available());
        String m41 = "";
        try {
            dev.elemore.iroh.gpu.GpuCore.require41("mainColor"); // the first statement of every 4.1 entry in GpuCore
        } catch (UnsupportedOperationException e) {
            m41 = e.getMessage();
        }
        check("api41 withdrawn: a 4.1 call says why (" + m41 + ")", m41.contains("api 4.1 is withdrawn"));
        check("api41 withdrawn: the self-check line says so", StartupReport.line().contains("api41=withdrawn"));
        FeatureFailures.resetForTest("");
        GpuLifecycle.resetForTest();
        GpuLifecycle.install(2, 800, 600);
        check("api41 default: apiMinor 1, self-check api41=on", dev.elemore.iroh.api.IrohApi.apiMinor() == 1 && StartupReport.line().contains("api41=on"));
        GpuLifecycle.resetForTest();

        // ---- device features: forced failure at device creation
        FeatureFailures.resetForTest("deviceCaps");
        check("deviceCaps forced failure: applySafely never throws", noThrow(() -> DeviceCaps.applySafely(null, null, null, null, null, null, null)));
        check("deviceCaps failure: upscale features reported off (velocity/FSR unavailable), one reason", !DeviceCaps.requested
                && !DeviceCaps.velocityCapable() && !DeviceCaps.fsrCapable() && FeatureFailures.reason("deviceCaps") != null);

        // ---- INT-4: ray tracing (card/P5-3 adapter). Requested at launch, seam rt -> one line, RT off, Look -> raster
        rtCases();

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

    /** INT-4: RT requested at launch: forced failure, a device without RT, the Look rule against the pack. */
    static void rtCases() throws Exception {
        String[] keys = {"iroh.rt", "iroh.rt.look"};
        System.setProperty("iroh.rt", "true");
        System.setProperty("iroh.rt.look", "RAY_TRACED");
        dev.elemore.iroh.rt.game.RtFailures.resetForTest();
        FeatureFailures.resetForTest("rt");
        dev.elemore.iroh.rt.game.RtSwitch.resetForTest();
        dev.elemore.iroh.rt.game.RtDeviceFeatures.resetForTest();
        long[] r = new long[1];
        check("rt forced failure at device creation: chain never throws", noThrow(() -> r[0] = dev.elemore.iroh.rt.game.RtDeviceFeatures.chain(null, null, null, 42L)));
        check("rt failure: chain unchanged, no extensions added, RT off, ONE recorded failure (" + FeatureFailures.snapshot() + ")",
                r[0] == 42L && !dev.elemore.iroh.rt.game.RtDeviceFeatures.enabled && !dev.elemore.iroh.rt.game.RtSwitch.ON
                        && dev.elemore.iroh.rt.game.RtDeviceFeatures.extensions(java.util.Set.of("a")).equals(java.util.Set.of("a"))
                        && FeatureFailures.snapshot().keySet().equals(java.util.Set.of("rt")));
        dev.elemore.iroh.rt.game.RtDeviceFeatures.deviceCreated(); // no-op: nothing was chained
        String line = StartupReport.line();
        check("rt failure: self-check says rt=failed and inert=NO through failures{rt} (" + line + ")",
                line.contains(" rt=failed") && line.contains("inert=NO") && line.contains("rt") && !dev.elemore.iroh.rt.game.RtSwitch.ON);
        check("rt failure: the Look falls back to RASTER (never the pack)", dev.elemore.iroh.render.MainPassArbiter.resolveLook(
                dev.elemore.iroh.rt.game.RtSwitch.lookRayTraced(), dev.elemore.iroh.rt.game.RtSwitch.ON, false) == dev.elemore.iroh.render.MainPassArbiter.Look.RASTER);
        // the configured Look wins over a pack request: refused with one line, not recorded as a failure
        String[] p = new String[1];
        check("Look RAY_TRACED + pack requested: pack setEnabled never throws", noThrow(() -> p[0] = PackRuntime.setEnabled(true)));
        check("Look RAY_TRACED wins: pack path stays off, refusal names the Look, not a failure (" + p[0] + ")",
                p[0] != null && p[0].startsWith("refused: the Look is ray tracing") && !PackRuntime.active() && FeatureFailures.reason("pack") == null);
        // a device WITHOUT RT: features dropped, one line, Look falls back (pure decision; chain() logs it and returns tail)
        dev.elemore.iroh.rt.RtCapabilities none = dev.elemore.iroh.rt.RtCapabilities.NONE,
                full = new dev.elemore.iroh.rt.RtCapabilities(true, true, true, false, false, 16L << 30),
                noBda = new dev.elemore.iroh.rt.RtCapabilities(true, true, false, false, false, 16L << 30);
        String a = dev.elemore.iroh.rt.game.RtDeviceFeatures.unavailableReason(false, none, false),
                b = dev.elemore.iroh.rt.game.RtDeviceFeatures.unavailableReason(true, noBda, true),
                c = dev.elemore.iroh.rt.game.RtDeviceFeatures.unavailableReason(true, full, false),
                d = dev.elemore.iroh.rt.game.RtDeviceFeatures.unavailableReason(true, full, true);
        check("device without RT: every missing piece named (" + a + " | " + b + " | " + c + "), full device -> available",
                a != null && a.contains("VK_KHR_ray_query") && b != null && b.equals("bufferDeviceAddress") && c != null && c.contains("SNORM") && d == null);
        String src = Files.readString(Path.of("src/main/java/dev/elemore/iroh/rt/game/RtDeviceFeatures.java"));
        String ch = HotPathAudit.bodies(src, "chain").get(0);
        int iWhy = ch.indexOf("unavailableReason("), iRet = ch.indexOf("return tail", iWhy), iBda = ch.indexOf("enable12.bufferDeviceAddress(true)"),
                iOn = ch.indexOf("enabled = true"), iLog = ch.indexOf("falls back to raster");
        check("device without RT: chain decides BEFORE touching the 1.2 struct or the flag, logs ONE line and returns the chain unchanged",
                iWhy > 0 && iLog > iWhy && iRet > iLog && iRet < iBda && iBda < iOn);
        check("device without RT: the Look falls back to RASTER", dev.elemore.iroh.render.MainPassArbiter.resolveLook(true, false, true)
                == dev.elemore.iroh.render.MainPassArbiter.Look.RASTER);
        for (String k : keys) System.clearProperty(k);
        dev.elemore.iroh.rt.game.RtSwitch.resetForTest();
        dev.elemore.iroh.rt.game.RtDeviceFeatures.resetForTest();
        dev.elemore.iroh.rt.game.RtFailures.resetForTest();
        FeatureFailures.resetForTest("");
        check("RT not requested: self-check carries no rt= at all", !StartupReport.line().contains(" rt="));
    }
}
