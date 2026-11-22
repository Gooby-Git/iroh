package dev.elemore.iroh.render;

/**
 * Order of an out-of-pass recording inside the main pass (VulkanImage.endPassRunResume), free of Vulkan so it is
 * tested offline (G1 fix, 2026-11-22; int-2b, td-0cc003). Whatever the recording did, it may have bound its own
 * pipeline, descriptor sets, vertex/index buffers and push constants, which leaves Iroh's bound-state cache wrong and
 * invalidates every dynamic state its pipelines declare (depth bias, line width) that the foreign pipeline did not.
 *
 * <p>The recording can sit BETWEEN an Iroh bind and its draw (VTextureSelector.bindShaderTextures flushes in-frame
 * uploads after Renderer.bindGraphicsPipeline: the title screen on int-2). So after the resume Iroh's pipeline that was
 * bound before is bound again and only the dynamic states IT declares are set ({@link CmdState#restore}); with none
 * bound, the next bind sets what it declares. int-2 set depth bias AND line width eagerly here: a GUI pipeline (line
 * width static) then drew with line width set since its bind = VUID-vkCmdDrawIndexed-None-08608.
 * Viewport and scissor (dynamic in every pipeline, reset by the pass begin) are set again when a pass is bound.
 * Descriptor sets, vertex and index buffers and push constants are bound per draw in Iroh, after the texture flush.
 * This runs even when no pass was bound and when the recording throws.
 */
public final class PassResume {

    public interface Hooks {
        /** true when a render pass is recording */
        boolean passBound();

        void endPass();

        void resumePass();

        /** viewport and scissor (after a pass begin reset them to full size); only called when a pass is bound */
        void reapplyViewportAndScissor();
    }

    private PassResume() {}

    public static void run(Hooks h, CmdState cmd, Runnable record) {
        long pipeline = cmd.boundHandle();
        int dynamic = cmd.boundDynamic();
        Object owner = cmd.boundOwner();
        boolean bound = h.passBound();
        if (bound)
            h.endPass();
        try {
            record.run();
        } finally {
            if (bound)
                h.resumePass();
            cmd.restore(pipeline, dynamic, owner);
            if (h.passBound())
                h.reapplyViewportAndScissor();
        }
    }
}
