package net.vulkanmod.vulkan.texture;

import net.vulkanmod.render.texture.ImageUploadHelper;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.buffer.StagingBuffer;
import net.vulkanmod.vulkan.queue.CommandPool;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import static net.vulkanmod.vulkan.texture.SamplerManager.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanImage {
    public static int DefaultFormat = VK_FORMAT_R8G8B8A8_UNORM;

    private static final VkDevice DEVICE = Vulkan.getVkDevice();

    public final String name;
    /** rc9: owner key for the growth monitor: creator + calling mod (digits folded), e.g. "GlTexture depth by vista". */
    public String owner = "?";
    /** rc13: followed by -Diroh.imageTrace (creation, delete request, deferred free, destroy). */
    public boolean traced;
    public final int format;
    public final int aspect;
    public final int mipLevels;
    public final int width;
    public final int height;
    public final int formatSize;
    public final int usage;
    public final int size;

    private long id;
    private long allocation;
    private long mainImageView;

    private long[] levelImageViews;

    private long sampler;

    private int currentLayout;

    // ---------------------------------------------------------------- Iroh (td-4a9be3): which command buffer owns the layout
    // The upload command buffer (ImageUploadHelper) is submitted BEFORE the frame command buffer, but both are recorded
    // during the same frame, and currentLayout is one variable. Recording order != execution order, so:
    //  - uploadOwned: the last layout change was recorded in the not yet submitted upload buffer. Later transitions of
    //    this image that must also precede the frame (readOnlyLayout) go there too, and the upload buffer ends with the
    //    image back in SHADER_READ_ONLY (ImageUploadHelper.submitCommands), the layout every frame draw expects.
    //  - frameUse == frameSerial: the frame command buffer already used the image this frame (sampled, attachment,
    //    transition). An upload or transition after that must be recorded in the frame buffer (outside a render pass)
    //    so it executes after those uses, as in GL.
    private boolean uploadOwned;
    private long frameUse = -1;
    private static long frameSerial;

    /** Renderer: a new frame command buffer was begun (frame start, or resumed after flushCmds). */
    public static void nextFrameSerial() {
        frameSerial++;
    }

    public boolean usedThisFrame() {
        return this.frameUse == frameSerial;
    }

    public void markFrameUse() {
        this.frameUse = frameSerial;
    }

    public boolean isUploadOwned() {
        return this.uploadOwned;
    }

    /** ImageUploadHelper, after the upload buffer was submitted: its transitions are history now. */
    public void clearUploadOwned() {
        this.uploadOwned = false;
    }

    // Used for already allocated images e.g. swap chain images
    public VulkanImage(String name, long id, int format, int mipLevels, int width, int height, int formatSize, int usage, long imageView) {
        this.id = id;
        this.mainImageView = imageView;

        this.name = name;
        this.mipLevels = mipLevels;
        this.width = width;
        this.height = height;
        this.formatSize = formatSize;
        this.format = format;
        this.usage = usage;
        this.aspect = getAspect(this.format);

        this.size = width * height * formatSize;

        this.sampler = SamplerManager.getTextureSampler((byte) this.mipLevels, (byte) 0);
    }

    private VulkanImage(Builder builder) {
        this.name = builder.name;
        this.owner = builder.owner != null ? builder.owner : builder.name;
        this.mipLevels = builder.mipLevels;
        this.width = builder.width;
        this.height = builder.height;
        this.formatSize = builder.formatSize;
        this.format = builder.format;
        this.usage = builder.usage;
        this.aspect = getAspect(this.format);

        this.size = width * height * formatSize;
    }

    public static VulkanImage createTextureImage(Builder builder) {
        VulkanImage image = new VulkanImage(builder);

        image.createImage();
        image.mainImageView = createImageView(image.id, builder.format, image.aspect, builder.mipLevels);

        image.sampler = SamplerManager.getTextureSampler(builder.mipLevels, builder.samplerFlags);

        if (builder.levelViews) {
            image.levelImageViews = new long[builder.mipLevels];

            for (int i = 0; i < builder.mipLevels; ++i) {
                image.levelImageViews[i] = createImageView(image.id, image.format, image.aspect, i, 1);
            }
        }

        return image;
    }

    public static VulkanImage createDepthImage(int format, int width, int height, int usage, boolean blur, boolean clamp) {
        return createDepthImage(null, null, format, width, height, usage, blur, clamp);
    }

    /** rc9: named / owned depth image (null = derived from the caller). */
    public static VulkanImage createDepthImage(String name, String owner, int format, int width, int height, int usage, boolean blur, boolean clamp) {
        VulkanImage image = VulkanImage.builder(width, height)
                .setName(name)
                .setOwner(owner)
                .setFormat(format)
                .setUsage(usage)
                .setLinearFiltering(blur)
                .setClamp(clamp)
                .createVulkanImage();

        return image;
    }

    public static VulkanImage createWhiteTexture() {
        try (MemoryStack stack = stackPush()) {
            int i = 0xFFFFFFFF;
            ByteBuffer buffer = stack.malloc(4);
            buffer.putInt(0, i);

            VulkanImage image = VulkanImage.builder(1, 1)
                    .setFormat(DefaultFormat)
                    .setUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .setLinearFiltering(false)
                    .setClamp(false)
                    .createVulkanImage();
            image.uploadSubTextureAsync(0, image.width, image.height, 0, 0, 0, 0, 0, buffer);
            return image;
//            return createTextureImage(1, 1, 4, false, false, buffer);
        }
    }

    private void createImage() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pTextureImage = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.pointers(0L);

            MemoryManager.getInstance().createImage(width, height, mipLevels,
                    format, VK_IMAGE_TILING_OPTIMAL,
                    usage,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    pTextureImage,
                    pAllocation);

            id = pTextureImage.get(0);
            allocation = pAllocation.get(0);

            MemoryManager.addImage(this);

            if (this.name != null) {
                Vulkan.setDebugLabel(stack, VK_OBJECT_TYPE_IMAGE, pTextureImage.get(), this.name);
            }
        }
    }

    public static int getAspect(int format) {
        return switch (format) {
            case VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT_S8_UINT ->
                    VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;

            case VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D32_SFLOAT,
                 VK_FORMAT_D16_UNORM -> VK_IMAGE_ASPECT_DEPTH_BIT;

            default -> VK_IMAGE_ASPECT_COLOR_BIT;
        };
    }

    public static boolean isDepthFormat(int format) {
        return switch (format) {
            case VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D24_UNORM_S8_UINT,
                 VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT,
                 VK_FORMAT_D16_UNORM -> true;
            default -> false;
        };
    }

    public static long createImageView(long image, int format, int aspectFlags, int mipLevels) {
        return createImageView(image, format, aspectFlags, 0, mipLevels);
    }

    public static long createImageView(long image, int format, int aspectFlags, int baseMipLevel, int mipLevels) {

        try (MemoryStack stack = stackPush()) {

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack);
            viewInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            viewInfo.image(image);
            viewInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
            viewInfo.format(format);
            viewInfo.subresourceRange().aspectMask(aspectFlags);
            viewInfo.subresourceRange().baseMipLevel(baseMipLevel);
            viewInfo.subresourceRange().levelCount(mipLevels);
            viewInfo.subresourceRange().baseArrayLayer(0);
            viewInfo.subresourceRange().layerCount(1);

            LongBuffer pImageView = stack.mallocLong(1);

            if (dev.elemore.iroh.memory.MemoryGuard.check(vkCreateImageView(DEVICE, viewInfo, null, pImageView), "vkCreateImageView") != VK_SUCCESS) {
                throw new RuntimeException("Failed to create texture image view");
            }

            return pImageView.get(0);
        }
    }

    public void uploadSubTextureAsync(int mipLevel, int width, int height, int xOffset, int yOffset, int unpackSkipRows, int unpackSkipPixels, int unpackRowLength, ByteBuffer buffer) {
        this.uploadSubTextureAsync(mipLevel, width, height,
                                   xOffset, yOffset, unpackSkipRows, unpackSkipPixels, unpackRowLength,
                                   MemoryUtil.memAddress(buffer));
    }

    private static boolean loggedBadUpload;

    public void uploadSubTextureAsync(int mipLevel, int width, int height, int xOffset, int yOffset, int unpackSkipRows, int unpackSkipPixels, int unpackRowLength, long srcPtr) {
        // Iroh: GL semantics, row length 0 = width (the base copied 0 bytes then and uploaded staging garbage:
        // Xaero's striped map tiles, td-834bea). Size = exactly the rows the copy reads.
        if (unpackRowLength <= 0)
            unpackRowLength = width;
        // Iroh (rc6, td-fcd844): destination checked before any copy (the source span is the caller's to check: it knows
        // the allocation; VkGlTexture.uploadClient and the NativeImage path do).
        int lw = Math.max(1, this.width >> mipLevel), lh = Math.max(1, this.height >> mipLevel);
        if (srcPtr == 0L || width <= 0 || height <= 0 || mipLevel < 0 || mipLevel >= this.mipLevels || xOffset < 0 || yOffset < 0
                || xOffset + width > lw || yOffset + height > lh || unpackSkipPixels + width > unpackRowLength || this.id == 0L) {
            if (!loggedBadUpload) {
                loggedBadUpload = true;
                net.vulkanmod.Initializer.LOGGER.error("Iroh: texture upload skipped: {} level {} region {},{} {}x{} (level {}x{}, mips {}), rowLength {} skip {},{}, src 0x{} (logged once)",
                        this.name, mipLevel, xOffset, yOffset, width, height, lw, lh, this.mipLevels, unpackRowLength, unpackSkipPixels,
                        unpackSkipRows, Long.toHexString(srcPtr), new Throwable("uploaded from"));
            }
            return;
        }
        long uploadSize = ((long) unpackRowLength * (height - 1) + width) * this.formatSize;
        if (dev.elemore.iroh.diag.UploadStats.ON) dev.elemore.iroh.diag.UploadStats.upload(this, uploadSize);

        // Iroh (td-4a9be3): the frame already used this image: upload in the frame buffer so earlier draws keep the
        // old contents and the layout history stays in one buffer.
        if (Renderer.isRecording() && usedThisFrame()) {
            srcPtr += ((long) unpackRowLength * unpackSkipRows + unpackSkipPixels) * this.formatSize;
            uploadInFrame(mipLevel, width, height, xOffset, yOffset, unpackRowLength, srcPtr, uploadSize);
            return;
        }

        // rc6: staging that fits without a flush (shared buffer or spill block); the copy into it is synchronous, so the
        // caller's memory is never read after this returns, and no staging offset is held across a flush.
        ImageUploadHelper helper = ImageUploadHelper.INSTANCE;
        srcPtr += ((long) unpackRowLength * unpackSkipRows + unpackSkipPixels) * this.formatSize;
        // perf/uploads: ring staging (chained blocks, no flush); consumed now, recorded in this epoch's upload buffer
        var alloc = StagingBuffer.stage(uploadSize, this.formatSize, srcPtr);
        if (dev.elemore.iroh.diag.UploadStats.ON) dev.elemore.iroh.diag.UploadStats.asyncUploads++;
        helper.queueCopy(this, alloc.bufferId, alloc.offset, mipLevel, width, height, xOffset, yOffset, unpackRowLength);
        StagingBuffer.ring().consumed(alloc);
    }

    private void transferDstLayout(MemoryStack stack, VkCommandBuffer commandBuffer) {
        transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    }

    /**
     * Iroh (td-4a9be3): upload recorded in the frame command buffer, outside any render pass, from a private staging
     * buffer freed after this frame's fence (the per-frame staging buffer is reset before the frame executes).
     */
    // ---------------------------------------------------------------- perf/uploads: batched in-frame uploads

    private boolean inFramePending;
    private static final it.unimi.dsi.fastutil.objects.ObjectArrayList<VulkanImage> IF_IMAGES = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
    private static final it.unimi.dsi.fastutil.longs.LongArrayList IF_DATA = new it.unimi.dsi.fastutil.longs.LongArrayList(); // 8 longs per upload
    private static final ImageBarrierBatch IF_BARRIERS = new ImageBarrierBatch();

    /**
     * Iroh (perf/uploads): an upload into an image the frame already used. GL semantics: earlier draws keep the old
     * texels, later draws see the new ones. The base ended and resumed the render pass and allocated a private staging
     * buffer for EACH such upload; now the data goes to ring staging and the copy is queued, and all queued in-frame
     * copies are recorded together (one pass break) right before the next draw that binds one of the images
     * (VTextureSelector.bindShaderTextures), before an off-screen pass begins, before a host read, and at frame end.
     */
    private void uploadInFrame(int mipLevel, int width, int height, int xOffset, int yOffset, int rowLength, long srcPtr, long uploadSize) {
        if (dev.elemore.iroh.diag.UploadStats.ON) dev.elemore.iroh.diag.UploadStats.inFrameUploads++;
        ImageUploadHelper.INSTANCE.flushPendingCopies(); // rc6: keep upload-buffer order for this image
        var alloc = StagingBuffer.stage(uploadSize, this.formatSize, srcPtr);
        long bufferId = alloc.bufferId;
        long srcOffset = alloc.offset;
        StagingBuffer.ring().consumedByFrame(alloc); // read by this frame's command buffer: released after the frame

        Framebuffer boundFb = Renderer.getInstance().getBoundFramebuffer();
        if (boundFb != null && (boundFb.getColorAttachment() == this || boundFb.getDepthAttachment() == this)) {
            // an attachment of the bound pass (GL feedback loop): cannot copy inside the pass; upload buffer as before
            logFeedbackOnce();
            ImageUploadHelper.INSTANCE.queueCopy(this, bufferId, srcOffset, mipLevel, width, height, xOffset, yOffset, rowLength);
            return;
        }
        this.inFramePending = true;
        IF_IMAGES.add(this);
        IF_DATA.add(bufferId);
        IF_DATA.add(srcOffset);
        IF_DATA.add(mipLevel);
        IF_DATA.add(width);
        IF_DATA.add(height);
        IF_DATA.add(xOffset);
        IF_DATA.add(yOffset);
        IF_DATA.add(rowLength);
    }

    public boolean hasInFramePending() {
        return this.inFramePending;
    }

    public static boolean anyInFramePending() {
        return !IF_IMAGES.isEmpty();
    }

    /** Records every queued in-frame upload into the frame command buffer, outside the bound pass (one break). */
    public static void flushInFrame() {
        if (IF_IMAGES.isEmpty())
            return;
        if (Renderer.recordingCommandBuffer("in-frame uploads") == null) {
            clearInFrame();
            return;
        }
        if (dev.elemore.iroh.diag.UploadStats.ON) dev.elemore.iroh.diag.UploadStats.inFrameFlushes++;
        endPassRunResume(() -> {
            VkCommandBuffer cb = Renderer.getCommandBuffer();
            try (MemoryStack stack = stackPush()) {
                for (int i = 0, n = IF_IMAGES.size(); i < n; i++) {
                    VulkanImage img = IF_IMAGES.get(i);
                    if (img.id == 0L) continue;
                    img.transitionImageLayout(stack, cb, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                    int o = i * 8;
                    ImageUtil.copyBufferToImageCmd(stack, cb, IF_DATA.getLong(o), img.id, (int) IF_DATA.getLong(o + 2),
                            (int) IF_DATA.getLong(o + 3), (int) IF_DATA.getLong(o + 4), (int) IF_DATA.getLong(o + 5),
                            (int) IF_DATA.getLong(o + 6), (int) IF_DATA.getLong(o + 1), (int) IF_DATA.getLong(o + 7),
                            (int) IF_DATA.getLong(o + 4));
                }
                for (int i = 0, n = IF_IMAGES.size(); i < n; i++) {
                    VulkanImage img = IF_IMAGES.get(i);
                    if (img.inFramePending && img.id != 0L && (img.usage & VK_IMAGE_USAGE_SAMPLED_BIT) != 0
                            && img.currentLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                        img.readOnlyLayoutBatched(IF_BARRIERS, cb);
                    img.inFramePending = false;
                }
                IF_BARRIERS.flush(cb, ImageBarrierBatch.VULKAN);
            }
        });
        clearInFrame();
    }

    private static void clearInFrame() {
        for (VulkanImage img : IF_IMAGES) img.inFramePending = false;
        IF_IMAGES.clear();
        IF_DATA.clear();
    }

    public boolean outsideRenderPass(Runnable record) {
        Renderer renderer = Renderer.getInstance();
        RenderPass boundPass = renderer.getBoundRenderPass();
        if (boundPass == null) {
            record.run();
            return true;
        }
        Framebuffer boundFb = renderer.getBoundFramebuffer();
        if (boundFb != null && (boundFb.getColorAttachment() == this || boundFb.getDepthAttachment() == this)) {
            logFeedbackOnce();
            return false;
        }
        endPassRunResume(record);
        return true;
    }

    /** Ends the bound render pass (if any), runs {@code record} on the frame command buffer, resumes the pass. */
    public static void endPassRunResume(Runnable record) {
        Renderer renderer = Renderer.getInstance();
        RenderPass boundPass = renderer.getBoundRenderPass();
        if (boundPass == null) {
            record.run();
            return;
        }
        Framebuffer boundFb = renderer.getBoundFramebuffer();
        VkGlFramebuffer glFb = VkGlFramebuffer.getBoundFramebuffer();
        renderer.endRenderPass();
        record.run();
        if (glFb == null && boundFb instanceof net.vulkanmod.vulkan.framebuffer.SceneTarget) {
            renderer.getMainPass().rebindMainTarget();          // LOAD pass; the CLEAR pass is frame-start only
        } else if (boundFb != null) {
            renderer.beginRendering(boundPass, boundFb);        // off-screen passes load (VkGlFramebuffer)
            VkGlFramebuffer.restoreBinding(glFb);
        }
        // Framebuffer.beginRenderPass reset viewport and scissor to full size
        dev.elemore.iroh.glshim.GlState.reapplyViewportAndScissor();
    }

    public void readOnlyLayout() {
        if (this.currentLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            markFrameUse();
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (this.uploadOwned) {
                // td-4a9be3: the pending layout change is in the upload buffer, which executes before the frame:
                // the transition belongs there too, and the frame then sees SHADER_READ_ONLY from its start.
                readOnlyLayout(stack, ImageUploadHelper.INSTANCE.getOrStartCommandBuffer().getHandle());
                markFrameUse();
                return;
            }

            Renderer renderer = Renderer.getInstance();
            RenderPass boundPass = renderer.getBoundRenderPass();
            if (boundPass != null) {
                if (isAttachmentLayout(this.currentLayout) || usedThisFrame()) {
                    // Iroh (RQ-10, td-4a9be3): this image was rendered to / used earlier in this frame (a render
                    // target left in an attachment layout, e.g. the main scene colour sampled while an off-screen
                    // pass is bound, or a texture copied into in the frame). The upload command buffer runs BEFORE
                    // the frame's commands, so recording the transition there executes it before the work it must
                    // follow. Record it in the frame command buffer between render passes, then resume the pass.
                    if (!outsideRenderPass(() -> {
                        try (MemoryStack s = stackPush()) {
                            readOnlyLayout(s, Renderer.getCommandBuffer());
                        }
                    })) {
                        // sampling an attachment of the bound pass (GL feedback loop): cannot be made valid, keep the old path
                        readOnlyLayout(stack, ImageUploadHelper.INSTANCE.getOrStartCommandBuffer().getHandle());
                    }
                    markFrameUse();
                    return;
                }
                CommandPool.CommandBuffer commandBuffer = ImageUploadHelper.INSTANCE.getOrStartCommandBuffer();
                VkCommandBuffer vkCommandBuffer = commandBuffer.getHandle();

                readOnlyLayout(stack, vkCommandBuffer);
            }
            else {
                readOnlyLayout(stack, Renderer.getCommandBuffer());
            }
            markFrameUse();
        }
    }

    private static boolean loggedFeedback;

    private static void logFeedbackOnce() {
        if (!loggedFeedback) {
            loggedFeedback = true;
            net.vulkanmod.Initializer.LOGGER.warn("Iroh: a texture attached to the bound render pass is sampled by the same pass (GL feedback loop); its layout transition stays in the upload buffer (RQ-10)");
        }
    }

    private static boolean isAttachmentLayout(int layout) {
        return layout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
               || layout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    }

    public void readOnlyLayout(MemoryStack stack, VkCommandBuffer commandBuffer) {
        transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    public void updateTextureSampler(boolean blur, boolean clamp, boolean mipmaps) {
        byte flags = blur ? LINEAR_FILTERING_BIT : 0;
        flags |= clamp ? CLAMP_BIT : 0;
        flags |= (byte) (mipmaps ? USE_MIPMAPS_BIT | MIPMAP_LINEAR_FILTERING_BIT : 0);

        this.updateTextureSampler(flags);
    }

    public void updateTextureSampler(byte flags) {
        updateTextureSampler(this.mipLevels - 1, flags);
    }

    public void updateTextureSampler(int maxLod, byte flags) {
        this.sampler = SamplerManager.getTextureSampler((byte) maxLod, flags);
    }

    public void transitionImageLayout(MemoryStack stack, VkCommandBuffer commandBuffer, int newLayout) {
        transitionImageLayout(stack, commandBuffer, this, newLayout);
    }

    public static void transitionImageLayout(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image, int newLayout) {
        if (image.currentLayout == newLayout) {
//            System.out.println("new layout is equal to current layout");
            return;
        }

        int sourceStage, srcAccessMask, destinationStage, dstAccessMask = 0;

        switch (image.currentLayout) {
            case VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR -> {
                srcAccessMask = 0;
                sourceStage = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
            }
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                sourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            }
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> {
                srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
                sourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            }
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
                sourceStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            }
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> {
                srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
                sourceStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            }
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> {
                srcAccessMask = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
                sourceStage = VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            }
            default -> throw new RuntimeException("Unexpected value:" + image.currentLayout);
        }

        switch (newLayout) {
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> {
                dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            }
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> {
                dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
                destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
            }
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> {
                dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                destinationStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            }
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> {
                dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
                destinationStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            }
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> {
                dstAccessMask = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
                destinationStage = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
            }
            case VK_IMAGE_LAYOUT_PRESENT_SRC_KHR -> {
                destinationStage = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
            }
            default -> throw new RuntimeException("Unexpected value:" + newLayout);
        }

        transitionLayout(stack, commandBuffer, image, image.currentLayout, newLayout,
                sourceStage, srcAccessMask, destinationStage, dstAccessMask);
    }

    public static void transitionLayout(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image, int oldLayout, int newLayout,
                                        int sourceStage, int srcAccessMask, int destinationStage, int dstAccessMask) {
        transitionLayout(stack, commandBuffer, image, 0, oldLayout, newLayout,
                sourceStage, srcAccessMask, destinationStage, dstAccessMask);
    }

    public static void transitionLayout(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image, int baseLevel, int oldLayout, int newLayout,
                                        int sourceStage, int srcAccessMask, int destinationStage, int dstAccessMask) {
        // Iroh (rc5, td-1cb256): own stack frame. Callers record many transitions under one frame (an upload flush of
        // thousands of images); a per-call calloc without a pop overflowed LWJGL's 64 KB thread stack ->
        // OutOfMemoryError -> vanilla's emergencySave halted the world.
        try (MemoryStack frame = stack.push()) {
            recordBarrier(frame, commandBuffer, image, baseLevel, newLayout, sourceStage, srcAccessMask, destinationStage, dstAccessMask);
        }
        image.currentLayout = newLayout;
        noteRecordedIn(image, commandBuffer);
    }

    private static void recordBarrier(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image, int baseLevel, int newLayout,
                                      int sourceStage, int srcAccessMask, int destinationStage, int dstAccessMask) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
        barrier.oldLayout(image.currentLayout);
        barrier.newLayout(newLayout);
        barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.image(image.getId());

        barrier.subresourceRange().baseMipLevel(baseLevel);
        barrier.subresourceRange().levelCount(VK_REMAINING_MIP_LEVELS);
        barrier.subresourceRange().baseArrayLayer(0);
        barrier.subresourceRange().layerCount(VK_REMAINING_ARRAY_LAYERS);

        barrier.subresourceRange().aspectMask(image.aspect);

        barrier.srcAccessMask(srcAccessMask);
        barrier.dstAccessMask(dstAccessMask);

        vkCmdPipelineBarrier(commandBuffer,
                sourceStage, destinationStage,
                0,
                null,
                null,
                barrier);
    }

    /**
     * Iroh (rc5, td-1cb256): queue this image's TRANSFER_DST -> SHADER_READ_ONLY transition into {@code batch} (recorded by
     * the batch's flush into {@code commandBuffer}); state is updated now, as the barrier precedes any later command.
     */
    public void readOnlyLayoutBatched(ImageBarrierBatch batch, VkCommandBuffer commandBuffer) {
        if (this.currentLayout != VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
            throw new IllegalStateException("batched read-only transition expects TRANSFER_DST, layout " + this.currentLayout);
        batch.add(this.getId(), this.aspect, 0, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        this.currentLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        noteRecordedIn(this, commandBuffer);
    }

    /** Iroh (td-4a9be3): remember which buffer holds the image's latest layout change (see uploadOwned). */
    private static void noteRecordedIn(VulkanImage image, VkCommandBuffer commandBuffer) {
        CommandPool.CommandBuffer upload = ImageUploadHelper.INSTANCE.getCommandBuffer();
        long cb = commandBuffer.address();
        if (upload != null && upload.getHandle().address() == cb) {
            image.uploadOwned = true;
            ImageUploadHelper.INSTANCE.track(image);
        } else if (Renderer.isRecording() && Renderer.getCommandBuffer() != null && Renderer.getCommandBuffer().address() == cb) {
            image.uploadOwned = false;
            image.frameUse = frameSerial;
        }
    }

    private static boolean hasStencilComponent(int format) {
        return format == VK_FORMAT_D32_SFLOAT_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT;
    }

    /** rc8: where free() sends an image (the deferred, epoch-gated free); replaceable for the offline registry test. */
    public static java.util.function.Consumer<VulkanImage> FREE = img -> MemoryManager.getInstance().addToFreeable(img);

    public void free() {
        FREE.accept(this);
    }

    public void doFree() {
        if (this.traced) dev.elemore.iroh.diag.ImageTrace.destroyed(this);
        if (this.id == 0L)
            return;

        vkDestroyImageView(Vulkan.getVkDevice(), this.mainImageView, null);

        if (this.levelImageViews != null)
            Arrays.stream(this.levelImageViews).forEach(
                    // Iroh (rc5, td-dcbf58): destroy each level view (the base destroyed mainImageView again for every
                    // level: a double destroy, and every level view leaked)
                    imageView -> vkDestroyImageView(Vulkan.getVkDevice(), imageView, null));

        MemoryManager.freeImage(this.id, this.allocation); // rc5: views first, then the image

        this.id = 0L;
    }

    public int getCurrentLayout() {
        return currentLayout;
    }

    public void setCurrentLayout(int currentLayout) {
        this.currentLayout = currentLayout;
    }

    public long getId() {
        return id;
    }

    public long getAllocation() {
        return allocation;
    }

    public long getImageView() {
        return mainImageView;
    }

    public long getLevelImageView(int i) {
        return levelImageViews[i];
    }

    public long[] getLevelImageViews() {
        return levelImageViews;
    }

    public long getSampler() {
        return sampler;
    }

    public static Builder builder(int width, int height) {
        return new Builder(width, height);
    }

    public static class Builder {
        final int width;
        final int height;

        String name;
        String owner;
        int format = VulkanImage.DefaultFormat;
        int formatSize;
        byte mipLevels = 1;
        int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;

        byte samplerFlags = 0;

        boolean levelViews = false;

        public Builder(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        public Builder setFormat(int format) {
            this.format = format;
            return this;
        }

        public Builder setMipLevels(int n) {
            this.mipLevels = (byte) n;

            if (n > 1)
                this.samplerFlags |= USE_MIPMAPS_BIT | MIPMAP_LINEAR_FILTERING_BIT;

            return this;
        }

        public Builder setUsage(int usage) {
            this.usage = usage;
            return this;
        }

        public Builder addUsage(int usage) {
            this.usage |= usage;
            return this;
        }

        public Builder setLinearFiltering(boolean b) {
            this.samplerFlags |= b ? LINEAR_FILTERING_BIT : 0;
            return this;
        }

        public Builder setClamp(boolean b) {
            this.samplerFlags |= b ? CLAMP_BIT : 0;
            return this;
        }

        public Builder setSamplerReductionModeMin() {
            this.samplerFlags = REDUCTION_MIN_BIT | LINEAR_FILTERING_BIT;
            return this;
        }

        public Builder setOwner(String owner) {
            this.owner = owner;
            return this;
        }

        public Builder setLevelViews(boolean b) {
            this.levelViews = b;
            return this;
        }

        public VulkanImage createVulkanImage() {
            this.formatSize = formatSize(this.format);
            // Iroh (rc8): every image is named (leak reports and the world-unload breakdown group by name). Unnamed
            // callers get "<creator class>.<method> WxH fmt".
            if (this.name == null || this.owner == null) {
                String creator = creatorName();
                String mod = dev.elemore.iroh.diag.EmuStats.callerMod();
                if (this.name == null)
                    this.name = creator + " " + this.width + "x" + this.height + " fmt" + this.format + " by " + mod;
                if (this.owner == null)
                    this.owner = (this.name.replaceAll("[0-9]+", "#")) + (this.name.contains(" by ") ? "" : " by " + mod);
            }

            return VulkanImage.createTextureImage(this);
        }

        private static String creatorName() {
            return StackWalker.getInstance().walk(s -> s
                    .filter(f -> !f.getClassName().startsWith("net.vulkanmod.vulkan.texture.VulkanImage"))
                    .findFirst()
                    .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
                    .orElse("?"));
        }

        private static int formatSize(int format) {
            return switch (format) {
                case VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_B8G8R8A8_UNORM /* Iroh: video frames */,
                     VK_FORMAT_D32_SFLOAT, VK_FORMAT_D24_UNORM_S8_UINT,
                     VK_FORMAT_R8G8B8A8_UINT, VK_FORMAT_R8G8B8A8_SINT -> 4;
                case VK_FORMAT_R8_UNORM -> 1;

                default -> throw new IllegalArgumentException(String.format("Unxepcted format: %s", format));
//                default -> 0;
            };
        }
    }
}
