package dev.elemore.iroh.api;

/**
 * api 4: the fog the terrain shader drew with this frame (after vanilla setupFog, mod fog and Iroh's distance-fog
 * option, i.e. FogDecision). {@code shape}: 0 sphere, 1 cylinder (vanilla FogShape index). Start = end = 3.4e38 when
 * the distance fog is off.
 */
public record FogState(float start, float end, int shape, float red, float green, float blue, float alpha) {}
