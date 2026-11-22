package dev.elemore.iroh.gpu;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * GLSL 450 (Vulkan) -> SPIR-V for api 4 pipelines, with shaderc (the library Iroh's SPIRVUtils uses). Kept free of
 * Minecraft classes so the offline exerciser can use it. Results are cached in memory by (kind, source); the Vulkan
 * pipeline cache (in game: Iroh's PIPELINE_CACHE) caches the compiled pipelines.
 */
public final class Glsl {
    public static final int VERTEX = shaderc_glsl_vertex_shader, FRAGMENT = shaderc_glsl_fragment_shader, COMPUTE = shaderc_glsl_compute_shader;
    private static final ConcurrentHashMap<String, byte[]> CACHE = new ConcurrentHashMap<>();
    private static long compiler, options;

    private Glsl() {}

    private static synchronized void init() {
        if (compiler != 0) return;
        compiler = shaderc_compiler_initialize();
        options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
    }

    /** SPIR-V words (direct buffer; caller frees with {@link MemoryUtil#memFree}). Throws with the compiler log. */
    public static ByteBuffer compile(String name, String source, int kind) {
        String key = kind + "\0" + source;
        byte[] bytes = CACHE.get(key);
        if (bytes == null) {
            init();
            long r;
            synchronized (Glsl.class) {
                r = shaderc_compile_into_spv(compiler, source, kind, name, "main", options);
            }
            if (r == 0) throw new IllegalStateException("shaderc failed on " + name);
            try {
                if (shaderc_result_get_compilation_status(r) != shaderc_compilation_status_success)
                    throw new IllegalArgumentException(name + " did not compile:\n" + shaderc_result_get_error_message(r));
                ByteBuffer out = shaderc_result_get_bytes(r);
                bytes = new byte[out.remaining()];
                out.get(bytes);
            } finally {
                shaderc_result_release(r);
            }
            CACHE.put(key, bytes);
        }
        ByteBuffer b = MemoryUtil.memAlloc(bytes.length);
        b.put(bytes).flip();
        return b;
    }
}
