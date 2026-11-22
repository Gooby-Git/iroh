package dev.elemore.iroh.rttest;

import dev.elemore.iroh.gpu.Glsl;
import dev.elemore.iroh.gpu.HeadlessGpuDevice;
import dev.elemore.iroh.gpu.VkGpuDevice.VkBuf;
import dev.elemore.iroh.rt.vk.RtAccel;
import dev.elemore.iroh.rt.vk.RtCompute;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * RT smoke test on lavapipe ({@code ./gradlew rtProbe}): one quad in Iroh's 16-byte vertex format, BLAS read as
 * R16G16B16A16_SNORM + geometry transform, TLAS with a translated instance, one ray query. Expected hit distances 1, 6,
 * 1 (after a flush), and 1 for float vertices without a transform. Last line "# rtprobe PASS n/4".
 */
public final class RtProbe {
    static final String SH = """
            #version 460
            #extension GL_EXT_ray_query : require
            layout(local_size_x = 1) in;
            layout(set = 0, binding = 0) uniform accelerationStructureEXT tlas;
            layout(std430, set = 0, binding = 1) buffer O { float o[]; };
            void main() {
                rayQueryEXT rq;
                rayQueryInitializeEXT(rq, tlas, gl_RayFlagsOpaqueEXT, 0xFF, vec3(0.5, 0.5, -1.0), 0.0, vec3(0, 0, 1), 100.0);
                while (rayQueryProceedEXT(rq)) {}
                o[0] = rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionTriangleEXT ? rayQueryGetIntersectionTEXT(rq, true) : -1.0;
            }
            """;

    public static void main(String[] a) {
        int pass = 0;
        try (HeadlessGpuDevice dev = new HeadlessGpuDevice(1L << 30, false, true)) {
            RtAccel acc = new RtAccel(dev);
            RtCompute comp = new RtCompute(dev);
            ByteBuffer v = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            float[][] c = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}};
            for (float[] p : c) {
                for (float x : p) v.putShort((short) Math.round(x * 2048 - 8192));
                v.putShort((short) 0).putInt(0).putInt(-1);
            }
            v.flip();
            ByteBuffer fv = MemoryUtil.memAlloc(48).order(ByteOrder.LITTLE_ENDIAN);
            for (float[] q : c) for (float x : q) fv.putFloat(x);
            fv.flip();
            VkBuf fb = acc.inputBuffer(48);
            dev.upload(fb, 0, fv);
            VkBuf vb = acc.inputBuffer(64);
            ByteBuffer d = MemoryUtil.memAlloc(64);
            d.put(v).flip();
            dev.upload(vb, 0, d);
            acc.quadIndices(1);
            dev.flush();
            VkBuf out = acc.inputBuffer(16);
            ByteBuffer sp = Glsl.compile("probe", SH, Glsl.COMPUTE);
            RtCompute.Pass p = comp.create("probe", sp, true, 1, 0);
            float s = RtAccel.SNORM_SCALE;
            for (int variant = 0; variant < 4; variant++) {
                float[] tr = {s, 0, 0, 4, 0, s, 0, 4, 0, 0, s, 4};
                acc.barrierInputsToBuild();
                RtAccel.Geometry g = variant == 3 ? new RtAccel.Geometry(fb, 0, 12, 1, null, true, org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT)
                        : new RtAccel.Geometry(vb, 0, 16, 1, tr, true);
                RtAccel.Accel b = acc.buildBlas(List.of(g), false);
                acc.barrierBuildToBuild();
                float tz = variant == 1 ? 5 : 0;
                RtAccel.Accel t = acc.buildTlas(List.of(new RtAccel.Instance(0, 0, tz, 0, 0xFF, b.address, false)));
                if (variant == 2) { dev.flush(); comp.resetSets(); }
                comp.dispatch(p, t.handle, new VkBuf[]{out}, null, 1, 1, 1);
                ByteBuffer r = dev.readBuffer(out, 0, 4);
                comp.resetSets();
                acc.destroy(b);
                acc.destroy(t);
                float want = variant == 1 ? 6f : 1f;
                boolean ok = Math.abs(r.getFloat(0) - want) < 1e-4f;
                if (ok) pass++;
                System.out.println((ok ? "ok   " : "FAIL ") + "variant " + variant + " hit t = " + r.getFloat(0) + " (expected " + want + ")");
            }
            dev.flush();
            acc.close();
            comp.destroy(p);
            comp.close();
            System.out.println("# rtprobe " + (pass == 4 ? "PASS" : "FAIL") + " " + pass + "/4");
            if (pass != 4) System.exit(1);
        }
    }
}
