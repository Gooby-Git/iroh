package dev.elemore.iroh.vktest;

import dev.elemore.iroh.vktest.TerrainWorldReplay.*;
import net.vulkanmod.render.chunk.buffer.TerrainCommandWriter;
import net.vulkanmod.render.chunk.buffer.WorldArena;
import net.vulkanmod.render.chunk.cull.CullBounds;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.chunk.cull.SectionCullData;
import net.vulkanmod.render.chunk.cull.TerrainCull;
import net.vulkanmod.render.shader.ShaderLoadUtil;
import net.vulkanmod.vulkan.device.IndirectDrawCaps;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.*;

import static dev.elemore.iroh.vktest.TerrainWorldReplay.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * P2-7b (L5b): the production GPU terrain cull (TerrainCull + terrain_cull.comp) on lavapipe + VK_LAYER_KHRONOS_validation
 * (sync validation; GPU-AV under -Pvalidate=gpuav sees the indirect buffer contents). ./gradlew terrainCullReplay [-Ptc.quick]
 *
 * <p>Scene: TerrainWorldReplay's generated section set in production WorldArena pages. Metadata: production CullBounds.scan
 * of every section layer's vertices, flags per grid slot (compiled; opaque = below the column's lowest surface, solid or
 * with an enclosed cave room, as VisibilitySet would say: no pair of faces connected). Candidates: EVERY section within
 * the scene, nearest first, chunk areas by first appearance (what the async walk's union can hand the renderer: no CPU
 * frustum at all), written by the production TerrainCommandWriter (beginCull / addAreaCull).
 *
 * <p>Recorded frames: overview, low camera across hills (horizon), looking at the sky, arbitrary fractions, valley facing
 * a hill, wide FOV (small sections under a pixel). For each frame, the reference image = the P2-7a CPU path (CPU facing
 * masks) over the same candidates; then every combination of the four techniques (16) on the device's tier, and in the
 * full run on both tiers (COUNT: compaction + vkCmdDrawIndexedIndirectCount; FIXED: instanceCount 0 in place +
 * fixed-count vkCmdDrawIndexedIndirect): (1) image SHA == reference (no visible section missing, nothing extra);
 * (2) the kept set read back from the out buffer == TerrainCullModel's set, except commands the model marks borderline;
 * (3) each technique alone culls something over the frames. Last line "# terraincullreplay PASS|FAIL|SKIP".
 */
