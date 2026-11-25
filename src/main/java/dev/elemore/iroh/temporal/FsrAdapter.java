package dev.elemore.iroh.temporal;

import dev.elemore.iroh.memory.MemoryGuard;
import dev.elemore.iroh.upscale.ImageRef;
import dev.elemore.iroh.upscale.UpscaleInputs;
import dev.elemore.iroh.upscale.fsr.FsrUpscaler;
import dev.elemore.iroh.upscale.fsr.NativeLoader;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Card P4-1b: FSR 3.1 (card P4-2a's {@link FsrUpscaler}, AMD SDK 1.1.4 + shim) behind {@link UpscalerAdapter}.
 * <ul>
 *   <li>Availability ({@link #usable}): native library loads, the device features were enabled at launch
 *   ({@link DeviceCaps#fsrCapable}), and no failure since the last mode/size change. Otherwise bilinear, with ONE log
 *   line naming the reason ({@link #reason}; the settings row shows it).</li>
 *   <li>Context lifetime ({@link FsrSession}): created on the first FSR frame and whenever render or display size or the
 *   flags change; the old context is destroyed through a deferred frame op (MemoryManager.addFrameOp: runs when this
 *   frame slot comes round again, i.e. after every frame that could have used it finished). Creating needs no idle GPU
 *   (FFX only allocates new resources); destroying does, hence the deferral.</li>
 *   <li>Memory: the SDK's estimate is checked against Iroh's budget BEFORE create (MemoryGuard.allowOptional);
 *   refusal = bilinear + one log line. After create the context's real bytes (memoryUsageBytes: SDK internal + shared
 *   images) are counted in MemoryGuard.EXTERNAL until destroy (FFX allocates outside VMA).</li>
 *   <li>Flags: DEPTH_INFINITE (standard Z, near 0.05, infinite far), AUTO_EXPOSURE, LDR colour (RGBA8 scene); shim
 *   NO_FP16 unless shaderFloat16 + shaderInt16 + 16-bit storage were enabled.</li>
 *   <li>Layouts: inputs SHADER_READ_ONLY, output (display colour, STORAGE usage when the features are on) GENERAL, as
 *   the shim declares them; the shim's backend restores those after the dispatch; then the output goes back to
 *   COLOR_ATTACHMENT for the GUI pass (explicit barrier: VulkanImage's helper knows no GENERAL).</li>
 * </ul>
 */
public final class FsrAdapter implements UpscalerAdapter {
    public static final FsrAdapter INSTANCE = new FsrAdapter();

    private final FsrSession session = new FsrSession(new FsrSession.Backend() {
        @Override public FsrUpscaler create(int flags, int shim, int rw, int rh, int dw, int dh) {
            dev.elemore.iroh.diag.FeatureFailures.inject("fsr"); // INT-2 test seam: FsrSession turns a throw into bilinear + one line
            VkDevice d = Vulkan.getVkDevice();
            return FsrUpscaler.create(d.getPhysicalDevice().getInstance().address(), d.getPhysicalDevice().address(), d.address(), 0L,
                    flags, shim, rw, rh, dw, dh);
        }
        @Override public boolean allow(long bytes) { // INT-4: per-context ceiling of the share table + the guard's total
            return bytes <= dev.elemore.iroh.memory.MemoryShares.Owner.FSR.ceiling(MemoryGuard.BUDGET.cap()) && MemoryGuard.allowOptional(bytes);
        }
        @Override public void account(long bytes) { MemoryGuard.addExternal(bytes); }
        @Override public void destroyLater(Runnable r) { MemoryManager.getInstance().addFrameOp(r); }
        @Override public void log(String msg) { Initializer.LOGGER.warn("Iroh upscale: {}", msg); }
    });

    private FsrAdapter() {}

    @Override public String name() { return "fsr3.1"; }
    @Override public boolean temporal() { return true; }
    @Override public boolean wantsVelocity() { return true; }

    /** -Diroh.fsr.debugChecking=true: FFX debug checker (create flag); its messages go to Iroh's log, each distinct one once. */
    public static final String DEBUG_CHECKING_PROPERTY = "iroh.fsr.debugChecking";
    /** -Diroh.fsr.debugView=true: SDK debug view drawn into the output (dispatch flag). */
    public static final String DEBUG_VIEW_PROPERTY = "iroh.fsr.debugView";

    /** FSR create flags: DEPTH_INFINITE | AUTO_EXPOSURE, plus DEBUG_CHECKING when the property is on. */
    public static int fsrFlags() {
        return FsrUpscaler.DEPTH_INFINITE | FsrUpscaler.AUTO_EXPOSURE
                | (Boolean.getBoolean(DEBUG_CHECKING_PROPERTY) ? FsrUpscaler.DEBUG_CHECKING : 0);
    }

    /** The five SetConstant tuning values for the live context (Q3 wires the settings to this). */
    public void setTuning(dev.elemore.iroh.upscale.fsr.FsrTuning t) {
        session.setTuning(t);
    }

    /** FSR can be used now (a failed create blocks it until the mode/size changes). */
    public boolean usable() {
        return DeviceCaps.fsrCapable() && NativeLoader.load() && !session.failed();
    }

    /** Why FSR is not usable, or "" (settings row). */
    public String reason() {
        if (!NativeLoader.load())
            return "needs the native library (" + NativeLoader.unavailableReason() + ")";
        if (!DeviceCaps.fsrCapable())
            return DeviceCaps.requested ? "this GPU lacks storage-image formats or subgroup quad ops" : "restart needed (device features are enabled at launch)";
        return session.failed() ? session.failure() : "";
    }

    /** a mode or size change: allow a new attempt after a failure */
    public void retry() {
        session.clearFailure();
    }

    @Override
    public void dispatch(VkCommandBuffer cmd, MemoryStack stack, Inputs in) {
        int flags = fsrFlags();
        int shim = DeviceCaps.fp16ShadersAllowed() ? 0 : FsrUpscaler.NO_FP16;
        FsrUpscaler up = session.ensure(flags, shim, in.renderW(), in.renderH(), in.displayW(), in.displayH());
        if (up == null) {
            Bilinear.INSTANCE.dispatch(cmd, stack, in);
            return;
        }
        // inputs -> SHADER_READ_ONLY for compute; output -> GENERAL (explicit: the helper knows no GENERAL)
        toRead(stack, cmd, in.colour());
        toRead(stack, cmd, in.depth());
        toRead(stack, cmd, in.velocity());
        toRead(stack, cmd, in.reactive());
        toRead(stack, cmd, in.transparency());
        VulkanImage out = in.output();
        VulkanImage.transitionLayout(stack, cmd, out, out.getCurrentLayout(), VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);

        UpscaleInputs u = FsrSession.inputs(cmd.address(), ref(in.colour()), ref(in.depth()), ref(in.velocity()),
                ref(in.reactive()), ref(in.transparency()), ref(out),
                new FsrSession.Numbers(in.jitterX(), in.jitterY(), in.mvScaleX(), in.mvScaleY(), in.renderW(), in.renderH(),
                        in.displayW(), in.displayH(), in.sharpness(), in.frameTimeMs(), in.cameraNear(), in.cameraFar(), in.fovY(), in.reset()));
        u.debugView = Boolean.getBoolean(DEBUG_VIEW_PROPERTY);
        session.dispatch(u);

        // back to what the GUI pass expects (render pass begin moves it on from COLOR_ATTACHMENT)
        VulkanImage.transitionLayout(stack, cmd, out, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
    }

    private static void toRead(MemoryStack stack, VkCommandBuffer cmd, VulkanImage img) {
        if (img == null || img.getCurrentLayout() == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            return;
        VulkanImage.transitionLayout(stack, cmd, img, img.getCurrentLayout(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
    }

    private static ImageRef ref(VulkanImage i) {
        if (i == null)
            return ImageRef.NONE;
        int layout = i.getCurrentLayout() == VK_IMAGE_LAYOUT_GENERAL ? ImageRef.LAYOUT_GENERAL : ImageRef.LAYOUT_SHADER_READ_ONLY;
        return new ImageRef(i.getId(), i.format, i.width, i.height, i.usage, layout);
    }

    /** last FFX runtime messages (warnings), "" when none */
    public String drainMessages() {
        return session.drainMessages();
    }

    /** deferred (frame op): in-flight frames may still use the context */
    @Override
    public void destroy() {
        session.close();
    }

    /** shutdown, GPU idle (Vulkan.cleanUp): close now, frame ops will not run any more */
    public void destroyNow() {
        session.closeNow();
    }
}
