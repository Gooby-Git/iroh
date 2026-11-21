package dev.elemore.iroh.chunk;

import dev.elemore.iroh.config.IrohOptions.DeferMode;

/**
 * Which chunk rebuilds the player must not see late, and how much waiting Iroh allows for them (P1-11 item 1).
 * Pure decisions, no Minecraft state, so a headless test can pin them down.
 *
 * <h3>What the modes mean for the player</h3>
 * Vanilla's "Prioritize Chunk Updates" ({@code PrioritizeChunkUpdates}, {@code LevelRenderer.compileSections}) picks
 * the dirty sections that are rebuilt synchronously on the render thread inside the frame, so no hole or stale block
 * is ever drawn: NONE = none (everything in the background), PLAYER_AFFECTED = sections marked dirty by a block
 * change the player's own client made (flag 8, {@code LevelRenderer.blockChanged}, applied to the 3x3x3 section
 * neighbourhood of the block), NEARBY = those plus every dirty section whose centre is within sqrt(768), about 27.7
 * blocks, of the camera block. Iroh's base has no synchronous path; its builder pool is always background (Sodium's
 * public option "chunk_build_defer_mode" names the same idea ALWAYS / ONE_FRAME / ZERO_FRAMES).
 * <ul>
 * <li>ALWAYS: nothing waits. A changed block is shown stale for as long as its rebuild takes plus the upload (usually
 *     one to two frames). Never stalls.</li>
 * <li>ONE_FRAME: an important rebuild is queued in the frame that notices the change and must be finished and
 *     uploaded before the next frame is presented. The frame that noticed it still shows the old block (at most one
 *     frame), the next one never does; the builder has a whole frame to finish, so the wait is usually zero.</li>
 * <li>ZERO_FRAMES: the render thread waits for important rebuilds right after queueing them and uploads them before
 *     the current frame is drawn. The block changes in the very frame it was noticed; the price is a stall of one
 *     section build (a few ms).</li>
 * </ul>
 * Which sections are important: with ONE_FRAME and ZERO_FRAMES those dirty from a player change, plus (only if
 * vanilla's option is NEARBY) every dirty section within the vanilla radius. So the defer mode is the master and
 * vanilla's option only widens the rule: under ALWAYS vanilla's option has no effect (the screen greys it and says
 * so); under the two waiting modes NONE and PLAYER_AFFECTED are the same, because player changes are the floor.
 * Stall guard: at most {@link #MAX_SYNC_SECTIONS} sections are waited for per frame and the wait stops after the
 * time budget; everything over the cap or the budget carries on in the background.
 */
public final class ChunkDeferPolicy {
    private ChunkDeferPolicy() {}

    /** Vanilla: {@code blockPos2.distSqr(cameraBlock) < 768.0} with blockPos2 = section origin + (8, 8, 8). */
    public static final double NEARBY_DIST_SQR = 768.0;

    /** Most sections one frame waits for (a block at a section corner dirties up to 8, a bulk edit far more). */
    public static final int MAX_SYNC_SECTIONS = 12;

    /** ZERO_FRAMES: longest the render thread waits inside the frame. */
    public static final long ZERO_FRAME_BUDGET_NS = 8_000_000L;

    /** ONE_FRAME: longest the render thread waits at the start of the next frame (the builders already had a frame). */
    public static final long ONE_FRAME_BUDGET_NS = 6_000_000L;

    /**
     * @param mode          Iroh's defer mode
     * @param nearbyRule    vanilla's Prioritize Chunk Updates is NEARBY
     * @param dirtyFromPlayer the section was dirtied by the local player's block change ({@code isDirtyFromPlayer})
     * @param distSqrToCamera squared distance from the section centre (origin + 8) to the camera block
     */
    public static boolean isImportant(DeferMode mode, boolean nearbyRule, boolean dirtyFromPlayer, double distSqrToCamera) {
        if (mode == DeferMode.ALWAYS)
            return false;
        return dirtyFromPlayer || (nearbyRule && distSqrToCamera < NEARBY_DIST_SQR);
    }

    /** Wait budget per frame for a mode, 0 when the mode never waits. */
    public static long budgetNs(DeferMode mode) {
        return switch (mode) {
            case ALWAYS -> 0L;
            case ONE_FRAME -> ONE_FRAME_BUDGET_NS;
            case ZERO_FRAMES -> ZERO_FRAME_BUDGET_NS;
        };
    }
}
