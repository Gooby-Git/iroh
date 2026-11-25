package dev.elemore.iroh.temporal;

import dev.elemore.iroh.diag.FeatureFailures;

import com.mojang.blaze3d.platform.Window;
import dev.elemore.iroh.debug.DebugSwitches;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import net.vulkanmod.vulkan.pass.MainPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Card P4-1a: the game-facing half of the temporal-upscaler inputs. Every entry point starts with the cheapest
 * possible "off" test and returns before touching any game or Vulkan class when {@code iroh.upscale.mode} is off.
 * Hooks (all render thread): {@code IrohRenderScaleM} (GameRenderer.render HEAD/TAIL, renderLevel HEAD, the
 * bindWrite before the GUI; post-chain sizes), WorldStages START (camera), DefaultMainPass (world end recording).
 *
 * <h2>What renders at which size (decisions)</h2>
 * RENDER size (world image set): everything from renderLevel HEAD up to the main-target bindWrite before the GUI:
 * sky, terrain, entities, block entities, particles, weather, clouds, the Fabulous transparency chain and the entity
 * outline chain (LevelRenderer's PostChains are resized to render size), the hand, GameRenderer.postEffect (spectator
 * / creeper vision: world-space), the world-icon screenshot (tryTakeScreenshotIfNeeded, in the world phase), Fabric
 * WorldRenderEvents hooks and Iroh frame hooks of the world stages, GL-shim draws issued in the world phase.
 * DISPLAY size: the upscaled picture, then the GUI/HUD, screens, the overlay, GameRenderer.blurEffect (menu blur
 * samples the display picture), F2 screenshots and Proctor's capture (Screenshot runs after GameRenderer.render),
 * the present blit (RQ-16 flip, unchanged: display colour -> swapchain).
 * Third-party RenderTargets / Satin effects keep DISPLAY-size targets (their resize comes from the window); when they
 * sample the main target in the world phase they read the render-size image by UV (correct, slightly wasteful) and a
 * depth copy across sizes goes through ImageUtil's NEAREST depth blit. Window.getWidth() stays the window size: mods
 * that set a display-size viewport in the world phase are mapped onto the render target ({@link TemporalFrame#fixRect},
 * switch iroh.upscale.viewportFix); sub-rects given in display pixels that fit inside the render target are NOT
 * recognisable and stay wrong (documented trap).
 *
 * <h2>P4-1b additions</h2>
 * FSR 3.1 through {@link FsrAdapter}; per-object motion (rendered entities that moved, {@link ObjectMotion}, gathered at
 * the world start); the hand's own reprojection (view bob, captured at renderHandsWithItems); opaque colour + depth copy
 * at WorldRenderEvents.AFTER_ENTITIES for the reactive / T&C masks; merged depth (hand included) for the upscaler.
 * All of it only when the velocity pass runs (a temporal upscaler or a debug view, and the device features).
 */
public final class TemporalRuntime {
    private TemporalRuntime() {}

    private static boolean inGameRender;
    private static boolean wasActive;
    private static UpscalerAdapter adapter;
    private static VelocityPass velocityPass;
    /** render-size inputs; all null while off */
    private static VulkanImage velocityImage, sceneDepth, mergedDepth, reactive, transparency, opaqueColour, opaqueDepth, phaseDepth, reactiveSdk;
    private static boolean haveSceneDepth, haveOpaque, havePhase;
    private static boolean warnedNoVelocity;
    private static long lastFrameNanos;
    private static float frameTimeMs = 16.7f, partialTick;
    private static Object lastLevel;
    private static int lastW, lastH;
    private static ScaleMode lastMode = ScaleMode.OFF;
    private static final double OBJECT_RANGE = 128.0;

    private static boolean warnedPack;
    private static boolean velocityFailed;
    private static final VulkanImage[] OUTS = new VulkanImage[4];
    private static final Matrix4f HAND_MV = new Matrix4f();

    /** GameRenderer.render HEAD */
    public static void onRenderHead() {
        if (!TemporalFrame.ACTIVE && !UpscaleSwitches.modeSet) // INT-2: one volatile read when off
            return;
        // integration/next: the Navidson pack path and render scale are not combined before card P4-8 (the pack would
        // run at render size on the world image set with no upscale cut point): the pack wins, upscaling stays off
        if (dev.elemore.iroh.shaderpack.runtime.PackRuntime.active()) {
            if (!warnedPack) {
                Initializer.LOGGER.warn("Iroh upscale: OFF because the Navidson shader pack path is on: the pack wins over render scale "
                        + "until the pack's own upscaler cut lands (card P4-8); the world renders at window size");
                warnedPack = true;
            }
            if (!TemporalFrame.ACTIVE)
                return;
            UpscaleSwitches.registerAll();
            DebugSwitches.set(UpscaleSwitches.MODE, "off");
        }
        UpscaleSwitches.registerAll();
        inGameRender = true;
        SwapChain sc = Renderer.getInstance().getSwapChain();
        ScaleMode m = UpscaleSwitches.mode();
        if (m != lastMode || sc.getWidth() != lastW || sc.getHeight() != lastH) {
            FsrAdapter.INSTANCE.retry(); // a mode or size change allows a new FSR attempt after a failure
            lastMode = m;
            lastW = sc.getWidth();
            lastH = sc.getHeight();
        }
        UpscalerAdapter a = UpscalerAdapter.select(DebugSwitches.getOr(UpscaleSwitches.UPSCALER, "auto"));
        if (adapter != a) {
            if (adapter != null)
                adapter.destroy(); // FSR: deferred frame op (FsrSession.closeLater)
            adapter = a;
            TemporalFrame.invalidateHistory();
        }
        boolean changed = TemporalFrame.beginFrame(sc.getWidth(), sc.getHeight(), a.temporal(), a.wantsVelocity());
        noteContext(); // H1C: F5, screens, dimension
        if (TemporalFrame.velocityOn && !DeviceCaps.velocityCapable()) {
            if (!warnedNoVelocity) {
                Initializer.LOGGER.warn("Iroh upscale: velocity pass / debug view unavailable ({}); launch with -Diroh.upscale.mode=<mode> so the device features are enabled",
                        DeviceCaps.report());
                warnedNoVelocity = true;
            }
            TemporalFrame.velocityOn = false;
            TemporalFrame.debugMode = 0;
        }
        long now = System.nanoTime();
        if (lastFrameNanos != 0)
            frameTimeMs = TemporalFrame.clampFrameTime((now - lastFrameNanos) / 1.0e6f); // H1C: raw wall clock after a hitch is not a frame time
        lastFrameNanos = now;

        if (changed) {
            Initializer.LOGGER.info("Iroh upscale: mode {} display {}x{} render {}x{} upscaler {} jitter {} (phases {}) mipBias {}",
                    TemporalFrame.mode.key, TemporalFrame.displayW, TemporalFrame.displayH, TemporalFrame.renderW, TemporalFrame.renderH,
                    a.name(), TemporalFrame.JITTER_ON, TemporalFrame.phaseCount, TemporalFrame.mipBias);
            resizeWorldChains();
        }
        if (!TemporalFrame.ACTIVE && wasActive)
            deactivate();
        wasActive = TemporalFrame.ACTIVE;
    }

    /** H1C (RQ-APP1-d): camera type / screen / dimension changes drop the upscaler history (pure rule in {@link TemporalFrame#noteContext}). */
    private static void noteContext() {
        if (!TemporalFrame.ACTIVE)
            return;
        try {
            Minecraft mc = Minecraft.getInstance();
            boolean screen = mc.screen != null && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen); // chat is a thin overlay, not a new picture
            TemporalFrame.noteContext(mc.options.getCameraType(), screen, mc.level != null ? mc.level.dimension() : null);
        } catch (RuntimeException e) {
            // never let a context probe break the frame
        }
    }

    /** GameRenderer.render TAIL */
    public static void onRenderTail() {
        if (!inGameRender)
            return;
        if (TemporalFrame.IN_WORLD)
            onBeforeGui();
        inGameRender = false;
    }

    /** GameRenderer.renderLevel HEAD, only inside GameRenderer.render (panorama / other renderLevel callers are left alone) */
    public static void onRenderLevelHead(float entityPartialTick) {
        if (!TemporalFrame.ACTIVE || !inGameRender || TemporalFrame.IN_WORLD)
            return;
        partialTick = entityPartialTick;
        haveSceneDepth = haveOpaque = havePhase = false;
        MainPass mp = Renderer.getInstance().getMainPass();
        try {
            if (mp instanceof DefaultMainPass dmp && dmp.beginWorld(TemporalFrame.renderW, TemporalFrame.renderH))
                TemporalFrame.IN_WORLD = true;
        } catch (RuntimeException e) {
            // INT-2: world images could not be made (beginWorld allocates before touching the frame): window size
            offForSession("renderScale", e);
        }
    }

    /** INT-2: a render-scale failure: ONE log line, the mode switch goes off, the world renders at window size. */
    public static void offForSession(String feature, Throwable t) {
        FeatureFailures.record(feature, t, "render scale and upscaling off for this session (the world renders at window size)");
        UpscaleSwitches.registerAll();
        DebugSwitches.set(UpscaleSwitches.MODE, "off");
    }

    /** DefaultMainPass.endWorld: recordWorldEnd threw; the display set is restored by the caller */
    public static void worldEndFailed(RuntimeException e) {
        offForSession("upscale", e);
    }

    /** the velocity pass will run this frame (it needs the device features) */
    private static boolean velocityFrame() {
        return TemporalFrame.IN_WORLD && TemporalFrame.velocityOn && DeviceCaps.velocityCapable();
    }

    /** WorldRenderEvents.AFTER_ENTITIES (via WorldStages): opaque colour + depth for the reactive / T&C masks */
    public static void onAfterEntities() {
        if (!velocityFrame() || !(Renderer.getInstance().getMainPass() instanceof DefaultMainPass dmp))
            return;
        int w = TemporalFrame.renderW, h = TemporalFrame.renderH;
        try {
            opaqueColour = ensure(opaqueColour, w, h, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "opaque colour");
            opaqueDepth = ensure(opaqueDepth, w, h, Vulkan.getDefaultDepthFormat(), VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "opaque depth");
        } catch (RuntimeException e) {
            offForSession("upscale", e); // INT-2: allocation before any pass change; this frame finishes without masks
            return;
        }
        haveOpaque = dmp.copyWorld(opaqueColour, opaqueDepth);
    }

    /**
     * WorldRenderEvents.AFTER_TRANSLUCENT (via WorldStages), Voxel-Aware reactive mode only (FSR-Q7): a depth copy after the
     * translucent terrain, tripwire and particles, before clouds and weather. Together with the opaque copy and the scene depth
     * it tells which draw phase wrote a pixel (VoxelMask). One extra pass split + a 4 B/px copy per frame in this mode.
     */
    public static void onAfterTranslucent() {
        if (!velocityFrame() || !haveOpaque || TemporalFrame.reactiveMode != dev.elemore.iroh.upscale.ReactiveMode.VOXEL
                || !(Renderer.getInstance().getMainPass() instanceof DefaultMainPass dmp))
            return;
        try {
            phaseDepth = ensure(phaseDepth, TemporalFrame.renderW, TemporalFrame.renderH, Vulkan.getDefaultDepthFormat(),
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "phase depth (after translucent)");
        } catch (RuntimeException e) {
            offForSession("upscale", e); // as onAfterEntities
            return;
        }
        havePhase = dmp.copyWorld(null, phaseDepth);
    }

    /** GameRenderer.renderLevel: the INVOKE of RenderSystem.clear right before renderItemInHand (depth-only clear) */
    public static void onBeforeHandClear() {
        if (!TemporalFrame.IN_WORLD)
            return;
        UpscalerAdapter a = adapter != null ? adapter : UpscalerAdapter.Bilinear.INSTANCE;
        if (!(TemporalFrame.velocityOn || a.temporal()) || !(Renderer.getInstance().getMainPass() instanceof DefaultMainPass dmp))
            return;
        try {
            sceneDepth = ensure(sceneDepth, TemporalFrame.renderW, TemporalFrame.renderH, Vulkan.getDefaultDepthFormat(),
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "scene depth before hand");
        } catch (RuntimeException e) {
            offForSession("upscale", e); // INT-2: as onAfterEntities
            return;
        }
        haveSceneDepth = dmp.copyWorld(null, sceneDepth);
    }

    /** ItemInHandRenderer.renderHandsWithItems in renderItemInHand: the hand's projection and bob model-view */
    public static void onHand(PoseStack poseStack) {
        if (!velocityFrame())
            return;
        Matrix4f mv = HAND_MV.set(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose()); // INT-2: no per-frame Matrix4f
        TemporalFrame.onHand(RenderSystem.getProjectionMatrix(), mv);
    }

    /** the main-target bindWrite(true) in GameRenderer.render, right before the GUI */
    public static void onBeforeGui() {
        if (!TemporalFrame.IN_WORLD)
            return;
        if (Renderer.getInstance().getMainPass() instanceof DefaultMainPass dmp)
            dmp.endWorld();
        TemporalFrame.IN_WORLD = false;
    }

    /** DefaultMainPass.endWorld, after the display images are the main target again */
    public static void worldClosed() {
        TemporalFrame.IN_WORLD = false;
    }

    /** Fabric WorldRenderEvents.START (via WorldStages): camera history + the objects that may move */
    public static void onWorldStart(double x, double y, double z, org.joml.Matrix4fc rotation, org.joml.Matrix4fc projection) {
        if (!TemporalFrame.IN_WORLD)
            return;
        Minecraft mc = Minecraft.getInstance();
        Object level = mc.level;
        if (level != lastLevel) {
            TemporalFrame.invalidateHistory();
            lastLevel = level;
        }
        TemporalFrame.onWorldCamera(x, y, z, rotation, projection);
        if (velocityFrame() && mc.level != null)
            gatherObjects(mc, x, y, z);
    }

    private static void gatherObjects(Minecraft mc, double cx, double cy, double cz) {
        Entity cam = mc.getCameraEntity();
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        double r2 = OBJECT_RANGE * OBJECT_RANGE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == cam && firstPerson)
                continue;
            Vec3 p = e.getPosition(partialTick);
            double dx = p.x - cx, dy = p.y - cy, dz = p.z - cz;
            if (dx * dx + dy * dy + dz * dz > r2)
                continue;
            double yaw = e instanceof LivingEntity le ? Mth.rotLerp(partialTick, le.yBodyRotO, le.yBodyRot) : e.getViewYRot(partialTick);
            AABB b = e.getBoundingBox().move(p.x - e.getX(), p.y - e.getY(), p.z - e.getZ());
            // living things: +0.6 on top so the name tag (drawn above the head, moves with it) takes the object's motion
            double top = e instanceof LivingEntity ? 0.6 : 0.0;
            TemporalFrame.objects.submit((long) e.getId() << 8, p.x, p.y, p.z, yaw,
                    new double[]{b.minX, b.minY, b.minZ}, new double[]{b.maxX, b.maxY + top, b.maxZ});
        }
    }

    /** LevelRenderer / GameRenderer.postEffect PostChain sizes: render size while active */
    public static int chainWidth(int w) {
        return TemporalFrame.ACTIVE ? TemporalFrame.renderW : w;
    }

    public static int chainHeight(int h) {
        return TemporalFrame.ACTIVE ? TemporalFrame.renderH : h;
    }

    private static void resizeWorldChains() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameRenderer == null)
            return;
        Window w = mc.getWindow();
        mc.gameRenderer.resize(w.getWidth(), w.getHeight());
    }

    private static VulkanImage ensure(VulkanImage img, int w, int h, int format, int usage, String what) {
        if (img != null && img.width == w && img.height == h)
            return img;
        if (img != null)
            img.free();
        return VulkanImage.builder(w, h)
                .setName("Iroh " + what + " (render scale)")
                .setFormat(format)
                .setUsage(usage)
                .setLinearFiltering(false)
                .setClamp(true)
                .createVulkanImage();
    }

    private static void deactivate() {
        if (Renderer.getInstance().getMainPass() instanceof DefaultMainPass dmp)
            dmp.releaseWorld();
        for (VulkanImage i : new VulkanImage[]{velocityImage, sceneDepth, mergedDepth, reactive, transparency, opaqueColour, opaqueDepth, phaseDepth, reactiveSdk})
            if (i != null)
                i.free();
        velocityImage = sceneDepth = mergedDepth = reactive = transparency = opaqueColour = opaqueDepth = phaseDepth = reactiveSdk = null;
        haveSceneDepth = haveOpaque = havePhase = false;
        if (adapter != null) {
            adapter.destroy();
            adapter = null;
        }
        // the compute pipelines are small (two pipelines + a pool + 7 KB per frame): kept until shutdown
        Initializer.LOGGER.info("Iroh upscale: off, world images and upscaler context released");
    }

    /**
     * Records the world-end work into the frame command buffer (no render pass open): velocity pass (motion, merged
     * depth, masks, debug view), then the upscale into the display colour.
     */
    public static void recordWorldEnd(VkCommandBuffer cmd, MemoryStack stack, VulkanImage worldColor, VulkanImage worldDepth, VulkanImage displayColor) {
        TemporalFrame.endWorld();
        UpscalerAdapter a = adapter != null ? adapter : UpscalerAdapter.Bilinear.INSTANCE;
        int rw = worldColor.width, rh = worldColor.height;
        boolean ran = false;
        if (TemporalFrame.velocityOn && DeviceCaps.velocityCapable() && (worldColor.usage & VK_IMAGE_USAGE_STORAGE_BIT) != 0) {
            int st = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
            velocityImage = ensure(velocityImage, rw, rh, VK_FORMAT_R16G16_SFLOAT, st, "velocity RG16F");
            mergedDepth = ensure(mergedDepth, rw, rh, VK_FORMAT_R32_SFLOAT, st, "merged depth R32F");
            reactive = ensure(reactive, rw, rh, VK_FORMAT_R8_UNORM, st, "reactive mask R8");
            transparency = ensure(transparency, rw, rh, VK_FORMAT_R8_UNORM, st, "transparency+composition mask R8");
            if (velocityPass == null && !velocityFailed) {
                try {
                    FeatureFailures.inject("velocity");
                    velocityPass = new VelocityPass(Vulkan.getVkDevice(), Renderer.getFramesNum());
                } catch (RuntimeException e) {
                    velocityFailed = true; // INT-2: no motion vectors for the session: temporal upscalers fall back to bilinear
                    FeatureFailures.record("velocity", e, "no motion vectors: the upscale stays bilinear for this session");
                }
            }
        }
        if (TemporalFrame.velocityOn && velocityPass != null && DeviceCaps.velocityCapable() && (worldColor.usage & VK_IMAGE_USAGE_STORAGE_BIT) != 0) {

            int cs = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            VulkanImage.transitionLayout(stack, cmd, worldDepth, worldDepth.getCurrentLayout(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, cs, VK_ACCESS_SHADER_READ_BIT);
            VulkanImage.transitionLayout(stack, cmd, worldColor, worldColor.getCurrentLayout(), VK_IMAGE_LAYOUT_GENERAL,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, cs, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            // FSR-Q6: the SDK's own reactive pass (colour is GENERAL now, the opaque copy SHADER_READ_ONLY); falls back to the own pass without a context
            boolean sdk = false;
            if (TemporalFrame.reactiveMode == dev.elemore.iroh.upscale.ReactiveMode.SDK && haveOpaque && a instanceof FsrAdapter fa) {
                try {
                    reactiveSdk = ensure(reactiveSdk, rw, rh, VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "FSR generated reactive mask R32F");
                    VulkanImage.transitionLayout(stack, cmd, reactiveSdk, reactiveSdk.getCurrentLayout(), VK_IMAGE_LAYOUT_GENERAL,
                            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, cs, VK_ACCESS_SHADER_WRITE_BIT);
                    sdk = fa.generateReactive(cmd, worldColor, opaqueColour, reactiveSdk, displayColor.width, displayColor.height, dev.elemore.iroh.upscale.fsr.ReactiveGen.DEFAULT);
                    VulkanImage.transitionLayout(stack, cmd, reactiveSdk, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            cs, VK_ACCESS_SHADER_WRITE_BIT, cs, VK_ACCESS_SHADER_READ_BIT);
                    // the SDK pass read colour in GENERAL: order it before the velocity pass's debug-view write to the same image
                    org.lwjgl.vulkan.VkMemoryBarrier.Buffer mb = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack).sType$Default()
                            .srcAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                    vkCmdPipelineBarrier(cmd, cs, cs, 0, mb, null, null);
                } catch (RuntimeException e) {
                    sdk = false;
                    FeatureFailures.record("reactiveSdk", e, "the SDK reactive mask failed: the own colour-difference mask is used");
                }
            }
            OUTS[0] = velocityImage; OUTS[1] = mergedDepth; OUTS[2] = reactive; OUTS[3] = transparency; // INT-2: no per-frame array
            for (VulkanImage out : OUTS)
                VulkanImage.transitionLayout(stack, cmd, out, out.getCurrentLayout(), VK_IMAGE_LAYOUT_GENERAL,
                        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, cs, VK_ACCESS_SHADER_WRITE_BIT);

            VelocityPass.Frame f = TemporalFrame.params;
            f.endDepthView = worldDepth.getImageView();
            f.sceneDepthView = haveSceneDepth ? sceneDepth.getImageView() : 0L;
            f.velocityView = velocityImage.getImageView();
            f.colourView = worldColor.getImageView();
            f.mergedDepthView = mergedDepth.getImageView();
            f.reactiveView = reactive.getImageView();
            f.tcView = transparency.getImageView();
            f.opaqueColourView = haveOpaque ? opaqueColour.getImageView() : 0L;
            f.opaqueDepthView = haveOpaque ? opaqueDepth.getImageView() : 0L;
            f.voxel = TemporalFrame.reactiveMode == dev.elemore.iroh.upscale.ReactiveMode.VOXEL;
            f.phaseDepthView = f.voxel && havePhase ? phaseDepth.getImageView() : 0L;
            f.sdkReactiveView = sdk ? reactiveSdk.getImageView() : 0L;
            f.width = rw;
            f.height = rh;
            f.mode = TemporalFrame.debugMode;
            f.reproject = TemporalFrame.reproject;
            f.invCurPR = TemporalFrame.invCurPR;
            f.handReproject = TemporalFrame.handValid ? TemporalFrame.handReproject : null;
            f.jitterNdcX = TemporalFrame.jitterNdcX;
            f.jitterNdcY = TemporalFrame.jitterNdcY;
            f.jitterPxX = TemporalFrame.jitterPxX;
            f.jitterPxY = TemporalFrame.jitterPxY;
            velocityPass.record(cmd, Renderer.getCurrentFrame(), f);

            int rd = cs | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            for (VulkanImage out : OUTS)
                VulkanImage.transitionLayout(stack, cmd, out, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        cs, VK_ACCESS_SHADER_WRITE_BIT, rd, VK_ACCESS_SHADER_READ_BIT);
            VulkanImage.transitionLayout(stack, cmd, worldColor, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    cs, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT | cs, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT);
            ran = true;
        }

        float sharp;
        try {
            String sv = DebugSwitches.getOr(UpscaleSwitches.SHARPNESS, "off");
            sharp = sv.equals("off") ? 0f : sv.equals("auto") ? dev.elemore.iroh.upscale.Rcas.forRatio((float) displayColor.width / rw) : Float.parseFloat(sv);
        } catch (NumberFormatException e) {
            sharp = 0f;
        }
        // a temporal upscaler without the velocity pass has no usable motion vectors: bilinear for this frame
        UpscalerAdapter use = a.temporal() && !ran ? UpscalerAdapter.Bilinear.INSTANCE : a;
        use.dispatch(cmd, stack, new UpscalerAdapter.Inputs(worldColor, ran ? mergedDepth : (haveSceneDepth ? sceneDepth : worldDepth),
                ran ? velocityImage : null, displayColor,
                rw, rh, displayColor.width, displayColor.height,
                TemporalFrame.jitterPxX, TemporalFrame.jitterPxY, rw, rh,
                frameTimeMs, TemporalFrame.cameraNear, Float.MAX_VALUE, TemporalFrame.fovY, TemporalFrame.reset,
                ran && haveOpaque ? reactive : null, ran ? transparency : null, sharp));
        TemporalFrame.frameDone();
        haveSceneDepth = haveOpaque = havePhase = false;
    }

    /** The bilinear null upscaler: src (render size) -> dst (display size), both GL row order, so no flip. */
    public static void blit(VkCommandBuffer cmd, MemoryStack stack, VulkanImage src, VulkanImage dst) {
        src.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        dst.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        boolean same = src.width == dst.width && src.height == dst.height;
        VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
        blit.srcSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        blit.dstSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
        blit.srcOffsets(0).set(0, 0, 0);
        blit.srcOffsets(1).set(src.width, src.height, 1);
        blit.dstOffsets(0).set(0, 0, 0);
        blit.dstOffsets(1).set(dst.width, dst.height, 1);
        vkCmdBlitImage(cmd, src.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                blit, same ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
    }

    /** shutdown (Vulkan.cleanUp), GPU idle */
    public static void cleanUp() {
        if (velocityPass != null) {
            velocityPass.destroy();
            velocityPass = null;
        }
        FsrAdapter.INSTANCE.destroyNow();
        adapter = null;
    }
}
