package dev.elemore.iroh.apitest;

import dev.elemore.iroh.api.*;
import dev.elemore.iroh.gpu.GpuCore;
import dev.elemore.iroh.gpu.HeadlessGpuDevice;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * UPLOAD-SYNC (RQ-R1-a): api-4 {@code IrohBuffers.upload} order on lavapipe, through the public api, one case per way a
 * consumer meets it. {@code ./gradlew uploadSyncRepro} (the fixed router: last line "# uploadsync PASS n/n", validation
 * clean); {@code -Pmode=legacy} runs the pre-UPLOAD-SYNC path ({@code -Diroh.api4.upload=preframe}, vkval label
 * uploadSyncRepro-legacy: the NEGATIVE CONTROL, meant to show the wrong values, the check's hazards and syncval errors).
 * <ol>
 *   <li>A, same-frame write between two GPU passes (Vista coverage / any GL port's glBufferSubData between passes): pass 1
 *       must read the OLD bytes, pass 2 the NEW. The pre-frame path lands before the whole frame: pass 1 reads NEW.</li>
 *   <li>B, upload between frames (client tick, AFTER_PRESENT) into a buffer the frame in flight read: deferred to the next
 *       frame start (in-stream, ordered after that frame); legacy: on the transfer queue under the frame (check: WAR).</li>
 *   <li>C, upload inside the game's main pass (world stages) into a buffer this frame used: queued (US-REV: no pass
 *       break of its own), recorded before the next api-4 work that reads it, in order; an untouched buffer takes the
 *       pre-frame path.</li>
 *   <li>D, upload inside an open api-4 pass (Vista's per-use uniform elements): pre-frame as in api 4.1; distinct bytes
 *       are fine, the same bytes twice in one frame is reported by the check.</li>
 * </ol>
 * US-REV (review of 9793f7d), on a second device with TWO frames in flight ({@link HeadlessGpuDevice#setFramesInFlight}:
 * frame command buffers are held until their slot comes round, the pre-frame batch is submitted at once, the worst legal
 * schedule of the game's async transfer queue), so in-flight ordering is exercised, not only program order:
 * <ol start="5">
 *   <li>B2, B with the frame really in flight: the frame in flight reads the OLD bytes, the next frame the NEW.</li>
 *   <li>E, a buffer read ONLY by the main join (Vista composite style), then a world-stage upload while that frame is in
 *       flight: the frame in flight must read the OLD bytes (review B1, scenario 1).</li>
 *   <li>F, the main join reads a buffer, then a world-stage upload into it in the same frame: the join reads the OLD
 *       bytes (review B1, scenario 2).</li>
 *   <li>G, a deferred upload still queued (its drain skipped by a pack world pass at frame start) and a second upload
 *       into the same buffer once the frame that used it has finished: the second must win (review m1).</li>
 *   <li>H, cost per frame of a realistic api-4 pattern (Vista: per-slot uploads in BEFORE_MAIN_PASS, its explicit
 *       runOutsideMainPass, a per-slot composite uniform, the main join) plus a GL-port uploader writing K ranges of one
 *       join-read buffer from a world stage: pass breaks, barriers and global barriers per frame (review M2).</li>
 * </ol>
 */
public final class UploadSyncRepro {
    static int pass, total;
    static final String OWNER = "uploadsync";
    static final boolean LEGACY = GpuCore.LEGACY_PREFRAME;

    static void check(String what, boolean ok) {
        total++;
        if (ok) pass++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static final String COPY = """
            #version 450
            layout(local_size_x = 4) in;
            layout(std430, set = 0, binding = 0) readonly buffer Src { uint s[]; };
            layout(std430, set = 0, binding = 1) writeonly buffer Dst { uint d[]; };
            void main() { d[gl_GlobalInvocationID.x] = s[gl_GlobalInvocationID.x]; }
            """;
    static final String VS_FULL = "#version 450\nvoid main(){ vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);"
            + " gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }\n";
    /** the main join's draw: red = the buffer's first word / 255 */
    static final String FS_SHOW = "#version 450\nlayout(std430, set = 0, binding = 0) readonly buffer B { uint v[]; };\n"
            + "layout(location = 0) out vec4 o;\nvoid main(){ o = vec4(float(v[0]) / 255.0, 0.0, 0.0, 1.0); }\n";

    static ByteBuffer words(int v) {
        ByteBuffer b = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) b.putInt(v);
        return b.flip();
    }

    static int read(HeadlessGpuDevice dev, GpuCore core, GpuBuffer b) {
        return dev.readBuffer(core.nativeBuffer(b), 0, 16).getInt(0);
    }

    static GpuBuffer buf(String name) {
        GpuBuffer b = IrohBuffers.create(OWNER, name, 16, IrohBuffers.STORAGE);
        if (b == null) throw new IllegalStateException(IrohBuffers.lastRefusal());
        return b;
    }

    static void copy(ComputePipelineHandle p, GpuBuffer src, GpuBuffer dst) {
        IrohCompute.dispatch(p, new IrohCompute.Binding[]{IrohCompute.Binding.read(src), IrohCompute.Binding.write(dst)}, null, 1, 1, 1);
    }

    static String routes(GpuCore core) {
        long[] r = core.uploadRoutes();
        return "routes in-stream " + r[0] + " pre-frame " + r[1] + " queued " + r[2] + " deferred " + r[3] + " in-pass " + r[4];
    }

    public static void main(String[] args) {
        System.out.println("mode " + (LEGACY ? "LEGACY (pre-UPLOAD-SYNC: every upload pre-frame; negative control)" : "UPLOAD-SYNC router")
                + ", check level " + GpuCore.CHECK);
        int wrong = 0;
        HeadlessGpuDevice dev = new HeadlessGpuDevice(1L << 30, false);
        GpuCore core = GpuCore.install(dev);
        core.resize(16, 16);
        try {
            ComputePipelineHandle cp = IrohCompute.pipeline(OWNER, "copy", COPY, 2, 0);
            GpuBuffer x = buf("x"), y = buf("y"), out1 = buf("out1"), out2 = buf("out2");
            IrohBuffers.upload(x, 0, words(1));
            read(dev, core, x); // flush: x = 1 on the GPU

            // ---- A: write between two passes of one frame
            core.frameStart();
            copy(cp, x, out1);
            IrohBuffers.upload(x, 0, words(2));
            copy(cp, x, out2);
            int a1 = read(dev, core, out1), a2 = read(dev, core, out2);
            boolean aOk = a1 == 1 && a2 == 2;
            if (!aOk) wrong++;
            if (LEGACY) System.out.println("     A legacy: pass 1 read " + a1 + " (program order: 1), pass 2 read " + a2);
            else check("A same-frame write between two passes: pass 1 reads the old value (" + a1 + "), pass 2 the new (" + a2 + ")", aOk);

            // ---- B: upload between frames into a buffer the frame in flight read
            long hz0 = core.uploadHazards();
            core.frameStart();
            copy(cp, x, out1);               // frame k reads x (in flight after its submit)
            read(dev, core, out1);
            dev.betweenFrames = true;
            int routeB = core.uploadRoute(x);
            IrohBuffers.upload(x, 0, words(3));
            dev.betweenFrames = false;
            core.frameRecordingStarted();    // Renderer: before FrameStage.BEFORE_MAIN_PASS
            copy(cp, x, out2);
            int b2 = read(dev, core, out2);
            if (b2 != 3) wrong++;
            if (LEGACY) System.out.println("     B legacy: route " + routeB + " (1 = pre-frame), next frame read " + b2 + ", check hazards +" + (core.uploadHazards() - hz0));
            else check("B upload between frames into a buffer in flight: deferred to the next frame start (route " + routeB + "), next frame reads "
                    + b2 + ", no hazard", routeB == GpuCore.ROUTE_DEFERRED && b2 == 3 && core.uploadHazards() == hz0);

            // ---- C: inside the game's main pass (emulated)
            hz0 = core.uploadHazards();
            core.frameStart();
            IrohBuffers.upload(y, 0, words(7));
            read(dev, core, y);
            core.frameStart();
            copy(cp, x, out1);               // x used this frame (x = 3)
            dev.mainPassBound = true;        // world stages: the main pass is bound
            int breaks0 = dev.passBreaks;
            int routeCx = core.uploadRoute(x), routeCy = core.uploadRoute(y);
            IrohBuffers.upload(x, 0, words(4));
            IrohBuffers.upload(y, 0, words(8)); // untouched by this frame and by frames in flight
            dev.mainPassBound = false;
            copy(cp, x, out2);
            int c1 = read(dev, core, out1), c2 = read(dev, core, out2), cy = read(dev, core, y);
            boolean cOk = c1 == 3 && c2 == 4 && cy == 8;
            if (!cOk) wrong++;
            if (LEGACY) System.out.println("     C legacy: pass before the upload read " + c1 + " (program order: 3), after " + c2 + ", y " + cy);
            else check("C main pass bound: used buffer -> queued (route " + routeCx + ", pass breaks " + (dev.passBreaks - breaks0) + "), reads "
                    + c1 + " then " + c2 + "; untouched buffer -> pre-frame (route " + routeCy + "), y = " + cy,
                    cOk && routeCx == GpuCore.ROUTE_PASS_BREAK && routeCy == GpuCore.ROUTE_PRE_FRAME && dev.passBreaks - breaks0 == 0 && core.uploadHazards() == hz0);

            // ---- D: inside an open api-4 pass (per-use uniform elements)
            if (!LEGACY) {
                core.frameStart();
                GpuBuffer ub = IrohBuffers.create(OWNER, "uniforms", 64, IrohBuffers.STORAGE);
                RenderTargetHandle t = IrohTargets.create(OWNER, "t", IrohTargets.Format.RGBA8, 4, 4);
                hz0 = core.uploadHazards();
                IrohTargets.beginRendering(t, null, true, new float[]{0, 0, 0, 0}, 0f, ub);
                int routeD = core.uploadRoute(ub);
                IrohBuffers.upload(ub, 0, words(5));
                IrohBuffers.upload(ub, 16, words(6));
                long distinct = core.uploadHazards() - hz0;
                IrohBuffers.upload(ub, 0, words(9)); // the same element twice in one frame
                long same = core.uploadHazards() - hz0 - distinct;
                IrohTargets.endRendering();
                int d0 = dev.readBuffer(core.nativeBuffer(ub), 0, 32).getInt(16);
                check("D inside an api pass: pre-frame (route " + routeD + "), distinct elements no hazard (" + distinct + "), same bytes twice reported ("
                        + same + "), element 1 = " + d0, routeD == GpuCore.ROUTE_IN_PASS && distinct == 0 && same == 1 && d0 == 6);
                ub.close();
                t.close();
            }

            System.out.println("     " + routes(core) + ", hazards " + core.uploadHazards());
            core.freeOwner(OWNER);
            dev.flush();
            check("all device memory returned (" + dev.allocatedBytes() + " bytes held by the device's own objects)", core.ownerResources(OWNER) == 0);
        } finally {
            GpuCore.uninstall();
            dev.close();
        }
        long legacyHazards = core.uploadHazards();
        wrong += inFlight();
        if (LEGACY) {
            // negative control: the old path must show the wrong order (A, C, E, F, G) and the check must see the hazards
            boolean reproduced = wrong >= 2 && legacyHazards >= 2;
            System.out.println("# uploadsync " + (reproduced ? "PASS" : "FAIL") + " legacy negative control: wrong reads " + wrong + ", hazards " + legacyHazards
                    + (reproduced ? " (reproduced)" : " (NOT reproduced)"));
            System.exit(reproduced ? 0 : 1);
        }
        System.out.println("# uploadsync " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }

    // ============================================================================================ US-REV: frames in flight

    static HeadlessGpuDevice fdev;
    static GpuCore fcore;

    /** the game's frame order: fence wait, GpuLifecycle.frameStart, vkBeginCommandBuffer, frameRecordingStarted */
    static void begin(boolean drainBlocked) {
        fdev.beginFrame();
        fdev.betweenFrames = true;      // GpuLifecycle.frameStart runs before the frame buffer is begun
        fcore.frameStart();
        fdev.betweenFrames = false;
        fdev.mainPassBound = drainBlocked; // a pack world pass live at frame start: no in-frame copy possible
        fcore.frameRecordingStarted();
        fdev.mainPassBound = false;
    }

    /** BEFORE_MAIN_PASS hooks ran: Renderer settles, then the world stages run with the main pass bound */
    static void worldStages() {
        fcore.settleAll();
        fdev.mainPassBound = true;
    }

    static void end() {
        fdev.mainPassBound = false;
        fdev.endFrame();
    }

    static int red(RenderTargetHandle t) {
        return IrohTargets.readback(t).get(0) & 0xFF;
    }

    static void join(IndirectPipelineHandle show, GpuBuffer... reads) {
        IrohTargets.beginRendering(IrohTargets.mainColor(), IrohTargets.mainDepth(), false, null, 1f, reads);
        IrohDraw.draw(show, new IrohCompute.Binding[]{IrohCompute.Binding.read(reads[0])}, new GpuTexture[0], null, 3, 1);
        IrohTargets.endRendering();
    }

    /** E..H on a device with two frames in flight; returns the wrong reads (legacy counts them). */
    static int inFlight() {
        int wrong = 0;
        fdev = new HeadlessGpuDevice(1L << 30, false);
        fdev.setFramesInFlight(2);
        fcore = GpuCore.install(fdev);
        fcore.resize(4, 4);
        try {
            begin(false);
            ComputePipelineHandle cp = IrohCompute.pipeline(OWNER, "copy", COPY, 2, 0);
            IndirectPipelineHandle show = IrohDraw.indirectPipeline(OWNER, "show", VS_FULL, FS_SHOW, 1, 0, 0, IrohTargets.Format.RGBA8,
                    IrohTargets.Format.DEPTH32F, org.lwjgl.vulkan.VK10.VK_COMPARE_OP_ALWAYS, false, IrohTargets.Blend.NONE);
            RenderTargetHandle mc = IrohTargets.create(OWNER, "main colour", IrohTargets.Format.RGBA8, 4, 4);
            RenderTargetHandle md = IrohTargets.create(OWNER, "main depth", IrohTargets.Format.DEPTH32F, 4, 4);
            fcore.standInMain(mc, md);
            GpuBuffer xe = buf("e"), xf = buf("f"), xg = buf("g"), out = buf("out"), xb = buf("b2"), out2 = buf("out2");
            IrohBuffers.upload(xe, 0, words(10));
            IrohBuffers.upload(xf, 0, words(30));
            IrohBuffers.upload(xb, 0, words(5));
            end();

            // ---- B2: an upload between frames while the frame that read the buffer is really in flight
            begin(false);
            IrohCompute.dispatch(cp, new IrohCompute.Binding[]{IrohCompute.Binding.read(xb), IrohCompute.Binding.write(out)}, null, 1, 1, 1);
            end();                            // in flight
            fdev.betweenFrames = true;        // client tick
            int routeB2 = fcore.uploadRoute(xb);
            IrohBuffers.upload(xb, 0, words(6));
            fdev.betweenFrames = false;
            begin(false);
            IrohCompute.dispatch(cp, new IrohCompute.Binding[]{IrohCompute.Binding.read(xb), IrohCompute.Binding.write(out2)}, null, 1, 1, 1);
            end();
            int b1 = read(fdev, fcore, out), b2 = read(fdev, fcore, out2);
            boolean b2Ok = b1 == 5 && b2 == 6;
            if (!b2Ok) wrong++;
            if (LEGACY) System.out.println("     B2 legacy: the frame in flight read " + b1 + " (want 5), the next " + b2);
            else check("B2 upload between frames, the frame that read it really in flight: route " + routeB2 + ", that frame read " + b1
                    + " (want 5), the next " + b2 + " (want 6)", b2Ok && routeB2 == GpuCore.ROUTE_DEFERRED);

            // ---- E: read only by the main join, then an upload while that frame is in flight
            begin(false);
            worldStages();
            join(show, xe);                   // frame N reads xe (10) through the main join only
            end();                            // frame N in flight
            begin(false);                     // frame N+1: frames up to N-1 finished
            worldStages();
            int routeE = fcore.uploadRoute(xe);
            IrohBuffers.upload(xe, 0, words(20)); // a world-stage upload
            end();
            int e1 = red(mc);                 // what frame N's join drew
            begin(false);
            IrohCompute.dispatch(cp, new IrohCompute.Binding[]{IrohCompute.Binding.read(xe), IrohCompute.Binding.write(out)}, null, 1, 1, 1);
            int e2 = read(fdev, fcore, out);
            end();
            boolean eOk = e1 == 10 && e2 == 20;
            if (!eOk) wrong++;
            if (LEGACY) System.out.println("     E legacy: frame in flight read " + e1 + " (want 10), later " + e2);
            else check("E main-join-only buffer, upload while that frame is in flight: route " + routeE + ", the frame in flight read " + e1
                    + " (want 10), the next frame " + e2 + " (want 20)", eOk && routeE != GpuCore.ROUTE_PRE_FRAME);

            // ---- F: the main join reads it, then an upload in the same frame
            begin(false);
            worldStages();
            join(show, xf);                   // reads 30
            int routeF = fcore.uploadRoute(xf);
            IrohBuffers.upload(xf, 0, words(40));
            end();
            int f1 = red(mc);
            begin(false);
            IrohCompute.dispatch(cp, new IrohCompute.Binding[]{IrohCompute.Binding.read(xf), IrohCompute.Binding.write(out)}, null, 1, 1, 1);
            int f2 = read(fdev, fcore, out);
            end();
            boolean fOk = f1 == 30 && f2 == 40;
            if (!fOk) wrong++;
            if (LEGACY) System.out.println("     F legacy: the join before the upload read " + f1 + " (want 30), later " + f2);
            else check("F main join then a same-frame upload: route " + routeF + ", the join read " + f1 + " (want 30), the next frame " + f2
                    + " (want 40)", fOk && routeF != GpuCore.ROUTE_PRE_FRAME);

            // ---- G: a deferred upload whose drain was skipped, then a second upload into the same buffer
            begin(false);
            IrohCompute.dispatch(cp, new IrohCompute.Binding[]{IrohCompute.Binding.read(xg), IrohCompute.Binding.write(out)}, null, 1, 1, 1);
            end();                            // xg used by the frame now in flight
            fdev.betweenFrames = true;
            IrohBuffers.upload(xg, 0, words(50)); // U1: deferred
            fdev.betweenFrames = false;
            begin(true);                      // the pack world pass is live at frame start: U1 cannot drain
            end();
            fdev.beginFrame();
            fdev.betweenFrames = true;
            fcore.frameStart();               // the frame that used xg has finished now
            int routeG = fcore.uploadRoute(xg);
            IrohBuffers.upload(xg, 0, words(60)); // U2, before the frame buffer is begun
            fdev.betweenFrames = false;
            fcore.frameRecordingStarted();
            IrohCompute.dispatch(cp, new IrohCompute.Binding[]{IrohCompute.Binding.read(xg), IrohCompute.Binding.write(out)}, null, 1, 1, 1);
            int g = read(fdev, fcore, out);
            end();
            if (g != 60) wrong++;
            if (LEGACY) System.out.println("     G legacy: last upload 60, the frame read " + g);
            else check("G deferred upload still queued, second upload into the same buffer: route " + routeG + ", the frame read " + g
                    + " (want 60: program order)", g == 60);

            if (!LEGACY) cost(cp, show, mc);
            fcore.freeOwner(OWNER);
            fdev.flush();
            check("frames in flight: all device memory returned", fcore.ownerResources(OWNER) == 0);
        } finally {
            GpuCore.uninstall();
            fdev.close();
        }
        return wrong;
    }

    static final int K = 8, FRAMES = 6;

    /** H: per-frame pass breaks / barriers of a realistic pattern; uploaders after Vista's break (worst) and before it. */
    static void cost(ComputePipelineHandle cp, IndirectPipelineHandle show, RenderTargetHandle mc) {
        for (boolean early : new boolean[]{false, true}) {
            int f = fdev.framesInFlight();
            GpuBuffer[] jobs = new GpuBuffer[f], ub = new GpuBuffer[f];
            for (int i = 0; i < f; i++) {
                jobs[i] = buf("jobs " + i);
                ub[i] = buf("composite uniforms " + i);
            }
            GpuBuffer dyn = IrohBuffers.create(OWNER, "gl port table", 16L * K, IrohBuffers.STORAGE), scratch = buf("scratch");
            long[] r0 = fcore.uploadRoutes();
            int br0 = 0, ba0 = 0, gb0 = 0;
            for (int fr = 0; fr < FRAMES + 2; fr++) {
                if (fr == 2) { // two warm-up frames: every buffer has been read by a join / dispatch once
                    br0 = fdev.passBreaks;
                    ba0 = fdev.barriers;
                    gb0 = fdev.globalBarriers;
                    r0 = fcore.uploadRoutes();
                }
                begin(false);
                int slot = IrohBuffers.frameSlot() % f;
                IrohBuffers.upload(jobs[slot], 0, words(fr));          // BEFORE_MAIN_PASS (Vista prepare)
                IrohCompute.dispatch(cp, new IrohCompute.Binding[]{IrohCompute.Binding.read(jobs[slot]), IrohCompute.Binding.write(scratch)}, null, 1, 1, 1);
                worldStages();
                Runnable glPort = () -> {
                    for (int k = 0; k < K; k++) IrohBuffers.upload(dyn, 16L * k, words(value(fr0(), k)));
                };
                frameValue = 100 + fr;
                if (early) glPort.run();
                IrohTargets.runOutsideMainPass(() ->                     // Vista render: its own explicit break
                        IrohCompute.dispatch(cp, new IrohCompute.Binding[]{IrohCompute.Binding.read(jobs[slot]), IrohCompute.Binding.write(scratch)}, null, 1, 1, 1));
                if (!early) glPort.run();
                IrohBuffers.upload(ub[slot], 0, words(fr));              // composite uniform, per slot
                IrohTargets.beginRendering(IrohTargets.mainColor(), IrohTargets.mainDepth(), false, null, 1f, dyn, ub[slot]);
                IrohDraw.draw(show, new IrohCompute.Binding[]{IrohCompute.Binding.read(dyn)}, new GpuTexture[0], null, 3, 1);
                IrohTargets.endRendering();
                end();
            }
            long[] r = fcore.uploadRoutes();
            double n = FRAMES;
            int px = red(mc);
            System.out.printf("     H %s: per frame pass breaks %.1f, barriers %.1f, global barriers %.1f; uploads in-stream %d pre-frame %d queued %d"
                            + " deferred %d in-pass %d over %d frames; last join read %d (want %d)%n",
                    early ? "uploader before Vista's break" : "uploader after Vista's break (worst)", (fdev.passBreaks - br0) / n,
                    (fdev.barriers - ba0) / n, (fdev.globalBarriers - gb0) / n, r[0] - r0[0], r[1] - r0[1], r[2] - r0[2], r[3] - r0[3], r[4] - r0[4],
                    FRAMES, px, frameValue);
            int breaks = (int) Math.round((fdev.passBreaks - br0) / n);
            double barriers = (fdev.barriers - ba0) / n;
            // before US-REV (measured on int-4c 0cccf9d with this harness): 9 breaks, 13.2 barriers, 10 global barriers per frame
            check("H cost (" + (early ? "uploader before Vista's break" : "uploader after it") + "): " + K + " world-stage uploads cost at most one pass break"
                    + " per frame beyond Vista's own (" + breaks + " breaks/frame), one barrier for the run of copies (" + barriers + "/frame),"
                    + " no global barrier from them, join reads this frame's value",
                    breaks <= (early ? 1 : 2) && barriers <= (early ? 6 : 7) && px == frameValue && (fdev.globalBarriers - gb0) <= FRAMES * 2);
            for (GpuBuffer b : jobs) b.close();
            for (GpuBuffer b : ub) b.close();
            dyn.close();
            scratch.close();
        }
    }

    static int frameValue;

    static int fr0() {
        return frameValue;
    }

    static int value(int v, int k) {
        return k == 0 ? v : v + k;
    }
}
