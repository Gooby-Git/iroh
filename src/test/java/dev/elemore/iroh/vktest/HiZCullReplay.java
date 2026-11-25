package dev.elemore.iroh.vktest;

import dev.elemore.iroh.vktest.TerrainCullReplay.Cam;
import dev.elemore.iroh.vktest.TerrainWorldReplay.*;
import net.vulkanmod.render.chunk.buffer.TerrainCommandWriter;
import net.vulkanmod.render.chunk.buffer.WorldArena;
import net.vulkanmod.render.chunk.cull.CullBounds;
import net.vulkanmod.render.chunk.cull.HiZPyramid;
import net.vulkanmod.render.chunk.cull.SectionCullData;
import net.vulkanmod.render.chunk.cull.TerrainCull;
import net.vulkanmod.render.shader.ShaderLoadUtil;
import net.vulkanmod.vulkan.device.IndirectDrawCaps;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static dev.elemore.iroh.vktest.TerrainWorldReplay.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * P2-7c (L5c): two-pass Hi-Z occlusion (TerrainCull T_HIZ + HiZPyramid) on lavapipe + VK_LAYER_KHRONOS_validation, frame
 * sequences as the game records them. ./gradlew hizCullReplay [-Phz.quick] [-Phz.noHorizon] [-Pvalidate=gpuav]
 *
 * <p>Scene: the P2-7a/7b generated hills (occluders: hills, enclosed cave rooms below the surface, water, plants, glass),
 * every section a candidate. Recorded camera PATHS, each frame using the pyramid the previous frame built (state carried as
 * in the game): walking with fast turns (+90, +90, -180 degrees per frame), rising over a crest (disocclusion by parallax),
 * teleports and a pitch whip from the sky down. Per frame, as WorldRenderer does: pass 1 (P2-7b techniques + Hi-Z against
 * the previous pyramid, flags), opaque draws, pass break: pyramid of the current depth + retest, pass 2 draws; then with
 * translucent Hi-Z on: pass break: pyramid again + translucent cull, translucent draws (else the CPU path).
 *
 * <p>Oracles per frame and option combination (16 P2-7b technique sets x translucent on/off on the main configuration;
 * COUNT and FIXED tiers, D32F and D24S8 depth, minmax and shader reduction, standard and reversed-Z on the others):
 * (1) the image equals the CPU path's (every candidate drawn) apart from P2-7b's classified 1 px cracks; (2) RAY-CAST
 * oracle: the first solid face (and the water surface) along every pixel-centre ray through the height field must be in
 * the drawn set (pass 1 + pass 2; translucent kept set), pixels whose ray passes a plant or glass block or hits within
 * 0.01 block of a face edge are not judged; (3) the pyramid read back equals a CPU max (min) reduction of the read-back
 * depth; (4) the retest's kept set equals a Java model of the Hi-Z test on the read-back pyramid (borderline allowed);
 * (5) negative control: the same frames WITHOUT the retest draws lose visible geometry (shows the oracles can fail and the
 * retest is what catches disocclusions); (6) 0 validation errors. Reports the commands / sections culled by Hi-Z.
 * Last line "# hizcullreplay PASS|FAIL|SKIP".
 */
