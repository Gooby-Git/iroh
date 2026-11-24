package dev.elemore.iroh.apitest;

import dev.elemore.iroh.api.*;
import dev.elemore.iroh.gpu.GpuCore;
import dev.elemore.iroh.gpu.GpuDevice;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * api 4 (card V-M1) offline: every entry point's argument validation, owner accounting and refusal, free-on-owner-death
 * and free-all (world unload), hazard barriers from declared reads/writes, the render-pass rules and the indirect-count
 * fallback, on a recording device (no Vulkan). GLSL compiles through shaderc for real. The lavapipe half is apiReplay.
 */
final class ApiV4Checks {
    private ApiV4Checks() {}

    /** Records calls; objects are strings. */
    static final class Rec implements GpuDevice {
        final List<String> log = new ArrayList<>();
        boolean indirectCount = true, inPass;
        long cap = 1000L << 20;
        int destroyed;

        @Override public String name() { return "rec"; }
        @Override public boolean supportsDrawIndirectCount() { return indirectCount; }
        @Override public long capBytes() { return cap; }
        @Override public boolean canRecordOutsideMainPass() { return !inPass && !mainBound; }
        @Override public Object createBuffer(long bytes, int usage) { log.add("buffer " + bytes); return "buf" + log.size(); }
        @Override public long bufferSize(Object b) { return 0; }
        @Override public void destroyBuffer(Object b) { destroyed++; log.add("free " + b); }
        @Override public void upload(Object b, long off, ByteBuffer src) { log.add("upload " + src.remaining()); }
        // UPLOAD-SYNC: like the game, an in-stream copy where no pass is bound
        @Override public boolean inFrameCopyPossible() { return !inPass && !mainBound; }
        @Override public void uploadInFrame(Object b, long off, ByteBuffer src) { log.add("upload " + src.remaining() + " in-frame"); }
        @Override public Object createComputePipeline(String n, ByteBuffer spv, int s, int t, int p) { log.add("compute " + n + " spv " + spv.remaining() + " tex " + t); return "cp" + log.size(); }
        @Override public Object createDrawPipeline(String n, ByteBuffer v, ByteBuffer f, int s, int t, int p, int c, int d, int cmp, boolean dw, int bl) {
            log.add("draw pipeline " + n + " blend " + bl);
            return "dp" + log.size();
        }
        @Override public void destroyPipeline(Object p) { destroyed++; log.add("free " + p); }
        @Override public Object createImage(String n, int w, int h, int f, boolean d) { log.add("image " + n + " " + w + "x" + h); return "img" + log.size(); }
        @Override public void destroyImage(Object i) { destroyed++; log.add("free " + i); }
        @Override public void barrier(int ss, int sa, int ds, int da) { log.add("barrier " + Integer.toHexString(ss) + "->" + Integer.toHexString(ds)); }
        @Override public void dispatch(Object p, Object[] b, Object[] t, ByteBuffer push, int x, int y, int z) { log.add("dispatch " + p + " " + x + texLog(t)); }
        @Override public void dispatchIndirect(Object p, Object[] b, Object[] t, ByteBuffer push, Object a, long off) { log.add("dispatchIndirect " + p + texLog(t)); }
        @Override public void draw(Object p, Object[] b, Object[] t, ByteBuffer push, int v, int i) { log.add("draw " + v + "x" + i + texLog(t)); }
        static String texLog(Object[] t) {
            if (t == null || t.length == 0) return "";
            StringBuilder sb = new StringBuilder(" tex");
            for (Object o : t) sb.append(' ').append(o instanceof External e ? "ext:" + e.key() : o);
            return sb.toString();
        }
        // api 4.1
        int mainColor = org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM, mainDepth = org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
        boolean mainBound;
        int tsCap = 16;
        @Override public Object createTexture(String n, int w, int h, int l, ByteBuffer t, boolean lin) { log.add("texture " + n + " " + w + "x" + h + " l" + l); return "tex" + log.size(); }
        @Override public void setStandIn(String k, Object t) { log.add("standIn " + k + " " + t); }
        @Override public void copyImage(Object a, Object b) { log.add("copy " + a + " " + b); }
        @Override public void uploadImage(Object i, ByteBuffer t) { log.add("uploadImage " + i); }
        @Override public void copyMainDepth(Object d) { log.add("mainDepth -> " + d); }
        @Override public void setMainStandIn(Object c, Object d) { log.add("mainStandIn"); }
        @Override public void beginMainRendering() { inPass = true; log.add("beginMain"); }
        @Override public void endMainRendering() { inPass = false; log.add("endMain"); }
        @Override public int mainColorFormat() { return mainColor; }
        @Override public int mainDepthFormat() { return mainDepth; }
        @Override public void runOutsideMainPass(Runnable r) { boolean was = mainBound; mainBound = false; log.add("suspend"); r.run(); log.add("resume"); mainBound = was; }
        @Override public int framesInFlight() { return 2; }
        int slot;
        @Override public int frameSlot() { return slot; }
        @Override public boolean resetTimestamps() { if (inPass || mainBound) return false; log.add("tsReset"); return true; }
        @Override public void writeTimestamp(int i) { log.add("ts " + i); }
        @Override public long[] readTimestamps(int s, int n) { long[] t = new long[n]; for (int i = 0; i < n; i++) t[i] = i * 1_000_000L; return t; }
        @Override public int timestampCapacity() { return tsCap; }
        @Override public void beginRendering(Object c, Object d, float[] cl, float cd, boolean clear) { inPass = true; log.add("begin"); }
        @Override public void endRendering() { inPass = false; log.add("end"); }
        @Override public void drawIndexedIndirectCount(Object p, Object[] b, Object[] t, ByteBuffer push, Object i, int it, Object c, long co, Object n, long no, int m, int s) { log.add("drawCount " + m); }
        @Override public void drawIndexedIndirect(Object p, Object[] b, Object[] t, ByteBuffer push, Object i, int it, Object c, long co, int m, int s) { log.add("drawFallback " + m); }
        @Override public ByteBuffer readback(Object i) { return null; }
        @Override public void flush() {}

