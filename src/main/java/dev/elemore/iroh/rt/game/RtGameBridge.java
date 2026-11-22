package dev.elemore.iroh.rt.game;

import dev.elemore.iroh.api.SectionListener;
import dev.elemore.iroh.rt.RtGeometryFeed;
import dev.elemore.iroh.rt.RtRuntime;
import dev.elemore.iroh.rt.SectionGeometry;

import java.util.function.Consumer;

/**
 * Game side of the RT seam: the api-4 SectionListener (registered by {@link RtFrameHooks} only while RT runs; never when
 * inert). P5-2: a built section is described by its quad counts per bucket from the live draw parameters (cheap) and
 * queued; WHERE the quads live is read again at build time through {@link RtSectionSource} (AreaBuffer reallocation
 * moves segments between the event and the build). Empty or vanished sections are resets.
 */
public final class RtGameBridge implements SectionListener {
    private final RtGeometryFeed feed;
    private final RtSectionSource source;
    private long version;

    public RtGameBridge(RtGeometryFeed feed, RtSectionSource source) {
        this.feed = feed;
        this.source = source;
    }

    /** P5-0 entry kept for RtSeamTest: registers only when {@code runtime} is active. Returns it or null. */
    public static RtGameBridge install(RtRuntime runtime, Consumer<SectionListener> addSectionListener) {
        if (!runtime.active()) return null;
        RtGameBridge b = new RtGameBridge(runtime.feed(), RtSectionSource.NONE);
        addSectionListener.accept(b);
        return b;
    }

    @Override
    public void sectionBuilt(int sx, int sy, int sz, boolean empty) {
        int[] q = empty ? null : source.quads(sx, sy, sz);
        if (q == null || q[0] + q[1] + q[2] == 0) {
            feed.sectionReset(sx, sy, sz);
            return;
        }
        feed.sectionBuilt(new SectionGeometry(sx, sy, sz, 0, 0, 16, q, new long[3], new float[0], ++version));
    }

    @Override
    public void sectionReset(int sx, int sy, int sz) {
        feed.sectionReset(sx, sy, sz);
    }
}
