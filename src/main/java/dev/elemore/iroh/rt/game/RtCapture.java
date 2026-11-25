package dev.elemore.iroh.rt.game;

import dev.elemore.iroh.api.FrameContext;
import dev.elemore.iroh.rt.RtCaptureFormat;
import dev.elemore.iroh.rt.RtFrameData;
import dev.elemore.iroh.rt.SectionGeometry;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.buffer.AreaBuffer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.buffer.DrawParametersBuffer;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * RT-1 dev switch {@code -Diroh.rt.capture=<dir>} (inert pin: {@link #DIR} is null unless the property is set, and
 * {@link #poll} is called from {@link RtFrameHooks#worldStart} only after the {@link RtSwitch#ON} read): writes the world
 * the RT session holds RESIDENT, in {@link RtCaptureFormat} (docs in that class), so the offline replay
 * ({@code rtGameReplay -Pcapture=<dir>}) rebuilds BLAS / TLAS from the real arena bytes through the GAME classes.
 * <p>
 * Trigger: {@code -Diroh.rt.capture.frame=N} (a capture at the Nth RT frame, once) and/or a file
 * {@code <dir>/take} (polled every 10 frames; its first line is the label, e.g. {@code village-noon}; the file is deleted,
 * then {@code <dir>/cap-NNN-label/} is written). Dev tool, one hitch per capture: it submits the frame so far
 * ({@code FrameSync.flushFrame}, as a screenshot does), copies the resident arena runs and the atlas mips through one-shot
 * transfer command buffers (host staging, batches of at most 128 MiB) and waits on a fence. Sections are written nearest
 * first, so the 512 MiB cap (RtCaptureFormat.CAP_BYTES) drops the far ones ({@code truncated=true} in meta). Never
 * throws into the frame: a failed capture logs one line. The files hold Mojang textures: the directory must be under
 * ~/.cache, never in the repository. UNVERIFIED in game (needs the guarded Proctor run, card RT-1).
 */
public final class RtCapture {
    private RtCapture() {}

    public static final String DIR = System.getProperty("iroh.rt.capture");
    private static final int AT_FRAME = Integer.getInteger("iroh.rt.capture.frame", -1);
    private static final long BATCH = 128L << 20, ATLAS_MAX = 192L << 20;
    private static int frames, taken;
    private static boolean busy;

    /** per RT frame, after the session's frame; render thread. */
    static void poll(FrameContext c) {
        if (DIR == null || busy) return;
        frames++;
        String label = null;
        if (AT_FRAME >= 0 && frames == AT_FRAME) label = "frame" + AT_FRAME;
        Path take = Path.of(DIR, "take");
        if (label == null && frames % 10 == 0 && Files.exists(take)) {
            try {
                List<String> l = Files.readAllLines(take);
                label = l.isEmpty() ? "" : l.get(0).replaceAll("[^A-Za-z0-9_-]", "");
                Files.deleteIfExists(take);
            } catch (IOException e) {
                RtDeviceFeatures.log("Iroh: rt capture trigger unreadable: " + e);
                return;
            }
        }
        if (label == null) return;
        busy = true;
        try {
            Path out = Path.of(DIR, String.format("cap-%03d%s", ++taken, label.isEmpty() ? "" : "-" + label));
            long t0 = System.nanoTime();
            String line = capture(c, out, label);
            RtDeviceFeatures.log(String.format("[iroh-rt] capture %s (%.0f ms)", line, (System.nanoTime() - t0) / 1e6));
        } catch (Throwable t) {
            RtDeviceFeatures.log("Iroh: rt capture failed: " + t);
        } finally {
            busy = false;
        }
    }

    /** One run of quads: where it lives in the arena. */
    private record Run(int sec, int bucket, long bufferId, long offset, long bytes) {}

    private static String capture(FrameContext c, Path out, String label) throws IOException {
        RtSession s = RtFrameHooks.session;
        if (s == null) throw new IOException("no RT session");
        int cx = (int) Math.floor(c.cameraPos().x()) >> 4, cy = (int) Math.floor(c.cameraPos().y()) >> 4, cz = (int) Math.floor(c.cameraPos().z()) >> 4;
        // everything recorded so far reaches the GPU before the one-shot reads (arena uploads included)
        dev.elemore.iroh.glshim.FrameSync.flushFrame();
        // ---- resident sections nearest first, runs per bucket
        long[] keys = s.queue.residentKeys();
        Arrays.sort(keys);
        List<Pos> pos = new ArrayList<>();
        for (long k : keys) {
            int[] p = RtCapture.unkey(k);
            long dx = p[0] - cx, dy = p[1] - cy, dz = p[2] - cz;
            pos.add(new Pos(p[0], p[1], p[2], dx * dx + dy * dy + dz * dz));
        }
        pos.sort((a, b) -> Long.compare(a.d, b.d));
        int vs = DrawBuffers.VERTEX_SIZE;
        List<Run> runs = new ArrayList<>();
        List<int[]> secQuads = new ArrayList<>();
        List<Pos> secPos = new ArrayList<>();
        long budget = RtCaptureFormat.CAP_BYTES - ATLAS_MAX - (1L << 20), used = 0;
        boolean truncated = false;
        for (Pos p : pos) {
            RenderSection rs = VulkanModSections.section(p.x, p.y, p.z);
            if (rs == null) continue;
            DrawBuffers db = rs.getChunkArea().getDrawBuffers();
            long base = db.getDrawParamsPtr();
            int[] q = new int[3];
            List<Run> mine = new ArrayList<>();
            int idx = secPos.size();
            for (TerrainRenderType t : TerrainRenderType.VALUES) {
                AreaBuffer ab = db.getAreaBuffer(t);
                if (ab == null) continue;
                for (int f = 0; f < QuadFacing.COUNT; f++) {
                    long pp = DrawParametersBuffer.getParamsPtr(base, rs.inAreaIndex, t.ordinal(), f);
                    int quads = DrawParametersBuffer.getIndexCount(pp) / 6;
                    long off = (long) DrawParametersBuffer.getVertexOffset(pp) * vs, len = (long) quads * 4 * vs;
                    if (quads <= 0 || off < 0 || off + len > ab.getSize()) continue;
                    int b = VulkanModSections.bucket(t);
                    q[b] += quads;
                    mine.add(new Run(idx, b, ab.getId(), off, len));
                }
            }
            long need = 24L + (long) (q[0] + q[1] + q[2]) * 4 * vs;
            if (q[0] + q[1] + q[2] == 0) continue;
            if (used + need > budget) {
                truncated = true;
                break;
            }
            used += need;
            runs.addAll(mine);
            secQuads.add(q);
            secPos.add(p);
        }
        // ---- atlas, frame, meta first (small), then the sections in batches
        RtCaptureFormat.Atlas atlas = readAtlas();
        try (RtCaptureFormat.Writer w = new RtCaptureFormat.Writer(out, vs)) {
            if (atlas != null) w.atlas(atlas);
            Matrix4f view = new Matrix4f(c.viewMatrix()), proj = new Matrix4f(c.projectionMatrix());
            RtFrameData fd = frameData(c, view, proj);
            float[] vf = new float[16], pf = new float[16];
            view.get(vf);
            proj.get(pf);
            w.frame(fd, vf, pf);
            writeSections(w, runs, secQuads, secPos, vs);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", label);
            m.put("rtFrame", frames);
            m.put("cameraWorld", c.cameraPos().x() + "," + c.cameraPos().y() + "," + c.cameraPos().z());
            m.put("originSection", cx + "," + cy + "," + cz);
            m.put("viewport", c.viewportWidth() + "x" + c.viewportHeight());
            m.put("truncated", truncated);
            m.put("residentAtCapture", keys.length);
            m.put("sunNote", "sunDir in frame.ircap = (-sin a, cos a, 0), a = Level.getSunAngle; sign convention UNVERIFIED");
            m.put("lights", "absent (card RT-3a)");
            m.put("entities", "absent (card RT-5)");
            if (atlas != null) {
                m.put("atlas", atlas.width() + "x" + atlas.height() + " levels " + atlas.levels().size());
            }
            m.forEach(w::meta);
            return out.getFileName() + ": " + secPos.size() + " sections of " + keys.length + " resident, " + w.bytesWritten() / (1 << 20)
                    + " MiB" + (truncated ? " (TRUNCATED at the cap)" : "");
        }
    }

    static int[] unkey(long k) {
        int x = (int) (k >>> 42) << 10 >> 10, z = (int) ((k >>> 20) & 0x3FFFFF) << 10 >> 10, y = (int) (k & 0xFFFFF) << 12 >> 12;
        if (SectionGeometry.key(x, y, z) != k) throw new IllegalStateException("bad key");
        return new int[]{x, y, z};
    }

    private static RtFrameData frameData(FrameContext c, Matrix4f view, Matrix4f proj) {
        RtFrameData fd = new RtFrameData();
        Matrix4f vp = new Matrix4f(proj).mul(view);   // camera-relative (the game's view matrix has no translation)
        vp.get(fd.viewProj);
        vp.get(fd.prevViewProj);
        new Matrix4f(vp).invert().get(fd.invViewProj);
        Matrix4f inv = new Matrix4f(view).invert();
        org.joml.Vector3f f = inv.transformDirection(new org.joml.Vector3f(0, 0, -1));
        fd.camPos[0] = (float) c.cameraPos().x();
        fd.camPos[1] = (float) c.cameraPos().y();
        fd.camPos[2] = (float) c.cameraPos().z();
        System.arraycopy(fd.camPos, 0, fd.prevCamPos, 0, 3);
        fd.camFwd[0] = fd.prevCamFwd[0] = f.x;
        fd.camFwd[1] = fd.prevCamFwd[1] = f.y;
        fd.camFwd[2] = fd.prevCamFwd[2] = f.z;
        fd.width = c.viewportWidth();
        fd.height = c.viewportHeight();
        fd.frameIndex = (int) c.frameIndex();
        float a = 0;
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null) a = level.getSunAngle(c.partialTick());
        fd.sunDir[0] = (float) -Math.sin(a);
        fd.sunDir[1] = (float) Math.cos(a);
        fd.sunDir[2] = 0;
        fd.sunAngularRadius = 0.0047f;   // the real sun, 0.27 degrees
        fd.sunE[0] = 3.2f; fd.sunE[1] = 3.0f; fd.sunE[2] = 2.7f;   // the harness's test irradiance until RT-10
        return fd;
    }

    // ------------------------------------------------------------------------------------------------ GPU reads

    private static RtCaptureFormat.Atlas readAtlas() {
        var tex = net.minecraft.client.Minecraft.getInstance().getTextureManager()
                .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        var gl = net.vulkanmod.gl.VkGlTexture.getTexture(tex.getId());
        VulkanImage img = gl == null ? null : gl.getVulkanImage();
        if (img == null || img.formatSize != 4) return null;
        int levels = 0;
        long total = 0;
        for (int l = 0; l < img.mipLevels; l++) {
            long b = (long) Math.max(1, img.width >> l) * Math.max(1, img.height >> l) * 4;
            if (total + b > ATLAS_MAX) break;
            total += b;
            levels++;
        }
        if (levels == 0) return null;
        dev.elemore.iroh.glshim.FrameSync.beforeHostRead(img);
        try (MemoryStack st = stackPush()) {
            int prev = img.getCurrentLayout();
            CommandPool.CommandBuffer cb = DeviceManager.getGraphicsQueue().beginCommands();
            img.transitionImageLayout(st, cb.getHandle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            LongBuffer pb = st.mallocLong(1);
            PointerBuffer pa = st.pointers(0L);
            MemoryManager.getInstance().createBuffer(total, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, pb, pa);
            long off = 0;
            long[] offs = new long[levels];
            for (int l = 0; l < levels; l++) {
                int lw = Math.max(1, img.width >> l), lh = Math.max(1, img.height >> l);
                offs[l] = off;
                ImageUtil.copyImageToBufferCmd(st, cb.getHandle(), pb.get(0), img.getId(), l, lw, lh, 0, 0, (int) off, 0, 0);
                off += (long) lw * lh * 4;
            }
            img.transitionImageLayout(st, cb.getHandle(), prev != VK_IMAGE_LAYOUT_UNDEFINED ? prev : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            long fence = DeviceManager.getGraphicsQueue().submitCommands(cb);
            vkWaitForFences(DeviceManager.vkDevice, fence, true, Long.MAX_VALUE);
            List<ByteBuffer> out = new ArrayList<>();
            final int n = levels;
            final long tot = total;
            MemoryManager.MapAndCopy(pa.get(0), data -> {
                ByteBuffer all = data.getByteBuffer(0, (int) tot);
                for (int l = 0; l < n; l++) {
                    int lw = Math.max(1, img.width >> l), lh = Math.max(1, img.height >> l), len = lw * lh * 4;
                    ByteBuffer d = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
                    d.put(all.slice((int) offs[l], len)).flip();
                    out.add(d);
                }
            });
            MemoryManager.freeBuffer(pb.get(0), pa.get(0));
            return new RtCaptureFormat.Atlas(img.width, img.height, out);
        }
    }

    private record Pos(int x, int y, int z, long d) {}

    /** The runs (grouped by section index, in order) copied to host staging in batches of at most BATCH bytes, written as sections. */
    private static void writeSections(RtCaptureFormat.Writer w, List<Run> runs, List<int[]> secQuads, List<Pos> secPos, int vs) throws IOException {
        int n = secPos.size(), from = 0, ri = 0;
        while (from < n) {
            long bytes = 0;
            int to = from;
            while (to < n) {
                int[] q = secQuads.get(to);
                long sb = (long) (q[0] + q[1] + q[2]) * 4 * vs;
                if (bytes > 0 && bytes + sb > BATCH) break;
                bytes += sb;
                to++;
            }
            // layout in staging: per section, per bucket contiguous
            long[][] base = new long[to - from][3];
            long off = 0;
            for (int i = from; i < to; i++)
                for (int b = 0; b < 3; b++) {
                    base[i - from][b] = off;
                    off += (long) secQuads.get(i)[b] * 4 * vs;
                }
            Map<Long, List<long[]>> byBuf = new LinkedHashMap<>();   // buffer -> {src, dst, size}
            long[][] cursor = new long[to - from][3];
            int rj = ri;
            while (rj < runs.size() && runs.get(rj).sec() < to) {
                Run r = runs.get(rj++);
                int k = r.sec() - from;
                byBuf.computeIfAbsent(r.bufferId(), x -> new ArrayList<>()).add(new long[]{r.offset(), base[k][r.bucket()] + cursor[k][r.bucket()], r.bytes()});
                cursor[k][r.bucket()] += r.bytes();
            }
            ri = rj;
            ByteBuffer[] host = new ByteBuffer[1];
            try (MemoryStack st = stackPush()) {
                CommandPool.CommandBuffer cb = DeviceManager.getGraphicsQueue().beginCommands();
                LongBuffer pb = st.mallocLong(1);
                PointerBuffer pa = st.pointers(0L);
                MemoryManager.getInstance().createBuffer(Math.max(16, off), VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, pb, pa);
                for (Map.Entry<Long, List<long[]>> e : byBuf.entrySet()) {
                    try (MemoryStack f = st.push()) {
                        VkBufferCopy.Buffer reg = VkBufferCopy.calloc(e.getValue().size(), f);
                        for (int i = 0; i < e.getValue().size(); i++) {
                            long[] x = e.getValue().get(i);
                            reg.get(i).srcOffset(x[0]).dstOffset(x[1]).size(x[2]);
                        }
                        vkCmdCopyBuffer(cb.getHandle(), e.getKey(), pb.get(0), reg);
                    }
                }
                long fence = DeviceManager.getGraphicsQueue().submitCommands(cb);
                vkWaitForFences(DeviceManager.vkDevice, fence, true, Long.MAX_VALUE);
                final long total = off;
                MemoryManager.MapAndCopy(pa.get(0), data -> {
                    ByteBuffer copy = ByteBuffer.allocateDirect((int) total).order(ByteOrder.LITTLE_ENDIAN);
                    copy.put(data.getByteBuffer(0, (int) total)).flip();
                    host[0] = copy;
                });
                MemoryManager.freeBuffer(pb.get(0), pa.get(0));
            }
            for (int i = from; i < to; i++) {
                int[] q = secQuads.get(i);
                ByteBuffer[] b = new ByteBuffer[3];
                for (int k = 0; k < 3; k++)
                    if (q[k] > 0) b[k] = host[0].slice((int) base[i - from][k], q[k] * 4 * vs).order(ByteOrder.LITTLE_ENDIAN);
                Pos p = secPos.get(i);
                if (!w.section(new RtCaptureFormat.Section(p.x(), p.y(), p.z(), q, b))) throw new IOException("cap reached while writing (budget bug)");
            }
            from = to;
        }
    }
}
