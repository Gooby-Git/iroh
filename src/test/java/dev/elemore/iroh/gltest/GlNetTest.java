package dev.elemore.iroh.gltest;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.elemore.iroh.glshim.GlNet;
import dev.elemore.iroh.glshim.GlShim;
import dev.elemore.iroh.glshim.PixelTransfer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.JNI;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless proof of the GL safety net (P1-2): real LWJGL GL natives are called on threads that never had a GL
 * context, which without the net aborts the JVM ("No context is current"). Nothing is rendered; no Vulkan device.
 * {@code ./gradlew glNetTest} ; last line "# glnet PASS n/n" or "# glnet FAIL ...". A JVM abort (no last line) is
 * the failure the net exists to prevent.
 *
 * <p>Not covered here (needs the game): handlers that touch Vulkan objects (textures, framebuffers, viewport,
 * scissor), the Mixin-level parts (GL.create TAIL install, getICD / getCapabilities / array-overload injects).
 */
public final class GlNetTest {
    private static final List<String> FAILS = new ArrayList<>();
    private static int checks;

    public static void main(String[] args) throws Exception {
        // GL.<clinit> loads libGL and builds LWJGL's abort table; no Mixin here, so install by hand.
        GL.getFunctionProvider();
        GlNet.install();
        check("net installed", GlNet.installed());
        check("every registered handler bound to a table entry (>= 70)", GlNet.handledCount() >= 70);

        runChecks("main");
        Thread t = new Thread(() -> runChecks("worker"), "glnet-worker");
        t.start();
        t.join();

        // ICD path: real libGL entry points without a context must be no-ops (glvnd / Mesa noop dispatch).
        GLCapabilities icd = GlNet.icdCapabilities();
        check("icd capabilities built", icd != null);
        if (icd != null && icd.glGetIntegerv != 0L) {
            int[] out = {12345};
            JNI.callPV(0x0B74, out, icd.glGetIntegerv);
            check("libGL glGetIntegerv without context is a no-op (value untouched)", out[0] == 12345);
        }

        pixelTransferChecks();
        programChecks();
        framebufferChecks();

        System.out.print(GlShim.unhandledSummary());
        System.out.println(FAILS.isEmpty() ? "# glnet PASS " + checks + "/" + checks
                : "# glnet FAIL " + FAILS.size() + "/" + checks + " " + FAILS);
        System.exit(FAILS.isEmpty() ? 0 : 1);
    }

