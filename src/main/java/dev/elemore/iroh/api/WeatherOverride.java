package dev.elemore.iroh.api;

/** See {@link IrohSky#overrideWeather}. Render thread, once per frame where vanilla draws rain and snow. */
@FunctionalInterface
public interface WeatherOverride {
    /** Draw the weather and return true to claim the stage (vanilla rain / snow are then skipped), or false. */
    boolean draw(FrameContext context);
}