public final class TerrainCullReplay {
    static final boolean QUICK = Boolean.getBoolean("tc.quick");
    /**
     * -Ptc.noHorizon: skip the horizon combinations. GPU-AV shader instrumentation makes lavapipe SIGSEGV in the
     * horizon build shader (si_addr 0x44, frames only in libvulkan_lvp; any loop of buffer stores after the run scan;
     * the RQ-B-gpuav-lvp pattern); tools/vkval-extra-gate.sh runs the instrumented pass without horizon and an
     * uninstrumented GPU-AV pass (indirect buffer content checks) with every combination.
     */
    static final boolean NO_HORIZON = Boolean.getBoolean("tc.noHorizon");
    static final int[] OPAQUE = {L_CUTOUT_MIPPED, L_CUTOUT}; // drawn opaque layers (solid is meshed into cutout_mipped)
    static int pass, fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    record Cam(String name, double x, double y, double z, float yaw, float pitch, float fov) {}

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
            // the game's terrain is drawn with back-face culling (the facing buckets, CPU or GPU, only drop back faces)
            TerrainWorldReplay.CULL_MODE = VK_CULL_MODE_BACK_BIT;
            TerrainWorldReplay.FRONT_FACE = VK_FRONT_FACE_CLOCKWISE;
            Dev d = device(s, Float16Repro.phys, code, 0);
            IndirectDrawCaps.CullTier tier = IndirectDrawCaps.cullTier();
            System.out.println("# terraincull-tier " + tier + " " + IndirectDrawCaps.report());
            if (d.dev == null || tier == IndirectDrawCaps.CullTier.CPU) {
                System.out.println("# terraincullreplay SKIP tier=" + tier + ": terrain is drawn directly (T-C): no world arena, no GPU cull (CPU path)");
                System.exit(0);
            }
            int before = Float16Repro.ERRORS.size();
            run(s, d, tier);
            d.close();
            int errs = Float16Repro.ERRORS.size() - before;
            for (String e : Float16Repro.ERRORS.subList(before, Math.min(Float16Repro.ERRORS.size(), before + 5)))
                System.out.println("  ! vk " + e.substring(0, Math.min(240, e.length())).replace("\n", " "));
            check("validation (" + MODE + "): 0 errors (" + errs + ")", errs == 0);
        }
        System.out.println("# terraincullreplay " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail) + " mode=" + MODE);
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------------------ scene metadata

    static final int RD = 8;

    static SectionCullData metadata(List<Sec> secs) {
        SectionCullData data = new SectionCullData(2 * RD + 1, SECTIONS_Y, MIN_SEC);
        Map<Long, Sec> byPos = new HashMap<>();
        for (Sec sc : secs) byPos.put(key(sc.sx, sc.sy, sc.sz), sc);
        for (int cz = -RD; cz <= RD; cz++)
            for (int cx = -RD; cx <= RD; cx++) {
                int minH = Integer.MAX_VALUE;
                for (int bz = -1; bz <= 16; bz++) for (int bx = -1; bx <= 16; bx++) minH = Math.min(minH, height(cx * 16 + bx, cz * 16 + bz));
                for (int sy = MIN_SEC; sy < MIN_SEC + SECTIONS_Y; sy++) {
                    int slot = data.gridIndex(cx, sy, cz);
                    // generate(): below y0 + 15 < minH - 1 the section is solid (no geometry) or holds a closed cave room
                    boolean opaque = sy * 16 + 15 < minH - 1;
                    data.sectionCompiled(slot, cx, cz, opaque ? 0L : -1L, false);
                    Sec sc = byPos.get(key(cx, sy, cz));
                    if (sc == null) continue;
                    for (int l : OPAQUE) data.layerBounds(slot, l, sc.verts[l] == null ? 0 : CullBounds.scan(sc.verts[l]));
                }
            }
        return data;
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0xFFFFF) | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF) << 40;
    }

    /** every section, nearest first; chunk areas in order of first appearance (no CPU frustum: the cull must do it) */
    static List<Area> allAreas(List<Sec> secs, double cx, double cy, double cz, Map<Long, Area> all) {
        for (Area a : all.values()) { a.queue.clear(); a.inArea.clear(); }
        List<Sec> vis = new ArrayList<>(secs);
        vis.sort(Comparator.comparingDouble(s -> sq(s.sx * 16 + 8 - cx) + sq(s.sy * 16 + 8 - cy) + sq(s.sz * 16 + 8 - cz)));
        List<Area> order = new ArrayList<>();
        Set<Area> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Sec s : vis) {
            Area a = areaOf(s, all);
            if (seen.add(a)) order.add(a);
            a.queue.add(s);
            a.inArea.add(inAreaIndex(a, s));
        }
        return order;
    }

    static Frame frameOf(List<Sec> secs, Layout lay, Cam c) {
        Frame f = new Frame();
        f.cx = c.x; f.cy = c.y; f.cz = c.z;
        f.mvp = new Matrix4f().perspective((float) Math.toRadians(c.fov), (float) W / H, 0.05f, 1200f, true)
                .rotateX((float) Math.toRadians(c.pitch)).rotateY((float) Math.toRadians(c.yaw));
        f.areas = allAreas(secs, c.x, c.y, c.z, lay.areas);
        return f;
    }

    // ------------------------------------------------------------------------------------------------ the GPU path

    static final int[] SEC = new int[512], MASK = new int[512], FI = new int[512], SLOT = new int[512];

    /** WorldRenderer.drawWorldLayerCulled's writing: per opaque layer, beginCull + addAreaCull over the areas */
    static TerrainCommandWriter[] writeCull(Frame fr, SectionCullData data, int tech, int maxDraws) {
        TerrainCommandWriter[] ws = new TerrainCommandWriter[3];
        int ox = (int) Math.floor(fr.cx) >> 4, oy = Math.max(MIN_SEC, Math.min(MIN_SEC + SECTIONS_Y - 1, (int) Math.floor(fr.cy) >> 4)), oz = (int) Math.floor(fr.cz) >> 4;
        boolean all = (tech & TerrainCull.T_FACING) != 0;
        for (int l = 0; l < 3; l++) {
            TerrainCommandWriter w = ws[l] = new TerrainCommandWriter();
            w.beginCull(ox, oy, oz, maxDraws);
            long base0 = (long) l * AREA_SECTIONS * FACINGS * PSTRIDE;
            for (Area a : fr.areas) {
                if (a.layerRanges[l] == 0) continue;
                int count = 0;
                for (int k = 0; k < a.queue.size(); k++) {
                    Sec sc = a.queue.get(k);
                    int fi = w.encode(sc.sx, sc.sy, sc.sz);
                    if (fi < 0) continue;
                    SEC[count] = a.inArea.get(k);
                    MASK[count] = all ? 0x7F : QuadFacing.facingMask(fr.cx, fr.cy, fr.cz, sc.sx * 16, sc.sy * 16, sc.sz * 16);
                    SLOT[count] = data.gridIndex(sc.sx, sc.sy, sc.sz);
                    FI[count++] = fi;
                }
                w.addAreaCull(a.wparams + base0, count, SEC, MASK, FI, SLOT, a.iPage, l * AREA_SECTIONS);
            }
            w.finish();
        }
        return ws;
    }

    static TerrainCull.Frame cullFrame(Frame fr, int tech) {
        TerrainCull.Frame f = new TerrainCull.Frame();
        fr.mvp.get(f.mvp);
        int ox = (int) Math.floor(fr.cx) >> 4, oy = Math.max(MIN_SEC, Math.min(MIN_SEC + SECTIONS_Y - 1, (int) Math.floor(fr.cy) >> 4)), oz = (int) Math.floor(fr.cz) >> 4;
        f.originRelX = (float) ((ox << 4) - fr.cx);
        f.originRelY = (float) ((oy << 4) - fr.cy);
        f.originRelZ = (float) ((oz << 4) - fr.cz);
        int cx = (int) Math.floor(fr.cx) >> 4, cy = (int) Math.floor(fr.cy) >> 4, cz = (int) Math.floor(fr.cz) >> 4;
        f.camSecX = cx; f.camSecY = cy; f.camSecZ = cz;
        f.camFracX = (float) (fr.cx - (cx << 4));
        f.camFracY = (float) (fr.cy - (cy << 4));
        f.camFracZ = (float) (fr.cz - (cz << 4));
        f.vpX = 0; f.vpY = 0; f.vpW = W; f.vpH = H;
        f.techniques = tech;
        return f;
    }

    static long serial;
    static HostBuf countShim;
    static final TerrainCommandWriter CPU_WRITER = new TerrainCommandWriter();

    /** the culled opaque layers + the CPU-path translucent layer, image read back; out buffer copied to {@code outRead} */
    static Object[] renderCulled(MemoryStack outer, Dev d, Frame fr, Layout lay, TerrainCull tc, TerrainCull.Frame cf, SectionCullData data,
                                 TerrainCommandWriter[] ws, HostBuf outRead, List<Runnable> deferred, boolean[] recorded) {
        try (MemoryStack s = outer.push()) {
            fr.uboOffset = 0;
            fr.indirectOffset = 0;
            VkCommandBuffer cmd = beginOnce(s, d);
            recorded[0] = tc.record(cmd, 0, ++serial, cf, data, ws);
            // lavapipe (Mesa 26.2.5) applies the last vkCmdDrawIndexedIndirectCount's count buffer to later plain
            // vkCmdDrawIndexedIndirect calls in the command buffer (translucent drew min(stale count, n) commands): the CPU-path
            // layers after a count draw go through the count entry point with their exact count (same semantics)
            TerrainWorldReplay.COUNT_SHIM = recorded[0] && tc.compact() ? countShim : null;
            TerrainWorldReplay.countShimOff = 0;
            VkClearValue.Buffer clear = VkClearValue.calloc(2, s);
            clear.get(0).color().float32(3, 1f);
            clear.get(1).depthStencil().depth(1f);
            VkRenderPassBeginInfo rb = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(d.rp).framebuffer(d.fb).pClearValues(clear);
            rb.renderArea().extent().set(W, H);
            vkCmdBeginRenderPass(cmd, rb, VK_SUBPASS_CONTENTS_INLINE);
            Counts c = new Counts();
            for (int layer : DRAWN) {
                if (layer == L_TRANSLUCENT || !recorded[0]) {
                    recordWorld(s, d, fr, layer, lay, CPU_WRITER, c, false);
                    continue;
                }
                TerrainCommandWriter w = ws[layer];
                if (w.draws() == 0) continue;
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, d.pipeWorldOpaque);
                vkCmdBindIndexBuffer(cmd, d.quadsIdx.buf(), 0, VK_INDEX_TYPE_UINT16);
                int ox = (int) Math.floor(fr.cx) >> 4, oy = Math.max(MIN_SEC, Math.min(MIN_SEC + SECTIONS_Y - 1, (int) Math.floor(fr.cy) >> 4)), oz = (int) Math.floor(fr.cz) >> 4;
                push(s, d, (ox << 4) + POS_BASE - fr.cx, (oy << 4) + POS_BASE - fr.cy, (oz << 4) + POS_BASE - fr.cz, c);
                bindUbos(s, d, fr, layer, c);
                long pBuf = s.nmalloc(8, 8), pOff = s.ncalloc(8, 1, 8), boundV = 0;
                for (int k = 0; k < w.draws(); k++) {
                    long vb = lay.arena.bufferId(lay.arena.vertexPool(layer), w.drawVertexPage(k));
                    if (vb != boundV) { MemoryUtil.memPutLong(pBuf, vb); nvkCmdBindVertexBuffers(cmd, 0, 1, pBuf, pOff); boundV = vb; }
                    tc.drawCulled(cmd, layer, k, w);
                }
            }
            vkCmdEndRenderPass(cmd);
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            r.get(0).imageExtent().set(W, H, 1);
            vkCmdCopyImageToBuffer(cmd, d.color[0], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, d.readback.buf(), r);
            if (recorded[0]) { // the cull's output for the kept-set check (after the draws read it)
                VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_INDIRECT_COMMAND_READ_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, mb, null, null);
                long bytes = tc.outCommandOffset() + (long) tc.commands() * TerrainCommandWriter.STRIDE;
                vkCmdCopyBuffer(cmd, tc.outBuffer(), outRead.buf(), VkBufferCopy.calloc(1, s).size(Math.min(bytes, outRead.size())));
            }
            VkMemoryBarrier.Buffer hb = VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, hb, null, null);
            submitWait(s, d, cmd);
            TerrainWorldReplay.COUNT_SHIM = null;
            for (Runnable q : deferred) q.run();
            deferred.clear();
            byte[] px = new byte[W * H * 4];
            MemoryUtil.memByteBuffer(d.readback.ptr(), px.length).get(px);
            return new Object[]{HexFormat.of().formatHex(sha(px)), px};
        }
    }

    /** kept commands of the GPU output as keys (layer, firstInstance, vertexOffset, firstIndex) */
    static Set<String> keptGpu(TerrainCull tc, TerrainCommandWriter[] ws, HostBuf outRead, boolean compact) {
        Set<String> kept = new HashSet<>();
        long cmd0 = outRead.ptr() + tc.outCommandOffset();
        for (int sg = 0; sg < tc.segments(); sg++) {
            int first = tc.segFirst(sg), count = tc.segCount(sg);
            int layer = layerOf(tc, first);
            int n = compact ? MemoryUtil.memGetInt(outRead.ptr() + sg * 4L) : count;
            for (int i = 0; i < n; i++) {
                long p = cmd0 + (long) (first + i) * TerrainCommandWriter.STRIDE;
                if (!compact && MemoryUtil.memGetInt(p + 4) == 0) continue;
                kept.add(layer + ":" + MemoryUtil.memGetInt(p + 16) + ":" + MemoryUtil.memGetInt(p + 12) + ":" + MemoryUtil.memGetInt(p + 8));
            }
        }
        return kept;
    }

    static int layerOf(TerrainCull tc, int first) {
        int l = 0;
        while (l < 2 && first >= tc.layerBase(l + 1)) l++;
        return l;
    }

    /** the model's kept set and borderline set over the writers' commands */
    static Set<String>[] keptModel(TerrainCommandWriter[] ws, TerrainCullModel model) {
        @SuppressWarnings("unchecked") Set<String>[] r = new Set[]{new HashSet<String>(), new HashSet<String>()};
        for (int l = 0; l < 3; l++) {
            TerrainCommandWriter w = ws[l];
            int n = w.totalCommands();
            if (n == 0) continue;
            long cmds = MemoryUtil.nmemAlloc((long) n * 20), words = MemoryUtil.nmemAlloc((long) n * 4);
            w.copyTo(cmds);
            w.copyWordsTo(words);
            for (int i = 0; i < n; i++) {
                long p = cmds + i * 20L;
                String k = l + ":" + MemoryUtil.memGetInt(p + 16) + ":" + MemoryUtil.memGetInt(p + 12) + ":" + MemoryUtil.memGetInt(p + 8);
                boolean v = model.visible(MemoryUtil.memGetInt(words + i * 4L), MemoryUtil.memGetInt(p + 16), l);
                if (v) r[0].add(k);
                if (model.borderline) r[1].add(k);
            }
            MemoryUtil.nmemFree(cmds);
            MemoryUtil.nmemFree(words);
        }
        return r;
    }

    static String techName(int t) {
        if (t == 0) return "none";
        StringBuilder b = new StringBuilder();
        if ((t & TerrainCull.T_FRUSTUM) != 0) b.append("frustum+");
        if ((t & TerrainCull.T_FACING) != 0) b.append("facing+");
        if ((t & TerrainCull.T_SCREEN) != 0) b.append("screen+");
        if ((t & TerrainCull.T_HORIZON) != 0) b.append("horizon+");
        return b.substring(0, b.length() - 1);
    }

    static void run(MemoryStack s, Dev d, IndirectDrawCaps.CullTier tier) {
        List<Sec> secs = generate(RD, true);
        System.out.println("  scene rd" + RD + ": " + secs.size() + " sections with geometry");
        ReplayOps ops = new ReplayOps(s, d, true);
        WorldArena arena = WorldArena.forGame(ops, LAYERS, 16);
        Layout lay = build(s, d, secs, arena, ops, true);
        SectionCullData data = metadata(secs);
        int opaqueSlots = 0;
        for (int i = 0; i < data.size; i++) if ((data.word(i, 0) & 3) == 3) opaqueSlots++;
        System.out.println("  metadata: " + data.size + " grid slots, " + opaqueSlots + " opaque");
        List<Runnable> deferred = new ArrayList<>();
        HostBuf outRead = hostBuf(s, d, 64L << 20, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        countShim = hostBuf(s, d, 1 << 16, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);

        double hy = height(-20, 4) + 2.6;
        List<Cam> cams = new ArrayList<>(List.of(
                new Cam("overview", 8.375, 96.25, 8.625, 35, 28, 70),
                new Cam("low across hills", -20.25, hy, 4.5, 60, 2, 70),
                new Cam("wide fov 150", 3.5, 110.5, -2.25, 200, 40, 150)));
        if (!QUICK) {
            cams.add(new Cam("sky", 0.5, 90.5, 0.5, 10, -80, 70));
            cams.add(new Cam("arbitrary fractions", 8.3, 96.7, 8.9, 123, 17, 70));
            double vy = height(40, -30) + 1.7;
            cams.add(new Cam("valley toward a hill", 40.6, vy, -30.2, -150, 5, 70));
        }
        String force = System.getenv("TC_TIER"); // debug: COUNT / FIXED only
        boolean[] modes = force != null ? new boolean[]{force.equals("COUNT")} : QUICK ? new boolean[]{tier == IndirectDrawCaps.CullTier.COUNT} : tier == IndirectDrawCaps.CullTier.COUNT ? new boolean[]{true, false} : new boolean[]{false};
        long[] culledBy = new long[16];
        for (boolean compact : modes) {
            TerrainCull tc = new TerrainCull(d.dev, d.mp, 1, compact, deferred::add);
            String tierName = compact ? "COUNT" : "FIXED";
            int cracks = 0;
            int imagesOk = 0, imagesAll = 0, setOk = 0, setAll = 0, borderTotal = 0, mismatchTotal = 0;
            for (Cam cam : cams) {
                Frame fr = frameOf(secs, lay, cam);
                Object[] ref = render(s, d, fr, lay, true, CPU_WRITER, new Counts(), false);
                int lit = lit((byte[]) ref[1]);
                long candidates = -1;
                for (int tech = 1; tech < 16; tech++) {
                    if (System.getenv("TC_TECH") != null && tech != Integer.parseInt(System.getenv("TC_TECH"))) continue; // debug: one combination
                    if (NO_HORIZON && (tech & TerrainCull.T_HORIZON) != 0) continue;
                    TerrainCommandWriter[] ws = writeCull(fr, data, tech, d.maxDraws);
                    TerrainCull.Frame cf = cullFrame(fr, tech);
                    boolean[] rec = {false};
                    Object[] img = renderCulled(s, d, fr, lay, tc, cf, data, ws, outRead, deferred, rec);
                    int cand = 0;
                    for (TerrainCommandWriter w : ws) cand += w.totalCommands();
                    if ((tech & TerrainCull.T_FACING) == 0) candidates = cand;
                    Set<String> gpu = rec[0] ? keptGpu(tc, ws, outRead, compact) : Set.of();
                    TerrainCullModel model = new TerrainCullModel(cf, data);
                    Set<String>[] mk = keptModel(ws, model);
                    if (System.getenv("TC_DEBUG") != null && tech == TerrainCull.T_FRUSTUM) debugFrustum(fr, cf, ws, data, model, secs);
                    if (System.getenv("TC_DEBUG") != null && tech == TerrainCull.T_HORIZON) debugHorizon(fr, cf, ws, data, model, secs);
                    int mismatch = 0, border = 0;
                    Set<String> diff = new HashSet<>(gpu);
                    diff.removeAll(mk[0]);
                    Set<String> diff2 = new HashSet<>(mk[0]);
                    diff2.removeAll(gpu);
                    diff.addAll(diff2);
                    for (String k : diff) { if (mk[1].contains(k)) border++; else mismatch++; }
                    boolean same = img[0].equals(ref[0]);
                    imagesAll++;
                    int[] cr = same ? new int[]{0, 0} : crackPixels(fr, (byte[]) ref[1], (byte[]) img[1], gpu);
                    cracks += cr[0];
                    if (same || cr[1] == 0) imagesOk++; // every differing pixel: its true first hit is kept (a crack showed culled hidden geometry)
                    else System.out.println("  FAIL detail: " + cr[1] + " differing px whose first visible surface was culled");
                    setAll++;
                    if (rec[0] && mismatch == 0) setOk++;
                    borderTotal += border;
                    mismatchTotal += mismatch;
                    if (Integer.bitCount(tech) == 1 && (tech & TerrainCull.T_FACING) == 0) culledBy[tech] += cand - gpu.size();
                    if (tech == TerrainCull.T_FACING) culledBy[tech] += candidatesAllFacings(ws) - gpu.size(); // vs all buckets
                    if (!same && System.getenv("TC_DEBUG") != null && compact && tech == TerrainCull.T_FRUSTUM) {
                        Object[] k = renderKept(s, d, fr, lay, tc, ws, outRead);
                        if (System.getenv("TC_KEPTPNG") != null) { dump((byte[]) k[1], System.getenv("TC_KEPTPNG")); System.out.println("    dbg kept sha " + k[0]); }
                        System.out.println("    dbg " + cam.name + " kept set drawn with plain MDI from the read-back counts: " + (k[0].equals(ref[0]) ? "SAME as reference" : "differs " + diffPixels((byte[]) ref[1], (byte[]) k[1])));
                    }
                    if (!same && System.getenv("TC_DEBUG") != null) {
                        byte[] ra = (byte[]) ref[1], ca = (byte[]) img[1];
                        int holes = 0, extra = 0, other = 0, minX = W, maxX = -1, minY = H, maxY = -1;
                        for (int i = 0; i < ra.length; i += 4) {
                            boolean rb = ra[i] == 0 && ra[i + 1] == 0 && ra[i + 2] == 0, cb = ca[i] == 0 && ca[i + 1] == 0 && ca[i + 2] == 0;
                            boolean df = ra[i] != ca[i] || ra[i + 1] != ca[i + 1] || ra[i + 2] != ca[i + 2];
                            if (!df) continue;
                            if (cb && !rb) holes++; else if (rb && !cb) extra++; else other++;
                            int px = (i / 4) % W, py = (i / 4) / W;
                            minX = Math.min(minX, px); maxX = Math.max(maxX, px); minY = Math.min(minY, py); maxY = Math.max(maxY, py);
                        }
                        dump(ra, System.getenv("TC_DEBUG") + "/ref-" + cam.name.replace(' ', '_') + ".png");
                        dump(ca, System.getenv("TC_DEBUG") + "/" + (compact ? "count-" : "fixed-") + cam.name.replace(' ', '_') + "-" + techName(tech) + ".png");
                        System.out.println("    dbg " + cam.name + " " + techName(tech) + ": holes " + holes + " extra " + extra + " other " + other + " bbox " + minX + "," + minY + " - " + maxX + "," + maxY);
                    }
                    if ((!same && cr[1] > 0) || mismatch > 0 || !rec[0])
                        System.out.printf("  FAIL detail: %s %s %s: image %s (%d px differ), kept gpu %d model %d, mismatch %d, borderline %d, recorded %b%n",
                                tierName, cam.name, techName(tech), same ? "same" : "DIFFERS", diffPixels((byte[]) ref[1], (byte[]) img[1]), gpu.size(), mk[0].size(), mismatch, border, rec[0]);
                    if (tech == 15)
                        System.out.printf("  %s %-22s all four: candidates %d (CPU masks), kept %d (%.1f%%), %d segments, lit %d px%n",
                                tierName, cam.name, candidates, gpu.size(), 100.0 * gpu.size() / Math.max(1, cand), tc.segments(), lit);
                    for (TerrainCommandWriter w : ws) w.free();
                }
            }
            check(String.format("%s: images == the CPU path for every frame x technique combination (%d/%d): no visible section missing "
                    + "(%d px differ only where the reference saw culled, fully enclosed geometry through a 1 px rasterization crack: the first surface hit along those pixels is kept)",
                    tierName, imagesOk, imagesAll, cracks), imagesOk == imagesAll);
            check(String.format("%s: kept set == model's (%d/%d; %d borderline differences allowed, %d real)", tierName, setOk, setAll, borderTotal, mismatchTotal), setOk == setAll);
            tc.destroyNow();
        }
        check(String.format("each technique culls on its own: frustum %d, facing %d, screen size %d, horizon %s commands over the frames",
                        culledBy[TerrainCull.T_FRUSTUM], culledBy[TerrainCull.T_FACING], culledBy[TerrainCull.T_SCREEN], NO_HORIZON ? "skipped (tc.noHorizon)" : culledBy[TerrainCull.T_HORIZON]),
                culledBy[TerrainCull.T_FRUSTUM] > 0 && culledBy[TerrainCull.T_FACING] > 0 && culledBy[TerrainCull.T_SCREEN] > 0 && (NO_HORIZON || culledBy[TerrainCull.T_HORIZON] > 0));
        // metadata path: dirty updates through the scatter on a later frame (a section reset + re-set) keep the image
        dirtyPath(s, d, secs, lay, data, deferred, outRead, tier, NO_HORIZON ? 15 & ~TerrainCull.T_HORIZON : 15);
        freeLayout(lay);
        freeScene(secs);
    }

    static void debugFrustum(Frame fr, TerrainCull.Frame cf, TerrainCommandWriter[] ws, SectionCullData data, TerrainCullModel model, List<Sec> secs) {
        Map<Long, Sec> byPos = new HashMap<>();
        for (Sec sc : secs) byPos.put(key(sc.sx, sc.sy, sc.sz), sc);
        int ox = (int) Math.floor(fr.cx) >> 4, oy = Math.max(MIN_SEC, Math.min(MIN_SEC + SECTIONS_Y - 1, (int) Math.floor(fr.cy) >> 4)), oz = (int) Math.floor(fr.cz) >> 4;
        org.joml.FrustumIntersection fi = new org.joml.FrustumIntersection(fr.mvp);
        int dis = 0, n = 0;
        for (int l = 0; l < 3; l++) {
            TerrainCommandWriter w = ws[l];
            int c = w.totalCommands();
            long cmds = MemoryUtil.nmemAlloc((long) Math.max(1, c) * 20), words = MemoryUtil.nmemAlloc((long) Math.max(1, c) * 4);
            w.copyTo(cmds);
            w.copyWordsTo(words);
            for (int i = 0; i < c; i++) {
                int word = MemoryUtil.memGetInt(words + i * 4L), f = MemoryUtil.memGetInt(cmds + i * 20L + 16);
                boolean v = model.visible(word, f, l);
                double sx = cf.originRelX + (byte) f * 16.0, sy = cf.originRelY + (byte) (f >> 16) * 16.0, sz = cf.originRelZ + (byte) (f >> 8) * 16.0;
                int[] b = CullBounds.unpack(data.word(word & 0x0FFFFFFF, 1 + l));
                boolean j = fi.testAab((float) (sx + b[0]), (float) (sy + b[1]), (float) (sz + b[2]), (float) (sx + b[3]), (float) (sy + b[4]), (float) (sz + b[5]));
                n++;
                if (j && !v) { if (dis++ < 5) System.out.println("    dbg joml-inside model-out: sec " + (sx) + "," + sy + "," + sz + " box " + Arrays.toString(b) + " slot " + (word & 0x0FFFFFFF)); }
                if (!v) { // any vertex of this command inside the frustum?
                    Sec sc = byPos.get(key(ox + (byte) f, oy + (byte) (f >> 16), oz + (byte) (f >> 8)));
                    int facing = word >>> 28;
                    if (sc == null) { if (dis++ < 5) System.out.println("    dbg no section at the decoded position"); continue; }
                    ByteBuffer vb = sc.verts[l][facing];
                    if (vb == null) continue;
                    outer:
                    for (int q = 0; q < vb.remaining() / 64; q++) { // sample each quad on a 9x9 grid
                        float[][] vq = new float[4][3];
                        for (int c4 = 0; c4 < 4; c4++) {
                            long p = MemoryUtil.memAddress(vb) + q * 64L + c4 * 16L;
                            vq[c4][0] = MemoryUtil.memGetShort(p) / 2048f + 4; vq[c4][1] = MemoryUtil.memGetShort(p + 2) / 2048f + 4; vq[c4][2] = MemoryUtil.memGetShort(p + 4) / 2048f + 4;
                        }
                        for (int a1 = 0; a1 <= 8; a1++) for (int a2 = 0; a2 <= 8; a2++) {
                            float u1 = a1 / 8f, u2 = a2 / 8f;
                            float[] pt = new float[3];
                            for (int k = 0; k < 3; k++) pt[k] = (1 - u1) * ((1 - u2) * vq[0][k] + u2 * vq[1][k]) + u1 * ((1 - u2) * vq[3][k] + u2 * vq[2][k]);
                            if (fi.testPoint((float) (sx + pt[0]), (float) (sy + pt[1]), (float) (sz + pt[2]))) {
                                if (dis++ < 8) System.out.println("    dbg culled quad point inside: sec " + sc.sx + "," + sc.sy + "," + sc.sz + " layer " + l + " facing " + facing + " local " + Arrays.toString(pt) + " box " + Arrays.toString(b) + " rel " + sx + "," + sy + "," + sz);
                                break outer;
                            }
                        }
                    }
                }
            }
            MemoryUtil.nmemFree(cmds);
            MemoryUtil.nmemFree(words);
        }
        System.out.println("    dbg frustum: " + dis + " of " + n + " commands culled although JOML says the box intersects");
    }

    /** debug: the read-back compacted commands drawn with vkCmdDrawIndexedIndirect (count from the read-back) */
    static Object[] renderKept(MemoryStack outer, Dev d, Frame fr, Layout lay, TerrainCull tc, TerrainCommandWriter[] ws, HostBuf outRead) {
        try (MemoryStack s = outer.push()) {
            fr.uboOffset = 0;
            fr.indirectOffset = 0;
            long base = 32L << 20;
            MemoryUtil.memCopy(outRead.ptr(), d.indirect.ptr() + base, tc.outCommandOffset() + (long) tc.commands() * 20);
            VkCommandBuffer cmd = beginOnce(s, d);
            VkClearValue.Buffer clear = VkClearValue.calloc(2, s);
            clear.get(0).color().float32(3, 1f);
            clear.get(1).depthStencil().depth(1f);
            VkRenderPassBeginInfo rb = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(d.rp).framebuffer(d.fb).pClearValues(clear);
            rb.renderArea().extent().set(W, H);
            vkCmdBeginRenderPass(cmd, rb, VK_SUBPASS_CONTENTS_INLINE);
            Counts c = new Counts();
            for (int layer : DRAWN) {
                if (layer == L_TRANSLUCENT) { recordWorld(s, d, fr, layer, lay, CPU_WRITER, c, false); continue; }
                TerrainCommandWriter w = ws[layer];
                if (w.draws() == 0) continue;
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, d.pipeWorldOpaque);
                vkCmdBindIndexBuffer(cmd, d.quadsIdx.buf(), 0, VK_INDEX_TYPE_UINT16);
                int ox = (int) Math.floor(fr.cx) >> 4, oy = Math.max(MIN_SEC, Math.min(MIN_SEC + SECTIONS_Y - 1, (int) Math.floor(fr.cy) >> 4)), oz = (int) Math.floor(fr.cz) >> 4;
                push(s, d, (ox << 4) + POS_BASE - fr.cx, (oy << 4) + POS_BASE - fr.cy, (oz << 4) + POS_BASE - fr.cz, c);
                bindUbos(s, d, fr, layer, c);
                long pBuf = s.nmalloc(8, 8), pOff = s.ncalloc(8, 1, 8);
                for (int sg = 0; sg < tc.segments(); sg++) {
                    if (layerOf(tc, tc.segFirst(sg)) != layer) continue;
                    // which writer draw: by position
                    int first = tc.segFirst(sg) - tc.layerBase(layer), dd = 0;
                    while (dd + 1 < w.draws() && w.drawFirst(dd + 1) <= first) dd++;
                    long vb = lay.arena.bufferId(lay.arena.vertexPool(layer), w.drawVertexPage(dd));
                    MemoryUtil.memPutLong(pBuf, vb); nvkCmdBindVertexBuffers(cmd, 0, 1, pBuf, pOff);
                    int n = MemoryUtil.memGetInt(outRead.ptr() + sg * 4L);
                    if (n > 0) vkCmdDrawIndexedIndirect(cmd, d.indirect.buf(), base + tc.outCommandOffset() + (long) tc.segFirst(sg) * 20, n, 20);
                }
            }
            vkCmdEndRenderPass(cmd);
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
            r.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            r.get(0).imageExtent().set(W, H, 1);
            vkCmdCopyImageToBuffer(cmd, d.color[0], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, d.readback.buf(), r);
            submitWait(s, d, cmd);
            byte[] px = new byte[W * H * 4];
            MemoryUtil.memByteBuffer(d.readback.ptr(), px.length).get(px);
            return new Object[]{HexFormat.of().formatHex(sha(px)), px};
        }
    }

    /**
     * Differing pixels classified: {crack, real}. A pixel is a crack when the ray through its centre first enters the
     * height field's solid (the terrain the scene is made of; water and plants are not solid) in a section whose
     * cutout_mipped commands are kept by the cull: the surface really visible there was drawn, so the reference must
     * have seen hidden geometry through a gap between sections (float rounding of section offsets). Anything else is
     * a real miss.
     */
    static int[] crackPixels(Frame fr, byte[] ref, byte[] got, Set<String> kept) {
        Set<String> keptSec = new HashSet<>();
        for (String k : kept) { String[] p = k.split(":"); keptSec.add(p[0] + ":" + p[1]); }
        org.joml.Matrix4f inv = new org.joml.Matrix4f(fr.mvp).invert();
        int ox = (int) Math.floor(fr.cx) >> 4, oy = Math.max(MIN_SEC, Math.min(MIN_SEC + SECTIONS_Y - 1, (int) Math.floor(fr.cy) >> 4)), oz = (int) Math.floor(fr.cz) >> 4;
        int crack = 0, real = 0;
        for (int i = 0; i < ref.length; i += 4) {
            if (ref[i] == got[i] && ref[i + 1] == got[i + 1] && ref[i + 2] == got[i + 2]) continue;
            int px = (i / 4) % W, py = (i / 4) / W;
            org.joml.Vector4f v = new org.joml.Vector4f((px + 0.5f) / W * 2 - 1, (py + 0.5f) / H * 2 - 1, 0.5f, 1f).mul(inv);
            double dx = v.x / v.w, dy = v.y / v.w, dz = v.z / v.w, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= len; dy /= len; dz /= len;
            boolean ok = false;
            for (double t = 0.05; t < 600; t += 0.02) {
                double x = fr.cx + dx * t, y = fr.cy + dy * t, z = fr.cz + dz * t;
                if (y < MIN_Y) break;
                if (solid(x, y, z)) {
                    int sx = (int) Math.floor(x) >> 4, sy = (int) Math.floor(y) >> 4, sz = (int) Math.floor(z) >> 4;
                    int fi = (sx - ox & 0xFF) | (sz - oz & 0xFF) << 8 | (sy - oy & 0xFF) << 16;
                    ok = keptSec.contains(L_CUTOUT_MIPPED + ":" + fi);
                    break;
                }
            }
            if (ok) crack++; else real++;
        }
        return new int[]{crack, real};
    }

    static boolean solid(double x, double y, double z) {
        return y < height((int) Math.floor(x), (int) Math.floor(z)) + 1;
    }

    /** debug: horizon-culled quads with a sample point visible from the camera through the height field */
    static void debugHorizon(Frame fr, TerrainCull.Frame cf, TerrainCommandWriter[] ws, SectionCullData data, TerrainCullModel model, List<Sec> secs) {
        Map<Long, Sec> byPos = new HashMap<>();
        for (Sec sc : secs) byPos.put(key(sc.sx, sc.sy, sc.sz), sc);
        int ox = (int) Math.floor(fr.cx) >> 4, oy = Math.max(MIN_SEC, Math.min(MIN_SEC + SECTIONS_Y - 1, (int) Math.floor(fr.cy) >> 4)), oz = (int) Math.floor(fr.cz) >> 4;
        org.joml.FrustumIntersection fi = new org.joml.FrustumIntersection(fr.mvp);
        int bad = 0, culled = 0;
        for (int l = 0; l < 3; l++) {
            TerrainCommandWriter w = ws[l];
            int c = w.totalCommands();
            long cmds = MemoryUtil.nmemAlloc((long) Math.max(1, c) * 20), words = MemoryUtil.nmemAlloc((long) Math.max(1, c) * 4);
            w.copyTo(cmds);
            w.copyWordsTo(words);
            for (int i = 0; i < c; i++) {
                int word = MemoryUtil.memGetInt(words + i * 4L), f = MemoryUtil.memGetInt(cmds + i * 20L + 16);
                if (model.visible(word, f, l)) continue;
                culled++;
                Sec sc = byPos.get(key(ox + (byte) f, oy + (byte) (f >> 16), oz + (byte) (f >> 8)));
                ByteBuffer vb = sc.verts[l][word >>> 28];
                if (vb == null) continue;
                outer:
                for (int q = 0; q < vb.remaining() / 64; q++) {
                    float[][] vq = new float[4][3];
                    for (int c4 = 0; c4 < 4; c4++) {
                        long p = MemoryUtil.memAddress(vb) + q * 64L + c4 * 16L;
                        vq[c4][0] = MemoryUtil.memGetShort(p) / 2048f + 4; vq[c4][1] = MemoryUtil.memGetShort(p + 2) / 2048f + 4; vq[c4][2] = MemoryUtil.memGetShort(p + 4) / 2048f + 4;
                    }
                    for (int a1 = 1; a1 < 4; a1++) for (int a2 = 1; a2 < 4; a2++) {
                        double u1 = a1 / 4.0, u2 = a2 / 4.0;
                        double[] pt = new double[3];
                        for (int k = 0; k < 3; k++) pt[k] = sc.sx * 16 * (k == 0 ? 1 : 0) + sc.sy * 16 * (k == 1 ? 1 : 0) + sc.sz * 16 * (k == 2 ? 1 : 0)
                                + (1 - u1) * ((1 - u2) * vq[0][k] + u2 * vq[1][k]) + u1 * ((1 - u2) * vq[3][k] + u2 * vq[2][k]);
                        if (a1 == 1 && a2 == 1 && System.getenv("TC_PIX") != null) { // projected box of the command vs a pixel
                            int[] bx = CullBounds.unpack(data.word(word & 0x0FFFFFFF, 1 + l));
                            double mnx = 1e9, mny = 1e9, mxx = -1e9, mxy = -1e9;
                            for (int k8 = 0; k8 < 8; k8++) {
                                org.joml.Vector4f v4 = new org.joml.Vector4f((float) (sc.sx * 16 + ((k8 & 1) != 0 ? bx[3] : bx[0]) - fr.cx), (float) (sc.sy * 16 + ((k8 & 2) != 0 ? bx[4] : bx[1]) - fr.cy),
                                        (float) (sc.sz * 16 + ((k8 & 4) != 0 ? bx[5] : bx[2]) - fr.cz), 1f).mul(fr.mvp);
                                double sx2 = (v4.x / v4.w * 0.5 + 0.5) * W, sy2 = (v4.y / v4.w * 0.5 + 0.5) * H;
                                mnx = Math.min(mnx, sx2); mxx = Math.max(mxx, sx2); mny = Math.min(mny, sy2); mxy = Math.max(mxy, sy2);
                            }
                            for (String pp : System.getenv("TC_PIX").split(";")) {
                                String[] xy = pp.split(",");
                                double px = Integer.parseInt(xy[0]) + 0.5, py = Integer.parseInt(xy[1]) + 0.5;
                                if (px >= mnx && px <= mxx && py >= mny && py <= mxy)
                                    System.out.println("    dbg pixel " + pp + " covered by culled sec " + sc.sx + "," + sc.sy + "," + sc.sz + " layer " + l + " facing " + (word >>> 28) + " flags " + Integer.toHexString(data.word(word & 0x0FFFFFFF, 0)));
                            }
                        }
                        if (!fi.testPoint((float) (pt[0] - fr.cx), (float) (pt[1] - fr.cy), (float) (pt[2] - fr.cz))) continue;
                        // march from the camera to 0.02 before the point
                        double dx = pt[0] - fr.cx, dy = pt[1] - fr.cy, dz = pt[2] - fr.cz, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        boolean blocked = false;
                        for (double t = 0.05; t < len - 0.02; t += 0.05) {
                            if (solid(fr.cx + dx * t / len, fr.cy + dy * t / len, fr.cz + dz * t / len)) { blocked = true; break; }
                        }
                        if (!blocked) {
                            if (bad++ < 6) System.out.println("    dbg horizon culled a visible point: sec " + sc.sx + "," + sc.sy + "," + sc.sz + " layer " + l + " facing " + (word >>> 28)
                                    + " point " + Arrays.toString(pt) + " slot flags " + Integer.toHexString(data.word(word & 0x0FFFFFFF, 0)));
                            break outer;
                        }
                    }
                }
            }
            MemoryUtil.nmemFree(cmds);
            MemoryUtil.nmemFree(words);
        }
        System.out.println("    dbg horizon: " + culled + " culled, " + bad + " with a point visible through the height field (camera " + fr.cx + "," + fr.cy + "," + fr.cz + ")");
    }

    static void dump(byte[] px, String path) {
        try {
            java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < W * H; i++) im.setRGB(i % W, i / W, (px[i * 4] & 255) << 16 | (px[i * 4 + 1] & 255) << 8 | (px[i * 4 + 2] & 255));
            javax.imageio.ImageIO.write(im, "png", new java.io.File(path));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static int candidatesAllFacings(TerrainCommandWriter[] ws) {
        int n = 0;
        for (TerrainCommandWriter w : ws) n += w.totalCommands();
        return n;
    }

    /** frame 1 uploads the whole mirror; then slots change (reset to unknown boxes, then back): the scatter path must apply them */
    static void dirtyPath(MemoryStack s, Dev d, List<Sec> secs, Layout lay, SectionCullData data, List<Runnable> deferred, HostBuf outRead, IndirectDrawCaps.CullTier tier, int tech) {
        TerrainCull tc = new TerrainCull(d.dev, d.mp, 1, tier == IndirectDrawCaps.CullTier.COUNT, deferred::add);
        Cam cam = new Cam("overview", 8.375, 96.25, 8.625, 35, 28, 70);
        Frame fr = frameOf(secs, lay, cam);
        Object[] ref = render(s, d, fr, lay, true, CPU_WRITER, new Counts(), false);
        boolean[] rec = {false};
        TerrainCommandWriter[] ws = writeCull(fr, data, tech, d.maxDraws);
        Object[] a = renderCulled(s, d, fr, lay, tc, cullFrame(fr, tech), data, ws, outRead, deferred, rec);
        int keptFull = keptGpu(tc, ws, outRead, tc.compact()).size();
        // drop the boxes of 40 sections (unknown = the whole range: kept by the box tests) and make them non-opaque
        int changed = 0;
        int[][] saved = new int[40][];
        List<Integer> slots = new ArrayList<>();
        List<Sec> changedSecs = new ArrayList<>();
        for (Sec sc : secs) {
            if (changed == 40) break;
            changedSecs.add(sc);
            int slot = data.gridIndex(sc.sx, sc.sy, sc.sz);
            saved[changed] = new int[]{data.word(slot, 0), data.word(slot, 1), data.word(slot, 2), data.word(slot, 3)};
            slots.add(slot);
            data.sectionReset(slot);
            data.sectionCompiled(slot, sc.sx, sc.sz, -1L, false);
            changed++;
        }
        boolean dirty = !data.fullPending() && data.dirtyCount() == 40;
        Object[] b = renderCulled(s, d, fr, lay, tc, cullFrame(fr, tech), data, ws, outRead, deferred, rec);
        int keptLoose = keptGpu(tc, ws, outRead, tc.compact()).size();
        for (int i = 0; i < slots.size(); i++) { // restore the original words through the same API
            Sec sc = changedSecs.get(i);
            int slot = slots.get(i);
            data.sectionCompiled(slot, sc.sx, sc.sz, (saved[i][0] & SectionCullData.F_OPAQUE) != 0 ? 0L : -1L, false);
            for (int l = 0; l < 3; l++) data.layerBounds(slot, l, saved[i][1 + l]);
        }
        boolean restored = true;
        for (int i = 0; i < slots.size(); i++) for (int k = 0; k < 4; k++) restored &= data.word(slots.get(i), k) == saved[i][k];
        Object[] c = renderCulled(s, d, fr, lay, tc, cullFrame(fr, tech), data, ws, outRead, deferred, rec);
        check(String.format("metadata updates through the per-slot scatter (40 dirty slots, no full upload: %b): kept %d -> %d with unknown boxes, images identical (%b %b %b)",
                        dirty, keptFull, keptLoose, a[0].equals(ref[0]), b[0].equals(ref[0]), c[0].equals(ref[0])),
                dirty && restored && keptLoose >= keptFull && a[0].equals(ref[0]) && b[0].equals(ref[0]) && c[0].equals(ref[0]));
        for (TerrainCommandWriter w : ws) w.free();
        tc.destroyNow();
    }
}
