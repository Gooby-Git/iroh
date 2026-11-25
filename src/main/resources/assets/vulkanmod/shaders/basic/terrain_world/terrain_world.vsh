#version 460

#include "light.glsl"
#include "fog.glsl"

layout (binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    int FogShape;
};

layout (push_constant) uniform pushConstant {
    vec3 ModelOffset;
};

layout (binding = 3) uniform sampler2D Sampler2;


layout (location = 0) out vec4 vertexColor;
layout (location = 1) out vec2 texCoord0;
layout (location = 2) out float vertexDistance;

#define COMPRESSED_VERTEX

#ifdef COMPRESSED_VERTEX
    layout (location = 0) in ivec4 Position;
    layout (location = 1) in uvec2 UV0;
    layout (location = 2) in uint PackedColor;
#else
    layout (location = 0) in vec3 Position;
    layout (location = 1) in vec4 Color;
    layout (location = 2) in vec2 UV0;
    layout (location = 3) in ivec2 UV2;
    layout (location = 4) in vec3 Normal;
#endif

const float UV_INV = 1.0 / 32768.0;
const vec3 POSITION_INV = vec3(1.0 / 2048.0);
const vec3 POSITION_OFFSET = vec3(4.0);

// Iroh P2-7a (L5a, iroh.terrain.mdi): world-arena terrain. firstInstance = the section's offset from a per-frame origin
// section in SECTION units, 8 signed bits per axis (x | z << 8 | y << 16; TerrainCommandWriter.encode); ModelOffset =
// origin * 16 + POS_OFFSET - camera. terrain.vsh decodes unsigned block offsets inside a chunk area instead.
vec4 getVertexPosition() {
    const vec3 baseOffset = vec3(bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8) * 16);

    #ifdef COMPRESSED_VERTEX
        return vec4(fma(Position.xyz, POSITION_INV, ModelOffset + baseOffset), 1.0);
    #else
        return vec4(Position.xyz + baseOffset, 1.0);
    #endif
}

void main() {
    const vec4 pos = getVertexPosition();
    gl_Position = MVP * pos;

    vertexDistance = fog_distance(pos.xyz, FogShape);
    const vec4 Color = unpackUnorm4x8(PackedColor);
    vertexColor = Color * sample_lightmap2(Sampler2, Position.a);

    texCoord0 = UV0 * UV_INV;
}