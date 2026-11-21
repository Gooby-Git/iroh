package dev.elemore.iroh.gltest;

import dev.elemore.iroh.glshim.Orientation;
import dev.elemore.iroh.glshim.Orientation.Target;
import net.vulkanmod.vulkan.shader.PipelineState.AssemblyRasterState;
import net.vulkanmod.vulkan.shader.PipelineState.BlendState;
import net.vulkanmod.vulkan.shader.PipelineState.DepthState;
import net.vulkanmod.vulkan.shader.PipelineState.LogicOpState;

import static org.lwjgl.vulkan.VK10.*;

/**
 * rc2 (td-42f410, td-eb1e26) headless guards. {@code ./gradlew orientationTest}; last line "# orientation PASS n/n".
 * <ol>
 *   <li>PipelineState bit packing: every field round-trips for every value while the other fields hold every value
 *       (the rc1 bug: cull mode FRONT leaked into topology, 3 -> 19).</li>
 *   <li>Orientation against a truth table written from the specs: GL facing (glFrontFace, GL area sign) and GL culling
 *       vs Vulkan facing (Vulkan area {@code a = -1/2 sum(...)} in framebuffer coordinates after the viewport transform,
 *       VK_FRONT_FACE_COUNTER_CLOCKWISE) and the cull mode Orientation picks; on-screen row after the present flip vs
 *       GL's window row; scissor rows vs GL rows; image row vs GL row for sampling. Every target kind.</li>
 * </ol>
 */
public final class OrientationTest {
    private static int pass, fail;
    private static final int GL_FRONT = 0x0404, GL_BACK = 0x0405, GL_FRONT_AND_BACK = 0x0408, GL_CW = 0x0900, GL_CCW = 0x0901;

    public static void main(String[] args) {
        packing();
        orientation();
        System.out.println("# orientation " + (fail == 0 ? "PASS " : "FAIL ") + pass + "/" + (pass + fail));
        if (fail != 0) System.exit(1);
    }

