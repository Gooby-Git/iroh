package dev.elemore.iroh.rt.denoise.nrd;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Parsed nrd::InstanceDesc (as serialised by the shim). */
public final class NrdInstanceDesc {
    public record Tex(int nrdFormat, int downsample) {
        public long bytes(int w, int h) {
            long pw = (w + downsample - 1) / downsample, ph = (h + downsample - 1) / downsample;
            return pw * ph * NrdFormats.bytesPerPixel(nrdFormat);
        }
    }

    public record Pipeline(boolean hasConstants, int spirvSize, int rangesNum, int[] rangeType, int[] rangeCount, String identifier) {}

    public int cbSpace, resSpace, cbReg, samplerBase, resBase, cbMaxSize;
    public int[] samplers;
    public int perSetTex, perSetStor, totalTex, totalStor, setsMax;
    public Tex[] permanent, transient_;
    public Pipeline[] pipelines;

    public static NrdInstanceDesc parse(ByteBuffer src) {
        ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        b.rewind();
        NrdInstanceDesc d = new NrdInstanceDesc();
        d.cbSpace = b.getInt(); d.resSpace = b.getInt(); d.cbReg = b.getInt(); d.samplerBase = b.getInt(); d.resBase = b.getInt(); d.cbMaxSize = b.getInt();
        d.samplers = new int[b.getInt()];
        for (int i = 0; i < d.samplers.length; i++) d.samplers[i] = b.getInt();
        int pipes = b.getInt(), perm = b.getInt(), trans = b.getInt();
        d.perSetTex = b.getInt(); d.perSetStor = b.getInt(); d.totalTex = b.getInt(); d.totalStor = b.getInt(); d.setsMax = b.getInt();
        d.permanent = new Tex[perm];
        for (int i = 0; i < perm; i++) d.permanent[i] = new Tex(b.getInt(), b.getInt());
        d.transient_ = new Tex[trans];
        for (int i = 0; i < trans; i++) d.transient_[i] = new Tex(b.getInt(), b.getInt());
        d.pipelines = new Pipeline[pipes];
        for (int i = 0; i < pipes; i++) {
            boolean cb = b.getInt() != 0;
            int size = b.getInt(), rn = b.getInt();
            int[] type = new int[2], count = new int[2];
            for (int k = 0; k < 2; k++) { type[k] = b.getInt(); count[k] = b.getInt(); }
            int len = b.getInt();
            byte[] s = new byte[len];
            b.get(s);
            while ((b.position() & 3) != 0) b.get();
            d.pipelines[i] = new Pipeline(cb, size, rn, type, count, new String(s, StandardCharsets.UTF_8));
        }
        return d;
    }

    public long poolBytes(Tex[] pool, int w, int h) {
        long t = 0;
        for (Tex x : pool) t += x.bytes(w, h);
        return t;
    }
}
