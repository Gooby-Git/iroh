package dev.elemore.iroh.rttest;

import dev.elemore.iroh.gpu.Glsl;
import dev.elemore.iroh.gpu.HeadlessGpuDevice;
import dev.elemore.iroh.gpu.VkGpuDevice.VkBuf;
import dev.elemore.iroh.rt.*;
import dev.elemore.iroh.rt.game.RtGameBridge;
import dev.elemore.iroh.rt.game.RtSectionSource;
import dev.elemore.iroh.rt.game.RtSession;
import dev.elemore.iroh.rt.game.VkRtBackend;
import dev.elemore.iroh.rt.vk.RtAccel;
import dev.elemore.iroh.rt.vk.RtCompute;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * RT-1: offline replay of a world capture ({@code ./gradlew rtGameReplay [-Pcapture=<dir>] [-Pgolden=write|check]}; last
 * line "# rtgamereplay PASS n/n"). A capture (RtCaptureFormat: resident section arena slices, the block atlas, camera and
 * sun, written by the game's RtCapture) is rebuilt through the GAME classes (RtSession + RtGameBridge + BlasBuildQueue +
 * VkRtBackend + RtAccel on a HeadlessGpuDevice, the arena as one buffer read in place by the BLAS builds) and the camera's
 * rays are traced at 320x180: primary hit distance and sun visibility (the chain beyond the sun comes with RT-2; this is
 * the rtWorldReplay shader family on real geometry), checked against a CPU brute force on a sample of rays.
 * <p>
 * Without {@code -Pcapture} the synthetic round trip runs (the pass criterion of the card): the RtScene hall is built the
 * rtReplay way (RtGpu: its own BLAS/TLAS), written with the capture writer, read back, rebuilt through the game classes, and
 * both TLASes trace the SAME rays with the same shader: hit distances and sun visibility must be BIT-IDENTICAL (and match
 * the CPU brute force). The written files are byte-compared with the in-memory scene. Synthetic capture files live in
 * ~/.cache/iroh-captures/_synthetic (they end .ircap, which reuse_gate refuses inside the repo); real captures contain
 * Mojang textures: their directory, images and goldens stay under ~/.cache/iroh-captures, never in git. -Pgolden=write
 * stores the sha256 of the traced buffer next to the capture, =check compares (local goldens only).
 */
