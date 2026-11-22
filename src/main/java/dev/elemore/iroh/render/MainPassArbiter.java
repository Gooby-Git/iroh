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
 * INT-4: the LOOK is a third axis. {@link Look}: RASTER (Iroh's own picture), PACK (Navidson through the loader: its
 * post chain and, behind its switches, W), RT (Iroh's ray tracing; actor <b>T</b> = the RT recording at WORLD_START).
 * <ol start="8">
 *   <li>PACK + RT: FORBIDDEN, the Look switch decides ({@link #resolveLook}). The configured Look (launch property
 *       {@code iroh.rt.look}, else iroh.json {@code look}) wins when both are asked for: Look RAY_TRACED + {@code
 *       iroh.pack.enabled} = the pack path stays off with ONE line ({@link #packRefusal}); Look NAVIDSON = RT is not
 *       requested at all (no device features, nothing registered). RT selected but not running (no hardware, dev gate
 *       off, failure) = RASTER, never the pack silently. Hence T + W never meet; the world pass also refuses while RT
 *       runs ({@link #worldPassRefusal(boolean, boolean)}) in case a runtime switch tries.</li>
 *   <li>RT + R (render scale, FSR): ALLOWED. RT targets are render-size (the R world set's size), T records through P
 *       (VulkanImage.endPassRunResume) at WORLD_START and resumes on the ACTIVE set; RT's output (when its passes are
 *       wired) is composed INSIDE the world phase, before endWorld, i.e. before the upscaler cut, exactly where the
 *       raster world picture ends today.</li>
 *   <li>RT + J / O (api-4 mods, Vista): ALLOWED, unchanged. T is a P like any other and cannot be inside J (it runs at
 *       WORLD_START, before any mod joins). Vista's LODs stay RASTER in v1: Vista draws them UNDER the near terrain in the
 *       world phase (BLEND_UNDER, depth-tested), after T; the RT compose stage (NOT wired in int-4: this build builds
 *       acceleration structures only) will sit AFTER Vista's composite and before the upscaler cut, replacing lighting
 *       only where the RT G-buffer has residency geometry and passing raster/LOD pixels through elsewhere. So far LODs are
 *       a raster backdrop behind the RT image.</li>
 *   <li>RT off: none of the above runs; T is not even registered (the frame hook exists only on a device created
 *       with the RT features).</li>
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

    /** rules 1 + 8: as above, and never while ray tracing runs (the Look switch keeps them apart) */
    public static String worldPassRefusal(boolean renderScaleWorldPhase, boolean rtRunning) {
        if (rtRunning) return "ray tracing is the Look this session: the pack world pass stays off (the Look switch picks one)";
        return worldPassRefusal(renderScaleWorldPhase);
    }

    /** INT-4: the three looks */
    public enum Look { RASTER, PACK, RT }

    /**
     * rule 8: what actually renders.
     * @param lookRt     the configured Look is RAY_TRACED (else NAVIDSON)
     * @param rtRunning  RT runs (device features present, dev gate on, no failure)
     * @param packLoaded the pack path is active
     */
    public static Look resolveLook(boolean lookRt, boolean rtRunning, boolean packLoaded) {
        if (lookRt) return rtRunning ? Look.RT : Look.RASTER;
        return packLoaded ? Look.PACK : Look.RASTER;
    }

    /** rule 8: null = the pack path may turn on, else the ONE line saying why it stays off */
    public static String packRefusal(boolean lookRt) {
        return lookRt ? "the Look is ray tracing (iroh.rt.look / iroh.json look = RAY_TRACED): the shader pack path stays off; "
                + "the Look switch picks one (set Look to Navidson for the pack)" : null;
    }
}
