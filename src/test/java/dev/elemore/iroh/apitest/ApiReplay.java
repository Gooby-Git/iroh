package dev.elemore.iroh.apitest;

import dev.elemore.iroh.api.*;
import dev.elemore.iroh.gpu.GpuCore;
import dev.elemore.iroh.gpu.HeadlessGpuDevice;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;

import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS;

/**
 * api 4 exerciser on lavapipe (card V-M1): the test bed Vista's M2/M3 run on. No game, no GPU: HeadlessGpuDevice refuses
 * every non-CPU device and caps memory at 1 GiB; the Gradle task restricts the loader to the lavapipe ICD.
 * <p>
 * Through the PUBLIC api 4 entry points: buffers + uploads, a compute "cull" that writes VkDrawIndexedIndirectCommands
 * and a count, IrohDraw.multiDrawIndirectCount into an IrohTargets colour + depth pair (depth test LESS, overlapping
 * quads), readback. Checks: every pixel equals a CPU reference of the same scene; the image is identical with the device
 * feature drawIndirectCount and with Iroh's zero-tail fallback; a golden hash; owner accounting and free-on-owner-death
 * (device memory back to its start). {@code ./gradlew apiReplay}; last line "# apireplay PASS n/n".
 */
public final class ApiReplay {
    static int pass, fail;
    static final int W = 64, H = 64, GRID = 8, N = GRID * GRID + 1;
    static final String OWNER = "apireplay";

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    static final String CULL = """
            #version 450
            layout(local_size_x = 64) in;
            layout(std430, set = 0, binding = 0) readonly buffer Quads { vec4 q[]; };
            layout(std430, set = 0, binding = 1) buffer Cmds { uint cmd[]; };
            layout(std430, set = 0, binding = 2) buffer Count { uint count; };
            layout(push_constant) uniform P { uint n; } p;
            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= p.n || i % 3u == 0u) return;           // "culled": every third quad
                uint b = atomicAdd(count, 1u) * 5u;
                cmd[b] = 6u; cmd[b + 1u] = 1u; cmd[b + 2u] = 0u; cmd[b + 3u] = 0u; cmd[b + 4u] = i;
            }
            """;
    static final String VS = """
            #version 450
            layout(std430, set = 0, binding = 0) readonly buffer Quads { vec4 q[]; };
            layout(std430, set = 0, binding = 1) readonly buffer Colors { uint c[]; };
            layout(location = 0) out vec4 col;
            void main() {
                vec4 r = q[gl_InstanceIndex];
                uint v = uint(gl_VertexIndex);
                vec2 corner = vec2((v == 1u || v == 2u) ? 1.0 : 0.0, v >= 2u ? 1.0 : 0.0);
                vec2 p = r.xy + corner * r.z;
                gl_Position = vec4(p * 2.0 - 1.0, r.w, 1.0);
                col = unpackUnorm4x8(c[gl_InstanceIndex]);
            }
            """;
    static final String FS = """
            #version 450
            layout(location = 0) in vec4 col;
            layout(location = 0) out vec4 o;
            void main() { o = col; }
            """;

    static float[][] quads() {
        float[][] q = new float[N][];
        for (int i = 0; i < GRID * GRID; i++)
            q[i] = new float[]{(i % GRID) / (float) GRID, (i / GRID) / (float) GRID, 1f / GRID, (i & 1) == 0 ? 0.3f : 0.7f};
        q[GRID * GRID] = new float[]{0.25f, 0.25f, 0.5f, 0.5f}; // a big quad between the two grid depths
        return q;
    }

    static int color(int i) {
        int h = i * 0x9E3779B1;
        return 0xFF000000 | (h >>> 8);
    }

    /** CPU reference: per pixel the nearest visible quad (depth LESS, clear 1.0), clear colour black-transparent. */
    static int[] reference(float[][] q) {
        int[] img = new int[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                float px = (x + 0.5f) / W, py = (y + 0.5f) / H, best = 1f;
                int c = 0;
                for (int i = 0; i < N; i++) {
                    if (i % 3 == 0) continue;
                    float[] r = q[i];
                    if (px >= r[0] && px < r[0] + r[2] && py >= r[1] && py < r[1] + r[2] && r[3] < best) {
                        best = r[3];
                        c = color(i);
                    }
                }
                img[y * W + x] = c;
            }
        return img;
    }

