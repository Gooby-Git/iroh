package dev.elemore.iroh.rttest;

import dev.elemore.iroh.gpu.VkGpuDevice.VkBuf;
import dev.elemore.iroh.rt.denoise.*;
import dev.elemore.iroh.upscale.ImageRef;
import dev.elemore.iroh.upscale.JitterSequence;
import dev.elemore.iroh.upscale.UpscaleInputs;
import dev.elemore.iroh.upscale.fsr.FsrUpscaler;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import static dev.elemore.iroh.rt.RtFrameData.*;
import static dev.elemore.iroh.rt.denoise.Slot.*;
import static dev.elemore.iroh.rttest.RtReplay.check;
import static dev.elemore.iroh.rttest.RtReplay.f;
import static dev.elemore.iroh.rttest.RtReplay.metric;
import static org.lwjgl.vulkan.VK12.*;

/**
 * P5-1 full offline chain on lavapipe: jittered raster G-buffer -> RT passes at RENDER resolution (sun visibility, block
 * light, GI + radiance cache, reflections) -> Iroh's own SvgfDenoiser (diffuse demodulated through IN_ALBEDO; specular;
 * shadow = sun visibility) -> composition -> FSR 3.1 to DISPLAY resolution -> tone map + grade (post.comp). ONE jitter
 * sequence (FSR's) drives the G-buffer, the denoiser and FSR; ONE motion-vector conversion ({@link #pack}): the G-buffer's
 * unjittered pixel motion (previous minus current) becomes UV units, which the denoiser reads as IN_MV and FSR reads with
 * motionVectorScale = render size. Two devices (the RT device and the denoise/FSR device), joined through host copies:
 * a harness arrangement only (in game it is one device and no copies).
 */
final class RtChain implements AutoCloseable {
    final int RW, RH, DW, DH, N, ND;
    final RtGpu gpu;
    final LvpDevice lvp;
    final RtReplay.Light light;
    final JitterSequence js;
    final float fovY;
    final VkBuf bSunVis, bEmit, bDiff, bVis, bSpec, bOut;
    VkBuf bGi, bRefl, bTmpG, bTmpR;
    static final float NEAR = 0.1f, FAR = 1000f, SKY_Z = 1e5f;
    final Map<String, double[]> cost = new LinkedHashMap<>();   // stage -> {nanos, calls}
    /** Look mode: the G-buffer's albedo bytes are sRGB; decode them for the denoiser's IN_ALBEDO and the modulation. */
    boolean srgbAlbedo;

    RtChain(RtScene scene, RtReplay.Light light, int rw, int rh, int dw, int dh, float fovY) {
        this.RW = rw;
        this.RH = rh;
        this.DW = dw;
        this.DH = dh;
        this.N = rw * rh;
        this.ND = dw * dh;
        this.light = light;
        this.fovY = fovY;
        gpu = new RtGpu(scene, rw, rh);
        lvp = new LvpDevice(false);
        js = new JitterSequence(rw, dw);
        bSunVis = gpu.buf(N * 16L);
        bEmit = gpu.buf(N * 16L);
        bGi = gpu.buf(N * 16L);
        bRefl = gpu.buf(N * 16L);
        bTmpG = gpu.buf(N * 16L);
        bTmpR = gpu.buf(N * 16L);
        bDiff = gpu.buf(N * 16L);
        bVis = gpu.buf(N * 16L);
        bSpec = gpu.buf(N * 16L);
        bOut = gpu.buf(N * 16L);
    }

    void time(String stage, Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        double[] c = cost.computeIfAbsent(stage, k -> new double[2]);
        c[0] += System.nanoTime() - t0;
        c[1]++;
    }

    // ------------------------------------------------------------------------------------------------ RT at render res

    static final class Rt {
        int[] g;
        float[] vis, emit, gi, refl;
        float[] jitter;
    }

