package dev.elemore.iroh.shaderpack.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * P3-9 (S): a vanilla vertex format ("vanilla:Position,Color,UV0,UV1,UV2,Normal" = the element names of
 * com.mojang.blaze3d.vertex.VertexFormat in order) as vertex attributes for a translated pack program, by Iris
 * attribute location. The SAME SPIR-V serves every format: only the pipeline's vertex-input state differs. Element
 * sizes/encodings are vanilla 1.21.1's (Position float x3, Color ubyte x4 normalized, UV0 float x2, UV1 overlay
 * short x2, UV2 light short x2 (0..240 like the 16-byte terrain vertex), Normal byte x3 normalized + 1 pad).
 * Inputs the format lacks read binding 1: a 16-byte zero buffer with stride 0 (vec4 -> (0,0,0,1) via R8_UNORM).
 */
public final class VanillaLayout {

    public record Attr(int offset, int format) {}

    public final int stride;
    final Map<String, Integer> offsets = new HashMap<>();

    private VanillaLayout(String spec) {
        int off = 0;
        for (String e : spec.substring("vanilla:".length()).split(",")) {
            offsets.put(e.trim(), off);
            off += size(e.trim());
        }
        stride = off;
    }

    private static final Map<String, VanillaLayout> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static VanillaLayout of(String spec) {
        return CACHE.computeIfAbsent(spec, VanillaLayout::new);
    }

    public static boolean is(String layout) {
        return layout != null && layout.startsWith("vanilla:");
    }

    static int size(String element) {
        return switch (element) {
            case "Position" -> 12;
            case "Color", "UV1", "UV2", "Normal" -> 4;
            case "UV0" -> 8;
            case "Padding" -> 1;
            default -> throw new IllegalArgumentException("unknown vanilla vertex element " + element);
        };
    }

    // VkFormat values (VK10)
    static final int R32G32B32_SFLOAT = 106, R8G8B8A8_UNORM = 37, R32G32_SFLOAT = 103, R16G16_SSCALED = 79, R8G8B8A8_SNORM = 38,
            R8_UNORM = 9, R8_UINT = 13, R8_SINT = 14;

    /** binding-0 attribute for an Iris input location, or null = read the zero binding */
    public Attr attribute(int location, String glslType) {
        boolean integer = glslType.startsWith("i") || glslType.startsWith("u");
        Integer o;
        int fmt;
        switch (location) {
            case 0, 12 -> { o = offsets.get("Position"); fmt = R32G32B32_SFLOAT; }
            case 1, 13 -> { o = offsets.get("Color"); fmt = R8G8B8A8_UNORM; }
            case 2, 14 -> { o = offsets.get("UV0"); fmt = R32G32_SFLOAT; }
            case 3, 4, 16 -> { o = offsets.get("UV2"); fmt = R16G16_SSCALED; }
            case 15 -> { o = offsets.get("UV1"); fmt = R16G16_SSCALED; }
            case 6, 17 -> { o = offsets.get("Normal"); fmt = R8G8B8A8_SNORM; }
            default -> { o = null; fmt = 0; }
        }
        if (o == null || integer)
            return null;
        return new Attr(o, fmt);
    }

    /** zero-binding format for an input the layout lacks */
    public static int zeroFormat(String glslType) {
        return glslType.startsWith("u") ? R8_UINT : glslType.startsWith("i") ? R8_SINT : R8_UNORM;
    }
}