    static int[] run(boolean forceFallback, StringBuilder hash, long[] leftover) throws Exception {
        HeadlessGpuDevice dev = new HeadlessGpuDevice(1L << 30, forceFallback);
        long before = dev.allocatedBytes();
        GpuCore core = GpuCore.install(dev);
        core.resize(W, H);
        try {
            float[][] q = quads();
            ByteBuffer qb = MemoryUtil.memAlloc(N * 16).order(ByteOrder.LITTLE_ENDIAN);
            for (float[] r : q) for (float f : r) qb.putFloat(f);
            qb.flip();
            ByteBuffer cb = MemoryUtil.memAlloc(N * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < N; i++) cb.putInt(color(i));
            cb.flip();
            ByteBuffer ib = MemoryUtil.memAlloc(12).order(ByteOrder.LITTLE_ENDIAN);
            for (short s : new short[]{0, 1, 2, 2, 3, 0}) ib.putShort(s);
            ib.flip();
            ByteBuffer zero = MemoryUtil.memCalloc(N * 20);

            GpuBuffer quads = IrohBuffers.create(OWNER, "quads", N * 16, IrohBuffers.STORAGE);
            GpuBuffer colors = IrohBuffers.create(OWNER, "colors", N * 4, IrohBuffers.STORAGE);
            GpuBuffer index = IrohBuffers.create(OWNER, "index", 12, IrohBuffers.INDEX);
            GpuBuffer cmds = IrohBuffers.create(OWNER, "cmds", N * 20, IrohBuffers.INDIRECT);
            GpuBuffer count = IrohBuffers.create(OWNER, "count", 4, IrohBuffers.INDIRECT | IrohBuffers.STORAGE);
            IrohBuffers.upload(quads, 0, qb);
            IrohBuffers.upload(colors, 0, cb);
            IrohBuffers.upload(index, 0, ib);
            IrohBuffers.upload(cmds, 0, zero);
            IrohBuffers.upload(count, 0, zero.slice(0, 4));

            ComputePipelineHandle cull = IrohCompute.pipeline(OWNER, "cull", CULL, 3, 4);
            ByteBuffer push = MemoryUtil.memAlloc(4).order(ByteOrder.LITTLE_ENDIAN);
            push.putInt(N).flip();
            IrohCompute.dispatch(cull, new IrohCompute.Binding[]{IrohCompute.Binding.read(quads), IrohCompute.Binding.write(cmds),
                    IrohCompute.Binding.readWrite(count)}, push, (N + 63) / 64, 1, 1);

            RenderTargetHandle color = IrohTargets.create(OWNER, "color", IrohTargets.Format.RGBA8, W, H);
            RenderTargetHandle depth = IrohTargets.create(OWNER, "depth", IrohTargets.Format.DEPTH32F, W, H);
            IndirectPipelineHandle draw = IrohDraw.indirectPipeline(OWNER, "quads", VS, FS, 2, 0, 0, IrohTargets.Format.RGBA8,
                    IrohTargets.Format.DEPTH32F, VK_COMPARE_OP_LESS, true, false);
            IrohDraw.prepareIndirectCount(cmds, 0, count, 0, N, 20);
            IrohTargets.beginRendering(color, depth, true, new float[]{0, 0, 0, 0}, 1f, quads, colors, index, cmds, count);
            IrohDraw.multiDrawIndirectCount(draw, new IrohCompute.Binding[]{IrohCompute.Binding.read(quads), IrohCompute.Binding.read(colors)},
                    null, null, index, false, cmds, 0, count, 0, N, 20);
            IrohTargets.endRendering();
            ByteBuffer px = IrohTargets.readback(color).order(ByteOrder.LITTLE_ENDIAN);
            int[] img = new int[W * H];
            for (int i = 0; i < W * H; i++) img[i] = px.getInt(i * 4);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            px.rewind();
            md.update(px);
            for (byte b : md.digest()) hash.append(String.format("%02x", b));

            int[] counts = {core.ownerResources(OWNER)};
            long held = core.ownerBytes(OWNER);
            check((forceFallback ? "fallback" : "drawIndirectCount") + ": owner holds " + counts[0] + " objects, " + held + " bytes accounted",
                    counts[0] == 9 && held == N * 16 + N * 4 + 12 + N * 20 + 4 + 2L * W * H * 4);
            int freed = core.freeOwner(OWNER);
            dev.flush();
            leftover[0] = dev.allocatedBytes() - before;
            check((forceFallback ? "fallback" : "drawIndirectCount") + ": owner death frees all " + freed + " objects (device memory back: "
                    + leftover[0] + " bytes left)", freed == 9 && core.ownerResources(OWNER) == 0 && !quads.alive() && leftover[0] == 0);
            MemoryUtil.memFree(qb);
            MemoryUtil.memFree(cb);
            MemoryUtil.memFree(ib);
            MemoryUtil.memFree(zero);
            MemoryUtil.memFree(push);
            System.out.println("     device " + dev.name() + ", drawIndirectCount " + dev.supportsDrawIndirectCount());
            return img;
        } finally {
            GpuCore.uninstall();
            dev.close();
        }
    }

