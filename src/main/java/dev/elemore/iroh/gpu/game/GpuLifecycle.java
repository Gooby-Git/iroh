package dev.elemore.iroh.gpu.game;

import dev.elemore.iroh.gpu.GpuCore;

/**
 * Wires api 4 into Iroh's renderer: install after the renderer exists, per-frame descriptor-pool reset, window resizes,
 * world unload (every owner's objects freed), a failing hook's owner freed, shutdown before the device goes.
 */
public final class GpuLifecycle {
    private static IrohGpuDevice device;

    private GpuLifecycle() {}

    public static void install(int frames, int width, int height) {
        try {
            device = new IrohGpuDevice(frames, net.vulkanmod.vulkan.device.DeviceManager.drawIndirectCountEnabled);
            GpuCore.install(device).resize(width, height);
            net.vulkanmod.Initializer.LOGGER.info("Iroh api 4: GPU buffers / compute / targets on (drawIndirectCount {})",
                    device.supportsDrawIndirectCount() ? "yes" : "no: zero-tail fallback");
        } catch (Throwable t) {
            device = null;
            net.vulkanmod.Initializer.LOGGER.error("Iroh api 4: GPU api unavailable", t);
        }
    }

    public static boolean available() {
        return device != null;
    }

    public static void frameStart(int slot) {
        if (device != null) device.frameStart(slot);
    }

    public static void resize(int w, int h) {
        GpuCore c = GpuCore.orNull();
        if (c != null) c.resize(w, h);
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
        if (device == null) return;
        GpuCore.uninstall();
        device.destroy();
        device = null;
    }
}
