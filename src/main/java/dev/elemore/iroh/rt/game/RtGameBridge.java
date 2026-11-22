package dev.elemore.iroh.rt.game;

import dev.elemore.iroh.api.SectionListener;
import dev.elemore.iroh.rt.RtGeometryFeed;
import dev.elemore.iroh.rt.RtRuntime;

import java.util.function.Consumer;

/**
 * Game side of the RT seam (P5-0: STUB, not called from anywhere yet). When wired (card P5-1), Iroh's renderer calls
 * {@link #install} once after device creation with {@code IrohApi::addSectionListener}; when the runtime is inert this
 * registers nothing. The listener only forwards events; filling {@link dev.elemore.iroh.rt.SectionGeometry} from
 * VulkanMod's DrawBuffers/AreaBuffer (device address, segment offset, quads per bucket, emissive quads) is P5-1's job:
 * until then built sections are reported as resets so nothing stale can be traced.
 */
public final class RtGameBridge implements SectionListener {
    private final RtGeometryFeed feed;

    private RtGameBridge(RtGeometryFeed feed) {
        this.feed = feed;
    }

    /** Registers a listener through {@code addSectionListener} only when {@code runtime} is active. Returns it or null. */
    public static RtGameBridge install(RtRuntime runtime, Consumer<SectionListener> addSectionListener) {
        if (!runtime.active()) return null;
        RtGameBridge b = new RtGameBridge(runtime.feed());
        addSectionListener.accept(b);
        return b;
    }

    @Override
    public void sectionBuilt(int sx, int sy, int sz, boolean empty) {
        feed.sectionReset(sx, sy, sz); // P5-1 replaces this with a SectionGeometry built from the section's draw data
    }

    @Override
    public void sectionReset(int sx, int sy, int sz) {
        feed.sectionReset(sx, sy, sz);
    }
}
