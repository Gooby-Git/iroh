package dev.elemore.iroh.render;

/**
 * INT-3 interaction matrix of everything that ends, resumes or redirects Iroh's main pass, free of Vulkan so it is
 * tested offline ({@code ./gradlew interactionTest}). Four actors:
 * <ul>
 *   <li><b>R</b> render scale's world phase (DefaultMainPass.beginWorld / endWorld): the main target IS the render-size
 *       world set while it is open.</li>
 *   <li><b>W</b> the shader pack's world pass (P3-11, behind {@code iroh.pack.world.split}): its own attachments
 *       (pack gbuffers) are open between a substituted bind and the next foreign recording; Iroh's pass is ended
 *       meanwhile.</li>
 *   <li><b>J</b> an api 4.1 mod joining the main pass ({@code beginRendering(mainColor(), mainDepth())}).</li>
 *   <li><b>O</b> api 4.1 {@code runOutsideMainPass(work)} and Iroh's own <b>P</b> = VulkanImage.endPassRunResume /
 *       {@link PassResume}: end the bound pass, record, resume on the ACTIVE set, drop Iroh's bound-state cache and
 *       re-apply dynamic state. O is implemented AS P (one mechanism).</li>
 * </ul>
 * Rules (who may be active together, who wins):
 * <ol>
 *   <li>R + W: FORBIDDEN. The pack wins over render scale (TemporalRuntime forces upscaling off while the pack path
 *       runs); should a world phase be open anyway, the world pass refuses the frame and turns itself off for the
 *       session with one log line ({@link #worldPassRefusal}).</li>
 *   <li>W yields to everything: any J, O, P, render pass begin or end closes W's attachments first and resumes Iroh's
 *       pass; the next substituted bind re-opens them. W never records inside another recording.</li>
 *   <li>R + J, R + O, R + P: allowed; J joins the render-size world set (tokens report its size), O / P resume on it.</li>
 *   <li>J + O: FORBIDDEN (O inside an open join): refused with IllegalStateException, the join stays valid.</li>
 *   <li>J while a foreign (mod off-screen) pass is bound: FORBIDDEN, refused; J needs Iroh's main target or no pass.</li>
 *   <li>O inside O, P inside O: allowed; the inner one finds no pass bound and just records.</li>
 *   <li>DefaultMainPass.end closes whatever is open (R, then W through endRenderPass) before the swapchain copy; a
 *       frame that ends with W still live (WorldRenderEvents.END skipped) turns W off with one line.</li>
 * </ol>
 */
public final class MainPassArbiter {
    private MainPassArbiter() {}

    /** what the arbiter needs from the renderer (game: Renderer / DefaultMainPass / SubstitutionHook; tests: fakes) */
    public interface Hooks extends PassResume.Hooks {
        /** W: close the pack world pass's attachments if open (resumes Iroh's pass). One volatile read when off. */
        void closePackWorldPass();

        /** the bound pass is Iroh's main target (any phase) */
        boolean mainTargetBound();

        /** begin Iroh's main pass (LOAD) on the active set */
        void resumeMain();

        /** int-2b: the frame command buffer's bound pipeline + dynamic state (PassResume binds Iroh's pipeline again) */
        CmdState cmdState();
    }

    /** O / P: end the bound pass (W first), record, resume, invalidate, re-apply */
    public static void runOutside(Hooks h, Runnable work) {
        h.closePackWorldPass();
        PassResume.run(h, h.cmdState(), work);
    }

    /** J: make Iroh's main target the bound pass, or refuse. W yields first. */
    public static void prepareJoin(Hooks h) {
        h.closePackWorldPass();
        if (!h.passBound())
            h.resumeMain();
        else if (!h.mainTargetBound())
            throw new IllegalStateException("api 4.1 main target: another render pass is bound (a mod framebuffer): join from a world stage");
    }

    /** rule 1: null = the world pass may run this frame, else the one-line reason it turns off */
    public static String worldPassRefusal(boolean renderScaleWorldPhase) {
        return renderScaleWorldPhase
                ? "render scale world phase is open: the pack world pass needs the display set (pack wins over render scale)"
                : null;
    }
}
