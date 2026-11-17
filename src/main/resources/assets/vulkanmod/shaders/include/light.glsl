//light.glsl
//#pragma once
const float MINECRAFT_LIGHT_POWER = (0.6);
const float MINECRAFT_AMBIENT_LIGHT = (0.4);

vec4 minecraft_mix_light(vec3 lightDir0, vec3 lightDir1, vec3 normal, vec4 color) {
    lightDir0 = normalize(lightDir0);
    lightDir1 = normalize(lightDir1);
    float light0 = max(0.0, dot(lightDir0, normal));
    float light1 = max(0.0, dot(lightDir1, normal));
    float lightAccum = min(1.0, fma((light0 + light1), MINECRAFT_LIGHT_POWER, MINECRAFT_AMBIENT_LIGHT));
    return vec4(color.rgb * lightAccum, color.a);
}

// Vanilla 1.21.1 terrain lookup: filtered texture() at uv/256 clamped to the texel centres of the 16x16 lightmap.
// Light level L (uv = 16 * L) lands on the boundary of texels L-1 and L (a 50/50 blend); level 0 clamps to the centre
// of texel 0. P1-8b: texelFetch (texel centre) was 11% too bright.
vec4 minecraft_sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp(vec2(uv) / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

vec4 sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texelFetch(lightMap, bitfieldExtract(uv, 4, 8), 0);
}

vec4 sample_lightmap2(sampler2D lightMap, uint uv) {
    // Compressed terrain vertex: block light level in bits 4..7, sky light level in bits 12..15 (vanilla uv = 16 * level).
    // Truncating to whole levels matches OpenGL better than the full 0..255 value: Iroh's Sodium-style smooth lighting
    // blends to fractional levels that vanilla's blend does not produce (P1-8b: calib-day grass +6% with 8 bits).
    const ivec2 lm = ivec2(bitfieldExtract(uv, 4, 4), bitfieldExtract(uv, 12, 4)) * 16;
    return minecraft_sample_lightmap(lightMap, lm);
}
