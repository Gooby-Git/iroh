package dev.elemore.iroh.apiimpl;

import dev.elemore.iroh.api.FrameStage;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.PipelineState;

/**
 * Iroh internal (P1-13a): the "leave the state as you found it" rule for frame hooks. Captured into static fields before
 * each hook (no allocation), compared after: bound framebuffer, blend enable, depth test / write / func, cull, colour mask.
 * Under -ea a difference is an AssertionError; otherwise it is logged once per hook class and the state restored.
 */
final class StateGuard {
    private static Object framebuffer;
    private static boolean blend, depthTest, depthMask, cull;
    private static int depthFun, colorMask;
    private static boolean warned;
    static boolean assertionsOn;

    static {
        boolean a = false;
        assert a = true;
        assertionsOn = a;
    }

    private StateGuard() {
    }

    static void capture() {
        Renderer r = Renderer.getInstance();
        framebuffer = r != null ? r.getBoundFramebuffer() : null;
        blend = PipelineState.blendInfo.enabled;
        depthTest = VRenderSystem.depthTest;
        depthMask = VRenderSystem.depthMask;
        depthFun = VRenderSystem.depthFun;
        cull = VRenderSystem.cull;
        colorMask = VRenderSystem.colorMask;
    }

    static void verifyAndRestore(Object hook, FrameStage stage) {
        Renderer r = Renderer.getInstance();
        Object fb = r != null ? r.getBoundFramebuffer() : null;
        boolean same = fb == framebuffer && blend == PipelineState.blendInfo.enabled && depthTest == VRenderSystem.depthTest
                && depthMask == VRenderSystem.depthMask && depthFun == VRenderSystem.depthFun && cull == VRenderSystem.cull
                && colorMask == VRenderSystem.colorMask;
        if (same) return;
        String what = "frame hook " + hook.getClass().getName() + " at " + stage + " changed the render state and did not restore it";
        if (!warned) {
            warned = true;
            net.vulkanmod.Initializer.LOGGER.error("Iroh: {} (restored; logged once)", what);
        }
        PipelineState.blendInfo.enabled = blend;
        VRenderSystem.depthTest = depthTest;
        VRenderSystem.depthMask = depthMask;
        VRenderSystem.depthFun = depthFun;
        VRenderSystem.cull = cull;
        VRenderSystem.colorMask = colorMask;
        if (assertionsOn) throw new AssertionError("Iroh: " + what);
    }
}
