package dev.elemore.iroh.shader;

/**
 * Where shader text comes from. At runtime this wraps the game's {@code ResourceProvider}; the offline
 * harness ({@code src/test/.../ShaderHarness}) wraps the jars of a mod folder. Both feed the same
 * {@link ShaderConversion} code, so the harness tests exactly what the game runs.
 */
public interface ShaderSources {

    /**
     * @param id resource id {@code namespace:path}, e.g. {@code charta:shaders/include/palette.glsl}
     * @return the file as UTF-8 text, or null if no pack provides it
     */
    String read(String id);

    /** @return the pack (mod id, "vanilla", "file/x.zip") that provides {@code id}, or "?" */
    default String packOf(String id) {
        return "?";
    }
}
