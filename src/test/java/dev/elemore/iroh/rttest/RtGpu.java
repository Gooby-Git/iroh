package dev.elemore.iroh.rttest;

import dev.elemore.iroh.gpu.HeadlessGpuDevice;
import dev.elemore.iroh.gpu.VkGpuDevice.VkBuf;
import dev.elemore.iroh.rt.RtFrameData;
import dev.elemore.iroh.rt.vk.RtAccel;
import dev.elemore.iroh.rt.vk.RtCompute;
import dev.elemore.iroh.rt.vk.RtShaders;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK12.*;

/**
 * The P5-0 RT pipeline on the headless lavapipe device: scene upload (raster vertex arena = BLAS input), BLAS per
 * section (+ compaction), TLAS, raster G-buffer, the ray-query passes, temporal accumulation and composition.
 * Every pass is timed (flush + wall clock: lavapipe RELATIVE cost only) and counts the rays it traced.
 */
final class RtGpu implements AutoCloseable {
    static final int PASS_PRIMARY = 0, PASS_SUN = 1, PASS_EMIT = 2, PASS_GI = 3, PASS_REFL = 4, COUNTER_CACHE_HITS = 8;
    static final int CACHE_ENTRIES = 1 << 16;

    final HeadlessGpuDevice dev;
    final RtAccel accel;
    final RtCompute comp;
    final RtScene scene;
    final int W, H, N;
    final RtFrameData fd = new RtFrameData();

    VkBuf verts, inst, atlas, mats, lights, frame, counters, origins, cmds, cache;
    VkBuf gbufCur, gbufPrev;
    Object gImage, dImage, raster, rasterEnt;
    RtCompute.Pass pRestir, pEmitUnit, pPrimary, pSun, pEmit, pGi, pResolve, pRefl, pAccum, pCompose, pSum, pSunVis, pComposeLook, pPost, pRecon;
    /** P5-1: upscaler jitter in pixels for the next setFrame (content shifts by +jitter). */
    float[] jitterPx = {0, 0};
    RtAccel.Accel tlas;
    final List<RtAccel.Accel> blas = new ArrayList<>();
    long blasBytesBuilt, blasBytesCompacted, blasScratchMax, tlasBytes;
    int sectionCount, instanceCount, totalQuads;
    final Map<String, long[]> timing = new LinkedHashMap<>(); // name -> {nanos, calls}

