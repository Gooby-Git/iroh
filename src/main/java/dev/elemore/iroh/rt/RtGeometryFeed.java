package dev.elemore.iroh.rt;

/**
 * Geometry from the game into the RT core. Called on the render thread at section intake (api-4 SectionListener
 * timing: the section's draw data is live from the next draw). Must stay cheap: implementations enqueue only.
 */
public interface RtGeometryFeed {
    void sectionBuilt(SectionGeometry g);

    void sectionReset(int sx, int sy, int sz);

    /** World unload / dimension change: drop everything. */
    void clear();

    RtGeometryFeed NONE = new RtGeometryFeed() {
        @Override public void sectionBuilt(SectionGeometry g) {}
        @Override public void sectionReset(int sx, int sy, int sz) {}
        @Override public void clear() {}
    };
}
