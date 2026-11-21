#version 450

#include "fog.glsl"

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in float vertexDistance;

layout(location = 0) out vec4 fragColor;

// Iroh (rc3, td-429512): vanilla rendertype_clouds: rgb mixed toward the fog colour, alpha kept. The base faded ALPHA to
// zero past FogEnd, so clouds farther than the terrain fog (every cloud seen from a low camera) vanished, in Fancy and
// in Fabulous alike, where vanilla draws them fog-coloured at their normal alpha.
void main() {
    vec4 color = vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
