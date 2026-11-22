package dev.elemore.iroh.rt.game;

import dev.elemore.iroh.gpu.VkGpuDevice;
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

    public VkRtBackend(VkGpuDevice dev) {
        this.dev = dev;
        this.accel = new RtAccel(dev);
        if (!accel.vertexFormatSupported(VK_FORMAT_R16G16B16A16_SNORM))
            throw new IllegalStateException("R16G16B16A16_SNORM is not an acceleration-structure vertex format on this device");
        if (net.vulkanmod.render.chunk.buffer.DrawBuffers.VERTEX_SIZE != 16)
            throw new IllegalStateException("RT needs the 16-byte compressed terrain vertex (got " + net.vulkanmod.render.chunk.buffer.DrawBuffers.VERTEX_SIZE + ")");
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
        long h = next++;
        blas.put(h, new RtAccel.Accel[]{a, t});
        bytes += (a == null ? 0 : a.bytes) + (t == null ? 0 : t.bytes);
        return h;
    }

    @Override
    public void destroyBlas(long handle) {
        RtAccel.Accel[] p = blas.remove(handle);
        if (p == null) return;
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
        List<RtAccel.Instance> inst = new ArrayList<>(count * 2);
        for (int i = 0; i < count; i++) {
            RtAccel.Accel[] p = blas.get(handles[i]);
            if (p == null) continue;
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
        accel.releaseTransient();   // VkGpuDevice frees are deferred past the frames that read them
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
        return tlas == null ? 0 : tlas.address;
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
        if (tlas != null) {
            accel.destroy(tlas);
            tlas = null;
        }
        targets(0, 0);
        accel.close();
        bytes = 0;
    }
}
