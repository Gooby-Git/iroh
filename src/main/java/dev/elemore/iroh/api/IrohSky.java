package dev.elemore.iroh.api;

import dev.elemore.iroh.apiimpl.SkyHooks;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.function.Predicate;

/**
 * Sky hooks (api 3, P1-13a): our mods' native replacement for HEAD-cancel mixins into {@code LevelRenderer.renderClouds}
 * and {@code renderSnowAndRain}, which only worked by mixin priority ordering (Iroh's own cloud mixin runs at 1500 so
 * that third-party mods at 1000 still go first; that rule stays for them).
 * <p>
 * Where they run: inside Iroh's cloud / weather points of the frame (render thread). Order: suppressors first (any true:
 * nothing is drawn), then overrides in ascending priority, first {@code true} claims the stage, else Iroh draws its own
 * clouds / vanilla weather. Cost with nothing registered: one array-length test per point. The PACK and RT paths
 * (Phases 3, 5, 7) must honour the same registrations (knowledge/api.md "Clouds" obligation).
 */
public final class IrohSky {
    private IrohSky() {
    }

    /** No clouds while {@code suppressed} tests true for the current level (asked once per frame; Outside of Time). */
    public static IrohFrame.Registration setCloudsSuppressed(Predicate<ClientLevel> suppressed) {
        return SkyHooks.addCloudSuppressor(suppressed);
    }

    /** Draw clouds instead of Iroh (Tempest's deck, Phase 7). Hook shape only in Phase 1: the default is Iroh's clouds. */
    public static IrohFrame.Registration overrideClouds(int priority, CloudOverride override) {
        return SkyHooks.addCloudOverride(priority, override);
    }

    /** Draw rain / snow instead of vanilla (Tempest). Hook shape in Phase 1. */
    public static IrohFrame.Registration overrideWeather(int priority, WeatherOverride override) {
        return SkyHooks.addWeatherOverride(priority, override);
    }
}
