package dev.elemore.iroh.rt.game;

import dev.elemore.iroh.api.FrameContext;
import dev.elemore.iroh.api.FrameStage;
import dev.elemore.iroh.api.IrohFrame;
import dev.elemore.iroh.api.RenderPath;
import dev.elemore.iroh.rt.RtMemoryModel;

import java.util.EnumSet;

/**
 * P5-2: the RT adapter's frame-stage hooks. Nothing here is on Iroh's hot paths when RT is off: the per-frame work is an
 * api frame hook registered only by {@link #install} (called from RtDeviceFeatures.deviceCreated, i.e. only when the
 * device was created WITH the RT features); the one game call site outside the api ({@link #worldUnloaded}, next to
 * MemoryGuard.worldUnloaded in WorldRenderer) starts with the {@link RtSwitch#ON} read (pinned).
 * <p>
 * Per frame at WORLD_START: lazy session start (seam {@code rt}), resize check against the viewport, then the section
 * BLAS builds within the per-frame budget and the TLAS, recorded OUTSIDE the main render pass
 * (VulkanImage.endPassRunResume, MainPassArbiter's P). Any throwable: {@link #fallBack} (one line, everything released,
 * raster continues, the mesher returns to shaded colours on the next rebuild).
 */
public final class RtFrameHooks {
    private RtFrameHooks() {}

    static RtSession session;
    static IrohFrame.Registration frameReg, sectionReg;
    static RtSession.FrameStats last;

    /** after vkCreateDevice with the RT features (not a hot path) */
    static void install() {
        if (!RtSwitch.ON || frameReg != null) return;
        frameReg = IrohFrame.addHook(FrameStage.WORLD_START, 0, EnumSet.allOf(RenderPath.class), RtFrameHooks::worldStart);
    }

    static void worldStart(FrameContext c) {
        if (!RtSwitch.ON) return;
        try {
            if (session == null) start(c);
            int w = c.viewportWidth(), h = c.viewportHeight();
            if (!session.resize(w, h)) throw new IllegalStateException("RT targets refused at " + w + "x" + h + " (memory owner iroh-rt)");
            int sx = (int) Math.floor(c.cameraPos().x()) >> 4, sy = (int) Math.floor(c.cameraPos().y()) >> 4, sz = (int) Math.floor(c.cameraPos().z()) >> 4;
            net.vulkanmod.vulkan.texture.VulkanImage.endPassRunResume(() -> {
                if (backend instanceof VkRtBackend vk) vk.origin(sx, sy, sz);
                last = session.frame(sx, sy, sz);
            });
        } catch (Throwable t) {
            fallBack("frame", t);
        }
    }

    static RtSession.Backend backend;

    static void start(FrameContext c) {
        RtFailures.inject("session");
        backend = new VkRtBackend((dev.elemore.iroh.gpu.VkGpuDevice) dev.elemore.iroh.gpu.GpuCore.get().device);
        RtSession s = new RtSession(RtSwitch.settings(), RtGuardMemory.forGame(), RtMemoryModel.radvAssumed(), new VulkanModSections(), backend);
        if (!s.enable(c.viewportWidth(), c.viewportHeight())) throw new IllegalStateException("RT targets refused (memory owner iroh-rt)");
        session = s;
        sectionReg = dev.elemore.iroh.apiimpl.Sections.add(new RtGameBridge(s.queue, new VulkanModSections()));
        if (RtMeshMode.RT_SHADES_PICTURE) { // INT-4: false in this build (acceleration structures only): keep shaded raster colour
            RtMeshMode.tintOnly = true;
            requestRebuild();
        }
    }

    /** WorldRenderer (setLevel / unload), next to MemoryGuard.worldUnloaded: drop every BLAS. Pinned first statement. */
    public static void worldUnloaded() {
        if (!RtSwitch.ON) return;
        try {
            if (session != null) session.worldUnloaded();
        } catch (Throwable t) {
            fallBack("worldUnload", t);
        }
    }

    /** Look switched away from RT at runtime, or shutdown: release everything (owner iroh-rt back to 0). */
    public static void stop() {
        RtSession s = session;
        session = null;
        if (sectionReg != null) { sectionReg.close(); sectionReg = null; }
        if (frameReg != null) { frameReg.close(); frameReg = null; }
        if (s != null) s.disable();
        backend = null;
        if (RtMeshMode.tintOnly) {
            RtMeshMode.tintOnly = false;
            requestRebuild();
        }
    }

    /**
     * The Look switch row (Iroh video-settings section) changed. Returns null when applied now, else the reason shown
     * (the RT device features are chosen at launch, like FSR's: without them the choice takes effect after a restart).
     */
    public static String applyLook(boolean rt) {
        RtSwitch.lookChanged(rt); // INT-4 rule 8 follows the row (the pack may turn on again after "Navidson")
        if (!rt) {
            RtSwitch.ON = false;
            stop();
            return null;
        }
        // INT-4 MainPassArbiter rule 8: the Look switch picks one: the pack path goes off now, even if RT itself needs a
        // restart (Look RT not running = raster, never the pack)
        if (dev.elemore.iroh.shaderpack.runtime.PackRuntime.active()) {
            dev.elemore.iroh.shaderpack.runtime.PackRuntime.setEnabled(false);
            RtDeviceFeatures.log("Iroh: Look set to ray tracing: the shader pack path is off");
        }
        if (!RtDeviceFeatures.enabled) return "restart";
        if (RtFailures.reason() != null) return "failed";
        RtSwitch.ON = true;
        install();
        return null;
    }

    static void fallBack(String step, Throwable t) {
        RtSwitch.fallBack(step, t);
        try {
            stop();
        } catch (Throwable ignored) {
        }
    }

    /** mesher colour mode changed: every section must be rebuilt (vanilla's allChanged path). */
    static void requestRebuild() {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.levelRenderer != null) mc.levelRenderer.allChanged();
        } catch (Throwable ignored) {
            // offline (no game): nothing to rebuild
        }
    }

    public static RtSession.FrameStats lastStats() {
        return last;
    }
}
