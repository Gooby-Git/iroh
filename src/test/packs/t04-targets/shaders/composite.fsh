#version 430 compatibility
/*
const int colortex0Format = RGBA16F;
const int colortex18Format = R8;
const bool colortex3Clear = false;
const vec4 colortex3ClearColor = vec4(0.0, 0.0, 0.0, 1.0);
*/
const int colortex5Format = RGB10_A2;
const int shadowMapResolution = 2048; // [1024 2048]
const float sunPathRotation = -40.0;
in vec2 uv;
uniform sampler2D colortex0;
layout(location = 0) out vec4 outA;
layout(location = 1) out vec4 outB;
/* RENDERTARGETS: 3,18 */
void main() { outA = texture(colortex0, uv); outB = vec4(1.0); }
