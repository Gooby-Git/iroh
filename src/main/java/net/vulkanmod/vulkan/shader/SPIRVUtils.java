package net.vulkanmod.vulkan.shader;

import dev.elemore.iroh.cache.DiskCache;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeResource;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.lwjgl.vulkan.VK12;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memASCII;
import static org.lwjgl.util.shaderc.Shaderc.*;

public class SPIRVUtils {
    private static final boolean DEBUG = false;
    private static final boolean OPTIMIZATIONS = true;

    private static long compiler;
    private static long options;

    //The dedicated Includer and Releaser Inner Classes used to Initialise #include Support for ShaderC
    private static final ShaderIncluder SHADER_INCLUDER = new ShaderIncluder();
    private static final ShaderReleaser SHADER_RELEASER = new ShaderReleaser();
    private static final long pUserData = 0;

    private static ObjectArrayList<String> includePaths;

    static {
        initCompiler();
    }

    private static void initCompiler() {
        compiler = shaderc_compiler_initialize();

        if (compiler == NULL) {
            throw new RuntimeException("Failed to create shader compiler");
        }

        options = shaderc_compile_options_initialize();

        if (options == NULL) {
            throw new RuntimeException("Failed to create compiler options");
        }

        if (OPTIMIZATIONS)
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

        if (DEBUG)
            shaderc_compile_options_set_generate_debug_info(options);

        shaderc_compile_options_set_target_env(options, shaderc_env_version_vulkan_1_2, VK12.VK_API_VERSION_1_2);
        shaderc_compile_options_set_include_callbacks(options, SHADER_INCLUDER, SHADER_RELEASER, pUserData);

        includePaths = new ObjectArrayList<>();
        addIncludePath("/assets/vulkanmod/shaders/include/");
    }

    public static void addIncludePath(String path) {
        URL url = SPIRVUtils.class.getResource(path);

        if (url != null)
            includePaths.add(url.toExternalForm());
    }

    public static SPIRV compileShader(String filename, String source, ShaderKind shaderKind) {
        if (source == null) {
            throw new NullPointerException("source for %s.%s is null".formatted(filename, shaderKind));
        }

        String cacheName = cacheFileName(source, shaderKind);

        if (cacheName != null) {
            SPIRV cached = loadCached(cacheName);
            if (cached != null) {
                if (CACHE_HITS.incrementAndGet() == 1)
                    net.vulkanmod.Initializer.LOGGER.info("SPIR-V cache: 1 hits so far (first hit this session)");
                return cached;
            }
        }

        long result = shaderc_compile_into_spv(compiler, source, shaderKind.kind, filename, "main", options);

        if (result == NULL) {
            throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V");
        }

        if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
            throw new RuntimeException(
                    "Failed to compile shader " + filename + " into SPIR-V:\n" + shaderc_result_get_error_message(
                            result));
        }

        ByteBuffer compiled = shaderc_result_get_bytes(result);

        CACHE_MISSES.incrementAndGet();
        if (cacheName != null) {
            storeCached(cacheName, compiled);
        }

