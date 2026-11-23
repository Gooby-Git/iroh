package dev.elemore.iroh.upscaletest;

import dev.elemore.iroh.temporal.FsrSession;
import dev.elemore.iroh.upscale.ImageRef;
import dev.elemore.iroh.upscale.JitterSequence;
import dev.elemore.iroh.upscale.UpscaleInputs;
import dev.elemore.iroh.upscale.fsr.FsrUpscaler;
import dev.elemore.iroh.upscale.fsr.NativeLoader;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK12.*;

/**
 * Card P4-1b: the game's FSR adapter path on LAVAPIPE. {@link FsrSession} (the exact lifecycle code FsrAdapter runs in
 * game, with a Backend over this harness's headless device instead of Iroh's) drives FSR over fsrReplay's synthetic
 * scene with Iroh's formats: RGBA8 UNORM colour (the world image), D32 depth, RG16F motion vectors, RGBA8 UNORM display
 * output with STORAGE usage in GENERAL (the display scene colour when the upscale features are on). Checks:
 * budget refusal -> no context + reason (bilinear fallback in game), creation after clearFailure, memory counted from
 * memoryUsageBytes, the first dispatch of a context is a reset, a display resize recreates and the old context is closed
 * only when its deferred frame op runs (bytes leave the count then), a flag change (NO_FP16 off) recreates, quality vs
 * bilinear with RGBA8 output, sharpening changes the picture. Output PNG build/p4-1b/fsr_adapter_rgba8.png.
 * {@code ./gradlew fsrAdapterReplay}; last line {@code # fsradapter PASS|FAIL}.
 */
public final class FsrAdapterReplay {
    static int fails;

