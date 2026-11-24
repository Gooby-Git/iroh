package dev.elemore.iroh.rttest;

import dev.elemore.iroh.gpu.Glsl;
import dev.elemore.iroh.gpu.HeadlessGpuDevice;
import dev.elemore.iroh.gpu.VkGpuDevice.VkBuf;
import dev.elemore.iroh.rt.vk.RtAccel;
import dev.elemore.iroh.rt.vk.RtCompute;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import dev.elemore.iroh.rt.*;
import dev.elemore.iroh.rt.game.RtSectionSource;
import dev.elemore.iroh.rt.game.RtSession;
import dev.elemore.iroh.rt.game.VkRtBackend;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

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
 * {@code dev.upload}. UPLOAD-SYNC correction (2026-11-24): the game submits that UploadManager batch at endFrame BEFORE
 * the frame (the frame waits on it), so check B's {@link HeadlessGpuDevice#uploadsAfterFrame} is a STRICTER order than
 * the game's, not the game's; the in-frame writes it demands are still right (program order). Whether this defect
 * contributed to the in-game loss is therefore unproven: the scratch overrun (A) is the evidenced cause.</li>
 * </ol>
 * R1-REV (Gemini review 5cb221b, docs/reviews/5cb221b-verdicts.md): C = a TLAS build that fails after the old TLAS was
 * destroyed must not make the release path throw "destroyed twice" (#2); D = a BLAS / TLAS build that fails half way
 * (allocation refused after the structure, the scratch or the instance buffer exists) leaks no acceleration structure
 * and no device memory (#3); E = the BLAS size query records nothing (no index-buffer grow outside a frame, or inside
 * a render pass), and an RT input write inside a render pass is refused (#5); F = alignUp to a non-power-of-two on a
 * negative address (#10). Fault injection: {@link HeadlessGpuDevice#failAllocationIn}.
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
        // ---- F: alignUp to a non-power-of-two (review #10): unsigned 0xffff800100000000 is a multiple of 48, so a computed
        // result must be the address itself; R1-REV refuses non-powers of two (no modulo on addresses at all)
        total++;
        String f;
        boolean fok;
        try {
            long f1 = RtAccel.alignUp(0xffff800100000000L, 48);
            fok = f1 == 0xffff800100000000L;
            f = String.format("0x%x (want 0xffff800100000000 or a refusal)", f1);
        } catch (IllegalArgumentException e) {
            fok = true;
            f = "refused (" + e.getMessage() + ")";
        }
        if (fok) pass++;
        System.out.println((fok ? "ok   " : "FAIL ") + "F alignUp(0xffff800100000000, 48): " + f);

        // ---- C/D/E: failure paths and the size query on the device
        try (HeadlessGpuDevice dev = new HeadlessGpuDevice(1L << 30, false, true)) {
            int[] pt = runFailurePaths(dev);
            pass += pt[0];
            total += pt[1];
        }
        System.out.println("# rthang " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        if (pass != total) System.exit(1);
    }

    /** One 16-byte vertex arena with {@code quads} flat quads; returns {buffer, address}. */
    static VkBuf arena(HeadlessGpuDevice dev, RtAccel acc, int quads) {
        ByteBuffer v = MemoryUtil.memAlloc(quads * 64).order(ByteOrder.LITTLE_ENDIAN);
        for (int q = 0; q < quads; q++)
            for (float[] p : new float[][]{{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}}) {
                for (float x : p) v.putShort((short) Math.round((x + q % 8) * 2048 - 8192));
                v.putShort((short) 0).putInt(0).putInt(-1);
            }
        v.flip();
        VkBuf vb = acc.inputBuffer(v.remaining());
        dev.upload(vb, 0, v);
        dev.flush();
        MemoryUtil.memFree(v);
        return vb;
    }

    /** Session + VkRtBackend over one arena section source (sections (i, 0, 0) all read quads of the same arena). */
    record Rig(VkRtBackend backend, RtSession session, RtMemory.Counter mem) {}

    static Rig rig(HeadlessGpuDevice dev, long arenaAddr, int quads) {
        VkRtBackend backend = new VkRtBackend(dev, 16);
        RtMemory.Counter mem = new RtMemory.Counter(1L << 30);
        RtSectionSource src = new RtSectionSource() {
            @Override public List<Range> ranges(int sx, int sy, int sz) {
                return List.of(new Range(SectionGeometry.OPAQUE, arenaAddr, 0, 16, quads), new Range(SectionGeometry.TRANSLUCENT, arenaAddr, 0, 16, quads));
            }
            @Override public int[] quads(int sx, int sy, int sz) { return new int[]{quads, 0, quads}; }
        };
        RtSession s = new RtSession(RtSettings.DEFAULT, mem, RtMemoryModel.radvAssumed(), src, backend);
        if (!s.enable(16, 16)) throw new IllegalStateException("targets refused");
        return new Rig(backend, s, mem);
    }

    static SectionGeometry sec(int x, int quads, long version) {
        return new SectionGeometry(x, 0, 0, 0, 0, 16, new int[]{quads, 0, quads}, new long[3], new float[0], version);
    }

    static int[] runFailurePaths(HeadlessGpuDevice dev) {
        int pass = 0, total = 0;
        RtAccel helper = new RtAccel(dev);
        VkBuf vb = arena(dev, helper, 8);

        // C: frame 1 builds two sections + TLAS; frame 2 (nothing new) is forced to rebuild the TLAS (camera moved: new
        // rebase origin) and the TLAS's first allocation fails AFTER the old TLAS was destroyed; then the RT release path
        total++;
        {
            long base = dev.allocatedBytes();   // each case against its own baseline (an earlier case's leak is not this one's)
            Rig r = rig(dev, helper.address(vb), 8);
            r.session().queue.sectionBuilt(sec(0, 8, 1));
            r.session().queue.sectionBuilt(sec(1, 8, 1));
            r.backend().origin(0, 0, 0);
            r.session().frame(0, 0, 0);
            dev.flush();
            String fail = null, release = null;
            dev.failAllocationIn = 1;   // the TLAS instance buffer
            try {
                r.backend().origin(1, 0, 0);
                r.session().frame(1, 0, 0);
            } catch (RuntimeException e) {
                fail = e.getMessage();
            }
            dev.failAllocationIn = 0;
            try {
                r.session().disable();   // RtFrameHooks.fallBack -> stop -> disable
            } catch (RuntimeException e) {
                release = e.toString();
            }
            dev.flush();
            boolean ok = fail != null && release == null && r.backend().liveObjects() == 0 && r.mem().usedBytes() == 0 && dev.allocatedBytes() == base;
            if (ok) pass++;
            System.out.println((ok ? "ok   " : "FAIL ") + "C failed TLAS rebuild then release: frame threw [" + fail + "], release threw [" + release + "], live "
                    + r.backend().liveObjects() + ", owner " + r.mem().usedBytes() + " B, device bytes over the baseline " + (dev.allocatedBytes() - base));
        }

        // D: every allocation step of a frame's builds fails once in turn (index grow, transforms, scratch, structure, the
        // second BLAS, the TLAS instance buffer, TLAS scratch, TLAS storage...); after each failure the release path runs:
        // no acceleration structure and no device memory may be left
        {
            int steps = 0, bad = 0;
            String first = null;
            for (int k = 1; k <= 16; k++) {
                total++;
                long base = dev.allocatedBytes();
                Rig r = rig(dev, helper.address(vb), 8);
                RtAccel acc = accelOf(r.backend());
                r.session().queue.sectionBuilt(sec(0, 8, 1));
                r.backend().origin(0, 0, 0);
                dev.failAllocationIn = k;
                String fail = null, release = null;
                try {
                    r.session().frame(0, 0, 0);
                } catch (RuntimeException e) {
                    fail = e.getMessage();
                }
                boolean fired = dev.failAllocationIn == 0;
                dev.failAllocationIn = 0;
                try {
                    r.session().disable();
                } catch (RuntimeException e) {
                    release = e.toString();
                }
                dev.flush();
                int live = acc.liveStructures();
                boolean ok = release == null && live == 0 && dev.allocatedBytes() == base && r.mem().usedBytes() == 0 && (fired == (fail != null));
                if (fired) steps++;
                if (ok) pass++;
                else {
                    bad++;
                    if (first == null) first = "step " + k + ": frame threw [" + fail + "], release threw [" + release + "], live structures " + live
                            + ", device bytes over the baseline " + (dev.allocatedBytes() - base) + ", owner " + r.mem().usedBytes();
                }
                if (!fired) break;   // past the last allocation of the frame: the clean frame was checked too
            }
            System.out.println((bad == 0 ? "ok   " : "FAIL ") + "D allocation failure at each of " + steps + " steps of a build frame: " + bad + " leaking"
                    + (first == null ? "" : "; first: " + first));
        }

        // E: the size query records nothing (outside a frame, or inside a render pass); an input write inside a pass is refused
        total++;
        {
            RtAccel acc = new RtAccel(dev);
            long before = dev.allocatedBytes();
            Object img = dev.createImage("e", 8, 8, VK_FORMAT_R8G8B8A8_UNORM, false);
            long withImg = dev.allocatedBytes();
            dev.beginRendering(img, null, new float[4], 1f, true);
            long[] sz = acc.blasSizes(List.of(new RtAccel.Geometry(vb, 0, 16, 8, new float[12], true)), false);
            long grew = dev.allocatedBytes() - withImg;
            String refused = null;
            try {
                acc.quadIndices(100_000);   // a grow would record vkCmdUpdateBuffer + a barrier inside the pass
            } catch (IllegalStateException e) {
                refused = e.getMessage();
            }
            dev.endRendering();
            dev.flush();
            dev.destroyImage(img);
            acc.close();
            dev.flush();
            boolean ok = sz[0] > 0 && grew == 0 && refused != null && dev.allocatedBytes() == before;
            if (ok) pass++;
            System.out.println((ok ? "ok   " : "FAIL ") + "E size query inside a render pass: sizes " + sz[0] + "/" + sz[1] + ", device bytes it allocated " + grew
                    + ", in-pass index grow " + (refused == null ? "RECORDED" : "refused") + ", leftover " + (dev.allocatedBytes() - before));
        }

        dev.destroyBuffer(vb);
        helper.close();
        dev.flush();
        return new int[]{pass, total};
    }

    static RtAccel accelOf(VkRtBackend b) {
        try {
            java.lang.reflect.Field f = VkRtBackend.class.getDeclaredField("accel");
            f.setAccessible(true);
            return (RtAccel) f.get(b);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
