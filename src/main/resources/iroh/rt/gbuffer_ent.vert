#version 460
// Iroh RT core (P5-3): raster G-buffer for POSED entity model parts (same 16-byte vertex as terrain, part-local positions).
// gl_InstanceIndex = TLAS-style part instance -> current and PREVIOUS row-major 3x4 pose (6 vec4). The motion vector of an
// entity pixel is the vertex projected through the previous pose with the previous camera minus the current: object motion
// plus camera motion, so the denoiser and FSR reproject a walking mob's own pixels onto its previous position.
// Shares gbuffer.frag (normal from derivatives: exact for flat faces in any pose).
#include "rt_frame.glsl"
layout(std430, set = 0, binding = 0) readonly buffer Verts { uvec4 verts[]; };
layout(std430, set = 0, binding = 1) readonly buffer Poses { vec4 poses[]; };
layout(std430, set = 0, binding = 4) readonly buffer FrameB { Frame fr; };
layout(location = 0) out vec3 wpos;
layout(location = 1) out vec2 uv;
layout(location = 2) flat out uint tint;
layout(location = 3) out vec4 prevClip;
layout(location = 4) out vec4 curClip;
layout(location = 5) flat out uint light;
vec3 xf(uint base, vec3 p) { return vec3(dot(poses[base], vec4(p, 1.0)), dot(poses[base + 1u], vec4(p, 1.0)), dot(poses[base + 2u], vec4(p, 1.0))); }
void main() {
    uvec4 v = verts[gl_VertexIndex];
    vec3 l = vec3(ivec3(int(v.x << 16) >> 16, int(v.x) >> 16, int(v.y << 16) >> 16)) * (1.0 / 2048.0) + 4.0;
    uint b = uint(gl_InstanceIndex) * 6u;
    vec3 p = xf(b, l);
    wpos = p;
    uv = vec2(float(v.z & 0xFFFFu), float(v.z >> 16)) * (1.0 / 32768.0);
    tint = v.w;
    light = v.y >> 16;
    curClip = fr.viewProj * vec4(p, 1.0);
    gl_Position = curClip + vec4(fr.jitter.xy * curClip.w, 0.0, 0.0);
    prevClip = fr.prevViewProj * vec4(xf(b + 3u, l), 1.0);
}