    static void ck(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        if (!NativeLoader.load()) {
            System.out.println("# fsradapter FAIL setup: " + NativeLoader.unavailableReason());
            System.exit(1);
        }
        StringBuilder rep = new StringBuilder();
        // V-0: the fp16 context below (flag change check) needs int16/float16/16-bit storage ENABLED, as FsrAdapter requires in
        // game (DeviceCaps); a device without them made VUID-VkShaderModuleCreateInfo-pCode-08740 (Int16) x5. Contexts use NO_FP16 otherwise.
        FsrReplay.init(true, rep);
        final int rw = 320, rh = 180, dw = 640, dh = 360;

        long[] budget = {1L << 20};            // 1 MiB: refused
        long[] counted = {0};
        List<Runnable> deferred = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        FsrSession session = new FsrSession(new FsrSession.Backend() {
            @Override public FsrUpscaler create(int flags, int shim, int w, int h, int d1, int d2) {
                return FsrUpscaler.create(FsrReplay.instance.address(), FsrReplay.phys.address(), FsrReplay.device.address(), 0L, flags, shim, w, h, d1, d2);
            }
            @Override public boolean allow(long bytes) { return bytes <= budget[0]; }
            @Override public void account(long bytes) { counted[0] += bytes; }
            @Override public void destroyLater(Runnable r) { deferred.add(r); }
            @Override public void log(String msg) { logs.add(msg); }
        });
        int flags = FsrUpscaler.DEPTH_INFINITE | FsrUpscaler.AUTO_EXPOSURE;
        int shim = FsrUpscaler.NO_FP16;

        ck(session.ensure(flags, shim, rw, rh, dw, dh) == null && session.failed() && logs.size() == 1, "budget refusal: no context, one log line: " + logs);
        ck(session.ensure(flags, shim, rw, rh, dw, dh) == null && logs.size() == 1, "stays failed (no retry storm) until clearFailure");
        session.clearFailure();
        budget[0] = Long.MAX_VALUE;
        FsrUpscaler up = session.ensure(flags, shim, rw, rh, dw, dh);
        long[] mem = up == null ? new long[]{0, 0} : up.memoryUsageBytes();
        ck(up != null && session.creates() == 1, "created after clearFailure");
        ck(counted[0] == mem[0] + mem[1] && counted[0] > 0, "memory counted from memoryUsageBytes: " + counted[0] + " B");
        ck(session.ensure(flags, shim, rw, rh, dw, dh) == up && session.creates() == 1, "same parameters: same context");

        // frames through the session, Iroh formats
        FsrReplay.Img color = FsrReplay.image(VK_FORMAT_R8G8B8A8_UNORM, rw, rh, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        FsrReplay.Img depth = FsrReplay.image(VK_FORMAT_D32_SFLOAT, rw, rh, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
        FsrReplay.Img mvI = FsrReplay.image(VK_FORMAT_R16G16_SFLOAT, rw, rh, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        FsrReplay.Img out = FsrReplay.image(VK_FORMAT_R8G8B8A8_UNORM, dw, dh, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        FsrReplay.Buf bc = FsrReplay.buffer((long) rw * rh * 4), bd = FsrReplay.buffer((long) rw * rh * 4), bm = FsrReplay.buffer((long) rw * rh * 4), bo = FsrReplay.buffer((long) dw * dh * 4);
        JitterSequence js = new JitterSequence(rw, dw);
        float[] c = new float[rw * rh * 4], d = new float[rw * rh], m = new float[rw * rh * 2];
        float[] last = null, sharpLast = null;
        boolean firstReset = false, laterNoReset = true;
        boolean outInit = false;
        int NF = FsrReplay.NF;
        for (int pass = 0; pass < 2; pass++) {
            for (int t = 0; t < NF; t++) {
                float[] j = js.offset(t);
                FsrReplay.SYNTH.render(t, j[0], j[1], c, d, m);
                ByteBuffer cb = MemoryUtil.memByteBuffer(bc.ptr(), (int) bc.size()).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer db = MemoryUtil.memByteBuffer(bd.ptr(), (int) bd.size()).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer mb = MemoryUtil.memByteBuffer(bm.ptr(), (int) bm.size()).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < rw * rh; i++) {
                    int px = 0;
                    for (int k = 0; k < 4; k++) px |= FsrReplay.clamp(k == 3 ? 1f : c[i * 4 + k]) << (8 * k);
                    cb.putInt(i * 4, px);
                    db.putFloat(i * 4, d[i]);
                    // Iroh's convention: UV deltas, scale = render size
                    mb.putShort(i * 4, Float.floatToFloat16(m[i * 2] / rw));
                    mb.putShort(i * 4 + 2, Float.floatToFloat16(m[i * 2 + 1] / rh));
                }
                FsrReplay.begin();
                FsrReplay.upload(color, bc, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                FsrReplay.upload(depth, bd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                FsrReplay.upload(mvI, bm, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                if (!outInit) { FsrReplay.layout(out, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL); outInit = true; }
                FsrUpscaler u = session.ensure(flags, shim, rw, rh, dw, dh);
                float sharp = pass == 1 ? 0.8f : 0f;
                UpscaleInputs in = FsrSession.inputs(FsrReplay.cmd.address(),
                        new ImageRef(color.image(), color.format(), rw, rh, color.usage(), ImageRef.LAYOUT_SHADER_READ_ONLY),
                        new ImageRef(depth.image(), depth.format(), rw, rh, depth.usage(), ImageRef.LAYOUT_SHADER_READ_ONLY),
                        new ImageRef(mvI.image(), mvI.format(), rw, rh, mvI.usage(), ImageRef.LAYOUT_SHADER_READ_ONLY),
                        ImageRef.NONE, ImageRef.NONE,
                        new ImageRef(out.image(), out.format(), dw, dh, out.usage(), ImageRef.LAYOUT_GENERAL),
                        new FsrSession.Numbers(j[0], j[1], rw, rh, rw, rh, dw, dh, sharp, 16.6f, 0.05f, Float.MAX_VALUE, (float) Math.toRadians(70), false));
                session.dispatch(in);
                if (pass == 0 && t == 0) firstReset = in.reset;
                else if (pass == 0) laterNoReset &= !in.reset;
                FsrReplay.download(out, bo);
                FsrReplay.submitWait();
                if (t == NF - 1) {
                    float[] o = new float[dw * dh * 4];
                    ByteBuffer ob = MemoryUtil.memByteBuffer(bo.ptr(), (int) bo.size());
                    for (int i = 0; i < o.length; i++) o[i] = (ob.get(i) & 0xFF) / 255f;
                    if (pass == 0) last = o; else sharpLast = o;
                }
            }
            if (pass == 0) {
                // a display resize recreates the context; the old one is closed only when its frame op runs
                long before = counted[0];
                FsrUpscaler old = up;
                FsrUpscaler up2 = session.ensure(flags, shim, rw, rh, dw - 64, dh - 36);
                ck(up2 != null && up2 != old && session.creates() == 2, "display resize recreates the context");
                ck(deferred.size() == 1 && counted[0] > before, "old context still alive (deferred), both counted until the frame op runs");
                deferred.remove(0).run();
                long[] m2 = up2.memoryUsageBytes();
                ck(counted[0] == m2[0] + m2[1], "after the frame op only the new context is counted");
                FsrUpscaler up3 = session.ensure(flags, 0, rw, rh, dw - 64, dh - 36);
                ck(up3 != up2 && session.creates() == 3, "flag change (fp16 permutations allowed) recreates");
                deferred.forEach(Runnable::run);
                deferred.clear();
                FsrUpscaler back = session.ensure(flags, shim, rw, rh, dw, dh);
                deferred.forEach(Runnable::run);
                deferred.clear();
                up = back;
            }
        }
        ck(firstReset && laterNoReset, "first dispatch of a context is a reset, later ones are not");
        String msgs = session.drainMessages();
        if (!msgs.isEmpty()) System.out.println("FFX messages:\n" + msgs);

        float[] gt = FsrReplay.groundTruth(FsrReplay.LT);
        float[] rend = new float[rw * rh * 4];
        float[] jl = JitterSequence.offset(FsrReplay.LT, js.phaseCount);
        FsrReplay.SYNTH.render(FsrReplay.LT, jl[0], jl[1], rend, new float[rw * rh], new float[rw * rh * 2]);
        float[] bil = FsrReplay.bilinear(rend, rw, rh, dw, dh);
        double eF = FsrReplay.rmse(last, gt, dw, dh, 0, 0, dw, dh), eB = FsrReplay.rmse(bil, gt, dw, dh, 0, 0, dw, dh);
        double eS = FsrReplay.rmse(sharpLast, last, dw, dh, 0, 0, dw, dh);
        System.out.printf("RGBA8 path: RMSE FSR %.4f bilinear %.4f; sharpening 0.8 vs off differs by RMSE %.4f%n", eF, eB, eS);
        ck(FsrReplay.finite(last), "output finite");
        ck(eF < eB, "FSR through the session beats bilinear with Iroh's RGBA8 colour / output");
        ck(eS > 0.002, "sharpening changes the picture");
        new File("build/p4-1b").mkdirs();
        FsrReplay.png(last, dw, dh, new File("build/p4-1b/fsr_adapter_rgba8.png"));
        session.closeNow();
        deferred.forEach(Runnable::run);
        ck(counted[0] == 0, "after close nothing is counted");
        System.out.println("# fsradapter " + (fails == 0 ? "PASS" : "FAIL " + fails));
        System.exit(fails == 0 ? 0 : 1);
    }
}
