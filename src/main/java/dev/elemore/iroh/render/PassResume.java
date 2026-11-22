package dev.elemore.iroh.render;

/**
 * Order of an out-of-pass recording inside the main pass (VulkanImage.endPassRunResume), free of Vulkan so it is
 * tested offline (G1 fix, 2026-11-22). Whatever the recording did, it may have bound its own pipeline, descriptor
 * sets, vertex/index buffers and push constants, which leaves Iroh's bound-state cache wrong and invalidates every
 * dynamic state its pipelines declare (depth bias, line width) that the foreign pipeline did not. So after the resume
 * the bound-pipeline cache is dropped (the next draw rebinds; descriptor sets, vertex and index buffers and push
 * constants are bound per draw in Iroh) and every dynamic state is set again: viewport, scissor, depth bias (zero
 * when polygon offset is off), line width. This runs even when no pass was bound.
 */
public final class PassResume {

    public interface Hooks {
        /** true when a render pass is recording */
        boolean passBound();

        void endPass();

        void resumePass();

        /** forget the bound pipeline handle (and anything else cached about bound state) */
        void invalidateBoundState();

        /** viewport, scissor, depth bias, line width */
        void reapplyDynamicState();
    }

    private PassResume() {}

    public static void run(Hooks h, Runnable record) {
        boolean bound = h.passBound();
        if (bound)
            h.endPass();
        try {
            record.run();
        } finally {
            if (bound)
                h.resumePass();
            h.invalidateBoundState();
            h.reapplyDynamicState();
        }
    }
}
