package dev.elemore.iroh.vktest;

import net.vulkanmod.render.chunk.buffer.TerrainCommandWriter;
import net.vulkanmod.render.chunk.buffer.WorldArena;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.shader.ShaderLoadUtil;
import net.vulkanmod.render.vertex.VertexBuilder;
import net.vulkanmod.vulkan.device.IndirectDrawCaps;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.security.MessageDigest;
import java.util.*;

import static org.lwjgl.vulkan.VK12.*;

/**
 * P2-7a (L5a): world-arena terrain (one vertex bind + multi-draw-indirect per page and layer) against VulkanMod's per-area
 * path, offline on lavapipe + VK_LAYER_KHRONOS_validation. ./gradlew terrainWorldReplay [-Pvalidate=gpuav] [-Ptw.quick]
 *
 * <p>Scene: a generated section set (height-map terrain with side faces, cave rooms under ground, cross-quad plants and
 * glass cubes alpha-tested, water with sorted 16-bit indices), compressed 16-byte terrain vertex, per-section tint so a
 * misplaced section changes the image. Visible set = sections in the frustum, nearest first; chunk areas in order of
 * first appearance (SectionGraph / AreaSetQueue rule); translucent iterates areas and sections in reverse.
 *
 * <p>Per-area path, as WorldRenderer.renderSectionLayer + DrawBuffers: per area one buffer per layer (sections packed),
 * DrawParametersBuffer layout, baseInstance = DrawBuffers.encodeSectionOffset, then per drawing area: vertex bind, push
 * constant (area origin + POS_OFFSET - camera), UBO write + descriptor bind (uploadAndBindUBOs), commands as
 * buildDrawBatchesIndirect (stride 32, back-face facing masks) copied into the indirect buffer, vkCmdDrawIndexedIndirect;
 * production terrain.vsh. World path, as WorldRenderer.drawWorldLayer: PRODUCTION WorldArena pages and
 * TerrainCommandWriter (addSection, buckets / runs, firstInstance encoding), production terrain_world.vsh, push constant
 * and UBOs once per layer, then per draw a vertex (and index) bind + vkCmdDrawIndexedIndirect.
 *
 * <p>Checks: (1) images identical (SHA-256) on a camera with exactly representable coordinates, one page per layer;
 * (2) identical with many small pages (buckets, translucent runs, index pages) and draws split at a small
 * maxDrawIndirectCount; (3) a camera with arbitrary fractions: differing pixels reported (float rounding of the
 * per-frame origin vs the area origin; bound 0.5%); (4) negative control: world commands drawn with terrain.vsh must
 * DIFFER. Zero validation errors (GPU-AV under -Pvalidate=gpuav sees the indirect buffer contents). (5) CPU record
 * cost per frame of both paths at RD24 and RD32 (no validation instance; draws, binds, ns): measured on lavapipe's
 * command recording, an estimate of the driver-call share on real drivers.
 * Last line "# terrainworld PASS|FAIL|SKIP ...".
 */
public final class TerrainWorldReplay {
    static final int W = 256, H = 256;
    static final int LAYERS = 5, FACINGS = 7, AREA_SECTIONS = 512, PSTRIDE = 16, OLD_STRIDE = 32;
    static final int L_CUTOUT_MIPPED = 1, L_CUTOUT = 2, L_TRANSLUCENT = 3;
    /** drawn in this order (TerrainRenderType COMPACT/SEMI_COMPACT sets: solid geometry is meshed into cutout_mipped) */
    static final int[] DRAWN = {L_CUTOUT_MIPPED, L_CUTOUT, L_TRANSLUCENT};
    static final float[] ALPHA_CUTOUT = {0f, 0.5f, 0.1f, 0f, 0.1f}; // TerrainRenderType alphaCutout
    static final float POS_BASE = -VertexBuilder.CompressedVertexBuilder.POS_OFFSET; // = DrawBuffers.POS_OFFSET (4)
    static final int MIN_Y = -64, MIN_SEC = -4, SECTIONS_Y = 24, SEA = 62;
    static final String MODE = System.getProperty("ti.vkval", "core");
    static final boolean QUICK = Boolean.getBoolean("tw.quick");

    // ------------------------------------------------------------------------------------------------ scene

    static final class Sec {
        final int sx, sy, sz; // section coordinates
        final int[][] quads = new int[LAYERS][FACINGS];
        ByteBuffer[][] verts; // [layer][facing], null = none (timing scenes keep counts only)
        ByteBuffer sortedIdx;  // translucent sorted indices, 16-bit
        final int tint;

        Sec(int sx, int sy, int sz) {
            this.sx = sx; this.sy = sy; this.sz = sz;
            this.tint = (int) (mix(sx * 73856093L ^ sy * 19349663L ^ sz * 83492791L) & 0x3F3F3F);
        }

        int vertexCount(int l, int f) { return quads[l][f] * 4; }
        boolean any() { for (int[] l : quads) for (int q : l) if (q > 0) return true; return false; }
    }

    static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    static int height(int x, int z) {
        return 60 + (int) Math.floor(10 * Math.sin(x * 0.071) + 8 * Math.cos(z * 0.053) + 5 * Math.sin((x + z) * 0.13));
    }

    static int hash(int x, int y, int z) {
        return (int) (mix(x * 0x9E3779B97F4A7C15L + y * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L) & 0x7FFFFFFF);
    }

    /** builds quads of one section; data=false counts only */
    static final class Sink {
        final Sec s;
        final boolean data;
        final ByteBuffer[][] tmp = new ByteBuffer[LAYERS][FACINGS];

        Sink(Sec s, boolean data) { this.s = s; this.data = data; }

        void quad(int layer, int facing, float[] p, boolean alphaTex, int color) {
            s.quads[layer][facing]++;
            if (!data) return;
            ByteBuffer b = tmp[layer][facing];
            if (b == null || b.remaining() < 64) {
                ByteBuffer n = ByteBuffer.allocate(Math.max(4096, b == null ? 0 : b.capacity() * 2)).order(ByteOrder.nativeOrder());
                if (b != null) { b.flip(); n.put(b); }
                tmp[layer][facing] = b = n;
            }
            int light = ((facing + 3) & 15) << 4 | (15 - (s.sy & 7)) << 12;
            float u0 = alphaTex ? 0.5f : 0f, u1 = alphaTex ? 1f : 0.5f;
            float[][] uv = {{u0, 0}, {u1, 0}, {u1, 1}, {u0, 1}};
            for (int c = 0; c < 4; c++) {
                b.putShort(pos(p[c * 3])).putShort(pos(p[c * 3 + 1])).putShort(pos(p[c * 3 + 2])).putShort((short) light);
                b.putShort((short) Math.round(uv[c][0] * 32767f)).putShort((short) Math.round(uv[c][1] * 32767f));
                b.putInt(color);
            }
        }

        void finish() {
            if (!data) return;
            s.verts = new ByteBuffer[LAYERS][FACINGS];
            for (int l = 0; l < LAYERS; l++)
                for (int f = 0; f < FACINGS; f++) {
                    ByteBuffer b = tmp[l][f];
                    if (b == null) continue;
                    b.flip();
                    ByteBuffer n = MemoryUtil.memAlloc(b.remaining());
                    n.put(b).flip();
                    s.verts[l][f] = n;
                }
            int wq = s.quads[L_TRANSLUCENT][6];
            if (wq > 0) { // sorted: back to front = reverse quad order here (any permutation is a valid sort result)
                ByteBuffer ib = MemoryUtil.memAlloc(wq * 12);
                for (int q = wq - 1; q >= 0; q--) for (int v : new int[]{0, 1, 2, 2, 3, 0}) ib.putShort((short) (q * 4 + v));
                ib.flip();
                s.sortedIdx = ib;
            }
        }
    }

    /** section-local coordinate -> compressed int16 (CompressedVertexBuilder: x * 2048 + POS_OFFSET * 2048) */
    static short pos(float local) {
        return (short) Math.round((local + VertexBuilder.CompressedVertexBuilder.POS_OFFSET) * 2048f);
    }

    /** sections with geometry within render distance rd (columns around section 0,0) */
    static List<Sec> generate(int rd, boolean data) {
        List<Sec> out = new ArrayList<>();
        for (int cz = -rd; cz <= rd; cz++)
            for (int cx = -rd; cx <= rd; cx++) {
                int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
                for (int bz = -1; bz <= 16; bz++) for (int bx = -1; bx <= 16; bx++) {
                    int h = height(cx * 16 + bx, cz * 16 + bz);
                    minH = Math.min(minH, h); maxH = Math.max(maxH, h);
                }
                for (int sy = MIN_SEC; sy < MIN_SEC + SECTIONS_Y; sy++) {
                    int y0 = sy * 16;
                    if (y0 > maxH + 2 && y0 > SEA) continue;
                    Sec s = new Sec(cx, sy, cz);
                    Sink k = new Sink(s, data);
                    if (y0 + 15 >= minH - 1) surface(k, cx, sy, cz);
                    else if (y0 + 15 < minH - 3 && hash(cx, sy, cz) % 100 < 55) cave(k, cx, sy, cz);
                    if (s.any()) { k.finish(); out.add(s); }
                }
            }
        return out;
    }

