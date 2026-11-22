package net.vulkanmod.vulkan.memory;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.buffer.AreaBuffer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.queue.Queue;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.Pair;
import net.vulkanmod.vulkan.util.VkResult;
import org.apache.commons.lang3.Validate;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaBudget;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;

import java.nio.LongBuffer;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class MemoryManager {
    private static final boolean DEBUG = false;
    public static final long BYTES_IN_MB = 1024 * 1024;

    private static MemoryManager INSTANCE;
    private static final long ALLOCATOR = Vulkan.getAllocator();

    private static final Long2ReferenceOpenHashMap<Buffer> buffers = new Long2ReferenceOpenHashMap<>();
    private static final Long2ReferenceOpenHashMap<VulkanImage> images = new Long2ReferenceOpenHashMap<>();

    static int Frames;

    private static long deviceMemory = 0;
    private static long nativeMemory = 0;

    private int currentFrame = 0;

    private final ObjectArrayList<Buffer.BufferInfo>[] freeableBuffers = new ObjectArrayList[Frames];
    private final ObjectArrayList<VulkanImage>[] freeableImages = new ObjectArrayList[Frames];

    private final ObjectArrayList<Runnable>[] frameOps = new ObjectArrayList[Frames];
    private final ObjectArrayList<Pair<AreaBuffer, Integer>>[] segmentsToFree = new ObjectArrayList[Frames];

    //debug
    private ObjectArrayList<StackTraceElement[]>[] stackTraces;

    public static MemoryManager getInstance() {
        return INSTANCE;
    }

    public static void createInstance(int frames) {
        Frames = frames;

        INSTANCE = new MemoryManager();
    }

    MemoryManager() {
        for (int i = 0; i < Frames; ++i) {
            this.freeableBuffers[i] = new ObjectArrayList<>();
            this.freeableImages[i] = new ObjectArrayList<>();

            this.frameOps[i] = new ObjectArrayList<>();
            this.segmentsToFree[i] = new ObjectArrayList<>();
        }

        if (DEBUG) {
            this.stackTraces = new ObjectArrayList[Frames];
            for (int i = 0; i < Frames; ++i) {
                this.stackTraces[i] = new ObjectArrayList<>();
            }
        }
    }

    public synchronized void initFrame(int frame) {
        this.setCurrentFrame(frame);
        this.freeBuffers(frame);
        this.freeImages(frame);
        this.freeEpochGated();
        this.doFrameOps(frame);
        this.freeSegments(frame);
    }

    public void setCurrentFrame(int frame) {
        Validate.isTrue(frame < Frames, "Out of bounds frame index");
        this.currentFrame = frame;
    }

    public void freeAllBuffers() {
        // callers wait for the device first (shutdown, frame-count change): everything is done
        var ring = net.vulkanmod.vulkan.memory.buffer.StagingBuffer.ring();
        if (ring != null) ring.completeAll();
        synchronized (this) {
            freeEpochGated();
        }
        for (int frame = 0; frame < Frames; ++frame) {
            this.freeBuffers(frame);
            this.freeImages(frame);
            this.doFrameOps(frame);
        }

//        buffers.values().forEach(buffer -> freeBuffer(buffer.getId(), buffer.getAllocation()));
//        images.values().forEach(image -> image.doFree(this));
    }

    /**
     * Iroh (rc5, td-dcbf58): at shutdown, after the device is idle and the deferred lists are freed, destroy every buffer
     * and image still registered: objects mods never close (vanilla VertexBuffers cached by mods, GL textures never
     * deleted; GL drops them with the context, Vulkan reports them as leaked at vkDestroyDevice). Logs the count by owner.
     */
    /** rc5: "buffers N (device MB), images M" for the world-unload log line (growth across rejoins = a real leak). */
    public static String liveSummary() {
        int pendingB = 0, pendingI = 0;
        if (INSTANCE != null)
            for (int i = 0; i < Frames; i++) {
                pendingB += INSTANCE.freeableBuffers[i].size();
                pendingI += INSTANCE.freeableImages[i].size();
            }
        if (INSTANCE != null) {
            pendingB += INSTANCE.epochBuffers.size();
            pendingI += INSTANCE.epochImages.size();
            }
        // rc6: per-owner breakdown (buffer class / image name with digits folded), top 8 each
        java.util.Map<String, Integer> b = new java.util.HashMap<>(), im = new java.util.HashMap<>();
        for (Buffer x : buffers.values()) b.merge(x.getClass().getSimpleName(), 1, Integer::sum);
        for (VulkanImage x : images.values()) im.merge(x.name == null ? "?" : x.name.replaceAll("[0-9]+", "#"), 1, Integer::sum);
        return "buffers " + buffers.size() + " (" + pendingB + " already scheduled for free), images " + images.size() + " (" + pendingI
                + " scheduled), device " + (deviceMemory >> 20) + " MB, host " + (nativeMemory >> 20) + " MB; buffers by class " + top(b)
                + "; images by name " + top(im);
    }

    private static String top(java.util.Map<String, Integer> m) {
        return m.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(8)
                .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    public void freeLeftovers() {
        java.util.Map<String, Integer> owners = new java.util.TreeMap<>();
        int nb = 0, ni = 0;
        for (Buffer b : new java.util.ArrayList<>(buffers.values())) {
            owners.merge("buffer " + b.getClass().getSimpleName(), 1, Integer::sum);
            vmaDestroyBuffer(ALLOCATOR, b.getId(), b.getAllocation());
            nb++;
        }
        buffers.clear();
        for (VulkanImage img : new java.util.ArrayList<>(images.values())) {
            String n = img.name == null ? "?" : img.name.replaceAll("[0-9]+", "#");
            owners.merge("image " + n, 1, Integer::sum);
            img.doFree();
            ni++;
        }
        images.clear();
        if (nb + ni > 0)
            net.vulkanmod.Initializer.LOGGER.info("Iroh: freed {} buffers and {} images still alive at shutdown (never closed by their owners): {}",
                    nb, ni, owners);
    }

    public void createBuffer(long size, int usage, int properties, LongBuffer pBuffer, PointerBuffer pBufferMemory) {
        try (MemoryStack stack = stackPush()) {

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack);
            bufferInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            bufferInfo.size(size);
            bufferInfo.usage(usage);

            // Buffers are written on the transfer queue and read on the graphics queue.
            // Without ownership transfers, that requires CONCURRENT sharing when the families differ.
            Queue.QueueFamilyIndices queueFamilies = Queue.getQueueFamilies();
            if (queueFamilies.transferFamily != queueFamilies.graphicsFamily) {
                bufferInfo.sharingMode(VK_SHARING_MODE_CONCURRENT);
                bufferInfo.pQueueFamilyIndices(stack.ints(queueFamilies.graphicsFamily, queueFamilies.transferFamily));
            }

            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack);
            allocationInfo.requiredFlags(properties);

            dev.elemore.iroh.memory.MemoryGuard.beforeAllocation("buffer " + size + " B"); // rc8: never ask the driver again after an OOM
            int result = dev.elemore.iroh.memory.MemoryGuard.check(vmaCreateBuffer(ALLOCATOR, bufferInfo, allocationInfo, pBuffer, pBufferMemory, null), "vmaCreateBuffer " + size + " B");
            if (result != VK_SUCCESS) {
                Initializer.LOGGER.info(String.format("Failed to create buffer with size: %.3f MB", ((float) size / BYTES_IN_MB)));
                Initializer.LOGGER.info(String.format("Tracked Device Memory used: %d/%d MB", getAllocatedDeviceMemoryMB(), getDeviceMemoryMB()));
                Initializer.LOGGER.info(getHeapStats());

                throw new RuntimeException("Failed to create buffer: %s".formatted(VkResult.decode(result)));
            }

        }
    }

    public synchronized void createBuffer(Buffer buffer, long size, int usage, int properties) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.pointers(VK_NULL_HANDLE);

            this.createBuffer(size, usage, properties, pBuffer, pAllocation);

            buffer.setId(pBuffer.get(0));
            buffer.setAllocation(pAllocation.get(0));
            buffer.setBufferSize(size);

            if ((properties & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                deviceMemory += size;
            }
            else {
                nativeMemory += size;
            }

            buffers.putIfAbsent(buffer.getId(), buffer);

            // Iroh (rc3, td-154ba3): with validation on, name every buffer "<type> <size> <creator>" so leak reports at
            // vkDestroyDevice say what each leaked VkBuffer is.
            if (net.vulkanmod.vulkan.Vulkan.ENABLE_VALIDATION_LAYERS)
                net.vulkanmod.vulkan.Vulkan.setDebugLabel(stack, org.lwjgl.vulkan.VK10.VK_OBJECT_TYPE_BUFFER, buffer.getId(),
                        buffer.getClass().getSimpleName() + " " + size + " " + creator());
        }
    }

    /** The first stack frame outside Iroh's memory classes (who asked for the buffer). Validation runs only. */
    private static String creator() {
        return StackWalker.getInstance().walk(s -> s
                .filter(f -> !f.getClassName().startsWith("net.vulkanmod.vulkan.memory."))
                .findFirst().map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
                .orElse("?"));
    }

    public void createImage(int width, int height, int mipLevels, int format, int tiling, int usage,
                                         int memProperties,
                                         LongBuffer pTextureImage, PointerBuffer pTextureImageMemory) {

        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack);
            imageInfo.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            imageInfo.imageType(VK_IMAGE_TYPE_2D);
            imageInfo.extent().width(width);
            imageInfo.extent().height(height);
            imageInfo.extent().depth(1);
            imageInfo.mipLevels(mipLevels);
            imageInfo.arrayLayers(1);
            imageInfo.format(format);
            imageInfo.tiling(tiling);
            imageInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.usage(usage);
            imageInfo.samples(VK_SAMPLE_COUNT_1_BIT);
