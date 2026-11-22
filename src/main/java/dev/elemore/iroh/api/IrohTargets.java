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
        dev.elemore.iroh.gpu.game.MainDepth.copy(GpuCore.get(), target);
    }

    /** Offline devices only (tests): the target's texels after all recorded work. */
    public static ByteBuffer readback(RenderTargetHandle target) {
        return GpuCore.get().readback(target);
    }
}
