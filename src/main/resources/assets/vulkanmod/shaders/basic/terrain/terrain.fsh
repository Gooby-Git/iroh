#version 450

#include "light.glsl"
#include "fog.glsl"

layout(binding = 2) uniform sampler2D Sampler0;

layout(binding = 1) uniform UBO {
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    float AlphaCutout;
    float MipClamp;   // FSR-Q67: -(world texture LOD bias) in the alpha-tested pass (0.5), else 0; cancels the sampler bias for foliage edges
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float vertexDistance;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    // leaves, grass and fences share this pass with stone (uniqueOpaqueLayer): a texel that is not fully opaque is an alpha edge,
    // sampled again at the unbiased LOD so the negative upscaler bias does not make foliage edges shimmer
    if (MipClamp != 0.0 && color.a < 0.999)
        color = texture(Sampler0, texCoord0, MipClamp);
    color *= vertexColor;
    if (color.a < AlphaCutout) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
