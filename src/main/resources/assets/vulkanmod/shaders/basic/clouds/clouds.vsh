#version 450

#include "fog.glsl"

// Iroh (rc3, td-429512): vanilla rendertype_clouds' fog distance: fog_distance(view-space position, FogShape). The base
// used length(Position + ModelOffset) with no Y in ModelOffset (horizontal distance only).
layout(binding = 0) uniform UniformBufferObject {
   mat4 MVP;
   mat4 ModelViewMat;
   int FogShape;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out float vertexDistance;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);
    vertexDistance = fog_distance((ModelViewMat * vec4(Position, 1.0)).xyz, FogShape);

    vertexColor = Color;
}
