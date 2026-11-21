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

    // ---- rc6: copy regions batched per (image, staging buffer); spill staging instead of a mid-frame stall

    private VulkanImage pendingImage;
    private long pendingBuffer;
    private int pendingCount;
    private org.lwjgl.vulkan.VkBufferImageCopy.Buffer regions = org.lwjgl.vulkan.VkBufferImageCopy.calloc(64);
    /** Upload copies recorded as vkCmdCopyBufferToImage calls (for -Diroh.uploadStats). */
    public long copyCommands;

    /** rc7 / perf-uploads: region array capacity, touched images and the staging ring (for -Diroh.memStats). */
    public String memSummary() {
        var r = net.vulkanmod.vulkan.memory.buffer.StagingBuffer.ring();
        return "regions cap " + regions.capacity() + ", touched " + touched.size()
                + (r == null ? "" : ", staging ring " + (r.totalBytes() >> 20) + " MB (" + r.liveBlocks() + " live, " + r.pooledBlocks() + " pooled, " + r.pendingReservations() + " pending)");
    }

    /**
     * rc6: queue one buffer-to-image copy. Consecutive copies into the same image from the same staging buffer (an animated
     * sprite's mip chain, an atlas's sprites in one tick, a burst of DynamicTexture uploads) become ONE
     * vkCmdCopyBufferToImage with many regions. Pending regions are recorded before anything else is recorded into the
     * upload buffer ({@link #getOrStartCommandBuffer}) and at submit, so command order is unchanged.
     */
    public void queueCopy(VulkanImage image, long buffer, long bufferOffset, int mipLevel, int width, int height, int x, int y, int rowLength) {
        if (image != this.pendingImage || buffer != this.pendingBuffer) {
            flushPendingCopies();
            this.pendingImage = image;
            this.pendingBuffer = buffer;
            // layout change recorded now, before this image's first pending region
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                image.transitionImageLayout(stack, startCommandBuffer().getHandle(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            }
        }
        if (this.pendingCount == this.regions.capacity()) {
            var bigger = org.lwjgl.vulkan.VkBufferImageCopy.calloc(this.regions.capacity() * 2);
            org.lwjgl.system.MemoryUtil.memCopy(this.regions.address(), bigger.address(), (long) this.pendingCount * org.lwjgl.vulkan.VkBufferImageCopy.SIZEOF);
            this.regions.free();
            this.regions = bigger;
        }
        var r = this.regions.get(this.pendingCount++);
        r.bufferOffset(bufferOffset).bufferRowLength(rowLength).bufferImageHeight(height);
        r.imageSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, mipLevel, 0, 1);
        r.imageOffset().set(x, y, 0);
        r.imageExtent().set(width, height, 1);
    }

    /** Records the pending regions (one copy command). */
    public void flushPendingCopies() {
        if (this.pendingCount == 0) {
            this.pendingImage = null;
            return;
        }
        if (this.pendingImage.getId() == 0L) { // destroyed while its copies were pending: drop them, never copy into a null handle
            this.pendingCount = 0;
            this.pendingImage = null;
            return;
        }
        var cb = startCommandBuffer().getHandle();
        net.vulkanmod.vulkan.memory.buffer.StagingBuffer.checkConsumer(cb, this.pendingBuffer); // Iroh R-3
        vkCmdCopyBufferToImage(cb, this.pendingBuffer, this.pendingImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                org.lwjgl.vulkan.VkBufferImageCopy.create(this.regions.address(), this.pendingCount));
        this.copyCommands++;
        this.pendingCount = 0;
        this.pendingImage = null;
        this.pendingBuffer = 0;
    }

    private CommandPool.CommandBuffer startCommandBuffer() {
        if (this.currentCmdBuffer == null) {
            this.currentCmdBuffer = this.queue.beginCommands();
        }
        return this.currentCmdBuffer;
    }

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
        flushPendingCopies(); // rc6

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
                int queued = this.barriers.size();
                this.barriers.flush(handle, net.vulkanmod.vulkan.texture.ImageBarrierBatch.VULKAN);
                if (dev.elemore.iroh.diag.UploadStats.ON) { dev.elemore.iroh.diag.UploadStats.submitBarrierCalls += this.barriers.lastCalls(); dev.elemore.iroh.diag.UploadStats.submitBarrierImages += queued; }
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
            if (dev.elemore.iroh.diag.UploadStats.ON) dev.elemore.iroh.diag.UploadStats.submits++;
            Synchronization.INSTANCE.addCommandBuffer(cb);
        }
    }

    /** Anything recording into the upload buffer goes through here: pending copies are recorded first (rc6). */
    public CommandPool.CommandBuffer getOrStartCommandBuffer() {
        flushPendingCopies();
        return startCommandBuffer();
    }

    public CommandPool.CommandBuffer getCommandBuffer() {
        return this.currentCmdBuffer;
    }
}
