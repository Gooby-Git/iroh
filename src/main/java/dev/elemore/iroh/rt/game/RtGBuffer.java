package dev.elemore.iroh.rt.game;

/**
 * P5-2: the RT G-buffer attachment (decision in docs/P5-DESIGN.md section 13: MRT, not depth reconstruction). One extra
 * colour attachment of the main world pass in RT mode, R32G32B32A32_UINT, exactly the layout rtReplay proves:
 * x = octahedral normal unorm16x2, y = albedo sRGB rgb8 | material/tile id 6 bits << 24 | sky-open bit 30,
 * z = viewZ float bits (0 = sky), w = motion half2 (pixels, previous minus current, UNJITTERED).
 * <p>
 * Coexistence (RtLookArbiter): it is created at RENDER size next to render scale's world images (P4-1a/INT-2 R phase),
 * so it composes with render scale and FSR; it never exists in the pack look (the pack's MRT world pass W and RT are
 * mutually exclusive by the Look switch, so MainPassArbiter's R+W rule never meets RT). Attachment index: after the
 * main colour (index 1), the same slot P3-11's MRT pass uses for pack colortex1, which is free because W is off in RT.
 */
public final class RtGBuffer {
    private RtGBuffer() {}

    /** VK_FORMAT_R32G32B32A32_UINT */
    public static final int FORMAT = 107;
    public static final int BYTES_PER_PIXEL = 16;
    public static final int ATTACHMENT_INDEX = 1;

    public static long bytes(int w, int h) {
        return (long) w * h * BYTES_PER_PIXEL;
    }
}
