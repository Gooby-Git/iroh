package dev.elemore.iroh.rt.game;

import dev.elemore.iroh.gpu.VkGpuDevice;
import dev.elemore.iroh.rt.RtLimits;
import dev.elemore.iroh.rt.SectionGeometry;
import dev.elemore.iroh.rt.vk.RtAccel;

import java.util.*;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SNORM;

/**
 * P5-2: {@link RtSession.Backend} on Iroh's api-4 Vulkan device through {@link RtAccel} (the code rtReplay proves on
 * lavapipe), reading the raster arena IN PLACE (RtAccel.Geometry with an absolute device address). Per section: one
 * BLAS for opaque + cutout (two geometries, opaque flag on the first) and, when present, one for translucent (TLAS mask
 * 2), as offline. TLAS rebuilt every frame, translation-only instances relative to the camera section (float precision
 * far from 0,0). All recording happens OUTSIDE the main render pass (the caller wraps frame() in endPassRunResume).
 * Game only; UNVERIFIED in game (Proctor R1).
 */
public final class VkRtBackend implements RtSession.Backend {
    private final VkGpuDevice dev;
    private final RtAccel accel;
    private final Map<Long, RtAccel.Accel[]> blas = new HashMap<>();
    private long next = 1, bytes;
    private RtAccel.Accel tlas;
    private VkGpuDevice.VkBuf work;
    private int ox, oy, oz;
    /** R1-HANG: true only between a TLAS build and the next change to the BLAS set (see {@link #tlasForTrace}) */
    private boolean tlasValid;
    /** R1-REV (review #6): the BLAS set or the rebase origin changed since the last TLAS build (else the TLAS is reused) */
    private boolean tlasDirty = true;
    private int tlasCount;
    /** the per-frame input barrier was recorded (reset by {@link #buildTlas}, which the session calls once per frame) */
    private boolean inputsOrdered;
    /** builds recorded since construction (rtWorldReplay prints them) */
    public long blasBuilds, tlasBuilds;

    /** {@code vertexSize}: the raster arena's vertex size (game: DrawBuffers.VERTEX_SIZE; rtWorldReplay: 16). */
    public VkRtBackend(VkGpuDevice dev, int vertexSize) {
        this.dev = dev;
        this.accel = new RtAccel(dev);
        if (!accel.vertexFormatSupported(VK_FORMAT_R16G16B16A16_SNORM))
            throw new IllegalStateException("R16G16B16A16_SNORM is not an acceleration-structure vertex format on this device");
        if (vertexSize != 16)
            throw new IllegalStateException("RT needs the 16-byte compressed terrain vertex (got " + vertexSize + ")");
    }

    static final float[] TRANSFORM = {RtAccel.SNORM_SCALE, 0, 0, RtAccel.POS_OFFSET, 0, RtAccel.SNORM_SCALE, 0, RtAccel.POS_OFFSET, 0, 0, RtAccel.SNORM_SCALE, RtAccel.POS_OFFSET};

    @Override
    public long buildBlas(SectionGeometry g, List<RtSectionSource.Range> ranges) {
        List<RtAccel.Geometry> solid = new ArrayList<>(), trans = new ArrayList<>();
        for (RtSectionSource.Range r : ranges) {
            RtAccel.Geometry geo = new RtAccel.Geometry(null, r.address() + r.byteOffset(), r.vertexStride(), r.quads(), TRANSFORM,
                    r.bucket() == SectionGeometry.OPAQUE, VK_FORMAT_R16G16B16A16_SNORM);
            (r.bucket() == SectionGeometry.TRANSLUCENT ? trans : solid).add(geo);
        }
        if (solid.isEmpty() && trans.isEmpty()) return 0;
        if (!inputsOrdered) {   // R1-REV: ONE transfer -> build barrier per frame (in-frame arena copies, if any, come first)
            accel.barrierInputsToBuild();
            inputsOrdered = true;
        }
        RtAccel.Accel a = solid.isEmpty() ? null : accel.buildBlas(solid, false);
        RtAccel.Accel t;
        try {
            t = trans.isEmpty() ? null : accel.buildBlas(trans, false);
        } catch (RuntimeException | Error e) {   // R1-REV (review #3): the solid BLAS is not leaked when the second build fails
            if (a != null) accel.destroy(a);
            throw e;
        }
        // R1-REV (review #6): no build -> build barrier per section: section BLASes write disjoint storage and scratch and
        // read nothing another section's build writes; the TLAS build's barrier orders them all before the TLAS reads them
        tlasDirty = true;
        blasBuilds += (a == null ? 0 : 1) + (t == null ? 0 : 1);
        long h = next++;
        blas.put(h, new RtAccel.Accel[]{a, t});
        bytes += (a == null ? 0 : a.bytes) + (t == null ? 0 : t.bytes);
        return h;
    }

    @Override
    public void destroyBlas(long handle) {
        RtAccel.Accel[] p = blas.remove(handle);
        if (p == null) return;
        tlasValid = false;   // the current TLAS may name it: no trace until the next TLAS build
        tlasDirty = true;
        for (RtAccel.Accel a : p)
            if (a != null) {
                bytes -= a.bytes;
                accel.destroy(a);   // deferred by the device's frames-in-flight rule
            }
    }