    static void surface(Sink k, int cx, int sy, int cz) {
        int y0 = sy * 16;
        int color = 0xFF000000 | (0xC0C0C0 + k.s.tint);
        for (int bz = 0; bz < 16; bz++)
            for (int bx = 0; bx < 16; bx++) {
                int x = cx * 16 + bx, z = cz * 16 + bz, h = height(x, z);
                if (h >= y0 && h < y0 + 16) {
                    float t = h + 1 - y0;
                    k.quad(L_CUTOUT_MIPPED, 2, new float[]{bx, t, bz, bx, t, bz + 1, bx + 1, t, bz + 1, bx + 1, t, bz}, false, color);
                }
                int[][] nb = {{1, 0, 0}, {-1, 0, 1}, {0, 1, 4}, {0, -1, 5}}; // dx, dz, facing (X_POS, X_NEG, Z_POS, Z_NEG)
                for (int[] n : nb) {
                    int hn = height(x + n[0], z + n[1]);
                    for (int y = Math.max(hn + 1, y0); y <= Math.min(h, y0 + 15); y++) {
                        float a = y - y0, b = a + 1;
                        float[] p = switch (n[2]) {
                            case 0 -> new float[]{bx + 1, a, bz, bx + 1, b, bz, bx + 1, b, bz + 1, bx + 1, a, bz + 1};
                            case 1 -> new float[]{bx, a, bz, bx, a, bz + 1, bx, b, bz + 1, bx, b, bz};
                            case 4 -> new float[]{bx, a, bz + 1, bx + 1, a, bz + 1, bx + 1, b, bz + 1, bx, b, bz + 1};
                            default -> new float[]{bx, a, bz, bx, b, bz, bx + 1, b, bz, bx + 1, a, bz};
                        };
                        k.quad(L_CUTOUT_MIPPED, n[2], p, false, color);
                    }
                }
                int hb = hash(x, 0, z);
                if (h >= SEA && h + 1 >= y0 && h + 1 < y0 + 16) {
                    float yb = h + 1 - y0;
                    if (hb % 100 < 12) { // cross plant, alpha-tested, UNDEFINED facing
                        k.quad(L_CUTOUT_MIPPED, 6, new float[]{bx, yb, bz, bx + 1, yb, bz + 1, bx + 1, yb + 1, bz + 1, bx, yb + 1, bz}, true, 0xFF40C040);
                        k.quad(L_CUTOUT_MIPPED, 6, new float[]{bx + 1, yb, bz, bx, yb, bz + 1, bx, yb + 1, bz + 1, bx + 1, yb + 1, bz}, true, 0xFF40C040);
                    } else if ((hb >> 8) % 1000 < 15) { // glass cube (cutout layer), five faces
                        float a = yb, b = yb + 1;
                        int g = 0xFFE0E0FF;
                        k.quad(L_CUTOUT, 2, new float[]{bx, b, bz, bx, b, bz + 1, bx + 1, b, bz + 1, bx + 1, b, bz}, true, g);
                        k.quad(L_CUTOUT, 0, new float[]{bx + 1, a, bz, bx + 1, b, bz, bx + 1, b, bz + 1, bx + 1, a, bz + 1}, true, g);
                        k.quad(L_CUTOUT, 1, new float[]{bx, a, bz, bx, a, bz + 1, bx, b, bz + 1, bx, b, bz}, true, g);
                        k.quad(L_CUTOUT, 4, new float[]{bx, a, bz + 1, bx + 1, a, bz + 1, bx + 1, b, bz + 1, bx, b, bz + 1}, true, g);
                        k.quad(L_CUTOUT, 5, new float[]{bx, a, bz, bx, b, bz, bx + 1, b, bz, bx + 1, a, bz}, true, g);
                    }
                }
                if (h < SEA && SEA >= y0 && SEA < y0 + 16) { // water surface (top of block 62), translucent, sorted
                    float t = SEA + 1 - y0;
                    k.quad(L_TRANSLUCENT, 6, new float[]{bx, t, bz, bx, t, bz + 1, bx + 1, t, bz + 1, bx + 1, t, bz}, false, 0x993060E0 | (k.s.tint & 0x0F0F0F));
                }
            }
    }

    static void cave(Sink k, int cx, int sy, int cz) {
        int hh = hash(cx, sy, cz);
        int x0 = 2 + hh % 4, x1 = x0 + 4 + (hh >> 3) % 5, y0 = 2 + (hh >> 6) % 4, y1 = y0 + 3 + (hh >> 9) % 4, z0 = 2 + (hh >> 12) % 4, z1 = z0 + 4 + (hh >> 15) % 5;
        int color = 0xFF000000 | (0x606060 + k.s.tint);
        for (int a = x0; a < x1; a++) for (int b = z0; b < z1; b++) {
            k.quad(L_CUTOUT_MIPPED, 2, new float[]{a, y0, b, a, y0, b + 1, a + 1, y0, b + 1, a + 1, y0, b}, false, color); // floor, up
            k.quad(L_CUTOUT_MIPPED, 3, new float[]{a, y1, b, a + 1, y1, b, a + 1, y1, b + 1, a, y1, b + 1}, false, color); // ceiling, down
        }
        for (int y = y0; y < y1; y++) {
            for (int b = z0; b < z1; b++) {
                k.quad(L_CUTOUT_MIPPED, 0, new float[]{x0, y, b, x0, y + 1, b, x0, y + 1, b + 1, x0, y, b + 1}, false, color); // west wall faces +x
                k.quad(L_CUTOUT_MIPPED, 1, new float[]{x1, y, b, x1, y, b + 1, x1, y + 1, b + 1, x1, y + 1, b}, false, color);
            }
            for (int a = x0; a < x1; a++) {
                k.quad(L_CUTOUT_MIPPED, 4, new float[]{a, y, z0, a + 1, y, z0, a + 1, y + 1, z0, a, y + 1, z0}, false, color);
                k.quad(L_CUTOUT_MIPPED, 5, new float[]{a, y, z1, a, y + 1, z1, a + 1, y + 1, z1, a + 1, y, z1}, false, color);
            }
        }
    }

    static void freeScene(List<Sec> secs) {
        for (Sec s : secs) {
            if (s.verts != null) for (ByteBuffer[] l : s.verts) for (ByteBuffer b : l) if (b != null) MemoryUtil.memFree(b);
            if (s.sortedIdx != null) MemoryUtil.memFree(s.sortedIdx);
        }
    }

    // ------------------------------------------------------------------------------------------------ visibility + areas

    static final class Area {
        final int ax, ay, az, ox, oy, oz; // area coords, origin blocks
        final List<Sec> queue = new ArrayList<>();
        final List<Integer> inArea = new ArrayList<>();
        // per-area path
        final long[] vb = new long[LAYERS];
        final long[] vbMem = new long[LAYERS];
        long ib, ibMem;
        long params, wparams; // DrawParametersBuffer blocks (old, world)
        final byte[] iPage = new byte[LAYERS * AREA_SECTIONS];
        final byte[] pref = new byte[LAYERS + 1]; // DrawBuffers.prefPage
        final int[] layerRanges = new int[LAYERS]; // DrawBuffers.layerRanges

        Area(int ax, int ay, int az) {
            this.ax = ax; this.ay = ay; this.az = az;
            this.ox = ax * 128; this.oy = MIN_Y + ay * 128; this.oz = az * 128;
            Arrays.fill(iPage, WorldArena.NONE);
            Arrays.fill(pref, WorldArena.NONE);
        }
    }

    static long paramsPtr(long base, int sec, int layer, int facing) { // DrawParametersBuffer.getParamsPtr
        return base + (((long) (layer * AREA_SECTIONS + sec) * FACINGS) + facing) * PSTRIDE;
    }

