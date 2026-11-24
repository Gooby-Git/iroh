package net.vulkanmod.render.vertex;

import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.vulkan.memory.buffer.IndexBuffer;
import net.vulkanmod.vulkan.memory.buffer.index.AutoIndexBuffer;
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless checks for the upstream VulkanMod back-ports of card P2-13 that can run without a device.
 * {@code ./gradlew upstreamBackportTest}; last line "# backport PASS n/n".
 * <ul>
 *   <li>babff0c: growing a TerrainBuilder's index buffer reallocates the index block itself (the base grew an unused
 *   field, so sorted indices overran the old block).</li>
 *   <li>RQ-P213-c (R-P213BC): a translucent section past 10922 quads keeps 16-bit indices sized indexCount * 2 (the type
 *   follows the vertex range, not the index count); past 16384 quads it falls back to unsorted sequential indices.</li>
 *   <li>RQ-P213-b (R-P213BC): builder threads survive a throwing task; stopThreads cannot hang on a missed wakeup.</li>
 *   <li>7a1e520: LINES index data past the 16-bit limit is 32-bit and in the quad-per-segment pattern.</li>
 * </ul>
 */
public final class UpstreamBackportTest {
    private static final List<String> FAILS = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        terrainIndexGrowth();
        lineIndices();
        translucentIndexType();
        checks += net.vulkanmod.render.chunk.build.task.BuilderThreadTest.run(FAILS);
        System.out.println(FAILS.isEmpty() ? "# backport PASS " + checks + "/" + checks
                : "# backport FAIL " + FAILS.size() + " of " + checks + ": " + FAILS);
        if (!FAILS.isEmpty())
            System.exit(1);
    }

    private static void terrainIndexGrowth() throws Exception {
        PipelineManager.terrainVertexFormat = CustomVertexFormat.COMPRESSED_TERRAIN;
        TerrainBuilder builder = new TerrainBuilder(64);
        long before = builder.indexBufferPtr;
        int grown = 1 << 20; // a 64-byte block cannot grow in place to 1 MiB with glibc or jemalloc: realloc must move it

        Method ensure = TerrainBuilder.class.getDeclaredMethod("ensureIndexCapacity", int.class);
        ensure.setAccessible(true);
        ensure.invoke(builder, grown);

        long after = builder.indexBufferPtr;
        check("index block reallocated when the capacity grows (was 0x" + Long.toHexString(before) + ", now 0x"
                + Long.toHexString(after) + ")", after != 0L && after != before);
        if (after != 0L && after != before) {
            int capacity = indexCapacity(builder);
            check("capacity covers the request (" + capacity + " >= " + grown + ")", capacity >= grown);
            MemoryUtil.memSet(after, 0x5A, capacity); // inside the new block only when the fix is in
            check("grown block is writable end to end", MemoryUtil.memGetByte(after + capacity - 1) == 0x5A);
            builder.free(); // must not crash on the grown block (a freed-twice or foreign pointer would abort here)
        }
    }

    private static int indexCapacity(TerrainBuilder builder) throws Exception {
        var f = TerrainBuilder.class.getDeclaredField("indexBufferCapacity");
        f.setAccessible(true);
        return f.getInt(builder);
    }

    private static void lineIndices() {
        int vertexCount = 70_000; // > 65536: the auto buffer switches to UINT32
        var type = AutoIndexBuffer.indexTypeFor(AutoIndexBuffer.DrawType.LINES, vertexCount);
        check("LINES past 65536 vertices use UINT32", type == IndexBuffer.IndexType.UINT32);
        check("LINES below 65536 vertices use UINT16",
                AutoIndexBuffer.indexTypeFor(AutoIndexBuffer.DrawType.LINES, 40_000) == IndexBuffer.IndexType.UINT16);
        ByteBuffer wide = AutoIndexBuffer.generate(AutoIndexBuffer.DrawType.LINES, type, vertexCount);
        try {
            int indexCount = AutoIndexBuffer.roundUpToDivisible(vertexCount * 3 / 2, 6);
            check("UINT32 line data is 4 bytes per index (" + wide.capacity() + " == " + indexCount * 4 + ")",
                    wide.capacity() == indexCount * 4);
            var ints = wide.asIntBuffer();
            int segment = 65_536 / 4; // first segment whose vertices need more than 16 bits
            int j = segment * 6;
            int v = segment * 4;
            check("segment past 16 bits keeps full vertex numbers",
                    ints.get(j) == v && ints.get(j + 1) == v + 1 && ints.get(j + 2) == v + 2
                            && ints.get(j + 3) == v + 3 && ints.get(j + 4) == v + 2 && ints.get(j + 5) == v + 1);
        } finally {
            MemoryUtil.memFree(wide);
        }

        ByteBuffer narrow = AutoIndexBuffer.generate(AutoIndexBuffer.DrawType.LINES, IndexBuffer.IndexType.UINT16, 400);
        try {
            check("UINT16 line data unchanged (2 bytes per index)", narrow.capacity() == AutoIndexBuffer.roundUpToDivisible(600, 6) * 2);
        } finally {
            MemoryUtil.memFree(narrow);
        }
    }

    private static QuadSorter.SortState sortState(int quads) {
        var points = new org.joml.Vector3f[quads];
        for (int i = 0; i < quads; i++)
            points[i] = new org.joml.Vector3f(i, 0, 0); // quad i sits at x = i: sorting from x = quads puts quad 0 (farthest) first
        return new QuadSorter.SortState(quads * 4, points, new float[quads], new int[quads]);
    }

    private static TerrainBuilder.DrawState sorted(TerrainBuilder b, int quads) {
        b.begin();
        b.restoreSortState(sortState(quads));
        b.setupQuadSorting(quads, 0, 0);
        var d = b.endDrawing();
        return d;
    }

    private static void translucentIndexType() {
        PipelineManager.terrainVertexFormat = CustomVertexFormat.COMPRESSED_TERRAIN;
        for (int quads : new int[]{10_922, 10_923, 12_000, 16_384}) {
            TerrainBuilder b = new TerrainBuilder(64);
            var d = sorted(b, quads);
            String tag = quads + " quads: ";
            check(tag + "sorted (not sequential)", !d.sequentialIndex());
            check(tag + "index type is 16-bit (vertex numbers < 65536), was " + d.indexType(),
                    d.indexType() == com.mojang.blaze3d.vertex.VertexFormat.IndexType.SHORT);
            ByteBuffer ib = b.getIndexBuffer();
            check(tag + "uploaded " + ib.remaining() + " bytes == indexCount * 2 = " + d.indexCount() * 2L,
                    ib.remaining() == d.indexCount() * 2L);
            // data as the 16-bit draw reads it: farthest quad first (back to front), six indices per quad
            var sh = ib.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
            int last = quads - 1;
            int n = d.indexCount();
            boolean ok = sh.get(0) == 0 && sh.get(1) == 1 && sh.get(2) == 2 && sh.get(3) == 2 && sh.get(4) == 3 && sh.get(5) == 0
                    && sh.get(n - 6) == (short) (last * 4) && sh.get(n - 2) == (short) (last * 4 + 3) && sh.get(n - 1) == (short) (last * 4);
            check(tag + "16-bit data readable end to end", ok);
            b.free();
        }

        // 16-bit indices are 2 bytes each: the sorter wrote 4 bytes per index (memPutInt at a 2-byte stride), so the last
        // index of the last quad stored 2 bytes past indexCount * 2
        int exact = 4;
        TerrainBuilder tight = new TerrainBuilder(exact * 12 + 8);
        MemoryUtil.memSet(tight.indexBufferPtr, 0x5A, exact * 12 + 8);
        sorted(tight, exact);
        check("sorted 16-bit write stays inside indexCount * 2 bytes (byte after = 0x"
                        + Integer.toHexString(MemoryUtil.memGetByte(tight.indexBufferPtr + exact * 12L) & 0xFF) + ")",
                MemoryUtil.memGetByte(tight.indexBufferPtr + exact * 12L) == 0x5A
                        && MemoryUtil.memGetByte(tight.indexBufferPtr + exact * 12L + 1) == 0x5A);
        tight.free();

        // past 16384 quads no 16-bit index can name every vertex: unsorted sequential indices (auto quad buffer)
        TerrainBuilder big = new TerrainBuilder(64);
        var d = sorted(big, 17_000);
        check("17000 quads fall back to sequential indices", d.sequentialIndex());
        check("17000 quads: indexCount stays vertexCount / 4 * 6 = " + d.indexCount(), d.indexCount() == 17_000 * 6);
        big.free();
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok)
            FAILS.add(what);
    }
}
