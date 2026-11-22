package dev.elemore.iroh.temporal;

import com.mojang.blaze3d.platform.Window;
import dev.elemore.iroh.debug.DebugSwitches;
import net.minecraft.client.Minecraft;
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
 */
public final class TemporalRuntime {
    private TemporalRuntime() {}

    private static boolean inGameRender;
    private static boolean wasActive;
    private static UpscalerAdapter adapter;
    private static VelocityPass velocityPass;
    private static VulkanImage velocityImage;
    /** depth copied at vanilla's pre-hand depth clear this frame */
    private static VulkanImage sceneDepth;
    private static boolean haveSceneDepth;
    private static boolean warnedNoVelocity;
    private static long lastFrameNanos;
    private static float frameTimeMs = 16.7f;
    private static Object lastLevel;

    /** GameRenderer.render HEAD */
    public static void onRenderHead() {
        if (!TemporalFrame.ACTIVE && !UpscaleSwitches.mode().on())
            return;
        UpscaleSwitches.registerAll();
        inGameRender = true;
        SwapChain sc = Renderer.getInstance().getSwapChain();
        UpscalerAdapter a = UpscalerAdapter.select(DebugSwitches.getOr(UpscaleSwitches.UPSCALER, "auto"));
        if (adapter != a) {
            if (adapter != null)
                adapter.destroy(); // TODO(P4-2b): FSR contexts need the GPU idle: destroy through a deferred frame op
            adapter = a;
            TemporalFrame.invalidateHistory();
        }
        boolean changed = TemporalFrame.beginFrame(sc.getWidth(), sc.getHeight(), a.temporal(), a.wantsVelocity());
        if (TemporalFrame.velocityOn && !DeviceCaps.velocityCapable()) {
            if (!warnedNoVelocity) {
                Initializer.LOGGER.warn("Iroh upscale: velocity pass / debug view unavailable ({}); launch with -Diroh.upscale.mode=<mode> so the device features are enabled",
                        DeviceCaps.report());
                warnedNoVelocity = true;
            }
            TemporalFrame.velocityOn = false;
            if (TemporalFrame.debugMode != 4)
                TemporalFrame.debugMode = 0;
        }
        long now = System.nanoTime();
        if (lastFrameNanos != 0)
            frameTimeMs = (now - lastFrameNanos) / 1.0e6f;
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

    /** GameRenderer.render TAIL */
    public static void onRenderTail() {
        if (!inGameRender)
            return;
        if (TemporalFrame.IN_WORLD)
            onBeforeGui();
        inGameRender = false;
    }

    /** GameRenderer.renderLevel HEAD, only inside GameRenderer.render (panorama / other renderLevel callers are left alone) */
    public static void onRenderLevelHead() {
        if (!TemporalFrame.ACTIVE || !inGameRender || TemporalFrame.IN_WORLD)
            return;
        MainPass mp = Renderer.getInstance().getMainPass();
        if (mp instanceof DefaultMainPass dmp && dmp.beginWorld(TemporalFrame.renderW, TemporalFrame.renderH))
            TemporalFrame.IN_WORLD = true;
    }

    /** GameRenderer.renderLevel: the INVOKE of RenderSystem.clear right before renderItemInHand (depth-only clear) */
    public static void onBeforeHandClear() {
        if (!TemporalFrame.IN_WORLD)
            return;
        UpscalerAdapter a = adapter != null ? adapter : UpscalerAdapter.Bilinear.INSTANCE;
        if (!(TemporalFrame.velocityOn || a.temporal()) || !(Renderer.getInstance().getMainPass() instanceof DefaultMainPass dmp))
            return;
        int w = TemporalFrame.renderW, h = TemporalFrame.renderH;
        if (sceneDepth == null || sceneDepth.width != w || sceneDepth.height != h) {
            if (sceneDepth != null)
                sceneDepth.free();
            sceneDepth = VulkanImage.builder(w, h)
                    .setName("Iroh scene depth before hand (render scale)")
                    .setFormat(Vulkan.getDefaultDepthFormat())
                    .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .setLinearFiltering(false)
                    .setClamp(true)
                    .createVulkanImage();
        }
        haveSceneDepth = dmp.copyWorldDepth(sceneDepth);
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

    /** Fabric WorldRenderEvents.START (via WorldStages) */
    public static void onWorldStart(double x, double y, double z, org.joml.Matrix4fc rotation, org.joml.Matrix4fc projection) {
        if (!TemporalFrame.IN_WORLD)
            return;
        Object level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            TemporalFrame.invalidateHistory();
            lastLevel = level;
        }
        TemporalFrame.onWorldCamera(x, y, z, rotation, projection);
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

    private static void deactivate() {
        if (Renderer.getInstance().getMainPass() instanceof DefaultMainPass dmp)
            dmp.releaseWorld();
        if (velocityImage != null) {
            velocityImage.free();
            velocityImage = null;
        }
        if (sceneDepth != null) {
            sceneDepth.free();
            sceneDepth = null;
        }
        haveSceneDepth = false;
        // the compute pipeline is small (two pipelines + a pool): kept until shutdown
        Initializer.LOGGER.info("Iroh upscale: off, world images released");
    }

    /**
     * Records the world-end work into the frame command buffer (no render pass open): velocity + debug view, then the
     * upscale into the display colour.
     */
    public static void recordWorldEnd(VkCommandBuffer cmd, MemoryStack stack, VulkanImage worldColor, VulkanImage worldDepth, VulkanImage displayColor) {
        TemporalFrame.endWorld();
        UpscalerAdapter a = adapter != null ? adapter : UpscalerAdapter.Bilinear.INSTANCE;
        int rw = worldColor.width, rh = worldColor.height;
        boolean debugColour = TemporalFrame.debugMode != 0;
        if (TemporalFrame.velocityOn || (debugColour && DeviceCaps.velocityCapable())) {
            ensureVelocity(rw, rh);
            // depth: attachment -> sampled by compute
            VulkanImage.transitionLayout(stack, cmd, worldDepth, worldDepth.getCurrentLayout(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            // velocity: previous frame's readers (compute/fragment/upscaler) -> compute write
            VulkanImage.transitionLayout(stack, cmd, velocityImage, velocityImage.getCurrentLayout(), VK_IMAGE_LAYOUT_GENERAL,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
            if (debugColour)
                VulkanImage.transitionLayout(stack, cmd, worldColor, worldColor.getCurrentLayout(), VK_IMAGE_LAYOUT_GENERAL,
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);

            velocityPass.record(cmd, Renderer.getCurrentFrame(), worldDepth.getImageView(), haveSceneDepth ? sceneDepth.getImageView() : 0L,
                    velocityImage.getImageView(), debugColour ? worldColor.getImageView() : 0L, rw, rh, TemporalFrame.reproject,
                    TemporalFrame.jitterNdcX, TemporalFrame.jitterNdcY, TemporalFrame.jitterPxX, TemporalFrame.jitterPxY,
                    TemporalFrame.debugMode);

            VulkanImage.transitionLayout(stack, cmd, velocityImage, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            if (debugColour)
                VulkanImage.transitionLayout(stack, cmd, worldColor, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        }

        // depth for the upscaler: the scene depth from before the hand clear (the hand itself is missing from it:
        // a merged depth is card P4-1b), else the end depth
        a.dispatch(cmd, stack, new UpscalerAdapter.Inputs(worldColor, haveSceneDepth ? sceneDepth : worldDepth,
                TemporalFrame.velocityOn ? velocityImage : null, displayColor,
                rw, rh, displayColor.width, displayColor.height,
                TemporalFrame.jitterPxX, TemporalFrame.jitterPxY, rw, rh,
                frameTimeMs, TemporalFrame.cameraNear, Float.MAX_VALUE, TemporalFrame.fovY, TemporalFrame.reset));
        TemporalFrame.frameDone();
        haveSceneDepth = false;
    }

    private static void ensureVelocity(int w, int h) {
        if (velocityPass == null)
            velocityPass = new VelocityPass(Vulkan.getVkDevice(), Renderer.getFramesNum());
        if (velocityImage != null && velocityImage.width == w && velocityImage.height == h)
            return;
        if (velocityImage != null)
            velocityImage.free();
        velocityImage = VulkanImage.builder(w, h)
                .setName("Iroh velocity RG16F (render scale)")
                .setFormat(VK_FORMAT_R16G16_SFLOAT)
                .setUsage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                .setLinearFiltering(false)
                .setClamp(true)
                .createVulkanImage();
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
        if (adapter != null) {
            adapter.destroy();
            adapter = null;
        }
    }
}