    /** RT passes for one frame. {@code seed} = RNG frame index; {@code jitterIndex} < 0 = no jitter. */
    Rt trace(RtCamera c, RtCamera p, int seed, int jitterIndex, int flags) {
        Rt r = new Rt();
        r.jitter = jitterIndex < 0 ? new float[]{0, 0} : js.offset(jitterIndex);
        gpu.jitterPx = r.jitter;
        time("RT passes (G-buffer, sun vis, light, GI+cache, refl)", () -> {
            gpu.setFrame(c, p, seed, flags, light);
            gpu.gbuffer();
            gpu.trace("sun visibility", gpu.pSunVis, bSunVis);
            if ((flags & (F_RESTIR | F_LIGHT_GRID)) != 0) gpu.restir(bEmit);   // P5-3: light grid + ReSTIR DI
            else gpu.trace("block light", gpu.pEmit, bEmit);
            gpu.trace("GI 1 bounce + cache", gpu.pGi, gpu.cache, bGi);
            if ((flags & F_CACHE) != 0) gpu.cacheResolve();
            if ((flags & F_HALF_GI) != 0) { gpu.trace("checkerboard recon", gpu.pRecon, bGi, bTmpG); VkBuf t = bGi; bGi = bTmpG; bTmpG = t; }
            gpu.trace("reflections", gpu.pRefl, bRefl);
            if ((flags & F_HALF_REFL) != 0) { gpu.trace("checkerboard recon", gpu.pRecon, bRefl, bTmpR); VkBuf t = bRefl; bRefl = bTmpR; bTmpR = t; }
        });
        r.g = gpu.readUvec4(gpu.gbufCur);
        r.vis = gpu.readVec4(bSunVis);
        r.emit = gpu.readVec4(bEmit);
        r.gi = gpu.readVec4(bGi);
        r.refl = gpu.readVec4(bRefl);
        gpu.jitterPx = new float[]{0, 0};
        return r;
    }

    // ------------------------------------------------------------------------------------------------ denoiser inputs

    static final class In {
        float[] mvUv, viewZ, albedo, diff, spec, pen, visRaw;
        int[] nrm;
        boolean[] valid;
    }

    static float half(int bits) {
        return Float.float16ToFloat((short) bits);
    }

