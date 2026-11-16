package dev.elemore.iroh.gltest;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.elemore.iroh.glshim.GlNet;
import dev.elemore.iroh.glshim.GlShim;
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
        check(where + ": glCreateProgram (unhandled) returns 0", prog == 0);
        check(where + ": glGetUniformLocation (unhandled, string arg) returns 0", GL20C.glGetUniformLocation(7, "Foo") == 0);
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

        // a function the net does not emulate, called with many args
        GL13C.glCompressedTexImage2D(0x0DE1, 0, 0x83F0, 4, 4, 0, (ByteBuffer) null);
        check(where + ": still alive after unhandled calls", true);
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) FAILS.add(what);
        System.out.println((ok ? "PASS " : "FAIL ") + what);
    }
}
