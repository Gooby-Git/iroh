package dev.elemore.iroh.rt.vk;

import dev.elemore.iroh.gpu.VkGpuDevice;
import dev.elemore.iroh.gpu.VkGpuDevice.VkBuf;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Acceleration structure recording for Iroh's RT core (card P5-0). Minecraft-free: runs on the headless lavapipe device
 * (rtReplay) and later on the game device. One BLAS per 16^3 section built straight from the RASTER vertex buffer:
 * Iroh's 16-byte compressed terrain vertex starts with three int16 positions (x2048, -4 block offset), which the
 * build reads as {@code VK_FORMAT_R16G16B16A16_SNORM} (w = the light short, ignored by the build) and undoes with the
 * geometry's 3x4 transform (scale 32767/2048, offset +4 + section offset inside the buffer's area). No decode pass, no
 * second copy of the positions. The same holds for the 36-byte wide pack format (first 16 bytes identical).
 * <p>
 * Geometries keep a fixed order per BLAS so {@code rayQueryGetIntersectionGeometryIndexEXT} is a stable bucket id:
 * 0 = opaque (OPAQUE flag: no candidate processing), 1 = alpha-tested (non-opaque: the shader alpha-tests candidates).
 * Translucent geometry (water, stained glass) goes into a separate BLAS whose instance carries mask bit
 * {@link #MASK_TRANSLUCENT}, so shadow rays skip it by cull mask instead of by candidate work.
 */
public final class RtAccel {
    /** Instance masks. Entities (P5-3) carry SOLID|ENTITY (every solid ray sees them; ENTITY lets a pass exclude them); the
     *  player's own body in first person carries SELF only: shadow and reflection rays include SELF, GI rays do not, and
     *  primary visibility is raster (the first-person hand is raster-only, never in the TLAS). See docs/P5-DESIGN.md 14. */
    public static final int MASK_SOLID = 0x01, MASK_TRANSLUCENT = 0x02, MASK_ENTITY = 0x04, MASK_SELF = 0x08;
    /** 16-byte terrain vertex: position = snorm * SNORM_SCALE + 4 (section-local blocks). */
    public static final float SNORM_SCALE = 32767f / 2048f, POS_OFFSET = 4f;

    public static final int STAGE_AS_BUILD = VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR;
    public static final int ACCESS_AS_READ = VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR, ACCESS_AS_WRITE = VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR;

    /** One geometry of a BLAS: a run of quads (4 vertices, 2 triangles each) inside a vertex buffer; with {@code vertices}
     *  null, {@code vertexOffset} is the absolute device address of the first vertex (P5-2: the game's raster arena). */
    public record Geometry(VkBuf vertices, long vertexOffset, int vertexStride, int quadCount, float[] transform3x4, boolean opaque, int vertexFormat) {
        /** Iroh's 16-byte terrain vertex read as R16G16B16A16_SNORM. */
        public Geometry(VkBuf vertices, long vertexOffset, int vertexStride, int quadCount, float[] transform3x4, boolean opaque) {
            this(vertices, vertexOffset, vertexStride, quadCount, transform3x4, opaque, VK_FORMAT_R16G16B16A16_SNORM);
        }
    }

    /** A built acceleration structure and its backing storage. */
    public static final class Accel {
        public final long handle, address, bytes;
        final VkBuf storage;
        public final long scratchBytes;
        /** R1-HANG: set by {@link RtAccel#destroy}; a TLAS must never name a destroyed BLAS (checked by the backend). */
        volatile boolean destroyed;

        public VkBuf storage() {
            return storage;
        }

        public boolean destroyed() {
            return destroyed;
        }

        Accel(long handle, long address, long bytes, VkBuf storage, long scratchBytes) {
            this.handle = handle;
            this.address = address;
            this.bytes = bytes;
            this.storage = storage;
            this.scratchBytes = scratchBytes;
        }
    }

    private final VkGpuDevice dev;
    private final VkDevice vk;
    private final int scratchAlign;
    private VkBuf quadIndices;
    private int quadIndexCapacity;
    private final java.util.ArrayList<VkBuf> transient_ = new java.util.ArrayList<>();

    public RtAccel(VkGpuDevice dev) {
        if (!dev.supportsDeviceAddress()) throw new IllegalStateException("device without buffer device address: no RT");
        this.dev = dev;
        this.vk = dev.vkDevice();
        try (MemoryStack s = stackPush()) {
            VkPhysicalDeviceAccelerationStructurePropertiesKHR ap = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(s).sType$Default();
            VkPhysicalDeviceProperties2 p2 = VkPhysicalDeviceProperties2.calloc(s).sType$Default().pNext(ap.address());
            vkGetPhysicalDeviceProperties2(vk.getPhysicalDevice(), p2);
            scratchAlign = Math.max(1, ap.minAccelerationStructureScratchOffsetAlignment());
        }
    }

    /** True when the device can read {@code format} as acceleration-structure vertex input. */
    public boolean vertexFormatSupported(int format) {
        try (MemoryStack s = stackPush()) {
            VkFormatProperties fp = VkFormatProperties.calloc(s);
            vkGetPhysicalDeviceFormatProperties(vk.getPhysicalDevice(), format, fp);
            return (fp.bufferFeatures() & VK_FORMAT_FEATURE_ACCELERATION_STRUCTURE_VERTEX_BUFFER_BIT_KHR) != 0;
        }
    }

    public static final int INPUT_USAGE = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT;

    /** A buffer usable as build input AND as a shader storage buffer (vertex arenas, instance lists). */
    public VkBuf inputBuffer(long bytes) {
        return dev.rawBuffer(bytes, INPUT_USAGE);
    }

    public long address(VkBuf b) {
        try (MemoryStack s = stackPush()) {
            return vkGetBufferDeviceAddress(vk, VkBufferDeviceAddressInfo.calloc(s).sType$Default().buffer(b.buffer()));
        }
    }

    /** The shared quad index buffer (0,1,2, 2,3,0 per quad), grown on demand. Same pattern as VulkanMod's raster. */
    public VkBuf quadIndices(int quads) {
        if (quads <= quadIndexCapacity) return quadIndices;
        int cap = Math.max(4096, Integer.highestOneBit(quads) << 1);
        ByteBuffer b = MemoryUtil.memAlloc(cap * 6 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int q = 0; q < cap; q++) {
            int v = q * 4;
            b.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v + 2).putInt(v + 3).putInt(v);
        }
        b.flip();
        VkBuf nb = inputBuffer(b.remaining());
        writeInFrame(nb, b);   // R1-HANG: in program order (dev.upload lands before the whole frame: UPLOAD-SYNC)
        MemoryUtil.memFree(b);
        dev.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                STAGE_AS_BUILD | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                ACCESS_AS_READ | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_INDEX_READ_BIT);
        if (quadIndices != null) {
            dev.destroyBuffer(quadIndices);
        }
        quadIndices = nb;
        quadIndexCapacity = cap;
        return nb;
    }

    /** Sizes {acceleration structure bytes, build scratch bytes} for a BLAS over these geometries. */
    public long[] blasSizes(List<Geometry> geoms, boolean compactable) {
        try (MemoryStack s = stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR info = blasInfo(s, geoms, compactable, 0);
            java.nio.IntBuffer counts = counts(s, geoms);
            VkAccelerationStructureBuildSizesInfoKHR sz = VkAccelerationStructureBuildSizesInfoKHR.calloc(s).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, counts, sz);
            return new long[]{sz.accelerationStructureSize(), sz.buildScratchSize()};
        }
    }

    private static java.nio.IntBuffer counts(MemoryStack s, List<Geometry> geoms) {
        java.nio.IntBuffer c = s.mallocInt(geoms.size());
        for (Geometry g : geoms) c.put(g.quadCount * 2);
        return c.flip();
    }

    private VkAccelerationStructureBuildGeometryInfoKHR blasInfo(MemoryStack s, List<Geometry> geoms, boolean compactable, long transformBase) {
        return blasInfo(s, geoms, compactable ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR : 0, transformBase);
    }

    private VkAccelerationStructureBuildGeometryInfoKHR blasInfo(MemoryStack s, List<Geometry> geoms, int extraFlags, long transformBase) {
        int maxQuads = 0;
        for (Geometry g : geoms) maxQuads = Math.max(maxQuads, g.quadCount);
        long idx = address(quadIndices(Math.max(1, maxQuads)));
        VkAccelerationStructureGeometryKHR.Buffer gb = VkAccelerationStructureGeometryKHR.calloc(geoms.size(), s);
        for (int i = 0; i < geoms.size(); i++) {
            Geometry g = geoms.get(i);
            VkAccelerationStructureGeometryKHR e = gb.get(i).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .flags(g.opaque ? VK_GEOMETRY_OPAQUE_BIT_KHR : VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
            VkAccelerationStructureGeometryTrianglesDataKHR t = e.geometry().triangles().sType$Default()
                    .vertexFormat(g.vertexFormat).vertexStride(g.vertexStride)
                    .maxVertex(Math.max(0, g.quadCount * 4 - 1)).indexType(VK_INDEX_TYPE_UINT32);
            // P5-2: vertices == null -> vertexOffset is an absolute device address (the game's raster arena, read in place)
            long va = g.vertices == null ? g.vertexOffset : address(g.vertices) + g.vertexOffset;
            if (g.quadCount < 0 || g.vertexStride <= 0 || (va == 0 && g.quadCount > 0))
                throw new IllegalStateException("BLAS geometry " + i + ": quads " + g.quadCount + " stride " + g.vertexStride + " address " + va);
            requireAligned(va, g.vertexFormat == VK_FORMAT_R32G32B32_SFLOAT ? 4 : 2, "vertexData");
            requireAligned(idx, 4, "indexData");
            long ta = transformBase == 0 || g.transform3x4 == null ? 0 : transformBase + i * 48L;
            if (ta != 0) requireAligned(ta, 16, "transformData");
            t.vertexData().deviceAddress(va);
            t.indexData().deviceAddress(idx);
            t.transformData().deviceAddress(ta);
        }
        int flags = VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR | extraFlags;
        return VkAccelerationStructureBuildGeometryInfoKHR.calloc(s).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(flags).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).pGeometries(gb)
                .geometryCount(geoms.size()); // LWJGL does not derive it (shared with ppGeometries)
    }

    private Accel create(MemoryStack s, int type, long bytes, long scratch) {
        VkBuf storage = dev.rawBuffer(bytes, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
        LongBuffer lb = s.mallocLong(1);
        VkGpuDevice.check(vkCreateAccelerationStructureKHR(vk, VkAccelerationStructureCreateInfoKHR.calloc(s).sType$Default()
                .buffer(storage.buffer()).offset(0).size(bytes).type(type), null, lb), "vkCreateAccelerationStructureKHR");
        long h = lb.get(0);
        long addr = vkGetAccelerationStructureDeviceAddressKHR(vk, VkAccelerationStructureDeviceAddressInfoKHR.calloc(s).sType$Default()
                .accelerationStructure(h));
        return new Accel(h, addr, bytes, storage, scratch);
    }

    private VkBuf scratch(long bytes) {
        VkBuf b = dev.rawBuffer(bytes + scratchAlign, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT);
        transient_.add(b);
        return b;
    }

    /**
     * The aligned scratch address inside {@code sc} for {@code need} bytes. R1-HANG: refuses (throws, the caller falls back
     * to raster) rather than let the build write outside the buffer.
     */
    private long scratchAddress(VkBuf sc, long need) {
        long base = address(sc), a = alignUp(base, scratchAlign);
        if (!rangeInside(base, sc.size(), a, need))
            throw new IllegalStateException(String.format("AS scratch 0x%x+%d outside its buffer 0x%x+%d", a, need, base, sc.size()));
        return a;
    }

    /** True when [a, a + need) lies inside [base, base + size). Unsigned-safe (device addresses may be negative longs). */
    public static boolean rangeInside(long base, long size, long a, long need) {
        long off = a - base;
        return off >= 0 && need >= 0 && off <= size && need <= size - off;
    }

    /**
     * First address at or above {@code addr} that is a multiple of {@code align}. R1-HANG: RADV device addresses are
     * sign-extended (0xffff8001..., NEGATIVE as a Java long); the old {@code (a + n - 1) / n * n} rounds toward zero, i.e.
     * up by another n for a negative a, and the build wrote up to n-1 bytes past the scratch buffer (validation: "scratchData
     * .deviceAddress ... has no buffer(s) associated that are valid"; then VK_ERROR_DEVICE_LOST). Never divide addresses.
     */
    public static long alignUp(long addr, long align) {
        if (align <= 1) return addr;
        if ((align & (align - 1)) == 0) return (addr + align - 1) & -align;   // the spec guarantees a power of two here
        long r = Math.floorMod(addr, align);
        return r == 0 ? addr : addr + (align - r);
    }

    /**
     * R1-HANG: writes build inputs INTO the frame's command buffer (vkCmdUpdateBuffer, 64 KiB per call, ordered by the
     * caller's TRANSFER -> AS_BUILD barrier), in program order. UPLOAD-SYNC correction (2026-11-24): {@code dev.upload} in
     * the game is VulkanMod's UploadManager, submitted at endFrame BEFORE the frame (which waits on it), not after it; it is
     * still out of program order (lands before every command of the frame) and not ordered against frames in flight, so
     * in-frame writes stay right here. Must be called outside a render pass.
     */
    void writeInFrame(VkBuf b, ByteBuffer data) {
        int len = data.remaining();
        if ((len & 3) != 0) throw new IllegalArgumentException("in-frame write of " + len + " bytes (needs a multiple of 4)");
        VkCommandBuffer c = dev.recordingCommandBuffer();
        long src = MemoryUtil.memAddress(data);
        for (int off = 0; off < len; off += 65536) {
            int n = Math.min(65536, len - off);
            nvkCmdUpdateBuffer(c, b.buffer(), off, n, src + off);
        }
    }

    /** Device addresses the builds read must be aligned (VUIDs 03711 vertexData component, 03712 indexData, 03810 transform, 03715 instances). */
    private static void requireAligned(long addr, int align, String what) {
        if ((addr & (align - 1)) != 0)
            throw new IllegalStateException(String.format("%s address 0x%x not %d-byte aligned", what, addr, align));
    }

    /**
     * Records a BLAS build (inputs must already be visible to AS builds: see {@link #barrierInputsToBuild}). Transforms are
     * uploaded into a small input buffer kept until {@link #releaseTransient}.
     */
    public Accel buildBlas(List<Geometry> geoms, boolean compactable) {
        return buildBlas(geoms, compactable ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR : 0, null);
    }

    /**
     * P5-3 refit fallback (free-form entity renderers): a BLAS built with ALLOW_UPDATE whose vertices are rewritten in place
     * each frame and refitted with {@link #refitBlas} (same topology), rebuilt from scratch every N frames because a refit's
     * bounds only grow looser. Rigid model parts never take this path (one static BLAS per part + a TLAS instance pose).
     */
    public Accel buildBlasUpdatable(List<Geometry> geoms) {
        return buildBlas(geoms, VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR, null);
    }

    /** Records an in-place refit (mode UPDATE, src = dst) of a BLAS made by {@link #buildBlasUpdatable} over the same geometry layout. */
    public void refitBlas(Accel a, List<Geometry> geoms) {
        buildBlas(geoms, VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR, a);
    }

    private Accel buildBlas(List<Geometry> geoms, int extraFlags, Accel update) {
        try (MemoryStack s = stackPush()) {
            int maxQuads = 0;
            for (Geometry g : geoms) maxQuads = Math.max(maxQuads, g.quadCount);
            quadIndices(Math.max(1, maxQuads));   // R1-HANG: grow (in-frame write + its barrier) before the inputs below
            ByteBuffer tb = MemoryUtil.memAlloc(geoms.size() * 48).order(ByteOrder.LITTLE_ENDIAN);
            for (Geometry g : geoms) for (int k = 0; k < 12; k++) tb.putFloat(g.transform3x4 == null ? 0 : g.transform3x4[k]);
            tb.flip();
            VkBuf tbuf = inputBuffer(tb.remaining());
            writeInFrame(tbuf, tb);
            MemoryUtil.memFree(tb);
            transient_.add(tbuf);
            dev.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, STAGE_AS_BUILD, VK_ACCESS_SHADER_READ_BIT | ACCESS_AS_READ);
            VkAccelerationStructureBuildGeometryInfoKHR info = blasInfo(s, geoms, extraFlags, address(tbuf));
            java.nio.IntBuffer counts = counts(s, geoms);
            VkAccelerationStructureBuildSizesInfoKHR sz = VkAccelerationStructureBuildSizesInfoKHR.calloc(s).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, counts, sz);
            Accel a;
            VkBuf sc;
            if (update == null) {
                a = create(s, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, sz.accelerationStructureSize(), sz.buildScratchSize());
                sc = scratch(sz.buildScratchSize());
            } else {
                a = update;
                sc = scratch(sz.updateScratchSize());
                info.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR).srcAccelerationStructure(a.handle);
                dev.barrier(STAGE_AS_BUILD, ACCESS_AS_READ | ACCESS_AS_WRITE, STAGE_AS_BUILD, ACCESS_AS_READ | ACCESS_AS_WRITE);
            }
            info.dstAccelerationStructure(a.handle).scratchData().deviceAddress(scratchAddress(sc, update == null ? sz.buildScratchSize() : sz.updateScratchSize()));
            VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges = VkAccelerationStructureBuildRangeInfoKHR.calloc(geoms.size(), s);
            for (int i = 0; i < geoms.size(); i++) ranges.get(i).primitiveCount(geoms.get(i).quadCount * 2);
            PointerBuffer pr = s.pointers(ranges.address());
            vkCmdBuildAccelerationStructuresKHR(dev.recordingCommandBuffer(), VkAccelerationStructureBuildGeometryInfoKHR.create(info.address(), 1), pr);
            return a;
        }
    }

    /** Records a compacted-size query for {@code blas} into a new query pool; read it after the work finished. */
    public long queryCompactedSize(List<Accel> blas) {
        try (MemoryStack s = stackPush()) {
            LongBuffer lb = s.mallocLong(1);
            VkGpuDevice.check(vkCreateQueryPool(vk, VkQueryPoolCreateInfo.calloc(s).sType$Default()
                    .queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR).queryCount(blas.size()), null, lb), "query pool");
            long pool = lb.get(0);
            VkCommandBuffer c = dev.recordingCommandBuffer();
            vkCmdResetQueryPool(c, pool, 0, blas.size());
            dev.barrier(STAGE_AS_BUILD, ACCESS_AS_WRITE, STAGE_AS_BUILD, ACCESS_AS_READ);
            LongBuffer hs = s.mallocLong(blas.size());
            for (Accel a : blas) hs.put(a.handle);
            hs.flip();
            vkCmdWriteAccelerationStructuresPropertiesKHR(c, hs, VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR, pool, 0);
            return pool;
        }
    }

    /** Reads the compacted sizes (after the device finished the recorded work) and destroys the pool. */
    public long[] readCompactedSizes(long pool, int n) {
        try (MemoryStack s = stackPush()) {
            LongBuffer out = s.mallocLong(n);
            VkGpuDevice.check(vkGetQueryPoolResults(vk, pool, 0, n, out, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT), "query results");
            vkDestroyQueryPool(vk, pool, null);
            long[] r = new long[n];
            for (int i = 0; i < n; i++) r[i] = out.get(i);
            return r;
        }
    }

    /** Records a COMPACT copy into a new structure of {@code compactBytes}; the source is destroyed after the GPU used it. */
    public Accel compact(Accel src, long compactBytes) {
        try (MemoryStack s = stackPush()) {
            Accel dst = create(s, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, compactBytes, 0);
            vkCmdCopyAccelerationStructureKHR(dev.recordingCommandBuffer(), VkCopyAccelerationStructureInfoKHR.calloc(s).sType$Default()
                    .src(src.handle).dst(dst.handle).mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR));
            destroy(src);
            return dst;
        }
    }

    /**
     * One TLAS instance with a full row-major 3x4 object-to-world transform (P5-3: posed entity model parts; terrain sections
     * use the translation-only constructor: the section origin relative to the camera-near rebase origin). Shaders that
     * need a world-space normal at a hit transform the triangle through {@code rayQueryGetIntersectionObjectToWorldEXT}.
     */
    public record Instance(float[] transform3x4, int customIndex, int mask, long blasAddress, boolean forceOpaque) {
        public Instance(float tx, float ty, float tz, int customIndex, int mask, long blasAddress, boolean forceOpaque) {
            this(new float[]{1, 0, 0, tx, 0, 1, 0, ty, 0, 0, 1, tz}, customIndex, mask, blasAddress, forceOpaque);
        }
    }

    /** Bytes per TLAS instance record (VkAccelerationStructureInstanceKHR). */
    public static final int INSTANCE_BYTES = 64;

    /** Records a full TLAS build over {@code instances} (rebuilt every frame in v1; see docs/P5-DESIGN.md). */
    public Accel buildTlas(List<Instance> instances) {
        try (MemoryStack s = stackPush()) {
            int n = instances.size();
            ByteBuffer ib = MemoryUtil.memCalloc(Math.max(1, n) * 64).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < n; i++) {
                Instance in = instances.get(i);
                int o = i * 64;
                for (int k = 0; k < 12; k++) ib.putFloat(o + k * 4, in.transform3x4[k]);
                ib.putInt(o + 48, (in.customIndex & 0xFFFFFF) | (in.mask << 24));
                int flags = VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR | (in.forceOpaque ? VK_GEOMETRY_INSTANCE_FORCE_OPAQUE_BIT_KHR : 0);
                ib.putInt(o + 52, flags << 24); // sbt offset 0
                ib.putLong(o + 56, in.blasAddress);
            }
            VkBuf ibuf = inputBuffer(Math.max(1, n) * 64L);
            writeInFrame(ibuf, ib);   // R1-HANG: in this frame's command buffer, never a late upload
            MemoryUtil.memFree(ib);
            requireAligned(address(ibuf), 16, "instances data");
            transient_.add(ibuf);
            dev.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT | STAGE_AS_BUILD, VK_ACCESS_TRANSFER_WRITE_BIT | ACCESS_AS_WRITE,
                    STAGE_AS_BUILD, VK_ACCESS_SHADER_READ_BIT | ACCESS_AS_READ);
            VkAccelerationStructureGeometryKHR.Buffer g = VkAccelerationStructureGeometryKHR.calloc(1, s);
            g.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).flags(0)
                    .geometry().instances().sType$Default().arrayOfPointers(false).data().deviceAddress(address(ibuf));
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, s);
            info.get(0).sType$Default().type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).pGeometries(g)
                    .geometryCount(1);
            VkAccelerationStructureBuildSizesInfoKHR sz = VkAccelerationStructureBuildSizesInfoKHR.calloc(s).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info.get(0), s.ints(n), sz);
            Accel a = create(s, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, sz.accelerationStructureSize(), sz.buildScratchSize());
            VkBuf sc = scratch(sz.buildScratchSize());
            info.get(0).dstAccelerationStructure(a.handle).scratchData().deviceAddress(scratchAddress(sc, sz.buildScratchSize()));
            VkAccelerationStructureBuildRangeInfoKHR.Buffer r = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, s);
            r.get(0).primitiveCount(n);
            vkCmdBuildAccelerationStructuresKHR(dev.recordingCommandBuffer(), info, s.pointers(r.address()));
            dev.barrier(STAGE_AS_BUILD, ACCESS_AS_WRITE, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | STAGE_AS_BUILD, ACCESS_AS_READ | VK_ACCESS_SHADER_READ_BIT);
            return a;
        }
    }

    /** Barrier: buffer uploads (vertex arenas) become visible to the AS builds and shaders. */
    public void barrierInputsToBuild() {
        dev.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, STAGE_AS_BUILD | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT | ACCESS_AS_READ);
    }

    /** Barrier: BLAS builds finished before TLAS builds / traversal read them. */
    public void barrierBuildToBuild() {
        dev.barrier(STAGE_AS_BUILD, ACCESS_AS_WRITE, STAGE_AS_BUILD | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, ACCESS_AS_READ);
    }

    /** Frees scratch / instance / transform buffers once the GPU finished with them. */
    public void releaseTransient() {
        for (VkBuf b : transient_) dev.destroyBuffer(b);
        transient_.clear();
    }

    public void destroy(Accel a) {
        if (a.destroyed) throw new IllegalStateException("acceleration structure destroyed twice");
        a.destroyed = true;
        long h = a.handle;
        dev.deferRelease(() -> vkDestroyAccelerationStructureKHR(vk, h, null));
        dev.destroyBuffer(a.storage);
    }

    public void close() {
        releaseTransient();
        if (quadIndices != null) dev.destroyBuffer(quadIndices);
        quadIndices = null;
    }
}
