package dev.elemore.iroh.upscale.fsr;

import dev.elemore.iroh.upscale.ImageRef;
import dev.elemore.iroh.upscale.UpscaleInputs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Writer for IrohFsrDispatch (296 bytes, little endian); offsets mirror iroh_fsr_jni.cpp, checked by NativeLoader via structSize(). */
final class FsrDispatchStruct {
    static final int SIZE = 296;

    static ByteBuffer allocate() {
        return ByteBuffer.allocateDirect(SIZE).order(ByteOrder.LITTLE_ENDIAN);
    }

    static void write(ByteBuffer b, UpscaleInputs in) {
        b.clear();
        b.putLong(0, in.commandBuffer);
        image(b, 8, in.color);
        image(b, 40, in.depth);
        image(b, 72, in.motionVectors);
        image(b, 104, in.exposure);
        image(b, 136, in.reactive);
        image(b, 168, in.transparency);
        image(b, 200, in.output);
        b.putFloat(232, in.jitterX).putFloat(236, in.jitterY).putFloat(240, in.motionVectorScaleX).putFloat(244, in.motionVectorScaleY);
        b.putInt(248, in.renderWidth).putInt(252, in.renderHeight).putInt(256, in.displayWidth).putInt(260, in.displayHeight);
        b.putFloat(264, in.sharpness).putFloat(268, in.frameTimeMs).putFloat(272, in.preExposure).putFloat(276, in.cameraNear)
                .putFloat(280, in.cameraFar).putFloat(284, in.cameraFovYRadians).putFloat(288, in.viewSpaceToMeters);
        b.putInt(292, (in.enableSharpening ? 1 : 0) | (in.reset ? 2 : 0) | (in.debugView ? 4 : 0));
    }

    private static void image(ByteBuffer b, int o, ImageRef r) {
        b.putLong(o, r.image()).putInt(o + 8, r.format()).putInt(o + 12, r.width()).putInt(o + 16, r.height())
                .putInt(o + 20, r.usage()).putInt(o + 24, r.layout()).putInt(o + 28, 0);
    }
}