//            imageInfo.sharingMode(VK_SHARING_MODE_CONCURRENT);
            imageInfo.pQueueFamilyIndices(
                    stack.ints(Queue.getQueueFamilies().graphicsFamily, Queue.getQueueFamilies().computeFamily));

            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack);
            allocationInfo.requiredFlags(memProperties);

            dev.elemore.iroh.memory.MemoryGuard.beforeAllocation("image " + width + "x" + height);
            int result = dev.elemore.iroh.memory.MemoryGuard.check(vmaCreateImage(ALLOCATOR, imageInfo, allocationInfo, pTextureImage, pTextureImageMemory, null), "vmaCreateImage " + width + "x" + height);
            if (result != VK_SUCCESS) {
                Initializer.LOGGER.info(String.format("Failed to create image with size: %dx%d", width, height));

                throw new RuntimeException("Failed to create image: %s".formatted(VkResult.decode(result)));
            }

        }
    }

    public static void addImage(VulkanImage image) {
        images.putIfAbsent(image.getId(), image);

        deviceMemory += image.size;
        ownerCount(image.owner, 1);
    }

    public static void MapAndCopy(long allocation, Consumer<PointerBuffer> consumer) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer data = stack.mallocPointer(1);

            vmaMapMemory(ALLOCATOR, allocation, data);
            consumer.accept(data);
            vmaUnmapMemory(ALLOCATOR, allocation);
        }
    }

    public PointerBuffer Map(long allocation) {
        PointerBuffer data = MemoryUtil.memAllocPointer(1);

        vmaMapMemory(ALLOCATOR, allocation, data);

        return data;
    }

    public static void freeBuffer(long buffer, long allocation) {
        vmaDestroyBuffer(ALLOCATOR, buffer, allocation);

        buffers.remove(buffer);
    }

    private static void freeBuffer(Buffer.BufferInfo bufferInfo) {
        vmaDestroyBuffer(ALLOCATOR, bufferInfo.id(), bufferInfo.allocation());

        if (bufferInfo.type() == MemoryType.Type.DEVICE_LOCAL) {
            deviceMemory -= bufferInfo.bufferSize();
        }
        else {
            nativeMemory -= bufferInfo.bufferSize();
        }

        buffers.remove(bufferInfo.id());
    }

    public static void freeImage(long imageId, long allocation) {
        vmaDestroyImage(ALLOCATOR, imageId, allocation);

        VulkanImage image = images.remove(imageId);
        deviceMemory -= image.size;
        ownerCount(image.owner, -1);
    }

    // rc9: live images per owner (creator + calling mod), for the growth monitor and the unload breakdown
    private static final java.util.HashMap<String, Integer> OWNER_COUNTS = new java.util.HashMap<>();

    private static synchronized void ownerCount(String owner, int d) {
        OWNER_COUNTS.merge(owner == null ? "?" : owner, d, Integer::sum);
    }

    public static synchronized java.util.Map<String, Integer> ownerCounts() {
        return new java.util.HashMap<>(OWNER_COUNTS);
    }

    // perf/uploads: deferred frees gated by the upload EPOCH they were scheduled in. Upload command buffers are no longer
    // waited at every frame end, so "the frame slot came round again" no longer implies "every copy reading this object
    // finished" (an upload recorded between two frames belongs to the next epoch). An object scheduled while epoch E is
    // open is freed once E completed (its upload command buffers and the frame recorded during E have finished).
    private final ObjectArrayList<Buffer.BufferInfo> epochBuffers = new ObjectArrayList<>();
    private final it.unimi.dsi.fastutil.longs.LongArrayList epochBufferTags = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private final ObjectArrayList<VulkanImage> epochImages = new ObjectArrayList<>();
    private final it.unimi.dsi.fastutil.longs.LongArrayList epochImageTags = new it.unimi.dsi.fastutil.longs.LongArrayList();

    // api 4 (card V-M1): raw Vulkan objects a mod's GPU resources own (pipelines, framebuffers), freed under the same rule
    private final ObjectArrayList<Runnable> epochOps = new ObjectArrayList<>();
    private final it.unimi.dsi.fastutil.longs.LongArrayList epochOpTags = new it.unimi.dsi.fastutil.longs.LongArrayList();

    /** Runs {@code r} once every frame and upload recorded while the current epoch is open has finished. */
    public synchronized void addEpochOp(Runnable r) {
        var ring = net.vulkanmod.vulkan.memory.buffer.StagingBuffer.ring();
        if (ring == null) {
            frameOps[currentFrame].add(r);
            return;
        }
        epochOps.add(r);
        epochOpTags.add(ring.epoch());
    }

    private void freeEpochGated() {
        var ring = net.vulkanmod.vulkan.memory.buffer.StagingBuffer.ring();
        long done = ring == null ? Long.MAX_VALUE : ring.completedEpoch();
        int wo = 0;
        for (int i = 0; i < epochOps.size(); i++) {
            if (epochOpTags.getLong(i) <= done) epochOps.get(i).run();
            else { epochOps.set(wo, epochOps.get(i)); epochOpTags.set(wo, epochOpTags.getLong(i)); wo++; }
        }
        epochOps.size(wo);
        epochOpTags.size(wo);
        int w = 0;
        for (int i = 0; i < epochBuffers.size(); i++) {
            if (epochBufferTags.getLong(i) <= done) freeBuffer(epochBuffers.get(i));
            else { epochBuffers.set(w, epochBuffers.get(i)); epochBufferTags.set(w, epochBufferTags.getLong(i)); w++; }
        }
        epochBuffers.size(w);
        epochBufferTags.size(w);
        w = 0;
        for (int i = 0; i < epochImages.size(); i++) {
            if (epochImageTags.getLong(i) <= done) epochImages.get(i).doFree();
            else { epochImages.set(w, epochImages.get(i)); epochImageTags.set(w, epochImageTags.getLong(i)); w++; }
        }
        epochImages.size(w);
        epochImageTags.size(w);
    }

    public synchronized void addToFreeable(Buffer buffer) {
        Buffer.BufferInfo bufferInfo = buffer.getBufferInfo();

        checkBuffer(bufferInfo);

        var ring = net.vulkanmod.vulkan.memory.buffer.StagingBuffer.ring();
        if (ring != null) {
            epochBuffers.add(bufferInfo);
            epochBufferTags.add(ring.epoch());
            return;
        }
        freeableBuffers[currentFrame].add(bufferInfo);

        if (DEBUG)
            stackTraces[currentFrame].add(new Throwable().getStackTrace());
    }

    /** rc13: images waiting in the epoch-gated free list (tests, memStats). */
    public synchronized int pendingEpochImages() {
        return epochImages.size();
    }

    public synchronized void addToFreeable(VulkanImage image) {
        var ring = net.vulkanmod.vulkan.memory.buffer.StagingBuffer.ring();
        if (ring != null) {
            epochImages.add(image);
            epochImageTags.add(ring.epoch());
            if (image.traced)
                dev.elemore.iroh.diag.ImageTrace.queued(image, "deferred free at epoch " + ring.epoch() + " (completed " + ring.completedEpoch() + ")");
            return;
        }
        freeableImages[currentFrame].add(image);
        if (image.traced) dev.elemore.iroh.diag.ImageTrace.queued(image, "frame-slot free (slot " + currentFrame + ", no ring)");
    }

    public synchronized void addFrameOp(Runnable runnable) {
        this.frameOps[currentFrame].add(runnable);
    }

    public void doFrameOps(int frame) {
        for (Runnable runnable : this.frameOps[frame]) {
            runnable.run();
        }

        this.frameOps[frame].clear();
    }

    private void freeBuffers(int frame) {
        List<Buffer.BufferInfo> bufferList = freeableBuffers[frame];
        for (Buffer.BufferInfo bufferInfo : bufferList) {

            freeBuffer(bufferInfo);
        }

        bufferList.clear();

        if (DEBUG)
            stackTraces[frame].clear();
    }

    private void freeImages(int frame) {
        List<VulkanImage> bufferList = freeableImages[frame];
        for (VulkanImage image : bufferList) {

            image.doFree();
        }

        bufferList.clear();
    }

    private void checkBuffer(Buffer.BufferInfo bufferInfo) {
        if (buffers.get(bufferInfo.id()) == null) {
            throw new RuntimeException("trying to free not present buffer");
        }
    }

    private void freeSegments(int frame) {
        var list = this.segmentsToFree[frame];
        for (var pair : list) {
            pair.first.setSegmentFree(pair.second);
        }

        list.clear();
    }

    public void addToFreeSegment(AreaBuffer areaBuffer, int offset) {
        this.segmentsToFree[this.currentFrame].add(new Pair<>(areaBuffer, offset));
    }

    public int getNativeMemoryMB() {
        return bytesInMb(nativeMemory);
    }

    public int getAllocatedDeviceMemoryMB() {
        return bytesInMb(deviceMemory);
    }

    public int getDeviceMemoryMB() {
        return bytesInMb(MemoryTypes.GPU_MEM.vkMemoryHeap.size());
    }

    int bytesInMb(long bytes) {
        return (int) (bytes / BYTES_IN_MB);
    }

    public String getHeapStats() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaBudget.Buffer vmaBudgets = VmaBudget.calloc(DeviceManager.memoryProperties.memoryHeapCount(), stack);

            vmaGetHeapBudgets(ALLOCATOR, vmaBudgets);

            VmaBudget vmaBudget = vmaBudgets.get(MemoryTypes.GPU_MEM.vkMemoryType.heapIndex());
            long usage = vmaBudget.usage();
            long budget = vmaBudget.budget();

            return String.format("Device Memory Heap Usage: %d/%dMB", bytesInMb(usage), bytesInMb(budget));
        }
    }
}
