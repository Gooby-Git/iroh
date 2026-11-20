package net.vulkanmod.render.texture;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.vulkan.VK10.*;

public class ImageUploadHelper {

    public static final ImageUploadHelper INSTANCE = new ImageUploadHelper();

    final Queue queue;
    private CommandPool.CommandBuffer currentCmdBuffer;

    /** Iroh (td-4a9be3): images whose latest layout change is recorded in {@link #currentCmdBuffer}. */
    private final ReferenceArrayList<VulkanImage> touched = new ReferenceArrayList<>();

    public ImageUploadHelper() {
        queue = DeviceManager.getGraphicsQueue();
    }

    /** Called by VulkanImage when a barrier is recorded into the current upload buffer. */
    public void track(VulkanImage image) {
        if (!this.touched.contains(image))
            this.touched.add(image);
    }

    public void submitCommands() {
        if (this.currentCmdBuffer == null) {
            return;
        }

        // Iroh (td-4a9be3): this buffer executes before the frame command buffer. Every image it left in
        // TRANSFER_DST goes back to SHADER_READ_ONLY here, because the frame's draws (recorded earlier or later
        // this frame) sample it in that layout. Without this, a texture uploaded after its last draw of the frame
        // (WaterMedia's video frame arriving mid-frame) stayed in TRANSFER_DST at submit: validation
        // "expects SHADER_READ_ONLY_OPTIMAL, current TRANSFER_DST_OPTIMAL".
        if (!this.touched.isEmpty()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                for (VulkanImage image : this.touched) {
                    if (image.isUploadOwned() && image.getId() != 0L
                            && image.getCurrentLayout() == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                            && (image.usage & VK_IMAGE_USAGE_SAMPLED_BIT) != 0)
                        image.readOnlyLayout(stack, this.currentCmdBuffer.getHandle());
                    image.clearUploadOwned();
                }
            }
            this.touched.clear();
        }

        long fence = queue.submitCommands(this.currentCmdBuffer);
        Synchronization.INSTANCE.addCommandBuffer(this.currentCmdBuffer);

        this.currentCmdBuffer = null;
    }

    public CommandPool.CommandBuffer getOrStartCommandBuffer() {
        if (this.currentCmdBuffer == null) {
            this.currentCmdBuffer = this.queue.beginCommands();
        }

        return this.currentCmdBuffer;
    }

    public CommandPool.CommandBuffer getCommandBuffer() {
        return this.currentCmdBuffer;
    }
}
