package dev.elemore.iroh.apiimpl;

import dev.elemore.iroh.api.HeldLightProvider;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.vulkanmod.Initializer;

import java.util.Arrays;

/**
 * Iroh internal (P1-6b): registered held-light providers. Stored now; read by the PACK path's held-light uniforms
 * (Phase 3) and the RT path (Phase 5+) through {@link #lightSource}. The RASTER path has no dynamic held light.
 */
public final class HeldLights {
    private static volatile HeldLightProvider[] providers = new HeldLightProvider[0];

    private HeldLights() {
    }

    public static synchronized void register(HeldLightProvider provider) {
        if (provider == null)
            throw new NullPointerException("provider");
        for (HeldLightProvider p : providers)
            if (p == provider)
                return;
        HeldLightProvider[] next = Arrays.copyOf(providers, providers.length + 1);
        next[next.length - 1] = provider;
        providers = next;
        Initializer.LOGGER.info("Iroh api: held-light provider registered: {} (no effect on the RASTER path)", provider.getClass().getName());
    }

    /** The stack whose light this hand gives off. For the PACK / RT held-light code. Render thread. */
    public static ItemStack lightSource(Player player, InteractionHand hand, ItemStack inHand) {
        ItemStack s = inHand;
        for (HeldLightProvider p : providers) {
            try {
                ItemStack r = p.lightSource(player, hand, s);
                if (r != null)
                    s = r;
            } catch (RuntimeException e) {
                Initializer.LOGGER.error("Iroh api: held-light provider {} threw", p.getClass().getName(), e);
            }
        }
        return s;
    }

    public static int count() {
        return providers.length;
    }
}
