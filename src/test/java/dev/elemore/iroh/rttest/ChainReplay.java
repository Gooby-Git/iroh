package dev.elemore.iroh.rttest;

import dev.elemore.iroh.rt.denoise.*;
import dev.elemore.iroh.rt.denoise.nrd.*;
import dev.elemore.iroh.upscale.*;
import dev.elemore.iroh.upscale.fsr.FsrUpscaler;
import dev.elemore.iroh.upscale.fsr.NativeLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;

import static dev.elemore.iroh.rt.denoise.Slot.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * P6-4 shared chain on lavapipe (CPU Vulkan only): noisy 1 spp path-traced-like Minecraft scene at RENDER resolution (320x180, with
 * the FSR jitter sequence) -> denoiser (own SVGF-family; NRD RELAX when built) -> modulate by albedo -> FSR 3.1 (libiroh_fsr)
 * -> DISPLAY resolution (640x360), with ONE jitter sequence and ONE motion-vector buffer (NRD UV units previous-minus-current;
 * FSR via motionVectorScale = render size). Compared with a 256 spp display-resolution reference. Last line "# chain PASS|FAIL".
 */
public final class ChainReplay {
    static final int RW = NrdScene.W, RH = NrdScene.H, DW = 640, DH = 360, NF = 32, LT = NF - 1;
    static int fails;
    static LvpDevice dev;
    static String dump = "build/chainreplay";
    static float mvSign = 1f;

    static void ck(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fails++;
    }

    static float lum(float r, float g, float b) { return 0.2126f * r + 0.7152f * g + 0.0722f * b; }
    static float tm(float x) { return x / (1 + x); }
    static int c8(float v) { return Math.max(0, Math.min(255, Math.round(v * 255f))); }

