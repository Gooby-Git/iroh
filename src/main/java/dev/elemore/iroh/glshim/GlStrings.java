package dev.elemore.iroh.glshim;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.Device;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers for glGetString when there is no GL context (Iroh renders with Vulkan).
 * Third-party mods read these at window creation to pick vendor workarounds (ImmediatelyFast) or
 * to decide whether GL features exist. The version reported is the minimum Minecraft 1.21.1 needs
 * (3.2 core) so no mod turns on a GL 4.x path it would then call for real. Extensions: none.
 */
public final class GlStrings {
    public static final int GL_VENDOR = 0x1F00, GL_RENDERER = 0x1F01, GL_VERSION = 0x1F02, GL_EXTENSIONS = 0x1F03,
            GL_SHADING_LANGUAGE_VERSION = 0x8B8C;

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

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
        logOnce("glGetString(0x" + Integer.toHexString(name) + ")", value);
        return value;
    }

    /** One INFO line per distinct call and calling class, so the GL shim's users show up in latest.log. */
    public static void logOnce(String call, Object answer) {
        String caller = StackWalker.getInstance().walk(s -> s
                .map(f -> f.getClassName())
                .filter(c -> !c.startsWith("dev.elemore.iroh.") && !c.startsWith("org.lwjgl."))
                .findFirst().orElse("?"));
        if (LOGGED.add(call + "@" + caller))
            Initializer.LOGGER.info("Iroh GL shim: {} from {} -> {}", call, caller, answer);
    }
}