    private static void runChecks(String where) {
        // unhandled natives: answered 0, logged, counted
        check(where + ": glCreateBuffers (GL45, unhandled) returns 0", GL45C.glCreateBuffers() == 0);
        GL20C.glUseProgram(0);
        int prog = GL20C.glCreateProgram();
        check(where + ": glCreateProgram (emulated since slice 2) returns an id", prog != 0);
        check(where + ": glGetUniformLocation of an unlinked program is -1", GL20C.glGetUniformLocation(prog, "Foo") == -1);
        check(where + ": glGetUniformBlockIndex (unhandled, string arg) returns 0", org.lwjgl.opengl.GL31C.glGetUniformBlockIndex(7, "Foo") == 0);
        check(where + ": glGetError handled -> 0", GL11C.glGetError() == 0);

        // depth func round trip, incl. GL_LESS (ImmediatelyFast blocker D) and vanilla cache sync
        GL11C.glDepthFunc(0x0201);
        check(where + ": GL_DEPTH_FUNC round-trips GL_LESS", GL11C.glGetInteger(0x0B74) == 0x0201);
        check(where + ": GlStateManager.DEPTH.func follows", GlStateManager.DEPTH.func == 0x0201);
        GL11C.glDepthFunc(0x0203);
        GL11C.glEnable(0x0B71);
        check(where + ": glIsEnabled(GL_DEPTH_TEST)", GL11C.glIsEnabled(0x0B71));
        check(where + ": GlStateManager.DEPTH.mode.enabled follows", GlStateManager.DEPTH.mode.enabled);
        GL11C.glDisable(0x0B71);
        check(where + ": glGetBoolean(GL_DEPTH_TEST) false after disable", !GL11C.glGetBoolean(0x0B71));

        // blend
        GL14C.glBlendFuncSeparate(0x0302, 0x0303, 1, 0x0303);
        check(where + ": GL_BLEND_SRC_RGB", GL11C.glGetInteger(0x80C9) == 0x0302);
        check(where + ": GL_BLEND_DST_ALPHA", GL11C.glGetInteger(0x80CA) == 0x0303);
        GL11C.glEnable(0x0BE2);
        check(where + ": blend enabled", GL11C.glIsEnabled(0x0BE2) && GlStateManager.BLEND.mode.enabled);

        // stencil (tracked only)
        GL11C.glStencilFunc(0x0202, 3, 0xFF);
        GL11C.glStencilOp(0x1E00, 0x1E01, 0x1E02);
        GL11C.glEnable(0x0B90);
        check(where + ": stencil func/ref/mask",
                GL11C.glGetInteger(0x0B92) == 0x0202 && GL11C.glGetInteger(0x0B97) == 3 && GL11C.glGetInteger(0x0B93) == 0xFF);
        check(where + ": stencil op", GL11C.glGetInteger(0x0B96) == 0x1E02);
        check(where + ": stencil test enabled", GL11C.glIsEnabled(0x0B90));

        // colour mask through glGetBooleanv(ByteBuffer) (4 values)
        GL11C.glColorMask(true, false, true, false);
        ByteBuffer bb = BufferUtils.createByteBuffer(16);
        GL11C.glGetBooleanv(0x0C23, bb);
        check(where + ": GL_COLOR_WRITEMASK", bb.get(0) == 1 && bb.get(1) == 0 && bb.get(2) == 1 && bb.get(3) == 0);
        GL11C.glColorMask(true, true, true, true);

        // float arguments reach the handler
        GL11C.glLineWidth(2.5f);
        check(where + ": GL_LINE_WIDTH float arg", GL11C.glGetFloat(0x0B21) == 2.5f);

        // names
        int tex = GL11C.glGenTextures();
        check(where + ": glGenTextures hands out an Iroh id", tex > 0 && GL11C.glIsTexture(tex));
        int buf = GL15C.glGenBuffers();
        check(where + ": glGenBuffers hands out an Iroh id", buf > 0);
        GL15C.glBindBuffer(0x8892, buf); // GL_ARRAY_BUFFER: the base threw here
        check(where + ": GL_ARRAY_BUFFER_BINDING tracked", GL11C.glGetInteger(0x8894) == buf);
        GL15C.glBindBuffer(0x8892, 0);
        int vao = GL30C.glGenVertexArrays();
        GL30C.glBindVertexArray(vao);
        check(where + ": GL_VERTEX_ARRAY_BINDING tracked", vao > 0 && GL11C.glGetInteger(0x85B5) == vao);
        GL30C.glBindVertexArray(0);

        // unknown pname answers 0
        check(where + ": unknown pname -> 0", GL11C.glGetInteger(0x7FFF0) == 0);

        // slice 2: copies / readbacks are handled (no target here: answered without touching Vulkan)
        GL11C.glCopyTexSubImage2D(0x0DE1, 0, 0, 0, 0, 0, 16, 16);
        ByteBuffer px = BufferUtils.createByteBuffer(16);
        GL11C.glReadPixels(0, 0, 2, 2, 0x1908, 0x1401, px);
        check(where + ": glCopyTexSubImage2D / glReadPixels not in the unhandled list",
                GlShim.unhandledCounts().keySet().stream().noneMatch(k -> k.startsWith("glCopyTexSubImage2D <-") || k.startsWith("glReadPixels <-")));

        // slice 2 item 4: fences are signalled at creation (readbacks complete synchronously)
        long sync = org.lwjgl.opengl.GL32C.glFenceSync(0x9117, 0);
        check(where + ": glFenceSync handle, glClientWaitSync ALREADY_SIGNALED",
                sync != 0 && org.lwjgl.opengl.GL32C.glClientWaitSync(sync, 1, 1_000_000L) == 0x911A
                && org.lwjgl.opengl.GL32C.glGetSynci(sync, 0x9114, null) == 0x9119);
        org.lwjgl.opengl.GL32C.glDeleteSync(sync);

        // a function the net does not emulate, called with many args
        GL13C.glCompressedTexImage2D(0x0DE1, 0, 0x83F0, 4, 4, 0, (ByteBuffer) null);
        check(where + ": still alive after unhandled calls", true);
    }

