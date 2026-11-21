package dev.elemore.iroh.shaderpack.graph;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pack (GL) texture formats -> the Vulkan format Iroh allocates and what that format can do. Offline the device is
 * unknown, so each row carries two answers: what the Vulkan spec REQUIRES of every implementation ("Required Format
 * Support", Vulkan 1.3 spec section 34.3) and what Iroh expects of its target class (desktop RDNA2+ / RADV, NVIDIA
 * Turing+; UNVERIFIED offline). At run time the graph's format check must be re-run against
 * vkGetPhysicalDeviceFormatProperties; this table only decides what the plan may assume and what it must check.
 * <p>
 * Rules: 3-component formats are promoted to 4 components (no desktop driver attaches RGB8/RGB16F/RGB32F as colour;
 * clear default becomes opaque black, Vitrail's correction); legacy formats map to the nearest modern format that
 * keeps the data kind (R3_G3_B2 / RGB5_A1 -> RGBA8, RGB9_E5 -> RGBA16F: E5B9G9R9 cannot be a colour attachment).
 */
public final class FormatCaps {

    /** attach = COLOR_ATTACHMENT, blend = COLOR_ATTACHMENT_BLEND, storage = STORAGE_IMAGE, filter = SAMPLED_IMAGE_FILTER_LINEAR */
    public enum Support { REQUIRED, EXPECTED, NO }

    public record Row(String gl, String vk, int bytes, boolean promoted, Support attach, Support blend, Support storage,
                      Support filter, String note) {}

    public static final Map<String, Row> TABLE = new LinkedHashMap<>();

    private static void r(String gl, String vk, int bytes, boolean promoted, Support attach, Support blend, Support storage,
                          Support filter, String note) {
        TABLE.put(gl, new Row(gl, vk, bytes, promoted, attach, blend, storage, filter, note));
    }

    static {
        Support R = Support.REQUIRED, E = Support.EXPECTED, N = Support.NO;
        r("R8", "R8_UNORM", 1, false, R, R, E, R, "");
        r("RG8", "R8G8_UNORM", 2, false, R, R, E, R, "");
        r("RGB8", "R8G8B8A8_UNORM", 4, true, R, R, R, R, "promoted from RGB8");
        r("RGBA8", "R8G8B8A8_UNORM", 4, false, R, R, R, R, "");
        r("R8_SNORM", "R8_SNORM", 1, false, E, E, E, R, "SNORM attachment optional in the spec");
        r("RG8_SNORM", "R8G8_SNORM", 2, false, E, E, E, R, "SNORM attachment optional in the spec");
        r("RGB8_SNORM", "R8G8B8A8_SNORM", 4, true, E, E, R, R, "promoted; SNORM attachment optional in the spec");
        r("RGBA8_SNORM", "R8G8B8A8_SNORM", 4, false, E, E, R, R, "SNORM attachment optional in the spec");
        r("R16", "R16_UNORM", 2, false, E, E, E, E, "16-bit UNORM attachment optional");
        r("RG16", "R16G16_UNORM", 4, false, E, E, E, E, "16-bit UNORM attachment optional");
        r("RGB16", "R16G16B16A16_UNORM", 8, true, E, E, E, E, "promoted; 16-bit UNORM attachment optional");
        r("RGBA16", "R16G16B16A16_UNORM", 8, false, E, E, E, E, "16-bit UNORM attachment optional");
        r("R16_SNORM", "R16_SNORM", 2, false, E, E, E, E, "");
        r("RG16_SNORM", "R16G16_SNORM", 4, false, E, E, E, E, "");
        r("RGB16_SNORM", "R16G16B16A16_SNORM", 8, true, E, E, E, E, "promoted");
        r("RGBA16_SNORM", "R16G16B16A16_SNORM", 8, false, E, E, E, E, "");
        r("R16F", "R16_SFLOAT", 2, false, R, R, R, R, "");
        r("RG16F", "R16G16_SFLOAT", 4, false, R, R, R, R, "");
        r("RGB16F", "R16G16B16A16_SFLOAT", 8, true, R, R, R, R, "promoted from RGB16F");
        r("RGBA16F", "R16G16B16A16_SFLOAT", 8, false, R, R, R, R, "");
        r("R32F", "R32_SFLOAT", 4, false, R, N, R, E, "no blend on R32F in the spec; linear filter optional");
        r("RG32F", "R32G32_SFLOAT", 8, false, R, N, R, E, "linear filter optional");
        r("RGB32F", "R32G32B32A32_SFLOAT", 16, true, R, N, R, E, "promoted; linear filter optional");
        r("RGBA32F", "R32G32B32A32_SFLOAT", 16, false, R, N, R, E, "linear filter optional");
        for (String w : new String[]{"8", "16", "32"}) {
            int b = Integer.parseInt(w) / 8;
            for (String s : new String[]{"I", "UI"}) {
                String sign = s.equals("I") ? "SINT" : "UINT";
                r("R" + w + s, "R" + w + "_" + sign, b, false, R, N, R, N, "integer: nearest only");
                r("RG" + w + s, "R" + w + "G" + w + "_" + sign, 2 * b, false, R, N, w.equals("32") ? R : E, N, "integer: nearest only");
                r("RGB" + w + s, "R" + w + "G" + w + "B" + w + "A" + w + "_" + sign, 4 * b, true, R, N, R, N, "promoted; integer");
                r("RGBA" + w + s, "R" + w + "G" + w + "B" + w + "A" + w + "_" + sign, 4 * b, false, R, N, R, N, "integer: nearest only");
            }
        }
        r("RGB10_A2", "A2B10G10R10_UNORM_PACK32", 4, false, R, R, E, R, "");
        r("RGB10_A2UI", "A2B10G10R10_UINT_PACK32", 4, false, R, N, E, N, "");
        r("R11F_G11F_B10F", "B10G11R11_UFLOAT_PACK32", 4, false, E, E, E, R,
                "attachment optional in the spec, supported on RDNA/RADV and NVIDIA (UNVERIFIED offline)");
        r("RGB9_E5", "R16G16B16A16_SFLOAT", 8, true, R, R, R, R, "E5B9G9R9 cannot be attached: mapped to RGBA16F");
        r("R3_G3_B2", "R8G8B8A8_UNORM", 4, true, R, R, R, R, "legacy: mapped to RGBA8");
        r("RGB5_A1", "R8G8B8A8_UNORM", 4, true, R, R, R, R, "legacy: mapped to RGBA8 (A1R5G5B5 attach is optional)");
        r("RGBA2", "R8G8B8A8_UNORM", 4, true, R, R, R, R, "legacy: mapped to RGBA8");
        r("RGBA4", "R8G8B8A8_UNORM", 4, true, R, R, R, R, "legacy: mapped to RGBA8 (I Like Vanilla)");
        r("RGB4", "R8G8B8A8_UNORM", 4, true, R, R, R, R, "legacy: mapped to RGBA8");
        r("RGB5", "R8G8B8A8_UNORM", 4, true, R, R, R, R, "legacy: mapped to RGBA8");
        r("RGB10", "A2B10G10R10_UNORM_PACK32", 4, true, R, R, E, R, "legacy: mapped to RGB10_A2");
        r("RGB12", "R16G16B16A16_UNORM", 8, true, E, E, E, E, "legacy: mapped to RGBA16");
        r("RGBA12", "R16G16B16A16_UNORM", 8, true, E, E, E, E, "legacy: mapped to RGBA16");
        // depth (Iroh-owned)
        r("DEPTH32F", "D32_SFLOAT", 4, false, Support.NO, Support.NO, Support.NO, Support.REQUIRED, "depth attachment, sampled");
    }

    /** Lower-case GLSL image format qualifiers (r16ui, rgba16f) also resolve. */
    public static Row of(String glFormat) {
        if (glFormat == null)
            return null;
        String f = glFormat.trim().toUpperCase(Locale.ROOT);
        Row r = TABLE.get(f);
        if (r != null)
            return r;
        if (f.equals("R11F_G11F_B10F") || f.equals("R11G11B10F"))
            return TABLE.get("R11F_G11F_B10F");
        return null;
    }

    private FormatCaps() {}
}
