package dev.elemore.iroh.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lets a mod hide a block face the chunk mesher would otherwise draw (the role Sodium's
 * {@code BlockOcclusionCache.shouldDrawSide} played for mixins). See {@link IrohApi#registerFaceCullVeto}.
 * <p>
 * Called only for faces vanilla's own rule ({@code Block.shouldRenderFace}: {@code skipRendering}, then occlusion
 * shapes) left visible, and only by the terrain mesher (not for items, falling blocks or block entities). It can only
 * hide, never force a face back on.
 * <p>
 * <b>Threading:</b> runs on chunk meshing worker threads, several at once, while the render thread draws. Read only
 * {@code level} (a snapshot of the section plus a 2-block border; anything further out reads as air,
 * as in Sodium's {@code LevelSlice}), never {@code Minecraft.level}, and keep no unsynchronised mutable state. Must be fast: it runs for every
 * visible face of every block that reaches it, so return {@code false} on the first cheap check that rules a block out.
 */
@FunctionalInterface
public interface FaceCullVeto {
    /**
     * @param state the block being meshed
     * @param level the meshing snapshot (not the live level)
     * @param pos   the block's position; shared and mutable on some paths, copy it with {@code immutable()} to keep it
     * @param face  the face about to be drawn
     * @return true to hide this face
     */
    boolean hideFace(BlockState state, BlockGetter level, BlockPos pos, Direction face);
}