        long count(String prefix) {
            return log.stream().filter(l -> l.startsWith(prefix)).count();
        }
    }

    static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    static boolean throwsIse(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }

    static final String CS = "#version 450\nlayout(local_size_x=1) in;\nlayout(std430,binding=0) buffer B{uint b[];};\nvoid main(){ b[0] = 1u; }\n";
    static final String VS = "#version 450\nlayout(std430,binding=0) readonly buffer B{vec4 q[];};\nvoid main(){ gl_Position = q[gl_VertexIndex]; }\n";
    static final String FS = "#version 450\nlayout(location=0) out vec4 o;\nvoid main(){ o = vec4(1); }\n";

    static void run(BiConsumer<String, Boolean> check) {
        Rec rec = new Rec();
        GpuCore core = GpuCore.install(rec);
        try {
            core.resize(800, 600);
            // ---- buffers: validation, accounting, refusal
            check.accept("buffer args: zero, unaligned, too big, bad usage, no owner refused",
                    throwsIae(() -> IrohBuffers.create("m", "b", 0, IrohBuffers.STORAGE)) && throwsIae(() -> IrohBuffers.create("m", "b", 6, IrohBuffers.STORAGE))
                            && throwsIae(() -> IrohBuffers.create("m", "b", 2L << 30, IrohBuffers.STORAGE)) && throwsIae(() -> IrohBuffers.create("m", "b", 16, 0))
                            && throwsIae(() -> IrohBuffers.create("m", "b", 16, 64)) && throwsIae(() -> IrohBuffers.create(null, "b", 16, IrohBuffers.STORAGE)));
            GpuBuffer a = IrohBuffers.create("vista", "a", 100L << 20, IrohBuffers.STORAGE);
            GpuBuffer b = IrohBuffers.create("vista", "b", 200L << 20, IrohBuffers.STORAGE | IrohBuffers.INDIRECT);
            check.accept("share = 40% of the device cap (400 MiB of 1000); owner holds 300 MiB", IrohBuffers.ownerShareBytes() == 400L << 20
                    && IrohBuffers.ownerBytes("vista") == 300L << 20);
            GpuBuffer refused = IrohBuffers.create("vista", "c", 101L << 20, IrohBuffers.STORAGE);
            check.accept("over the share: null + reason, nothing allocated (" + IrohBuffers.lastRefusal() + ")", refused == null
                    && IrohBuffers.lastRefusal().contains("share") && rec.count("buffer") == 2);
            check.accept("another owner has its own share", IrohBuffers.create("tempest", "deck", 300L << 20, IrohBuffers.STORAGE) != null);
            check.accept("upload range/alignment checked", throwsIae(() -> IrohBuffers.upload(a, 0, ByteBuffer.allocateDirect(6)))
                    && throwsIae(() -> IrohBuffers.upload(a, 2, ByteBuffer.allocateDirect(4)))
                    && throwsIae(() -> IrohBuffers.upload(a, (100L << 20) - 4, ByteBuffer.allocateDirect(8))));
            IrohBuffers.upload(a, 0, ByteBuffer.allocate(16)); // heap buffer: copied to direct
            check.accept("upload recorded in-stream outside a pass (heap buffers copied)", rec.count("upload 16 in-frame") == 1);

            // ---- compute: validation, barriers from declared access
            ComputePipelineHandle cp = IrohCompute.pipeline("vista", "c", CS, 1, 0);
            check.accept("compute pipeline compiled by shaderc and created", rec.count("compute c spv") == 1 && cp.storageBuffers() == 1);
            check.accept("compute GLSL errors are reported", throwsIae(() -> IrohCompute.pipeline("vista", "bad", "#version 450\nvoid main(){ x; }", 0, 0)));
            check.accept("dispatch args: binding count, group limits, push size",
                    throwsIae(() -> IrohCompute.dispatch(cp, new IrohCompute.Binding[0], null, 1, 1, 1))
                            && throwsIae(() -> IrohCompute.dispatch(cp, new IrohCompute.Binding[]{IrohCompute.Binding.readWrite(a)}, null, 0, 1, 1))
                            && throwsIae(() -> IrohCompute.dispatch(cp, new IrohCompute.Binding[]{IrohCompute.Binding.readWrite(a)}, ByteBuffer.allocateDirect(4), 1, 1, 1)));
            rec.log.clear();
            IrohCompute.dispatch(cp, new IrohCompute.Binding[]{IrohCompute.Binding.readWrite(a)}, null, 1, 1, 1); // after the upload: barrier
            IrohCompute.dispatch(cp, new IrohCompute.Binding[]{IrohCompute.Binding.readWrite(a)}, null, 1, 1, 1); // after a write: barrier
            IrohCompute.dispatch(cp, new IrohCompute.Binding[]{IrohCompute.Binding.read(b)}, null, 1, 1, 1);      // untouched: none
            check.accept("barriers: upload->compute and compute->compute on the same buffer, none for an untouched one (" + rec.log + ")",
                    rec.count("barrier") == 2 && rec.count("dispatch") == 3 && rec.log.get(0).equals("barrier 1000->800")
                            && rec.log.get(2).equals("barrier 800->800"));
            check.accept("dispatchIndirect: args need INDIRECT and a 12-byte range",
                    throwsIae(() -> IrohCompute.dispatchIndirect(cp, new IrohCompute.Binding[]{IrohCompute.Binding.read(b)}, null, a, 0))
                            && throwsIae(() -> IrohCompute.dispatchIndirect(cp, new IrohCompute.Binding[]{IrohCompute.Binding.read(a)}, null, b, (200L << 20) - 8)));

            // ---- targets and passes
            RenderTargetHandle col = IrohTargets.createScaled("vista", "col", IrohTargets.Format.RGBA8, 1f);
            RenderTargetHandle dep = IrohTargets.createScaled("vista", "dep", IrohTargets.Format.DEPTH32F, 0.5f);
            check.accept("scaled targets follow the window (800x600, half 400x300)", col.width() == 800 && col.height() == 600 && dep.width() == 400);
            core.resize(1000, 500);
            check.accept("resize recreates scaled targets (1000x500 / 500x250) and frees the old images", col.width() == 1000 && dep.height() == 250
                    && rec.count("free img") == 2);
            check.accept("pass rules: sizes must match, colour/depth kinds checked",
                    throwsIae(() -> IrohTargets.beginRendering(col, dep, true, null, 1f)) && throwsIae(() -> IrohTargets.beginRendering(dep, null, true, null, 1f)));
            RenderTargetHandle dep2 = IrohTargets.createScaled("vista", "dep2", IrohTargets.Format.DEPTH32F, 1f);
            GpuBuffer cmds = IrohBuffers.create("vista2", "cmds", 20 * 64, IrohBuffers.INDIRECT);
            GpuBuffer cnt = IrohBuffers.create("vista2", "cnt", 4, IrohBuffers.INDIRECT);
            GpuBuffer idx = IrohBuffers.create("vista2", "idx", 12, IrohBuffers.INDEX);
            GpuBuffer quads = IrohBuffers.create("vista2", "q", 1024, IrohBuffers.STORAGE);
            IndirectPipelineHandle dp = IrohDraw.indirectPipeline("vista2", "lod", VS, FS, 1, 0, 0, IrohTargets.Format.RGBA8, IrohTargets.Format.DEPTH32F,
                    org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER, true, false);
            check.accept("indirect args: stride >= 20, ranges", throwsIae(() -> IrohDraw.prepareIndirectCount(cmds, 0, cnt, 0, 64, 16))
                    && throwsIae(() -> IrohDraw.prepareIndirectCount(cmds, 0, cnt, 0, 65, 20)) && throwsIae(() -> IrohDraw.prepareIndirectCount(cmds, 0, cnt, 4, 64, 20)));
            IrohCompute.Binding[] st = {IrohCompute.Binding.read(quads)};
            check.accept("draw outside a pass refused", throwsIse(() -> IrohDraw.multiDrawIndirectCount(dp, st, null, null, idx, false, cmds, 0, cnt, 0, 64, 20)));
            IrohDraw.prepareIndirectCount(cmds, 0, cnt, 0, 64, 20);
            IrohTargets.beginRendering(col, dep2, true, null, 0f, quads, idx, cmds, cnt);
            check.accept("compute inside a pass refused", throwsIse(() -> IrohCompute.dispatch(cp, new IrohCompute.Binding[]{IrohCompute.Binding.read(b)}, null, 1, 1, 1)));
            check.accept("undeclared buffer in a draw refused", throwsIse(() -> IrohDraw.multiDrawIndirectCount(dp, new IrohCompute.Binding[]{IrohCompute.Binding.read(a)},
                    null, null, idx, false, cmds, 0, cnt, 0, 64, 20)));
            check.accept("draw with unprepared count arguments refused", throwsIse(() -> IrohDraw.multiDrawIndirectCount(dp, st, null, null, idx, false, cmds, 0, cnt, 0, 32, 20)));
            IrohDraw.multiDrawIndirectCount(dp, st, null, null, idx, false, cmds, 0, cnt, 0, 64, 20);
            IrohTargets.endRendering();
            check.accept("drawIndirectCount path recorded", rec.count("drawCount 64") == 1 && rec.count("drawFallback") == 0);
            rec.indirectCount = false;
            rec.log.clear();
            IrohDraw.prepareIndirectCount(cmds, 0, cnt, 0, 64, 20);
            IrohTargets.beginRendering(col, dep2, false, null, 0f, quads, idx, cmds, cnt);
            IrohDraw.multiDrawIndirectCount(dp, st, null, null, idx, false, cmds, 0, cnt, 0, 64, 20);
            IrohTargets.endRendering();
            check.accept("fallback: Iroh's zero-tail compute before the pass, then a plain indirect draw of maxDraws (" + rec.log + ")",
                    rec.count("compute iroh zero tail") == 1 && rec.count("dispatch") == 1 && rec.count("drawFallback 64") == 1
                            && rec.log.indexOf("begin") > rec.log.indexOf(rec.log.stream().filter(l -> l.startsWith("dispatch")).findFirst().orElse("?")));

            // ---- owner death and world unload
            int before = rec.destroyed;
            int freed = core.freeOwner("vista2");
            check.accept("owner death: all " + freed + " objects of vista2 freed, accounting at 0, handles dead", freed == 5
                    && rec.destroyed - before == 5 && IrohBuffers.ownerBytes("vista2") == 0 && !cmds.alive() && !dp.alive());
            check.accept("a freed handle is refused", throwsIse(() -> IrohBuffers.upload(cmds, 0, ByteBuffer.allocateDirect(4))));
            a.close();
            a.close();
            long expect = (200L << 20) + 1000L * 500 * 4 + 500L * 250 * 4 + 1000L * 500 * 4;
            check.accept("close is idempotent and returns the bytes (" + IrohBuffers.ownerBytes("vista") + " == " + expect + ")",
                    IrohBuffers.ownerBytes("vista") == expect);
            int all = core.freeAll();
            check.accept("world unload frees everything (" + all + ")", core.liveResources() == 0 && IrohBuffers.ownerBytes("vista") == 0
                    && IrohBuffers.ownerBytes("tempest") == 0);
        } finally {
            GpuCore.uninstall();
        }
        check.accept("no core installed: api 4 throws UnsupportedOperationException", GpuCore.orNull() == null);
        phaseSize(check);
        lazyInstall(check);
    }

