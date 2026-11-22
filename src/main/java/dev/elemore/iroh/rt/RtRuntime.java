package dev.elemore.iroh.rt;

import java.util.function.Consumer;

/**
 * The RT core's single entry for the game. {@link #create} returns {@link #INERT} unless {@link RtSettings#active}
 * holds: the inert runtime requests no device features, registers nothing, allocates nothing and does no per-frame
 * work (RtSeamTest). The active runtime owns the BLAS queue (fed by the game's section events) and later the passes.
 * P5-0 builds the seam only; the passes run offline (rtReplay).
 */
public final class RtRuntime {
    public static final RtRuntime INERT = new RtRuntime(null, null, null);

    private final RtSettings settings;
    private final BlasBuildQueue queue;
    private final RtMemory memory;
    private long frames;

    private RtRuntime(RtSettings settings, BlasBuildQueue queue, RtMemory memory) {
        this.settings = settings;
        this.queue = queue;
        this.memory = memory;
    }

    /**
     * @param registrar how the game subscribes a feed to its section events (in game: an api-4 SectionListener adapter);
     *                  called only when RT is active
     */
    public static RtRuntime create(RtSettings settings, RtCapabilities caps, RtMemory memory, RtMemoryModel model,
                                   Consumer<RtGeometryFeed> registrar) {
        if (settings == null || !settings.active(caps)) return INERT;
        BlasBuildQueue q = new BlasBuildQueue(memory, model);
        registrar.accept(q);
        return new RtRuntime(settings, q, memory);
    }

    public boolean active() {
        return this != INERT;
    }

    /** Device extensions the RT core needs at device creation; empty when inert (nothing is requested). */
    public String[] deviceExtensions() {
        if (!active()) return new String[0];
        return new String[]{"VK_KHR_acceleration_structure", "VK_KHR_ray_query", "VK_KHR_deferred_host_operations"};
    }

    public RtGeometryFeed feed() {
        return active() ? queue : RtGeometryFeed.NONE;
    }

    /** Render thread, once per frame: camera section, then this frame's BLAS builds (none when inert). */
    public int frame(int camSx, int camSy, int camSz) {
        if (!active()) return 0;
        frames++;
        queue.camera(camSx, camSy, camSz);
        return queue.take().size();
    }

    public RtSettings settings() {
        return settings;
    }

    public long frames() {
        return frames;
    }

    public RtMemory memory() {
        return memory;
    }
}
