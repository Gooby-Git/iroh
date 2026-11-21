package net.vulkanmod.render.texture;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.texture.VulkanImage;

import static org.lwjgl.vulkan.VK10.*;

public class ImageUploadHelper {

    public static final ImageUploadHelper INSTANCE = new ImageUploadHelper();

    final Queue queue;
    private CommandPool.CommandBuffer currentCmdBuffer;

    /** Iroh (td-4a9be3): images whose latest layout change is recorded in {@link #currentCmdBuffer}. */
    private final ReferenceArrayList<VulkanImage> touched = new ReferenceArrayList<>();
    /** Iroh (rc5): membership for {@link #touched} (was a linear contains: O(n^2) for a join's thousands of images). */
    private final it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<VulkanImage> touchedSet = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();
    /** Iroh (rc5, td-1cb256): all read-only transitions of one submit as one barrier call, heap array reused. */
    private final net.vulkanmod.vulkan.texture.ImageBarrierBatch barriers = new net.vulkanmod.vulkan.texture.ImageBarrierBatch();

    public ImageUploadHelper() {
        queue = DeviceManager.getGraphicsQueue();
    }

    /** Called by VulkanImage when a barrier is recorded into the current upload buffer. */
    public void track(VulkanImage image) {
        if (this.touchedSet.add(image))
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
        try {
            if (!this.touched.isEmpty()) {
                var handle = this.currentCmdBuffer.getHandle();
                for (int i = 0, n = this.touched.size(); i < n; i++) {
                    VulkanImage image = this.touched.get(i);
                    if (image.isUploadOwned() && image.getId() != 0L
                            && image.getCurrentLayout() == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                            && (image.usage & VK_IMAGE_USAGE_SAMPLED_BIT) != 0)
                        image.readOnlyLayoutBatched(this.barriers, handle);
                    image.clearUploadOwned();
                }
                this.barriers.flush(handle, net.vulkanmod.vulkan.texture.ImageBarrierBatch.VULKAN);
            }
        } finally {
            // rc5 (td-08dc9f): even if recording the transitions throws, submit what is recorded now and drop the list.
            // The old path left a half-processed list and an unsubmitted buffer that was submitted frames later, after
            // textures it references had been destroyed ("command buffer invalid: Texture ... destroyed").
            this.touched.clear();
            this.touchedSet.clear();
            CommandPool.CommandBuffer cb = this.currentCmdBuffer;
            this.currentCmdBuffer = null;
            queue.submitCommands(cb);
            Synchronization.INSTANCE.addCommandBuffer(cb);
        }
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
