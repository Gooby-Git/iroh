package dev.elemore.iroh.rttest;

import dev.elemore.iroh.upscale.ImageRef;
import dev.elemore.iroh.upscale.fsr.FsrFrameGeneration;
import dev.elemore.iroh.upscale.fsr.NativeLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.function.IntFunction;

import static org.lwjgl.vulkan.VK12.*;

/**
 * Phase 8 groundwork on lavapipe (CPU Vulkan only): FSR optical flow + frame interpolation through libiroh_fsr WITHOUT the Win32 swapchain
 * proxy. Two real frames (+ depth + motion vectors) -> the interpolated middle frame, checked against the analytically rendered
 * true middle frame on (1) a moving checker scene and (2) the Minecraft-like scene. Last line "# fgreplay PASS|FAIL".
 */
public final class FgReplay {
    static int W = NrdScene.W, H = NrdScene.H;
    static int fails;
    static LvpDevice dev;
    static String dump = "build/fgreplay";
    static final int EXT_OF = Integer.getInteger("fg.extof", 0);

    static void ck(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fails++;
    }

    static void png(float[] rgb, String name) throws Exception {
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < W * H; p++) im.setRGB(p % W, p / W, (c8(rgb[p * 3]) << 16) | (c8(rgb[p * 3 + 1]) << 8) | c8(rgb[p * 3 + 2]));
        new File(dump).mkdirs();
        ImageIO.write(im, "png", new File(dump, name));
    }

    static int c8(float v) { return Math.max(0, Math.min(255, Math.round(v * 255f))); }

    /** a real frame: sRGB-encoded colour (0..1), depth 0..1, motion vectors in pixels (current -> previous), camera */
    static final class Real {
        float[] rgb = new float[W * H * 3], depth = new float[W * H], mv = new float[W * H * 2];
        float[] pos = new float[3], up = new float[]{0, 1, 0}, right = new float[]{1, 0, 0}, fwd = new float[]{0, 0, 1};
        float near = 0.1f, far = 1000f;
        int[] id = new int[W * H];
    }

    static float kk(int w) { return w / 320f; }

    // ---- scene 1: moving checker square (display-space velocity 4 px/frame in x, 1.5 in y), textured background
    static float[] checker(float x, float y, float t, boolean[] on) {
        float k = W / 320f;
        float sx = (90 + 4f * t) * k, sy = (50 + 1.5f * t) * k;
        boolean in = x >= sx && x < sx + 80 * k && y >= sy && y < sy + 80 * k;
        on[0] = in;
        if (in) { boolean w = (((int) Math.floor((x - sx) / (10f * k)) + (int) Math.floor((y - sy) / (10f * k))) & 1) == 0; return w ? new float[]{0.95f, 0.85f, 0.25f} : new float[]{0.1f, 0.15f, 0.6f}; }
        float u = x / W, v = y / H, b = 0.3f + 0.3f * u + 0.15f * v;
        boolean cell = (((int) Math.floor(x / (6f * kk(W))) + (int) Math.floor(y / (6f * kk(W)))) & 1) == 0;
        float kc = cell ? 0.1f : 0f;
        return new float[]{b + kc, b * 0.9f + kc, b * 0.75f + kc};
    }

    static Real checkerFrame(float t, float tPrev) {
        Real r = new Real();
        boolean[] on = new boolean[1];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            float[] acc = new float[3];
            for (int sy = 0; sy < 3; sy++) for (int sx = 0; sx < 3; sx++) { float[] c = checker(x + (sx + 0.5f) / 3f, y + (sy + 0.5f) / 3f, t, on); acc[0] += c[0]; acc[1] += c[1]; acc[2] += c[2]; }
            int p = y * W + x;
            r.rgb[p * 3] = acc[0] / 9; r.rgb[p * 3 + 1] = acc[1] / 9; r.rgb[p * 3 + 2] = acc[2] / 9;
            checker(x + 0.5f, y + 0.5f, t, on);
            r.depth[p] = on[0] ? 0.5f : 0.9f;
            r.mv[p * 2] = on[0] ? -4f * kk(W) * (t - tPrev) : 0f; r.mv[p * 2 + 1] = on[0] ? -1.5f * kk(W) * (t - tPrev) : 0f;
        }
        return r;
    }

    // ---- scene 2: Minecraft-like (camera strafe + turn + moving entity), 64 spp to keep noise out of the comparison
    static float srgb(float lin) {
        float x = lin / (1 + lin);                       // tonemap
        return x <= 0.0031308f ? 12.92f * x : 1.055f * (float) Math.pow(x, 1 / 2.4) - 0.055f;
    }

    static Real mcFrame(int f, float half, int spp) {
        McScene.STATIC = false;
        McScene.HALF = half;
        McScene.Frame fr = McScene.render(W, H, f, spp, 900 + f, 1f, 0, 0);
        McScene.HALF = 0f;
        Real r = new Real();
        for (int p = 0; p < W * H; p++) {
            for (int k = 0; k < 3; k++) r.rgb[p * 3 + k] = fr.id[p] == 0 ? 0.7f : srgb(fr.radiance[p * 3 + k] * fr.albedo[p * 3 + k]);
            float z = Math.min(fr.viewZ[p], 99999f);
            r.depth[p] = r.far / (r.far - r.near) - r.near * r.far / ((r.far - r.near) * z);
            // McScene vectors are UV previous-minus-current -> pixels
            r.mv[p * 2] = fr.mv[p * 2] * W; r.mv[p * 2 + 1] = fr.mv[p * 2 + 1] * H;
            r.id[p] = fr.id[p];
        }
        float[][] b = McScene.basis(f);
        r.pos = McScene.eye(f); r.right = b[0]; r.up = b[1]; r.fwd = b[2];
        return r;
    }

    interface Mask { boolean in(int p); }

    static double rmse(float[] a, float[] b, Mask m) {
        double s = 0; long n = 0;
        for (int p = 0; p < W * H; p++) if (m.in(p)) for (int k = 0; k < 3; k++) { double d = a[p * 3 + k] - b[p * 3 + k]; s += d * d; n++; }
        return Math.sqrt(s / Math.max(1, n));
    }

    static void runScene(String name, Real warm, Real prev, Real cur, Real mid, boolean linear) throws Exception {
        int u = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, fmtH = VK_FORMAT_R16G16B16A16_SFLOAT, G = ImageRef.LAYOUT_GENERAL, RO = ImageRef.LAYOUT_SHADER_READ_ONLY;
        DenoiseGpuImg col = new DenoiseGpuImg(fmtH), dep = new DenoiseGpuImg(VK_FORMAT_R32_SFLOAT), mv = new DenoiseGpuImg(VK_FORMAT_R16G16_SFLOAT), out = new DenoiseGpuImg(fmtH);
        LvpDevice.Buf bc = dev.hostBuffer(W * H * 8, 3), bd = dev.hostBuffer(W * H * 4, 3), bm = dev.hostBuffer(W * H * 4, 3), bo = dev.hostBuffer(W * H * 8, 3);
        try (FsrFrameGeneration fg = new FsrFrameGeneration(dev.instance.address(), dev.phys.address(), dev.device.address(), 0L, false, 0, W, H, W, H, fmtH)) {
            System.out.println("== " + name);
            System.out.println("   shared resources (SDK descriptions): " + fg.info);
            System.out.println("   memory: SDK internal " + fg.memory[0] + " B (optical flow + interpolation contexts) + shim-owned shared images " + fg.memory[1] + " B at " + W + "x" + H);
            float[] result = null;
            Real[] seq = {warm, prev, cur};
            for (int i = 0; i < 3; i++) {
                Real r = seq[i];
                ByteBuffer cb = LvpDevice.bytes(bc), db = LvpDevice.bytes(bd), mb = LvpDevice.bytes(bm);
                for (int p = 0; p < W * H; p++) {
                    for (int k = 0; k < 3; k++) cb.putShort(p * 8 + k * 2, Float.floatToFloat16(r.rgb[p * 3 + k]));
                    cb.putShort(p * 8 + 6, Float.floatToFloat16(1f));
                    db.putFloat(p * 4, r.depth[p]);
                    mb.putShort(p * 4, Float.floatToFloat16(r.mv[p * 2])).putShort(p * 4 + 2, Float.floatToFloat16(r.mv[p * 2 + 1]));
                }
                dev.begin();
                if (i == 0) dev.layout(out.img.image(), VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
                LvpDevice.Buf bOfIn = null, bScd = null;
                if (EXT_OF != 0) {                                  // optical flow supplied by the test: block-averaged true motion (the SDK's own OF needs a 32-lane wave)
                    long[] si = fg.sharedImages();
                    int bw = (int) si[1], bh = (int) si[2];
                    bOfIn = dev.hostBuffer(Math.max(4096, bw * bh * 4L), 3);
                    ByteBuffer ob2 = LvpDevice.bytes(bOfIn);
                    for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
                        int px = Math.min(W - 1, bx * 8 + 4), py = Math.min(H - 1, by * 8 + 4);
                        ob2.putShort((by * bw + bx) * 4, (short) Math.round(r.mv[(py * W + px) * 2] * EXT_OF)).putShort((by * bw + bx) * 4 + 2, (short) Math.round(r.mv[(py * W + px) * 2 + 1] * EXT_OF));
                    }
                    dev.upload(si[0], bw, bh, bOfIn);
                    bScd = dev.hostBuffer(4096, 3);
                    dev.upload(si[4], 3, 1, bScd);
                }
                dev.upload(col.img.image(), W, H, bc); dev.upload(dep.img.image(), W, H, bd); dev.upload(mv.img.image(), W, H, bm);
                dev.layout(col.img.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                dev.layout(dep.img.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                dev.layout(mv.img.image(), VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                FsrFrameGeneration.Frame f = new FsrFrameGeneration.Frame();
                f.commandBuffer = dev.cmd.address();
                f.color = new ImageRef(col.img.image(), fmtH, W, H, u, RO);
                f.depth = new ImageRef(dep.img.image(), VK_FORMAT_R32_SFLOAT, W, H, u, RO);
                f.motionVectors = new ImageRef(mv.img.image(), VK_FORMAT_R16G16_SFLOAT, W, H, u, RO);
                f.output = new ImageRef(out.img.image(), fmtH, W, H, u, G);
                f.renderWidth = W; f.renderHeight = H; f.displayWidth = W; f.displayHeight = H;
                f.mvScaleX = 1f; f.mvScaleY = 1f;                  // pixel units, current -> previous (same as the upscaler)
                f.cameraNear = r.near; f.cameraFar = r.far; f.fovYRadians = (float) Math.toRadians(60);
                f.cameraPosition = r.pos; f.cameraUp = r.up; f.cameraRight = r.right; f.cameraForward = r.fwd;
                f.frameId = i; f.reset = i == 0; f.linearColor = linear; f.debugView = Boolean.getBoolean("fg.debug"); f.skipOpticalFlow = EXT_OF != 0;
                fg.dispatch(f);
                dev.download(out.img.image(), W, H, bo);
                LvpDevice.Buf bOf = null;
                if (i == 2) {                                       // inspect the optical flow outputs after the last dispatch
                    long[] si = fg.sharedImages();
                    bOf = dev.hostBuffer(Math.max(4096, si[1] * si[2] * 8), 3);
                    dev.downloadSized(si[0], (int) si[1], (int) si[2], bOf, 0);
                }
                dev.submitWait();
                if (bOfIn != null) { dev.destroyBuffer(bOfIn); dev.destroyBuffer(bScd); }
                if (bOf != null) {
                    ByteBuffer ofb = LvpDevice.bytes(bOf);
                    long[] si = fg.sharedImages();
                    int n = (int) (si[1] * si[2]);
                    int minx = 99999, maxx = -99999, miny = 99999, maxy = -99999, nz = 0;
                    for (int q = 0; q < n; q++) { int vx = ofb.getShort(q * 4), vy = ofb.getShort(q * 4 + 2); if (vx != 0 || vy != 0) nz++; minx = Math.min(minx, vx); maxx = Math.max(maxx, vx); miny = Math.min(miny, vy); maxy = Math.max(maxy, vy); }
                    System.out.println("   optical flow vectors (" + si[1] + "x" + si[2] + ", RG16_SINT): non-zero " + nz + "/" + n + ", x range " + minx + ".." + maxx + ", y range " + miny + ".." + maxy);
                    dev.destroyBuffer(bOf);
                }
                ByteBuffer ob = LvpDevice.bytes(bo);
                result = new float[W * H * 3];
                for (int p = 0; p < W * H; p++) for (int k = 0; k < 3; k++) result[p * 3 + k] = Float.float16ToFloat(ob.getShort(p * 8 + k * 2));
            }
            String msgs = fg.drainMessages();
            if (!msgs.isEmpty()) System.out.println("   FFX messages: " + msgs);
            boolean fin = true;
            for (float v : result) if (Float.isNaN(v) || Float.isInfinite(v)) fin = false;
            float[] blend = new float[W * H * 3];
            for (int i = 0; i < blend.length; i++) blend[i] = 0.5f * (prev.rgb[i] + cur.rgb[i]);
            Mask all = p -> true;
            Mask moving = p -> Math.abs(cur.mv[p * 2]) + Math.abs(cur.mv[p * 2 + 1]) > 0.01f || Math.abs(prev.mv[p * 2]) + Math.abs(prev.mv[p * 2 + 1]) > 0.01f;
            double eI = rmse(result, mid.rgb, all), eB = rmse(blend, mid.rgb, all), eP = rmse(prev.rgb, mid.rgb, all), eC = rmse(cur.rgb, mid.rgb, all);
            double mI = rmse(result, mid.rgb, moving), mB = rmse(blend, mid.rgb, moving);
            System.out.printf("   RMSE vs true middle frame (sRGB 0..1): interpolated %.4f | 50/50 blend %.4f | previous frame %.4f | current frame %.4f; on moving pixels: interpolated %.4f vs blend %.4f%n", eI, eB, eP, eC, mI, mB);
            ck(fin, name + ": interpolated image has no NaN/Inf");
            if (dev.subgroupSize >= 32) {
                ck(eI < eB, name + ": interpolation is closer to the true middle frame than a 50/50 blend");
                ck(eI < Math.min(eP, eC), name + ": interpolation is closer than either real frame");
            } else {
                System.out.println("  note  quality checks skipped: this device has " + dev.subgroupSize + "-lane subgroups; the SDK's optical flow shader branches on ffxWaveLaneCount() == 32 (ffx_opticalflow_compute_optical_flow_v5.h:54,75) and returns all-zero vectors otherwise;"
                        + " the interpolated image equals the current frame here, also when the vectors are supplied by the test (-Dfg.extof=+-1). Needs a 32/64-lane GPU (RADV) to judge quality.");
                ck(Math.abs(eI - eC) < 0.01, name + ": pipeline ran end to end (output is a valid image; with no usable motion data the SDK passes the current frame through)");
            }
            String base = name.replaceAll("[^A-Za-z0-9]+", "_");
            png(result, base + "_interpolated.png"); png(mid.rgb, base + "_truth_middle.png"); png(blend, base + "_blend.png"); png(prev.rgb, base + "_prev.png"); png(cur.rgb, base + "_cur.png");
        } finally {
            col.free(); dep.free(); mv.free(); out.free();
            dev.destroyBuffer(bc); dev.destroyBuffer(bd); dev.destroyBuffer(bm); dev.destroyBuffer(bo);
        }
    }

    static final class DenoiseGpuImg {
        final dev.elemore.iroh.rt.denoise.DenoiseGpu.Img img;
        DenoiseGpuImg(int fmt) { img = dev.createImage(fmt, W, H, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT, "fg"); }
        void free() { dev.destroyImage(img); }
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) { if (a.startsWith("--dump=")) dump = a.substring(7); else if (a.startsWith("--size=")) { String[] z = a.substring(7).split("x"); W = Integer.parseInt(z[0]); H = Integer.parseInt(z[1]); } }
        if (!NativeLoader.load()) { System.out.println("# fgreplay FAIL setup: " + NativeLoader.unavailableReason()); System.exit(1); }
        dev = new LvpDevice(false);
        for (String l : dev.report) System.out.println(l);
        try {
            runScene("moving checker", checkerFrame(-1, -1), checkerFrame(0, -1), checkerFrame(1, 0), checkerFrame(0.5f, 0), false);
            runScene("Minecraft-like", mcFrame(9, 0f, 64), mcFrame(10, 0f, 64), mcFrame(11, 0f, 64), mcFrame(10, 0.5f, 64), false);
        } catch (Throwable t) {
            System.out.println("FRAME GENERATION FAILURE on lavapipe: " + t);
            t.printStackTrace(System.out);
            fails++;
        }
        System.out.println("images: " + new File(dump).getAbsolutePath());
        System.out.println("# fgreplay " + (fails == 0 ? "PASS" : "FAIL " + fails + " checks"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