    RtGpu(RtScene scene, int w, int h) {
        this.scene = scene;
        this.W = w;
        this.H = h;
        this.N = w * h;
        dev = new HeadlessGpuDevice(1L << 30, false, true);
        accel = new RtAccel(dev);
        comp = new RtCompute(dev);
        uploadScene();
        buildAccel();
        createPipelines();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    VkBuf buf(long bytes) {
        return accel.inputBuffer(Math.max(16, bytes));
    }

    void up(VkBuf b, long off, ByteBuffer heap) {
        ByteBuffer d = MemoryUtil.memAlloc(heap.remaining());
        d.put(heap.duplicate()).flip();
        dev.upload(b, off, d);
        MemoryUtil.memFree(d);
    }

    void uploadBarrier() {
        dev.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
    }

    void sync() {
        dev.flush();
        comp.resetSets();
    }

    static ByteBuffer le(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    float[] readVec4(VkBuf b) {
        return readVec4(b, N);
    }

    float[] readVec4(VkBuf b, int count) {
        ByteBuffer r = dev.readBuffer(b, 0, count * 16);
        comp.resetSets();
        float[] f = new float[count * 4];
        r.asFloatBuffer().get(f);
        return f;
    }

    int[] readUvec4(VkBuf b) {
        ByteBuffer r = dev.readBuffer(b, 0, N * 16);
        comp.resetSets();
        int[] f = new int[N * 4];
        r.asIntBuffer().get(f);
        return f;
    }

    void writeVec4(VkBuf b, float[] f) {
        ByteBuffer x = le(f.length * 4);
        x.asFloatBuffer().put(f);
        up(b, 0, x);
        uploadBarrier();
    }

    void time(String name, Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        sync();
        long[] t = timing.computeIfAbsent(name, k -> new long[2]);
        t[0] += System.nanoTime() - t0;
        t[1]++;
    }

    // ------------------------------------------------------------------------------------------------ scene

    int[][] firstVertex; // per section, per bucket
    /** P5-3 entities: first vertex of each part model (appended after the terrain), its BLAS, the static TLAS instances,
     *  the per-frame pose buffer (6 vec4 per part instance: current + previous 3x4) and the entity draw commands. */
    int[] firstVertexPart;
    final List<RtAccel.Accel> partBlas = new ArrayList<>();
    final List<RtAccel.Instance> staticInstances = new ArrayList<>();
    VkBuf poses, entCmds;
    int entityCount;
    static final int MAX_ENT_INSTANCES = 4096;

    void uploadScene() {
        List<RtScene.SectionMesh> secs = scene.sections;
        sectionCount = secs.size();
        firstVertex = new int[sectionCount][3];
        int total = 0;
        for (int s = 0; s < sectionCount; s++)
            for (int b = 0; b < 3; b++) {
                firstVertex[s][b] = total;
                total += secs.get(s).quads[b] * 4;
                totalQuads += secs.get(s).quads[b];
            }
        RtEntities ents = scene.entities;
        int parts = ents == null ? 0 : RtEntities.PARTS;
        firstVertexPart = new int[parts];
        for (int p = 0; p < parts; p++) {
            firstVertexPart[p] = total;
            total += ents.quads[p] * 4;
        }
        ByteBuffer v = le(Math.max(16, total * 16));
        for (RtScene.SectionMesh m : secs) for (int b = 0; b < 3; b++) v.put(m.buckets[b].duplicate());
        for (int p = 0; p < parts; p++) v.put(ents.mesh[p].duplicate());
        v.flip();
        verts = buf(v.capacity());
        up(verts, 0, v);

        ByteBuffer in = le((sectionCount * 2 + parts) * 16), or = le(sectionCount * 16), cm = le(sectionCount * 20);
        for (int s = 0; s < sectionCount; s++) {
            RtScene.SectionMesh m = secs.get(s);
            in.putInt(firstVertex[s][0]).putInt(firstVertex[s][1]).putInt(0).putInt(0);
            in.putInt(firstVertex[s][2]).putInt(0).putInt(1).putInt(0);
            or.putFloat(m.sx * 16).putFloat(m.sy * 16).putFloat(m.sz * 16).putFloat(0);
            cm.putInt(m.totalQuads() * 6).putInt(1).putInt(0).putInt(firstVertex[s][0]).putInt(s);
        }
        for (int p = 0; p < parts; p++) in.putInt(firstVertexPart[p]).putInt(0).putInt(2).putInt(0);   // customIndex sectionCount*2 + p
        if (parts > 0) {
            poses = buf(MAX_ENT_INSTANCES * 96L);
            entCmds = dev.rawBuffer(MAX_ENT_INSTANCES * 20L, VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        }
        inst = buf(in.capacity());
        up(inst, 0, in.flip());
        origins = buf(or.capacity());
        up(origins, 0, or.flip());
        cmds = dev.rawBuffer(cm.capacity(), VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        up(cmds, 0, cm.flip());

        ByteBuffer at = le(scene.atlas.length * 4);
        for (int t : scene.atlas) at.putInt(t);
        atlas = buf(at.capacity());
        up(atlas, 0, at.flip());

        writeMaterials(scene.materials);
        writeLights(scene.lights);

        frame = buf(RtFrameData.BYTES);
        counters = buf(64);
        cache = buf((long) CACHE_ENTRIES * 48);
        fd.cacheMask = CACHE_ENTRIES - 1;
        gbufCur = buf(N * 16L);
        gbufPrev = buf(N * 16L);
        gImage = dev.createImage("gbuffer", W, H, VK_FORMAT_R32G32B32A32_UINT, false);
        dImage = dev.createImage("depth", W, H, VK_FORMAT_D32_SFLOAT, true);
        uploadBarrier();
        clearCache();
        sync();
    }

    /** (Re)writes the material table (2 vec4 per atlas tile). P5-1: re-callable (torch on/off test). */
    void writeMaterials(RtScene.Material[] materials) {
        ByteBuffer mt = le(RtScene.TILES * 32);
        for (RtScene.Material m : materials)
            mt.putFloat(m.roughness()).putFloat(m.alphaMode()).putFloat(m.water() ? 1 : 0).putFloat(m.spec() ? 1 : 0)
                    .putFloat(m.er()).putFloat(m.eg()).putFloat(m.eb()).putFloat(m.f0());
        if (mats == null) mats = buf(mt.capacity());
        up(mats, 0, mt.flip());
        uploadBarrier();
    }

    /** (Re)writes the emissive quad list with its power CDF. P5-1: re-callable (lights switched off = not listed). */
    void writeLights(List<RtScene.Light> ls) {
        double totalPower = 0;
        for (RtScene.Light l : ls) totalPower += l.power();
        ByteBuffer lb = le(Math.max(1, ls.size()) * 80);
        double cdf = 0;
        for (int i = 0; i < ls.size(); i++) {
            RtScene.Light l = ls.get(i);
            cdf += l.power() / totalPower;
            float c = i == ls.size() - 1 ? 1f : (float) cdf;
            lb.putFloat(l.p0()[0]).putFloat(l.p0()[1]).putFloat(l.p0()[2]).putFloat(l.area());
            lb.putFloat(l.e1()[0]).putFloat(l.e1()[1]).putFloat(l.e1()[2]).putFloat(c);
            lb.putFloat(l.e2()[0]).putFloat(l.e2()[1]).putFloat(l.e2()[2]).putFloat((float) (l.power() / totalPower));
            lb.putFloat(l.n()[0]).putFloat(l.n()[1]).putFloat(l.n()[2]).putFloat(0);
            lb.putFloat(l.radiance()[0]).putFloat(l.radiance()[1]).putFloat(l.radiance()[2]).putFloat(0);
        }
        if (lights == null || lightsBytes < lb.capacity()) { lights = buf(Math.max(lb.capacity(), 80L * 256)); lightsBytes = Math.max(lb.capacity(), 80 * 256); }
        up(lights, 0, lb.flip());
        uploadBarrier();
        fd.lightCount = ls.size();
        buildLightGrid(ls, totalPower);
    }

    // ------------------------------------------------------------------------------------------------ P5-3 light grid + ReSTIR
    dev.elemore.iroh.rt.RtLightGrid grid;
    VkBuf gridCells, gridEnts, resA, resB, resTmp;
    double gridBuildMs, raysPerPixel;

    /** Per-section light grid over the scene's sections, rebuilt whenever the light list changes (timed: CPU ms). */
    void buildLightGrid(List<RtScene.Light> ls, double totalPower) {
        float[] c = new float[ls.size() * 3], p = new float[ls.size()];
        for (int i = 0; i < ls.size(); i++) {
            RtScene.Light l = ls.get(i);
            for (int a = 0; a < 3; a++) c[i * 3 + a] = l.p0()[a] + 0.5f * (l.e1()[a] + l.e2()[a]);
            p[i] = (float) (l.power() / totalPower);
        }
        long t0 = System.nanoTime();
        grid = new dev.elemore.iroh.rt.RtLightGrid(scene.SX, scene.SY, scene.SZ, c, p);
        gridBuildMs = (System.nanoTime() - t0) / 1e6;
        uploadGrid();
    }

    void uploadGrid() {
        ByteBuffer cb = le(Math.max(16, grid.cells.length * 4)), eb = le(Math.max(16, grid.entLight.length * 8));
        for (int v : grid.cells) cb.putInt(v);
        for (int i = 0; i < grid.entLight.length; i++) eb.putInt(grid.entLight[i]).putFloat(grid.entCdf[i]);
        cb.position(0).limit(cb.capacity());
        eb.position(0).limit(eb.capacity());
        if (gridCells != null) { dev.destroyBuffer(gridCells); dev.destroyBuffer(gridEnts); }
        gridCells = buf(cb.capacity());
        gridEnts = buf(eb.capacity());
        up(gridCells, 0, cb);
        up(gridEnts, 0, eb);
        uploadBarrier();
    }

    void clearReservoirs() {
        if (resA == null) { resA = buf(N * 32L); resB = buf(N * 32L); resTmp = buf(N * 32L); }
        ByteBuffer z = le(N * 32);
        for (int i = 0; i < N; i++) z.putFloat(i * 32 + 24, -1f);
        up(resA, 0, z);
        up(resB, 0, z);
        uploadBarrier();
    }

    /** Block light through the light grid / ReSTIR (restir.comp, two phases), irradiance into {@code out}. */
    void restir(VkBuf out) {
        if (resA == null) clearReservoirs();
        ByteBuffer push = MemoryUtil.memAlloc(16).order(ByteOrder.LITTLE_ENDIAN);
        time("block light (grid / ReSTIR)", () -> {
            for (int ph = 0; ph < 2; ph++) {
                push.putInt(0, ph).putInt(4, grid.gx).putInt(8, grid.gy).putInt(12, grid.gz);
                comp.dispatch(pRestir, tlas.handle, scene(resA, resTmp, resB, out, gbufPrev, gridCells, gridEnts), push, (W + 7) / 8, (H + 7) / 8, 1);
                comp.barrier();
            }
        });
        MemoryUtil.memFree(push);
        VkBuf t = resA;
        resA = resB;
        resB = t;
    }

    int lightsBytes;

    void clearCache() {
        up(cache, 0, le(CACHE_ENTRIES * 48));
        uploadBarrier();
    }

    static float[] geomTransform() {
        float s = RtAccel.SNORM_SCALE, o = RtAccel.POS_OFFSET;
        return new float[]{s, 0, 0, o, 0, s, 0, o, 0, 0, s, o};
    }

    void buildAccel() {
        accel.barrierInputsToBuild();
        List<RtAccel.Instance> instances = new ArrayList<>();
        List<RtAccel.Accel> built = new ArrayList<>();
        List<Integer> owner = new ArrayList<>();
        for (int s = 0; s < sectionCount; s++) {
            RtScene.SectionMesh m = scene.sections.get(s);
            if (m.quads[0] + m.quads[1] > 0) {
                built.add(accel.buildBlas(List.of(
                        new RtAccel.Geometry(verts, firstVertex[s][0] * 16L, 16, m.quads[0], geomTransform(), true),
                        new RtAccel.Geometry(verts, firstVertex[s][1] * 16L, 16, m.quads[1], geomTransform(), false)), true));
                owner.add(s * 2);
            }
            if (m.quads[2] > 0) {
                built.add(accel.buildBlas(List.of(new RtAccel.Geometry(verts, firstVertex[s][2] * 16L, 16, m.quads[2], geomTransform(), false)), true));
                owner.add(s * 2 + 1);
            }
        }
        for (RtAccel.Accel a : built) {
            blasBytesBuilt += a.bytes;
            blasScratchMax = Math.max(blasScratchMax, a.scratchBytes);
        }
        boolean compact = !"false".equals(System.getProperty("rt.compact"));
        long[] sizes = new long[built.size()];
        if (compact) {
            long pool = accel.queryCompactedSize(built);
            sync();
            accel.releaseTransient();
            sizes = accel.readCompactedSizes(pool, built.size());
        }
        for (int i = 0; i < built.size(); i++) {
            RtAccel.Accel c = compact ? accel.compact(built.get(i), sizes[i]) : built.get(i);
            blasBytesCompacted += c.bytes;
            blas.add(c);
            int ci = owner.get(i);
            RtScene.SectionMesh m = scene.sections.get(ci / 2);
            instances.add(new RtAccel.Instance(m.sx * 16, m.sy * 16, m.sz * 16, ci,
                    (ci & 1) == 0 ? RtAccel.MASK_SOLID : RtAccel.MASK_TRANSLUCENT, c.address, false));
        }
        if (scene.entities != null)   // P5-3: one BLAS per rigid part model, built once (never per frame)
            for (int p = 0; p < RtEntities.PARTS; p++) {
                RtAccel.Accel a = accel.buildBlas(List.of(new RtAccel.Geometry(verts, firstVertexPart[p] * 16L, 16, scene.entities.quads[p], geomTransform(), true)), false);
                partBlas.add(a);
                partBlasBytes += a.bytes;
            }
        staticInstances.addAll(instances);
        accel.barrierBuildToBuild();
        tlas = accel.buildTlas(instances);
        tlasBytes = tlas.bytes;
        instanceCount = instances.size();
        sync();
        accel.releaseTransient();
    }

    long partBlasBytes;

    /**
     * P5-3: pose the entities for the next frame. Rebuilds the TLAS over the static section instances plus one instance per
     * posed part ({@code inTlas} false = the "before" case: entities only in the raster G-buffer, so they receive RT light
     * but cast no RT shadow and are absent from GI and reflections), and writes the pose buffer + draw commands the
     * G-buffer's entity draw reads. Timed as "TLAS rebuild (+N entity instances)".
     */
    void poseEntities(List<RtEntities.Inst> insts, boolean inTlas) {
        entityCount = insts.size();
        ByteBuffer pb = le(Math.max(1, entityCount) * 96), cb = le(Math.max(1, entityCount) * 20);
        List<RtAccel.Instance> all = new ArrayList<>(staticInstances);
        for (int i = 0; i < entityCount; i++) {
            RtEntities.Inst e = insts.get(i);
            for (float f : e.cur()) pb.putFloat(f);
            for (float f : e.prev()) pb.putFloat(f);
            cb.putInt(scene.entities.quads[e.part()] * 6).putInt(1).putInt(0).putInt(firstVertexPart[e.part()]).putInt(i);
            if (inTlas) all.add(new RtAccel.Instance(e.cur(), sectionCount * 2 + e.part(), e.mask(), partBlas.get(e.part()).address, true));
        }
        if (entityCount > 0) {
            up(poses, 0, pb.flip());
            up(entCmds, 0, cb.flip());
            uploadBarrier();
        }
        time("TLAS rebuild (static + entity instances)", () -> {
            RtAccel.Accel old = tlas;
            tlas = accel.buildTlas(all);
            accel.destroy(old);
        });
        accel.releaseTransient();
        instanceCount = all.size();
    }

    void createPipelines() {
        accel.quadIndices(totalQuads);
        uploadBarrier();
        sync();
        ByteBuffer vs = RtShaders.spirv("gbuffer.vert"), fs = RtShaders.spirv("gbuffer.frag");
        raster = dev.createDrawPipeline("rt-gbuffer", vs, fs, 5, 0, 0, VK_FORMAT_R32G32B32A32_UINT, VK_FORMAT_D32_SFLOAT,
                VK_COMPARE_OP_LESS, true, HeadlessGpuDevice.BLEND_NONE); // INT-4: api 4.1 blend is an int (RQ-P52-a)
        if (scene.entities != null) {
            ByteBuffer ve = RtShaders.spirv("gbuffer_ent.vert");
            rasterEnt = dev.createDrawPipeline("rt-gbuffer-entities", ve, fs, 5, 0, 0, VK_FORMAT_R32G32B32A32_UINT, VK_FORMAT_D32_SFLOAT,
                    VK_COMPARE_OP_LESS, true, HeadlessGpuDevice.BLEND_NONE); // INT-4: api 4.1 blend is an int (RQ-P52-a)
            MemoryUtil.memFree(ve);
        }
        MemoryUtil.memFree(vs);
        MemoryUtil.memFree(fs);
        pPrimary = pass("primary.comp", true, 9, 0);
        pSun = pass("sun.comp", true, 9, 0);
        pEmit = pass("emit.comp", true, 9, 0);
        pEmitUnit = pass("emit_unit.comp", true, 10, 8);
        pGi = pass("gi.comp", true, 10, 0);
        pResolve = pass("cache_resolve.comp", false, 2, 0);
        pRefl = pass("refl.comp", true, 9, 0);
        pAccum = pass("accum.comp", false, 6, 8);
        pCompose = pass("compose.comp", true, 11, 0);
        pSum = pass("sum.comp", false, 4, 4);
        pSunVis = pass("sunvis.comp", true, 9, 0);
        pComposeLook = pass("compose_look.comp", true, 12, 0);
        pPost = pass("post.comp", false, 3, 4);
        pRecon = pass("recon.comp", true, 10, 0);
        pRestir = pass("restir.comp", true, 15, 16);
    }

    RtCompute.Pass pass(String name, boolean traces, int buffers, int push) {
        ByteBuffer sp = RtShaders.spirv(name);
        RtCompute.Pass p = comp.create(name, sp, traces, buffers, push);
        MemoryUtil.memFree(sp);
        return p;
    }

    // ------------------------------------------------------------------------------------------------ frame

    /** Frame constants for camera {@code cam} (previous camera {@code prev}) and switch flags. */
    void setFrame(RtCamera cam, RtCamera prev, int frameIndex, int flags, RtReplay.Light light) {
        Matrix4f vp = cam.viewProj(), pvp = prev.viewProj();
        vp.get(fd.viewProj);
        pvp.get(fd.prevViewProj);
        float jx = jitterPx[0], jy = jitterPx[1];
        fd.jitter[0] = 2f * jx / W; fd.jitter[1] = 2f * jy / H; fd.jitter[2] = jx; fd.jitter[3] = jy;
        new Matrix4f().translation(fd.jitter[0], fd.jitter[1], 0).mul(vp).invert().get(fd.invViewProj);   // primary rays through the jittered sample
        Vector3f f = cam.forward(), pf = prev.forward();
        fd.camPos[0] = cam.pos.x; fd.camPos[1] = cam.pos.y; fd.camPos[2] = cam.pos.z;
        fd.camFwd[0] = f.x; fd.camFwd[1] = f.y; fd.camFwd[2] = f.z;
        fd.prevCamPos[0] = prev.pos.x; fd.prevCamPos[1] = prev.pos.y; fd.prevCamPos[2] = prev.pos.z;
        fd.prevCamFwd[0] = pf.x; fd.prevCamFwd[1] = pf.y; fd.prevCamFwd[2] = pf.z;
        System.arraycopy(light.sunDir, 0, fd.sunDir, 0, 3);
        System.arraycopy(light.sunE, 0, fd.sunE, 0, 3);
        System.arraycopy(light.zenith, 0, fd.skyZenith, 0, 3);
        System.arraycopy(light.horizon, 0, fd.skyHorizon, 0, 3);
        fd.sunAngularRadius = light.sunRadius;
        fd.fog = light.fog;
        fd.fogDensity = light.fogDensity;
        fd.fogFalloff = light.fogFalloff;
        fd.fogBase = light.fogBase;
        fd.width = W;
        fd.height = H;
        fd.frameIndex = frameIndex;
        fd.flags = flags;
        if (light.look != null) light.look.accept(fd);
        ByteBuffer b = le(RtFrameData.BYTES);
        fd.write(b).flip();
        up(frame, 0, b);
        uploadBarrier();
    }

    /** Raster G-buffer into gImage, copied to gbufCur (previous frame's moves to gbufPrev first). */
    void gbuffer() {
        VkBuf t = gbufPrev;
        gbufPrev = gbufCur;
        gbufCur = t;
        time("gbuffer (raster)", () -> {
            dev.beginRendering(gImage, dImage, new float[]{0, 0, 0, 0}, 1f, true);
            dev.drawIndexedIndirect(raster, new Object[]{verts, origins, atlas, mats, frame}, new Object[0], null,
                    accel.quadIndices(totalQuads), VK_INDEX_TYPE_UINT32, cmds, 0, sectionCount, 20);
            if (rasterEnt != null && entityCount > 0)
                dev.drawIndexedIndirect(rasterEnt, new Object[]{verts, poses, atlas, mats, frame}, new Object[0], null,
                        accel.quadIndices(totalQuads), VK_INDEX_TYPE_UINT32, entCmds, 0, entityCount, 20);
            dev.endRendering();
            dev.barrier(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT);
            dev.copyImageToBuffer(gImage, gbufCur);
            dev.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_READ_BIT);
        });
    }

    VkBuf[] scene(VkBuf... extra) {
        VkBuf[] b = new VkBuf[8 + extra.length];
        b[0] = frame;
        b[1] = verts;
        b[2] = inst;
        b[3] = atlas;
        b[4] = mats;
        b[5] = lights;
        b[6] = gbufCur;
        b[7] = counters;
        System.arraycopy(extra, 0, b, 8, extra.length);
        return b;
    }

    void trace(String name, RtCompute.Pass p, VkBuf... extra) {
        time(name, () -> {
            comp.dispatch(p, tlas.handle, scene(extra), null, (W + 7) / 8, (H + 7) / 8, 1);
            comp.barrier();
        });
    }

    void cacheResolve() {
        time("cache resolve", () -> {
            comp.dispatch(pResolve, 0, new VkBuf[]{frame, cache}, null, CACHE_ENTRIES / 64, 1, 1);
            comp.barrier();
        });
    }

    void sum(VkBuf a, VkBuf b, VkBuf c, VkBuf out) {
        ByteBuffer push = MemoryUtil.memAlloc(4).putInt(0, N);
        time("diffuse sum", () -> {
            comp.dispatch(pSum, 0, new VkBuf[]{a, b, c, out}, push, (N + 63) / 64, 1, 1);
            comp.barrier();
        });
        MemoryUtil.memFree(push);
    }

    /** Temporal accumulation history (ping-pong). */
    final class Accum {
        VkBuf a = buf(N * 16L), b = buf(N * 16L);

        void step(VkBuf signal, float maxHistory, boolean reset) {
            ByteBuffer push = MemoryUtil.memAlloc(8).order(ByteOrder.LITTLE_ENDIAN);
            push.putFloat(0, maxHistory).putInt(4, reset ? 1 : 0);
            time("temporal accumulate", () -> {
                comp.dispatch(pAccum, 0, new VkBuf[]{frame, gbufCur, gbufPrev, signal, a, b}, push, (W + 7) / 8, (H + 7) / 8, 1);
                comp.barrier();
            });
            MemoryUtil.memFree(push);
            VkBuf t = a;
            a = b;
            b = t;
        }

        VkBuf current() {
            return a;
        }
    }

    /** P5-1 chain composition: denoised diffuse radiance (modulated), sun visibility, specular -> HDR. */
    void composeLook(VkBuf diff, VkBuf vis, VkBuf spec, VkBuf out) {
        trace("compose (chain/look)", pComposeLook, diff, vis, spec, out);
    }

    /** P5-1 final stage on {@code count} pixels: tone map + saturation + owner's grade; params = 3 vec4 (see post.comp). */
    float[] post(float[] hdr, int count, float[] params) {
        VkBuf in = buf(count * 16L), out = buf(count * 16L), g = buf(48);
        ByteBuffer x = le(count * 16);
        x.asFloatBuffer().put(hdr, 0, count * 4);
        up(in, 0, x);
        ByteBuffer gp = le(48);
        gp.asFloatBuffer().put(params);
        up(g, 0, gp);
        uploadBarrier();
        ByteBuffer push = MemoryUtil.memAlloc(4).putInt(0, count);
        time("post (tonemap + grade)", () -> {
            comp.dispatch(pPost, 0, new VkBuf[]{in, out, g}, push, (count + 63) / 64, 1, 1);
            comp.barrier();
        });
        MemoryUtil.memFree(push);
        float[] r = readVec4(out, count);
        for (VkBuf b : new VkBuf[]{in, out, g}) dev.destroyBuffer(b);
        return r;
    }

    void writeVec4(VkBuf b, float[] f, int count) {
        ByteBuffer x = le(count * 16);
        x.asFloatBuffer().put(f, 0, count * 4);
        up(b, 0, x);
        uploadBarrier();
    }

    void compose(VkBuf diff, VkBuf spec, VkBuf out) {
        trace("compose + fog", pCompose, diff, spec, out);
    }

    int[] readCounters() {
        ByteBuffer r = dev.readBuffer(counters, 0, 64);
        comp.resetSets();
        int[] c = new int[16];
        r.asIntBuffer().get(c);
        return c;
    }

    void resetCounters() {
        up(counters, 0, le(64));
        uploadBarrier();
    }

    @Override
    public void close() {
        sync();
        for (RtAccel.Accel a : blas) accel.destroy(a);
        for (RtAccel.Accel a : partBlas) accel.destroy(a);
        if (rasterEnt != null) dev.destroyPipeline(rasterEnt);
        if (tlas != null) accel.destroy(tlas);
        accel.close();
        for (RtCompute.Pass p : new RtCompute.Pass[]{pRestir, pEmitUnit, pPrimary, pSun, pEmit, pGi, pResolve, pRefl, pAccum, pCompose, pSum, pSunVis, pComposeLook, pPost, pRecon})
            if (p != null) comp.destroy(p);
        comp.close();
        dev.destroyPipeline(raster);
        dev.close();
    }
}
