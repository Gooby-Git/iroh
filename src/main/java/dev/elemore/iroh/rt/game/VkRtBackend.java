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
    /** R1-HANG: true only between this frame's TLAS build and the next change to the BLAS set (see {@link #tlasForTrace}) */
    private boolean tlasValid;
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
        RtAccel.Accel a = solid.isEmpty() ? null : accel.buildBlas(solid, false);
        RtAccel.Accel t = trans.isEmpty() ? null : accel.buildBlas(trans, false);
        accel.barrierBuildToBuild();
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
        for (RtAccel.Accel a : p)
            if (a != null) {
                bytes -= a.bytes;
                accel.destroy(a);   // deferred by the device's frames-in-flight rule
            }
    }

    public void origin(int sx, int sy, int sz) {
        ox = sx;
        oy = sy;
        oz = sz;
    }

    @Override
    public void buildTlas(long[] handles, int[][] sections, int count) {
        tlasValid = false;
        if (count == 0) {   // R1-HANG: nothing resident (or world unload / disable, possibly outside a frame): no TLAS, no recording
            if (tlas != null) {
                bytes -= tlas.bytes;
                accel.destroy(tlas);
                tlas = null;
            }
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
        if (tlas != null) {
            bytes -= tlas.bytes;
            accel.destroy(tlas);
        }
        tlas = accel.buildTlas(inst);
        bytes += tlas.bytes;
        tlasBuilds++;
        tlasValid = true;
        accel.releaseTransient();   // VkGpuDevice frees are deferred past the frames that read them
    }

    /**
     * R1-HANG: the TLAS a ray-query pass of THIS frame may trace, or 0 = do not trace. Valid only after this frame's
     * {@link #buildTlas} (recorded earlier in the same command buffer, followed by the build -> compute barrier) and
     * while no BLAS it names was destroyed since. A pass that gets 0 skips tracing (raster stays).
     */
    public long tlasForTrace() {
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
        for (Long h : new ArrayList<>(blas.keySet())) destroyBlas(h);
        tlasValid = false;
        if (tlas != null) {
            accel.destroy(tlas);
            tlas = null;
        }
        targets(0, 0);
        accel.close();
        bytes = 0;
    }
}