    /**
     * INT-2 phase-size rule (api 4 + render scale): scaled targets follow the phase they are drawn in; fixed targets
     * never change; the world phase is render size, the rest window size.
     */
    static void phaseSize(BiConsumer<String, Boolean> check) {
        Rec rec = new Rec();
        GpuCore core = GpuCore.install(rec);
        try {
            core.resize(2560, 1440);                                     // window (display) size
            RenderTargetHandle lodCol = IrohTargets.createScaled("vista", "lod colour", IrohTargets.Format.RGBA8, 1f);
            RenderTargetHandle lodDep = IrohTargets.createScaled("vista", "lod depth", IrohTargets.Format.DEPTH32F, 1f);
            RenderTargetHandle fixed = IrohTargets.create("vista", "fixed", IrohTargets.Format.RGBA8, 64, 64);
            check.accept("outside the world phase a scaled target is window size", lodCol.width() == 2560 && lodCol.height() == 1440);

            core.setWorldPhase(true, 1706, 960);                         // render scale Quality: world at render size
            IrohTargets.beginRendering(lodCol, lodDep, true, null, 1f);
            IrohTargets.endRendering();
            check.accept("drawn in the world phase: scale 1 target refit to render size 1706x960 (" + lodCol.width() + "x" + lodCol.height() + ")",
                    lodCol.width() == 1706 && lodCol.height() == 960 && lodDep.width() == 1706 && lodDep.height() == 960);
            long expectBytes = 1706L * 960 * 4 * 2 + 64L * 64 * 4;
            check.accept("owner accounting follows the refit (" + IrohBuffers.ownerBytes("vista") + " == " + expectBytes + ")",
                    IrohBuffers.ownerBytes("vista") == expectBytes);
            RenderTargetHandle madeInWorld = IrohTargets.createScaled("vista", "half", IrohTargets.Format.R32F, 0.5f);
            check.accept("created inside the world phase: render size x scale (853x480)", madeInWorld.width() == 853 && madeInWorld.height() == 480);
            core.resize(1920, 1080);                                     // a window resize while the target is world-sized
            check.accept("a window resize leaves world-sized targets to their next fit", lodCol.width() == 1706);
            core.setWorldPhase(true, 1280, 720);                         // new render size (mode or window change)
            IrohTargets.beginRendering(lodCol, null, true, null, 1f);
            IrohTargets.endRendering();
            check.accept("next world-phase draw takes the new render size", lodCol.width() == 1280 && lodCol.height() == 720);
            core.setWorldPhase(false, 0, 0);
            long refitsBefore = core.refits();
            IrohTargets.beginRendering(fixed, null, true, null, 1f);
            IrohTargets.endRendering();
            check.accept("fixed-size targets never refit", fixed.width() == 64 && core.refits() == refitsBefore);
            IrohTargets.beginRendering(lodCol, null, true, null, 1f);
            IrohTargets.endRendering();
            check.accept("drawn outside the world phase again: back to window size 1920x1080", lodCol.width() == 1920 && lodCol.height() == 1080);
            check.accept("colour and depth of different phases are refused, never drawn mismatched",
                    throwsIae(() -> IrohTargets.beginRendering(madeInWorld, lodDep, true, null, 1f)));
        } finally {
            GpuCore.uninstall();
        }
    }

