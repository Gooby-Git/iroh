package dev.elemore.iroh.rttest;

import dev.elemore.iroh.rt.denoise.*;
import dev.elemore.iroh.rt.denoise.nrd.*;
import dev.elemore.iroh.upscale.ImageRef;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;

import static dev.elemore.iroh.rt.denoise.Slot.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * NRD (and Iroh's temporal fallback) through the JNI shim on lavapipe (CPU Vulkan; NEVER a GPU): 320x180 synthetic scene,
 * 30 frames with camera motion and a moving box, 1 spp noisy diffuse sky-light + noisy sun shadow, 1024 spp references.
 * Last line "# nrdreplay PASS|FAIL ...". Not a quality verdict for the real game: it proves the library runs, binds, and
 * denoises on a Vulkan implementation that is not NVIDIA's.
 */
public final class NrdReplay {
    static final int W = NrdScene.W, H = NrdScene.H, NF = 30, LT = NF - 1;
    static int fails;
    static LvpDevice dev;
    static String dump = "build/nrdreplay";
    static double lastMeanLen = -1, lastShortFrac = -1;
    static double sumLen; static int cntLen, shortLen;
    static boolean tuned = Boolean.parseBoolean(System.getProperty("nrd.tuned", "false"));

    record Imgs(DenoiseGpu.Img mv, DenoiseGpu.Img viewZ, DenoiseGpu.Img nrm, DenoiseGpu.Img noisy, DenoiseGpu.Img out, DenoiseGpu.Img pen, DenoiseGpu.Img shadow, DenoiseGpu.Img spec) {}

    static void ck(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fails++;
    }

    static ImageRef ref(DenoiseGpu.Img i, int fmt) {
        return new ImageRef(i.image(), fmt, W, H, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, ImageRef.LAYOUT_GENERAL);
    }

    static DenoiseGpu.Img mk(int fmt) {
        return dev.createImage(fmt, W, H, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "user");
    }

    static float lum(float r, float g, float b) { return 0.2126f * r + 0.7152f * g + 0.0722f * b; }

    static float tm(float x) { return x / (1 + x); }

    static int clamp8(float v) { return Math.max(0, Math.min(255, Math.round(v * 255f))); }

    static void png(float[] rgb, int stride, String name) throws Exception {
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int o = (y * W + x) * stride;
            im.setRGB(x, y, (clamp8(tm(rgb[o])) << 16) | (clamp8(tm(rgb[o + (stride > 1 ? 1 : 0)])) << 8) | clamp8(tm(rgb[o + (stride > 1 ? 2 : 0)])));
        }
        new File(dump).mkdirs();
        ImageIO.write(im, "png", new File(dump, name));
    }

    /** runs one denoiser over the frame sequence; returns the output of the last frame (rgb, or visibility for SIGMA) and per-frame finiteness. */
    static float[] run(String name, DenoiserType type, boolean fallback, NrdScene.Frame[] frames, boolean[] finiteAll, long[] memOut) throws Exception {
        Imgs im = new Imgs(mk(VK_FORMAT_R16G16_SFLOAT), mk(VK_FORMAT_R32_SFLOAT), mk(VK_FORMAT_A2B10G10R10_UNORM_PACK32), mk(VK_FORMAT_R16G16B16A16_SFLOAT), mk(VK_FORMAT_R16G16B16A16_SFLOAT),
                mk(VK_FORMAT_R16_SFLOAT), mk(VK_FORMAT_R8_UNORM), mk(VK_FORMAT_R16G16B16A16_SFLOAT));
        LvpDevice.Buf bMv = dev.hostBuffer(W * H * 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT), bZ = dev.hostBuffer(W * H * 4, 3), bN = dev.hostBuffer(W * H * 4, 3),
                bNoisy = dev.hostBuffer(W * H * 8, 3), bOut = dev.hostBuffer(W * H * 8, 3), bPen = dev.hostBuffer(W * H * 2, 3), bSh = dev.hostBuffer(W * H, 3);
        boolean own = type.name().startsWith("IROH_");
        Denoiser den = fallback ? new TemporalAccumulationDenoiser(dev, W, H) : own ? new SvgfDenoiser(dev, new DenoiserType[]{type}, W, H) : new NrdDenoiser(dev, new DenoiserType[]{type}, W, H);
        try {
            System.out.println("-- " + name + ": " + den.name() + "; library pools estimate 320x180 " + den.estimateMemoryBytes(W, H) + " B, allocated {perm, trans} " + java.util.Arrays.toString(den.memoryUsageBytes()));
            memOut[0] = den.memoryUsageBytes()[0] + den.memoryUsageBytes()[1];
            if (!fallback && tuned && type == DenoiserType.REBLUR_DIFFUSE) den.configure(0, Map.of("maxBlurRadius", 8f, "diffusePrepassBlurRadius", 8f, "minBlurRadius", 1f));
            if (!fallback && tuned && type == DenoiserType.RELAX_DIFFUSE) den.configure(0, Map.of("atrousIterations", 3f, "diffusePrepassBlurRadius", 8f));
            if (own && System.getProperty("own.cfg") != null) { Map<String, Float> mm = new java.util.HashMap<>(); for (String kv : System.getProperty("own.cfg").split(",")) { String[] a = kv.split("="); mm.put(a[0], Float.parseFloat(a[1])); } den.configure(0, mm); }
            if (type == DenoiserType.SIGMA_SHADOW) den.configure(0, Map.of("lightDirX", NrdScene.SUN[0], "lightDirY", NrdScene.SUN[1], "lightDirZ", NrdScene.SUN[2]));
            float[] last = null;
            boolean first = true;
            for (int f = 0; f < NF; f++) {
                NrdScene.Frame fr = frames[f];
                ByteBuffer mv = LvpDevice.bytes(bMv), z = LvpDevice.bytes(bZ), n = LvpDevice.bytes(bN), no = LvpDevice.bytes(bNoisy), pe = LvpDevice.bytes(bPen);
                for (int p = 0; p < W * H; p++) {
                    mv.putShort(p * 4, Float.floatToFloat16(fr.mv[p * 2])).putShort(p * 4 + 2, Float.floatToFloat16(fr.mv[p * 2 + 1]));
                    z.putFloat(p * 4, fr.viewZ[p]);
                    n.putInt(p * 4, NrdScene.packNormalRoughness(fr.normal[p * 3], fr.normal[p * 3 + 1], fr.normal[p * 3 + 2], 1.0f, 0));
                    float r = fr.radiance[p * 3], g = fr.radiance[p * 3 + 1], b = fr.radiance[p * 3 + 2], hd = fr.hitDist[p];
                    float a0, a1, a2, a3;
                    if (type == DenoiserType.REBLUR_DIFFUSE) { float[] y = NrdScene.ycocg(r, g, b); a0 = y[0]; a1 = y[1]; a2 = y[2]; a3 = NrdScene.normHitDist(hd, fr.viewZ[p]); }
                    else { a0 = r; a1 = g; a2 = b; a3 = Math.min(hd, 65504f); }
                    no.putShort(p * 8, Float.floatToFloat16(Math.min(a0, 65504f))).putShort(p * 8 + 2, Float.floatToFloat16(a1)).putShort(p * 8 + 4, Float.floatToFloat16(a2)).putShort(p * 8 + 6, Float.floatToFloat16(a3));
                    pe.putShort(p * 2, Float.floatToFloat16(Math.min(fr.penumbra[p], 65504f)));
                }
                dev.begin();
                if (first) {
                    dev.layout(im.out().image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
                    dev.layout(im.shadow().image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
                    first = false;
                }
                dev.upload(im.mv().image(), W, H, bMv);
                dev.upload(im.viewZ().image(), W, H, bZ);
                dev.upload(im.nrm().image(), W, H, bN);
                dev.upload(im.noisy().image(), W, H, bNoisy);
                dev.upload(im.pen().image(), W, H, bPen);
                DenoiseFrame df = new DenoiseFrame();
                df.commandBuffer = dev.cmd.address();
                df.images.put(IN_MV, ref(im.mv(), VK_FORMAT_R16G16_SFLOAT));
                df.images.put(IN_VIEWZ, ref(im.viewZ(), VK_FORMAT_R32_SFLOAT));
                df.images.put(IN_NORMAL_ROUGHNESS, ref(im.nrm(), VK_FORMAT_A2B10G10R10_UNORM_PACK32));
                df.images.put(IN_DIFF_RADIANCE_HITDIST, ref(im.noisy(), VK_FORMAT_R16G16B16A16_SFLOAT));
                df.images.put(OUT_DIFF_RADIANCE_HITDIST, ref(im.out(), VK_FORMAT_R16G16B16A16_SFLOAT));
                df.images.put(IN_PENUMBRA, ref(im.pen(), VK_FORMAT_R16_SFLOAT));
                df.images.put(OUT_SHADOW_TRANSLUCENCY, own ? ref(im.out(), VK_FORMAT_R16G16B16A16_SFLOAT) : ref(im.shadow(), VK_FORMAT_R8_UNORM));
                FrameSettings fs = df.settings;
                fs.viewToClip = fs.viewToClipPrev = NrdScene.viewToClip();
                fs.worldToView = NrdScene.worldToView(f);
                fs.worldToViewPrev = NrdScene.worldToView(Math.max(0, f - 1));
                fs.resourceSize = fs.resourceSizePrev = fs.rectSize = fs.rectSizePrev = new int[]{W, H};
                fs.frameIndex = f;
                fs.timeDeltaMs = 16.6f;
                fs.denoisingRange = 1000f;
                fs.reset = f == 0;
                den.denoise(df);
                boolean scalarOut = type == DenoiserType.SIGMA_SHADOW || type == DenoiserType.IROH_SHADOW;
                if (type == DenoiserType.SIGMA_SHADOW) dev.download(im.shadow().image(), W, H, bSh);
                else dev.download(im.out().image(), W, H, bOut);
                dev.submitWait();
                ByteBuffer ob = LvpDevice.bytes(type == DenoiserType.SIGMA_SHADOW ? bSh : bOut);
                float[] o = new float[scalarOut ? W * H : W * H * 3];
                boolean finite = true;
                for (int p = 0; p < W * H; p++) {
                    if (type == DenoiserType.SIGMA_SHADOW) {
                        float v = (ob.get(p) & 255) / 255f;
                        o[p] = v * v;                                   // SIGMA_BackEnd_UnpackShadow: shadow = value^2
                    } else if (type == DenoiserType.IROH_SHADOW) {
                        o[p] = Float.float16ToFloat(ob.getShort(p * 8));
                    } else {
                        float c0 = Float.float16ToFloat(ob.getShort(p * 8)), c1 = Float.float16ToFloat(ob.getShort(p * 8 + 2)), c2 = Float.float16ToFloat(ob.getShort(p * 8 + 4));
                        float[] rgb = type == DenoiserType.REBLUR_DIFFUSE ? NrdScene.fromYcocg(c0, c1, c2) : new float[]{c0, c1, c2};
                        o[p * 3] = rgb[0]; o[p * 3 + 1] = rgb[1]; o[p * 3 + 2] = rgb[2];
                        if (own && f == NF - 1) { float len = Float.float16ToFloat(ob.getShort(p * 8 + 6)); if (frames[f].id[p] != 0) { lastMeanLen += (lastMeanLen < 0 ? 1 : 0); sumLen += len; cntLen++; if (len < 4) shortLen++; } }
                        finite &= !(Float.isNaN(c0) || Float.isInfinite(c0) || Float.isNaN(c1) || Float.isInfinite(c1) || Float.isNaN(c2) || Float.isInfinite(c2));
                    }
                }
                finiteAll[0] &= finite;
                last = o;
            }
            return last;
        } finally {
            den.close();
            for (DenoiseGpu.Img i : new DenoiseGpu.Img[]{im.mv(), im.viewZ(), im.nrm(), im.noisy(), im.out(), im.pen(), im.shadow(), im.spec()}) dev.destroyImage(i);
            for (LvpDevice.Buf b : new LvpDevice.Buf[]{bMv, bZ, bN, bNoisy, bOut, bPen, bSh}) dev.destroyBuffer(b);
        }
    }

    interface Mask { boolean in(int p); }

    /** mean |luminance step| (tonemapped) across 4-neighbour pairs that belong to different objects: how much silhouette contrast survives. */
    static double boundaryContrast(float[] a, NrdScene.Frame fr) {
        double s = 0;
        int n = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = y * W + x;
            if (fr.id[p] == 0) continue;
            for (int[] d : new int[][]{{1, 0}, {0, 1}}) {
                int xx = x + d[0], yy = y + d[1];
                if (xx >= W || yy >= H) continue;
                int q = yy * W + xx;
                if (fr.id[q] == 0 || fr.id[q] == fr.id[p]) continue;
                s += Math.abs(tm(lum(a[p * 3], a[p * 3 + 1], a[p * 3 + 2])) - tm(lum(a[q * 3], a[q * 3 + 1], a[q * 3 + 2])));
                n++;
            }
        }
        return n == 0 ? 0 : s / n;
    }

    static double rmse(float[] a, float[] ref, int stride, Mask m, boolean tonemap) {
        double s = 0;
        long n = 0;
        for (int p = 0; p < W * H; p++) {
            if (!m.in(p)) continue;
            double d;
            if (stride == 1) d = a[p] - ref[p];
            else {
                float x = lum(a[p * 3], a[p * 3 + 1], a[p * 3 + 2]), y = lum(ref[p * 3], ref[p * 3 + 1], ref[p * 3 + 2]);
                d = tonemap ? tm(x) - tm(y) : x - y;
            }
            s += d * d;
            n++;
        }
        return n == 0 ? 0 : Math.sqrt(s / n);
    }

    public static void main(String[] args) throws Exception {
        String golden = "check";
        boolean noQuad = false;
        for (String a : args) {
            if (a.startsWith("--golden=")) golden = a.substring(9);
            else if (a.startsWith("--dump=")) dump = a.substring(7);
        }
        StringBuilder err = new StringBuilder();
        if (!NrdLoader.load()) {
            System.out.println("# nrdreplay FAIL setup: libiroh_nrd unavailable: " + NrdLoader.unavailableReason());
            System.exit(1);
        }
        int[] lib = NrdNative.libraryDesc();
        System.out.println("native: " + NrdNative.version() + "; SPIR-V binding offsets sampler/texture/cb/storage = " + lib[0] + "/" + lib[1] + "/" + lib[2] + "/" + lib[3]
                + "; normalEncoding " + lib[7] + " roughnessEncoding " + lib[8] + "; " + lib[9] + " denoisers");
        // names must match the Java slot / type ordinals
        int bad = 0, order = 0;
        java.util.List<String> libNames = new java.util.ArrayList<>(), javaNames = new java.util.ArrayList<>();
        for (Slot s : Slot.values()) { if (s == Slot.IN_ALBEDO) continue; libNames.add(NrdNative.resourceTypeName(s.ordinal()).replace("_", "").toLowerCase()); javaNames.add(s.name().replace("_", "").toLowerCase()); if (!libNames.get(libNames.size() - 1).equals(javaNames.get(javaNames.size() - 1))) order++; }
        java.util.Collections.sort(libNames); java.util.Collections.sort(javaNames);
        if (!libNames.equals(javaNames)) bad++;
        for (DenoiserType t : DenoiserType.values()) if (t.nrdValue >= 0 && !NrdNative.denoiserName(t.nrdValue).replace("_", "").equalsIgnoreCase(t.name().replace("_", ""))) { bad++; System.out.println("type mismatch " + t + " vs " + NrdNative.denoiserName(t.nrdValue)); }
        ck(bad == 0, "Java Slot names equal the library's resource type names (as a set) and DenoiserType values equal nrd::Denoiser names");
        System.out.println("note: NRD v4.18.0 GetResourceTypeString() lists the 3 confidence/threshold-mix inputs AFTER the SH inputs but the enum (NRDDescs.h, and the values in dispatches) has them first: " + order + " of 32 names come back at the wrong ordinal (debug strings only; the Java slots follow the header enum)");
        // memory for the Radiance-style set from the real library (no GPU)
        for (int[] sz : new int[][]{{1707, 960}, {2560, 1440}}) {
            StringBuilder sb = new StringBuilder("pool memory " + sz[0] + "x" + sz[1] + ":");
            for (DenoiserType[] set : new DenoiserType[][]{{DenoiserType.RELAX_DIFFUSE_SPECULAR}, {DenoiserType.REBLUR_DIFFUSE_SPECULAR}, {DenoiserType.SIGMA_SHADOW},
                    {DenoiserType.RELAX_DIFFUSE_SPECULAR, DenoiserType.REBLUR_DIFFUSE_SPECULAR, DenoiserType.SIGMA_SHADOW}}) {
                int[] ids = new int[set.length];
                for (int i = 0; i < set.length; i++) ids[i] = set[i].nrdValue;
                long h = NrdNative.create(ids);
                NrdInstanceDesc d = NrdInstanceDesc.parse(NrdNative.instanceDesc(h));
                long perm = d.poolBytes(d.permanent, sz[0], sz[1]), trans = d.poolBytes(d.transient_, sz[0], sz[1]);
                sb.append(String.format(" [%s %.1f MiB (perm %.1f + trans %.1f), %d pipelines]", java.util.Arrays.toString(set).replace("DenoiserType.", ""), (perm + trans) / 1048576.0, perm / 1048576.0, trans / 1048576.0, d.pipelines.length));
                NrdNative.destroy(h);
            }
            System.out.println(sb);
        }
        dev = new LvpDevice(true);
        for (String l : dev.report) System.out.println(l);
        System.out.println("note: the NRD library in use was built with " + (dev.computeDerivativesQuads ? "" : "(device lacks compute derivative quads) ") + "NRD_SUPPORTS_QUAD_INTRINSICS as selected by the gradle task");
        System.out.println("== scene 320x180, " + NF + " frames, camera motion + moving box, 1 spp noisy inputs");
        NrdScene.Frame[] frames = new NrdScene.Frame[NF];
        for (int f = 0; f < NF; f++) frames[f] = NrdScene.render(f, 1, f + 1);
        float[][] ref = NrdScene.reference(LT, 1024);
        float[] gt = ref[0], gtVis = ref[1];
        NrdScene.Frame fl = frames[LT];
        Mask surf = p -> fl.id[p] != 0;
        Mask edge = p -> {
            if (fl.id[p] == 0) return false;
            int x = p % W, y = p / W;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int xx = x + d[0], yy = y + d[1];
                if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue;
                int q = yy * W + xx;
                if (fl.id[q] != fl.id[p]) return true;
            }
            return false;
        };
        Mask dis = p -> fl.id[p] != 0 && fl.disoccluded[p];
        int nSurf = 0, nEdge = 0, nDis = 0;
        for (int p = 0; p < W * H; p++) { if (surf.in(p)) nSurf++; if (edge.in(p)) nEdge++; if (dis.in(p)) nDis++; }
        System.out.println("pixels: surface " + nSurf + ", edge " + nEdge + ", disoccluded at the last frame " + nDis);
        double eNoisy = rmse(fl.radiance, gt, 3, surf, false), eNoisyT = rmse(fl.radiance, gt, 3, surf, true);
        double eNoisyEdge = rmse(fl.radiance, gt, 3, edge, true), eNoisyDis = rmse(fl.radiance, gt, 3, dis, true);
        System.out.printf("noisy 1 spp: RMSE luminance %.4f (tonemapped %.4f), edge %.4f, disoccluded %.4f%n", eNoisy, eNoisyT, eNoisyEdge, eNoisyDis);
        png(fl.radiance, 3, "noisy_last.png");
        png(gt, 3, "reference_1024spp.png");
        boolean[] fin = {true};
        long[] mem = new long[1];
        // ---- temporal accumulation fallback (Iroh's own)
        float[] fb = run("fallback", DenoiserType.TEMPORAL_ACCUMULATION, true, frames, fin, mem);
        ck(fin[0], "fallback: no NaN/Inf in any frame");
        double fbE = rmse(fb, gt, 3, surf, true), fbEdge = rmse(fb, gt, 3, edge, true), fbDis = rmse(fb, gt, 3, dis, true);
        System.out.printf("fallback temporal accumulation: RMSE (tonemapped) %.4f, edge %.4f, disoccluded %.4f (noisy %.4f / %.4f / %.4f)%n", fbE, fbEdge, fbDis, eNoisyT, eNoisyEdge, eNoisyDis);
        ck(fbE < eNoisyT * 0.7, "fallback reduces the error versus the noisy input (> 30%)");
        png(fb, 3, "fallback_temporal.png");
        // ---- Iroh's own SVGF-family denoiser
        fin[0] = true;
        float[] ow = run("IROH_DIFFUSE", DenoiserType.IROH_DIFFUSE, false, frames, fin, mem);
        ck(fin[0], "own: no NaN/Inf in any frame");
        double oE = rmse(ow, gt, 3, surf, true), oEdge = rmse(ow, gt, 3, edge, true), oDis = rmse(ow, gt, 3, dis, true), oLin = rmse(ow, gt, 3, surf, false);
        double oC = boundaryContrast(ow, fl), gC = boundaryContrast(gt, fl);
        System.out.printf("   own history length at the last frame: mean %.1f, %.1f%% of surface pixels below 4 frames%n", sumLen / Math.max(1, cntLen), 100.0 * shortLen / Math.max(1, cntLen));
        System.out.printf("IROH_DIFFUSE (own): RMSE tonemapped %.4f (noisy %.4f, fallback %.4f), linear %.4f, edge %.4f, disoccluded %.4f; MSE ratio %.1fx; silhouette contrast %.0f%%%n", oE, eNoisyT, fbE, oLin, oEdge, oDis, (eNoisyT * eNoisyT) / (oE * oE), 100 * oC / gC);
        ck(oE * oE < 0.2 * eNoisyT * eNoisyT, "own: variance reduced at least 5x versus the noisy input");
        ck(oC > 0.7 * gC, "own: silhouettes keep at least 70% of the reference contrast");
        ck(oE < fbE, "own: better than the plain temporal fallback");
        png(ow, 3, "iroh_diffuse.png");
        // ---- NRD
        for (DenoiserType t : new DenoiserType[]{DenoiserType.RELAX_DIFFUSE, DenoiserType.REBLUR_DIFFUSE}) {
            fin[0] = true;
            float[] o;
            try {
                o = run(t.name(), t, false, frames, fin, mem);
            } catch (Throwable x) {
                System.out.println("NRD " + t + " FAILURE on lavapipe: " + x);
                x.printStackTrace(System.out);
                fails++;
                continue;
            }
            ck(fin[0], t + ": no NaN/Inf in any frame");
            { double mo = 0, mg = 0, mn = 0; int c = 0; for (int p = 0; p < W * H; p++) if (surf.in(p)) { mo += lum(o[p * 3], o[p * 3 + 1], o[p * 3 + 2]); mg += lum(gt[p * 3], gt[p * 3 + 1], gt[p * 3 + 2]); mn += lum(fl.radiance[p * 3], fl.radiance[p * 3 + 1], fl.radiance[p * 3 + 2]); c++; }
              System.out.printf("   mean luminance over surfaces: denoised %.4f, reference %.4f, noisy %.4f%n", mo / c, mg / c, mn / c); }
            double e = rmse(o, gt, 3, surf, true), eRaw = rmse(o, gt, 3, surf, false), ed = rmse(o, gt, 3, edge, true), di = rmse(o, gt, 3, dis, true);
            System.out.printf("%s: RMSE tonemapped %.4f (noisy %.4f, fallback %.4f), linear %.4f (noisy %.4f), edge %.4f (noisy %.4f, fallback %.4f), disoccluded %.4f (noisy %.4f, fallback %.4f)%n",
                    t, e, eNoisyT, fbE, eRaw, eNoisy, ed, eNoisyEdge, fbEdge, di, eNoisyDis, fbDis);
            { double[] se = new double[5], sn = new double[5]; int[] cn = new int[5]; int minx = W, maxx = 0, miny = H, maxy = 0; double worst = 0;
              for (int p = 0; p < W * H; p++) if (dis.in(p)) { double d = tm(lum(o[p * 3], o[p * 3 + 1], o[p * 3 + 2])) - tm(lum(gt[p * 3], gt[p * 3 + 1], gt[p * 3 + 2])); double dn = tm(lum(fl.radiance[p * 3], fl.radiance[p * 3 + 1], fl.radiance[p * 3 + 2])) - tm(lum(gt[p * 3], gt[p * 3 + 1], gt[p * 3 + 2])); int id = fl.id[p]; se[id] += d * d; sn[id] += dn * dn; cn[id]++; if (Math.abs(d) > 0.3) { minx = Math.min(minx, p % W); maxx = Math.max(maxx, p % W); miny = Math.min(miny, p / W); maxy = Math.max(maxy, p / W); } }
              StringBuilder sb = new StringBuilder("   disoccluded error by object (rmse denoised/noisy, px): ");
              for (int id = 1; id < 5; id++) if (cn[id] > 0) sb.append(String.format("id%d %.3f/%.3f (%d)  ", id, Math.sqrt(se[id] / cn[id]), Math.sqrt(sn[id] / cn[id]), cn[id]));
              sb.append(String.format("; pixels with |err|>0.3 span x %d..%d y %d..%d", minx, maxx, miny, maxy)); System.out.println(sb); }
            System.out.printf("   MSE ratio noisy/denoised (tonemapped) = %.1fx%n", (eNoisyT * eNoisyT) / (e * e));
            ck(e * e < 0.25 * eNoisyT * eNoisyT, t + ": variance reduced at least 4x versus the noisy input");
            double cD = boundaryContrast(o, fl), cG = boundaryContrast(gt, fl);
            System.out.printf("   silhouette contrast kept: denoised %.4f vs reference %.4f (%.0f%%)%n", cD, cG, 100 * cD / cG);
            ck(cD > 0.7 * cG, t + ": silhouettes keep at least 70% of the reference contrast (edges stay)");
            ck(di < 1.5 * e + 0.05, t + ": disoccluded pixels are not much worse than the frame average (no ghosting blow-up)");
            png(o, 3, t.name().toLowerCase() + ".png");
            if (t == DenoiserType.RELAX_DIFFUSE) goldenCheck(o, golden);
        }
        // ---- own shadow denoiser
        fin[0] = true;
        float[] osh = run("IROH_SHADOW", DenoiserType.IROH_SHADOW, false, frames, fin, mem);
        { float[] nv = new float[W * H]; for (int p = 0; p < W * H; p++) nv[p] = fl.penumbra[p] >= NrdScene.FP16_MAX ? 1f : 0f;
          Mask pen = p -> fl.id[p] != 0 && gtVis[p] > 0.02f && gtVis[p] < 0.98f;
          double enp = rmse(nv, gtVis, 1, pen, false), edp = rmse(osh, gtVis, 1, pen, false);
          System.out.printf("IROH_SHADOW (own): penumbra RMSE %.4f vs noisy %.4f; whole surface %.4f%n", edp, enp, rmse(osh, gtVis, 1, surf, false));
          ck(fin[0] && edp < enp, "own shadow: penumbra closer to the reference than the 1-sample input");
          float[] sv = new float[W * H * 3]; for (int p = 0; p < W * H; p++) sv[p * 3] = sv[p * 3 + 1] = sv[p * 3 + 2] = osh[p]; png(sv, 3, "iroh_shadow.png"); }
        // ---- SIGMA
        fin[0] = true;
        try {
            float[] sh = run("SIGMA_SHADOW", DenoiserType.SIGMA_SHADOW, false, frames, fin, mem);
            float[] noisyVis = new float[W * H];
            for (int p = 0; p < W * H; p++) noisyVis[p] = fl.penumbra[p] >= NrdScene.FP16_MAX ? 1f : 0f;
            double en = rmse(noisyVis, gtVis, 1, surf, false), ed = rmse(sh, gtVis, 1, surf, false);
            System.out.printf("SIGMA_SHADOW: visibility RMSE denoised %.4f vs noisy (1 sample) %.4f%n", ed, en);
            ck(fin[0], "SIGMA: no NaN/Inf");
            { double lit = 0, shd = 0, gl = 0, gs = 0; int cl = 0, cs = 0; for (int p = 0; p < W * H; p++) if (surf.in(p)) { if (gtVis[p] > 0.99f) { lit += sh[p]; gl += gtVis[p]; cl++; } else if (gtVis[p] < 0.01f) { shd += sh[p]; gs += gtVis[p]; cs++; } }
              System.out.printf("   SIGMA mean visibility: lit area %.4f (reference %.4f, %d px), shadow core %.4f (reference %.4f, %d px)%n", lit / cl, gl / cl, cl, shd / cs, gs / cs, cs); }
            Mask pen = p -> fl.id[p] != 0 && gtVis[p] > 0.02f && gtVis[p] < 0.98f;
            int np = 0;
            for (int p = 0; p < W * H; p++) if (pen.in(p)) np++;
            double enp = rmse(noisyVis, gtVis, 1, pen, false), edp = rmse(sh, gtVis, 1, pen, false);
            System.out.printf("   SIGMA penumbra pixels (%d): RMSE denoised %.4f vs noisy %.4f%n", np, edp, enp);
            ck(edp < enp, "SIGMA: in the penumbra the denoised visibility is closer to the reference than the 1-sample input");
            float[] sv = new float[W * H * 3], gv = new float[W * H * 3];
            for (int p = 0; p < W * H; p++) { sv[p * 3] = sv[p * 3 + 1] = sv[p * 3 + 2] = sh[p]; gv[p * 3] = gv[p * 3 + 1] = gv[p * 3 + 2] = gtVis[p]; }
            png(sv, 3, "sigma_shadow.png");
            png(gv, 3, "sigma_reference.png");
        } catch (Throwable x) {
            System.out.println("NRD SIGMA_SHADOW FAILURE on lavapipe: " + x);
            x.printStackTrace(System.out);
            fails++;
        }
        System.out.println("images: " + new File(dump).getAbsolutePath() + "/{noisy_last,reference_1024spp,fallback_temporal,relax_diffuse,reblur_diffuse,sigma_shadow,sigma_reference}.png");
        System.out.println("device memory used by the harness at the end: " + dev.allocated + " B of cap " + LvpDevice.CAP);
        System.out.println("# nrdreplay " + (fails == 0 ? "PASS" : "FAIL " + fails + " checks"));
        System.exit(fails == 0 ? 0 : 1);
    }

    static void goldenCheck(float[] o, String mode) throws Exception {
        File gf = new File("src/test/resources/golden/nrd-relax-diffuse-320x180.png");
        BufferedImage cur = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < W * H; p++) cur.setRGB(p % W, p / W, (clamp8(tm(o[p * 3])) << 16) | (clamp8(tm(o[p * 3 + 1])) << 8) | clamp8(tm(o[p * 3 + 2])));
        if (mode.equals("update") || !gf.exists()) {
            gf.getParentFile().mkdirs();
            ImageIO.write(cur, "png", gf);
            System.out.println("golden " + (mode.equals("update") ? "updated" : "missing: created") + " " + gf);
            return;
        }
        BufferedImage g = ImageIO.read(gf);
        double sum = 0, mx = 0;
        for (int p = 0; p < W * H; p++) {
            int a = cur.getRGB(p % W, p / W), b = g.getRGB(p % W, p / W);
            for (int sh = 0; sh <= 16; sh += 8) { double d = Math.abs(((a >> sh) & 255) - ((b >> sh) & 255)); sum += d; mx = Math.max(mx, d); }
        }
        double mean = sum / (W * H * 3);
        System.out.printf("golden (RELAX_DIFFUSE, last frame): mean abs diff %.3f/255, max %.0f/255%n", mean, mx);
        ck(mean <= 1.5 && mx <= 40, "matches the stored golden within tolerance (mean <= 1.5/255, max <= 40/255)");
    }
}