    /**
     * Denoiser/FSR inputs from the RT outputs. THE one place where the G-buffer's motion (pixels, previous minus current,
     * unjittered) is converted to the shared convention (UV units, previous minus current): denoiser IN_MV as is, FSR
     * with motionVectorScale = (renderW, renderH).
     */
    In pack(Rt r) {
        In in = new In();
        in.mvUv = new float[N * 2];
        in.viewZ = new float[N];
        in.albedo = new float[N * 3];
        in.diff = new float[N * 4];
        in.spec = new float[N * 4];
        in.pen = new float[N];
        in.visRaw = new float[N];
        in.nrm = new int[N];
        in.valid = new boolean[N];
        RtScene.Material[] mats = gpu.scene.materials;
        for (int i = 0; i < N; i++) {
            int[] g = r.g;
            float vz = Float.intBitsToFloat(g[i * 4 + 2]);
            boolean valid = vz > 0;
            in.valid[i] = valid;
            int mv = g[i * 4 + 3];
            in.mvUv[i * 2] = half(mv & 0xFFFF) / RW;
            in.mvUv[i * 2 + 1] = half(mv >>> 16) / RH;
            in.viewZ[i] = valid ? vz : SKY_Z;
            int al = g[i * 4 + 1];
            float[] a = new float[3];
            for (int k = 0; k < 3; k++) {
                float c = valid ? ((al >>> (8 * k)) & 0xFF) / 255f : 1f;
                a[k] = srgbAlbedo && valid ? (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)) : c;
            }
            System.arraycopy(a, 0, in.albedo, i * 3, 3);
            int tile = (al >>> 24) & 63;
            RtScene.Material m = mats[tile];
            float rough = !valid ? 1 : m.water() ? 0.02f : m.spec() ? m.roughness() : 1f;
            int nn = g[i * 4];
            double ex = (nn & 0xFFFF) / 65535.0 * 2 - 1, ey = (nn >>> 16) / 65535.0 * 2 - 1, ez = 1 - Math.abs(ex) - Math.abs(ey);
            if (ez < 0) { double ox = ex; ex = (1 - Math.abs(ey)) * (ox >= 0 ? 1 : -1); ey = (1 - Math.abs(ox)) * (ey >= 0 ? 1 : -1); }
            double l = Math.sqrt(ex * ex + ey * ey + ez * ez);
            in.nrm[i] = valid ? NrdScene.packNormalRoughness((float) (ex / l), (float) (ey / l), (float) (ez / l), rough, 0) : NrdScene.packNormalRoughness(0, 1, 0, 1, 0);
            boolean water = valid && m.water();
            for (int k = 0; k < 3; k++) in.diff[i * 4 + k] = valid && !water ? (float) (a[k] / Math.PI * (r.emit[i * 4 + k] + r.gi[i * 4 + k])) : 0f;
            in.diff[i * 4 + 3] = r.gi[i * 4 + 3];
            for (int k = 0; k < 4; k++) in.spec[i * 4 + k] = r.refl[i * 4 + k];
            in.pen[i] = r.vis[i * 4];
            in.visRaw[i] = r.vis[i * 4 + 1];
        }
        return in;
    }

    // ------------------------------------------------------------------------------------------------ own denoiser (3 channels)

    final class Den implements AutoCloseable {
        final SvgfDenoiser diffuse, specShadow;   // IN_ALBEDO only for diffuse: SvgfDenoiser would demodulate specular too
        final DenoiseGpu.Img mv, z, n, alb, iDiff, iSpec, iPen, oDiff, oSpec, oShadow;
        final LvpDevice.Buf bMv, bZ, bN, bAlb, bDiffI, bSpecI, bPen, bOD, bOS, bOSh;
        boolean first = true;
        final int G = ImageRef.LAYOUT_GENERAL, u = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, H16 = VK_FORMAT_R16G16B16A16_SFLOAT, NF = VK_FORMAT_A2B10G10R10_UNORM_PACK32;

        Den() {
            diffuse = new SvgfDenoiser(lvp, new DenoiserType[]{DenoiserType.IROH_DIFFUSE}, RW, RH);
            specShadow = new SvgfDenoiser(lvp, new DenoiserType[]{DenoiserType.IROH_SPECULAR, DenoiserType.IROH_SHADOW}, RW, RH);
            mv = lvp.createImage(VK_FORMAT_R16G16_SFLOAT, RW, RH, u, "mv");
            z = lvp.createImage(VK_FORMAT_R32_SFLOAT, RW, RH, u, "viewZ");
            n = lvp.createImage(NF, RW, RH, u, "normal");
            alb = lvp.createImage(H16, RW, RH, u, "albedo");
            iDiff = lvp.createImage(H16, RW, RH, u, "diff in");
            iSpec = lvp.createImage(H16, RW, RH, u, "spec in");
            iPen = lvp.createImage(H16, RW, RH, u, "penumbra in");
            oDiff = lvp.createImage(H16, RW, RH, u, "diff out");
            oSpec = lvp.createImage(H16, RW, RH, u, "spec out");
            oShadow = lvp.createImage(H16, RW, RH, u, "shadow out");
            bMv = lvp.hostBuffer(N * 4L, 3);
            bZ = lvp.hostBuffer(N * 4L, 3);
            bN = lvp.hostBuffer(N * 4L, 3);
            bAlb = lvp.hostBuffer(N * 8L, 3);
            bDiffI = lvp.hostBuffer(N * 8L, 3);
            bSpecI = lvp.hostBuffer(N * 8L, 3);
            bPen = lvp.hostBuffer(N * 8L, 3);
            bOD = lvp.hostBuffer(N * 8L, 3);
            bOS = lvp.hostBuffer(N * 8L, 3);
            bOSh = lvp.hostBuffer(N * 8L, 3);
        }

        ImageRef ref(DenoiseGpu.Img i, int fmt) {
            return new ImageRef(i.image(), fmt, RW, RH, u, G);
        }

        /** Returns {diffuse radiance rgb + history (N*4), specular rgb + history (N*4), visibility (N*4, r)}. */
        float[][] run(In in, int frame, boolean reset, RtCamera c, RtCamera p, float[] j, float[] jp) {
            ByteBuffer m = LvpDevice.bytes(bMv), zz = LvpDevice.bytes(bZ), nn = LvpDevice.bytes(bN), aa = LvpDevice.bytes(bAlb), dd = LvpDevice.bytes(bDiffI), ss = LvpDevice.bytes(bSpecI), pp = LvpDevice.bytes(bPen);
            for (int i = 0; i < N; i++) {
                m.putShort(i * 4, Float.floatToFloat16(in.mvUv[i * 2])).putShort(i * 4 + 2, Float.floatToFloat16(in.mvUv[i * 2 + 1]));
                zz.putFloat(i * 4, in.viewZ[i]);
                nn.putInt(i * 4, in.nrm[i]);
                for (int k = 0; k < 3; k++) {
                    aa.putShort(i * 8 + k * 2, Float.floatToFloat16(in.albedo[i * 3 + k]));
                    // IN_ALBEDO contract: the noisy diffuse input is MODULATED (albedo x irradiance / pi); divided inside
                    dd.putShort(i * 8 + k * 2, Float.floatToFloat16(Math.min(in.diff[i * 4 + k], 60000f)));
                    ss.putShort(i * 8 + k * 2, Float.floatToFloat16(Math.min(in.spec[i * 4 + k], 60000f)));
                    pp.putShort(i * 8 + k * 2, Float.floatToFloat16(in.pen[i]));
                }
                aa.putShort(i * 8 + 6, Float.floatToFloat16(1f));
                dd.putShort(i * 8 + 6, Float.floatToFloat16(Math.min(in.diff[i * 4 + 3], 60000f)));
                ss.putShort(i * 8 + 6, Float.floatToFloat16(Math.min(in.spec[i * 4 + 3], 60000f)));
                pp.putShort(i * 8 + 6, Float.floatToFloat16(1f));
            }
            lvp.begin();
            if (first) for (DenoiseGpu.Img o : new DenoiseGpu.Img[]{oDiff, oSpec, oShadow}) lvp.layout(o.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
            first = false;
            lvp.upload(mv.image(), RW, RH, bMv);
            lvp.upload(z.image(), RW, RH, bZ);
            lvp.upload(n.image(), RW, RH, bN);
            lvp.upload(alb.image(), RW, RH, bAlb);
            lvp.upload(iDiff.image(), RW, RH, bDiffI);
            lvp.upload(iSpec.image(), RW, RH, bSpecI);
            lvp.upload(iPen.image(), RW, RH, bPen);
            FrameSettings fs = settings(frame, reset, c, p, j, jp);
            DenoiseFrame d1 = frame(fs);
            d1.images.put(IN_ALBEDO, ref(alb, H16));
            d1.images.put(IN_DIFF_RADIANCE_HITDIST, ref(iDiff, H16));
            d1.images.put(OUT_DIFF_RADIANCE_HITDIST, ref(oDiff, H16));
            d1.identifiers = new int[]{0};
            diffuse.denoise(d1);
            DenoiseFrame d2 = frame(fs);
            d2.images.put(IN_SPEC_RADIANCE_HITDIST, ref(iSpec, H16));
            d2.images.put(OUT_SPEC_RADIANCE_HITDIST, ref(oSpec, H16));
            d2.images.put(IN_PENUMBRA, ref(iPen, H16));
            d2.images.put(OUT_SHADOW_TRANSLUCENCY, ref(oShadow, H16));
            d2.identifiers = new int[]{0, 1};
            specShadow.denoise(d2);
            lvp.download(oDiff.image(), RW, RH, bOD);
            lvp.download(oSpec.image(), RW, RH, bOS);
            lvp.download(oShadow.image(), RW, RH, bOSh);
            lvp.submitWait();
            return new float[][]{rgba16(bOD), rgba16(bOS), rgba16(bOSh)};
        }

        DenoiseFrame frame(FrameSettings fs) {
            DenoiseFrame d = new DenoiseFrame();
            d.commandBuffer = lvp.cmd.address();
            d.settings = fs;
            d.images.put(IN_MV, new ImageRef(mv.image(), VK_FORMAT_R16G16_SFLOAT, RW, RH, u, G));
            d.images.put(IN_VIEWZ, new ImageRef(z.image(), VK_FORMAT_R32_SFLOAT, RW, RH, u, G));
            d.images.put(IN_NORMAL_ROUGHNESS, ref(n, NF));
            return d;
        }

        @Override
        public void close() {
            diffuse.close();
            specShadow.close();
            for (DenoiseGpu.Img i : new DenoiseGpu.Img[]{mv, z, n, alb, iDiff, iSpec, iPen, oDiff, oSpec, oShadow}) lvp.destroyImage(i);
            for (LvpDevice.Buf b : new LvpDevice.Buf[]{bMv, bZ, bN, bAlb, bDiffI, bSpecI, bPen, bOD, bOS, bOSh}) lvp.destroyBuffer(b);
        }
    }

    float[] rgba16(LvpDevice.Buf b) {
        return rgba16(b, N);
    }

    double box3(float[] a, int x, int y) {
        double s = 0;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) s += RtImg.lum(a, Math.max(0, Math.min(RH - 1, y + dy)) * RW + Math.max(0, Math.min(RW - 1, x + dx)));
        return s / 9;
    }

    static float[] rgba16(LvpDevice.Buf b, int count) {
        ByteBuffer o = LvpDevice.bytes(b);
        float[] f = new float[count * 4];
        for (int i = 0; i < count * 4; i++) f[i] = Float.float16ToFloat(o.getShort(i * 2));
        return f;
    }

    FrameSettings settings(int frame, boolean reset, RtCamera c, RtCamera p, float[] j, float[] jp) {
        FrameSettings fs = new FrameSettings();
        Matrix4f proj = new Matrix4f().scale(1, -1, 1).perspective((float) Math.toRadians(c.fovYDeg), RW / (float) RH, NEAR, FAR, true);
        proj.get(fs.viewToClip);
        proj.get(fs.viewToClipPrev);
        new Matrix4f().lookAt(c.pos, c.target, new Vector3f(0, 1, 0)).get(fs.worldToView);
        new Matrix4f().lookAt(p.pos, p.target, new Vector3f(0, 1, 0)).get(fs.worldToViewPrev);
        fs.resourceSize = fs.resourceSizePrev = fs.rectSize = fs.rectSizePrev = new int[]{RW, RH};
        fs.jitter = new float[]{-j[0], -j[1]};            // denoiser: sampleUv = pixelUv + jitter; content shifts by +jitter
        fs.jitterPrev = new float[]{-jp[0], -jp[1]};
        fs.frameIndex = frame;
        fs.timeDeltaMs = 16.6f;
        fs.denoisingRange = 5e4f;
        fs.reset = reset;
        return fs;
    }

    // ------------------------------------------------------------------------------------------------ compose

    /** HDR at render res from denoised (or noisy) signals: diff rgb (modulated), visibility in vis[i*4], spec rgb. */
    float[] compose(float[] diff, float[] vis, int visStride, float[] spec) {
        float[] v = new float[N * 4];
        for (int i = 0; i < N; i++) v[i * 4] = vis[i * visStride];
        gpu.writeVec4(bDiff, diff, N);
        gpu.writeVec4(bVis, v, N);
        gpu.writeVec4(bSpec, spec, N);
        time("compose", () -> gpu.composeLook(bDiff, bVis, bSpec, bOut));
        return gpu.readVec4(bOut);
    }

    // ------------------------------------------------------------------------------------------------ FSR 3.1

    final class Fsr implements AutoCloseable {
        final FsrUpscaler up;
        final DenoiseGpu.Img col, dep, mv, out, react;
        final LvpDevice.Buf bCol, bDep, bMv, bOut, bReact;
        /** P5-1: feed FSR a reactive mask from the denoiser's history length (off = FSR keeps its own history). */
        boolean reactive = true;
        float[] prevHdr;
        final int u = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, H16 = VK_FORMAT_R16G16B16A16_SFLOAT;
        int frames;

        Fsr() {
            up = FsrUpscaler.create(lvp.instance.address(), lvp.phys.address(), lvp.device.address(), 0L, FsrUpscaler.HDR | FsrUpscaler.AUTO_EXPOSURE, FsrUpscaler.NO_FP16, RW, RH, DW, DH);
            col = lvp.createImage(H16, RW, RH, u, "fsr colour");
            dep = lvp.createImage(VK_FORMAT_R32_SFLOAT, RW, RH, u, "fsr depth");
            mv = lvp.createImage(VK_FORMAT_R16G16_SFLOAT, RW, RH, u, "fsr mv");
            out = lvp.createImage(H16, DW, DH, u | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, "fsr out");
            react = lvp.createImage(VK_FORMAT_R8_UNORM, RW, RH, u, "fsr reactive");
            bReact = lvp.hostBuffer(Math.max(16, N), 3);
            bCol = lvp.hostBuffer(N * 8L, 3);
            bDep = lvp.hostBuffer(N * 4L, 3);
            bMv = lvp.hostBuffer(N * 4L, 3);
            bOut = lvp.hostBuffer(ND * 8L, 3);
        }

        /**
         * {@code history} = the denoiser's diffuse output (alpha = history length) or null. Reactive mask: pixels whose
         * denoiser history was just reset (disocclusion, or the denoiser's lag guard after a lighting change) tell FSR to
         * trust the current frame: r = 0.9 at history 1, falling to 0 at history 5. Without it FSR's own history kept a
         * switched-on light at 71-85% for 12+ frames (rtReplay step (i), measured).
         */
        float[] run(float[] hdr, In in, float[] j, boolean reset, float[] history) {
            ByteBuffer cb = LvpDevice.bytes(bCol), db = LvpDevice.bytes(bDep), mb = LvpDevice.bytes(bMv), rb = LvpDevice.bytes(bReact);
            boolean useReact = reactive && history != null;
            if (useReact) for (int i = 0; i < N; i++) {
                float h = history[i * 4 + 3];
                float r = 0f;   // history-length term tried and dropped: it cost texture detail in motion (0.36 -> 0.29) for no lag gain; FSR handles disocclusion from depth + MVs
                // shading change not carried by the motion vectors (a light switched on/off): the denoised colour moved
                // by more than 10-40% against the previous frame's colour at the reprojected position
                if (prevHdr != null && in.valid[i]) {
                    int x = i % RW, y = i / RW;
                    int px = Math.round(x + in.mvUv[i * 2] * RW), py = Math.round(y + in.mvUv[i * 2 + 1] * RH);
                    if (px >= 0 && py >= 0 && px < RW && py < RH) {
                        double lc = box3(hdr, x, y), lp = box3(prevHdr, px, py);   // 3x3 means: jitter and pixel-art texels are not shading changes
                        double rel = Math.abs(lc - lp) / Math.max(Math.max(lc, lp), 0.02);
                        double t = Math.max(0, Math.min(1, (rel - 0.25) / 0.35));
                        r = Math.max(r, (float) (0.9 * t * t * (3 - 2 * t)));
                    }
                }
                rb.put(i, (byte) Math.round(r * 255f));
            }
            prevHdr = hdr.clone();
            for (int i = 0; i < N; i++) {
                for (int k = 0; k < 3; k++) cb.putShort(i * 8 + k * 2, Float.floatToFloat16(Math.min(Math.max(hdr[i * 4 + k], 0f), 60000f)));
                cb.putShort(i * 8 + 6, Float.floatToFloat16(1f));
                float zv = Math.min(in.viewZ[i], 99999f);
                db.putFloat(i * 4, FAR / (FAR - NEAR) - NEAR * FAR / ((FAR - NEAR) * zv));
                mb.putShort(i * 4, Float.floatToFloat16(in.mvUv[i * 2])).putShort(i * 4 + 2, Float.floatToFloat16(in.mvUv[i * 2 + 1]));
            }
            lvp.begin();
            lvp.upload(col.image(), RW, RH, bCol);
            lvp.upload(dep.image(), RW, RH, bDep);
            lvp.upload(mv.image(), RW, RH, bMv);
            if (useReact) lvp.upload(react.image(), RW, RH, bReact);
            if (frames == 0) lvp.layout(out.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
            for (DenoiseGpu.Img i : useReact ? new DenoiseGpu.Img[]{col, dep, mv, react} : new DenoiseGpu.Img[]{col, dep, mv}) lvp.layout(i.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            int RO = ImageRef.LAYOUT_SHADER_READ_ONLY;
            UpscaleInputs x = new UpscaleInputs();
            x.commandBuffer = lvp.cmd.address();
            x.color = new ImageRef(col.image(), H16, RW, RH, u, RO);
            x.depth = new ImageRef(dep.image(), VK_FORMAT_R32_SFLOAT, RW, RH, u, RO);
            x.motionVectors = new ImageRef(mv.image(), VK_FORMAT_R16G16_SFLOAT, RW, RH, u, RO);
            if (useReact) x.reactive = new ImageRef(react.image(), VK_FORMAT_R8_UNORM, RW, RH, u, RO);
            x.output = new ImageRef(out.image(), H16, DW, DH, u | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, ImageRef.LAYOUT_GENERAL);
            x.jitterX = j[0];
            x.jitterY = j[1];
            x.motionVectorScaleX = RW;   // UV-unit vectors (the shared convention) -> render pixels
            x.motionVectorScaleY = RH;
            x.renderWidth = RW; x.renderHeight = RH; x.displayWidth = DW; x.displayHeight = DH;
            x.frameTimeMs = 16.6f; x.cameraNear = NEAR; x.cameraFar = FAR; x.cameraFovYRadians = (float) Math.toRadians(fovY);
            x.reset = reset || frames == 0;
            up.dispatch(x);
            lvp.download(out.image(), DW, DH, bOut);
            lvp.submitWait();
            frames++;
            return rgba16(bOut, ND);
        }

        @Override
        public void close() {
            up.close();
            for (DenoiseGpu.Img i : new DenoiseGpu.Img[]{col, dep, mv, out, react}) lvp.destroyImage(i);
            for (LvpDevice.Buf b : new LvpDevice.Buf[]{bCol, bDep, bMv, bOut, bReact}) lvp.destroyBuffer(b);
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    static float[] bilinear(float[] src, int sw, int sh, int dw, int dh) {
        float[] o = new float[dw * dh * 4];
        for (int y = 0; y < dh; y++)
            for (int x = 0; x < dw; x++) {
                float fx = (x + 0.5f) * sw / dw - 0.5f, fy = (y + 0.5f) * sh / dh - 0.5f;
                int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
                float ax = fx - x0, ay = fy - y0;
                for (int k = 0; k < 4; k++) {
                    float a = px(src, sw, sh, x0, y0, k), b = px(src, sw, sh, x0 + 1, y0, k), c = px(src, sw, sh, x0, y0 + 1, k), d = px(src, sw, sh, x0 + 1, y0 + 1, k);
                    o[(y * dw + x) * 4 + k] = (a * (1 - ax) + b * ax) * (1 - ay) + (c * (1 - ax) + d * ax) * ay;
                }
            }
        return o;
    }

    static float px(float[] s, int w, int h, int x, int y, int k) {
        x = Math.max(0, Math.min(w - 1, x));
        y = Math.max(0, Math.min(h - 1, y));
        return s[(y * w + x) * 4 + k];
    }

    static double tm(double x) {
        return x / (1 + x);
    }

    static double tml(float[] a, int i) {
        return tm(Math.max(0, RtImg.lum(a, i)));
    }

    /** {relMAE linear, bias, tonemapped RMSE, n}. */
    static double[] score(float[] a, float[] ref, boolean[] m) {
        double[] e = RtImg.err(a, ref, m);
        double s = 0;
        int n = 0;
        for (int i = 0; i < m.length; i++)
            if (m[i]) {
                double d = tml(a, i) - tml(ref, i);
                s += d * d;
                n++;
            }
        return new double[]{e[0], e[1], Math.sqrt(s / Math.max(1, n)), n};
    }

    static double detail(float[] a, float[] ref, boolean[] m, int w, int h) {
        double sab = 0, saa = 0, sbb = 0;
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                if (!m[i]) continue;
                double ha = tml(a, i), hb = tml(ref, i);
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) { ha -= tml(a, i + dy * w + dx) / 9; hb -= tml(ref, i + dy * w + dx) / 9; }
                sab += ha * hb;
                saa += ha * ha;
                sbb += hb * hb;
            }
        return sab / Math.sqrt(saa * sbb + 1e-12);
    }

    static boolean finite(float[] a) {
        for (float v : a) if (!Float.isFinite(v)) return false;
        return true;
    }

    static float[] cachedRef(String key, Supplier<float[]> s) {
        Path p = RtReplay.OUT.resolve("cpuref").resolve(key + ".bin");
        try {
            if (Files.exists(p)) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(p)))) {
                    float[] v = new float[in.readInt()];
                    for (int i = 0; i < v.length; i++) v[i] = in.readFloat();
                    return v;
                }
            }
            long t0 = System.nanoTime();
            float[] v = s.get();
            metric(String.format(Locale.ROOT, "cpu reference %s: %.1f s", key, (System.nanoTime() - t0) / 1e9));
            Files.createDirectories(p.getParent());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)))) {
                out.writeInt(v.length);
                for (float x : v) out.writeFloat(x);
            }
            return v;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** CPU reference composite (HDR, RGBA) for a camera at w x h. */
    static float[] reference(RtScene scene, RtReplay.Light light, RtCamera c, String tag, int sunSpp, int emitSpp, int indSpp, int specSpp) {
        String key = "chain-" + tag + "-" + c.w + "x" + c.h + "-v1-" + Integer.toHexString(Float.floatToIntBits(c.pos.x * 31 + c.pos.z) ^ Arrays.hashCode(light.sunDir))
                + "-" + sunSpp + "-" + emitSpp + "-" + indSpp + "-" + specSpp;
        return cachedRef(key, () -> {
            CpuRef r = new CpuRef(scene, light, c);
            CpuRef.Surf[] p = r.primary();
            float[] s = r.sun(p, true, sunSpp), e = r.emit(p, emitSpp), in = r.indirect(p, indSpp), sp = r.spec(p, specSpp);
            float[] d = new float[s.length];
            for (int i = 0; i < d.length; i++) d[i] = s[i] + e[i] + in[i];
            return r.compose(p, d, sp);
        });
    }

    float[] postNeutral(float[] hdr, int count) {
        return gpu.post(hdr, count, neutralGrade());
    }

    static float[] neutralGrade() {
        return new float[]{0, 0, 0, 0, 1.0f, 1.0f, 0f, 1f, 1.15f, 0.97f, 16f, 0.20f};
    }

    @Override
    public void close() {
        gpu.close();
        lvp.close();
    }
}
