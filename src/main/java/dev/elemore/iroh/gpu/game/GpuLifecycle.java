package dev.elemore.iroh.gpu.game;

import dev.elemore.iroh.diag.FeatureFailures;
import dev.elemore.iroh.gpu.GpuCore;

/**
 * Wires api 4 into Iroh's renderer: per-frame descriptor-pool reset, window resizes, world unload (every owner's objects
 * freed), a failing hook's owner freed, shutdown before the device goes.
 * INT-2 (RQ-INT1-a): the GPU device is created LAZILY, on a mod's first api-4 call ({@link GpuCore#get}), not at renderer
 * init: with no api-4 user there are no descriptor pools, no per-frame vkResetDescriptorPool and no per-frame work beyond
 * one static null test. {@link #install} only records the frame count and window size. The capabilities stay advertised
 * from renderer init on (the device can be created on demand); a failed lazy create logs ONE line, withdraws them and
 * makes every later api-4 call throw UnsupportedOperationException with the reason.
 */
public final class GpuLifecycle {
    private static volatile IrohGpuDevice device;
    private static volatile boolean ready;
    private static volatile String failure;
    private static int frames, width = 1, height = 1;
    private static boolean worldPhase;
    private static int worldW = 1, worldH = 1;

    private GpuLifecycle() {}

    /** Renderer init: nothing is created here (see the class comment). */
    public static void install(int frames, int width, int height) {
        GpuLifecycle.frames = frames;
        GpuLifecycle.width = width;
        GpuLifecycle.height = height;
        failure = null;
        ready = true;
        GpuCore.setLazyInstaller(GpuLifecycle::createNow);
    }

    /** the lazy create (GpuCore.get, render thread, once) */
    static void createNow() {
        try {
            FeatureFailures.inject("api4");
            IrohGpuDevice d = new IrohGpuDevice(frames, net.vulkanmod.vulkan.device.DeviceManager.drawIndirectCountEnabled);
            try {
                GpuCore c = GpuCore.install(d);
                c.resize(width, height);
                c.setWorldPhase(worldPhase, worldW, worldH);
            } catch (Throwable t) {
                GpuCore.uninstall();
                d.destroy();
                throw t;
            }
            device = d;
            net.vulkanmod.Initializer.LOGGER.info("Iroh api 4: GPU buffers / compute / targets created on first use (drawIndirectCount {})",
                    d.supportsDrawIndirectCount() ? "yes" : "no: zero-tail fallback");
        } catch (Throwable t) {
            device = null;
            failure = FeatureFailures.record("api4", t, "api 4 withdrawn: mods' GPU-api calls are refused, their own fallbacks apply");
            GpuCore.setUnavailableReason("device creation failed: " + failure);
        }
    }

    /** api 4 can be used: the device exists, or can still be created on first use */
    public static boolean available() {
        return device != null || (ready && failure == null);
    }

    /** the device objects exist (a mod has used api 4) */
    public static boolean installed() {
        return device != null;
    }

    /** one word for the startup self-check: off | ready(lazy) | on | failed */
    public static String state() {
        if (device != null) return "on";
        if (failure != null) return "failed";
        return ready ? "ready(lazy)" : "off";
    }

    public static void frameStart(int slot) {
        IrohGpuDevice d = device;
        if (d != null) {
            d.frameStart(slot);
            GpuCore c = GpuCore.orNull(); // api 4.1: per-owner timestamps read back without waiting
            if (c != null) c.frameStart();
        }
    }

    public static void resize(int w, int h) {
        width = w;
        height = h;
        GpuCore c = GpuCore.orNull();
        if (c != null) c.resize(w, h);
    }

    /**
     * INT-2: render scale's world phase opened (render size) or closed (DefaultMainPass.beginWorld / endWorld): scaled
     * api-4 targets follow the phase they are drawn in (GpuCore phase-size rule). Render thread; a static store + a null
     * test when api 4 is unused.
     */
    public static void worldPhase(boolean active, int w, int h) {
        worldPhase = active;
        if (active) {
            worldW = w;
            worldH = h;
        }
        GpuCore c = GpuCore.orNull();
        if (c != null) c.setWorldPhase(active, w, h);
    }

    public static void worldUnloaded() {
        GpuCore c = GpuCore.orNull();
        if (c == null) return;
        int n = c.freeAll();
        if (n > 0) net.vulkanmod.Initializer.LOGGER.info("Iroh api 4: freed {} GPU objects mods still held at world unload", n);
    }

    /** A mod's frame hook was removed after repeated failures: its GPU objects go too ("owner dies = Iroh frees"). */
    public static void ownerFailed(Object hook) {
        GpuCore c = GpuCore.orNull();
        if (c == null || hook == null) return;
        String mod = dev.elemore.iroh.diag.ModAttribution.ofClass(hook.getClass().getName());
        int n = c.freeOwner(mod);
        if (n > 0) net.vulkanmod.Initializer.LOGGER.warn("Iroh api 4: freed {} GPU objects of {} (its frame hook failed)", n, mod);
    }

    public static void shutdown() {
        GpuCore.setLazyInstaller(null);
        ready = false;
        IrohGpuDevice d = device;
        if (d == null) return;
        GpuCore.uninstall();
        d.destroy();
        device = null;
    }

    /** tests only: back to "never installed" */
    public static void resetForTest() {
        GpuCore.setLazyInstaller(null);
        device = null;
        ready = false;
        failure = null;
    }
}
