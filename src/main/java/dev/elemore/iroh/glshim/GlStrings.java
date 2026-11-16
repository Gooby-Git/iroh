package dev.elemore.iroh.glshim;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.Device;

/**
 * Answers for glGetString when there is no GL context (Iroh renders with Vulkan).
 * Third-party mods read these at window creation to pick vendor workarounds (ImmediatelyFast) or
 * to decide whether GL features exist. The version reported is the minimum Minecraft 1.21.1 needs
 * (3.2 core) so no mod turns on a GL 4.x path it would then call for real. Extensions: none.
 */
public final class GlStrings {
    public static final int GL_VENDOR = 0x1F00, GL_RENDERER = 0x1F01, GL_VERSION = 0x1F02, GL_EXTENSIONS = 0x1F03,
            GL_SHADING_LANGUAGE_VERSION = 0x8B8C;

    private GlStrings() {}

    public static String get(int name) {
        Device device = Vulkan.getDevice();
        String value = switch (name) {
            case GL_VENDOR -> device != null ? device.vendorIdString : "Iroh";
            case GL_RENDERER -> "Iroh (Vulkan)" + (device != null ? " " + device.deviceName : "");
            case GL_VERSION -> "3.2.0 Iroh (Vulkan)";
            case GL_SHADING_LANGUAGE_VERSION -> "1.50 Iroh";
            case GL_EXTENSIONS -> "";
            default -> null;
        };
        GlShim.note("glGetString(0x" + Integer.toHexString(name) + ")", value);
        return value;
    }
}
