package dev.elemore.iroh.shaderpack;

import org.lwjgl.vulkan.VK12;

import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * GLSL 4.60 (translated) to SPIR-V through shaderc (LWJGL, the same library the base uses), Vulkan 1.2 target.
 * One compiler and options object per thread (the harness compiles programs in parallel). No include callback:
 * the front end's {@link Cpp} already expanded everything.
 */
public final class PackCompiler {

    public record Compiled(byte[] spirv, String error, String warnings) {}

    private static final ThreadLocal<long[]> STATE = ThreadLocal.withInitial(() -> {
        long c = shaderc_compiler_initialize();
        long o = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(o, shaderc_env_version_vulkan_1_2, VK12.VK_API_VERSION_1_2);
        shaderc_compile_options_set_optimization_level(o, Boolean.getBoolean("iroh.pack.optimize")
                ? shaderc_optimization_level_performance : shaderc_optimization_level_zero);
        return new long[]{c, o};
    });

    private PackCompiler() {}

    public static Compiled compile(PackGlsl.Stage stage, String source, String name) {
        long[] st = STATE.get();
        int kind = switch (stage) {
            case VERTEX -> shaderc_glsl_vertex_shader;
            case FRAGMENT -> shaderc_glsl_fragment_shader;
            case GEOMETRY -> shaderc_glsl_geometry_shader;
            case TESS_CONTROL -> shaderc_glsl_tess_control_shader;
            case TESS_EVAL -> shaderc_glsl_tess_evaluation_shader;
            case COMPUTE -> shaderc_glsl_compute_shader;
        };
        // pack units are large (Complementary: 300+ KB after expansion): never on the LWJGL stack
        ByteBuffer src = org.lwjgl.system.MemoryUtil.memUTF8(source, false);
        ByteBuffer nm = org.lwjgl.system.MemoryUtil.memUTF8(name);
        ByteBuffer entry = org.lwjgl.system.MemoryUtil.memUTF8("main");
        long res;
        try {
            res = shaderc_compile_into_spv(st[0], src, kind, nm, entry, st[1]);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(src);
            org.lwjgl.system.MemoryUtil.memFree(nm);
            org.lwjgl.system.MemoryUtil.memFree(entry);
        }
        if (res == 0)
            return new Compiled(null, "shaderc returned no result", "");
        try {
            String msg = shaderc_result_get_error_message(res);
            if (shaderc_result_get_compilation_status(res) != shaderc_compilation_status_success)
                return new Compiled(null, msg == null ? "unknown shaderc error" : msg, "");
            ByteBuffer b = shaderc_result_get_bytes(res);
            byte[] out = new byte[b.remaining()];
            b.get(out);
            return new Compiled(out, null, msg == null ? "" : msg);
        } finally {
            shaderc_result_release(res);
        }
    }
}