    // ------------------------------------------------------------------------------------------- 1. bit packing
    private static void packing() {
        int bad = 0, n = 0;
        int[] culls = {VK_CULL_MODE_NONE, VK_CULL_MODE_FRONT_BIT, VK_CULL_MODE_BACK_BIT, VK_CULL_MODE_FRONT_AND_BACK};
        for (int topo = 0; topo <= VK_PRIMITIVE_TOPOLOGY_PATCH_LIST; topo++)
            for (int poly = 0; poly <= VK_POLYGON_MODE_POINT; poly++)
                for (int cull : culls) {
                    n++;
                    int s = AssemblyRasterState.encode(cull, topo, poly);
                    int s2 = AssemblyRasterState.withCullMode(AssemblyRasterState.encode(false, topo, poly), cull);
                    if (AssemblyRasterState.decodeTopology(s) != topo || AssemblyRasterState.decodePolygonMode(s) != poly
                            || AssemblyRasterState.decodeCullMode(s) != cull || s != s2)
                        bad++;
                }
        check("AssemblyRasterState round trip, " + n + " combinations (topology 0..10 x polygon 0..2 x cull 0..3)", bad == 0);
        check("AssemblyRasterState fields do not overlap",
                (AssemblyRasterState.POLYGON_MODE_MASK << AssemblyRasterState.POLYGON_MODE_OFFSET
                 & AssemblyRasterState.TOPOLOGY_MASK << AssemblyRasterState.TOPOLOGY_OFFSET) == 0
                && (AssemblyRasterState.TOPOLOGY_MASK << AssemblyRasterState.TOPOLOGY_OFFSET
                    & AssemblyRasterState.CULL_MODE_MASK << AssemblyRasterState.CULL_MODE_OFFSET) == 0);
        check("rc1 regression: TRIANGLE_LIST + cull FRONT decodes to TRIANGLE_LIST, not 19",
                AssemblyRasterState.decodeTopology(AssemblyRasterState.encode(VK_CULL_MODE_FRONT_BIT, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, 0))
                == VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

        bad = 0; n = 0;
        for (int sr = 0; sr <= 18; sr++) for (int dr = 0; dr <= 18; dr += 3) for (int sa = 0; sa <= 18; sa += 6)
            for (int da = 0; da <= 18; da += 5) for (int op = 0; op <= 4; op++) for (int en = 0; en < 2; en++) {
                n++;
                int s = (en == 1 ? BlendState.ENABLE_BIT : 0) | BlendState.encode(sr, BlendState.SRC_RGB_OFFSET, BlendState.FACTOR_MASK)
                        | BlendState.encode(dr, BlendState.DST_RGB_OFFSET, BlendState.FACTOR_MASK)
                        | BlendState.encode(sa, BlendState.SRC_A_OFFSET, BlendState.FACTOR_MASK)
                        | BlendState.encode(da, BlendState.DST_A_OFFSET, BlendState.FACTOR_MASK)
                        | BlendState.encode(op, BlendState.FUN_OFFSET, BlendState.OP_MASK);
                if (BlendState.getSrcRgbFactor(s) != sr || BlendState.getDstRgbFactor(s) != dr || BlendState.getSrcAlphaFactor(s) != sa
                        || BlendState.getDstAlphaFactor(s) != da || BlendState.blendOp(s) != op || BlendState.enable(s) != (en == 1))
                    bad++;
            }
        check("BlendState round trip, " + n + " combinations (factors 0..18, op 0..4, enable)", bad == 0);
        check("BlendState enable bit above the op field", BlendState.ENABLE_BIT > (BlendState.OP_MASK << BlendState.FUN_OFFSET));

        bad = 0;
        for (int gl = 512; gl <= 519; gl++) for (int t = 0; t < 2; t++) for (int m = 0; m < 2; m++) {
            int s = (t == 1 ? DepthState.DEPTH_TEST_BIT : 0) | (m == 1 ? DepthState.DEPTH_MASK_BIT : 0) | DepthState.encodeDepthFun(gl);
            if (DepthState.decodeDepthFun(s) != gl - 512 || DepthState.depthTest(s) != (t == 1) || DepthState.depthMask(s) != (m == 1)) bad++;
        }
        check("DepthState round trip (GL_NEVER..GL_ALWAYS -> VkCompareOp 0..7, test, mask)", bad == 0);

        bad = 0;
        for (int gl = 0x1500; gl <= 0x150F; gl++) for (int e = 0; e < 2; e++) {
            int s = (e == 1 ? LogicOpState.ENABLE_BIT : 0) | LogicOpState.encodeLogicOpFun(gl);
            if (LogicOpState.decodeFun(s) != gl - 0x1500 || LogicOpState.enable(s) != (e == 1)) bad++;
        }
        check("LogicOpState round trip (GL_CLEAR..GL_SET -> VkLogicOp 0..15, enable)", bad == 0);
    }

    // ------------------------------------------------------------------------------------------- 2. orientation
    private static void orientation() {
        final int W = 64, H = 32;
        // a triangle in GL window coordinates (y up), counter-clockwise; its mirror image is clockwise
        double[][] ccw = {{10, 4}, {50, 4}, {10, 28}};
        double[][] cw = {{10, 4}, {10, 28}, {50, 4}};
        int bad = 0, n = 0;
        for (Target t : Target.values())
            for (int cullFace : new int[]{GL_BACK, GL_FRONT, GL_FRONT_AND_BACK})
                for (int frontFace : new int[]{GL_CCW, GL_CW})
                    for (double[][] tri : new double[][][]{ccw, cw}) {
                        n++;
                        // GL truth (GL 4.6 section 14.6.1: area sign in window coordinates, positive = CCW)
                        boolean glCcw = areaGl(tri) > 0;
                        boolean glFront = glCcw == (frontFace == GL_CCW);
                        boolean glCulled = cullFace == GL_FRONT_AND_BACK || (cullFace == GL_BACK) != glFront;
                        // Vulkan: window -> NDC -> framebuffer through Orientation's viewport, Vulkan area formula
                        float[] v = Orientation.vkViewport(t, 0, 0, W, H, H);
                        double[][] fb = new double[3][2];
                        for (int i = 0; i < 3; i++) {
                            double ndcX = tri[i][0] / W * 2 - 1, ndcY = tri[i][1] / H * 2 - 1;
                            fb[i][0] = v[0] + (ndcX + 1) / 2 * v[2];
                            fb[i][1] = v[1] + (ndcY + 1) / 2 * v[3];
                        }
                        double a = -areaGl(fb); // Vulkan 1.3 section 28.7.1: a = -1/2 sum(...)
                        boolean vkFront = Orientation.FRONT_FACE == VK_FRONT_FACE_COUNTER_CLOCKWISE ? a > 0 : a < 0;
                        int cull = Orientation.vkCullMode(t, cullFace, frontFace);
                        boolean vkCulled = (cull & (vkFront ? VK_CULL_MODE_FRONT_BIT : VK_CULL_MODE_BACK_BIT)) != 0;
                        if (vkCulled != glCulled) {
                            bad++;
                            System.out.println("  mismatch " + t + " cull=" + Integer.toHexString(cullFace) + " front=" + Integer.toHexString(frontFace)
                                    + " glCcw=" + glCcw + " gl culled " + glCulled + " vk culled " + vkCulled);
                        }
                    }
        check("culling: Vulkan keeps / drops exactly what GL keeps / drops, " + n + " cases (3 targets x 3 glCullFace x 2 glFrontFace x 2 windings)", bad == 0);

        // the common case spelled out: a GL CCW triangle survives GL_BACK culling on every target
        boolean ok = true;
        for (Target t : Target.values()) {
            float[] v = Orientation.vkViewport(t, 0, 0, W, H, H);
            double[][] fb = new double[3][2];
            for (int i = 0; i < 3; i++) {
                fb[i][0] = v[0] + ccw[i][0] / W * v[2];
                fb[i][1] = v[1] + ccw[i][1] / H * v[3];
            }
            boolean vkFront = -areaGl(fb) > 0;
            int cull = Orientation.vkCullMode(t, GL_BACK, GL_CCW);
            ok &= (cull & (vkFront ? VK_CULL_MODE_FRONT_BIT : VK_CULL_MODE_BACK_BIT)) == 0;
        }
        check("a GL counter-clockwise triangle survives GL_BACK culling on SCENE, OFFSCREEN and SWAPCHAIN", ok);

        // rows: image row of GL window row y; on-screen row from the top after the present path
        bad = 0;
        for (Target t : Target.values()) {
            float[] v = Orientation.vkViewport(t, 0, 0, W, H, H);
            for (int glY = 0; glY < H; glY++) {
                double centre = glY + 0.5;
                double imageRow = v[1] + centre / H * v[3]; // framebuffer y of that GL row centre
                if (t.glLayout()) {
                    if (Math.abs(imageRow - centre) > 1e-9) bad++;                 // GL layout: image row = GL row
                    double screenFromTop = H - imageRow;                          // the one flipped present blit
                    if (Math.abs(screenFromTop - (H - centre)) > 1e-9) bad++;     // GL: row y is H - y from the top
                } else {
                    if (Math.abs(imageRow - (H - centre)) > 1e-9) bad++;          // swapchain: top-down directly
                }
            }
        }
        check("rows: GL-layout targets store GL row y at image row y, and every target shows it H-y from the top (upright)", bad == 0);

        // scissor: the rows Vulkan keeps are the image rows of GL's scissored rows
        bad = 0;
        for (Target t : Target.values())
            for (int y = -3; y < H; y += 5) for (int h = 1; h <= H; h += 7) {
                int[] s = Orientation.vkScissor(t, 2, y, 9, h, H);
                for (int glRow = 0; glRow < H; glRow++) {
                    boolean glKeeps = glRow >= y && glRow < y + h;
                    int imageRow = t.glLayout() ? glRow : H - 1 - glRow;
                    boolean vkKeeps = imageRow >= s[1] && imageRow < s[1] + s[3];
                    if (glKeeps != vkKeeps) bad++;
                }
                if (s[0] < 0 || s[1] < 0) bad++;
            }
        check("scissor: Vulkan scissor rows = image rows of GL's scissor box, offsets >= 0, every target", bad == 0);
    }

    /** The GL polygon area 1/2 sum(x_i*y_{i+1} - x_{i+1}*y_i). */
    private static double areaGl(double[][] p) {
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            double[] a = p[i], b = p[(i + 1) % p.length];
            s += a[0] * b[1] - b[0] * a[1];
        }
        return s / 2;
    }

    private static void check(String what, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }
}
