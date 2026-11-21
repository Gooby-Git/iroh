package dev.elemore.iroh.api;

import java.util.List;

/**
 * Resource layout of an {@link IrohShaders} program (api 3). Bindings, fixed so the GLSL can name them:
 * binding 0 = vertex uniform block with {@code vertexUniforms} in order, binding 1 = fragment uniform block with
 * {@code fragmentUniforms} in order, bindings 2.. = {@code samplers} combined image samplers (sampler2D) in order. Uniform
 * names are Iroh's built-in suppliers, filled every draw: MVP, ModelViewMat, ProjMat (mat4); ColorModulator, FogColor
 * (vec4); FogStart, FogEnd, GameTime, LineWidth (float); ScreenSize (vec2); FogShape (int). Samplers name the texture
 * unit: "Sampler0".."Sampler7" (set per draw with {@link IrohDraw#texture}).
 */
public record ShaderLayout(List<String> vertexUniforms, List<String> fragmentUniforms, List<String> samplers) {
    public ShaderLayout {
        vertexUniforms = List.copyOf(vertexUniforms);
        fragmentUniforms = List.copyOf(fragmentUniforms);
        samplers = List.copyOf(samplers);
    }
}