    /** frame setup: frustum cull, nearest first, areas in order of first appearance */
    static List<Area> visibleAreas(List<Sec> secs, Matrix4f mvp, double cx, double cy, double cz, Map<Long, Area> all) {
        FrustumIntersection fr = new FrustumIntersection(mvp);
        List<Sec> vis = new ArrayList<>();
        for (Sec s : secs) {
            float x = (float) (s.sx * 16 - cx), y = (float) (s.sy * 16 - cy), z = (float) (s.sz * 16 - cz);
            if (fr.testAab(x, y, z, x + 16, y + 16, z + 16)) vis.add(s);
        }
        vis.sort(Comparator.comparingDouble(s -> sq(s.sx * 16 + 8 - cx) + sq(s.sy * 16 + 8 - cy) + sq(s.sz * 16 + 8 - cz)));
        List<Area> order = new ArrayList<>();
        Set<Area> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Sec s : vis) {
            Area a = areaOf(s, all);
            if (seen.add(a)) order.add(a);
            a.queue.add(s);
            int x = s.sx * 16, y = s.sy * 16, z = s.sz * 16;
            a.inArea.add(((x - a.ox) >> 4) + (((z - a.oz) >> 4) * 8 + ((y - a.oy) >> 4)) * 8); // SectionGrid.moveSection
        }
        return order;
    }

    static Area areaOf(Sec s, Map<Long, Area> all) {
        int ax = Math.floorDiv(s.sx * 16, 128), ay = Math.floorDiv(s.sy * 16 - MIN_Y, 128), az = Math.floorDiv(s.sz * 16, 128);
        long key = ((long) ax & 0xFFFFF) | ((long) ay & 0xFFFFF) << 20 | ((long) az & 0xFFFFF) << 40;
        return all.computeIfAbsent(key, k -> new Area(ax, ay, az));
    }

    static double sq(double v) { return v * v; }

    static int inAreaIndex(Area a, Sec s) {
        return ((s.sx * 16 - a.ox) >> 4) + (((s.sz * 16 - a.oz) >> 4) * 8 + ((s.sy * 16 - a.oy) >> 4)) * 8;
    }

    // ------------------------------------------------------------------------------------------------ device

    static final class Dev {
        VkDevice dev;
        VkQueue queue;
        VkPhysicalDeviceMemoryProperties mp;
        String tier;
        int maxDraws;
        final Deque<Runnable> undo = new ArrayDeque<>();
        long rp, fb, layout, dsl, dpool, set, sampler, pool;
        long[] color, depth, atlas, lightmap;
        long pipeOldOpaque, pipeOldTrans, pipeWorldOpaque, pipeWorldTrans;
        HostBuf ubo, indirect, quadsIdx, readback;
        VkCommandBuffer cmd;

        void close() { vkDeviceWaitIdle(dev); while (!undo.isEmpty()) undo.pop().run(); }

        /** destroy what was created after {@code mark} (undo is LIFO at the front) */
        void rollback(int mark) { vkDeviceWaitIdle(dev); while (undo.size() > mark) undo.pop().run(); }
    }

    record HostBuf(long buf, long mem, long ptr, long size) {}

    static HostBuf hostBuf(MemoryStack outer, Dev d, long size, int usage) {
        try (MemoryStack s = outer.push()) {
            LongBuffer lb = s.mallocLong(1);
            ck(vkCreateBuffer(d.dev, VkBufferCreateInfo.calloc(s).sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, lb), "buffer");
            long buf = lb.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(s);
            vkGetBufferMemoryRequirements(d.dev, buf, mr);
            long mem = TerrainIndirectRepro.alloc(s, d.dev, d.mp, mr, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            ck(vkBindBufferMemory(d.dev, buf, mem, 0), "bind");
            PointerBuffer p = s.mallocPointer(1);
            ck(vkMapMemory(d.dev, mem, 0, VK_WHOLE_SIZE, 0, p), "map");
            HostBuf h = new HostBuf(buf, mem, p.get(0), size);
            HOSTPTR.put(buf, h.ptr);
            VkDevice dev = d.dev;
            d.undo.push(() -> { vkDestroyBuffer(dev, buf, null); vkFreeMemory(dev, mem, null); });
            return h;
        }
    }

    static long[] image(MemoryStack outer, Dev d, int w, int h, int format, int usage, int aspect) {
        try (MemoryStack s = outer.push()) {
            LongBuffer lb = s.mallocLong(1);
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(s).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format).mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().set(w, h, 1);
            ck(vkCreateImage(d.dev, ci, null, lb), "image");
            long img = lb.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(s);
            vkGetImageMemoryRequirements(d.dev, img, mr);
            long mem = TerrainIndirectRepro.alloc(s, d.dev, d.mp, mr, 0);
            ck(vkBindImageMemory(d.dev, img, mem, 0), "bindImage");
            VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(s).sType$Default().image(img).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
            vi.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1);
            ck(vkCreateImageView(d.dev, vi, null, lb), "view");
            long view = lb.get(0);
            VkDevice dev = d.dev;
            d.undo.push(() -> { vkDestroyImageView(dev, view, null); vkDestroyImage(dev, img, null); vkFreeMemory(dev, mem, null); });
            return new long[]{img, view};
        }
    }

    static Dev device(MemoryStack outer, VkPhysicalDevice phys, ByteBuffer[] code, int maxDrawOverride) {
        Dev d = new Dev();
        try (MemoryStack s = outer.push()) {
            VkPhysicalDeviceVulkan12Features a12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan11Features a11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default().pNext(a12.address());
            VkPhysicalDeviceFeatures2 a2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(a11.address());
            vkGetPhysicalDeviceFeatures2(phys, a2);
            VkPhysicalDeviceVulkan12Features e12 = VkPhysicalDeviceVulkan12Features.calloc(s).sType$Default();
            VkPhysicalDeviceVulkan11Features e11 = VkPhysicalDeviceVulkan11Features.calloc(s).sType$Default().pNext(e12.address());
            VkPhysicalDeviceFeatures2 e2 = VkPhysicalDeviceFeatures2.calloc(s).sType$Default().pNext(e11.address());
            IndirectDrawCaps.applyDrawCount(a12, e12); // P2-7b: production step (DeviceManager): drawIndirectCount where offered
            IndirectDrawCaps.TerrainTier t = IndirectDrawCaps.apply(a2.features(), a11, e2.features(), e11); // production device step
            d.tier = t.name();
            if (t != IndirectDrawCaps.TerrainTier.INDIRECT) return d;
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(s);
            vkGetPhysicalDeviceProperties(phys, props);
            d.maxDraws = maxDrawOverride > 0 ? maxDrawOverride : props.limits().maxDrawIndirectCount();
            VkDeviceQueueCreateInfo.Buffer q = VkDeviceQueueCreateInfo.calloc(1, s).sType$Default().queueFamilyIndex(0).pQueuePriorities(s.floats(1f));
            PointerBuffer pp = s.mallocPointer(1);
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(s).sType$Default().pQueueCreateInfos(q).pNext(e2.address());
            ck(vkCreateDevice(phys, dci, null, pp), "vkCreateDevice");
            d.dev = new VkDevice(pp.get(0), phys, dci);
            VkDevice dev = d.dev;
            d.undo.push(() -> vkDestroyDevice(dev, null));
            vkGetDeviceQueue(dev, 0, 0, pp);
            d.queue = new VkQueue(pp.get(0), dev);
            d.mp = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(phys, d.mp);
            VkPhysicalDeviceMemoryProperties mpf = d.mp;
            d.undo.addLast(mpf::free);
            LongBuffer lb = s.mallocLong(1);

            d.color = image(s, d, W, H, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
            d.depth = image(s, d, W, H, VK_FORMAT_D32_SFLOAT, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
            d.atlas = image(s, d, 16, 16, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
            d.lightmap = image(s, d, 16, 16, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
            ck(vkCreateSampler(dev, VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0f), null, lb), "sampler");
            d.sampler = lb.get(0);
            long smp = d.sampler;
            d.undo.push(() -> vkDestroySampler(dev, smp, null));

            // terrain.json bindings; UBOs dynamic as Iroh's descriptor sets (UBO.getType)
            VkDescriptorSetLayoutBinding.Buffer bind = VkDescriptorSetLayoutBinding.calloc(4, s);
            bind.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            bind.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            bind.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            bind.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            ck(vkCreateDescriptorSetLayout(dev, VkDescriptorSetLayoutCreateInfo.calloc(s).sType$Default().pBindings(bind), null, lb), "dsl");
            d.dsl = lb.get(0);
            long dsl = d.dsl;
            d.undo.push(() -> vkDestroyDescriptorSetLayout(dev, dsl, null));
            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(2, s);
            ps.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(2);
            ps.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
            ck(vkCreateDescriptorPool(dev, VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(1).pPoolSizes(ps), null, lb), "dpool");
            d.dpool = lb.get(0);
            long dpool = d.dpool;
            d.undo.push(() -> vkDestroyDescriptorPool(dev, dpool, null));
            ck(vkAllocateDescriptorSets(dev, VkDescriptorSetAllocateInfo.calloc(s).sType$Default().descriptorPool(dpool).pSetLayouts(s.longs(dsl)), lb), "dset");
            d.set = lb.get(0);

            d.ubo = hostBuf(s, d, 8L << 20, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            d.indirect = hostBuf(s, d, 96L << 20, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
            d.readback = hostBuf(s, d, (long) W * H * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            int maxQuads = 1 << 14; // 65536 vertices: 16-bit
            d.quadsIdx = hostBuf(s, d, maxQuads * 12L, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            for (int qd = 0; qd < maxQuads; qd++) for (int v = 0; v < 6; v++)
                MemoryUtil.memPutShort(d.quadsIdx.ptr + (qd * 6L + v) * 2, (short) (qd * 4 + new int[]{0, 1, 2, 2, 3, 0}[v]));

            VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(4, s);
            wr.get(0).sType$Default().dstSet(d.set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, s).buffer(d.ubo.buf).offset(0).range(80));
            wr.get(1).sType$Default().dstSet(d.set).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1, s).buffer(d.ubo.buf).offset(0).range(32));
            wr.get(2).sType$Default().dstSet(d.set).dstBinding(2).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(d.sampler).imageView(d.atlas[1]).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            wr.get(3).sType$Default().dstSet(d.set).dstBinding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, s).sampler(d.sampler).imageView(d.lightmap[1]).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            vkUpdateDescriptorSets(dev, wr, null);

            // pass: colour + depth
            VkAttachmentDescription.Buffer at = VkAttachmentDescription.calloc(2, s);
            at.get(0).format(VK_FORMAT_R8G8B8A8_UNORM).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            at.get(1).format(VK_FORMAT_D32_SFLOAT).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer sp = VkSubpassDescription.calloc(1, s).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1)
                    .pColorAttachments(VkAttachmentReference.calloc(1, s).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL))
                    .pDepthStencilAttachment(VkAttachmentReference.calloc(s).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL));
            VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(2, s);
            int fragTests = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            dep.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | fragTests).dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | fragTests)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT);
            dep.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            ck(vkCreateRenderPass(dev, VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(at).pSubpasses(sp).pDependencies(dep), null, lb), "renderpass");
            d.rp = lb.get(0);
            long rp = d.rp;
            d.undo.push(() -> vkDestroyRenderPass(dev, rp, null));
            ck(vkCreateFramebuffer(dev, VkFramebufferCreateInfo.calloc(s).sType$Default().renderPass(rp).pAttachments(s.longs(d.color[1], d.depth[1])).width(W).height(H).layers(1), null, lb), "fb");
            d.fb = lb.get(0);
            long fbo = d.fb;
            d.undo.push(() -> vkDestroyFramebuffer(dev, fbo, null));
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, s).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(12);
            ck(vkCreatePipelineLayout(dev, VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(dsl)).pPushConstantRanges(pcr), null, lb), "layout");
            d.layout = lb.get(0);
            long lay = d.layout;
            d.undo.push(() -> vkDestroyPipelineLayout(dev, lay, null));
            long vsOld = TerrainIndirectRepro.module(s, dev, code[0]), vsWorld = TerrainIndirectRepro.module(s, dev, code[1]);
            long fsOpaque = TerrainIndirectRepro.module(s, dev, code[2]), fsTrans = TerrainIndirectRepro.module(s, dev, code[3]);
            d.undo.push(() -> { for (long m : new long[]{vsOld, vsWorld, fsOpaque, fsTrans}) vkDestroyShaderModule(dev, m, null); });
            d.pipeOldOpaque = pipeline(s, d, vsOld, fsOpaque, false);
            d.pipeOldTrans = pipeline(s, d, vsOld, fsTrans, true);
            d.pipeWorldOpaque = pipeline(s, d, vsWorld, fsOpaque, false);
            d.pipeWorldTrans = pipeline(s, d, vsWorld, fsTrans, true);

            ck(vkCreateCommandPool(dev, VkCommandPoolCreateInfo.calloc(s).sType$Default().flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(0), null, lb), "pool");
            d.pool = lb.get(0);
            long pool = d.pool;
            d.undo.push(() -> vkDestroyCommandPool(dev, pool, null));
            ck(vkAllocateCommandBuffers(dev, VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pp), "cb");
            d.cmd = new VkCommandBuffer(pp.get(0), dev);
            uploadTextures(s, d);
        }
        return d;
    }

    /**
     * P2-7b: rasterizer culling of the replay pipelines. P2-7a draws without (NONE); terrainCullReplay sets BACK like the
     * game's terrain render types (quads here are counter-clockwise around their facing normal in world space; the
     * GL-style projection without a y-flipped viewport makes front faces clockwise on screen).
     */
    static int CULL_MODE = VK_CULL_MODE_NONE, FRONT_FACE = VK_FRONT_FACE_COUNTER_CLOCKWISE;

    static long pipeline(MemoryStack outer, Dev d, long vs, long fs, boolean blend) {
        try (MemoryStack s = outer.push()) {
            VkPipelineShaderStageCreateInfo.Buffer st = VkPipelineShaderStageCreateInfo.calloc(2, s);
            st.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vs).pName(s.UTF8("main"));
            st.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fs).pName(s.UTF8("main"));
            VkVertexInputAttributeDescription.Buffer attr = VkVertexInputAttributeDescription.calloc(3, s);
            attr.get(0).location(0).binding(0).format(VK_FORMAT_R16G16B16A16_SINT).offset(0);
            attr.get(1).location(1).binding(0).format(VK_FORMAT_R16G16_UINT).offset(8);
            attr.get(2).location(2).binding(0).format(VK_FORMAT_R32_UINT).offset(12);
            VkPipelineVertexInputStateCreateInfo vi = VkPipelineVertexInputStateCreateInfo.calloc(s).sType$Default()
                    .pVertexBindingDescriptions(VkVertexInputBindingDescription.calloc(1, s).binding(0).stride(16).inputRate(VK_VERTEX_INPUT_RATE_VERTEX))
                    .pVertexAttributeDescriptions(attr);
            VkViewport.Buffer vp = VkViewport.calloc(1, s).width(W).height(H).maxDepth(1f);
            VkRect2D.Buffer sc = VkRect2D.calloc(1, s);
            sc.get(0).extent().set(W, H);
            VkPipelineColorBlendAttachmentState.Buffer cba = VkPipelineColorBlendAttachmentState.calloc(1, s).colorWriteMask(0xF);
            if (blend) cba.get(0).blendEnable(true).srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD).srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).alphaBlendOp(VK_BLEND_OP_ADD);
            VkGraphicsPipelineCreateInfo.Buffer gp = VkGraphicsPipelineCreateInfo.calloc(1, s).sType$Default().pStages(st).pVertexInputState(vi)
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(s).sType$Default().viewportCount(1).pViewports(vp).scissorCount(1).pScissors(sc))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(s).sType$Default().polygonMode(VK_POLYGON_MODE_FILL).cullMode(CULL_MODE)
                            .frontFace(FRONT_FACE).lineWidth(1f))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(s).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(s).sType$Default().depthTestEnable(true).depthWriteEnable(true)
                            .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(s).sType$Default().pAttachments(cba))
                    .layout(d.layout).renderPass(d.rp).subpass(0);
            LongBuffer lb = s.mallocLong(1);
            ck(vkCreateGraphicsPipelines(d.dev, VK_NULL_HANDLE, gp, null, lb), "pipeline");
            long p = lb.get(0);
            VkDevice dev = d.dev;
            d.undo.push(() -> vkDestroyPipeline(dev, p, null));
            return p;
        }
    }

    /** atlas: left half opaque pattern, right half alpha checker; lightmap: (block, sky) gradient */
    static void uploadTextures(MemoryStack outer, Dev d) {
        try (MemoryStack s = outer.push()) {
            HostBuf stage = hostBuf(s, d, 2 * 16 * 16 * 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                int a = x < 8 ? 0xFF : (((x + y) & 1) == 0 ? 0xFF : 0x00);
                int r = 128 + x * 8, g = 128 + y * 8, b = 200 - x * 4;
                MemoryUtil.memPutInt(stage.ptr + (y * 16 + x) * 4L, a << 24 | b << 16 | g << 8 | r);
                MemoryUtil.memPutInt(stage.ptr + 1024 + (y * 16 + x) * 4L, 0xFF << 24 | 160 << 16 | (y * 16 + 15) << 8 | (x * 16 + 15));
            }
            VkCommandBuffer c = beginOnce(s, d);
            long[] imgs = {d.atlas[0], d.lightmap[0]};
            for (int i = 0; i < 2; i++) {
                TerrainIndirectRepro.barrier(s, c, imgs[i], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
                VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
                r.get(0).bufferOffset(i * 1024L).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                r.get(0).imageExtent().set(16, 16, 1);
                vkCmdCopyBufferToImage(c, stage.buf, imgs[i], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, r);
                TerrainIndirectRepro.barrier(s, c, imgs[i], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            }
            submitWait(s, d, c);
        }
    }

    static VkCommandBuffer beginOnce(MemoryStack s, Dev d) {
        ck(vkResetCommandBuffer(d.cmd, 0), "reset");
        ck(vkBeginCommandBuffer(d.cmd, VkCommandBufferBeginInfo.calloc(s).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "begin");
        return d.cmd;
    }

    static void submitWait(MemoryStack s, Dev d, VkCommandBuffer c) {
        ck(vkEndCommandBuffer(c), "end");
        ck(vkQueueSubmit(d.queue, VkSubmitInfo.calloc(1, s).sType$Default().pCommandBuffers(s.pointers(c)), VK_NULL_HANDLE), "submit");
        ck(vkQueueWaitIdle(d.queue), "wait");
    }

    // ------------------------------------------------------------------------------------------------ data layouts

    /** device buffers of the replay: host-visible, mapped (data=true) or tiny placeholders (timing: never executed) */
    static final class ReplayOps implements WorldArena.Ops {
        final MemoryStack s;
        final Dev d;
        final boolean data;
        final Map<Object, HostBuf> bufs = new IdentityHashMap<>();

        ReplayOps(MemoryStack s, Dev d, boolean data) { this.s = s; this.d = d; this.data = data; }

        @Override public Object allocate(boolean index, long bytes) {
            HostBuf h = hostBuf(s, d, data ? bytes : 65536, index ? VK_BUFFER_USAGE_INDEX_BUFFER_BIT : VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            Object key = new Object();
            bufs.put(key, h);
            return key;
        }
        @Override public long id(Object buffer) { return bufs.get(buffer).buf; }
        @Override public void upload(Object dst, long dstOffset, ByteBuffer src) {
            if (data) MemoryUtil.memCopy(MemoryUtil.memAddress(src), bufs.get(dst).ptr + dstOffset, src.remaining());
        }
        @Override public void retire(Object buffer) {} // destroyed with the device
        @Override public void later(Runnable r) {}  // no frees in a replay
    }

    static final class Layout {
        final Map<Long, Area> areas = new HashMap<>();
        WorldArena arena;
        ReplayOps ops;
        long paramBytes;
    }

    /**
     * Both layouts of the scene. Per area (AreaBuffer after a fill: sections packed in arrival order) and world (production
     * WorldArena.upload per facing, sorted indices into the index pool, as DrawBuffers.uploadWorld).
     */
    static Layout build(MemoryStack s, Dev d, List<Sec> secs, WorldArena arena, ReplayOps ops, boolean data) {
        Layout lay = new Layout();
        lay.arena = arena;
        lay.ops = ops;
        Map<Area, long[]> sizes = new IdentityHashMap<>(); // per layer vertex bytes, [LAYERS] = index bytes
        for (Sec sc : secs) {
            Area a = areaOf(sc, lay.areas);
            long[] z = sizes.computeIfAbsent(a, k -> new long[LAYERS + 1]);
            for (int l = 0; l < LAYERS; l++) for (int f = 0; f < FACINGS; f++) z[l] += sc.vertexCount(l, f) * 16L;
            z[LAYERS] += sc.quads[L_TRANSLUCENT][6] * 12L;
        }
        long pBytes = (long) AREA_SECTIONS * LAYERS * FACINGS * PSTRIDE;
        for (Area a : lay.areas.values()) {
            long[] z = sizes.get(a);
            a.params = MemoryUtil.nmemCalloc(1, pBytes);
            a.wparams = MemoryUtil.nmemCalloc(1, pBytes);
            lay.paramBytes += 2 * pBytes;
            for (long p = 0; p < pBytes; p += PSTRIDE) { MemoryUtil.memPutInt(a.params + p + 8, -1); MemoryUtil.memPutInt(a.wparams + p + 8, -1); }
            for (int l = 0; l < LAYERS; l++)
                if (z[l] > 0) a.vb[l] = hostBuf(s, d, data ? z[l] : 65536, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT).buf;
            if (z[LAYERS] > 0) {
                HostBuf ib = hostBuf(s, d, data ? z[LAYERS] : 65536, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
                a.ib = ib.buf;
                a.ibMem = ib.ptr;
            }
        }
        Map<Area, long[]> fill = new IdentityHashMap<>();
        for (Sec sc : secs) {
            Area a = areaOf(sc, lay.areas);
            int sec = inAreaIndex(a, sc);
            long[] at = fill.computeIfAbsent(a, k -> new long[LAYERS + 1]);
            int base = encodeSectionOffset(sc);
            for (int l = 0; l < LAYERS; l++) {
                int total = 0;
                for (int f = 0; f < FACINGS; f++) total += sc.vertexCount(l, f) * 16;
                if (total > 0) a.pref[l] = (byte) arena.vertexPool(l).pageFor(total, a.pref[l]); // DrawBuffers.uploadWorld
                for (int f = 0; f < FACINGS; f++) {
                    int vc = sc.vertexCount(l, f);
                    if (vc == 0) continue;
                    // per area
                    long p = paramsPtr(a.params, sec, l, f);
                    MemoryUtil.memPutInt(p, vc * 6 / 4);
                    MemoryUtil.memPutInt(p + 8, (int) (at[l] / 16));
                    MemoryUtil.memPutInt(p + 12, base);
                    if (data) {
                        long dst = HOSTPTR.get(a.vb[l]);
                        MemoryUtil.memCopy(MemoryUtil.memAddress(sc.verts[l][f]), dst + at[l], vc * 16L);
                    }
                    // world
                    ByteBuffer src = data ? sc.verts[l][f] : dummy(vc * 16);
                    long w = arena.upload(arena.vertexPool(l), src, a.pref[l]);
                    a.pref[l] = (byte) WorldArena.page(w);
                    long q = paramsPtr(a.wparams, sec, l, f);
                    MemoryUtil.memPutInt(q, vc * 6 / 4);
                    MemoryUtil.memPutInt(q + 8, WorldArena.offset(w) / 16);
                    MemoryUtil.memPutInt(q + TerrainCommandWriter.PARAM_PAGE_OFFSET, WorldArena.page(w) + 1); // DrawBuffers.setVPage
                    a.layerRanges[l]++;
                    if (l == L_TRANSLUCENT && f == 6) {
                        int n = sc.quads[l][6] * 6;
                        MemoryUtil.memPutInt(p + 4, (int) (at[LAYERS] / 2));
                        if (data) MemoryUtil.memCopy(MemoryUtil.memAddress(sc.sortedIdx), a.ibMem + at[LAYERS], n * 2L);
                        at[LAYERS] += n * 2L;
                        long wi = arena.upload(arena.indexPool(), data ? sc.sortedIdx : dummy(n * 2), a.pref[LAYERS]);
                        a.pref[LAYERS] = (byte) WorldArena.page(wi);
                        MemoryUtil.memPutInt(q + 4, WorldArena.offset(wi) / 2);
                        a.iPage[l * AREA_SECTIONS + sec] = (byte) WorldArena.page(wi);
                    }
                    at[l] += vc * 16L;
                }
            }
        }
        return lay;
    }

    static ByteBuffer DUMMY;

    static ByteBuffer dummy(int bytes) {
        if (DUMMY == null || DUMMY.capacity() < bytes) DUMMY = ByteBuffer.allocateDirect(Math.max(bytes, 1 << 20));
        return DUMMY.duplicate().position(0).limit(bytes);
    }

    /** buffer -> mapped pointer of every hostBuf */
    static final Map<Long, Long> HOSTPTR = new HashMap<>();

    /** DrawBuffers.encodeSectionOffset (block offsets inside the area, minHeight -64) */
    static int encodeSectionOffset(Sec sc) {
        int x = sc.sx * 16, y = sc.sy * 16, z = sc.sz * 16;
        return ((y - MIN_Y) & 127) << 16 | (z & 127) << 8 | (x & 127);
    }

    static void freeLayout(Layout lay) {
        for (Area a : lay.areas.values()) { MemoryUtil.nmemFree(a.params); MemoryUtil.nmemFree(a.wparams); }
    }

    // ------------------------------------------------------------------------------------------------ recording

    static final class Counts {
        long draws, vertexBinds, indexBinds, pushes, uboBinds, copiedBytes, commands;

        @Override public String toString() {
            return "draws " + draws + ", vertex binds " + vertexBinds + ", index binds " + indexBinds + ", push " + pushes + ", UBO binds " + uboBinds
                    + ", commands " + commands + ", copied " + (copiedBytes >> 10) + " KiB";
        }
    }

    static final class Frame {
        Matrix4f mvp;
        double cx, cy, cz;
        List<Area> areas;
        int uboOffset;
        long indirectOffset;
    }

    /** uploadAndBindUBOs: the layer's UBO values into the ring at a new offset, descriptor set bound with dynamic offsets */
    static void bindUbos(MemoryStack s, Dev d, Frame fr, int layer, Counts c) {
        long p = d.ubo.ptr + fr.uboOffset;
        fr.mvp.get(MemoryUtil.memByteBuffer(p, 64));
        MemoryUtil.memPutInt(p + 64, 0);                   // FogShape
        long q = p + 256;
        MemoryUtil.memPutFloat(q + 16, 1e6f);               // FogStart
        MemoryUtil.memPutFloat(q + 20, 2e6f);               // FogEnd
        MemoryUtil.memPutFloat(q + 24, ALPHA_CUTOUT[layer]); // AlphaCutout (setCutoutUniform)
        vkCmdBindDescriptorSets(d.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, d.layout, 0, s.longs(d.set), s.ints(fr.uboOffset, fr.uboOffset + 256));
        fr.uboOffset += 512;
        c.uboBinds++;
    }

    static void push(MemoryStack s, Dev d, double x, double y, double z, Counts c) {
        vkCmdPushConstants(d.cmd, d.layout, VK_SHADER_STAGE_VERTEX_BIT, 0, s.floats((float) x, (float) y, (float) z));
        c.pushes++;
    }

    static long OLD_SCRATCH = MemoryUtil.nmemAlloc((long) AREA_SECTIONS * FACINGS * OLD_STRIDE);
    static final int[] SEC_IDX = new int[512], MASKS = new int[512], FIS = new int[512];

    /** WorldRenderer.renderSectionLayer per-area loop + DrawBuffers.bindBuffers / buildDrawBatchesIndirect, indirect tier */
    static void recordOld(MemoryStack outer, Dev d, Frame fr, int layer, Counts c) {
        try (MemoryStack s = outer.push()) {
            boolean translucent = layer == L_TRANSLUCENT;
            VkCommandBuffer cmd = d.cmd;
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, translucent ? d.pipeOldTrans : d.pipeOldOpaque);
            vkCmdBindIndexBuffer(cmd, d.quadsIdx.buf, 0, VK_INDEX_TYPE_UINT16);
            c.indexBinds++;
            List<Area> areas = fr.areas;
            for (int ai = 0; ai < areas.size(); ai++) {
                Area a = areas.get(translucent ? areas.size() - 1 - ai : ai);
                if (a.vb[layer] == 0 || a.queue.isEmpty()) continue;
                // bindBuffers
                try (MemoryStack t = s.push()) {
                    nvkCmdBindVertexBuffers(cmd, 0, 1, t.npointer(a.vb[layer]), t.npointer(0));
                    c.vertexBinds++;
                    push(t, d, a.ox + POS_BASE - fr.cx, a.oy + POS_BASE - fr.cy, a.oz + POS_BASE - fr.cz, c);
                }
                if (translucent && a.ib != 0) { vkCmdBindIndexBuffer(cmd, a.ib, 0, VK_INDEX_TYPE_UINT16); c.indexBinds++; }
                bindUbos(s, d, fr, layer, c);
                // buildDrawBatchesIndirect
                long drawParamsBasePtr = a.params + ((long) layer * AREA_SECTIONS * FACINGS) * PSTRIDE;
                long facingsStride = FACINGS * PSTRIDE;
                int count = 0, drawCount = 0;
                long ptr = OLD_SCRATCH;
                int n = a.queue.size();
                if (!translucent) {
                    for (int k = 0; k < n; k++) {
                        Sec sc = a.queue.get(k);
                        SEC_IDX[count] = a.inArea.get(k);
                        MASKS[count] = QuadFacing.facingMask(fr.cx, fr.cy, fr.cz, sc.sx * 16, sc.sy * 16, sc.sz * 16);
                        count++;
                    }
                    for (int j = 0; j < count; ++j) {
                        long p2 = drawParamsBasePtr + (SEC_IDX[j] * facingsStride);
                        int mask = MASKS[j];
                        for (int i = 0; i < FACINGS; i++, p2 += PSTRIDE) {
                            if ((mask & 1 << i) == 0) continue;
                            int indexCount = MemoryUtil.memGetInt(p2);
                            if (indexCount <= 0) continue;
                            MemoryUtil.memPutInt(ptr, indexCount);
                            MemoryUtil.memPutInt(ptr + 4, 1);
                            MemoryUtil.memPutInt(ptr + 8, MemoryUtil.memGetInt(p2 + 4));
                            MemoryUtil.memPutInt(ptr + 12, MemoryUtil.memGetInt(p2 + 8));
                            MemoryUtil.memPutInt(ptr + 16, MemoryUtil.memGetInt(p2 + 12));
                            ptr += OLD_STRIDE;
                            drawCount++;
                        }
                    }
                } else {
                    for (int k = n - 1; k >= 0; k--) SEC_IDX[count++] = a.inArea.get(k);
                    for (int j = 0; j < count; ++j) {
                        long p2 = drawParamsBasePtr + SEC_IDX[j] * facingsStride + 6 * PSTRIDE;
                        int indexCount = MemoryUtil.memGetInt(p2);
                        if (indexCount <= 0) continue;
                        MemoryUtil.memPutInt(ptr, indexCount);
                        MemoryUtil.memPutInt(ptr + 4, 1);
                        MemoryUtil.memPutInt(ptr + 8, MemoryUtil.memGetInt(p2 + 4));
                        MemoryUtil.memPutInt(ptr + 12, MemoryUtil.memGetInt(p2 + 8));
                        MemoryUtil.memPutInt(ptr + 16, MemoryUtil.memGetInt(p2 + 12));
                        ptr += OLD_STRIDE;
                        drawCount++;
                    }
                }
                if (drawCount == 0) continue;
                long bytes = (long) n * FACINGS * OLD_STRIDE; // the per-area path copies the whole scratch window
                MemoryUtil.memCopy(OLD_SCRATCH, d.indirect.ptr + fr.indirectOffset, bytes);
                vkCmdDrawIndexedIndirect(cmd, d.indirect.buf, fr.indirectOffset, drawCount, OLD_STRIDE);
                fr.indirectOffset += bytes;
                c.copiedBytes += bytes;
                c.commands += drawCount;
                c.draws++;
            }
        }
    }

    /** P2-7b: when set, recordWorld issues its draws as vkCmdDrawIndexedIndirectCount with an exact host count (lavapipe workaround) */
    static HostBuf COUNT_SHIM;
    static long countShimOff;

    /** WorldRenderer.drawWorldLayer with the production TerrainCommandWriter */
    static void recordWorld(MemoryStack outer, Dev d, Frame fr, int layer, Layout lay, TerrainCommandWriter w, Counts c, boolean oldShader) {
        try (MemoryStack s = outer.push()) {
            boolean translucent = layer == L_TRANSLUCENT;
            VkCommandBuffer cmd = d.cmd;
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, oldShader ? (translucent ? d.pipeOldTrans : d.pipeOldOpaque) : (translucent ? d.pipeWorldTrans : d.pipeWorldOpaque));
            vkCmdBindIndexBuffer(cmd, d.quadsIdx.buf, 0, VK_INDEX_TYPE_UINT16);
            c.indexBinds++;
            int ox = (int) Math.floor(fr.cx) >> 4, oy = Math.max(MIN_SEC, Math.min(MIN_SEC + SECTIONS_Y - 1, (int) Math.floor(fr.cy) >> 4)), oz = (int) Math.floor(fr.cz) >> 4;
            w.begin(translucent, ox, oy, oz, d.maxDraws);
            List<Area> areas = fr.areas;
            int iiBase = layer * AREA_SECTIONS;
            long base0 = (long) layer * AREA_SECTIONS * FACINGS * PSTRIDE;
            for (int ai = 0; ai < areas.size(); ai++) { // DrawBuffers.writeWorldCommands per area
                Area a = areas.get(translucent ? areas.size() - 1 - ai : ai);
                int n = a.queue.size();
                if (a.layerRanges[layer] == 0) continue; // DrawBuffers.hasWorldLayer
                // two passes like buildDrawBatchesIndirect (gather, then the parameter reads): see DrawBuffers.writeWorldCommands
                int count = 0;
                for (int k = 0; k < n; k++) {
                    int kk = translucent ? n - 1 - k : k;
                    Sec sc = a.queue.get(kk);
                    int fi = w.encode(sc.sx, sc.sy, sc.sz);
                    if (fi < 0) continue;
                    SEC_IDX[count] = a.inArea.get(kk);
                    MASKS[count] = translucent ? 1 << 6 : QuadFacing.facingMask(fr.cx, fr.cy, fr.cz, sc.sx * 16, sc.sy * 16, sc.sz * 16);
                    FIS[count++] = fi;
                }
                w.addArea(a.wparams + base0, count, SEC_IDX, MASKS, FIS, a.iPage, iiBase);
            }
            w.finish();
            if (w.draws() == 0) return;
            push(s, d, (ox << 4) + POS_BASE - fr.cx, (oy << 4) + POS_BASE - fr.cy, (oz << 4) + POS_BASE - fr.cz, c);
            bindUbos(s, d, fr, layer, c);
            WorldArena arena = lay.arena;
            long base = fr.indirectOffset; // IndirectBuffer.reserveMapped + TerrainCommandWriter.copyTo: written once, in place
            w.copyTo(d.indirect.ptr + base);
            fr.indirectOffset += (long) w.totalCommands() * TerrainCommandWriter.STRIDE;
            c.copiedBytes += (long) w.totalCommands() * TerrainCommandWriter.STRIDE;
            long boundV = 0;
            int boundKey = TerrainCommandWriter.QUADS;
            long pBuf = s.nmalloc(8, 8), pOff = s.ncalloc(8, 1, 8);
            for (int k = 0; k < w.draws(); k++) {
                long vb = arena.bufferId(arena.vertexPool(layer), w.drawVertexPage(k));
                int key = w.drawIndexKey(k);
                if (vb != boundV) { MemoryUtil.memPutLong(pBuf, vb); nvkCmdBindVertexBuffers(cmd, 0, 1, pBuf, pOff); boundV = vb; c.vertexBinds++; }
                if (key != boundKey) {
                    if (key == TerrainCommandWriter.QUADS) vkCmdBindIndexBuffer(cmd, d.quadsIdx.buf, 0, VK_INDEX_TYPE_UINT16);
                    else vkCmdBindIndexBuffer(cmd, arena.bufferId(arena.indexPool(), key), 0, VK_INDEX_TYPE_UINT16);
                    boundKey = key;
                    c.indexBinds++;
                }
                int cnt = w.drawCount(k);
                long off = base + (long) w.drawFirst(k) * TerrainCommandWriter.STRIDE;
                if (COUNT_SHIM != null) { // P2-7b: lavapipe keeps a count draw's count buffer for later plain indirect draws (see terrainCullReplay)
                    MemoryUtil.memPutInt(COUNT_SHIM.ptr + countShimOff, cnt);
                    vkCmdDrawIndexedIndirectCount(cmd, d.indirect.buf, off, COUNT_SHIM.buf, countShimOff, cnt, TerrainCommandWriter.STRIDE);
                    countShimOff += 4;
                } else vkCmdDrawIndexedIndirect(cmd, d.indirect.buf, off, cnt, TerrainCommandWriter.STRIDE);
                c.commands += cnt;
                c.draws++;
            }
        }
    }

    static Frame frame(List<Sec> secs, Layout lay, double cx, double cy, double cz, float yaw, float pitch, float aspect) {
        for (Area a : lay.areas.values()) { a.queue.clear(); a.inArea.clear(); }
        Frame f = new Frame();
        f.cx = cx; f.cy = cy; f.cz = cz;
        // camera-relative MVP (positions are camera-relative in both paths)
        f.mvp = new Matrix4f().perspective((float) Math.toRadians(70), aspect, 0.05f, 1200f, true).rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw));
        f.areas = visibleAreas(secs, f.mvp, cx, cy, cz, lay.areas);
        return f;
    }

    /** one image: layers in order, readback, SHA-256 + raw pixels */
    static Object[] render(MemoryStack outer, Dev d, Frame fr, Layout lay, boolean world, TerrainCommandWriter w, Counts c, boolean oldShaderForWorld) {
        try (MemoryStack s = outer.push()) {
            fr.uboOffset = 0;
            fr.indirectOffset = 0;
            VkCommandBuffer cmd = beginOnce(s, d);
            VkClearValue.Buffer clear = VkClearValue.calloc(2, s);
            clear.get(0).color().float32(3, 1f); // black: lit() counts any non-black pixel
            clear.get(1).depthStencil().depth(1f);
            VkRenderPassBeginInfo rb = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(d.rp).framebuffer(d.fb).pClearValues(clear);
            rb.renderArea().extent().set(W, H);
            vkCmdBeginRenderPass(cmd, rb, VK_SUBPASS_CONTENTS_INLINE);
            for (int layer : DRAWN) {
                if (world) recordWorld(s, d, fr, layer, lay, w, c, oldShaderForWorld);
                else recordOld(s, d, fr, layer, c);
            }
            vkCmdEndRenderPass(cmd);
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            r.get(0).imageExtent().set(W, H, 1);
            vkCmdCopyImageToBuffer(cmd, d.color[0], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, d.readback.buf, r);
            VkBufferMemoryBarrier.Buffer hb = VkBufferMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).buffer(d.readback.buf).size(VK_WHOLE_SIZE);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, null, hb, null);
            submitWait(s, d, cmd);
            byte[] px = new byte[W * H * 4];
            MemoryUtil.memByteBuffer(d.readback.ptr, px.length).get(px);
            return new Object[]{HexFormat.of().formatHex(sha(px)), px};
        }
    }

    static byte[] sha(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static int diffPixels(byte[] a, byte[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i += 4) if (a[i] != b[i] || a[i + 1] != b[i + 1] || a[i + 2] != b[i + 2]) n++;
        return n;
    }

    static int lit(byte[] a) {
        int n = 0;
        for (int i = 0; i < a.length; i += 4) if (a[i] != 0 || a[i + 1] != 0 || a[i + 2] != 0) n++; // not the clear colour
        return n;
    }

    // ------------------------------------------------------------------------------------------------ main

    static int pass, fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        String icd = System.getenv("VK_DRIVER_FILES");
        if (icd == null || !icd.toLowerCase().contains("lvp")) throw new IllegalStateException("refusing to run: VK_DRIVER_FILES must point at lavapipe");
        String base = ShaderLoadUtil.class.getResource("/assets/vulkanmod").toExternalForm() + "/shaders/basic";
        ByteBuffer[] code = {
                spirv(base, "terrain", "terrain", SPIRVUtils.ShaderKind.VERTEX_SHADER),
                spirv(base, "terrain_world", "terrain_world", SPIRVUtils.ShaderKind.VERTEX_SHADER),
                spirv(base, "terrain", "terrain", SPIRVUtils.ShaderKind.FRAGMENT_SHADER),
                spirv(base, "terrain_early_z", "terrain_early_z", SPIRVUtils.ShaderKind.FRAGMENT_SHADER)};
        try (MemoryStack s = MemoryStack.stackPush()) {
            Float16Repro.init(s);
            Dev d = device(s, Float16Repro.phys, code, 0);
            System.out.println("# terrainworld-tier " + d.tier + " " + IndirectDrawCaps.report() + (d.dev != null ? " maxDrawIndirectCount=" + Integer.toUnsignedString(d.maxDraws) : ""));
            if (d.dev == null) {
                System.out.println("# terrainworld SKIP tier=" + d.tier + ": the device draws terrain directly (T-C); the world arena is off there (per-area direct draws, covered by terrainIndirectRepro/hwCapsRepro)");
                System.exit(0);
            }
            int before = Float16Repro.ERRORS.size();
            imageCases(s, d);
            d.close();
            int errs = Float16Repro.ERRORS.size() - before;
            for (String e : Float16Repro.ERRORS.subList(before, Math.min(Float16Repro.ERRORS.size(), before + 5)))
                System.out.println("  ! vk " + e.substring(0, Math.min(240, e.length())).replace("\n", " "));
            check("validation (" + MODE + "): 0 errors in the image cases (" + errs + ")", errs == 0);
            if (!QUICK) timing(s, code);
        }
        System.out.println("# terrainworld " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail) + " mode=" + MODE);
        System.exit(fail == 0 ? 0 : 1);
    }

    static ByteBuffer spirv(String base, String cfg, String name, SPIRVUtils.ShaderKind kind) {
        return SPIRVUtils.compileShader(name, ShaderLoadUtil.getShaderSource(base, cfg, name, kind), kind).bytecode();
    }

    static void imageCases(MemoryStack s, Dev d) {
        int rd = 8;
        List<Sec> secs = generate(rd, true);
        int quads = 0;
        for (Sec sc : secs) for (int[] l : sc.quads) for (int q : l) quads += q;
        System.out.println("  scene rd" + rd + ": " + secs.size() + " sections with geometry, " + quads + " quads");
        // camera with exactly representable coordinates (multiples of 1/1024), looking down over the terrain and water
        double[][] cams = {{8.375, 96.25, 8.625, 35, 28}, {-40.5, 80.75, 30.125, -120, 18}};
        // (1) game page sizes: one page per layer at this size
        {
            ReplayOps ops = new ReplayOps(s, d, true);
            WorldArena arena = WorldArena.forGame(ops, LAYERS, 16);
            Layout lay = build(s, d, secs, arena, ops, true);
            TerrainCommandWriter w = new TerrainCommandWriter();
            for (int ci = 0; ci < cams.length; ci++) {
                double[] c = cams[ci];
                Frame fr = frame(secs, lay, c[0], c[1], c[2], (float) c[3], (float) c[4], (float) W / H);
                Counts co = new Counts(), cw = new Counts();
                Object[] old = render(s, d, fr, lay, false, null, co, false);
                Object[] nu = render(s, d, fr, lay, true, w, cw, false);
                int lit = lit((byte[]) old[1]);
                check(String.format("camera %d, game pages (%d vertex pages): world image == per-area image (%s / %s, %d lit px); per-area %s | world %s",
                                ci, pages(arena), ((String) old[0]).substring(0, 12), ((String) nu[0]).substring(0, 12), lit, co, cw),
                        old[0].equals(nu[0]) && lit > W * H / 4 && w.outOfRange == 0 && w.orphans == 0);
                if (ci == 0) {
                    Object[] ctl = render(s, d, fr, lay, true, w, new Counts(), true);
                    check("negative control: world commands with terrain.vsh (area-offset decode) give a DIFFERENT image (" + diffPixels((byte[]) old[1], (byte[]) ctl[1]) + " px differ)",
                            !old[0].equals(ctl[0]) && diffPixels((byte[]) old[1], (byte[]) ctl[1]) > 1000);
                }
            }
            // (3) arbitrary camera fractions: the frame origin rounds differently from the area origin (sub-ulp positions)
            Frame fr = frame(secs, lay, 8.3, 96.7, 8.9, 35, 28, (float) W / H);
            Object[] old = render(s, d, fr, lay, false, null, new Counts(), false);
            Object[] nu = render(s, d, fr, lay, true, w, new Counts(), false);
            int diff = diffPixels((byte[]) old[1], (byte[]) nu[1]);
            check(String.format("arbitrary camera fractions: %d of %d px differ (float rounding of origin + offset; bound 0.5%%)", diff, W * H), diff <= W * H / 200);
            w.free();
            freeLayout(lay);
        }
        // (2) small pages: many pages per layer, translucent runs across pages, index pages; draws split at 50 commands
        {
            ReplayOps ops = new ReplayOps(s, d, true);
            long[] first = new long[LAYERS], max = new long[LAYERS];
            Arrays.fill(first, 64 << 10);
            Arrays.fill(max, 256 << 10);
            WorldArena arena = new WorldArena(ops, LAYERS, 16, first, max, 4 << 10, 16 << 10);
            Layout lay = build(s, d, secs, arena, ops, true);
            TerrainCommandWriter w = new TerrainCommandWriter();
            int saved = d.maxDraws;
            d.maxDraws = 50;
            double[] c = cams[0];
            Frame fr = frame(secs, lay, c[0], c[1], c[2], (float) c[3], (float) c[4], (float) W / H);
            Counts co = new Counts(), cw = new Counts();
            Object[] old = render(s, d, fr, lay, false, null, co, false);
            Object[] nu = render(s, d, fr, lay, true, w, cw, false);
            d.maxDraws = saved;
            check(String.format("small pages (%d vertex pages, %d index pages) + maxDrawIndirectCount 50: world image == per-area image (%s); world %s",
                            pages(arena), arena.indexPool().pageCount(), ((String) nu[0]).substring(0, 12), cw),
                    old[0].equals(nu[0]) && pages(arena) > 10 && arena.indexPool().pageCount() > 1 && arenaErrors(arena) == 0);
            w.free();
            freeLayout(lay);
        }
        freeScene(secs);
    }

    static int arenaErrors(WorldArena a) {
        int n = a.indexPool().errors().size() + (int) a.badFrees;
        for (int l = 0; l < LAYERS; l++) n += a.vertexPool(l).errors().size();
        return n;
    }

    static int pages(WorldArena a) {
        int n = 0;
        for (int l = 0; l < LAYERS; l++) n += a.vertexPool(l).pageCount();
        return n;
    }

    /** (5) CPU record cost per frame, both paths, RD24 and RD32, on a device without validation */
    static void timing(MemoryStack outer, ByteBuffer[] code) {
        try (MemoryStack s = outer.push()) {
            VkInstance inst = plainInstance(s);
            VkPhysicalDevice phys = cpuDevice(s, inst);
            Dev d = device(s, phys, code, 0);
            if (d.dev == null) { System.out.println("  timing: device tier " + d.tier + ", skipped"); vkDestroyInstance(inst, null); return; }
            boolean forced = System.getenv("VK_LOADER_LAYERS_ENABLE") != null || System.getenv("VK_INSTANCE_LAYERS") != null;
            for (int rd : new int[]{24, 32}) {
                int mark = d.undo.size();
                List<Sec> secs = generate(rd, false);
                ReplayOps ops = new ReplayOps(s, d, false);
                WorldArena arena = WorldArena.forGame(ops, LAYERS, 16);
                Layout lay = build(s, d, secs, arena, ops, false);
                TerrainCommandWriter w = new TerrainCommandWriter();
                // standing at ground level, looking along the terrain (p1-stand like)
                Frame fr = frame(secs, lay, 8.5, 75.5, 8.5, 30, 5, 16f / 9f);
                int visSecs = 0;
                for (Area a : fr.areas) visSecs += a.queue.size();
                // blocks A B A B (40 frames each, 3 x 2 blocks measured after one warm-up round): interleaving every frame
                // let the per-area path's 4 MiB indirect copy evict the world path's data and charged it to the world path
                int block = 60, rounds = 5, meas = block * (rounds - 1);
                long[][] ns = new long[2][meas], cpu = new long[2][meas];
                java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
                Counts[] cnt = new Counts[2];
                for (int r = 0; r < rounds; r++)
                    for (int pp = 0; pp < 2; pp++)
                        for (int b = 0; b < block; b++) try (MemoryStack s2 = s.push()) {
                        int it = r * block + b, warm = block;
                        int path = (r & 1) == 0 ? pp : 1 - pp; // A B, then B A: neither path always runs first
                        Counts c = new Counts();
                        fr.uboOffset = 0;
                        fr.indirectOffset = 0;
                        VkCommandBuffer cmd = beginOnce(s2, d);
                        VkRenderPassBeginInfo rb = VkRenderPassBeginInfo.calloc(s2).sType$Default().renderPass(d.rp).framebuffer(d.fb).pClearValues(VkClearValue.calloc(2, s2));
                        rb.renderArea().extent().set(W, H);
                        vkCmdBeginRenderPass(cmd, rb, VK_SUBPASS_CONTENTS_INLINE);
                        long c0 = mx.getCurrentThreadCpuTime(), t0 = System.nanoTime();
                        for (int layer : DRAWN) {
                            if (path == 0) recordOld(s2, d, fr, layer, c);
                            else recordWorld(s2, d, fr, layer, lay, w, c, false);
                        }
                        long t1 = System.nanoTime(), c1 = mx.getCurrentThreadCpuTime();
                        if (it >= warm) cpu[path][it - warm] = c1 - c0;
                        vkCmdEndRenderPass(cmd);
                        ck(vkEndCommandBuffer(cmd), "end");
                        if (it >= warm) ns[path][it - warm] = t1 - t0;
                        cnt[path] = c;
                    }
                for (long[] t : ns) Arrays.sort(t);
                for (long[] t : cpu) Arrays.sort(t);
                long mo = ns[0][meas / 2], mw = ns[1][meas / 2], co = cpu[0][meas / 2], cw = cpu[1][meas / 2];
                System.out.printf("  record cost RD%d (%d sections with geometry, %d visible in %d areas; lavapipe recording, load1 %.1f%s): per-area wall median %.3f ms (min %.3f) thread-CPU median %.3f ms [%s] | world wall median %.3f ms (min %.3f) thread-CPU median %.3f ms [%s] | draws %d -> %d, wall %.2fx, CPU %.2fx%n",
                        rd, secs.size(), visSecs, fr.areas.size(), load1(), forced ? ", layers forced on: NOT representative" : "",
                        mo / 1e6, ns[0][0] / 1e6, co / 1e6, cnt[0], mw / 1e6, ns[1][0] / 1e6, cw / 1e6, cnt[1],
                        cnt[0].draws, cnt[1].draws, mw / (double) Math.max(1, mo), cw / (double) Math.max(1, co));
                check(String.format("RD%d: same %d commands drawn by both paths; world draws %d <= per-area %d, vertex binds %d <= %d, UBO binds %d (one per layer); no section out of range",
                                rd, cnt[0].commands, cnt[1].draws, cnt[0].draws, cnt[1].vertexBinds, cnt[0].vertexBinds, cnt[1].uboBinds),
                        cnt[0].commands == cnt[1].commands && cnt[1].draws <= cnt[0].draws && cnt[1].vertexBinds <= cnt[0].vertexBinds
                                && cnt[1].uboBinds <= DRAWN.length && w.outOfRange == 0 && w.orphans == 0);
                w.free();
                freeLayout(lay);
                d.rollback(mark); // this RD's buffers
            }
            d.close();
            vkDestroyInstance(inst, null);
        }
    }

    static double load1() {
        try { return Double.parseDouble(java.nio.file.Files.readString(java.nio.file.Path.of("/proc/loadavg")).split(" ")[0]); } catch (Exception e) { return -1; }
    }

    static VkInstance plainInstance(MemoryStack s) {
        VkApplicationInfo app = VkApplicationInfo.calloc(s).sType$Default().apiVersion(VK_API_VERSION_1_2);
        VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(app);
        PointerBuffer pp = s.mallocPointer(1);
        ck(vkCreateInstance(ici, null, pp), "instance");
        long h = pp.get(0);
        return net.vulkanmod.vulkan.util.WideStack.call(() -> new VkInstance(h, ici));
    }

    static VkPhysicalDevice cpuDevice(MemoryStack s, VkInstance inst) {
        java.nio.IntBuffer n = s.mallocInt(1);
        vkEnumeratePhysicalDevices(inst, n, null);
        PointerBuffer devs = s.mallocPointer(n.get(0));
        vkEnumeratePhysicalDevices(inst, n, devs);
        for (int i = 0; i < devs.capacity(); i++) {
            VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), inst);
            VkPhysicalDeviceProperties pr = VkPhysicalDeviceProperties.calloc(s);
            vkGetPhysicalDeviceProperties(pd, pr);
            if (pr.deviceType() == VK_PHYSICAL_DEVICE_TYPE_CPU) return pd;
        }
        throw new IllegalStateException("no CPU Vulkan device");
    }

    static void ck(int r, String what) {
        if (r != VK_SUCCESS) throw new IllegalStateException(what + ": VkResult " + r);
    }
}
