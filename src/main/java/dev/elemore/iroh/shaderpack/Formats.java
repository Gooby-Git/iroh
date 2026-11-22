package dev.elemore.iroh.shaderpack;

import java.util.Locale;
import java.util.Map;

/** Pack texture formats: bytes per texel and whether Vulkan can attach them as they are. */
public final class Formats {

    private Formats() {}

    /** bytes per texel; 3-component formats are counted as Vulkan stores them after promotion to 4. */
    public static int bytes(String f) {
        f = f.toUpperCase(Locale.ROOT);
        switch (f) {
            case "R3_G3_B2" -> {
                return 1;
            }
            case "RGB5_A1" -> {
                return 2;
            }
            case "RGB10_A2", "RGB10_A2UI", "R11F_G11F_B10F", "RGB9_E5" -> {
                return 4;
            }
            default -> {
            }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(R|RG|RGB|RGBA)(8|16|32)(F|I|UI|_SNORM)?").matcher(f);
        if (!m.matches())
            return -1;
        int comps = m.group(1).length();
        if (comps == 3)
            comps = 4; // RGB8/RGB16F... promoted: most cannot be colour attachments in Vulkan
        return comps * Integer.parseInt(m.group(2)) / 8;
    }

    /** 3-component formats that Vulkan (RDNA, most desktop) cannot attach and are promoted to RGBA. */
    public static boolean promoted(String f) {
        f = f.toUpperCase(Locale.ROOT);
        return f.matches("RGB(8|16|32)(F|I|UI|_SNORM)?");
    }

    /** OptiFine buffer aliases. */
    public static final Map<String, String> ALIASES = Map.of("gcolor", "colortex0", "gdepth", "colortex1",
            "gnormal", "colortex2", "composite", "colortex3", "gaux1", "colortex4", "gaux2", "colortex5",
            "gaux3", "colortex6", "gaux4", "colortex7");

    public static String canonical(String buffer) {
        return ALIASES.getOrDefault(buffer, buffer);
    }
}