        return new SPIRV(result, compiled);
    }

    // On-disk SPIR-V cache. Key = SHA-256 of (cache version, shader kind, source, include directory contents).
    private static final String CACHE_VERSION = "iroh-spv-1";
    private static final AtomicInteger CACHE_HITS = new AtomicInteger();
    private static final AtomicInteger CACHE_MISSES = new AtomicInteger();
    private static byte[] includeFingerprint;
    private static boolean includeFingerprintFailed;

    public static int getCacheHits() {
        return CACHE_HITS.get();
    }

    public static int getCacheMisses() {
        return CACHE_MISSES.get();
    }

    private static synchronized byte[] getIncludeFingerprint() {
        if (includeFingerprint == null && !includeFingerprintFailed) {
            try {
                MessageDigest digest = DiskCache.sha256();
                for (String includePath : includePaths) {
                    Path root = Paths.get(new URI(includePath));
                    try (Stream<Path> files = Files.walk(root)) {
                        List<Path> sorted = files.filter(Files::isRegularFile).sorted().toList();
                        for (Path file : sorted) {
                            digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                            digest.update(Files.readAllBytes(file));
                        }
                    }
                }
                includeFingerprint = digest.digest();
            } catch (Throwable t) {
                includeFingerprintFailed = true;
            }
        }
        return includeFingerprint;
    }

    private static String cacheFileName(String source, ShaderKind shaderKind) {
        try {
            byte[] fingerprint = getIncludeFingerprint();
            if (fingerprint == null)
                return null;

            MessageDigest digest = DiskCache.sha256();
            digest.update(CACHE_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) shaderKind.ordinal());
            digest.update(fingerprint);
            digest.update(source.getBytes(StandardCharsets.UTF_8));
            return DiskCache.hex(digest.digest()) + ".spv";
        } catch (Throwable t) {
            return null;
        }
    }

    private static SPIRV loadCached(String name) {
        try {
            byte[] bytes = DiskCache.read(name);
            // SPIR-V: whole 32-bit words, starts with the magic number 0x07230203 (little endian here)
            if (bytes == null || bytes.length < 20 || (bytes.length & 3) != 0
                    || bytes[0] != 0x03 || bytes[1] != 0x02 || bytes[2] != 0x23 || bytes[3] != 0x07)
                return null;

            ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
            buf.put(bytes).flip();
            return new SPIRV(0, buf);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void storeCached(String name, ByteBuffer spirv) {
        try {
            byte[] bytes = new byte[spirv.remaining()];
            spirv.duplicate().get(bytes);
            DiskCache.write(name, bytes);
        } catch (Throwable t) {
            // cache is optional
        }
    }

    public enum ShaderKind {
        VERTEX_SHADER(shaderc_glsl_vertex_shader),
        GEOMETRY_SHADER(shaderc_glsl_geometry_shader),
        FRAGMENT_SHADER(shaderc_glsl_fragment_shader),
        COMPUTE_SHADER(shaderc_glsl_compute_shader);

        private final int kind;

        ShaderKind(int kind) {
            this.kind = kind;
        }
    }

    private static class ShaderIncluder implements ShadercIncludeResolveI {

        private static final int MAX_PATH_LENGTH = 4096; //Maximum Linux/Unix Path Length

        @Override
        public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
            var requesting = memASCII(requesting_source);
            var requested = memASCII(requested_source);

            try {
                Path path;

                for (String includePath : includePaths) {
                    path = Paths.get(new URI(String.format("%s%s", includePath, requested)));

                    if (Files.exists(path)) {
                        byte[] bytes = Files.readAllBytes(path);

                        // V-4 (RQ-V3c-b): shaderc reads the result AFTER this callback returns, so it must outlive it: heap, freed by
                        // ShaderReleaser (a MemoryStack frame is popped on return and was overwritten by nested includes).
                        ByteBuffer name = memASCII(requested, true);
                        ByteBuffer content = MemoryUtil.memAlloc(bytes.length);
                        content.put(bytes).flip();
                        return ShadercIncludeResult.malloc()
                                                   .source_name(name)
                                                   .content(content)
                                                   .user_data(user_data).address();
                    }
                }
            } catch (IOException | URISyntaxException e) {
                throw new RuntimeException(e);
            }

            throw new RuntimeException(String.format("%s: Unable to find %s in include paths", requesting, requested));
        }
    }

    /** V-4 (RQ-V3c-b): frees what {@link ShaderIncluder} allocated on the heap (name, content, the result struct). */
    private static class ShaderReleaser implements ShadercIncludeResultReleaseI {

        @Override
        public void invoke(long user_data, long include_result) {
            if (include_result == 0L)
                return;
            ShadercIncludeResult r = ShadercIncludeResult.create(include_result);
            MemoryUtil.memFree(r.source_name());
            MemoryUtil.memFree(r.content());
            MemoryUtil.nmemFree(include_result);
            //TODO:Maybe dump Shader Compiled Binaries here to a .Misc Diretcory to allow easy caching.recompilation...
        }
    }

    public static final class SPIRV implements NativeResource {

        private final long handle;
        private ByteBuffer bytecode;

        public SPIRV(long handle, ByteBuffer bytecode) {
            this.handle = handle;
            this.bytecode = bytecode;
        }

        public ByteBuffer bytecode() {
            return bytecode;
        }

        @Override
        public void free() {
//            shaderc_result_release(handle);
            if (handle == 0 && bytecode != null)
                MemoryUtil.memFree(bytecode); // loaded from the disk cache
            bytecode = null; // Help the GC
        }
    }

}