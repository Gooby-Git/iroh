package dev.elemore.iroh.rttest;

import dev.elemore.iroh.gpu.Glsl;
import dev.elemore.iroh.gpu.HeadlessGpuDevice;
import dev.elemore.iroh.gpu.VkGpuDevice.VkBuf;
import dev.elemore.iroh.rt.*;
import dev.elemore.iroh.rt.game.RtGameBridge;
import dev.elemore.iroh.rt.game.RtSession;
import dev.elemore.iroh.rt.game.RtSectionSource;
import dev.elemore.iroh.rt.game.VkRtBackend;
import dev.elemore.iroh.rt.vk.RtAccel;
import dev.elemore.iroh.rt.vk.RtCompute;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * R1-HANG: a world-scale replay of the in-game R1 scenario, offline on lavapipe ({@code ./gradlew rtWorldReplay}; last line
 * "# rtworld PASS n/n"). It drives the GAME's RT classes (RtSession + RtGameBridge + BlasBuildQueue + VkRtBackend +
 * RtAccel) over a terrain arena in Iroh's 16-byte vertex format, with uploads recorded during a frame landing AFTER it
 * ({@link HeadlessGpuDevice#uploadsAfterFrame}; arena edits land in an earlier submit). UPLOAD-SYNC: that is stricter
 * than the game, where the batch lands before the frame; passing under it covers both orders. Per frame: section events (load ring around the camera, edits, unloads), the
 * session's budgeted BLAS builds + TLAS, then one ray-query dispatch of downward rays against
 * {@link VkRtBackend#tlasForTrace}, each compared with the height the resident section was built from.
 * Phase 1 = R1's settings (MEDIUM, residency 8: 8 builds / 200k triangles per frame), camera parked until everything
 * is resident, then flying, with edits and unloads. Phase 2 = safe mode ({@code iroh.rt.safe}: residency 2).
 * Checks: every ray right, the queue's per-frame bound held, the session drained, no live objects / bytes after
 * disable, 0 validation errors (the task runs the layer; sync validation in core mode, -Pvalidate=gpuav for GPU-AV).
 * {@code -Ptrace=false} skips the ray dispatch (lavapipe + GPU-AV instrumentation crash, see vkval-extra-gate.sh).
 * Not covered: frames in flight (HeadlessGpuDevice waits each frame), entities (rtEntities), real RADV timing.
 */
public final class RtWorldReplay {
    static final int VERTEX = 16, GRID = 24, Y_TOP = 200;
    /** quads per bucket in one arena slot (opaque, cutout, translucent) and the bucket offsets in bytes */
    static final int[] CAP = {1024, 64, 256};
    static final long[] OFF = {0, 1024L * 4 * VERTEX, (1024L + 64) * 4 * VERTEX};
    static final long SLOT_BYTES = (1024L + 64 + 256) * 4 * VERTEX;

    static final String SH = """
            #version 460
            #extension GL_EXT_ray_query : require
            layout(local_size_x = 64) in;
            layout(set = 0, binding = 0) uniform accelerationStructureEXT tlas;
            layout(std430, set = 0, binding = 1) readonly buffer I { vec4 rays[]; };
            layout(std430, set = 0, binding = 2) buffer O { float o[]; };
            void main() {
                uint i = gl_GlobalInvocationID.x;
                if (i >= uint(rays.length())) return;
                rayQueryEXT rq;
                rayQueryInitializeEXT(rq, tlas, gl_RayFlagsOpaqueEXT, 0x01, rays[i].xyz, 0.0, vec3(0, -1, 0), rays[i].w);
                uint n = 0u;
                while (rayQueryProceedEXT(rq)) { if (++n > 256u) { rayQueryTerminateEXT(rq); break; } }
                o[i] = rayQueryGetIntersectionTypeEXT(rq, true) == gl_RayQueryCommittedIntersectionTriangleEXT ? rayQueryGetIntersectionTEXT(rq, true) : -1.0;
            }
            """;

    /** Terrain: column height (top block y) per world block column; edits change it in place. */
    static final Map<Long, Integer> edits = new HashMap<>();

    static int height(int x, int z) {
        Integer e = edits.get(((long) x << 32) ^ (z & 0xffffffffL));
        if (e != null) return e;
        double h = 70 + 7 * Math.sin(x * 0.11) + 6 * Math.cos(z * 0.07) + 3 * Math.sin((x + z) * 0.31);
        return (int) Math.floor(h);
    }

    /** Section mesh: top faces (opaque), cross-free cutout panels on 1 in 7 columns, side faces, water at 62 (translucent). */
    static final class Mesh {
        final ByteBuffer[] b = new ByteBuffer[3];
        final int[] quads = new int[3];
        final int[] heights = new int[256];   // the column heights this mesh was made from (local x + 16 z)

        Mesh() {
            for (int i = 0; i < 3; i++) b[i] = ByteBuffer.allocate(CAP[i] * 4 * VERTEX).order(ByteOrder.LITTLE_ENDIAN);
        }

        void v(int bucket, float x, float y, float z) {
            ByteBuffer o = b[bucket];
            o.putShort((short) Math.round((x - 4) * 2048)).putShort((short) Math.round((y - 4) * 2048)).putShort((short) Math.round((z - 4) * 2048));
            o.putShort((short) 0).putInt(0xffffffff).putInt(0);
        }

        void quad(int bucket, float[] p) {
            if (quads[bucket] >= CAP[bucket]) throw new IllegalStateException("slot full: bucket " + bucket);
            for (int k = 0; k < 4; k++) v(bucket, p[k * 3], p[k * 3 + 1], p[k * 3 + 2]);
            quads[bucket]++;
        }
    }

    static Mesh mesh(int sx, int sy, int sz) {
        Mesh m = new Mesh();
        int y0 = sy * 16;
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int x = sx * 16 + lx, z = sz * 16 + lz, h = height(x, z);
                m.heights[lx + 16 * lz] = h;
                float top = h + 1 - y0;
                if (h >= y0 && h < y0 + 16)
                    m.quad(SectionGeometry.OPAQUE, new float[]{lx, top, lz, lx, top, lz + 1, lx + 1, top, lz + 1, lx + 1, top, lz});
                // side faces toward lower neighbours, clipped to this section (vertical: never hit by the downward rays)
                int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] d : nb) {
                    int hn = height(x + d[0], z + d[1]);
                    for (int y = Math.max(hn + 1, y0); y <= Math.min(h, y0 + 15); y++) {
                        float ly = y - y0, fx = d[0] > 0 ? lx + 1 : lx, fz = d[1] > 0 ? lz + 1 : lz;
                        if (d[0] != 0) m.quad(SectionGeometry.OPAQUE, new float[]{fx, ly, lz, fx, ly + 1, lz, fx, ly + 1, lz + 1, fx, ly, lz + 1});
                        else m.quad(SectionGeometry.OPAQUE, new float[]{lx, ly, fz, lx + 1, ly, fz, lx + 1, ly + 1, fz, lx, ly + 1, fz});
                    }
                }
                if (Math.floorMod(x * 31 + z * 17, 7) == 0 && h + 1 >= y0 && h + 1 < y0 + 16) {   // a grass panel at x + 0.25
                    float fx = lx + 0.25f, b = h + 1 - y0;
                    m.quad(SectionGeometry.CUTOUT, new float[]{fx, b, lz, fx, b + 1, lz, fx, b + 1, lz + 1, fx, b, lz + 1});
                }
                if (h < 61 && 62 >= y0 && 62 < y0 + 16) {
                    float w = 62 - y0;
                    m.quad(SectionGeometry.TRANSLUCENT, new float[]{lx, w, lz, lx, w, lz + 1, lx + 1, w, lz + 1, lx + 1, w, lz});
                }
            }
        return m;
    }

    /** The "raster arena": fixed slots, one per loaded section, 3 buckets each; uploads go into the "beginFrame" submit. */
    static final class World implements RtSectionSource {
        final HeadlessGpuDevice dev;
        final VkBuf arena;
        final long arenaAddr;
        final int slots;
        final Deque<Integer> free = new ArrayDeque<>();
        final Map<Long, int[]> loaded = new HashMap<>();      // key -> {slot, q0, q1, q2}
        final Map<Long, int[]> heightsNow = new HashMap<>();  // key -> heights of the CURRENT mesh
        final Map<Long, int[]> builtFrom = new HashMap<>();   // key -> heights the session last BUILT from (ranges() at build time)
        int rangeCalls;

        World(HeadlessGpuDevice dev, RtAccel helper, int slots) {
            this.dev = dev;
            this.slots = slots;
            arena = helper.inputBuffer((long) slots * SLOT_BYTES);
            arenaAddr = helper.address(arena);
            for (int i = 0; i < slots; i++) free.add(i);
        }


        /** (Re)meshes a section into its slot (an upload in the coming "beginFrame" submit); false = it has no geometry. */
        boolean load(int sx, int sy, int sz) {
            long k = SectionGeometry.key(sx, sy, sz);
            Mesh m = mesh(sx, sy, sz);
            if (m.quads[0] + m.quads[1] + m.quads[2] == 0) {
                unload(sx, sy, sz);
                return false;
            }
            int[] e = loaded.get(k);
            int slot = e != null ? e[0] : free.pop();
            for (int bkt = 0; bkt < 3; bkt++) {
                if (m.quads[bkt] == 0) continue;
                ByteBuffer d = MemoryUtil.memAlloc(m.quads[bkt] * 4 * VERTEX);
                d.put(m.b[bkt].flip()).flip();
                dev.upload(arena, slot * SLOT_BYTES + OFF[bkt], d);
                MemoryUtil.memFree(d);
            }
            loaded.put(k, new int[]{slot, m.quads[0], m.quads[1], m.quads[2]});
            heightsNow.put(k, m.heights);
            return true;
        }

        void unload(int sx, int sy, int sz) {
            long k = SectionGeometry.key(sx, sy, sz);
            int[] e = loaded.remove(k);
            if (e != null) free.push(e[0]);
            heightsNow.remove(k);
        }

        @Override
        public List<Range> ranges(int sx, int sy, int sz) {
            rangeCalls++;
            long k = SectionGeometry.key(sx, sy, sz);
            int[] e = loaded.get(k);
            List<Range> out = new ArrayList<>();
            if (e == null) return out;
            for (int bkt = 0; bkt < 3; bkt++)
                if (e[1 + bkt] > 0)
                    out.add(new Range(bkt, arenaAddr, e[0] * SLOT_BYTES + OFF[bkt], VERTEX, e[1 + bkt]));
            builtFrom.put(k, heightsNow.get(k));
            return out;
        }

        @Override
        public int[] quads(int sx, int sy, int sz) {
            int[] e = loaded.get(SectionGeometry.key(sx, sy, sz));
            return e == null ? null : new int[]{e[1], e[2], e[3]};
        }
    }

    record Result(int frames, long rays, long wrong, long checked, int maxBuilt, long maxTris, int resident, long tris, double msPer100kTris,
                  long blasBuilds, long tlasBuilds, boolean settled, int residentParked) {}

    static Result phase(HeadlessGpuDevice dev, RtCompute comp, RtCompute.Pass pass, String name, int frames, boolean trace, StringBuilder log) {
        edits.clear();
        RtAccel helper = new RtAccel(dev);
        World w = new World(dev, helper, 2400);
        RtMemory.Counter mem = new RtMemory.Counter(RtLimits.capMiB() << 20);
        VkRtBackend backend = new VkRtBackend(dev, VERTEX);
        RtSession s = new RtSession(RtSettings.DEFAULT, mem, RtMemoryModel.radvAssumed(), w, backend);
        RtGameBridge bridge = new RtGameBridge(s.queue, w);
        int R = s.queue.radius, load = R + 2;
        dev.flush();
        if (!s.enable(160, 90)) throw new IllegalStateException("targets refused");
        int n = GRID * GRID;
        VkBuf rays = helper.inputBuffer(n * 16L), out = helper.inputBuffer(n * 4L);
        int camX = 0, camZ = 0, camY = 5;   // sections; camera y 80 -> section 5
        Set<Long> want = new HashSet<>();
        long wrong = 0, checked = 0, raysTot = 0, trisTot = 0, nsBuilds = 0, trisTimed = 0;
        int maxBuilt = 0;
        long maxTris = 0;
        Random rnd = new Random(1234);
        int parked = 1_000_000, f = 0;   // parked until everything in range is built, then `frames` flying frames
        String firstWrong = null;
        boolean settled = false;
        int residentParked = 0;
        for (; f < parked + frames && f < 4000; f++) {
            // ---- camera: parked until everything in range is resident, then flies +x / +z with edits
            if (f >= parked && f % 3 == 0) { camX++; if (f % 6 == 0) camZ++; }
            // ---- "beginFrame": section loads / unloads / edits (arena uploads land before the frame, as in game)
            Set<Long> now = new HashSet<>();
            for (int dz = -load; dz <= load; dz++)
                for (int dx = -load; dx <= load; dx++)
                    for (int sy = 2; sy <= 6; sy++) now.add(SectionGeometry.key(camX + dx, sy, camZ + dz));
            for (Long k : new ArrayList<>(want))
                if (!now.contains(k)) {
                    int[] c = unkey(k);
                    w.unload(c[0], c[1], c[2]);
                    bridge.sectionReset(c[0], c[1], c[2]);
                    want.remove(k);
                }
            int loadsThisFrame = 0;
            for (Long k : now)
                if (want.add(k)) {
                    int[] c = unkey(k);
                    boolean has = w.load(c[0], c[1], c[2]);
                    bridge.sectionBuilt(c[0], c[1], c[2], !has);
                    loadsThisFrame++;
                }
            if (f >= parked && f % 5 == 0) {   // edit: raise or dig a 3x3 patch near the camera, inside its section
                int x = (camX + rnd.nextInt(5) - 2) * 16 + 4 + rnd.nextInt(8), z = (camZ + rnd.nextInt(5) - 2) * 16 + 4 + rnd.nextInt(8);
                int h0 = height(x, z), sy = Math.floorDiv(h0, 16), h1 = Math.max(sy * 16 + 1, Math.min(sy * 16 + 14, h0 + (rnd.nextBoolean() ? 2 : -2)));
                for (int ez = -1; ez <= 1; ez++) for (int ex = -1; ex <= 1; ex++) edits.put(((long) (x + ex) << 32) ^ ((z + ez) & 0xffffffffL), h1);
                for (int ez = -1; ez <= 1; ez++)
                    for (int ex = -1; ex <= 1; ex++)
                        for (int sy2 = 2; sy2 <= 6; sy2++) {   // remesh every touched section (neighbours' side faces too)
                            int sx = Math.floorDiv(x + ex * 2, 16), sz = Math.floorDiv(z + ez * 2, 16);
                            long k = SectionGeometry.key(sx, sy2, sz);
                            if (!want.contains(k)) continue;
                            boolean has = w.load(sx, sy2, sz);
                            bridge.sectionBuilt(sx, sy2, sz, !has);
                        }
            }
            dev.flush();   // the beginFrame submit: uploads, the frame waits on them
            // ---- the frame: RT at WORLD_START (builds + TLAS), then the trace
            long t0 = System.nanoTime();
            backend.origin(camX, camY, camZ);   // as RtFrameHooks.worldStart: the rebase origin first, then the frame
            RtSession.FrameStats st = s.frame(camX, camY, camZ);
            maxBuilt = Math.max(maxBuilt, st.built());
            if (!settled && f > 0 && st.built() == 0) { settled = true; residentParked = st.resident(); parked = f + 1; }   // all in range built
            maxTris = Math.max(maxTris, st.triangles());
            trisTot += st.triangles();
            if (st.built() > s.queue.maxBuilds || (st.built() > 1 && st.triangles() > s.queue.maxTriangles)) {
                log.append("FAIL ").append(name).append(" frame ").append(f).append(": per-frame budget broken ").append(st).append('\n');
                wrong++;
            }
            long tlas = backend.tlasForTrace();
            float[] expect = new float[n];
            if (trace && tlas != 0) {
                ByteBuffer rb = MemoryUtil.memAlloc(n * 16).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < n; i++) {
                    int gx = i % GRID, gz = i / GRID;
                    // spread over the residency box, plus a margin outside it (those must miss)
                    int x = (camX - R - 1) * 16 + (int) ((gx + 0.5) * (2 * R + 3) * 16 / GRID), z = (camZ - R - 1) * 16 + (int) ((gz + 0.5) * (2 * R + 3) * 16 / GRID);
                    rb.putFloat(x + 0.5f - camX * 16).putFloat(Y_TOP - camY * 16).putFloat(z + 0.5f - camZ * 16).putFloat(400);
                    int sx = Math.floorDiv(x, 16), sz = Math.floorDiv(z, 16);
                    expect[i] = -1;
                    // the surface is the highest resident built section with a top face over this column
                    for (int sy = 6; sy >= 2; sy--) {
                        long k = SectionGeometry.key(sx, sy, sz);
                        int[] hb = w.builtFrom.get(k);
                        if (hb == null || !s.queue.isResident(sx, sy, sz)) continue;
                        int h = hb[Math.floorMod(x, 16) + 16 * Math.floorMod(z, 16)];
                        if (h >= sy * 16 && h < sy * 16 + 16) { expect[i] = Y_TOP - (h + 1); break; }
                    }
                }
                rb.flip();
                dev.upload(rays, 0, rb);   // lands after this frame's work (game order), before the trace submit below
                MemoryUtil.memFree(rb);
            }
            dev.flush();   // end of frame: builds (+ the late ray upload); timing = the GPU (CPU) work of the builds
            long dt = System.nanoTime() - t0;
            if (st.triangles() > 0) { nsBuilds += dt; trisTimed += st.triangles(); }
            if (trace && tlas != 0) {
                // a second submit traces against this frame's TLAS (still valid: no BLAS changed since)
                if (backend.tlasForTrace() != tlas) throw new IllegalStateException("tlas changed");
                comp.dispatch(pass, tlas, new VkBuf[]{rays, out}, null, (n + 63) / 64, 1, 1);
                ByteBuffer r = dev.readBuffer(out, 0, n * 4).order(ByteOrder.LITTLE_ENDIAN);
                comp.resetSets();
                for (int i = 0; i < n; i++) {
                    float got = r.getFloat(i * 4);
                    checked++;
                    if (Math.abs(got - expect[i]) > 1e-3f) {
                        wrong++;
                        if (firstWrong == null) firstWrong = "frame " + f + " ray " + i + " got " + got + " expected " + expect[i];
                    }
                }
                raysTot += n;
            }
            if (f % 40 == 0 || f == parked + frames - 1)
                log.append(String.format("     %s f%-3d cam %d,%d loads %d built %d tris %d resident %d pending %d used %d MiB%n", name, f, camX, camZ,
                        loadsThisFrame, st.built(), st.triangles(), st.resident(), st.pending(), st.usedBytes() >> 20));
        }
        int resident = s.queue.residentCount();
        long blasB = backend.blasBuilds, tlasB = backend.tlasBuilds;
        s.disable();
        dev.flush();
        int live = backend.liveObjects();
        long liveBytes = backend.liveBytes(), used = mem.usedBytes();
        if (live != 0 || liveBytes != 0 || used != 0) {
            log.append("FAIL ").append(name).append(": after disable live ").append(live).append(" bytes ").append(liveBytes).append(" owner ").append(used).append('\n');
            wrong++;
        }
        if (firstWrong != null) log.append("     first wrong: ").append(firstWrong).append('\n');
        dev.destroyBuffer(rays);
        dev.destroyBuffer(out);
        dev.destroyBuffer(w.arena);
        helper.close();
        dev.flush();
        return new Result(f, raysTot, wrong, checked, maxBuilt, maxTris, resident, trisTot,
                trisTimed == 0 ? 0 : nsBuilds / 1e6 / trisTimed * 100_000, blasB, tlasB, settled, residentParked);
    }

    /** Inverse of SectionGeometry.key (22-bit x, 22-bit z, 20-bit y, two's complement). */
    static int[] unkey(long k) {
        int x = (int) (k >>> 42) << 10 >> 10, z = (int) ((k >>> 20) & 0x3FFFFF) << 10 >> 10, y = (int) (k & 0xFFFFF) << 12 >> 12;
        if (SectionGeometry.key(x, y, z) != k) throw new IllegalStateException("bad key");
        return new int[]{x, y, z};
    }

    public static void main(String[] args) {
        boolean trace = !"false".equals(System.getProperty("rtworld.trace", "true"));
        int frames = Integer.getInteger("rtworld.frames", 240);
        int pass = 0, total = 0;
        StringBuilder log = new StringBuilder();
        try (HeadlessGpuDevice dev = new HeadlessGpuDevice(1L << 30, false, true)) {
            dev.uploadsAfterFrame = true;   // stricter than the game (UPLOAD-SYNC): in-frame uploads land after the frame
            RtCompute comp = new RtCompute(dev);
            RtCompute.Pass p = comp.create("rtworld", Glsl.compile("rtworld", SH, Glsl.COMPUTE), true, 2, 0);
            String[][] phases = {{"r1-medium-r8", null}, {"safe-r2", "true"}};
            for (String[] ph : phases) {
                if (ph[1] != null) System.setProperty("iroh.rt.safe", ph[1]); else System.clearProperty("iroh.rt.safe");
                String limits = RtLimits.describe(RtPreset.MEDIUM);
                Result r = phase(dev, comp, p, ph[0], ph[1] == null ? frames : frames / 2, trace, log);
                System.out.print(log);
                log.setLength(0);
                total++;
                boolean residentOk = r.settled() && (ph[1] == null ? r.residentParked() > 400 : r.residentParked() > 20);
                boolean ok = r.wrong() == 0 && residentOk && (!trace || r.checked() > 0);
                if (ok) pass++;
                System.out.println((ok ? "ok   " : "FAIL ") + ph[0] + " [" + limits + "]: " + r.frames() + " frames, rays " + r.rays() + " wrong " + r.wrong()
                        + ", max builds/frame " + r.maxBuilt() + " max tris/frame " + r.maxTris() + ", resident parked " + r.residentParked()
                        + (r.settled() ? " (settled)" : " (NOT settled)") + " / at end " + r.resident()
                        + ", BLAS builds " + r.blasBuilds() + " TLAS builds " + r.tlasBuilds() + ", tris built " + r.tris()
                        + String.format(", lavapipe %.1f ms per 100k tris (CPU Vulkan; RADV is far faster: UNVERIFIED there)", r.msPer100kTris()));
            }
            System.clearProperty("iroh.rt.safe");
            comp.destroy(p);
            comp.close();
            dev.flush();
        }
        System.out.println("# rtworld " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        if (pass != total) System.exit(1);
    }
}
