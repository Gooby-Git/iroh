package dev.elemore.iroh.shaderpack.runtime;

import dev.elemore.iroh.memory.MemoryGuard;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The pack path's backend on Iroh's own device (P3-4): the same {@link VkPackBackend} draw code the lavapipe replay
 * runs, recording into Iroh's frame command buffer (outside the main render pass: the caller wraps a pack frame in
 * {@link VulkanImage#endPassRunResume}).
 * <ul>
 *   <li>2D images: {@link VulkanImage} (named {@code pack:<target>}, owner {@code pack}, counted by MemoryManager and
 *       visible to {@code -Diroh.imageTrace=pack:}), freed through {@link VulkanImage#FREE} (deferred, epoch-gated).
 *       3D images (custom image.* volumes): VMA directly, destroyed through a frame op.</li>
 *   <li>Every pack image is NON-essential: the "pack" share (MemoryShares, 35% of Iroh's cap) and
 *       {@link MemoryGuard#allowOptional} first (INT-4); a refusal throws
 *       {@link Refused}, which the lifecycle turns into "pack path off for the session" (raster continues).</li>
 *   <li>Views, framebuffers, pipelines, samplers: destroyed by {@code MemoryManager.addFrameOp}, i.e. when the current
 *       frame slot comes round again (its fence has passed: no in-flight frame can use them).</li>
 *   <li>Per-draw uniform buffers and upload staging: host-visible VMA buffers freed by a frame op of the frame that
 *       used them. The frame command buffer never references the shared per-frame staging buffer (R-3 rule).</li>
 *   <li>One descriptor pool per frame slot, reset at {@link #beginFrame} (the slot's previous frame has completed).</li>
 *   <li>Orientation (RQ-16): pack targets are GL-row-order offscreen targets; fullscreen passes use a positive-height
 *       viewport from the origin and no culling; the pack output is copied into the scene target, which RQ-16's
 *       flipped blit presents.</li>
 * </ul>
 * UNVERIFIED until the game runs it (written 2026-11-22 without a launch).
 */
public final class IrohVkBackend extends VkPackBackend {

    public static final class Refused extends RuntimeException {
        public Refused(String m) {
            super(m);
        }
    }

    private final long[] pools;
    /** P3-11: extra pools per frame slot (in use this frame / spare after the reset) */
    private final java.util.List<java.util.List<Long>> usedPools = new java.util.ArrayList<>(), sparePools = new java.util.ArrayList<>();

    // per-pass GPU timestamps (G1 item 4): one query pool, MAXQ per frame slot, read when the slot comes round again
    private static final int MAXQ = 128;
    private long queryPool;
    private double periodNs;
    private final java.util.List<java.util.List<String>> slotNames = new java.util.ArrayList<>();
    private int slot = -1;
    public final PackGpuTimes times = new PackGpuTimes();
    private long lastRoll = System.nanoTime();

    public IrohVkBackend() {
        pools = new long[Renderer.getFramesNum()];
        initQuad();
        try (MemoryStack s = stackPush()) {
            periodNs = DeviceManager.deviceProperties.limits().timestampPeriod();
            LongBuffer p = s.mallocLong(1);
            if (periodNs > 0 && vkCreateQueryPool(device(), VkQueryPoolCreateInfo.calloc(s).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(MAXQ * pools.length), null, p) == VK_SUCCESS)
                queryPool = p.get(0);
        }
        for (int i = 0; i < pools.length; i++) {
            slotNames.add(new java.util.ArrayList<>());
            usedPools.add(new java.util.ArrayList<>());
            sparePools.add(new java.util.ArrayList<>());
        }
    }

    private void readTimes(int f) {
        java.util.List<String> names = slotNames.get(f);
        if (queryPool == 0 || names.isEmpty())
            return;
        int n = names.size() * 2;
        try (MemoryStack s = stackPush()) {
            LongBuffer res = s.mallocLong(n * 2);
            int r = vkGetQueryPoolResults(device(), queryPool, f * MAXQ, n, res, 16,
                    VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
            if (r == VK_SUCCESS || r == VK_NOT_READY) {
                long[] t = new long[n];
                boolean[] ok = new boolean[n];
                for (int i = 0; i < n; i++) {
                    t[i] = res.get(2 * i);
                    ok[i] = res.get(2 * i + 1) != 0;
                }
                times.addFrame(names, t, ok, periodNs);
            }
        }
        names.clear();
        long now = System.nanoTime();
        if (now - lastRoll > 2_000_000_000L) {
            lastRoll = now;
            dev.elemore.iroh.debug.DebugSwitches.publish(PackSwitches.GPU_TIMES, times.roll());
        }
    }

    @Override
    public void timeBegin(String pass) {
        java.util.List<String> names = slot >= 0 ? slotNames.get(slot) : null;
        if (queryPool == 0 || names == null || names.size() * 2 + 2 > MAXQ)
            return;
        vkCmdWriteTimestamp(cmd(), VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, slot * MAXQ + names.size() * 2);
        names.add(pass);
    }

    @Override
    public void timeEnd(String pass) {
        java.util.List<String> names = slot >= 0 ? slotNames.get(slot) : null;
        if (queryPool == 0 || names == null || names.isEmpty() || !names.get(names.size() - 1).equals(pass))
            return;
        vkCmdWriteTimestamp(cmd(), VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, slot * MAXQ + names.size() * 2 - 1);
    }

    @Override
    public String name() {
        return "iroh";
    }

    @Override
    protected VkDevice device() {
        return DeviceManager.vkDevice;
    }

    @Override
    protected VkPhysicalDevice phys() {
        return DeviceManager.physicalDevice;
    }

    @Override
    protected long pipelineCache() {
        return VK_NULL_HANDLE; // Pipeline.PIPELINE_CACHE is package-private to the base; pack pipelines are built once per load
    }

    @Override
    protected VkCommandBuffer cmd() {
        return Renderer.getCommandBuffer();
    }

    @Override
    protected long descriptorPool() {
        int f = Renderer.getCurrentFrame();
        if (pools[f] == 0)
            pools[f] = HeadlessVkBackend.createDescriptorPool(device());
        return pools[f];
    }

    @Override
    protected boolean descriptorPoolExhausted() {
        int f = Renderer.getCurrentFrame();
        if (pools[f] == 0)
            return false;
        java.util.List<Long> spare = sparePools.get(f);
        if (usedPools.get(f).size() + spare.size() >= 64)
            return false; // bound: 64 extra pools per slot (UNVERIFIED: check in G3b, iroh.pack.world report)
        usedPools.get(f).add(pools[f]);
        pools[f] = spare.isEmpty() ? HeadlessVkBackend.createDescriptorPool(device()) : spare.remove(spare.size() - 1);
        return true;
    }

    @Override
    public void beginFrame() {
        int f = Renderer.getCurrentFrame();
        if (pools[f] != 0)
            vkResetDescriptorPool(device(), pools[f], 0);
        for (long up : usedPools.get(f)) { // the slot's frame completed: every extra pool is free again
            vkResetDescriptorPool(device(), up, 0);
            sparePools.get(f).add(up);
        }
        usedPools.get(f).clear();
        // the slot's previous frame has completed (Renderer waited its fence): its timestamps are ready
        readTimes(f);
        resetUboRing(f);
        if (queryPool != 0)
            vkCmdResetQueryPool(cmd(), queryPool, f * MAXQ, MAXQ); // outside any render pass (endPassRunResume)
        slot = f;
    }

    @Override
    public void endFrame() {
        // recorded into Iroh's frame command buffer; Iroh submits it
    }

    @Override
    public float[] readback(int image) {
        throw new UnsupportedOperationException("synchronous readback is offline only; use readbackAsync");
    }

    /** records a copy of mip 0 into a host buffer; {@code done} gets the decoded texels after the frame has executed */
    public void readbackAsync(int image, Consumer<float[]> done) {
        Img im = img(image);
        long texels = (long) im.d.width() * im.d.height() * im.d.depth();
        HostBuf st = hostBuffer(Math.max(16, texels * TexelCodec.bytes(im.d.vkFormat())), VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        try (MemoryStack s = stackPush()) {
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.imageSubresource().aspectMask(im.aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.imageExtent().width(im.d.width()).height(im.d.height()).depth(im.d.depth());
            vkCmdCopyImageToBuffer(cmd(), im.image, VK_IMAGE_LAYOUT_GENERAL, st.buffer(), r);
        }
        String fmt = im.d.vkFormat();
        MemoryManager.getInstance().addFrameOp(() -> {
            float[] px = TexelCodec.decode(fmt, st.mapped(), (int) texels);
            freeHostBuffer(st);
            done.accept(px);
        });
    }

    // ------------------------------------------------------------------------------------------------ images

    /** INT-4: pack images are owner "pack" in the one share table (35% of Iroh's cap + the guard's total check) */
    static final dev.elemore.iroh.memory.MemoryShares.Account PACK_MEMORY = dev.elemore.iroh.memory.MemoryShares.Account.game(
            dev.elemore.iroh.memory.MemoryShares.Owner.PACK);
    private static final java.util.Map<Long, Long> PACK_BYTES = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    protected Object[] allocImage(ImageDesc d, int fmt, int usage, boolean three) {
        long bytes = d.bytes();
        if (!PACK_MEMORY.reserve(bytes))
            throw new Refused("pack image " + d.name() + " refused: " + PACK_MEMORY.lastRefusal());
        try {
            Object[] a = allocImageReserved(d, fmt, usage, three);
            PACK_BYTES.put((Long) a[0], bytes);
            return a;
        } catch (RuntimeException | Error e) {
            PACK_MEMORY.release(bytes);
            throw e;
        }
    }

    private Object[] allocImageReserved(ImageDesc d, int fmt, int usage, boolean three) {
        if (!three) {
            VulkanImage vi = VulkanImage.builder(d.width(), d.height()).setName(d.name()).setOwner("pack").setFormat(fmt)
                    .setUsage(usage).setMipLevels(d.mips()).setLinearFiltering(true).setClamp(true).createVulkanImage();
            vi.setCurrentLayout(VK_IMAGE_LAYOUT_GENERAL); // createImage records UNDEFINED -> GENERAL next
            if (dev.elemore.iroh.diag.ImageTrace.ON)
                dev.elemore.iroh.diag.ImageTrace.createdImage(vi); // -Diroh.imageTrace=pack: follows pack targets
            // depth: VulkanImage's own view of a D24S8 image has DEPTH|STENCIL aspects, which cannot be sampled; the base
            // class makes depth-only views instead (destroyed by releaseImage)
            boolean depth = (vi.aspect & VK_IMAGE_ASPECT_DEPTH_BIT) != 0;
            return new Object[]{vi.getId(), vi, depth ? null : vi.getImageView(), null};
        }
        MemoryGuard.beforeAllocation("pack 3D image " + d.name());
        try (MemoryStack s = stackPush()) {
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType$Default().imageType(VK_IMAGE_TYPE_3D).format(fmt)
                    .mipLevels(d.mips()).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().width(d.width()).height(d.height()).depth(d.depth());
            VmaAllocationCreateInfo ai = VmaAllocationCreateInfo.calloc(s).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer pImage = s.mallocLong(1);
            PointerBuffer pAlloc = s.mallocPointer(1);
            int r = MemoryGuard.check(vmaCreateImage(Vulkan.getAllocator(), ci, ai, pImage, pAlloc, null), "pack 3D image " + d.name());
            if (r != VK_SUCCESS)
                throw new Refused("vmaCreateImage " + d.name() + ": " + r);
            return new Object[]{pImage.get(0), pAlloc.get(0)};
        }
    }

    @Override
    protected void releaseImage(long image, Object alloc, long[] views) {
        Long held = PACK_BYTES.remove(image); // INT-4: back to the "pack" share (the free itself is deferred a frame)
        if (held != null) PACK_MEMORY.release(held);
        if (alloc instanceof VulkanImage vi) {
            // views are the VulkanImage's own (main view): VulkanImage.doFree destroys them
            for (long v : views)
                if (v != vi.getImageView())
                    releaseLater(() -> vkDestroyImageView(device(), v, null));
            VulkanImage.FREE.accept(vi);
            return;
        }
        long a = (Long) alloc;
        releaseLater(() -> {
            for (long v : views)
                vkDestroyImageView(device(), v, null);
            vmaDestroyImage(Vulkan.getAllocator(), image, a);
        });
    }

    @Override
    protected void releaseLater(Runnable r) {
        MemoryManager.getInstance().addFrameOp(r);
    }

    @Override
    protected HostBuf hostBuffer(long size, int usage) {
        try (MemoryStack s = stackPush()) {
            VkBufferCreateInfo bi = VkBufferCreateInfo.calloc(s).sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo ai = VmaAllocationCreateInfo.calloc(s).usage(VMA_MEMORY_USAGE_AUTO)
                    .flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer pBuf = s.mallocLong(1);
            PointerBuffer pAlloc = s.mallocPointer(1);
            VmaAllocationInfo info = VmaAllocationInfo.calloc(s);
            int r = MemoryGuard.check(vmaCreateBuffer(Vulkan.getAllocator(), bi, ai, pBuf, pAlloc, info), "pack host buffer");
            if (r != VK_SUCCESS)
                throw new Refused("vmaCreateBuffer: " + r);
            return new HostBuf(pBuf.get(0), pAlloc.get(0), size, MemoryUtil.memByteBuffer(info.pMappedData(), (int) size));
        }
    }

    @Override
    protected void freeHostBuffer(HostBuf b) {
        vmaDestroyBuffer(Vulkan.getAllocator(), b.buffer(), (Long) b.alloc());
    }

    @Override
    protected void freeHostBufferAfterFrame(HostBuf b) {
        releaseLater(() -> freeHostBuffer(b));
    }

    /** imports an Iroh VulkanImage (scene colour / depth) as a blit source / destination; its layout is tracked by Iroh */
    @Override
    public int importImage(Object external, String name) {
        VulkanImage vi = (VulkanImage) external;
        String fmt = vkFormatName(vi.format);
        ImageDesc d = new ImageDesc(name, fmt, vi.width, vi.height, 1, 1, java.util.Set.of("TRANSFER_SRC", "TRANSFER_DST"));
        return importRaw(d, vi.getId(), vi.getImageView(), vi.format, new ImportedLayout() {
            @Override
            public int get() {
                return vi.getCurrentLayout();
            }

            @Override
            public void set(int layout) {
                vi.setCurrentLayout(layout);
            }

            @Override
            public void toSampled() {
                // PACK-FULL: Iroh's own rule (upload buffer when the pending change is there, else the frame buffer); the
                // caller is outside every render pass, so the frame-buffer branch records between passes. Queued in-frame
                // uploads are recorded first (review #3: else the pack samples the texels from before them)
                if (vi.hasInFramePending())
                    VulkanImage.flushInFrame();
                vi.readOnlyLayout();
            }

            @Override
            public boolean pending() {
                return vi.hasInFramePending();
            }

            @Override
            public void noteSampled() {
                vi.markFrameUse(); // a later upload this frame goes to the frame buffer, after this sample (as for Iroh's own binds)
            }
        });
    }

    /** depth images take the scene depth format so the post path can copy the raster depth in (no format conversion for depth) */
    @Override
    public int createImage(ImageDesc d) {
        if (d.vkFormat().startsWith("D") && Vulkan.getDefaultDepthFormat() != VK_FORMAT_D32_SFLOAT) {
            String sceneFmt = vkFormatName(Vulkan.getDefaultDepthFormat());
            ImageDesc dd = new ImageDesc(d.name(), sceneFmt, d.width(), d.height(), d.depth(), d.mips(), d.usage());
            return createImage(dd, Vulkan.getDefaultDepthFormat());
        }
        return super.createImage(d);
    }

    static String vkFormatName(int format) {
        for (java.lang.reflect.Field f : VK10.class.getFields())
            if (f.getName().startsWith("VK_FORMAT_") && f.getType() == int.class)
                try {
                    if (f.getInt(null) == format)
                        return f.getName().substring("VK_FORMAT_".length());
                } catch (IllegalAccessException ignored) {
                }
        return "UNDEFINED";
    }

    @Override
    protected boolean vertexStoresEnabled() {
        return PackDeviceFeatures.vertexStores;
    }

    @Override
    protected boolean fragmentStoresEnabled() {
        return PackDeviceFeatures.fragmentStores;
    }

    @Override
    protected boolean independentBlendEnabled() {
        return PackDeviceFeatures.independentBlend;
    }

    @Override
    public void close() {
        destroyShared();
        if (queryPool != 0) {
            long q = queryPool;
            queryPool = 0;
            releaseLater(() -> vkDestroyQueryPool(device(), q, null));
        }
        for (int i = 0; i < pools.length; i++) {
            long p = pools[i];
            if (p != 0)
                releaseLater(() -> vkDestroyDescriptorPool(device(), p, null));
            pools[i] = 0;
            for (java.util.List<Long> l : java.util.List.of(usedPools.get(i), sparePools.get(i))) {
                for (long x : l)
                    releaseLater(() -> vkDestroyDescriptorPool(device(), x, null));
                l.clear();
            }
        }
    }
}