    static void png(float[] rgb, int w, int h, String name) throws Exception {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < w * h; p++) im.setRGB(p % w, p / w, (c8(tm(rgb[p * 3])) << 16) | (c8(tm(rgb[p * 3 + 1])) << 8) | c8(tm(rgb[p * 3 + 2])));
        new File(dump).mkdirs();
        ImageIO.write(im, "png", new File(dump, name));
    }

    interface Make { Denoiser make(); }

    /** denoiser == null: feed the noisy modulated frame straight to FSR. fsr == false: return the render-resolution final colour per frame (no upscaler). */
    static float[] run(String name, Make make, McScene.Frame[] fr, boolean fsr, float[][] renderFinalOut) {
        int G = ImageRef.LAYOUT_GENERAL, RO = ImageRef.LAYOUT_SHADER_READ_ONLY, u = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, fmtH = VK_FORMAT_R16G16B16A16_SFLOAT, fmtN = VK_FORMAT_A2B10G10R10_UNORM_PACK32;
        DenoiseGpu.Img mv = dev.createImage(VK_FORMAT_R16G16_SFLOAT, RW, RH, u, "mv"), z = dev.createImage(VK_FORMAT_R32_SFLOAT, RW, RH, u, "z"), n = dev.createImage(fmtN, RW, RH, u, "n"),
                no = dev.createImage(fmtH, RW, RH, u, "noisy"), out = dev.createImage(fmtH, RW, RH, u, "dout"), col = dev.createImage(fmtH, RW, RH, u, "color"),
                dep = dev.createImage(VK_FORMAT_R32_SFLOAT, RW, RH, u, "depth"), fo = dev.createImage(fmtH, DW, DH, u | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, "fsr out");
        LvpDevice.Buf bMv = dev.hostBuffer(RW * RH * 4, 3), bZ = dev.hostBuffer(RW * RH * 4, 3), bN = dev.hostBuffer(RW * RH * 4, 3), bNo = dev.hostBuffer(RW * RH * 8, 3), bOut = dev.hostBuffer(RW * RH * 8, 3),
                bCol = dev.hostBuffer(RW * RH * 8, 3), bDep = dev.hostBuffer(RW * RH * 4, 3), bFo = dev.hostBuffer(DW * DH * 8, 3);
        Denoiser den = make == null ? null : make.make();
        FsrUpscaler up = fsr ? FsrUpscaler.create(dev.instance.address(), dev.phys.address(), dev.device.address(), 0L, FsrUpscaler.HDR | FsrUpscaler.AUTO_EXPOSURE, FsrUpscaler.NO_FP16, RW, RH, DW, DH) : null;
        try {
            JitterSequence js = new JitterSequence(RW, DW);
            float[] last = null;
            boolean firstOut = true;
            float near = 0.1f, far = 1000f;
            for (int f = 0; f < fr.length; f++) {
                McScene.Frame c = fr[f];
                float[] j = js.offset(f), jp = js.offset(Math.max(0, f - 1));
                ByteBuffer m = LvpDevice.bytes(bMv), zz = LvpDevice.bytes(bZ), nn = LvpDevice.bytes(bN), nz = LvpDevice.bytes(bNo);
                for (int p = 0; p < RW * RH; p++) {
                    m.putShort(p * 4, Float.floatToFloat16(c.mv[p * 2])).putShort(p * 4 + 2, Float.floatToFloat16(c.mv[p * 2 + 1]));
                    zz.putFloat(p * 4, c.viewZ[p]);
                    nn.putInt(p * 4, NrdScene.packNormalRoughness(c.normal[p * 3], c.normal[p * 3 + 1], c.normal[p * 3 + 2], 1f, 0));
                    for (int k = 0; k < 3; k++) nz.putShort(p * 8 + k * 2, Float.floatToFloat16(Math.min(c.radiance[p * 3 + k], 60000f)));
                    nz.putShort(p * 8 + 6, Float.floatToFloat16(2f));
                }
                float[] colF = new float[RW * RH * 3];
                if (den != null) {
                    dev.begin();
                    if (firstOut) { dev.layout(out.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL); }
                    dev.upload(mv.image(), RW, RH, bMv); dev.upload(z.image(), RW, RH, bZ); dev.upload(n.image(), RW, RH, bN); dev.upload(no.image(), RW, RH, bNo);
                    DenoiseFrame df = new DenoiseFrame();
                    df.commandBuffer = dev.cmd.address();
                    df.images.put(IN_MV, new ImageRef(mv.image(), VK_FORMAT_R16G16_SFLOAT, RW, RH, u, G));
                    df.images.put(IN_VIEWZ, new ImageRef(z.image(), VK_FORMAT_R32_SFLOAT, RW, RH, u, G));
                    df.images.put(IN_NORMAL_ROUGHNESS, new ImageRef(n.image(), fmtN, RW, RH, u, G));
                    df.images.put(IN_DIFF_RADIANCE_HITDIST, new ImageRef(no.image(), fmtH, RW, RH, u, G));
                    df.images.put(OUT_DIFF_RADIANCE_HITDIST, new ImageRef(out.image(), fmtH, RW, RH, u, G));
                    FrameSettings fs = df.settings;
                    fs.viewToClip = fs.viewToClipPrev = NrdScene.viewToClip();
                    fs.worldToView = McScene.worldToView(f);
                    fs.worldToViewPrev = McScene.worldToView(Math.max(0, f - 1));
                    fs.resourceSize = fs.resourceSizePrev = fs.rectSize = fs.rectSizePrev = new int[]{RW, RH};
                    fs.jitter = new float[]{-j[0], -j[1]};            // NRD: sampleUv = pixelUv + cameraJitter; our content shifts by +jitter, so the sample moves by -jitter
                    fs.jitterPrev = new float[]{-jp[0], -jp[1]};
                    fs.frameIndex = f;
                    fs.timeDeltaMs = 16.6f;
                    fs.denoisingRange = 1000f;
                    fs.reset = f == 0;
                    den.denoise(df);
                    dev.download(out.image(), RW, RH, bOut);
                    dev.submitWait();
                    firstOut = false;
                    ByteBuffer ob = LvpDevice.bytes(bOut);
                    for (int p = 0; p < RW * RH; p++) for (int k = 0; k < 3; k++) colF[p * 3 + k] = Float.float16ToFloat(ob.getShort(p * 8 + k * 2)) * (c.id[p] == 0 ? 1f : c.albedo[p * 3 + k]);
                } else {
                    for (int p = 0; p < RW * RH; p++) for (int k = 0; k < 3; k++) colF[p * 3 + k] = c.radiance[p * 3 + k] * c.albedo[p * 3 + k];
                }
                if (!fsr) { if (renderFinalOut != null) renderFinalOut[f] = colF; last = colF; continue; }
                // ---- FSR: colour (modulated), depth from viewZ, the SAME motion vectors
                ByteBuffer cb = LvpDevice.bytes(bCol), db = LvpDevice.bytes(bDep);
                for (int p = 0; p < RW * RH; p++) {
                    for (int k = 0; k < 3; k++) cb.putShort(p * 8 + k * 2, Float.floatToFloat16(Math.min(colF[p * 3 + k], 60000f)));
                    cb.putShort(p * 8 + 6, Float.floatToFloat16(1f));
                    float zv = Math.min(c.viewZ[p], 99999f);
                    db.putFloat(p * 4, far / (far - near) - near * far / ((far - near) * zv));
                }
                dev.begin();
                dev.upload(col.image(), RW, RH, bCol); dev.upload(dep.image(), RW, RH, bDep); dev.upload(mv.image(), RW, RH, bMv);
                if (f == 0) dev.layout(fo.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
                dev.layout(col.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                dev.layout(dep.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                dev.layout(mv.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                UpscaleInputs in = new UpscaleInputs();
                in.commandBuffer = dev.cmd.address();
                in.color = new ImageRef(col.image(), fmtH, RW, RH, u, RO);
                in.depth = new ImageRef(dep.image(), VK_FORMAT_R32_SFLOAT, RW, RH, u, RO);
                in.motionVectors = new ImageRef(mv.image(), VK_FORMAT_R16G16_SFLOAT, RW, RH, u, RO);
                in.output = new ImageRef(fo.image(), fmtH, DW, DH, u | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, G);
                in.jitterX = j[0]; in.jitterY = j[1];
                in.motionVectorScaleX = mvSign * RW; in.motionVectorScaleY = mvSign * RH;      // UV-unit vectors (NRD convention) -> render pixels
                in.renderWidth = RW; in.renderHeight = RH; in.displayWidth = DW; in.displayHeight = DH;
                in.frameTimeMs = 16.6f; in.cameraNear = near; in.cameraFar = far; in.cameraFovYRadians = (float) Math.toRadians(60);
                in.reset = f == 0;
                up.dispatch(in);
                dev.download(fo.image(), DW, DH, bFo);
                dev.submitWait();
                ByteBuffer fb = LvpDevice.bytes(bFo);
                float[] o = new float[DW * DH * 3];
                for (int p = 0; p < DW * DH; p++) for (int k = 0; k < 3; k++) o[p * 3 + k] = Float.float16ToFloat(fb.getShort(p * 8 + k * 2));
                last = o;
            }
            if (fsr) System.out.println("   FSR messages: " + (up.drainMessages().isEmpty() ? "none" : up.drainMessages()));
            return last;
        } finally {
            if (up != null) up.close();
            if (den != null) den.close();
            for (DenoiseGpu.Img i : new DenoiseGpu.Img[]{mv, z, n, no, out, col, dep, fo}) dev.destroyImage(i);
            for (LvpDevice.Buf b : new LvpDevice.Buf[]{bMv, bZ, bN, bNo, bOut, bCol, bDep, bFo}) dev.destroyBuffer(b);
        }
    }

    static float[] bilinear(float[] src, int sw, int sh, int dw, int dh) {
        float[] o = new float[dw * dh * 3];
        for (int y = 0; y < dh; y++) for (int x = 0; x < dw; x++) {
            float fx = (x + 0.5f) * sw / dw - 0.5f, fy = (y + 0.5f) * sh / dh - 0.5f;
            int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
            float ax = fx - x0, ay = fy - y0;
            for (int k = 0; k < 3; k++) {
                float a = px(src, sw, sh, x0, y0, k), b = px(src, sw, sh, x0 + 1, y0, k), c = px(src, sw, sh, x0, y0 + 1, k), d = px(src, sw, sh, x0 + 1, y0 + 1, k);
                o[(y * dw + x) * 3 + k] = (a * (1 - ax) + b * ax) * (1 - ay) + (c * (1 - ax) + d * ax) * ay;
            }
        }
        return o;
    }

    static float px(float[] s, int w, int h, int x, int y, int k) {
        x = Math.max(0, Math.min(w - 1, x)); y = Math.max(0, Math.min(h - 1, y));
        return s[(y * w + x) * 3 + k];
    }

    interface Mask { boolean in(int p); }

    static double rmse(float[] a, float[] ref, Mask m) {
        double s = 0; long n = 0;
        for (int p = 0; p < DW * DH; p++) if (m.in(p)) { double d = tm(lum(a[p * 3], a[p * 3 + 1], a[p * 3 + 2])) - tm(lum(ref[p * 3], ref[p * 3 + 1], ref[p * 3 + 2])); s += d * d; n++; }
        return Math.sqrt(s / Math.max(1, n));
    }

    static double hp(float[] a, int x, int y) {
        double c = tm(lum(a[(y * DW + x) * 3], a[(y * DW + x) * 3 + 1], a[(y * DW + x) * 3 + 2])), s = 0;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) { int q = (y + dy) * DW + x + dx; s += tm(lum(a[q * 3], a[q * 3 + 1], a[q * 3 + 2])); }
        return c - s / 9;
    }

    static double detail(float[] a, float[] ref, Mask m) {
        double sab = 0, saa = 0, sbb = 0;
        for (int y = 1; y < DH - 1; y++) for (int x = 1; x < DW - 1; x++) if (m.in(y * DW + x)) { double ha = hp(a, x, y), hb = hp(ref, x, y); sab += ha * hb; saa += ha * ha; sbb += hb * hb; }
        return sab / Math.sqrt(saa * sbb + 1e-12);
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) if (a.startsWith("--dump=")) dump = a.substring(7);
        if (!NativeLoader.load()) { System.out.println("# chain FAIL setup: libiroh_fsr unavailable: " + NativeLoader.unavailableReason()); System.exit(1); }
        boolean nrd = NrdLoader.load();
        dev = new LvpDevice(false);
        for (String l : dev.report) System.out.println(l);
        System.out.println("== chain: noisy 1 spp @" + RW + "x" + RH + " (jittered, " + JitterSequence.phaseCount(RW, DW) + "-phase sequence) -> denoiser -> FSR 3.1 -> " + DW + "x" + DH + "; reference 256 spp @" + DW + "x" + DH + (nrd ? "" : "; NRD not available (" + NrdLoader.unavailableReason() + ")"));
        McScene.STATIC = false;
        JitterSequence js = new JitterSequence(RW, DW);
        McScene.Frame[] fr = new McScene.Frame[NF];
        for (int f = 0; f < NF; f++) { float[] j = js.offset(f); fr[f] = McScene.render(RW, RH, f, 1, f + 1, 1f, j[0], j[1]); }
        McScene.Frame ref = McScene.render(DW, DH, LT, 256, 777, 1f, 0, 0);
        float[] gt = new float[DW * DH * 3];
        for (int p = 0; p < DW * DH * 3; p++) gt[p] = ref.radiance[p] * ref.albedo[p];
        Mask surf = p -> ref.id[p] != 0;
        Mask walls = p -> ref.id[p] != 0 && ref.id[p] != 12 && ref.id[p] != 3;
        png(gt, DW, DH, "reference_256spp.png");
        Map<String, float[]> res = new LinkedHashMap<>();
        float[][] rf = new float[NF][];
        float[] noisyRender = run("noisy render", null, fr, false, rf);
        res.put("A noisy -> bilinear", bilinear(noisyRender, RW, RH, DW, DH));
        float[] ownRender = run("own render", () -> new SvgfDenoiser(dev, new DenoiserType[]{DenoiserType.IROH_DIFFUSE}, RW, RH), fr, false, null);
        res.put("B own -> bilinear", bilinear(ownRender, RW, RH, DW, DH));
        res.put("C noisy -> FSR", run("noisy -> FSR", null, fr, true, null));
        res.put("D own -> FSR", run("own -> FSR", () -> new SvgfDenoiser(dev, new DenoiserType[]{DenoiserType.IROH_DIFFUSE}, RW, RH), fr, true, null));
        mvSign = -1f;
        res.put("G own -> FSR (WRONG mv sign)", run("own -> FSR wrong sign", () -> new SvgfDenoiser(dev, new DenoiserType[]{DenoiserType.IROH_DIFFUSE}, RW, RH), fr, true, null));
        mvSign = 1f;
        if (nrd) {
            float[] nrdRender = run("NRD render", () -> new NrdDenoiser(dev, new DenoiserType[]{DenoiserType.RELAX_DIFFUSE}, RW, RH), fr, false, null);
            res.put("E NRD RELAX -> bilinear", bilinear(nrdRender, RW, RH, DW, DH));
            res.put("F NRD RELAX -> FSR", run("NRD -> FSR", () -> new NrdDenoiser(dev, new DenoiserType[]{DenoiserType.RELAX_DIFFUSE}, RW, RH), fr, true, null));
        }
        // bilinear of a noiseless render-resolution final is the "no upscaler" ceiling reference
        double eA = 0;
        for (Map.Entry<String, float[]> e : res.entrySet()) {
            float[] o = e.getValue();
            boolean fin = true;
            for (float v : o) if (Float.isNaN(v) || Float.isInfinite(v)) { fin = false; break; }
            double er = rmse(o, gt, surf), dc = detail(o, gt, walls);
            if (e.getKey().startsWith("A")) eA = er;
            System.out.printf("%-26s RMSE tonemapped vs reference %.4f, texture detail correlation %.3f%s%n", e.getKey(), er, dc, fin ? "" : "  NaN!");
            ck(fin, e.getKey() + ": no NaN/Inf");
            png(o, DW, DH, e.getKey().replaceAll("[^A-Za-z0-9]+", "_") + ".png");
        }
        double eB = rmse(res.get("B own -> bilinear"), gt, surf), eC = rmse(res.get("C noisy -> FSR"), gt, surf), eD = rmse(res.get("D own -> FSR"), gt, surf);
        double dB = detail(res.get("B own -> bilinear"), gt, walls), dD = detail(res.get("D own -> FSR"), gt, walls);
        double eG = rmse(res.get("G own -> FSR (WRONG mv sign)"), gt, surf);
        ck(eG > eD * 1.03, String.format("the motion-vector convention is load-bearing: the opposite sign is clearly worse (%.4f vs %.4f)", eG, eD));
        ck(eB < eA, "denoising before a plain upscale lowers the error versus the noisy upscale");
        ck(eD < eC, "own denoiser + FSR is closer to the reference than FSR on the noisy frame (the shared jitter / motion-vector conventions work end to end)");
        ck(eD <= eB * 1.05, "FSR on the denoised frame is not worse than a bilinear upscale of it");
        ck(dD > dB, String.format("FSR recovers more texture detail than bilinear on the denoised frame (%.3f vs %.3f)", dD, dB));
        System.out.println("images: " + new File(dump).getAbsolutePath());
        System.out.println("# chain " + (fails == 0 ? "PASS" : "FAIL " + fails + " checks"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
