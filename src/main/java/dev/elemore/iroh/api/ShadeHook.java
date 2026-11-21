package dev.elemore.iroh.api;

import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lets a mod choose WHERE the chunk mesher samples light for a quad, without changing where the quad is drawn. The role
 * a mixin on Sodium's {@code AbstractBlockRenderContext.shadeQuad} played for Polaris (slopes lit at their block face,
 * not at the depth their corners sagged to). See {@link IrohApi#registerShadeHook}.
 * <p>
 * <b>When:</b> once per quad, in the terrain mesher only, after the quad passed the model's transforms and face
 * culling, immediately before Iroh computes its smooth or flat light (ambient occlusion + lightmap). The hook may move
 * vertices with {@code quad.pos(...)}; Iroh restores all four positions as soon as the light is computed, so the quad is
 * drawn with its real geometry. Colour, UV, normals, material and tag must not be changed here.
 * <p>
 * <b>Threading:</b> meshing worker threads, several at once. No shared mutable state without care (a thread local is
 * the usual way to know which block is being emitted).
 * <p>
 * <b>Cost:</b> it runs for every quad of every block while registered, so the first check must rule out the quads it
 * does not care about (Polaris: one thread-local read).
 */
@FunctionalInterface
public interface ShadeHook {
    /**
     * @param state the block being meshed
     * @param pos   its position (shared and mutable: copy to keep)
     * @param quad  the quad about to be lit; positions may be moved for lighting only
     * @return true if any vertex was moved (only a hint; Iroh restores positions either way)
     */
    boolean beforeShade(BlockState state, BlockPos pos, MutableQuadView quad);
}