public final class RtGameReplay {
    static int pass, fail;
    static final int W = 320, H = 180;
    static final Path CACHE = Path.of(System.getProperty("user.home"), ".cache", "iroh-captures");

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (ok) pass++; else fail++;
    }

    static final String SH = """
            #version 460
            #extension GL_EXT_ray_query : require
            layout(local_size_x = 64) in;
            layout(set = 0, binding = 0) uniform accelerationStructureEXT tlas;
            layout(std430, set = 0, binding = 1) readonly buffer R { vec4 rays[]; };   // per pixel: origin.xyz tmax, dir.xyz 0
            layout(std430, set = 0, binding = 2) buffer O { vec4 o[]; };                // t, sun visibility, 0, 0
            layout(std430, set = 0, binding = 3) readonly buffer S { vec4 sun; };
            bool hit(rayQueryEXT q) {
                uint n = 0u;
                while (rayQueryProceedEXT(q)) { if (++n > 256u) { rayQueryTerminateEXT(q); break; } }
                return rayQueryGetIntersectionTypeEXT(q, true) == gl_RayQueryCommittedIntersectionTriangleEXT;
            }
            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= uint(o.length())) return;
                vec3 org = rays[2u * i].xyz, dir = rays[2u * i + 1u].xyz;
                rayQueryEXT rq;
                rayQueryInitializeEXT(rq, tlas, gl_RayFlagsOpaqueEXT, 0x01, org, 0.0, dir, rays[2u * i].w);
                if (!hit(rq)) { o[i] = vec4(-1.0, -1.0, 0.0, 0.0); return; }
                float t = rayQueryGetIntersectionTEXT(rq, true);
                rayQueryEXT sq;
                rayQueryInitializeEXT(sq, tlas, gl_RayFlagsOpaqueEXT | gl_RayFlagsTerminateOnFirstHitEXT, 0x01, org + dir * t, 0.002, sun.xyz, 1.0e4);
                o[i] = vec4(t, hit(sq) ? 0.0 : 1.0, 0.0, 0.0);
            }
            """;

    // ------------------------------------------------------------------------------------------------ rays

    /** Rays through the pixel centres: {origin (relative to the origin section) + tmax, dir + 0} per pixel. */
    static float[] rays(RtCaptureFormat.Capture c, int[] origin) {
        boolean gl = "gl".equals(c.meta().getOrDefault("projection", "vk"));
        Matrix4f view = new Matrix4f().set(c.view()), proj = new Matrix4f().set(c.proj());
        Matrix4f inv = new Matrix4f(proj).mul(view).invert();
        ByteBuffer f = c.frame();
        float[] cam = new float[3];
        for (int i = 0; i < 3; i++) cam[i] = f.getFloat((48 + i) * 4);   // invViewProj, viewProj, prevViewProj (48 floats), then camPos
        float[] r = new float[W * H * 8];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                float nx = (x + 0.5f) / W * 2 - 1, ny = (y + 0.5f) / H * 2 - 1;
                if (gl) ny = -ny;
                Vector4f a = inv.transform(new Vector4f(nx, ny, gl ? -1 : 0, 1)), b = inv.transform(new Vector4f(nx, ny, 1, 1));
                Vector3f d = new Vector3f(b.x / b.w - a.x / a.w, b.y / b.w - a.y / a.w, b.z / b.w - a.z / a.w).normalize();
                int i = (y * W + x) * 8;
                r[i] = cam[0] - origin[0] * 16f;
                r[i + 1] = cam[1] - origin[1] * 16f;
                r[i + 2] = cam[2] - origin[2] * 16f;
                r[i + 3] = 512f;
                r[i + 4] = d.x;
                r[i + 5] = d.y;
                r[i + 6] = d.z;
            }
        return r;
    }

    static float[] sunDir(RtCaptureFormat.Capture c) {
        ByteBuffer f = c.frame();
        // RtFrameData layout: 3 matrices (48 f), 4 vec4 (camPos, camFwd, prevCamPos, prevCamFwd), then sunDir
        float[] s = new float[3];
        for (int i = 0; i < 3; i++) s[i] = f.getFloat((48 + 16 + i) * 4);
        return s;
    }

    static int[] origin(RtCaptureFormat.Capture c) {
        String[] p = c.meta().getOrDefault("originSection", "0,0,0").split(",");
        return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
    }

    // ------------------------------------------------------------------------------------------------ the game-class replay

    record Replayed(float[] out, int frames, int resident, long blasBytes, long blasBuilds, long tlasBuilds, double buildMs, double traceMs, String device) {}

    /** The capture's arena as the raster arena: one buffer, per section three bucket runs. */
    static final class CaptureSource implements RtSectionSource {
        final Map<Long, long[]> at = new HashMap<>();   // key -> {off0, off1, off2}
        final Map<Long, int[]> q = new HashMap<>();
        long addr;

        @Override
        public List<Range> ranges(int sx, int sy, int sz) {
            long k = SectionGeometry.key(sx, sy, sz);
            long[] o = at.get(k);
            List<Range> out = new ArrayList<>();
            if (o == null) return out;
            int[] n = q.get(k);
            for (int b = 0; b < 3; b++) if (n[b] > 0) out.add(new Range(b, addr, o[b], 16, n[b]));
            return out;
        }

        @Override
        public int[] quads(int sx, int sy, int sz) {
            return q.get(SectionGeometry.key(sx, sy, sz));
        }
    }

    static Replayed replay(RtCaptureFormat.Capture c, float[] rays, float[] sun, long devCap) {
        int n = W * H;
        long t0 = System.nanoTime();
        try (HeadlessGpuDevice dev = new HeadlessGpuDevice(devCap, false, true)) {
            dev.uploadsAfterFrame = true;   // stricter than the game, as rtWorldReplay
            RtAccel helper = new RtAccel(dev);
            RtCompute comp = new RtCompute(dev);
            long total = 0;
            for (RtCaptureFormat.Section s : c.sections()) total += (long) s.totalQuads() * 4 * 16;
            VkBuf arena = helper.inputBuffer(Math.max(16, total));
            CaptureSource src = new CaptureSource();
            src.addr = helper.address(arena);
            long off = 0;
            for (RtCaptureFormat.Section s : c.sections()) {
                long k = SectionGeometry.key(s.sx(), s.sy(), s.sz());
                long[] o = new long[3];
                for (int b = 0; b < 3; b++) {
                    o[b] = off;
                    if (s.quads()[b] == 0) continue;
                    ByteBuffer d = MemoryUtil.memAlloc(s.buckets()[b].remaining());
                    d.put(s.buckets()[b].duplicate()).flip();
                    dev.upload(arena, off, d);
                    MemoryUtil.memFree(d);
                    off += s.buckets()[b].remaining();
                }
                src.at.put(k, o);
                src.q.put(k, s.quads().clone());
            }
            dev.flush();
            VkRtBackend backend = new VkRtBackend(dev, 16);
            RtSession ses = new RtSession(RtSettings.DEFAULT, new RtMemory.Counter(64L << 30), RtMemoryModel.radvAssumed(), src, backend);
            ses.queue.radius = 4096;   // the replay holds the whole capture resident
            ses.queue.maxBuilds = 64;
            ses.queue.maxTriangles = 400_000;
            RtGameBridge bridge = new RtGameBridge(ses.queue, src);
            int[] org = origin(c);
            if (!ses.enable(W, H)) throw new IllegalStateException("targets refused");
            for (RtCaptureFormat.Section s : c.sections()) bridge.sectionBuilt(s.sx(), s.sy(), s.sz(), s.totalQuads() == 0);
            int frames = 0;
            RtSession.FrameStats st;
            do {
                backend.origin(org[0], org[1], org[2]);
                st = ses.frame(org[0], org[1], org[2]);
                dev.flush();
                frames++;
            } while ((st.built() > 0 || st.pending() > 0) && frames < 20000);
            backend.origin(org[0], org[1], org[2]);
            st = ses.frame(org[0], org[1], org[2]);   // one idle frame: the TLAS of the settled set
            dev.flush();
            double buildMs = (System.nanoTime() - t0) / 1e6;
            long tlas = backend.tlasForTrace();
            if (tlas == 0) throw new IllegalStateException("no TLAS to trace after " + frames + " frames (resident " + st.resident() + ", pending " + st.pending() + ")");
            float[] out = trace(dev, comp, tlas, rays, sun, helper::inputBuffer, true);
            double traceMs = (System.nanoTime() - t0) / 1e6 - buildMs;
            long bb = backend.blasLiveBytes(), bl = backend.blasBuilds, tb = backend.tlasBuilds;
            int resident = ses.queue.residentCount();
            ses.disable();
            dev.flush();
            if (backend.liveObjects() != 0 || backend.liveBytes() != 0) throw new IllegalStateException("live objects after disable: " + backend.liveObjects());
            dev.destroyBuffer(arena);
            helper.close();
            comp.close();
            dev.flush();
            return new Replayed(out, frames, resident, bb, bl, tb, buildMs, traceMs, dev.deviceName);
        }
    }

    interface Alloc { VkBuf make(long bytes); }

    static float[] trace(HeadlessGpuDevice dev, RtCompute comp, long tlas, float[] rays, float[] sun, Alloc alloc, boolean free) {
        int n = W * H;
        RtCompute.Pass p = comp.create("rtgame", Glsl.compile("rtgame", SH, Glsl.COMPUTE), true, 3, 0);
        VkBuf rb = alloc.make(n * 32L), ob = alloc.make(n * 16L), sb = alloc.make(16);
        ByteBuffer r = MemoryUtil.memAlloc(n * 32).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : rays) r.putFloat(f);
        r.flip();
        dev.upload(rb, 0, r);
        MemoryUtil.memFree(r);
        ByteBuffer s = MemoryUtil.memAlloc(16).order(ByteOrder.LITTLE_ENDIAN);
        s.putFloat(sun[0]).putFloat(sun[1]).putFloat(sun[2]).putFloat(0).flip();
        dev.upload(sb, 0, s);
        MemoryUtil.memFree(s);
        dev.flush();   // uploads land in their own submit (uploadsAfterFrame devices: after the frame's work)
        dev.barrier(org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT);
        comp.dispatch(p, tlas, new VkBuf[]{rb, ob, sb}, null, (n + 63) / 64, 1, 1);
        ByteBuffer o = dev.readBuffer(ob, 0, n * 16).order(ByteOrder.LITTLE_ENDIAN);
        comp.resetSets();
        float[] out = new float[n * 4];
        o.asFloatBuffer().get(out);
        comp.destroy(p);
        if (free) {
            dev.destroyBuffer(rb);
            dev.destroyBuffer(ob);
            dev.destroyBuffer(sb);
        }
        dev.flush();
        return out;
    }

    // ------------------------------------------------------------------------------------------------ CPU brute force

    /** Decoded triangles of the SOLID buckets (opaque + cutout), world space, per section with its box. */
    static final class CpuWorld {
        final List<float[]> boxes = new ArrayList<>();   // minx,miny,minz,maxx,maxy,maxz
        final List<float[]> tris = new ArrayList<>();    // per section: 9 floats per triangle

        CpuWorld(RtCaptureFormat.Capture c, int[] org) {
            for (RtCaptureFormat.Section s : c.sections()) {
                int quads = s.quads()[0] + s.quads()[1];
                if (quads == 0) continue;
                float[] t = new float[quads * 18];
                int ti = 0;
                float[] box = {1e30f, 1e30f, 1e30f, -1e30f, -1e30f, -1e30f};
                float ox = (s.sx() - org[0]) * 16f, oy = (s.sy() - org[1]) * 16f, oz = (s.sz() - org[2]) * 16f;
                for (int b = 0; b < 2; b++) {
                    if (s.quads()[b] == 0) continue;
                    ByteBuffer d = s.buckets()[b].duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    for (int q = 0; q < s.quads()[b]; q++) {
                        float[][] v = new float[4][3];
                        for (int k = 0; k < 4; k++) {
                            int p = (q * 4 + k) * 16;
                            v[k][0] = RtScene.decodePos(d.getShort(p)) + ox;
                            v[k][1] = RtScene.decodePos(d.getShort(p + 2)) + oy;
                            v[k][2] = RtScene.decodePos(d.getShort(p + 4)) + oz;
                            for (int a = 0; a < 3; a++) {
                                box[a] = Math.min(box[a], v[k][a]);
                                box[a + 3] = Math.max(box[a + 3], v[k][a]);
                            }
                        }
                        int[][] idx = {{0, 1, 2}, {2, 3, 0}};
                        for (int[] tr : idx)
                            for (int k : tr) { t[ti++] = v[k][0]; t[ti++] = v[k][1]; t[ti++] = v[k][2]; }
                    }
                }
                boxes.add(box);
                tris.add(t);
            }
        }

        /** nearest hit distance along (o, d) within (tmin, tmax), or -1 */
        double nearest(double[] o, double[] d, double tmin, double tmax, boolean any) {
            double best = tmax;
            boolean found = false;
            for (int s = 0; s < boxes.size(); s++) {
                float[] b = boxes.get(s);
                double t0 = tmin, t1 = best;
                for (int a = 0; a < 3 && t0 <= t1; a++) {
                    double inv = 1.0 / (d[a] == 0 ? 1e-30 : d[a]), ta = (b[a] - o[a]) * inv, tb = (b[a + 3] - o[a]) * inv;
                    t0 = Math.max(t0, Math.min(ta, tb) - 1e-4);
                    t1 = Math.min(t1, Math.max(ta, tb) + 1e-4);
                }
                if (t0 > t1) continue;
                float[] t = tris.get(s);
                for (int i = 0; i < t.length; i += 9) {
                    double h = tri(o, d, t, i);
                    if (h > tmin && h < best) {
                        best = h;
                        found = true;
                        if (any) return best;
                    }
                }
            }
            return found ? best : -1;
        }

        static double tri(double[] o, double[] d, float[] t, int i) {
            double e1x = t[i + 3] - t[i], e1y = t[i + 4] - t[i + 1], e1z = t[i + 5] - t[i + 2];
            double e2x = t[i + 6] - t[i], e2y = t[i + 7] - t[i + 1], e2z = t[i + 8] - t[i + 2];
            double px = d[1] * e2z - d[2] * e2y, py = d[2] * e2x - d[0] * e2z, pz = d[0] * e2y - d[1] * e2x;
            double det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1e-12) return -1;
            double id = 1.0 / det, sx = o[0] - t[i], sy = o[1] - t[i + 1], sz = o[2] - t[i + 2];
            double u = (sx * px + sy * py + sz * pz) * id;
            if (u < 0 || u > 1) return -1;
            double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
            double v = (d[0] * qx + d[1] * qy + d[2] * qz) * id;
            if (v < 0 || u + v > 1) return -1;
            return (e2x * qx + e2y * qy + e2z * qz) * id;
        }
    }

    /** Samples {@code count} rays: GPU hit / sun visibility against the CPU; mismatches are edge rays within tolerance of a seam. */
    static String cpuCheck(CpuWorld w, float[] rays, float[] sun, float[] out, int count) {
        Random rnd = new Random(7);
        int bad = 0, hits = 0;
        double worst = 0;
        for (int k = 0; k < count; k++) {
            int i = rnd.nextInt(W * H);
            double[] o = {rays[i * 8], rays[i * 8 + 1], rays[i * 8 + 2]}, d = {rays[i * 8 + 4], rays[i * 8 + 5], rays[i * 8 + 6]};
            double t = w.nearest(o, d, 0, rays[i * 8 + 3], false), g = out[i * 4];
            if (t > 0) hits++;
            if ((t < 0) != (g < 0) || (t > 0 && Math.abs(t - g) > 2e-3 * Math.max(1, t))) {
                bad++;
                continue;
            }
            if (t > 0) {
                double[] p = {o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t}, sd = {sun[0], sun[1], sun[2]};
                boolean lit = w.nearest(p, sd, 0.002, 1e4, true) < 0;
                if (lit != (out[i * 4 + 1] > 0.5)) bad++;
                worst = Math.max(worst, Math.abs(t - g));
            }
        }
        return count + " rays (" + hits + " hits): " + bad + " differ, worst dt " + String.format(Locale.ROOT, "%.5f", worst) + ";;" + bad + ";;" + count;
    }

    // ------------------------------------------------------------------------------------------------ synthetic capture

    static RtCaptureFormat.Capture fromScene(RtScene scene) {
        RtCamera cam = new RtCamera(new Vector3f(-2.0f, 16.5f, 27.0f), new Vector3f(16.0f, 7.0f, 14.0f), 70, W, H);
        RtReplay.Light light = new RtReplay.Light(new RtFrameData().waterExtinction);
        RtFrameData fd = new RtFrameData();
        cam.viewProj().get(fd.viewProj);
        System.arraycopy(fd.viewProj, 0, fd.prevViewProj, 0, 16);
        new Matrix4f(cam.viewProj()).invert().get(fd.invViewProj);
        fd.camPos[0] = cam.pos.x; fd.camPos[1] = cam.pos.y; fd.camPos[2] = cam.pos.z;
        Vector3f f = cam.forward();
        fd.camFwd[0] = f.x; fd.camFwd[1] = f.y; fd.camFwd[2] = f.z;
        System.arraycopy(light.sunDir, 0, fd.sunDir, 0, 3);
        System.arraycopy(light.sunE, 0, fd.sunE, 0, 3);
        fd.sunAngularRadius = light.sunRadius;
        fd.width = W;
        fd.height = H;
        float[] view = new float[16], proj = new float[16];
        new Matrix4f().lookAt(cam.pos, cam.target, new Vector3f(0, 1, 0)).get(view);
        new Matrix4f().scale(1, -1, 1).perspective((float) Math.toRadians(cam.fovYDeg), W / (float) H, cam.near, cam.far, true).get(proj);
        List<RtCaptureFormat.Section> secs = new ArrayList<>();
        for (RtScene.SectionMesh m : scene.sections) {
            ByteBuffer[] b = new ByteBuffer[3];
            for (int k = 0; k < 3; k++) if (m.quads[k] > 0) b[k] = m.buckets[k].duplicate().order(ByteOrder.LITTLE_ENDIAN);
            secs.add(new RtCaptureFormat.Section(m.sx, m.sy, m.sz, m.quads.clone(), b));
        }
        List<ByteBuffer> lv = new ArrayList<>();
        ByteBuffer at = ByteBuffer.allocate(RtScene.ATLAS * RtScene.ATLAS * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int t : scene.atlas) at.putInt(t);
        at.flip();
        lv.add(at);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("projection", "vk");
        meta.put("originSection", "0,0,0");
        return new RtCaptureFormat.Capture(meta, writeToBuffer(fd), view, proj, 16, secs, new RtCaptureFormat.Atlas(RtScene.ATLAS, RtScene.ATLAS, lv));
    }

    static ByteBuffer writeToBuffer(RtFrameData fd) {
        ByteBuffer b = ByteBuffer.allocate(RtFrameData.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        fd.write(b);
        b.flip();
        return b;
    }

    static String sha(float[] f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        ByteBuffer b = ByteBuffer.allocate(f.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : f) b.putInt(Float.floatToRawIntBits(x));
        md.update(b.array());
        return HexFormat.of().formatHex(md.digest());
    }

    static void image(Path file, float[] out) throws IOException {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < W * H; i++) {
            float t = out[i * 4], vis = out[i * 4 + 1];
            int v;
            if (t < 0) v = 0x6aa8e8;   // sky
            else {
                float g = Math.max(0.15f, 1f - t / 120f), s = vis > 0.5f ? 1f : 0.45f;
                int c = Math.min(255, (int) (255 * g * s));
                v = (c << 16) | (c << 8) | c;
            }
            img.setRGB(i % W, i / W, v);
        }
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
    }

    // ------------------------------------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        String cap = System.getProperty("rt.capture");
        if (cap != null) realCapture(Path.of(cap));
        else synthetic();
        System.out.println("# rtgamereplay " + (fail == 0 ? "PASS" : "FAIL") + " " + pass + "/" + (pass + fail));
        System.exit(fail == 0 ? 0 : 1);
    }

    static void synthetic() throws Exception {
        RtScene scene = new RtScene();
        RtCaptureFormat.Capture mem = fromScene(scene);
        int[] org = origin(mem);
        float[] rays = rays(mem, org), sun = sunDir(mem);
        System.out.println("synthetic: " + mem.sections().size() + " sections, " + mem.quads() + " quads, " + W + "x" + H);
        // ---- the rtReplay way: RtGpu builds its own BLAS/TLAS from the scene (steps a/b geometry)
        float[] direct;
        try (RtGpu gpu = RtReplay.newGpuOrSkip(scene, W, H)) {
            direct = trace(gpu.dev, gpu.comp, gpu.tlas.handle, rays, sun, gpu::buf, false);
        }
        // ---- writer -> reader
        Path dir = CACHE.resolve("_synthetic").resolve("hall");
        try (RtCaptureFormat.Writer w = new RtCaptureFormat.Writer(dir, 16)) {
            w.atlas(mem.atlas());
            w.frame(mem.frame(), mem.view(), mem.proj());
            for (RtCaptureFormat.Section s : mem.sections()) if (!w.section(s)) throw new IllegalStateException("cap");
            w.meta("projection", "vk");
            w.meta("originSection", "0,0,0");
            w.meta("label", "synthetic RtScene hall");
        }
        RtCaptureFormat.Capture back = RtCaptureFormat.read(dir);
        check("round trip: " + back.sections().size() + " sections, " + back.quads() + " quads equal the scene", sectionsEqual(mem, back));
        check("round trip: atlas (" + back.atlas().width() + "x" + back.atlas().height() + ", " + back.atlas().levels().size() + " level) and frame data equal",
                back.atlas().levels().get(0).equals(mem.atlas().levels().get(0)) && back.frame().equals(mem.frame())
                        && Arrays.equals(back.view(), mem.view()) && Arrays.equals(back.proj(), mem.proj()));
        // ---- through the game classes
        Replayed r = replay(back, rays, sun, 1L << 30);
        int diff = 0, vdiff = 0, hits = 0;
        double worst = 0;
        for (int i = 0; i < W * H; i++) {
            if (Float.floatToRawIntBits(direct[i * 4]) != Float.floatToRawIntBits(r.out()[i * 4])) {
                diff++;
                worst = Math.max(worst, Math.abs(direct[i * 4] - r.out()[i * 4]));
            }
            if (direct[i * 4 + 1] != r.out()[i * 4 + 1]) vdiff++;
            if (direct[i * 4] > 0) hits++;
        }
        System.out.println("  replay: " + r.frames() + " frames, resident " + r.resident() + ", BLAS " + r.blasBuilds() + " (" + r.blasBytes() + " B), TLAS builds "
                + r.tlasBuilds() + String.format(Locale.ROOT, ", build %.0f ms, trace %.0f ms (%s)", r.buildMs(), r.traceMs(), r.device()));
        for (int i = 0, shown = 0; i < W * H && shown < 5; i++)
            if (direct[i * 4] != r.out()[i * 4]) { System.out.println("  px " + i + " direct " + direct[i * 4] + " replay " + r.out()[i * 4]); shown++; }
        check("steps (a)/(b): primary hit distance of the replayed game classes is bit-identical to the rtReplay build on " + hits + " hit pixels of " + W * H
                + " (" + diff + " differ, worst " + worst + ")", diff == 0 && hits > W * H / 4);
        check("steps (a)/(b): sun visibility identical (" + vdiff + " differ)", vdiff == 0);
        String cc = cpuCheck(new CpuWorld(back, org), rays, sun, r.out(), 600);
        String[] p = cc.split(";;");
        check("CPU brute force on the replay: " + p[0], Integer.parseInt(p[1]) <= Integer.parseInt(p[2]) / 100);
        check("all sections resident after the replay (" + r.resident() + " of " + nonEmpty(back) + " non-empty)", r.resident() == nonEmpty(back));
        image(CACHE.resolve("_synthetic").resolve("out").resolve("replay.png"), r.out());
    }

    static int nonEmpty(RtCaptureFormat.Capture c) {
        int n = 0;
        for (RtCaptureFormat.Section s : c.sections()) if (s.totalQuads() > 0) n++;
        return n;
    }

    static boolean sectionsEqual(RtCaptureFormat.Capture a, RtCaptureFormat.Capture b) {
        if (a.sections().size() != b.sections().size()) return false;
        for (int i = 0; i < a.sections().size(); i++) {
            RtCaptureFormat.Section x = a.sections().get(i), y = b.sections().get(i);
            if (x.sx() != y.sx() || x.sy() != y.sy() || x.sz() != y.sz() || !Arrays.equals(x.quads(), y.quads())) return false;
            for (int k = 0; k < 3; k++) {
                if (x.quads()[k] == 0) continue;
                if (!x.buckets()[k].duplicate().equals(y.buckets()[k].duplicate())) return false;
            }
        }
        return true;
    }

    static void realCapture(Path dir) throws Exception {
        RtCaptureFormat.Capture c = RtCaptureFormat.read(dir);
        int[] org = origin(c);
        System.out.println("capture " + dir.getFileName() + ": " + c.sections().size() + " sections, " + c.quads() + " quads, meta " + c.meta());
        check("capture is outside the repository (under ~/.cache)", dir.toAbsolutePath().normalize().startsWith(Path.of(System.getProperty("user.home"), ".cache")));
        float[] rays = rays(c, org), sun = sunDir(c);
        Replayed r = replay(c, rays, sun, 3L << 30);
        System.out.println("  replay: " + r.frames() + " frames, resident " + r.resident() + ", BLAS " + r.blasBuilds() + " (" + r.blasBytes() + " B), TLAS builds "
                + r.tlasBuilds() + String.format(Locale.ROOT, ", build %.0f ms, trace %.0f ms (%s)", r.buildMs(), r.traceMs(), r.device()));
        int hits = 0, lit = 0;
        for (int i = 0; i < W * H; i++) {
            if (r.out()[i * 4] > 0) { hits++; if (r.out()[i * 4 + 1] > 0.5f) lit++; }
        }
        System.out.println(String.format(Locale.ROOT, "  primary hits %.1f%% of pixels, %.1f%% of them sunlit", 100.0 * hits / (W * H), hits == 0 ? 0 : 100.0 * lit / hits));
        check("all non-empty captured sections resident (" + r.resident() + " of " + nonEmpty(c) + ")", r.resident() == nonEmpty(c));
        check("primary rays hit geometry (" + hits + " of " + W * H + " pixels)", hits > W * H / 20);
        String[] p = cpuCheck(new CpuWorld(c, org), rays, sun, r.out(), 400).split(";;");
        check("CPU brute force: " + p[0], Integer.parseInt(p[1]) <= Integer.parseInt(p[2]) / 50);
        Path out = dir.resolve("out");
        image(out.resolve("replay.png"), r.out());
        String sha = sha(r.out()), mode = System.getProperty("rt.golden", "");
        Path g = dir.resolve("golden.txt");
        if (mode.equals("write")) Files.writeString(g, sha + "\n");
        else if (mode.equals("check")) check("local golden: traced buffer sha256 " + sha.substring(0, 12), Files.exists(g) && Files.readString(g).trim().equals(sha));
        Files.writeString(out.resolve("metrics.txt"), "sha256 " + sha + "\nhits " + hits + "\nlit " + lit + "\n");
    }
}
