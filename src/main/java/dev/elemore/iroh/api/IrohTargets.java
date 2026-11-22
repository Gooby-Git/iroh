package dev.elemore.iroh.api;

import dev.elemore.iroh.gpu.GpuCore;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * api 4 (card V-M1): off-screen images owned by a mod. Capability {@link Capability#RENDER_TARGETS}.
 * Screen-scaled targets ({@code scale > 0}) follow window resizes (contents are lost; re-render them). Targets are
 * attachments of {@link #beginRendering} passes and sampled textures of indirect pipelines. Layout: Vulkan GENERAL, no
 * Y flip (row 0 = top). {@link #mainDepthCopy} copies the game's main depth (Iroh's convention: standard Z, clear 1,
 * LEQUAL, infinite far plane) into a mod target for its own depth tests.
 */
public final class IrohTargets {
    private IrohTargets() {
    }

    public enum Format {
        RGBA8(VK_FORMAT_R8G8B8A8_UNORM, 4, false),
        RGBA16F(VK_FORMAT_R16G16B16A16_SFLOAT, 8, false),
        R32F(VK_FORMAT_R32_SFLOAT, 4, false),
        DEPTH32F(VK_FORMAT_D32_SFLOAT, 4, true);

        public final int vkFormat, bytesPerTexel;
        public final boolean depth;

        Format(int vkFormat, int bytesPerTexel, boolean depth) {
            this.vkFormat = vkFormat;
            this.bytesPerTexel = bytesPerTexel;
            this.depth = depth;
        }
    }

    /** api 4.1: colour blending of an indirect / full-screen pipeline. */
    public enum Blend {
        NONE,
        /** GL blendFuncSeparate(SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ONE_MINUS_SRC_ALPHA). */
        ALPHA,
        /** GL blendFunc(ONE, SRC_ALPHA): the fragment goes UNDER the image (result = colour + image x alpha). */
        UNDER
    }

    /** Fixed size. Null when the owner's share would be exceeded. */
    public static RenderTargetHandle create(String owner, String name, Format format, int width, int height) {
        return GpuCore.get().createTarget(owner, name, format, width, height, 0);
    }

    /** A fraction of the window (e.g. 1 = full, 0.5 = half), recreated on resize. */
    public static RenderTargetHandle createScaled(String owner, String name, Format format, float scale) {
        return GpuCore.get().createTarget(owner, name, format, 0, 0, scale);
    }

    /**
     * Starts a pass drawing into {@code color} and/or {@code depth}. {@code reads}: every buffer the pass's draws read
     * (storage, index, commands, counts): Iroh places their barriers before the pass. Outside the main render pass only.
     */
    public static void beginRendering(RenderTargetHandle color, RenderTargetHandle depth, boolean clear, float[] clearRgba, float clearDepth,
                                      GpuBuffer... reads) {
        GpuCore.get().beginRendering(color, depth, clear, clearRgba, clearDepth, reads);
    }

    public static void endRendering() {
        GpuCore.get().endRendering();
    }

    /**
     * Copies the main depth into {@code target} (DEPTH32F, same size as the window). In game only, at
     * {@link FrameStage#AFTER_OPAQUE_TERRAIN} or later (Iroh ends and resumes the main pass around the copy). Throws
     * {@link UnsupportedOperationException} where there is no main target (offline devices).
     */
    public static void mainDepthCopy(RenderTargetHandle target) {
        GpuCore.get().mainDepthCopy(target);
    }

    /**
     * api 4.1: tokens for the game's main colour / depth target, usable only as the attachments of {@link #beginRendering}
     * (pipelines made for RGBA8 + DEPTH32F). In game that pass JOINS the bound main pass at a world stage (no clear, no
     * barriers inside): every buffer or target it reads must have been written before the last settle, i.e. in
     * {@link FrameStage#BEFORE_MAIN_PASS} or inside {@link #runOutsideMainPass}. Offline: stand-ins (GpuCore.standInMain).
     */
    public static RenderTargetHandle mainColor() {
        return GpuCore.get().mainColor();
    }

    public static RenderTargetHandle mainDepth() {
        return GpuCore.get().mainDepth();
    }

    /**
     * api 4.1: runs {@code work} where compute, copies and mod passes may be recorded. In game at a world stage the main
     * pass ends first and resumes after (LOAD); one global barrier at the end makes everything written visible to the
     * main pass. Cost: one pass break. Offline: just runs it.
     */
    public static void runOutsideMainPass(Runnable work) {
        GpuCore.get().runOutsideMainPass(work);
    }

    /** api 4.1: copies {@code src} into {@code dst} (same size and format). Outside a render pass. */
    public static void copy(RenderTargetHandle src, RenderTargetHandle dst) {
        GpuCore.get().copyTarget(src, dst);
    }

    /** api 4.1, offline only: fills a target from host texels (RGBA8 / R32F / DEPTH32F, 4 bytes per texel). */
    public static void upload(RenderTargetHandle target, ByteBuffer texels) {
        GpuCore.get().uploadTarget(target, texels);
    }

    /** Offline devices only (tests): the target's texels after all recorded work. */
    public static ByteBuffer readback(RenderTargetHandle target) {
        return GpuCore.get().readback(target);
    }
}
