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
 *   <li>C, upload inside the game's main pass (world stages) into a buffer this frame used: one pass break, in order; an
 *       untouched buffer takes the pre-frame path with no break.</li>
 *   <li>D, upload inside an open api-4 pass (Vista's per-use uniform elements): pre-frame as in api 4.1; distinct bytes
 *       are fine, the same bytes twice in one frame is reported by the check.</li>
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
        return "routes in-stream " + r[0] + " pre-frame " + r[1] + " pass-break " + r[2] + " deferred " + r[3] + " in-pass " + r[4];
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
            else check("C main pass bound: used buffer -> pass break (route " + routeCx + ", breaks " + (dev.passBreaks - breaks0) + "), reads "
                    + c1 + " then " + c2 + "; untouched buffer -> pre-frame, no break (route " + routeCy + "), y = " + cy,
                    cOk && routeCx == GpuCore.ROUTE_PASS_BREAK && routeCy == GpuCore.ROUTE_PRE_FRAME && dev.passBreaks - breaks0 == 1 && core.uploadHazards() == hz0);

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
        if (LEGACY) {
            // negative control: the old path must show the wrong order (A, C) and the check must see the hazards
            boolean reproduced = wrong >= 2 && core.uploadHazards() >= 2;
            System.out.println("# uploadsync " + (reproduced ? "PASS" : "FAIL") + " legacy negative control: wrong reads " + wrong + ", hazards " + core.uploadHazards()
                    + (reproduced ? " (reproduced)" : " (NOT reproduced)"));
            System.exit(reproduced ? 0 : 1);
        }
        System.out.println("# uploadsync " + (pass == total ? "PASS" : "FAIL") + " " + pass + "/" + total);
        System.exit(pass == total ? 0 : 1);
    }
}