    /** {total barriers, ALL->ALL barriers} recorded by one frame of three passes */
    static int[] countBarriers() {
        HeadlessGpuDevice dev = new HeadlessGpuDevice(1L << 30, false);
        GpuCore core = GpuCore.install(dev);
        core.resize(W, H);
        try {
            RenderTargetHandle a = IrohTargets.create(OWNER, "bcA", IrohTargets.Format.RGBA8, W, H);
            RenderTargetHandle b = IrohTargets.create(OWNER, "bcB", IrohTargets.Format.RGBA8, W, H);
            dev.flush();
            long t0 = dev.barriers, g0 = dev.globalBarriers;
            for (RenderTargetHandle t : new RenderTargetHandle[]{a, b, a}) {
                IrohTargets.beginRendering(t, null, true, new float[]{0, 0, 0, 0}, 1f);
                IrohTargets.endRendering();
            }
            dev.flush();
            return new int[]{(int) (dev.barriers - t0), (int) (dev.globalBarriers - g0)};
        } finally {
            core.freeOwner(OWNER);
            GpuCore.uninstall();
            dev.close();
        }
    }

    public static void main(String[] args) throws Exception {
        int[] ref = reference(quads());
        StringBuilder h1 = new StringBuilder(), h2 = new StringBuilder();
        long[] left = new long[1];
        int[] a = run(false, h1, left);
        int bad = 0;
        for (int i = 0; i < ref.length; i++) if (a[i] != ref[i]) bad++;
        check("compute cull + multiDrawIndirectCount: every pixel equals the CPU reference (" + bad + " of " + ref.length + " differ)", bad == 0);
        int[] b = run(true, h2, left);
        int diff = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) diff++;
        check("zero-tail fallback (no drawIndirectCount) draws the same image (" + diff + " pixels differ)", diff == 0);
        // P2-12 (RQ-APP1-a): barriers per frame of a 3-pass api 4 frame (two targets, the second pass samples nothing but reuses the first's colour)
        int[] bc = countBarriers();
        boolean legacy = Boolean.getBoolean("iroh.api4.legacyTargetBarrier");
        System.out.println("     barriers per 3-pass frame: total " + bc[0] + ", ALL_COMMANDS->ALL_COMMANDS " + bc[1] + (legacy ? " (legacy path)" : ""));
        check("no ALL_COMMANDS -> ALL_COMMANDS barrier per frame" + (legacy ? " (legacy negative control: expected " + bc[0] + " > 0)" : ""),
                legacy ? bc[1] > 0 : bc[1] == 0);
        String golden = System.getProperty("golden", "");
        System.out.println("     image sha256 " + h1);
        if (!golden.isEmpty()) check("golden " + golden, golden.contentEquals(h1));
        System.out.println("# apireplay " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
