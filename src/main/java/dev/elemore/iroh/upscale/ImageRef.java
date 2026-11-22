package dev.elemore.iroh.upscale;

/** A Vulkan image the upscaler reads or writes. Raw handles (LWJGL {@code long}) keep this package free of LWJGL types. */
public record ImageRef(long image, int format, int width, int height, int usage, int layout) {
    public static final ImageRef NONE = new ImageRef(0, 0, 0, 0, 0, 0);
    /** VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL / VK_IMAGE_LAYOUT_GENERAL: the only layouts FSR accepts (see fsr.md A.3). */
    public static final int LAYOUT_SHADER_READ_ONLY = 5, LAYOUT_GENERAL = 1;

    public boolean present() {
        return image != 0;
    }
}