    /** INT-2 (RQ-INT1-a): the device is created on the first api-4 call, once; a failed create refuses with the reason. */
    static void lazyInstall(BiConsumer<String, Boolean> check) {
        int[] runs = {0};
        Rec rec = new Rec();
        GpuCore.setLazyInstaller(() -> { runs[0]++; GpuCore.install(rec); });
        try {
            check.accept("lazy: nothing installed before the first call", GpuCore.orNull() == null && runs[0] == 0);
            IrohBuffers.create("vista", "b", 64, IrohBuffers.STORAGE);
            IrohBuffers.create("vista", "c", 64, IrohBuffers.STORAGE);
            check.accept("lazy: the first api-4 call created the device, exactly once", GpuCore.orNull() != null && runs[0] == 1);
        } finally {
            GpuCore.uninstall();
            GpuCore.setLazyInstaller(null);
        }
        GpuCore.setLazyInstaller(() -> { runs[0]++; GpuCore.setUnavailableReason("device creation failed: test"); });
        boolean refused = false;
        String msg = "";
        try {
            IrohBuffers.create("vista", "b", 64, IrohBuffers.STORAGE);
        } catch (UnsupportedOperationException e) {
            refused = true;
            msg = e.getMessage();
        }
        boolean refusedAgain = false;
        try {
            IrohBuffers.create("vista", "b", 64, IrohBuffers.STORAGE);
        } catch (UnsupportedOperationException e) {
            refusedAgain = true;
        }
        check.accept("lazy create failed: refused with the reason (" + msg + "), no retry storm", refused && refusedAgain
                && msg.contains("device creation failed") && runs[0] == 2);
        GpuCore.setUnavailableReason("no device yet, or capability off");
    }
}