    public void origin(int sx, int sy, int sz) {
        if (sx != ox || sy != oy || sz != oz) tlasDirty = true;   // instance translations are relative to it
        ox = sx;
        oy = sy;
        oz = sz;
    }

    @Override
    public void buildTlas(long[] handles, int[][] sections, int count) {
        inputsOrdered = false;
        // R1-REV (review #6): nothing changed since the last build (same BLAS set, same origin): the TLAS built in an earlier
        // frame stays valid (its trailing build -> trace barrier orders it before every later command on the queue), no
        // per-frame instance write, allocation or build
        if (count > 0 && !tlasDirty && count == tlasCount && tlas != null && !tlas.destroyed()) return;
        tlasValid = false;
        if (count == 0) {   // R1-HANG: nothing resident (or world unload / disable, possibly outside a frame): no TLAS, no recording
            dropTlas();
            tlasCount = 0;
            tlasDirty = true;
            return;
        }
        if (count * 2L > RtLimits.HARD_MAX_INSTANCES)
            throw new IllegalStateException("TLAS of " + count + " sections exceeds the hard instance cap");
        List<RtAccel.Instance> inst = new ArrayList<>(count * 2);
        for (int i = 0; i < count; i++) {
            RtAccel.Accel[] p = blas.get(handles[i]);
            if (p == null) continue;
            for (RtAccel.Accel x : p) // R1-HANG: never name a destroyed BLAS (a TLAS over freed memory hangs the GPU)
                if (x != null && x.destroyed()) throw new IllegalStateException("TLAS would name a destroyed BLAS (handle " + handles[i] + ")");
            float tx = (sections[i][0] - ox) * 16f, ty = (sections[i][1] - oy) * 16f, tz = (sections[i][2] - oz) * 16f;
            if (p[0] != null) inst.add(new RtAccel.Instance(tx, ty, tz, i, 1, p[0].address, false));
            if (p[1] != null) inst.add(new RtAccel.Instance(tx, ty, tz, i, 2, p[1].address, false));
        }
        dropTlas();   // R1-REV (review #2): field cleared BEFORE the build, so a failed build is never destroyed twice
        try {
            tlas = accel.buildTlas(inst);
        } finally {
            accel.releaseTransient();   // VkGpuDevice frees are deferred past the frames that read them (also after a failure)
        }
        bytes += tlas.bytes;
        tlasBuilds++;
        tlasCount = count;
        tlasDirty = false;
        tlasValid = true;
    }

    private void dropTlas() {
        RtAccel.Accel t = tlas;
        tlas = null;
        if (t != null) {
            bytes -= t.bytes;
            accel.destroy(t);
        }
    }

    /** R1-REV (review #7): the storage bytes of a BLAS handle (opaque + translucent), for the owner's reconcile */
    @Override
    public long blasBytes(long handle) {
        RtAccel.Accel[] p = blas.get(handle);
        if (p == null) return -1;
        long b = 0;
        for (RtAccel.Accel a : p) if (a != null) b += a.bytes;
        return b;
    }

    /** bytes of all live section BLASes (rtWorldReplay compares the owner's count with it) */
    public long blasLiveBytes() {
        long b = 0;
        for (long h : blas.keySet()) b += blasBytes(h);
        return b;
    }

    /**
     * R1-HANG: the TLAS a ray-query pass of THIS frame may trace, or 0 = do not trace. Valid only after this frame's
     * {@link #buildTlas} (recorded earlier in the same command buffer, followed by the build -> compute barrier) and
     * while no BLAS it names was destroyed since. A pass that gets 0 skips tracing (raster stays).
     */
    public long tlasForTrace() {   // R1-REV: "this frame's" = built this frame, or reused unchanged (no BLAS destroyed since)
        return tlasValid && tlas != null && !tlas.destroyed() ? tlas.handle : 0;
    }

    @Override
    public void targets(int w, int h) {
        if (work != null) {
            bytes -= work.size();
            dev.destroyBuffer(work);
            work = null;
        }
        if (w <= 0 || h <= 0) return;
        // RT working buffers (sun/emit/GI/refl signals, cache resolve); the G-buffer attachment itself joins the main
        // pass's MRT set (RtGBuffer; designed, wired by the integration card)
        work = dev.rawBuffer((long) w * h * 64, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        bytes += work.size();
    }

    public long tlasAddress() {
        return tlasValid && tlas != null && !tlas.destroyed() ? tlas.address : 0;
    }

    @Override
    public int liveObjects() {
        int n = 0;
        for (RtAccel.Accel[] p : blas.values()) for (RtAccel.Accel a : p) if (a != null) n++;
        return n + (tlas != null ? 1 : 0) + (work != null ? 1 : 0);
    }

    @Override
    public long liveBytes() {
        return bytes;
    }

    @Override
    public void close() {
        try {
            for (Long h : new ArrayList<>(blas.keySet())) destroyBlas(h);
            tlasValid = false;
            dropTlas();
            targets(0, 0);
        } finally {
            accel.close();
            bytes = 0;
            tlasDirty = true;
            tlasCount = 0;
        }
    }
}