    /** P1-2 slice 2 item 3: honest completeness and draw/read buffer state (no Vulkan objects are made here). */
    private static void framebufferChecks() {
        check("fbo: default framebuffer complete", GL30C.glCheckFramebufferStatus(0x8D40) == 0x8CD5);
        int fb = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(0x8D40, fb);
        check("fbo: no attachments -> INCOMPLETE_MISSING_ATTACHMENT", GL30C.glCheckFramebufferStatus(0x8D40) == 0x8CD7);
        GL30C.glFramebufferTexture2D(0x8D40, 0x8CE0, 0x0DE1, 999999, 1);
        check("fbo: mip level > 0 / unknown texture -> UNSUPPORTED, no throw", GL30C.glCheckFramebufferStatus(0x8D40) == 0x8CDD);
        org.lwjgl.opengl.GL20C.glDrawBuffers(0x8CE0);
        GL11C.glReadBuffer(0x8CE0);
        check("fbo: GL_DRAW_BUFFER0 / GL_READ_BUFFER answered", GL11C.glGetInteger(0x8825) == 0x8CE0 && GL11C.glGetInteger(0x0C02) == 0x8CE0);
    }

    /** P1-2 slice 2 item 2: shader objects capture sources through the real LWJGL entry points; the link translates. */
    private static void programChecks() {
        String vs = "#version 150\nin vec2 Position;\nin vec4 Color;\nout vec4 c;\nvoid main(){ c = Color; gl_Position = vec4(Position,0,1); }\n";
        String fs = "#version 150\nuniform vec4 Tint;\nuniform sampler2D Tex;\nin vec4 c;\nout vec4 o;\nvoid main(){ o = c * Tint * texture(Tex, vec2(0.5)); }\n";
        int v = GL20C.glCreateShader(0x8B31), f = GL20C.glCreateShader(0x8B30);
        check("prog: glCreateShader answered", v != 0 && f != 0 && v != f);
        GL20C.glShaderSource(v, vs);
        GL20C.glShaderSource(f, fs);
        check("prog: glShaderSource captured", vs.equals(dev.elemore.iroh.glshim.GlPrograms.sourceOf(v)) && fs.equals(dev.elemore.iroh.glshim.GlPrograms.sourceOf(f)));
        GL20C.glCompileShader(v);
        check("prog: COMPILE_STATUS true (errors come at link)", GL20C.glGetShaderi(v, 0x8B81) == 1);
        int prog = GL20C.glCreateProgram();
        check("prog: glCreateProgram answered", prog != 0);
        GL20C.glAttachShader(prog, v);
        GL20C.glAttachShader(prog, f);
        GL20C.glBindAttribLocation(prog, 2, "Color");
        check("prog: glBindAttribLocation recorded", Integer.valueOf(2).equals(dev.elemore.iroh.glshim.GlPrograms.boundAttribsOf(prog).get("Color")));
        dev.elemore.iroh.glshim.GlPrograms.LinkResult r = dev.elemore.iroh.glshim.GlPrograms.LinkResult.link(
                vs, fs, dev.elemore.iroh.glshim.GlPrograms.boundAttribsOf(prog), true);
        check("prog: link translates + compiles (" + r.log + ")", r.ok);
        check("prog: bound location kept, free slot filled", r.ok && "Color".equals(r.attributes.get(2)) && "Position".equals(r.attributes.get(0)));
        check("prog: uniform in block, sampler bound", r.ok && r.output.uniforms().size() == 1 && r.output.samplers().size() == 1);
        dev.elemore.iroh.glshim.GlPrograms.LinkResult legacy = dev.elemore.iroh.glshim.GlPrograms.LinkResult.link(
                "#version 110\nvoid main(){ gl_Position = ftransform(); }", "#version 110\nvoid main(){ gl_FragColor = vec4(1); }",
                new java.util.HashMap<>(), false);
        check("prog: fixed-function built-ins refused like a core profile", !legacy.ok && legacy.log.contains("ftransform"));
        if (r.vertex != null) r.vertex.free();
        if (r.fragment != null) r.fragment.free();
        // no program current: draw is reported, not crashed
        GL11C.glDrawArrays(0x0004, 0, 3);
        check("prog: glDrawArrays without a program logged as unhandled",
                GlShim.unhandledCounts().keySet().stream().anyMatch(k -> k.startsWith("glDrawArrays(no GL program current)")));
    }

