#version 460
// Iroh RT core (P5-0): packed G-buffer, one RGBA32UI target + depth.
//   x = octahedral normal (unorm16x2), y = albedo rgb8 | tile id << 24, z = viewZ (float bits, 0 = sky),
//   w = motion vector half2 in pixels, previous - current (NRD wants uv units: scale 1/size; FSR: pixels).
#include "rt_frame.glsl"
layout(std430, set = 0, binding = 2) readonly buffer Atlas { uint atlas[]; };
layout(std430, set = 0, binding = 3) readonly buffer Mats { vec4 mats[]; };
layout(std430, set = 0, binding = 4) readonly buffer FrameB { Frame fr; };
layout(location = 0) in vec3 wpos;
layout(location = 1) in vec2 uv;
layout(location = 2) flat in uint tint;
layout(location = 3) in vec4 prevClip;
layout(location = 0) out uvec4 o;
vec2 octWrap(vec2 v) { return (1.0 - abs(v.yx)) * vec2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0); }
vec2 octEncode(vec3 n) { n /= abs(n.x) + abs(n.y) + abs(n.z); vec2 e = n.z >= 0.0 ? n.xy : octWrap(n.xy); return e * 0.5 + 0.5; }
void main() {
    vec3 n = normalize(cross(dFdx(wpos), dFdy(wpos)));   // flat quads: exact face normal, no normal in the 16-byte vertex
    if (dot(n, fr.camPos.xyz - wpos) < 0.0) n = -n;
    int tile = int(uv.x * 4.0) + 4 * int(uv.y * 4.0);
    ivec2 t0 = ivec2(tile % 4, tile / 4) * 16;
    ivec2 p = clamp(ivec2(floor(uv * 64.0)), t0, t0 + 15);
    vec4 tex = unpackUnorm4x8(atlas[p.y * 64 + p.x]);
    if (mats[tile * 2].y == 1.0 && tex.a < 0.5) discard;
    vec3 albedo = tex.rgb * unpackUnorm4x8(tint).rgb;
    vec2 prevPix = (prevClip.xy / prevClip.w * 0.5 + 0.5) * vec2(fr.dims.xy);
    vec2 mv = prevPix - gl_FragCoord.xy;
    o = uvec4(packUnorm2x16(octEncode(n)), (packUnorm4x8(vec4(albedo, 0.0)) & 0xFFFFFFu) | (uint(tile) << 24),
              floatBitsToUint(dot(wpos - fr.camPos.xyz, fr.camFwd.xyz)), packHalf2x16(mv));
}
