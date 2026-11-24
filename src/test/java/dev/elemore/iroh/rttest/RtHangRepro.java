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
 * R1-HANG (td-84dfca) reproductions, offline ({@code ./gradlew rtHangRepro}; last line "# rthang PASS n/n").
 * The in-game R1 run (int-4c-f7, RX 9070 XT, RADV 26.2.5) logged, ~4 s before VK_ERROR_DEVICE_LOST + "ring gfx_0.0.0
 * timeout", validation errors "scratchData.deviceAddress [...] has no buffer(s) associated that are valid": the build
 * scratch range ran past (or started beyond) its buffer. Two defects, neither visible on lavapipe as it was used:
 * <ol>
 * <li>scratch alignment by SIGNED division: RADV device addresses are sign-extended (0xffff8001_xxxxxxxx, negative as
 * a Java long); (a + n - 1) / n * n rounds toward zero, i.e. UP by a further n for a negative a, so the aligned start
 * lands up to 2n-1 bytes into a buffer of size + n and the build writes up to n-1 bytes past its end. Lavapipe
 * addresses are host pointers (positive), where the formula is right. Check A, no device.</li>
 * <li>RtAccel wrote its build inputs (geometry transforms, TLAS instance records, the shared quad index buffer) with
 * {@code dev.upload}: in the game that is VulkanMod's UploadManager, whose batch is submitted at the NEXT beginFrame on
 * the transfer family (here the async-compute family), so the frame's AS builds read the buffers before they are
 * written. HeadlessGpuDevice always submitted uploads first, so offline never saw it. Check B models the game order
 * ({@link HeadlessGpuDevice#uploadsAfterFrame}).</li>
 * </ol>
 */
public final class RtHangRepro {
    static final String SH = RtProbe.SH;

    public static void main(String[] args) {
        int pass = 0, total = 0;

        // ---- A: scratch alignment over RADV-style (negative) and lavapipe-style (positive) addresses
        total++;
        long[] bases = {0xffff800101a7df80L, 0xffff800101a7c740L, 0xffff800101f5f180L, 0xffff800102002bd0L, 0xffff800102065680L,
                0xffff800000000000L, 0x00007f3ed6768000L, 0x00007f3ed67680c8L, 0x1000L};
        int bad = 0;
        String firstBad = null;
        for (long align : new long[]{256, 128, 64, 16}) {
            for (long base0 : bases) {
                for (int r = 0; r < 300; r += 4) {
                    long base = base0 + r, need = 148, size = need + align;   // RtAccel.scratch: buffer = size + align
                    long a = RtAccel.alignUp(base, align);
                    boolean ok = Math.floorMod(a, align) == 0 && a >= base && a - base < align && a + need <= base + size;
                    if (!ok) {
                        bad++;
                        if (firstBad == null)
                            firstBad = String.format("align %d base 0x%x -> 0x%x (+%d, range end +%d past the buffer)", align, base, a, a - base, a + need - (base + size));
                    }
                }
            }
        }
        if (bad == 0) pass++;
        System.out.println((bad == 0 ? "ok   " : "FAIL ") + "A scratch alignment: " + bad + " bad cases" + (firstBad == null ? "" : "; first: " + firstBad));

        // ---- B: build inputs written in the frame that builds (game upload order)
        try (HeadlessGpuDevice dev = new HeadlessGpuDevice(1L << 30, false, true)) {
            RtAccel acc = new RtAccel(dev);
            RtCompute comp = new RtCompute(dev);
            ByteBuffer v = MemoryUtil.memAlloc(64).order(ByteOrder.LITTLE_ENDIAN);
            float[][] c = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}};
            for (float[] p : c) {
                for (float x : p) v.putShort((short) Math.round(x * 2048 - 8192));
                v.putShort((short) 0).putInt(0).putInt(-1);
            }
            v.flip();
            VkBuf vb = acc.inputBuffer(64);
            VkBuf out = acc.inputBuffer(16);
            dev.upload(vb, 0, v);   // the arena: uploaded in an EARLIER submit (game: beginFrame, the frame waits on it)
            dev.flush();
            dev.uploadsAfterFrame = true;
            ByteBuffer sp = Glsl.compile("hangprobe", SH, Glsl.COMPUTE);
            RtCompute.Pass p = comp.create("hangprobe", sp, true, 1, 0);
            float s = RtAccel.SNORM_SCALE;
            for (int frame = 0; frame < 3; frame++) {
                total++;
                float[] tr = {s, 0, 0, 4, 0, s, 0, 4, 0, 0, s, 4 + frame};
                acc.barrierInputsToBuild();
                RtAccel.Accel b = acc.buildBlas(List.of(new RtAccel.Geometry(vb, 0, 16, 1, tr, true)), false);
                acc.barrierBuildToBuild();
                RtAccel.Accel t = acc.buildTlas(List.of(new RtAccel.Instance(0, 0, 0, 0, 0xFF, b.address, false)));
                comp.dispatch(p, t.handle, new VkBuf[]{out}, null, 1, 1, 1);
                ByteBuffer r = dev.readBuffer(out, 0, 4);   // flushes: frame cmd, then (late) uploads
                comp.resetSets();
                acc.destroy(b);
                acc.destroy(t);
                acc.releaseTransient();
                float want = 1f + frame;
                boolean ok = Math.abs(r.getFloat(0) - want) < 1e-4f;
                if (ok) pass++;
                System.out.println((ok ? "ok   " : "FAIL ") + "B frame " + frame + " (uploads after the frame, as in game): hit t = " + r.getFloat(0)
                        + " (expected " + want + (ok ? "" : "; -1 = the build read transforms / instances / indices not yet written") + ")");
            }
            dev.uploadsAfterFrame = false;
            dev.flush();
            dev.destroyBuffer(vb);
            dev.destroyBuffer(out);
            MemoryUtil.memFree(v);
            acc.close();
            comp.destroy(p);
            comp.close();
            dev.flush();
        }
        System.out.println("# rthang " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        if (pass != total) System.exit(1);
    }
}
