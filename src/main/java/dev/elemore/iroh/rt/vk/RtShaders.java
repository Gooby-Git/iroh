package dev.elemore.iroh.rt.vk;

import dev.elemore.iroh.gpu.Glsl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Loads the RT core's GLSL from {@code /iroh/rt/} (classpath), expands {@code #include "x"} once each, compiles to SPIR-V. */
public final class RtShaders {
    private RtShaders() {}

    public static String source(String name) {
        return expand(name, new HashSet<>());
    }

    private static String expand(String name, Set<String> seen) {
        if (!seen.add(name)) return "";
        String src = read(name);
        StringBuilder out = new StringBuilder();
        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#include \"")) out.append(expand(t.substring(10, t.lastIndexOf('"')), seen)).append('\n');
            else out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String read(String name) {
        try (InputStream in = RtShaders.class.getResourceAsStream("/iroh/rt/" + name)) {
            if (in == null) throw new IllegalArgumentException("no RT shader " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SPIR-V (caller frees with MemoryUtil.memFree). Kind from the extension: .comp / .vert / .frag. */
    public static ByteBuffer spirv(String name) {
        int kind = name.endsWith(".vert") ? Glsl.VERTEX : name.endsWith(".frag") ? Glsl.FRAGMENT : Glsl.COMPUTE;
        return Glsl.compile(name, source(name), kind);
    }
}
