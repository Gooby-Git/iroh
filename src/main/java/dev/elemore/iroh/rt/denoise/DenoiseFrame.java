package dev.elemore.iroh.rt.denoise;

import dev.elemore.iroh.upscale.ImageRef;

import java.util.EnumMap;
import java.util.Map;

/**
 * One denoise call: records into {@link #commandBuffer} and reads/writes {@link #images}. Contract for ALL images: layout
 * GENERAL on entry (and on exit), usage STORAGE|SAMPLED, no queue-family transfer. {@link #identifiers} selects which of the
 * instance's denoisers run (indices into the creation list).
 */
public final class DenoiseFrame {
    public long commandBuffer;
    public final Map<Slot, ImageRef> images = new EnumMap<>(Slot.class);
    public FrameSettings settings = new FrameSettings();
    public int[] identifiers = {0};
}
