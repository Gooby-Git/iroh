#version 460
// Iroh RT core (P5-0): raster G-buffer from the terrain vertex arena (16-byte compressed vertices, vertex pulling).
// gl_InstanceIndex = section index (firstInstance of the indirect command) -> section origin.
#include "rt_frame.glsl"
layout(std430, set = 0, binding = 0) readonly buffer Verts { uvec4 verts[]; };
layout(std430, set = 0, binding = 1) readonly buffer Origins { vec4 origins[]; };
layout(std430, set = 0, binding = 4) readonly buffer FrameB { Frame fr; };
layout(location = 0) out vec3 wpos;
layout(location = 1) out vec2 uv;
layout(location = 2) flat out uint tint;
layout(location = 3) out vec4 prevClip;
layout(location = 4) out vec4 curClip;
layout(location = 5) flat out uint light;
void main() {
    uvec4 v = verts[gl_VertexIndex];
    vec3 p = vec3(ivec3(int(v.x << 16) >> 16, int(v.x) >> 16, int(v.y << 16) >> 16)) * (1.0 / 2048.0) + 4.0 + origins[gl_InstanceIndex].xyz;
    wpos = p;
    uv = vec2(float(v.z & 0xFFFFu), float(v.z >> 16)) * (1.0 / 32768.0);
    tint = v.w;
    light = v.y >> 16;
    curClip = fr.viewProj * vec4(p, 1.0);                       // unjittered: motion vectors
    gl_Position = curClip + vec4(fr.jitter.xy * curClip.w, 0.0, 0.0);   // jittered raster (upscaler sequence)
    prevClip = fr.prevViewProj * vec4(p, 1.0);
}
