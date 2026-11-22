package dev.elemore.iroh.api;

/**
 * api 4: Iroh's chunk renderer applied a built 16^3 section (its draw data is live from the next draw) or reset one
 * (moved out of range / world change). Render thread, during the frame's section intake: keep it cheap.
 * Section coordinates (block >> 4).
 */
public interface SectionListener {
    void sectionBuilt(int sx, int sy, int sz, boolean empty);

    default void sectionReset(int sx, int sy, int sz) {}
}
