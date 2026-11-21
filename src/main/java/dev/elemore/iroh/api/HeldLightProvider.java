package dev.elemore.iroh.api;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Chooses which item a hand's dynamic "held light" is computed from (what Iris's {@code heldBlockLightValue} /
 * {@code heldBlockLightValue2} uniforms and their colours read). Lets a mod make an item worn in a non-hand slot (a
 * Trinkets lantern) light the world as if held.
 * <p>
 * <b>Effect:</b> only on the PACK and RT render paths (Phase 3+), where Iroh computes held light. The RASTER path, like
 * vanilla and like the OpenGL pack with shaders off, has no dynamic held light, so providers change nothing there.
 * <p>
 * <b>Threading:</b> render thread, once per hand per frame. Keep it cheap.
 */
@FunctionalInterface
public interface HeldLightProvider {
    /**
     * @param player the local player
     * @param hand   which hand's light is being computed
     * @param inHand the stack really in that hand (may be empty)
     * @return the stack to compute this hand's light from; return {@code inHand} to leave it alone. Providers run in
     * registration order, each seeing the previous one's result.
     */
    ItemStack lightSource(Player player, InteractionHand hand, ItemStack inHand);
}