public final class HiZCullReplay {
    static final boolean QUICK = Boolean.getBoolean("hz.quick");
    /** GPU-AV instrumentation crashes lavapipe in the horizon build shader (TerrainCullReplay NO_HORIZON) */
    static final boolean NO_HORIZON = Boolean.getBoolean("hz.noHorizon");
    static final int RD = TerrainCullReplay.RD;
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
            TerrainWorldReplay.CULL_MODE = VK_CULL_MODE_BACK_BIT;
            TerrainWorldReplay.FRONT_FACE = VK_FRONT_FACE_CLOCKWISE;
            Dev d = device(s, Float16Repro.phys, code, 0);
            IndirectDrawCaps.CullTier tier = IndirectDrawCaps.cullTier();
            System.out.println("# hizcull-tier " + tier + " " + IndirectDrawCaps.report());
            if (d.dev == null || tier == IndirectDrawCaps.CullTier.CPU) {
                System.out.println("# hizcullreplay SKIP tier=" + tier + ": terrain is drawn directly (T-C): no world arena, no GPU cull, no Hi-Z");
                System.exit(0);
            }
            int before = Float16Repro.ERRORS.size();
            run(s, d, code, tier);
            d.close();
            int errs = Float16Repro.ERRORS.size() - before;
            for (String e : Float16Repro.ERRORS.subList(before, Math.min(Float16Repro.ERRORS.size(), before + 5)))
                System.out.println("  ! vk " + e.substring(0, Math.min(240, e.length())).replace("\n", " "));
            check("validation (" + MODE + "): 0 errors (" + errs + ")", errs == 0);
        }
        System.out.println("# hizcullreplay " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail) + " mode=" + MODE + " path=" + IndirectDrawCaps.hiZPath().name().toLowerCase());
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------------------ depth targets

    /** a sampled depth image + clear / load render passes (compatible with the replay's pass) + world pipelines drawn with them */
    static final class Target {
        final int format;
        final boolean reversed;
        long depthImg, fb, rpClear, rpLoad, pipeOpaque, pipeTrans;

        Target(int format, boolean reversed) { this.format = format; this.reversed = reversed; }

        String name() { return (this.format == VK_FORMAT_D32_SFLOAT ? "D32F" : "D24S8") + (this.reversed ? " reversed-Z" : ""); }

        int aspect() { return this.format == VK_FORMAT_D32_SFLOAT ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT; }
    }

    static Target target(MemoryStack outer, Dev d, ByteBuffer[] code, int format, boolean reversed) {
        Target t = new Target(format, reversed);
        try (MemoryStack s = outer.push()) {
            long[] dimg = image(s, d, W, H, format, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, t.aspect());
            t.depthImg = dimg[0];
            t.rpClear = pass(s, d, format, true);
            t.rpLoad = pass(s, d, format, false);
            LongBuffer lb = s.mallocLong(1);
            ck(vkCreateFramebuffer(d.dev, VkFramebufferCreateInfo.calloc(s).sType$Default().renderPass(t.rpClear).pAttachments(s.longs(d.color[1], dimg[1]))
                    .width(W).height(H).layers(1), null, lb), "framebuffer");
            t.fb = lb.get(0);
            long fb = t.fb;
            VkDevice dev = d.dev;
            d.undo.push(() -> vkDestroyFramebuffer(dev, fb, null));
            long vs = TerrainIndirectRepro.module(s, dev, code[1]), fsO = TerrainIndirectRepro.module(s, dev, code[2]), fsT = TerrainIndirectRepro.module(s, dev, code[3]);
            d.undo.push(() -> { for (long m : new long[]{vs, fsO, fsT}) vkDestroyShaderModule(dev, m, null); });
            long rp0 = d.rp;
            int cmp0 = TerrainWorldReplay.DEPTH_COMPARE;
            d.rp = t.rpClear;
            TerrainWorldReplay.DEPTH_COMPARE = reversed ? VK_COMPARE_OP_GREATER_OR_EQUAL : VK_COMPARE_OP_LESS_OR_EQUAL;
            t.pipeOpaque = pipeline(s, d, vs, fsO, false);
            t.pipeTrans = pipeline(s, d, vs, fsT, true);
            d.rp = rp0;
            TerrainWorldReplay.DEPTH_COMPARE = cmp0;
        }
        return t;
    }

    /** colour + depth, same subpass and dependencies as TerrainWorldReplay's pass (render pass compatibility); clear or load */
    static long pass(MemoryStack outer, Dev d, int depthFormat, boolean clear) {
        try (MemoryStack s = outer.push()) {
            VkAttachmentDescription.Buffer at = VkAttachmentDescription.calloc(2, s);
            at.get(0).format(VK_FORMAT_R8G8B8A8_UNORM).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(clear ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            at.get(1).format(depthFormat).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(clear ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
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
            LongBuffer lb = s.mallocLong(1);
            ck(vkCreateRenderPass(d.dev, VkRenderPassCreateInfo.calloc(s).sType$Default().pAttachments(at).pSubpasses(sp).pDependencies(dep), null, lb), "render pass");
            long rp = lb.get(0);
            VkDevice dev = d.dev;
            d.undo.push(() -> vkDestroyRenderPass(dev, rp, null));
            return rp;
        }
    }

    static void beginPass(MemoryStack s, VkCommandBuffer cmd, Target t, boolean clear) {
        VkClearValue.Buffer cv = VkClearValue.calloc(2, s);
        cv.get(0).color().float32(3, 1f); // black
        cv.get(1).depthStencil().depth(t.reversed ? 0f : 1f);
        VkRenderPassBeginInfo rb = VkRenderPassBeginInfo.calloc(s).sType$Default().renderPass(clear ? t.rpClear : t.rpLoad).framebuffer(t.fb).pClearValues(cv);
        rb.renderArea().extent().set(W, H);
        vkCmdBeginRenderPass(cmd, rb, VK_SUBPASS_CONTENTS_INLINE);
    }

    static final int READ_LAYOUT = VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;

    /** WorldRenderer.hizBuildInBreak's barriers: attachment -> read-only for compute, and back for the next pass */
    static void depthToRead(MemoryStack s, VkCommandBuffer cmd, Target t, boolean toRead) {
        int frag = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
        VkImageMemoryBarrier.Buffer ib = VkImageMemoryBarrier.calloc(1, s).sType$Default()
                .srcAccessMask(toRead ? VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT : 0)
                .dstAccessMask(toRead ? VK_ACCESS_SHADER_READ_BIT : VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                .oldLayout(toRead ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL : READ_LAYOUT)
                .newLayout(toRead ? READ_LAYOUT : VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(t.depthImg);
        ib.subresourceRange().aspectMask(t.aspect()).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(cmd, toRead ? frag : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                toRead ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT : frag, 0, null, null, ib);
    }

    /** a depth target never drawn this command buffer: UNDEFINED -> read-only (contents irrelevant to the check that uses it) */
    static void depthToReadFromUndefined(MemoryStack s, VkCommandBuffer cmd, Target t) {
        VkImageMemoryBarrier.Buffer ib = VkImageMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(0).dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(READ_LAYOUT)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(t.depthImg);
        ib.subresourceRange().aspectMask(t.aspect()).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, ib);
    }

    // ------------------------------------------------------------------------------------------------ paths

    record Path(String name, List<Cam> cams) {}

    static List<Path> paths() {
        List<Path> ps = new ArrayList<>();
        // walking along x over the hills, near the ground, with fast turns: +90, +90, -180 in one frame each
        List<Cam> walk = new ArrayList<>();
        float[] yaws = {60, 63, 153, 243, 63, 68, 158, 160};
        for (int i = 0; i < (QUICK ? 6 : yaws.length); i++) {
            double x = -20.25 + i * 1.5, z = 4.5 + i * 0.75;
            walk.add(new Cam("walk-" + i, x, height((int) Math.floor(x), (int) Math.floor(z)) + 2.6, z, yaws[i], 3 + (i & 1), 70));
        }
        ps.add(new Path("walk + fast turns", walk));
        if (QUICK) return ps;
        // a valley toward a hill, then rising over the crest (sections behind it appear by parallax), then a turn
        List<Cam> crest = new ArrayList<>();
        double vy = height(40, -30) + 1.7;
        for (int i = 0; i < 6; i++) crest.add(new Cam("crest-" + i, 40.6 - i * 0.8, vy + i * 5.5, -30.2 + i * 0.4, -150 + i * 2, 5 - i, 70));
        crest.add(new Cam("crest-turn", 36.6, vy + 33, -28.2, -60, 10, 70));
        ps.add(new Path("rise over a crest", crest));
        // teleports between far apart spots (the previous pyramid shows another place entirely)
        List<Cam> tp = new ArrayList<>();
        tp.add(new Cam("tp-0", 8.375, 96.25, 8.625, 35, 28, 70));
        tp.add(new Cam("tp-1", 60.5, height(60, -40) + 3.0, -40.25, 200, 4, 70));
        tp.add(new Cam("tp-2", -70.5, height(-70, 50) + 2.2, 50.5, 110, 2, 90));
        tp.add(new Cam("tp-3", -70.5, height(-70, 50) + 2.2, 50.5, 112, 2, 90));
        tp.add(new Cam("tp-4", 8.375, 96.25, 8.625, 35, 28, 70));
        ps.add(new Path("teleports", tp));
        // looking at the sky, whipping down onto the terrain, wide FOV
        List<Cam> whip = new ArrayList<>();
        whip.add(new Cam("whip-0", 0.5, 90.5, 0.5, 10, -80, 70));
        whip.add(new Cam("whip-1", 0.5, 90.5, 0.5, 12, 40, 70));
        whip.add(new Cam("whip-2", 0.5, 90.5, 0.5, 100, 35, 110));
        whip.add(new Cam("whip-3", 3.5, 88.5, -2.25, 200, 30, 150));
        ps.add(new Path("pitch whip + wide FOV", whip));
        return ps;
    }

    static Frame frameOf(List<Sec> secs, Layout lay, Cam c, boolean reversed) {
        Frame f = TerrainCullReplay.frameOf(secs, lay, c);
        if (reversed) f.mvp = new Matrix4f().m22(-1f).m32(1f).mul(f.mvp); // z' = w - z: depth 1 at the near plane, 0 far
        return f;
    }

    // ------------------------------------------------------------------------------------------------ the ray-cast oracle

    static final int GEN = (RD + 1) * 16; // blocks of generated terrain: chunk -RD .. RD

    static boolean generated(int x, int z) {
        return x >= -RD * 16 && x < GEN && z >= -RD * 16 && z < GEN;
    }

    static boolean solidBlock(int x, int y, int z) {
        return generated(x, z) && y <= height(x, z) && y >= MIN_Y;
    }

    /** a plant or glass cube stands in this block (TerrainWorldReplay.surface) */
    static boolean decor(int x, int y, int z) {
        if (!generated(x, z)) return false;
        int h = height(x, z);
        if (h < SEA || y != h + 1) return false;
        int hb = hash(x, 0, z);
        return hb % 100 < 12 || (hb >> 8) % 1000 < 15;
    }

    /**
     * Per pixel centre: the first solid face along the ray (DDA through blocks), as "1:slot:facing" (cutout_mipped), and the
     * water surface it crosses first from above as "3:slot:6". Pixels whose ray passes a decor block or hits within 0.01
     * block of a face edge are not judged.
     */
    static Map<String, Integer> required(Frame fr, SectionCullData data) {
        Map<String, Integer> req = new HashMap<>();
        Matrix4f inv = new Matrix4f(fr.mvp).invert();
        Vector4f v = new Vector4f();
        for (int py = 0; py < H; py++)
            for (int px = 0; px < W; px++) {
                v.set((px + 0.5f) / W * 2 - 1, (py + 0.5f) / H * 2 - 1, 0.5f, 1f).mul(inv);
                double dx = v.x / v.w, dy = v.y / v.w, dz = v.z / v.w, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                cast(fr.cx, fr.cy, fr.cz, dx / len, dy / len, dz / len, data, req);
            }
        return req;
    }

    static void cast(double ox, double oy, double oz, double dx, double dy, double dz, SectionCullData data, Map<String, Integer> req) {
        int bx = (int) Math.floor(ox), by = (int) Math.floor(oy), bz = (int) Math.floor(oz);
        if (solidBlock(bx, by, bz)) return;
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dx), tdy = dy == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dy), tdz = dz == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dz);
        double tx = dx == 0 ? Double.POSITIVE_INFINITY : (sx > 0 ? bx + 1 - ox : ox - bx) * tdx;
        double ty = dy == 0 ? Double.POSITIVE_INFINITY : (sy > 0 ? by + 1 - oy : oy - by) * tdy;
        double tz = dz == 0 ? Double.POSITIVE_INFINITY : (sz > 0 ? bz + 1 - oz : oz - bz) * tdz;
        String water = null;
        for (int it = 0; it < 4000; it++) {
            double t;
            int face;
            if (tx < ty && tx < tz) { bx += sx; t = tx; tx += tdx; face = sx > 0 ? 1 : 0; }
            else if (ty < tz) { by += sy; t = ty; ty += tdy; face = sy > 0 ? 3 : 2; }
            else { bz += sz; t = tz; tz += tdz; face = sz > 0 ? 5 : 4; }
            if (t > 420 || by < MIN_Y || by > 400) return;
            if (decor(bx, by, bz)) return; // plants / glass: alpha-tested or translucent, not judged
            if (sy < 0 && face == 2 && by == SEA && generated(bx, bz) && height(bx, bz) < SEA && water == null) {
                if (edge(ox + dx * t, oz + dz * t)) return;
                water = "3:" + data.gridIndex(Math.floorDiv(bx, 16), Math.floorDiv(SEA, 16), Math.floorDiv(bz, 16)) + ":6";
            }
            if (solidBlock(bx, by, bz)) {
                double hx = ox + dx * t, hy = oy + dy * t, hz = oz + dz * t;
                boolean e = face < 2 ? edge(hy, hz) : face < 4 ? edge(hx, hz) : edge(hx, hy);
                if (e) return;
                req.merge("1:" + data.gridIndex(Math.floorDiv(bx, 16), Math.floorDiv(by, 16), Math.floorDiv(bz, 16)) + ":" + face, 1, Integer::sum);
                if (water != null) req.merge(water, 1, Integer::sum);
                return;
            }
        }
    }

    static boolean edge(double a, double b) {
        double fa = a - Math.floor(a), fb = b - Math.floor(b);
        return fa < 0.01 || fa > 0.99 || fb < 0.01 || fb > 0.99;
    }

    // ------------------------------------------------------------------------------------------------ command identity

    /** command fields "layer:firstInstance:vertexOffset:firstIndex" -> "layer:slot:facing" for every candidate of the frame */
    static Map<String, String> identities(TerrainCommandWriter[] ws, int[] layerIds) {
        Map<String, String> id = new HashMap<>();
        for (int l = 0; l < ws.length; l++) {
            TerrainCommandWriter w = ws[l];
            int n = w == null ? 0 : w.totalCommands();
            if (n == 0) continue;
            long cmds = MemoryUtil.nmemAlloc((long) n * 20), words = MemoryUtil.nmemAlloc((long) n * 4);
            w.copyTo(cmds);
            w.copyWordsTo(words);
            for (int i = 0; i < n; i++) {
                int word = MemoryUtil.memGetInt(words + i * 4L);
                id.put(cmdKey(layerIds[l], cmds + i * 20L), layerIds[l] + ":" + (word & TerrainCommandWriter.WORD_SLOT_MASK) + ":" + (word >>> TerrainCommandWriter.WORD_FACING_SHIFT));
            }
            MemoryUtil.nmemFree(cmds);
            MemoryUtil.nmemFree(words);
        }
        return id;
    }

    static String cmdKey(int layer, long p) {
        return layer + ":" + MemoryUtil.memGetInt(p + 16) + ":" + MemoryUtil.memGetInt(p + 12) + ":" + MemoryUtil.memGetInt(p + 8);
    }

    /** commands of one region of a job's out buffer (read back): compacted per segment (COUNT) or instanceCount != 0 (FIXED) */
    static void region(long out, long cntOff, long cmdOff, int segs, int[] segFirst, int[] segCount, int[] segLayer, boolean compact, Set<String> into) {
        for (int sg = 0; sg < segs; sg++) {
            int first = segFirst[sg], n = compact ? MemoryUtil.memGetInt(out + cntOff + sg * 4L) : segCount[sg];
            for (int i = 0; i < n; i++) {
                long p = out + cmdOff + (long) (first + i) * 20;
                if (!compact && MemoryUtil.memGetInt(p + 4) == 0) continue;
                into.add(cmdKey(segLayer[sg], p));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ one culled frame

    static final class Result {
        String sha;
        byte[] px;
        final Set<String> drawn1 = new HashSet<>(), drawn2 = new HashSet<>(), flagged = new HashSet<>(), trans = new HashSet<>();
        boolean recorded, retested, transRecorded, pyramidBuilt;
        int candidates, transCandidates;
        float[][] pyramid; // levels, read back after the retest's build (check frames)
        float[] depth;
        Map<String, String> ids;
        TerrainCommandWriter[] ws;
        TerrainCommandWriter tw;
    }

    static final class Ctx {
        Dev d;
        Layout lay;
        SectionCullData data;
        HostBuf outRead, transRead, pyrRead, depthRead, countShim;
        List<Runnable> deferred = new ArrayList<>();
        long serial;
    }

    /** the translucent writer as WorldRenderer.drawTranslucentCulled writes it (areas and sections back to front, cull words) */
    static TerrainCommandWriter writeTranslucent(Frame fr, SectionCullData data, int maxDraws) {
        TerrainCommandWriter w = new TerrainCommandWriter();
        int ox = (int) Math.floor(fr.cx) >> 4, oy = Math.max(MIN_SEC, Math.min(MIN_SEC + SECTIONS_Y - 1, (int) Math.floor(fr.cy) >> 4)), oz = (int) Math.floor(fr.cz) >> 4;
        w.beginCull(true, ox, oy, oz, maxDraws);
        int layer = L_TRANSLUCENT;
        long base0 = (long) layer * AREA_SECTIONS * FACINGS * PSTRIDE;
        int[] sec = new int[512], mask = new int[512], fi = new int[512], slot = new int[512];
        List<Area> areas = fr.areas;
        for (int ai = areas.size() - 1; ai >= 0; ai--) {
            Area a = areas.get(ai);
            if (a.layerRanges[layer] == 0) continue;
            int count = 0;
            for (int k = a.queue.size() - 1; k >= 0; k--) {
                Sec sc = a.queue.get(k);
                int f = w.encode(sc.sx, sc.sy, sc.sz);
                if (f < 0) continue;
                sec[count] = a.inArea.get(k);
                mask[count] = 1 << 6;
                slot[count] = data.gridIndex(sc.sx, sc.sy, sc.sz);
                fi[count++] = f;
            }
            w.addAreaCull(a.wparams + base0, count, sec, mask, fi, slot, a.iPage, layer * AREA_SECTIONS);
        }
        w.finish();
        return w;
    }

    static void push(MemoryStack s, Dev d, Frame fr) {
        int ox = (int) Math.floor(fr.cx) >> 4, oy = Math.max(MIN_SEC, Math.min(MIN_SEC + SECTIONS_Y - 1, (int) Math.floor(fr.cy) >> 4)), oz = (int) Math.floor(fr.cz) >> 4;
        TerrainWorldReplay.push(s, d, (ox << 4) + POS_BASE - fr.cx, (oy << 4) + POS_BASE - fr.cy, (oz << 4) + POS_BASE - fr.cz, new Counts());
    }

    interface DrawFn { void draw(VkCommandBuffer cmd, int d); }

    /** the bound pipeline's push constant + UBOs, then per writer draw its vertex page and index source, then {@code fn} */
    static void drawWriter(MemoryStack outer, Ctx c, Frame fr, int layer, long pipe, TerrainCommandWriter w, DrawFn fn) {
        try (MemoryStack s = outer.push()) {
            VkCommandBuffer cmd = c.d.cmd;
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
            vkCmdBindIndexBuffer(cmd, c.d.quadsIdx.buf(), 0, VK_INDEX_TYPE_UINT16);
            push(s, c.d, fr);
            bindUbos(s, c.d, fr, layer, new Counts());
            WorldArena arena = c.lay.arena;
            long pBuf = s.nmalloc(8, 8), pOff = s.ncalloc(8, 1, 8), boundV = 0;
            int boundKey = TerrainCommandWriter.QUADS;
            for (int k = 0; k < w.draws(); k++) {
                long vb = arena.bufferId(arena.vertexPool(layer), w.drawVertexPage(k));
                if (vb != boundV) { MemoryUtil.memPutLong(pBuf, vb); nvkCmdBindVertexBuffers(cmd, 0, 1, pBuf, pOff); boundV = vb; }
                int key = w.drawIndexKey(k);
                if (key != boundKey) {
                    if (key == TerrainCommandWriter.QUADS) vkCmdBindIndexBuffer(cmd, c.d.quadsIdx.buf(), 0, VK_INDEX_TYPE_UINT16);
                    else vkCmdBindIndexBuffer(cmd, arena.bufferId(arena.indexPool(), key), 0, VK_INDEX_TYPE_UINT16);
                    boundKey = key;
                }
                fn.draw(cmd, k);
            }
        }
    }

    /** the CPU path (P2-7a) for a layer, drawn with the target's pipelines */
    static void cpuLayer(MemoryStack s, Ctx c, Frame fr, Target t, int layer) {
        long o = c.d.pipeWorldOpaque, tr = c.d.pipeWorldTrans;
        c.d.pipeWorldOpaque = t.pipeOpaque;
        c.d.pipeWorldTrans = t.pipeTrans;
        recordWorld(s, c.d, fr, layer, c.lay, TerrainCullReplay.CPU_WRITER, new Counts(), false);
        c.d.pipeWorldOpaque = o;
        c.d.pipeWorldTrans = tr;
    }

    static Object[] readColor(MemoryStack s, Ctx c, VkCommandBuffer cmd) {
        VkImageMemoryBarrier.Buffer ib = VkImageMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(c.d.color[0]);
        ib.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, ib);
        VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(1, s);
        r.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        r.get(0).imageExtent().set(W, H, 1);
        vkCmdCopyImageToBuffer(cmd, c.d.color[0], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, c.d.readback.buf(), r);
        return null;
    }

    static void hostBarrier(MemoryStack s, VkCommandBuffer cmd) {
        VkMemoryBarrier.Buffer hb = VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, hb, null, null);
    }

    static byte[] pixels(Ctx c) {
        byte[] px = new byte[W * H * 4];
        MemoryUtil.memByteBuffer(c.d.readback.ptr(), px.length).get(px);
        return px;
    }

    /** the reference: every candidate through the CPU path, one pass */
    static Object[] reference(MemoryStack outer, Ctx c, Frame fr, Target t) {
        try (MemoryStack s = outer.push()) {
            fr.uboOffset = 0;
            fr.indirectOffset = 0;
            VkCommandBuffer cmd = beginOnce(s, c.d);
            TerrainWorldReplay.COUNT_SHIM = null;
            beginPass(s, cmd, t, true);
            for (int layer : DRAWN) cpuLayer(s, c, fr, t, layer);
            vkCmdEndRenderPass(cmd);
            readColor(s, c, cmd);
            hostBarrier(s, cmd);
            submitWait(s, c.d, cmd);
            byte[] px = pixels(c);
            return new Object[]{HexFormat.of().formatHex(sha(px)), px};
        }
    }

    /**
     * One game frame through the GPU cull with Hi-Z (and the translucent cull when {@code transOn}). {@code skipPass2}: the
     * negative control (the retest runs, its draws are left out). {@code readPyramid}: copy depth + pyramid after the build.
     */
    static Result culledFrame(MemoryStack outer, Ctx c, TerrainCull tc, Target t, Frame fr, TerrainCull.Frame cf, boolean transOn, boolean skipPass2, boolean readPyramid) {
        Result r = new Result();
        try (MemoryStack s = outer.push()) {
            fr.uboOffset = 0;
            fr.indirectOffset = 0;
            long serial = ++c.serial;
            r.ws = TerrainCullReplay.writeCull(fr, c.data, cf.techniques, c.d.maxDraws);
            for (TerrainCommandWriter w : r.ws) r.candidates += w.totalCommands();
            r.tw = transOn ? writeTranslucent(fr, c.data, c.d.maxDraws) : null;
            r.ids = identities(r.ws, new int[]{0, 1, 2});
            if (r.tw != null) r.ids.putAll(identities(new TerrainCommandWriter[]{r.tw}, new int[]{L_TRANSLUCENT}));
            r.transCandidates = r.tw == null ? 0 : r.tw.totalCommands();
            VkCommandBuffer cmd = beginOnce(s, c.d);
            r.recorded = tc.record(cmd, 0, serial, cf, c.data, r.ws);
            TerrainWorldReplay.COUNT_SHIM = tc.compact() ? c.countShim : null; // lavapipe's stale count buffer (terrainCullReplay)
            TerrainWorldReplay.countShimOff = 0;
            beginPass(s, cmd, t, true);
            for (int layer : TerrainCullReplay.OPAQUE) {
                if (!r.recorded) { cpuLayer(s, c, fr, t, layer); continue; }
                TerrainCommandWriter w = r.ws[layer];
                if (w.draws() == 0) continue;
                drawWriter(s, c, fr, layer, t.pipeOpaque, w, (k, dd) -> tc.drawCulled(k, layer, dd, w));
            }
            vkCmdEndRenderPass(cmd);
            // WorldRenderer.afterOpaqueLayers: pass break, pyramid of pass 1's depth, retest, pass 2
            if (tc.retestPending()) {
                depthToRead(s, cmd, t, true);
                boolean built = tc.buildPyramid(cmd, 0, serial, t, t.depthImg, t.format, READ_LAYOUT, W, H);
                if (readPyramid && built) copyPyramid(s, c, cmd, tc.hiz(), t);
                r.retested = tc.recordRetest(cmd, 0, serial, built);
                r.pyramidBuilt = built;
                depthToRead(s, cmd, t, false);
                beginPass(s, cmd, t, false);
                if (!skipPass2)
                    for (int layer : TerrainCullReplay.OPAQUE) {
                        TerrainCommandWriter w = r.ws[layer];
                        if (w.draws() == 0) continue;
                        drawWriter(s, c, fr, layer, t.pipeOpaque, w, (k, dd) -> tc.drawRetest(k, layer, dd, w));
                    }
                vkCmdEndRenderPass(cmd);
            }
            // translucent: WorldRenderer.drawTranslucentCulled (pyramid of the depth now, translucent cull) or the CPU path
            if (r.tw != null && r.tw.draws() > 0) {
                depthToRead(s, cmd, t, true);
                boolean built = tc.buildPyramid(cmd, 0, serial, t, t.depthImg, t.format, READ_LAYOUT, W, H);
                TerrainCull.Frame tf = copyFrame(cf);
                tf.techniques = (cf.techniques & (TerrainCull.T_FRUSTUM | TerrainCull.T_SCREEN)) | TerrainCull.T_HIZ;
                r.transRecorded = tc.recordTranslucent(cmd, 0, serial, tf, c.data, r.tw, built);
                depthToRead(s, cmd, t, false);
            }
            beginPass(s, cmd, t, false);
            if (r.transRecorded) drawWriter(s, c, fr, L_TRANSLUCENT, t.pipeTrans, r.tw, (k, dd) -> tc.drawTranslucent(k, dd, r.tw));
            else cpuLayer(s, c, fr, t, L_TRANSLUCENT);
            vkCmdEndRenderPass(cmd);
            readColor(s, c, cmd);
            // the cull outputs (after the draws read them)
            VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, mb, null, null);
            if (r.recorded) vkCmdCopyBuffer(cmd, tc.outBuffer(), c.outRead.buf(), VkBufferCopy.calloc(1, s).size(Math.min(tc.outBytes(), c.outRead.size())));
            if (r.transRecorded) {
                long bytes = tc.translucentCommandOffset() + (long) r.tw.totalCommands() * 20;
                vkCmdCopyBuffer(cmd, tc.translucentOutBuffer(), c.transRead.buf(), VkBufferCopy.calloc(1, s).size(Math.min(bytes, c.transRead.size())));
            }
            hostBarrier(s, cmd);
            submitWait(s, c.d, cmd);
            TerrainWorldReplay.COUNT_SHIM = null;
            for (Runnable q : c.deferred) q.run();
            c.deferred.clear();
            r.px = pixels(c);
            r.sha = HexFormat.of().formatHex(sha(r.px));
            if (r.recorded) readOpaque(tc, r);
            if (r.transRecorded) readTranslucent(tc, r);
            if (readPyramid && r.retested && r.pyramidBuilt) readBackPyramid(c, tc.hiz(), t, r);
        }
        return r;
    }

    static TerrainCull.Frame copyFrame(TerrainCull.Frame a) {
        TerrainCull.Frame b = new TerrainCull.Frame();
        System.arraycopy(a.mvp, 0, b.mvp, 0, 16);
        System.arraycopy(a.prevMvp, 0, b.prevMvp, 0, 16);
        b.originRelX = a.originRelX; b.originRelY = a.originRelY; b.originRelZ = a.originRelZ;
        b.vpX = a.vpX; b.vpY = a.vpY; b.vpW = a.vpW; b.vpH = a.vpH;
        b.camSecX = a.camSecX; b.camSecY = a.camSecY; b.camSecZ = a.camSecZ;
        b.camFracX = a.camFracX; b.camFracY = a.camFracY; b.camFracZ = a.camFracZ;
        b.techniques = a.techniques; b.horizonRadius = a.horizonRadius;
        b.depthW = a.depthW; b.depthH = a.depthH;
        b.prevShiftX = a.prevShiftX; b.prevShiftY = a.prevShiftY; b.prevShiftZ = a.prevShiftZ;
        b.prevValid = a.prevValid;
        return b;
    }

    static void readOpaque(TerrainCull tc, Result r) {
        HostBuf rb = CTX.outRead;
        int segs = tc.segments();
        int[] first = new int[segs], count = new int[segs], layer = new int[segs];
        for (int i = 0; i < segs; i++) {
            first[i] = tc.segFirst(i);
            count[i] = tc.segCount(i);
            int l = 0;
            while (l < 2 && first[i] >= tc.layerBase(l + 1)) l++;
            layer[i] = l;
        }
        region(rb.ptr(), 0, tc.outCommandOffset(), segs, first, count, layer, tc.compact(), r.drawn1);
        if (r.retested) {
            region(rb.ptr(), tc.outCounts2Offset(), tc.outCommands2Offset(), segs, first, count, layer, tc.compact(), r.drawn2);
            // flagged commands: their original fields from the writers (flags region, one uint per command)
            long cmds = MemoryUtil.nmemAlloc((long) Math.max(1, tc.commands()) * 20);
            for (int l = 0; l < 3; l++) if (r.ws[l].totalCommands() > 0) r.ws[l].copyTo(cmds + (long) tc.layerBase(l) * 20);
            for (int i = 0; i < segs; i++)
                for (int k = 0; k < count[i]; k++)
                    if (MemoryUtil.memGetInt(rb.ptr() + tc.outFlagsOffset() + (long) (first[i] + k) * 4) != 0)
                        r.flagged.add(cmdKey(layer[i], cmds + (long) (first[i] + k) * 20));
            MemoryUtil.nmemFree(cmds);
        }
    }

    static void readTranslucent(TerrainCull tc, Result r) {
        int segs = tc.translucentSegments();
        int[] first = new int[segs], count = new int[segs], layer = new int[segs];
        for (int i = 0; i < segs; i++) { first[i] = tc.translucentSegFirst(i); count[i] = tc.translucentSegCount(i); layer[i] = L_TRANSLUCENT; }
        region(CTX.transRead.ptr(), 0, tc.translucentCommandOffset(), segs, first, count, layer, tc.compact(), r.trans);
    }

    static Ctx CTX;

    // ------------------------------------------------------------------------------------------------ pyramid readback + model

    static void copyPyramid(MemoryStack outer, Ctx c, VkCommandBuffer cmd, HiZPyramid hz, Target t) {
        try (MemoryStack s = outer.push()) {
            VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, s).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, mb, null, null);
            long off = 0;
            int w = hz.width0(), h = hz.height0();
            VkBufferImageCopy.Buffer r = VkBufferImageCopy.calloc(hz.levels(), s);
            for (int k = 0; k < hz.levels(); k++) {
                r.get(k).bufferOffset(off).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(k).layerCount(1);
                r.get(k).imageExtent().set(w, h, 1);
                off += (long) w * h * 4;
                w = Math.max(1, (w + 1) >> 1);
                h = Math.max(1, (h + 1) >> 1);
            }
            vkCmdCopyImageToBuffer(cmd, hz.image(), VK_IMAGE_LAYOUT_GENERAL, c.pyrRead.buf(), r);
            // the depth: read-only -> transfer source and back (copies need TRANSFER_SRC or GENERAL)
            for (int pass = 0; pass < 2; pass++) {
                VkImageMemoryBarrier.Buffer ib = VkImageMemoryBarrier.calloc(1, s).sType$Default()
                        .srcAccessMask(pass == 0 ? 0 : VK_ACCESS_TRANSFER_READ_BIT).dstAccessMask(pass == 0 ? VK_ACCESS_TRANSFER_READ_BIT : VK_ACCESS_SHADER_READ_BIT)
                        .oldLayout(pass == 0 ? READ_LAYOUT : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).newLayout(pass == 0 ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL : READ_LAYOUT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(t.depthImg);
                ib.subresourceRange().aspectMask(t.aspect()).levelCount(1).layerCount(1);
                vkCmdPipelineBarrier(cmd, pass == 0 ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TRANSFER_BIT,
                        pass == 0 ? VK_PIPELINE_STAGE_TRANSFER_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, ib);
                if (pass == 0) {
                    VkBufferImageCopy.Buffer dr = VkBufferImageCopy.calloc(1, s);
                    dr.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1);
                    dr.get(0).imageExtent().set(W, H, 1);
                    vkCmdCopyImageToBuffer(cmd, t.depthImg, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, c.depthRead.buf(), dr);
                }
            }
        }
    }

    static void readBackPyramid(Ctx c, HiZPyramid hz, Target t, Result r) {
        r.pyramid = new float[hz.levels()][];
        long off = 0;
        int w = hz.width0(), h = hz.height0();
        for (int k = 0; k < hz.levels(); k++) {
            r.pyramid[k] = new float[w * h];
            MemoryUtil.memFloatBuffer(c.pyrRead.ptr() + off, w * h).get(r.pyramid[k]);
            off += (long) w * h * 4;
            w = Math.max(1, (w + 1) >> 1);
            h = Math.max(1, (h + 1) >> 1);
        }
        r.depth = new float[W * H];
        for (int i = 0; i < W * H; i++) {
            int bits = MemoryUtil.memGetInt(c.depthRead.ptr() + i * 4L);
            r.depth[i] = t.format == VK_FORMAT_D32_SFLOAT ? Float.intBitsToFloat(bits) : (bits & 0xFFFFFF) / 16777215f;
        }
    }

    /** max |GPU - CPU reduction| over every texel of every level (CPU: the farthest of each 2x2 footprint, clamped) */
    static double pyramidError(Result r, boolean reversed) {
        int sw = W, sh = H;
        float[] src = r.depth;
        double worst = 0;
        for (int k = 0; k < r.pyramid.length; k++) {
            int w = Math.max(1, (sw + 1) >> 1), h = Math.max(1, (sh + 1) >> 1);
            float[] dst = new float[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int x0 = 2 * x, y0 = 2 * y, x1 = Math.min(x0 + 1, sw - 1), y1 = Math.min(y0 + 1, sh - 1);
                    float a = src[y0 * sw + x0], b = src[y0 * sw + x1], cc = src[y1 * sw + x0], d = src[y1 * sw + x1];
                    float v = reversed ? Math.min(Math.min(a, b), Math.min(cc, d)) : Math.max(Math.max(a, b), Math.max(cc, d));
                    dst[y * w + x] = v;
                    worst = Math.max(worst, Math.abs(v - r.pyramid[k][y * w + x]));
                }
            src = dst; // the CPU chain continues from its own values
            sw = w;
            sh = h;
        }
        return worst;
    }

    /** Java model of terrain_cull.comp hizOccluded (current MVP, no shift); returns 1 occluded, 0 visible, -1 borderline */
    static int modelOccluded(float[] lo, float[] hi, Matrix4f m, float[][] pyr, boolean rev) {
        float mnx = 1e30f, mny = 1e30f, mxx = -1e30f, mxy = -1e30f, zn = rev ? -1e30f : 1e30f;
        Vector4f c = new Vector4f();
        for (int k = 0; k < 8; k++) {
            c.set((k & 1) != 0 ? hi[0] : lo[0], (k & 2) != 0 ? hi[1] : lo[1], (k & 4) != 0 ? hi[2] : lo[2], 1f).mul(m);
            if (c.w <= 1e-3f) return 0;
            float nx = c.x / c.w, ny = c.y / c.w, nz = c.z / c.w;
            float sx = (nx * 0.5f + 0.5f) * W, sy = (ny * 0.5f + 0.5f) * H;
            mnx = Math.min(mnx, sx); mxx = Math.max(mxx, sx); mny = Math.min(mny, sy); mxy = Math.max(mxy, sy);
            zn = rev ? Math.max(zn, nz) : Math.min(zn, nz);
        }
        if (rev ? zn >= 1f : zn <= 0f) return 0;
        float M = TerrainCull.HIZ_MARGIN;
        double ex0 = mnx - 0.5 - M, ex1 = mxx - 0.5 + M, ey0 = mny - 0.5 - M, ey1 = mxy - 0.5 + M;
        boolean border = near(ex0) || near(ex1) || near(ey0) || near(ey1);
        int x0 = Math.max(0, (int) Math.ceil(Math.max(-1e6, ex0))), x1 = Math.min(W - 1, (int) Math.floor(Math.min(1e6, ex1)));
        int y0 = Math.max(0, (int) Math.ceil(Math.max(-1e6, ey0))), y1 = Math.min(H - 1, (int) Math.floor(Math.min(1e6, ey1)));
        if (x0 > x1 || y0 > y1) return border ? -1 : 0;
        int L = 0;
        while (L < pyr.length && ((x1 >> (L + 1)) - (x0 >> (L + 1)) > 1 || (y1 >> (L + 1)) - (y0 >> (L + 1)) > 1)) L++;
        if (L >= pyr.length) return 0;
        int lw = W, lh = H;
        for (int k = 0; k <= L; k++) { lw = Math.max(1, (lw + 1) >> 1); lh = Math.max(1, (lh + 1) >> 1); }
        float far = rev ? 1e30f : -1e30f;
        for (int y = y0 >> (L + 1); y <= y1 >> (L + 1); y++)
            for (int x = x0 >> (L + 1); x <= x1 >> (L + 1); x++) {
                float d = pyr[L][y * lw + x];
                far = rev ? Math.min(far, d) : Math.max(far, d);
            }
        float eps = TerrainCull.HIZ_EPS;
        boolean occ = rev ? zn < far - eps : zn > far + eps;
        if (border || Math.abs(zn - far) < 4 * eps + 1e-6f) return -1;
        return occ ? 1 : 0;
    }

    static boolean near(double v) {
        return Math.abs(v - Math.rint(v)) < 2e-3;
    }

    /** the box terrain_cull.comp builds for a command (camera-relative), from the metadata word and firstInstance */
    static void box(TerrainCull.Frame cf, SectionCullData data, int layer, int slot, int fi, float[] lo, float[] hi) {
        int w = layer < 3 ? data.word(slot, 1 + layer) : data.word(slot, SectionCullData.TRANSLUCENT_WORD);
        int[] b = CullBounds.unpack(w);
        float sx = cf.originRelX + (byte) fi * 16f, sy = cf.originRelY + (byte) (fi >> 16) * 16f, sz = cf.originRelZ + (byte) (fi >> 8) * 16f;
        lo[0] = sx + b[0] - 0.0625f; lo[1] = sy + b[1] - 0.0625f; lo[2] = sz + b[2] - 0.0625f;
        hi[0] = sx + b[3] + 0.0625f; hi[1] = sy + b[4] + 0.0625f; hi[2] = sz + b[5] + 0.0625f;
    }

    // ------------------------------------------------------------------------------------------------ the run

    record Config(String name, boolean compact, Target target, boolean minmax, List<Integer> combos, List<Path> paths, boolean control) {}

    static final int ALL4 = TerrainCull.T_FRUSTUM | TerrainCull.T_HORIZON | TerrainCull.T_SCREEN | TerrainCull.T_FACING;
    static final int TRANS = 1 << 8; // combo bit: translucent Hi-Z on

    static String comboName(int combo) {
        int t = combo & 15;
        return "hiz" + (t == 0 ? "" : "+" + TerrainCullReplay.techName(t)) + ((combo & TRANS) != 0 ? "+translucent" : "");
    }

    static final class Totals {
        long frames, imagesOk, cracks, required, judgedPx, missed, candidates, hizFlagged, hizRetestDrew, hizCulledSections, transCandidates, transKept,
                retestSets, retestMismatch, retestBorder, pyramidFrames, controlFrames, controlLost, sectionsCandidates, passedOthers;
        double pyramidWorst;
        final List<String> details = new ArrayList<>();
    }

    static void run(MemoryStack s, Dev d, ByteBuffer[] code, IndirectDrawCaps.CullTier tier) {
        List<Sec> secs = generate(RD, true);
        System.out.println("  scene rd" + RD + ": " + secs.size() + " sections with geometry");
        ReplayOps ops = new ReplayOps(s, d, true);
        Ctx c = new Ctx();
        CTX = c;
        c.d = d;
        c.lay = build(s, d, secs, WorldArena.forGame(ops, LAYERS, 16), ops, true);
        c.data = TerrainCullReplay.metadata(secs);
        Map<Long, Sec> byPos = new HashMap<>();
        for (Sec sc : secs) byPos.put(TerrainCullReplay.key(sc.sx, sc.sy, sc.sz), sc);
        int transBoxes = 0;
        for (Sec sc : secs) // P2-7c: the translucent layer's box (DrawBuffers.uploadWorld for the TRANSLUCENT layer)
            if (sc.verts[L_TRANSLUCENT] != null) {
                int b = CullBounds.scan(sc.verts[L_TRANSLUCENT]);
                if (b != 0) { c.data.layerBounds(c.data.gridIndex(sc.sx, sc.sy, sc.sz), SectionCullData.TRANSLUCENT_LAYER, b); transBoxes++; }
            }
        System.out.println("  metadata: " + c.data.size + " grid slots, " + transBoxes + " translucent boxes");
        c.outRead = hostBuf(s, d, 64L << 20, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        c.transRead = hostBuf(s, d, 16L << 20, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        c.pyrRead = hostBuf(s, d, (long) W * H * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        c.depthRead = hostBuf(s, d, (long) W * H * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        c.countShim = hostBuf(s, d, 1 << 16, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);

        boolean minmaxDev = IndirectDrawCaps.hiZPath() == IndirectDrawCaps.HiZPath.MINMAX;
        System.out.println("  hiz reduction on this device: " + IndirectDrawCaps.hiZPath().name().toLowerCase() + " (r32f minmax format " + HiZPyramid.r32fMinmax(Float16Repro.phys) + ")");
        Target d32 = target(s, d, code, VK_FORMAT_D32_SFLOAT, false);
        Target d24r = target(s, d, code, VK_FORMAT_D24_UNORM_S8_UINT, true);
        boolean countDev = tier == IndirectDrawCaps.CullTier.COUNT;
        List<Path> paths = paths();
        List<Integer> four = new ArrayList<>(List.of(0, ALL4 | TRANS, TerrainCull.T_FRUSTUM | TRANS, ALL4));
        List<Config> cfgs = new ArrayList<>();
        if (QUICK) {
            cfgs.add(new Config("main", countDev, d32, minmaxDev, List.of(0, ALL4 | TRANS), paths, true));
            cfgs.add(new Config("D24S8 reversed-Z shader", countDev, d24r, false, List.of(ALL4 | TRANS), paths, false));
        } else {
            List<Integer> all = new ArrayList<>();
            for (int t = 0; t < 16; t++) { all.add(t); all.add(t | TRANS); }
            cfgs.add(new Config("main", countDev, d32, minmaxDev, all, paths, true));
            Target d24 = target(s, d, code, VK_FORMAT_D24_UNORM_S8_UINT, false);
            if (countDev) cfgs.add(new Config("FIXED tier, D24S8, shader", false, d24, false, four, paths, false));
            cfgs.add(new Config("D24S8 reversed-Z, shader", countDev, d24r, false, four, paths, false));
            Target d32r = target(s, d, code, VK_FORMAT_D32_SFLOAT, true);
            cfgs.add(new Config("D32F reversed-Z" + (minmaxDev ? ", minmax" : ""), countDev, d32r, minmaxDev, four, paths.subList(0, 2), false));
            if (minmaxDev) cfgs.add(new Config("D32F, shader (vs minmax)", countDev, d32, false, four, paths.subList(0, 1), false));
        }
        // references per target and path frame; the ray-cast oracle per path frame (geometry only)
        Map<String, Object[]> refs = new HashMap<>();
        Map<String, Map<String, Integer>> oracle = new HashMap<>();
        long t0 = System.nanoTime();
        Totals grand = new Totals();
        Set<String> hizSections = new HashSet<>();
        for (Config cfg : cfgs) {
            Totals tot = new Totals();
            TerrainCull tc = new TerrainCull(d.dev, d.mp, 1, cfg.compact, c.deferred::add, cfg.minmax, cfg.target.reversed);
            Set<Integer> combos = new LinkedHashSet<>();
            for (int combo : cfg.combos) combos.add(NO_HORIZON ? combo & ~TerrainCull.T_HORIZON : combo); // GPU-AV: horizon left out, the rest kept
            for (int combo : combos) {
                for (Path p : cfg.paths) {
                    boolean prev = false;
                    float[] prevMvp = new float[16];
                    double px = 0, py = 0, pz = 0;
                    for (Cam cam : p.cams) {
                        Frame fr = frameOf(secs, c.lay, cam, cfg.target.reversed);
                        String rk = cfg.target.name() + "|" + cam.name();
                        Object[] ref = refs.computeIfAbsent(rk, k -> reference(s, c, fr, cfg.target));
                        Map<String, Integer> req = oracle.computeIfAbsent(cam.name() + (cfg.target.reversed ? "|r" : ""), k -> required(fr, c.data));
                        TerrainCull.Frame cf = TerrainCullReplay.cullFrame(fr, (combo & 15) | TerrainCull.T_HIZ);
                        cf.depthW = W;
                        cf.depthH = H;
                        cf.prevValid = prev;
                        System.arraycopy(prevMvp, 0, cf.prevMvp, 0, 16);
                        cf.prevShiftX = (float) (fr.cx - px);
                        cf.prevShiftY = (float) (fr.cy - py);
                        cf.prevShiftZ = (float) (fr.cz - pz);
                        boolean transOn = (combo & TRANS) != 0;
                        boolean readPyr = combo == 0 || combo == (ALL4 | TRANS);
                        Result r = culledFrame(s, c, tc, cfg.target, fr, cf, transOn, false, readPyr);
                        judge(cfg, combo, p, cam, fr, cf, ref, req, r, tot, hizSections);
                        if (cfg.control && combo == 0 && r.retested && !r.flagged.isEmpty()) { // negative control on the same inputs
                            Result nc = culledFrame(s, c, tc, cfg.target, fr, copyFrame(cf), transOn, true, false);
                            tot.controlFrames++;
                            int[] cr = nc.sha.equals(ref[0]) ? new int[]{0, 0} : TerrainCullReplay.crackPixels(fr, (byte[]) ref[1], nc.px, union(nc));
                            int lost = missing(req, nc, false);
                            if (cr[1] > 0 || lost > 0) tot.controlLost++;
                            for (TerrainCommandWriter w : nc.ws) w.free();
                            if (nc.tw != null) nc.tw.free();
                        }
                        for (TerrainCommandWriter w : r.ws) w.free();
                        if (r.tw != null) r.tw.free();
                        // the pyramid now holds this frame's depth (built at the retest, and again at the translucent cull)
                        prev = r.retested || r.transRecorded;
                        fr.mvp.get(prevMvp);
                        px = fr.cx; py = fr.cy; pz = fr.cz;
                    }
                }
            }
            tc.destroyNow();
            report(cfg, tot);
            add(grand, tot);
        }
        // review P2-7c #1: a retest after a SECOND record of the same frame, without a build after that record, must not trust
        // the pyramid (built from the first record's depth): keep-all mode
        {
            TerrainCull tc = new TerrainCull(d.dev, d.mp, 1, countDev, c.deferred::add, minmaxDev, false);
            Cam cam = paths.get(0).cams.get(0);
            Frame fr = frameOf(secs, c.lay, cam, false);
            TerrainCull.Frame cf = TerrainCullReplay.cullFrame(fr, TerrainCull.T_HIZ);
            cf.depthW = W; cf.depthH = H;
            boolean first, second, firstOk;
            try (MemoryStack f = s.push()) {
                long serial = ++c.serial;
                TerrainCommandWriter[] ws = TerrainCullReplay.writeCull(fr, c.data, cf.techniques, d.maxDraws);
                VkCommandBuffer cmd = beginOnce(f, d);
                tc.record(cmd, 0, serial, cf, c.data, ws); // fills the pyramid's history: next record may use HZ_PREV
                depthToReadFromUndefined(f, cmd, d32);
                boolean built = tc.buildPyramid(cmd, 0, serial, d32, d32.depthImg, d32.format, READ_LAYOUT, W, H);
                first = tc.recordRetest(cmd, 0, serial, built);
                firstOk = tc.retestPyramidOk;
                cf.prevValid = true;
                System.arraycopy(cf.mvp, 0, cf.prevMvp, 0, 16);
                tc.record(cmd, 0, serial, cf, c.data, ws); // second record, no build after it
                second = tc.recordRetest(cmd, 0, serial, true);
                submitWait(f, d, cmd);
                for (Runnable q : c.deferred) q.run();
                c.deferred.clear();
                for (TerrainCommandWriter w : ws) w.free();
            }
            check("a retest after a second record without a new build keeps every flag (pyramid trusted after its own build: " + firstOk
                    + ", after the second record: " + tc.retestPyramidOk + ")", first && second && firstOk && !tc.retestPyramidOk);
            tc.destroyNow();
        }
        // the reversed-Z references draw the same picture as the standard ones (the projection / compare / clear flip is right)
        int pairs = 0, worstDiff = 0;
        Map<Integer, Target> stdTargets = new HashMap<>();
        for (Map.Entry<String, Object[]> e : new ArrayList<>(refs.entrySet())) {
            String k = e.getKey();
            if (!k.contains(" reversed-Z|")) continue;
            Object[] std = refs.get(k.replace(" reversed-Z|", "|"));
            if (std == null) { // no standard config of that format ran (quick): draw its reference now
                int fmt = k.startsWith("D32F") ? VK_FORMAT_D32_SFLOAT : VK_FORMAT_D24_UNORM_S8_UINT;
                Target st = stdTargets.computeIfAbsent(fmt, f -> target(s, d, code, f, false));
                String camName = k.substring(k.indexOf('|') + 1);
                Cam cam = paths.stream().flatMap(p -> p.cams.stream()).filter(x -> x.name().equals(camName)).findFirst().orElseThrow();
                std = reference(s, c, frameOf(secs, c.lay, cam, false), st);
            }
            pairs++;
            worstDiff = Math.max(worstDiff, diffPixels((byte[]) std[1], (byte[]) e.getValue()[1]));
        }
        check("reversed-Z references == standard references of the same depth format (" + pairs + " frame pairs, worst " + worstDiff + " px of " + W * H + " differ: depth rounding)",
                pairs > 0 && worstDiff <= W * H / 500);
        System.out.printf("  total: %d frames in %.1f s; Hi-Z culled %d commands (%d distinct sections) the other techniques kept, the retest drew %d of the %d flagged back%n",
                grand.frames, (System.nanoTime() - t0) / 1e9, grand.hizFlagged - grand.hizRetestDrew, hizSections.size(), grand.hizRetestDrew, grand.hizFlagged);
        check("Hi-Z culls on its own over the paths (" + (grand.hizFlagged - grand.hizRetestDrew) + " commands)", grand.hizFlagged - grand.hizRetestDrew > 0);
        check("translucent Hi-Z culls something (" + (grand.transCandidates - grand.transKept) + " of " + grand.transCandidates + " translucent commands)",
                grand.transCandidates - grand.transKept > 0);
        if (!QUICK) check("negative control: without the retest draws visible geometry is lost on " + grand.controlLost + " of " + grand.controlFrames
                + " frames that flagged commands (the oracles see it; the retest is what keeps it)", grand.controlLost > 0);
        else System.out.println("  negative control (quick): visible geometry lost without the retest on " + grand.controlLost + " of " + grand.controlFrames + " frames");
        freeLayout(c.lay);
        freeScene(secs);
    }

    static Set<String> union(Result r) {
        Set<String> u = new HashSet<>(r.drawn1);
        u.addAll(r.drawn2);
        return u;
    }

    /** required commands (ray-cast) not drawn: opaque in pass 1 + pass 2, water in the translucent kept set (when culled) */
    static int missing(Map<String, Integer> req, Result r, boolean list) {
        if (!r.recorded) return 0;
        Set<String> drawn = new HashSet<>();
        for (String k : union(r)) { String id = r.ids.get(k); if (id != null) drawn.add(id); }
        for (String k : r.trans) { String id = r.ids.get(k); if (id != null) drawn.add(id); }
        int miss = 0;
        for (String k : req.keySet()) {
            if (k.startsWith("3:") && !r.transRecorded) continue; // the CPU path drew every translucent command
            if (!drawn.contains(k)) {
                miss++;
                if (list && miss <= 4) System.out.println("    oracle: visible " + k + " (" + req.get(k) + " px) not drawn");
            }
        }
        return miss;
    }

    static void judge(Config cfg, int combo, Path p, Cam cam, Frame fr, TerrainCull.Frame cf, Object[] ref, Map<String, Integer> req, Result r, Totals tot, Set<String> hizSections) {
        tot.frames++;
        boolean same = r.sha.equals(ref[0]);
        int[] cr = same ? new int[]{0, 0} : TerrainCullReplay.crackPixels(fr, (byte[]) ref[1], r.px, union(r));
        tot.cracks += cr[0];
        if (same || cr[1] == 0) tot.imagesOk++;
        int miss = missing(req, r, false);
        tot.required += req.size();
        for (int v : req.values()) tot.judgedPx += v;
        tot.missed += miss;
        tot.candidates += r.candidates;
        tot.hizFlagged += r.flagged.size();
        tot.passedOthers += r.drawn1.size() + r.flagged.size(); // what the P2-7b techniques kept: Hi-Z's own share is measured against it
        tot.hizRetestDrew += r.drawn2.size();
        for (String k : r.flagged) if (!r.drawn2.contains(k)) { String id = r.ids.get(k); if (id != null) hizSections.add(id.substring(0, id.lastIndexOf(':'))); }
        if (r.transRecorded) { tot.transCandidates += r.transCandidates; tot.transKept += r.trans.size(); }
        if (!r.recorded) tot.details.add(cam.name() + " " + comboName(combo) + ": cull not recorded");
        if ((!same && cr[1] > 0) || miss > 0) {
            String m = String.format("%s %s %s: image %s (%d px, %d real), oracle missed %d, pass1 %d pass2 %d flagged %d", cfg.name, comboName(combo), cam.name(),
                    same ? "same" : "DIFFERS", diffPixels((byte[]) ref[1], r.px), cr[1], miss, r.drawn1.size(), r.drawn2.size(), r.flagged.size());
            tot.details.add(m);
            if (tot.details.size() <= 6) { System.out.println("  FAIL detail: " + m); missing(req, r, true); }
        }
        if (r.pyramid != null) {
            tot.pyramidFrames++;
            tot.pyramidWorst = Math.max(tot.pyramidWorst, pyramidError(r, cfg.target.reversed));
            // retest vs the model on the read-back pyramid (built from pass 1's depth: the one the retest read)
            float[] lo = new float[3], hi = new float[3];
            Matrix4f m = new Matrix4f().set(cf.mvp);
            for (String k : r.flagged) {
                String id = r.ids.get(k);
                String[] a = id.split(":"), f = k.split(":");
                box(cf, CTX.data, Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(f[1]), lo, hi);
                int v = modelOccluded(lo, hi, m, r.pyramid, cfg.target.reversed);
                boolean kept = r.drawn2.contains(k);
                tot.retestSets++;
                if (v < 0) { if (kept == (v == 0)) continue; tot.retestBorder++; continue; }
                if (kept != (v == 0)) tot.retestMismatch++;
            }
        }
    }

    static void add(Totals g, Totals t) {
        g.frames += t.frames; g.hizFlagged += t.hizFlagged; g.hizRetestDrew += t.hizRetestDrew;
        g.transCandidates += t.transCandidates; g.transKept += t.transKept; g.controlFrames += t.controlFrames; g.controlLost += t.controlLost;
    }

    static void report(Config cfg, Totals t) {
        String tierName = cfg.compact ? "COUNT" : "FIXED";
        System.out.printf("  %-28s %s %s %s: %d frames, %d combos; candidates %d, kept by the other techniques %d, Hi-Z flagged %d, retest drew %d (disocclusions),"
                        + " Hi-Z culled %d (%.1f%% of what the others kept); translucent kept %d/%d%n",
                cfg.name, tierName, cfg.target.name(), cfg.minmax ? "minmax" : "shader", t.frames, cfg.combos.size(), t.candidates, t.passedOthers, t.hizFlagged, t.hizRetestDrew,
                t.hizFlagged - t.hizRetestDrew, 100.0 * (t.hizFlagged - t.hizRetestDrew) / Math.max(1, t.passedOthers), t.transKept, t.transCandidates);
        check(String.format("%s: images == the CPU path on every frame of every path and combination (%d/%d; %d px only at P2-7b's classified 1 px cracks)",
                cfg.name, t.imagesOk, t.frames, t.cracks), t.imagesOk == t.frames && t.details.stream().noneMatch(x -> x.contains("not recorded")));
        check(String.format("%s: ray-cast oracle: 0 visible commands culled (%d missed of %d required, summed over frames; %d pixel rays judged)", cfg.name, t.missed, t.required, t.judgedPx),
                t.missed == 0 && t.judgedPx > t.frames * 1000L);
        if (t.pyramidFrames > 0) {
            check(String.format("%s: pyramid == CPU %s reduction of the read-back depth on %d frames (worst |diff| %.3g)", cfg.name, cfg.target.reversed ? "min" : "max",
                    t.pyramidFrames, t.pyramidWorst), t.pyramidWorst <= (cfg.target.format == VK_FORMAT_D32_SFLOAT ? 0 : 1.2e-7));
            check(String.format("%s: retest kept set == the Java Hi-Z model on the read-back pyramid (%d flagged commands, %d borderline, %d real mismatches)",
                    cfg.name, t.retestSets, t.retestBorder, t.retestMismatch), t.retestMismatch == 0);
        }
    }
}