    /** P1-2 slice 2 (td-834bea): GL unpack / pack rules, pure CPU. */
    private static void pixelTransferChecks() {
        // Xaero: 64x64 BGRA + UNSIGNED_INT_8_8_8_8, row length 0, alignment 4 -> RGBA8
        int w = 64, h = 64;
        java.nio.ByteBuffer src = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
        for (int i = 0; i < w * h; i++) src.putInt(i * 4, 0x11223344 + i); // B=0x11 G=0x22 R=0x33 A=0x44(+i) as a packed int
        long stride = PixelTransfer.rowStride(w, 0, 4, PixelTransfer.GL_BGRA, PixelTransfer.GL_UNSIGNED_INT_8_8_8_8);
        check("pixel: row length 0 -> width * bpp", stride == 256);
        java.nio.ByteBuffer dst = PixelTransfer.alloc(w, h, PixelTransfer.Dst.RGBA8);
        boolean ok = PixelTransfer.unpack(org.lwjgl.system.MemoryUtil.memAddress(src), stride, w, h,
                PixelTransfer.GL_BGRA, PixelTransfer.GL_UNSIGNED_INT_8_8_8_8, PixelTransfer.Dst.RGBA8, org.lwjgl.system.MemoryUtil.memAddress(dst));
        check("pixel: BGRA/8_8_8_8 unpack supported", ok);
        check("pixel: BGRA/8_8_8_8 -> R,G,B,A bytes", (dst.get(0) & 0xFF) == 0x33 && (dst.get(1) & 0xFF) == 0x22
                && (dst.get(2) & 0xFF) == 0x11 && (dst.get(3) & 0xFF) == 0x44);
        int last = (w * h - 1) * 4;
        check("pixel: last pixel converted (no staging garbage)", (dst.get(last + 3) & 0xFF) == ((0x44 + w * h - 1) & 0xFF));
        // pack back to the same format through a stride with skip
        java.nio.ByteBuffer back = org.lwjgl.system.MemoryUtil.memCalloc(w * h * 4);
        PixelTransfer.pack(org.lwjgl.system.MemoryUtil.memAddress(dst), PixelTransfer.Dst.RGBA8, w, h,
                PixelTransfer.GL_BGRA, PixelTransfer.GL_UNSIGNED_INT_8_8_8_8, org.lwjgl.system.MemoryUtil.memAddress(back), stride);
        check("pixel: pack round trip BGRA/8_8_8_8", back.getInt(0) == src.getInt(0) && back.getInt(last) == src.getInt(last));
        // RGB unsigned byte, width 3, alignment 4 -> stride 12 (9 bytes padded)
        check("pixel: RGB stride padded to alignment", PixelTransfer.rowStride(3, 0, 4, PixelTransfer.GL_RGB, PixelTransfer.GL_UNSIGNED_BYTE) == 12);
        check("pixel: RGB alignment 1 unpadded", PixelTransfer.rowStride(3, 0, 1, PixelTransfer.GL_RGB, PixelTransfer.GL_UNSIGNED_BYTE) == 9);
        check("pixel: float element >= alignment unpadded", PixelTransfer.rowStride(3, 0, 4, PixelTransfer.GL_RED, PixelTransfer.GL_FLOAT) == 12);
        check("pixel: skip rows/pixels with row length", PixelTransfer.skipOffset(10, 16, 4, 2, 3, PixelTransfer.GL_RGBA, PixelTransfer.GL_UNSIGNED_BYTE) == 2 * 64 + 12);
        // RGBA bytes are identical to RGBA8 (memcpy path); BGRA bytes are not
        check("pixel: RGBA/UNSIGNED_BYTE identical to RGBA8", PixelTransfer.identical(PixelTransfer.GL_RGBA, PixelTransfer.GL_UNSIGNED_BYTE, PixelTransfer.Dst.RGBA8));
        check("pixel: BGRA/8_8_8_8 not identical to RGBA8", !PixelTransfer.identical(PixelTransfer.GL_BGRA, PixelTransfer.GL_UNSIGNED_INT_8_8_8_8, PixelTransfer.Dst.RGBA8));
        // RGB -> RGBA8 fills alpha 1
        java.nio.ByteBuffer rgb = org.lwjgl.system.MemoryUtil.memAlloc(12);
        rgb.put(0, (byte) 10).put(1, (byte) 20).put(2, (byte) 30);
        java.nio.ByteBuffer one = PixelTransfer.alloc(1, 1, PixelTransfer.Dst.RGBA8);
        PixelTransfer.unpack(org.lwjgl.system.MemoryUtil.memAddress(rgb), 12, 1, 1, PixelTransfer.GL_RGB, PixelTransfer.GL_UNSIGNED_BYTE,
                PixelTransfer.Dst.RGBA8, org.lwjgl.system.MemoryUtil.memAddress(one));
        check("pixel: RGB -> RGBA8 alpha 255", one.get(0) == 10 && one.get(2) == 30 && (one.get(3) & 0xFF) == 255);
        for (java.nio.ByteBuffer b : new java.nio.ByteBuffer[]{src, dst, back, rgb, one}) org.lwjgl.system.MemoryUtil.memFree(b);
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) FAILS.add(what);
        System.out.println((ok ? "PASS " : "FAIL ") + what);
    }
}
