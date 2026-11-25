package dev.elemore.iroh.upscaletest;

import dev.elemore.iroh.upscale.ImageRef;
import dev.elemore.iroh.upscale.UpscaleInputs;
import dev.elemore.iroh.upscale.fsr.FsrException;
import dev.elemore.iroh.upscale.fsr.FsrNative;
import dev.elemore.iroh.upscale.fsr.FsrTuning;
import dev.elemore.iroh.upscale.fsr.FsrTuning.Key;
import dev.elemore.iroh.upscale.fsr.FsrUpscaler;
import dev.elemore.iroh.upscale.fsr.NativeLoader;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static dev.elemore.iroh.upscaletest.FsrReplay.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Card FSR-Q1: the five FfxFsr3UpscalerConfigureUpscaleKey constants through ffxFsr3UpscalerSetConstant on lavapipe.
 * Checks defaults at create, SDK clamping against {@link FsrTuning#clamp} (below min, above max, in range) for both the
 * 32-bit and the 16-bit permutations, a dispatch after every key change (finite output, no FFX error), an unknown key,
 * and that the debug checker's messages reach drainMessages (frameTimeDelta below 1 ms, cameraNear below 0.075).
 * Run: ./gradlew fsrConstantsTest ; last line "# fsrconstants PASS|FAIL ...".
 */
public final class FsrConstantsTest {
    static final int RW = 96, RH = 54, DW = 192, DH = 108;

    public static void main(String[] args) {
        StringBuilder rep = new StringBuilder();
        try {
            if (!NativeLoader.load()) throw new IllegalStateException("libiroh_fsr unavailable: " + NativeLoader.unavailableReason());
            init(true, rep);
        } catch (Throwable t) {
            System.out.println(rep);
            System.out.println("# fsrconstants FAIL setup: " + t);
            System.exit(1);
        }
        System.out.println("native: " + FsrNative.version());
        ck(FsrNative.version().contains("3.1.6"), "version string reports 3.1.6");
        for (boolean fp16 : new boolean[]{false, true}) {
            try {
                run(fp16);
            } catch (Throwable t) {
                t.printStackTrace(System.out);
                ck(false, "run fp16=" + fp16 + " threw " + t);
            }
        }
        System.out.println(fails == 0 ? "# fsrconstants PASS" : "# fsrconstants FAIL " + fails + " checks");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void run(boolean want16) {
        frame = 0;   // fresh images per run: the output needs its UNDEFINED -> GENERAL transition
        System.out.println("== permutations: " + (want16 ? "fp16 (if the device has it)" : "32-bit"));
        Img color = image(VK_FORMAT_R16G16B16A16_SFLOAT, RW, RH, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        Img depth = image(VK_FORMAT_D32_SFLOAT, RW, RH, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_DEPTH_BIT);
        Img mv = image(VK_FORMAT_R16G16_SFLOAT, RW, RH, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        Img out = image(VK_FORMAT_R16G16B16A16_SFLOAT, DW, DH, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, VK_IMAGE_ASPECT_COLOR_BIT);
        Buf bc = buffer((long) RW * RH * 8), bd = buffer((long) RW * RH * 4), bm = buffer((long) RW * RH * 4), bo = buffer((long) DW * DH * 8);
        ByteBuffer cb = MemoryUtil.memByteBuffer(bc.ptr(), (int) bc.size()).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer db = MemoryUtil.memByteBuffer(bd.ptr(), (int) bd.size()).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer mb = MemoryUtil.memByteBuffer(bm.ptr(), (int) bm.size()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < RW * RH; i++) {
            float g = 0.25f + 0.5f * ((i % RW) / (float) RW);
            for (int c = 0; c < 4; c++) cb.putShort((i * 4 + c) * 2, Float.floatToFloat16(c == 3 ? 1f : g));
            db.putFloat(i * 4, 0.5f);
            mb.putInt(i * 4, 0);
        }
        int shim = want16 ? 0 : FsrUpscaler.NO_FP16;
        try (FsrUpscaler up = FsrUpscaler.create(instance.address(), phys.address(), device.address(), 0L,
                FsrUpscaler.AUTO_EXPOSURE | FsrUpscaler.DEBUG_CHECKING | FsrUpscaler.DEPTH_INFINITE, shim, RW, RH, DW, DH)) {
            // defaults straight after create
            float[] k0 = up.constants();
            for (Key k : Key.values())
                ck(Math.abs(k0[k.id] - k.def) < 1e-6f, "default " + k + " = " + k.def + " (got " + k0[k.id] + ")");
            // clamping per key: below min, in range, above max
            for (Key k : Key.values()) {
                float lo = Float.isInfinite(k.min) ? -1e6f : k.min - 5f;
                float hi = Float.isInfinite(k.max) ? 1e6f : k.max + 5f;
                float mid = Float.isInfinite(k.max) ? 2.5f : (k.min + k.max) / 2f + 0.01f;
                for (float v : new float[]{lo, k.min, mid, hi}) {
                    float got = up.setConstant(k, v);
                    ck(Math.abs(got - FsrTuning.clamp(k, v)) <= 1e-6f * Math.max(1f, Math.abs(got)),
                            k + " set " + v + " -> stored " + got + " expected " + FsrTuning.clamp(k, v));
                    dispatch(up, color, depth, mv, out, bc, bd, bm, bo, 16.6f, 0.1f, v == lo);
                }
                up.setConstant(k, k.def);
            }
            boolean untouched = true;
            float[] k1 = up.constants();
            for (Key k : Key.values()) untouched &= Math.abs(k1[k.id] - k.def) < 1e-6f;
            ck(untouched, "after restoring every key the constants equal the defaults");
            // a whole tuning set at once
            FsrTuning t = new FsrTuning().set(Key.VELOCITY_FACTOR, 0f).set(Key.REACTIVENESS_SCALE, 2f)
                    .set(Key.SHADING_CHANGE_SCALE, 1.5f).set(Key.ACCUMULATION_ADDED_PER_FRAME, 0.2f).set(Key.MIN_DISOCCLUSION_ACCUMULATION, 0.1f);
            up.apply(t);
            float[] k2 = up.constants();
            for (Key k : Key.values()) ck(Math.abs(k2[k.id] - t.get(k)) < 1e-6f, "apply(tuning) " + k + " = " + t.get(k));
            dispatch(up, color, depth, mv, out, bc, bd, bm, bo, 16.6f, 0.1f, false);
            // unknown key
            boolean threw = false;
            try { FsrNative.setConstant(handle(up), 9, 1f); } catch (FsrException e) { threw = true; }
            ck(threw, "unknown configure key is rejected");
            // debug checker -> drainMessages
            up.drainMessages();
            dispatch(up, color, depth, mv, out, bc, bd, bm, bo, 0.5f, 0.05f, false);
            String m = up.drainMessages();
            System.out.println("debug checker output: " + m.replace("\n", " | "));
            ck(m.contains("frameTimeDelta"), "debug checker reports frameTimeDelta < 1 ms through drainMessages");
            ck(m.contains("cameraNear value is very low"), "debug checker reports cameraNear 0.05 < 0.075");
            up.drainMessages();
            dispatch(up, color, depth, mv, out, bc, bd, bm, bo, 16.6f, 0.1f, false);
            String clean = up.drainMessages();
            ck(!clean.contains("FFX error"), "a sane dispatch raises no FFX error (" + clean.replace("\n", " | ") + ")");
        }
        vkDeviceWaitIdle(device);
    }

    static long handle(FsrUpscaler up) {
        try {
            var f = FsrUpscaler.class.getDeclaredField("ctx");
            f.setAccessible(true);
            return f.getLong(up);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static int frame;

    static void dispatch(FsrUpscaler up, Img color, Img depth, Img mv, Img out, Buf bc, Buf bd, Buf bm, Buf bo, float frameMs, float near, boolean quiet) {
        begin();
        upload(color, bc, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        upload(depth, bd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        upload(mv, bm, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        if (frame == 0) layout(out, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        int cu = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        UpscaleInputs in = new UpscaleInputs();
        in.commandBuffer = cmd.address();
        in.color = new ImageRef(color.image(), color.format(), RW, RH, cu, ImageRef.LAYOUT_SHADER_READ_ONLY);
        in.depth = new ImageRef(depth.image(), depth.format(), RW, RH, cu, ImageRef.LAYOUT_SHADER_READ_ONLY);
        in.motionVectors = new ImageRef(mv.image(), mv.format(), RW, RH, cu, ImageRef.LAYOUT_SHADER_READ_ONLY);
        in.output = new ImageRef(out.image(), out.format(), DW, DH, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT, ImageRef.LAYOUT_GENERAL);
        in.motionVectorScaleX = RW; in.motionVectorScaleY = RH;
        in.renderWidth = RW; in.renderHeight = RH; in.displayWidth = DW; in.displayHeight = DH;
        in.frameTimeMs = frameMs; in.cameraNear = near; in.cameraFar = Float.MAX_VALUE; in.cameraFovYRadians = (float) Math.toRadians(60);
        in.reset = frame == 0;
        up.dispatch(in);
        download(out, bo);
        submitWait();
        ByteBuffer ob = MemoryUtil.memByteBuffer(bo.ptr(), (int) bo.size()).order(ByteOrder.LITTLE_ENDIAN);
        boolean finite = true;
        for (int i = 0; i < DW * DH * 4; i++) finite &= Float.isFinite(Float.float16ToFloat(ob.getShort(i * 2)));
        frame++;
        if (!quiet) ck(finite, "dispatch #" + frame + " output finite");
        else if (!finite) ck(false, "dispatch #" + frame + " output finite");
    }
}
