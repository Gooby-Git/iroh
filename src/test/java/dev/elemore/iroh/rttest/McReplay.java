package dev.elemore.iroh.rttest;

import dev.elemore.iroh.rt.denoise.*;
import dev.elemore.iroh.rt.denoise.nrd.*;
import dev.elemore.iroh.upscale.ImageRef;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BiConsumer;

import static dev.elemore.iroh.rt.denoise.Slot.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Minecraft-like scene (pixel-art albedo, window-lit interior, flickering torch, moving entity, strafing + turning camera) through
 * the denoisers on lavapipe: own SVGF-family vs NRD RELAX vs the temporal fallback, modulated vs demodulated input, firefly
 * clamp, and a light-switch lag test. Last line "# mcreplay PASS|FAIL". CPU Vulkan only (LvpDevice refuses anything else).
 */
public final class McReplay {
    static final int W = NrdScene.W, H = NrdScene.H, NF = 30, LT = NF - 1;
    static int fails;
    static LvpDevice dev;
    static String dump = "build/mcreplay";

    static void ck(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fails++;
    }

    static float lum(float r, float g, float b) { return 0.2126f * r + 0.7152f * g + 0.0722f * b; }
    static float tm(float x) { return x / (1 + x); }
    static int c8(float v) { return Math.max(0, Math.min(255, Math.round(v * 255f))); }

    static void png(float[] rgb, String name) throws Exception {
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < W * H; p++) im.setRGB(p % W, p / W, (c8(tm(rgb[p * 3])) << 16) | (c8(tm(rgb[p * 3 + 1])) << 8) | c8(tm(rgb[p * 3 + 2])));
        new File(dump).mkdirs();
        ImageIO.write(im, "png", new File(dump, name));
    }

    interface Make { Denoiser make(); }

    /** runs a denoiser over the frames; input = noisy radiance (demod) or modulated by the albedo; bindAlbedo: pass IN_ALBEDO. Calls perFrame(f, rgb out). Returns the last output. */
    static float[] run(String name, Make make, McScene.Frame[] fr, boolean modulatedInput, boolean bindAlbedo, boolean nrdRelax, BiConsumer<Integer, float[]> perFrame, Map<String, Float> cfg) {
        int fmtN = VK_FORMAT_A2B10G10R10_UNORM_PACK32, fmtH = VK_FORMAT_R16G16B16A16_SFLOAT;
        DenoiseGpu.Img mv = dev.createImage(VK_FORMAT_R16G16_SFLOAT, W, H, 3 | 0, "mv"), z = dev.createImage(VK_FORMAT_R32_SFLOAT, W, H, 3, "z"), n = dev.createImage(fmtN, W, H, 3, "n"),
                no = dev.createImage(fmtH, W, H, 3, "noisy"), al = dev.createImage(fmtH, W, H, 3, "albedo"), out = dev.createImage(fmtH, W, H, 3, "out");
        // usage bits: STORAGE (8) | SAMPLED (4) are required by the denoisers: recreate with the right flags
        dev.destroyImage(mv); dev.destroyImage(z); dev.destroyImage(n); dev.destroyImage(no); dev.destroyImage(al); dev.destroyImage(out);
        int u = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        mv = dev.createImage(VK_FORMAT_R16G16_SFLOAT, W, H, u, "mv"); z = dev.createImage(VK_FORMAT_R32_SFLOAT, W, H, u, "z"); n = dev.createImage(fmtN, W, H, u, "n");
        no = dev.createImage(fmtH, W, H, u, "noisy"); al = dev.createImage(fmtH, W, H, u, "albedo"); out = dev.createImage(fmtH, W, H, u, "out");
        LvpDevice.Buf bMv = dev.hostBuffer(W * H * 4, 3), bZ = dev.hostBuffer(W * H * 4, 3), bN = dev.hostBuffer(W * H * 4, 3), bNo = dev.hostBuffer(W * H * 8, 3), bAl = dev.hostBuffer(W * H * 8, 3), bOut = dev.hostBuffer(W * H * 8, 3);
        Denoiser den = make.make();
        try {
            if (den instanceof SvgfDenoiser && System.getProperty("own.cfg") != null) { Map<String, Float> mm = new HashMap<>(); for (String kv : System.getProperty("own.cfg").split(",")) { String[] a = kv.split("="); mm.put(a[0], Float.parseFloat(a[1])); } den.configure(0, mm); }
            if (cfg != null && !cfg.isEmpty()) den.configure(0, cfg);
            float[] last = null;
            boolean first = true;
            for (int f = 0; f < fr.length; f++) {
                McScene.Frame c = fr[f];
                ByteBuffer m = LvpDevice.bytes(bMv), zz = LvpDevice.bytes(bZ), nn = LvpDevice.bytes(bN), nz = LvpDevice.bytes(bNo), aa = LvpDevice.bytes(bAl);
                for (int p = 0; p < W * H; p++) {
                    m.putShort(p * 4, Float.floatToFloat16(c.mv[p * 2])).putShort(p * 4 + 2, Float.floatToFloat16(c.mv[p * 2 + 1]));
                    zz.putFloat(p * 4, c.viewZ[p]);
                    nn.putInt(p * 4, NrdScene.packNormalRoughness(c.normal[p * 3], c.normal[p * 3 + 1], c.normal[p * 3 + 2], 1f, 0));
                    for (int k = 0; k < 3; k++) {
                        float v = c.radiance[p * 3 + k] * (modulatedInput ? c.albedo[p * 3 + k] : 1f);
                        nz.putShort(p * 8 + k * 2, Float.floatToFloat16(Math.min(v, 60000f)));
                        aa.putShort(p * 8 + k * 2, Float.floatToFloat16(c.id[p] == 0 ? 1f : c.albedo[p * 3 + k]));
                    }
                    nz.putShort(p * 8 + 6, Float.floatToFloat16(2f));
                    aa.putShort(p * 8 + 6, Float.floatToFloat16(1f));
                }
                dev.begin();
                if (first) { dev.layout(out.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL); first = false; }
                dev.upload(mv.image(), W, H, bMv); dev.upload(z.image(), W, H, bZ); dev.upload(n.image(), W, H, bN); dev.upload(no.image(), W, H, bNo); dev.upload(al.image(), W, H, bAl);
                DenoiseFrame df = new DenoiseFrame();
                df.commandBuffer = dev.cmd.address();
                int G = ImageRef.LAYOUT_GENERAL;
                df.images.put(IN_MV, new ImageRef(mv.image(), VK_FORMAT_R16G16_SFLOAT, W, H, u, G));
                df.images.put(IN_VIEWZ, new ImageRef(z.image(), VK_FORMAT_R32_SFLOAT, W, H, u, G));
                df.images.put(IN_NORMAL_ROUGHNESS, new ImageRef(n.image(), fmtN, W, H, u, G));
                df.images.put(IN_DIFF_RADIANCE_HITDIST, new ImageRef(no.image(), fmtH, W, H, u, G));
                df.images.put(OUT_DIFF_RADIANCE_HITDIST, new ImageRef(out.image(), fmtH, W, H, u, G));
                if (bindAlbedo) df.images.put(IN_ALBEDO, new ImageRef(al.image(), fmtH, W, H, u, G));
                FrameSettings fs = df.settings;
                fs.viewToClip = fs.viewToClipPrev = NrdScene.viewToClip();
                fs.worldToView = McScene.worldToView(f);
                fs.worldToViewPrev = McScene.worldToView(Math.max(0, f - 1));
                fs.resourceSize = fs.resourceSizePrev = fs.rectSize = fs.rectSizePrev = new int[]{W, H};
                fs.frameIndex = f;
                fs.timeDeltaMs = 16.6f;
                fs.denoisingRange = 1000f;
                fs.reset = f == 0;
                den.denoise(df);
                dev.download(out.image(), W, H, bOut);
                dev.submitWait();
                ByteBuffer ob = LvpDevice.bytes(bOut);
                float[] o = new float[W * H * 3];
                for (int p = 0; p < W * H; p++) for (int k = 0; k < 3; k++) o[p * 3 + k] = Float.float16ToFloat(ob.getShort(p * 8 + k * 2));
                if (perFrame != null) perFrame.accept(f, o);
                last = o;
            }
            return last;
        } finally {
            den.close();
            for (DenoiseGpu.Img i : new DenoiseGpu.Img[]{mv, z, n, no, al, out}) dev.destroyImage(i);
            for (LvpDevice.Buf b : new LvpDevice.Buf[]{bMv, bZ, bN, bNo, bAl, bOut}) dev.destroyBuffer(b);
        }
    }

    static float[] mod(float[] rad, McScene.Frame f) {
        float[] o = new float[W * H * 3];
        for (int p = 0; p < W * H * 3; p++) o[p] = rad[p] * f.albedo[p];
        return o;
    }

    interface Mask { boolean in(int p); }

    static double rmse(float[] a, float[] ref, Mask m) {
        double s = 0; long n = 0;
        for (int p = 0; p < W * H; p++) if (m.in(p)) { double d = tm(lum(a[p * 3], a[p * 3 + 1], a[p * 3 + 2])) - tm(lum(ref[p * 3], ref[p * 3 + 1], ref[p * 3 + 2])); s += d * d; n++; }
        return Math.sqrt(s / Math.max(1, n));
    }

    /** correlation of the 3x3 high-pass of luminance (texture detail) between a and ref over the mask */
    static double detailCorr(float[] a, float[] ref, Mask m) {
        double sab = 0, saa = 0, sbb = 0;
        for (int y = 1; y < H - 1; y++) for (int x = 1; x < W - 1; x++) {
            int p = y * W + x;
            if (!m.in(p)) continue;
            double ha = hp(a, x, y), hb = hp(ref, x, y);
            sab += ha * hb; saa += ha * ha; sbb += hb * hb;
        }
        return sab / Math.sqrt(saa * sbb + 1e-12);
    }

    static double hp(float[] a, int x, int y) {
        double c = tm(lum(a[(y * W + x) * 3], a[(y * W + x) * 3 + 1], a[(y * W + x) * 3 + 2])), s = 0;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) { int q = (y + dy) * W + x + dx; s += tm(lum(a[q * 3], a[q * 3 + 1], a[q * 3 + 2])); }
        return c - s / 9;
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) if (a.startsWith("--dump=")) dump = a.substring(7);
        if (!NrdLoader.load()) System.out.println("note: NRD library unavailable (" + NrdLoader.unavailableReason() + "): NRD cases skipped");
        boolean nrd = NrdLoader.load();
        dev = new LvpDevice(true);
        for (String l : dev.report) System.out.println(l);
        McScene.STATIC = false;
        System.out.println("== Minecraft-like scene 320x180, " + NF + " frames: window-lit room, pixel-art albedo, flickering torch, moving entity, strafing + turning camera");
        McScene.Frame[] fr = new McScene.Frame[NF];
        for (int f = 0; f < NF; f++) fr[f] = McScene.render(f, 1, f + 1, 1f, 0, 0);
        McScene.Frame refF = McScene.render(LT, 1024, 777, 1f, 0, 0);
        McScene.Frame fl = fr[LT];
        float[] gtMod = mod(refF.radiance, fl);
        float[] noisyMod = mod(fl.radiance, fl);
        Mask surf = p -> fl.id[p] != 0;
        Mask walls = p -> fl.id[p] != 0 && fl.id[p] != 12 && fl.id[p] != 3;     // large textured surfaces (not the entity)
        int ns = 0; for (int p = 0; p < W * H; p++) if (surf.in(p)) ns++;
        System.out.println("surface pixels " + ns + ", sky/window pixels " + (W * H - ns));
        double eN = rmse(noisyMod, gtMod, surf);
        System.out.printf("noisy 1 spp (modulated): RMSE tonemapped %.4f, detail correlation with the reference %.3f%n", eN, detailCorr(noisyMod, gtMod, walls));
        png(noisyMod, "noisy_last.png");
        png(gtMod, "reference_1024spp.png");
        Map<String, float[]> res = new LinkedHashMap<>();
        Map<String, Double> err = new LinkedHashMap<>();
        class C { String name; Make mk; boolean modIn, bindAl, afterMod; Map<String, Float> cfg; C(String n, Make m, boolean mi, boolean b, boolean am, Map<String, Float> c) { name = n; mk = m; modIn = mi; bindAl = b; afterMod = am; cfg = c; } }
        List<C> cases = new ArrayList<>();
        cases.add(new C("fallback (demod)", () -> new TemporalAccumulationDenoiser(dev, W, H), false, false, true, null));
        cases.add(new C("own (demod)", () -> new SvgfDenoiser(dev, new DenoiserType[]{DenoiserType.IROH_DIFFUSE}, W, H), false, false, true, null));
        cases.add(new C("own (modulated input, no albedo)", () -> new SvgfDenoiser(dev, new DenoiserType[]{DenoiserType.IROH_DIFFUSE}, W, H), true, false, false, null));
        cases.add(new C("own (modulated input + IN_ALBEDO)", () -> new SvgfDenoiser(dev, new DenoiserType[]{DenoiserType.IROH_DIFFUSE}, W, H), true, true, false, null));
        cases.add(new C("own (demod, firefly clamp 5)", () -> new SvgfDenoiser(dev, new DenoiserType[]{DenoiserType.IROH_DIFFUSE}, W, H), false, false, true, Map.of("fireflyFactor", 5f)));
        if (nrd) {
            cases.add(new C("NRD RELAX_DIFFUSE (demod)", () -> new NrdDenoiser(dev, new DenoiserType[]{DenoiserType.RELAX_DIFFUSE}, W, H), false, false, true, null));
            cases.add(new C("NRD RELAX_DIFFUSE (modulated input)", () -> new NrdDenoiser(dev, new DenoiserType[]{DenoiserType.RELAX_DIFFUSE}, W, H), true, false, false, null));
            cases.add(new C("NRD REBLUR_DIFFUSE (demod, YCoCg)", () -> new NrdDenoiser(dev, new DenoiserType[]{DenoiserType.REBLUR_DIFFUSE}, W, H), false, false, true, null));
        }
        for (C c : cases) {
            if (c.name.contains("REBLUR")) continue;     // REBLUR needs YCoCg-packed input: covered by NrdReplay
            float[] o = run(c.name, c.mk, fr, c.modIn, c.bindAl, false, null, c.cfg);
            boolean fin = true;
            for (float v : o) if (Float.isNaN(v) || Float.isInfinite(v)) fin = false;
            float[] fin2 = c.afterMod ? mod(o, fl) : o;
            double e = rmse(fin2, gtMod, surf), dc = detailCorr(fin2, gtMod, walls);
            res.put(c.name, fin2);
            err.put(c.name, e);
            System.out.printf("%-40s RMSE tonemapped %.4f (MSE ratio vs noisy %.1fx), texture detail correlation %.3f%s%n", c.name, e, (eN * eN) / (e * e), dc, fin ? "" : "  NaN!");
            ck(fin, c.name + ": no NaN/Inf");
            png(fin2, c.name.replaceAll("[^A-Za-z0-9]+", "_") + ".png");
        }
        double eOwn = err.get("own (demod)"), eMod = err.get("own (modulated input, no albedo)"), eAl = err.get("own (modulated input + IN_ALBEDO)");
        double dOwn = detailCorr(res.get("own (demod)"), gtMod, walls), dMod = detailCorr(res.get("own (modulated input, no albedo)"), gtMod, walls), dAl = detailCorr(res.get("own (modulated input + IN_ALBEDO)"), gtMod, walls);
        ck(dAl > dMod + 0.02, String.format("IN_ALBEDO keeps pixel-art detail the modulated-input filter blurs (detail correlation %.3f vs %.3f)", dAl, dMod));
        ck(eOwn < err.get("fallback (demod)"), "own beats the temporal fallback on the Minecraft scene");
        ck(eOwn < 0.7 * eN, "own: error at least 30% below the noisy input on the Minecraft scene (the scene is mostly dark, so the noisy tonemapped error is already small)");

        // ---- light switch: static camera, torch off for frames 0..7, on from frame 8
        System.out.println("== light switch (static camera, torch off until frame 8)");
        McScene.STATIC = true;
        int NS = 26, SW = 8;
        McScene.Frame[] ls = new McScene.Frame[NS];
        for (int f = 0; f < NS; f++) ls[f] = McScene.render(0, 1, 100 + f, f < SW ? 0f : 1f, 0, 0);
        McScene.Frame on = McScene.render(0, 1024, 555, 1f, 0, 0), off = McScene.render(0, 1024, 556, 0f, 0, 0);
        for (McScene.Frame x : ls) { x.mv = zeros(); }
        boolean[] region = new boolean[W * H];
        int nr = 0;
        for (int p = 0; p < W * H; p++) if (on.id[p] != 0 && lum(on.radiance[p * 3], on.radiance[p * 3 + 1], on.radiance[p * 3 + 2]) - lum(off.radiance[p * 3], off.radiance[p * 3 + 1], off.radiance[p * 3 + 2]) > 0.3) { region[p] = true; nr++; }
        final int nrF = nr;
        double target = 0, base = 0;
        for (int p = 0; p < W * H; p++) if (region[p]) { target += lum(on.radiance[p * 3], on.radiance[p * 3 + 1], on.radiance[p * 3 + 2]); base += lum(off.radiance[p * 3], off.radiance[p * 3 + 1], off.radiance[p * 3 + 2]); }
        target /= nr; base /= nr;
        System.out.printf("torch-lit region: %d pixels, reference mean luminance off %.3f -> on %.3f%n", nr, base, target);
        List<C> lc = new ArrayList<>();
        lc.add(new C("fallback", () -> new TemporalAccumulationDenoiser(dev, W, H), false, false, false, null));
        lc.add(new C("own", () -> new SvgfDenoiser(dev, new DenoiserType[]{DenoiserType.IROH_DIFFUSE}, W, H), false, false, false, null));
        if (nrd) lc.add(new C("NRD RELAX_DIFFUSE", () -> new NrdDenoiser(dev, new DenoiserType[]{DenoiserType.RELAX_DIFFUSE}, W, H), false, false, false, null));
        for (C c : lc) {
            double[] series = new double[NS];
            run("switch " + c.name, c.mk, ls, false, false, false, (f, o) -> {
                double s = 0; for (int p = 0; p < W * H; p++) if (region[p]) s += lum(o[p * 3], o[p * 3 + 1], o[p * 3 + 2]);
                series[f] = s / nrF;
            }, null);
            double v0 = series[SW - 1];
            int reach = -1;
            for (int f = SW; f < NS; f++) if ((series[f] - v0) / (target - v0) >= 0.9) { reach = f - SW + 1; break; }
            StringBuilder sb = new StringBuilder();
            for (int f = SW - 1; f < Math.min(NS, SW + 12); f++) sb.append(String.format(" %.2f", (series[f] - v0) / (target - v0)));
            System.out.printf("%-18s frames to reach 90%% of the new level: %s; normalised response from the last dark frame:%s%n", c.name, reach < 0 ? ">" + (NS - SW) : String.valueOf(reach), sb);
            if (c.name.equals("own")) ck(reach > 0 && reach <= 15, "own: reaches 90% of the new light level within 15 frames (the plain temporal fallback and stock NRD defaults need more than " + (NS - SW) + " in this test)");
        }
        McScene.STATIC = false;
        System.out.println("images: " + new File(dump).getAbsolutePath());
        System.out.println("# mcreplay " + (fails == 0 ? "PASS" : "FAIL " + fails + " checks"));
        System.exit(fails == 0 ? 0 : 1);
    }

    static float[] zeros() { return new float[W * H * 2]; }
}
